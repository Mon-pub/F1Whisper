package ch.threema.app.linkpreview;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * F1Whisper: the outcome of a sender-side link-preview fetch (Open Graph metadata + an optional
 * re-encoded preview image). Immutable. Built only on the sending device; the recipient never
 * fetches anything (the result is transmitted E2E as a media message, see
 * {@link ch.threema.app.linkpreview.LinkPreviewFetcher}).
 */
public class LinkPreviewResult {
    @NonNull
    private final String url;
    @Nullable
    private final String title;
    @Nullable
    private final String description;
    /**
     * Re-encoded JPEG bytes of the preview image (og:image), already EXIF-stripped and size-capped,
     * or {@code null} when the page had no usable image. When null, the caller may synthesise a
     * placeholder so the preview can still be sent (a media message needs a blob).
     */
    @Nullable
    private final byte[] imageBytes;

    public LinkPreviewResult(
        @NonNull String url,
        @Nullable String title,
        @Nullable String description,
        @Nullable byte[] imageBytes
    ) {
        this.url = url;
        this.title = title;
        this.description = description;
        this.imageBytes = imageBytes;
    }

    @NonNull
    public String getUrl() {
        return url;
    }

    @Nullable
    public String getTitle() {
        return title;
    }

    @Nullable
    public String getDescription() {
        return description;
    }

    @Nullable
    public byte[] getImageBytes() {
        return imageBytes;
    }

    /**
     * @return {@code true} if there is enough metadata to be worth showing/sending a preview at all
     * (a title or an image). A bare URL with neither is not previewable.
     */
    public boolean isPreviewable() {
        return (title != null && !title.isBlank()) || imageBytes != null;
    }
}
