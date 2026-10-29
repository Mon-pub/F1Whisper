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

    // A neutral desktop UA: many sites only emit Open Graph tags for "browser" user agents.
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0 Safari/537.36";

    private static final Pattern OG_TAG_PATTERN = Pattern.compile(
        "<\\s*meta[^>]*property\\s*=\\s*[\"']\\s*og:([^\"']+)[\"'][^>]*>",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_PATTERN = Pattern.compile(
        "content\\s*=\\s*[\"']([^\"']*)[\"']",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_PATTERN = Pattern.compile(
        "<\\s*title[^>]*>(.*?)<\\s*/\\s*title\\s*>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
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
            final Request request = new Request.Builder()
                .url(rawUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                .build();

            String title = null;
            String description = null;
            String imageUrl = null;
            byte[] imageBytes = null;

            try (Response response = getClient().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }
                final ResponseBody body = response.body();
                if (body == null) {
                    return null;
                }

                final MediaType contentType = body.contentType();
                // If the URL itself is an image, use it directly as the preview image.
                if (contentType != null && "image".equalsIgnoreCase(contentType.type())) {
                    final byte[] raw = readCapped(body.byteStream(), MAX_IMAGE_BYTES);
                    imageBytes = reencodeImage(raw);
                    return imageBytes != null
                        ? new LinkPreviewResult(rawUrl, null, null, imageBytes)
                        : null;
                }

                final byte[] htmlBytes = readCapped(body.byteStream(), MAX_HTML_BYTES);
                final String html = decodeHtml(htmlBytes, contentType);

                final OpenGraph og = parseOpenGraph(html);
                title = og.title;
                description = og.description;
                imageUrl = og.imageUrl;
            }

            // Resolve a (possibly relative) image URL against the page URL, then re-validate + fetch.
            if (imageUrl != null && !imageUrl.isBlank()) {
                final HttpUrl base = HttpUrl.parse(rawUrl);
                final HttpUrl resolved = base != null ? base.resolve(imageUrl) : HttpUrl.parse(imageUrl);
                final String resolvedStr = resolved != null ? resolved.toString() : null;
                if (LinkPreviewValidator.isValidPreviewUrl(resolvedStr)) {
                    imageBytes = fetchImage(resolvedStr);
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

    @Nullable
    private static byte[] fetchImage(String imageUrl) {
        try {
            final Request request = new Request.Builder()
                .url(imageUrl)
                .header("User-Agent", USER_AGENT)
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

    private static OpenGraph parseOpenGraph(String html) {
        final OpenGraph og = new OpenGraph();
        final Matcher tagMatcher = OG_TAG_PATTERN.matcher(html);
        while (tagMatcher.find()) {
            final String property = tagMatcher.group(1);
            final String tag = tagMatcher.group();
            final Matcher contentMatcher = CONTENT_PATTERN.matcher(tag);
            if (property == null || !contentMatcher.find()) {
                continue;
            }
            final String value = decodeEntities(contentMatcher.group(1));
            switch (property.trim().toLowerCase()) {
                case "title":
                    if (og.title == null) {
                        og.title = truncate(value);
                    }
                    break;
                case "description":
                    if (og.description == null) {
                        og.description = truncate(value);
                    }
                    break;
                case "image":
                case "image:url":
                case "image:secure_url":
                    if (og.imageUrl == null) {
                        og.imageUrl = value;
                    }
                    break;
                default:
                    break;
            }
        }

        if (og.title == null || og.title.isBlank()) {
            final Matcher titleMatcher = TITLE_PATTERN.matcher(html);
            if (titleMatcher.find()) {
                og.title = truncate(decodeEntities(titleMatcher.group(1)));
            }
        }
        return og;
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

    private static final class OpenGraph {
        @Nullable
        String title;
        @Nullable
        String description;
        @Nullable
        String imageUrl;
    }
}
