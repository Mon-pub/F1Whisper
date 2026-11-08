package ch.threema.app.linkpreview;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.text.HtmlCompat;

import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

/**
 * F1Whisper: sender-side Open Graph link-preview fetcher, ported from Signal's
 * {@code LinkPreviewRepository}/{@code LinkPreviewUtil}. ONLY the sending device runs this; the
 * preview is then transmitted E2E so the recipient never contacts the target site (see
 * {@link ch.threema.app.services.MessageService} media-send path). All fetching is hardened:
 * https-only + SSRF/redirect validation ({@link LinkPreviewValidator},
 * {@link RedirectValidationInterceptor}), 2 MB body caps, short timeouts, no caching, image
 * re-encoded (EXIF stripped) and size-capped.
 */
public final class LinkPreviewFetcher {
    private static final Logger logger = getThreemaLogger("LinkPreviewFetcher");

    private LinkPreviewFetcher() {
    }

    private static final long MAX_HTML_BYTES = 2L * 1024 * 1024;  // 2 MB
    private static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024;  // 2 MB
    private static final int MAX_FIELD_LENGTH = 500;
    private static final int MAX_IMAGE_DIMENSION = 1024;
    private static final int IMAGE_JPEG_QUALITY = 80;

    // Sites serve page-specific Open Graph tags to link-preview crawlers and often only a generic shell (or a bot
    // challenge) to browsers, so ask as a crawler first, like Signal does. One retry with a browser UA covers the
    // sites that do the opposite.
    private static final String CRAWLER_USER_AGENT = "WhatsApp/2";
    private static final String BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0 Safari/537.36";
    private static final Pattern CHARSET_PATTERN = Pattern.compile(
        "charset\\s*=\\s*[\"']?\\s*([a-zA-Z0-9\\-_]+)",
        Pattern.CASE_INSENSITIVE);

    @Nullable
    private static volatile OkHttpClient client;

    private static OkHttpClient getClient() {
        OkHttpClient local = client;
        if (local == null) {
            synchronized (LinkPreviewFetcher.class) {
                local = client;
                if (local == null) {
                    // Deliberately NOT the app's cert-pinned OnPrem client: this talks to arbitrary
                    // third-party websites. No cache, no auth, short timeouts, per-hop validation.
                    local = new OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .writeTimeout(5, TimeUnit.SECONDS)
                        .callTimeout(10, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .followSslRedirects(false)
                        .cache(null)
                        .addNetworkInterceptor(new RedirectValidationInterceptor())
                        .build();
                    client = local;
                }
            }
        }
        return local;
    }

    /**
     * Fetch and parse the Open Graph preview for {@code rawUrl}. Returns {@code null} when the URL is
     * invalid, the fetch fails, or there is nothing previewable. Never throws. MUST run off the main
     * thread.
     */
    @WorkerThread
    @Nullable
    public static LinkPreviewResult fetch(@Nullable String rawUrl) {
        if (!LinkPreviewValidator.isValidPreviewUrl(rawUrl)) {
            return null;
        }
        try {
            PageAttempt attempt = fetchPage(rawUrl, CRAWLER_USER_AGENT);
            if (attempt.shouldRetryAsBrowser()) {
                logger.debug("Link preview: crawler response unusable, retrying as browser");
                attempt = fetchPage(rawUrl, BROWSER_USER_AGENT);
            }
            if (attempt.directImage != null) {
                return new LinkPreviewResult(rawUrl, null, null, attempt.directImage);
            }
            final LinkPreviewHtmlParser.Metadata metadata = attempt.metadata;
            if (metadata == null || attempt.challenge) {
                return null;
            }

            final String title = cleanField(metadata.title);
            final String description = cleanField(metadata.description);
            byte[] imageBytes = null;

            // Resolve a (possibly relative) image URL against the FINAL page URL (after redirects), then
            // re-validate + fetch.
            final String imageUrl = metadata.imageUrl != null ? decodeEntities(metadata.imageUrl).trim() : null;
            if (imageUrl != null && !imageUrl.isEmpty()) {
                final HttpUrl base = attempt.finalUrl != null ? attempt.finalUrl : HttpUrl.parse(rawUrl);
                final HttpUrl resolved = base != null ? base.resolve(imageUrl) : HttpUrl.parse(imageUrl);
                final String resolvedStr = resolved != null ? resolved.toString() : null;
                if (LinkPreviewValidator.isValidPreviewUrl(resolvedStr)) {
                    imageBytes = fetchImage(resolvedStr, attempt.userAgent);
                }
            }

            if ((title == null || title.isBlank()) && imageBytes == null) {
                return null;
            }
            return new LinkPreviewResult(rawUrl, title, description, imageBytes);
        } catch (Exception e) {
            logger.debug("Link preview fetch failed: {}", e.getMessage());
            return null;
        }
    }

    /** One page request: either a direct image, parsed HTML metadata, or a failure that may deserve a retry. */
    private static final class PageAttempt {
        final String userAgent;
        @Nullable
        HttpUrl finalUrl;
        @Nullable
        byte[] directImage;
        @Nullable
        LinkPreviewHtmlParser.Metadata metadata;
        boolean challenge;
        boolean botDenied;
        boolean image;

        PageAttempt(String userAgent) {
            this.userAgent = userAgent;
        }

        boolean shouldRetryAsBrowser() {
            if (!CRAWLER_USER_AGENT.equals(userAgent) || image) {
                return false;
            }
            return botDenied || challenge || metadata == null || !metadata.isUsable();
        }
    }

    private static PageAttempt fetchPage(String url, String userAgent) throws IOException {
        final PageAttempt attempt = new PageAttempt(userAgent);
        final Request request = new Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml")
            .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
            .build();

        try (Response response = getClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                attempt.botDenied = isBotDenialStatus(response.code());
                return attempt;
            }
            final ResponseBody body = response.body();
            if (body == null) {
                return attempt;
            }
            attempt.finalUrl = response.request().url();

            final MediaType contentType = body.contentType();
            // If the URL itself is an image, use it directly as the preview image.
            if (contentType != null && "image".equalsIgnoreCase(contentType.type())) {
                attempt.image = true;
                attempt.directImage = reencodeImage(readCapped(body.byteStream(), MAX_IMAGE_BYTES));
                return attempt;
            }

            // A huge page still has its metadata in the head: parse the capped prefix instead of failing.
            final byte[] htmlBytes = readPrefix(body.byteStream(), MAX_HTML_BYTES);
            final String html = LinkPreviewHtmlParser.dropUnfinishedTag(decodeHtml(htmlBytes, contentType));
            attempt.metadata = LinkPreviewHtmlParser.parse(html);
            final String title = attempt.metadata.title != null ? decodeEntities(attempt.metadata.title) : null;
            attempt.challenge = LinkPreviewHtmlParser.isChallengePage(html, title);
        }
        return attempt;
    }

    private static boolean isBotDenialStatus(int code) {
        return code == 403 || code == 406 || code == 429 || code == 503;
    }

    @Nullable
    private static String cleanField(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        final String cleaned = truncate(LinkPreviewHtmlParser.cleanText(decodeEntities(raw)));
        return cleaned == null || cleaned.isEmpty() ? null : cleaned;
    }

    @Nullable
    private static byte[] fetchImage(String imageUrl, String userAgent) {
        try {
            final Request request = new Request.Builder()
                .url(imageUrl)
                .header("User-Agent", userAgent)
                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                .build();
            try (Response response = getClient().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                final byte[] raw = readCapped(response.body().byteStream(), MAX_IMAGE_BYTES);
                return reencodeImage(raw);
            }
        } catch (Exception e) {
            logger.debug("Link preview image fetch failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Decode the fetched bytes, downscale to {@link #MAX_IMAGE_DIMENSION} and re-encode as JPEG. This
     * also strips any EXIF/metadata (incl. GPS) from the original og:image since we re-encode from a
     * decoded bitmap. Returns {@code null} on any decode failure.
     */
    @Nullable
    private static byte[] reencodeImage(@Nullable byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (bitmap == null) {
                return null;
            }
            final int w = bitmap.getWidth();
            final int h = bitmap.getHeight();
            if (w <= 0 || h <= 0) {
                return null;
            }
            final int largest = Math.max(w, h);
            if (largest > MAX_IMAGE_DIMENSION) {
                final float scale = (float) MAX_IMAGE_DIMENSION / (float) largest;
                final Bitmap scaled = Bitmap.createScaledBitmap(
                    bitmap, Math.round(w * scale), Math.round(h * scale), true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, baos);
            bitmap.recycle();
            return baos.toByteArray();
        } catch (Throwable t) {
            logger.debug("Link preview image decode failed: {}", t.getMessage());
            return null;
        }
    }

    private static byte[] readCapped(InputStream in, long max) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > max) {
                throw new IOException("Exceeded maximum size during read");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Read at most {@code max} bytes and stop; unlike {@link #readCapped} a longer body is not an error. */
    private static byte[] readPrefix(InputStream in, long max) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while (total < max && (read = in.read(buffer, 0, (int) Math.min(buffer.length, max - total))) != -1) {
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    private static String decodeHtml(byte[] bytes, @Nullable MediaType contentType) {
        Charset charset = null;
        if (contentType != null) {
            charset = contentType.charset();
        }
        if (charset == null) {
            // Sniff a <meta charset=...> from the first part of the document.
            final String ascii = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
            final Matcher m = CHARSET_PATTERN.matcher(ascii);
            if (m.find()) {
                try {
                    charset = Charset.forName(m.group(1));
                } catch (Exception ignored) {
                    // fall through to UTF-8
                }
            }
        }
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }
        return new String(bytes, charset);
    }

    @Nullable
    private static String truncate(@Nullable String s) {
        if (s == null) {
            return null;
        }
        final String trimmed = s.trim();
        if (trimmed.length() <= MAX_FIELD_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_FIELD_LENGTH);
    }

    private static String decodeEntities(String s) {
        if (s == null) {
            return "";
        }
        try {
            // Double-decode to handle double-encoded entities (matches Signal).
            final String once = HtmlCompat.fromHtml(s, HtmlCompat.FROM_HTML_MODE_LEGACY).toString();
            return HtmlCompat.fromHtml(once, HtmlCompat.FROM_HTML_MODE_LEGACY).toString();
        } catch (Exception e) {
            return s;
        }
    }
}
