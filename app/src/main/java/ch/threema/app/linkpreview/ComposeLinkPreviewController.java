package ch.threema.app.linkpreview;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Patterns;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;

import ch.threema.app.R;
import ch.threema.app.preference.service.PreferenceService;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

/**
 * F1Whisper: drives the Signal-style compose-time link-preview chip. As the user types, the FIRST
 * https URL is extracted (debounced), the preview is fetched off the main thread by
 * {@link LinkPreviewFetcher} (sender-only; the recipient never fetches), and a chip is shown above
 * the input. The user can dismiss the chip; on send the fragment calls {@link #consume(String)} to
 * attach the cached preview to the outgoing message. Disabled entirely when the user's "Generate
 * link previews" preference is off.
 *
 * <p>This object is single-conversation scoped; call {@link #reset()} when the chat changes or after
 * a message is sent.
 */
public class ComposeLinkPreviewController {
    private static final Logger logger = getThreemaLogger("ComposeLinkPreviewController");

    private static final long DEBOUNCE_MS = 300;

    @NonNull
    private final View chipRoot;
    @NonNull
    private final ImageView chipImage;
    @NonNull
    private final TextView chipTitle;
    @NonNull
    private final TextView chipDomain;
    @NonNull
    private final PreferenceService preferenceService;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    private Runnable pendingFetch;
    @Nullable
    private String activeUrl;
    @Nullable
    private String dismissedUrl;
    @Nullable
    private LinkPreviewResult result;
    /**
     * Monotonic token so a stale background fetch (URL changed meanwhile) cannot overwrite a newer
     * one when it completes.
     */
    private int fetchGeneration = 0;

    public ComposeLinkPreviewController(@NonNull View chipRoot, @NonNull PreferenceService preferenceService) {
        this.chipRoot = chipRoot;
        this.preferenceService = preferenceService;
        this.chipImage = chipRoot.findViewById(R.id.link_preview_chip_image);
        this.chipTitle = chipRoot.findViewById(R.id.link_preview_chip_title);
        this.chipDomain = chipRoot.findViewById(R.id.link_preview_chip_domain);
        final View close = chipRoot.findViewById(R.id.link_preview_chip_close);
        if (close != null) {
            close.setOnClickListener(v -> onUserDismiss());
        }
    }

    /**
     * Feed the current compose text. Extracts the first previewable URL and (debounced) fetches it.
     */
    @UiThread
    public void onTextChanged(@Nullable CharSequence text) {
        if (!preferenceService.isLinkPreviewsEnabled()) {
            clearState();
            hideChip();
            return;
        }

        final String url = extractFirstPreviewUrl(text);
        if (url == null) {
            // No URL in the text -> drop any chip and forget a previous dismissal.
            clearState();
            hideChip();
            return;
        }

        if (url.equals(activeUrl)) {
            // Already handling this exact URL (fetching or shown, or dismissed) - nothing to do.
            return;
        }

        // A different URL than before -> a previous dismissal no longer applies.
        activeUrl = url;
        dismissedUrl = null;
        result = null;
        hideChip();

        final int generation = ++fetchGeneration;
        if (pendingFetch != null) {
            mainHandler.removeCallbacks(pendingFetch);
        }
        pendingFetch = () -> executor.submit(() -> {
            final LinkPreviewResult fetched = LinkPreviewFetcher.fetch(url);
            mainHandler.post(() -> onFetchComplete(generation, url, fetched));
        });
        mainHandler.postDelayed(pendingFetch, DEBOUNCE_MS);
    }

    @UiThread
    private void onFetchComplete(int generation, @NonNull String url, @Nullable LinkPreviewResult fetched) {
        if (generation != fetchGeneration) {
            // Superseded by a newer URL.
            return;
        }
        if (!url.equals(activeUrl) || url.equals(dismissedUrl)) {
            return;
        }
        if (fetched == null || !fetched.isPreviewable()) {
            result = null;
            hideChip();
            return;
        }
        result = fetched;
        showChip(fetched);
    }

    @UiThread
    private void showChip(@NonNull LinkPreviewResult preview) {
        final String title = preview.getTitle();
        if (title != null && !title.isBlank()) {
            chipTitle.setText(title);
            chipTitle.setVisibility(View.VISIBLE);
        } else {
            chipTitle.setVisibility(View.GONE);
        }

        final String host = Uri.parse(preview.getUrl()).getHost();
        chipDomain.setText(host != null ? host : preview.getUrl());

        final byte[] imageBytes = preview.getImageBytes();
        if (imageBytes != null && imageBytes.length > 0) {
            try {
                final Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                if (bitmap != null) {
                    chipImage.setImageBitmap(bitmap);
                    chipImage.setVisibility(View.VISIBLE);
                } else {
                    chipImage.setVisibility(View.GONE);
                }
            } catch (Throwable t) {
                chipImage.setVisibility(View.GONE);
            }
        } else {
            chipImage.setVisibility(View.GONE);
        }

        chipRoot.setVisibility(View.VISIBLE);
    }

    @UiThread
    private void onUserDismiss() {
        dismissedUrl = activeUrl;
        result = null;
        hideChip();
    }

    /**
     * Called by the fragment at send time. Returns the cached preview ONLY if it still matches the
     * first URL in the final message text and was not dismissed; otherwise {@code null} (send plain
     * text). After this the controller is reset.
     */
    @UiThread
    @Nullable
    public LinkPreviewResult consume(@Nullable String finalText) {
        LinkPreviewResult consumed = null;
        if (result != null && activeUrl != null && !activeUrl.equals(dismissedUrl)) {
            final String url = extractFirstPreviewUrl(finalText);
            if (activeUrl.equals(url)) {
                consumed = result;
            }
        }
        reset();
        return consumed;
    }

    /**
     * Clear all state and hide the chip (e.g. on conversation switch).
     */
    @UiThread
    public void reset() {
        clearState();
        hideChip();
    }

    public void destroy() {
        executor.shutdownNow();
        if (pendingFetch != null) {
            mainHandler.removeCallbacks(pendingFetch);
        }
    }

    private void clearState() {
        activeUrl = null;
        dismissedUrl = null;
        result = null;
        fetchGeneration++;
        if (pendingFetch != null) {
            mainHandler.removeCallbacks(pendingFetch);
            pendingFetch = null;
        }
    }

    @UiThread
    private void hideChip() {
        chipRoot.setVisibility(View.GONE);
        chipImage.setImageDrawable(null);
    }

    /**
     * Extract the first previewable URL from text: the first {@link Patterns#WEB_URL} match that, after
     * defaulting a missing scheme to https, passes {@link LinkPreviewValidator}.
     */
    @AnyThread
    @Nullable
    private static String extractFirstPreviewUrl(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) {
            return null;
        }
        try {
            final Matcher matcher = Patterns.WEB_URL.matcher(text);
            while (matcher.find()) {
                String candidate = matcher.group();
                if (candidate == null) {
                    continue;
                }
                final String lower = candidate.toLowerCase();
                if (lower.startsWith("http://")) {
                    // Plain http is not previewed (https-only); skip.
                    continue;
                }
                if (!lower.startsWith("https://")) {
                    candidate = "https://" + candidate;
                }
                if (LinkPreviewValidator.isValidPreviewUrl(candidate)) {
                    return candidate;
                }
            }
        } catch (Exception e) {
            logger.debug("URL extraction failed: {}", e.getMessage());
        }
        return null;
    }
}
