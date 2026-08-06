package ch.threema.app.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.data.media.FileDataModel;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

/**
 * F1Whisper helper for the image/video "spoiler" feature. An image or video sent as a spoiler is
 * rendered with a blurred thumbnail plus a tap-to-reveal overlay until the recipient reveals it.
 * <p>
 * Reveal state is kept purely in memory (per process), keyed by message id, so the media is
 * re-hidden whenever the chat is reopened or the app is restarted. There is no persisted/consumed
 * state and no database migration (this differs from "listen once" voice on purpose - spoilers are
 * infinitely re-hideable, not burned).
 */
public final class MediaSpoilerUtil {
    private static final Logger logger = getThreemaLogger("MediaSpoilerUtil");

    // In-memory, per-process reveal set keyed by message id. Cleared on app restart.
    private static final Set<Integer> revealedMessageIds =
        Collections.synchronizedSet(new HashSet<>());

    private MediaSpoilerUtil() {
    }

    public static boolean isRevealed(int messageId) {
        return revealedMessageIds.contains(messageId);
    }

    public static void reveal(int messageId) {
        revealedMessageIds.add(messageId);
    }

    /**
     * Forget all currently-revealed media spoilers so they are re-hidden on the next bind. Called
     * when the user leaves a conversation, making the reveal strictly per chat-visit: re-entering the
     * chat shows every media spoiler obscured again until tapped.
     * <p>
     * This only affects the media-spoiler reveal set kept here; text-spoiler reveal state lives in a
     * separate store and is intentionally not touched.
     */
    public static void clearRevealed() {
        revealedMessageIds.clear();
    }

    /**
     * @return {@code true} if this file message is an un-revealed spoiler that should be rendered
     * blurred (i.e. it carries the spoiler metadata flag and the user has not yet tapped to reveal
     * it in the current session).
     */
    public static boolean shouldObscure(@Nullable AbstractMessageModel messageModel) {
        // The spoiler flag only lives in the file-data metadata, so it is only meaningful for FILE
        // messages. Guarding on the type also avoids spurious warnings from getFileData() on legacy
        // IMAGE/VIDEO messages (which never carry the flag anyway).
        if (messageModel == null || messageModel.getType() != MessageType.FILE) {
            return false;
        }
        final FileDataModel fileData = messageModel.getFileData();
        return fileData != null && fileData.isSpoiler() && !isRevealed(messageModel.getId());
    }

    // Strong, Telegram-style obscuring parameters. The thumbnail is collapsed to a tiny bitmap so
    // every recognizable detail is destroyed, blurred, then a dark scrim is composited on top. The
    // ImageView upscales the small result, which adds further smoothing.
    private static final int DOWNSCALE_DIVISOR = 16; // target width ~= thumbnail width / 16
    private static final int MIN_DOWNSCALE_SIZE = 8;  // never collapse below this (avoids 0-px bitmaps)
    private static final int MAX_DOWNSCALE_SIZE = 24; // cap so the smear stays heavy on large thumbs
    private static final int SCRIM_ALPHA = 0x66;      // ~40% black scrim over the blur
    // Opaque neutral fill used when obscuring fails outright. Deliberately not transparent: the
    // fallback has to be at least as hiding as the blur it replaces.
    private static final int PLACEHOLDER_COLOR = 0xFF3A3A3A;

    /**
     * @return a heavily obscured copy of the given thumbnail (strong blur + dark scrim), or
     * {@code null} if the input is {@code null}. The original bitmap is never modified (the cached
     * thumbnail is shared with the lightbox viewer), so a defensive copy is always made first.
     * <p>
     * The media content is collapsed to a tiny bitmap, blurred and darkened so it is genuinely
     * unrecognizable until the user taps to reveal.
     */
    @Nullable
    public static Bitmap obscure(@Nullable Bitmap thumbnail, @NonNull Context context) {
        if (thumbnail == null) {
            return null;
        }
        try {
            final int srcWidth = Math.max(1, thumbnail.getWidth());
            final int srcHeight = Math.max(1, thumbnail.getHeight());

            // Collapse to a tiny bitmap (classic strong-blur trick): heavy downsample destroys detail.
            final int longestEdge = Math.max(srcWidth, srcHeight);
            int targetLongest = clamp(longestEdge / DOWNSCALE_DIVISOR, MIN_DOWNSCALE_SIZE, MAX_DOWNSCALE_SIZE);
            final float scale = (float) targetLongest / (float) longestEdge;
            final int smallWidth = Math.max(1, Math.round(srcWidth * scale));
            final int smallHeight = Math.max(1, Math.round(srcHeight * scale));

            // createScaledBitmap returns a new bitmap (or the same instance if dimensions match); copy
            // to guarantee we never mutate the shared/cached thumbnail and to normalize the config.
            Bitmap small = Bitmap.createScaledBitmap(thumbnail, smallWidth, smallHeight, true);
            final Bitmap working = small.copy(Bitmap.Config.ARGB_8888, true);
            if (small != thumbnail && small != working) {
                small.recycle();
            }

            // Two RenderScript blur passes on the tiny bitmap (cheap) for an extra-smooth smear.
            BitmapUtil.blurBitmap(working, context);
            BitmapUtil.blurBitmap(working, context);

            // Composite a dark scrim so even bright media reads as obscured.
            final Canvas canvas = new Canvas(working);
            final Paint scrimPaint = new Paint();
            scrimPaint.setColor(Color.argb(SCRIM_ALPHA, 0, 0, 0));
            canvas.drawRect(0, 0, working.getWidth(), working.getHeight(), scrimPaint);

            return working;
        } catch (Exception | OutOfMemoryError e) {
            // F1-PATCH: fail CLOSED. This branch used to return the original bitmap, which meant the
            // one path that exists because obscuring can fail handed back the fully legible image -
            // and the bubble then looked like an ordinary photo, giving the user no hint that the
            // spoiler had not been applied. BitmapUtil.blurBitmap goes through RenderScript, which is
            // deprecated and missing or throwing on a growing set of devices, so this is a branch
            // that gets taken. OutOfMemoryError is caught alongside Exception for the same reason:
            // the allocations above are exactly what runs out of memory, and an Error escaping here
            // would leave the caller displaying the original.
            logger.error("Failed to obscure spoiler thumbnail; substituting an opaque placeholder", e);
            return opaquePlaceholder(thumbnail);
        }
    }

    /**
     * @return a small opaque bitmap with the source's aspect ratio, or {@code null} if even that
     * cannot be allocated. Used when obscuring fails: the bubble keeps its shape and reads as
     * deliberately hidden rather than as broken, which {@code null} (an empty ImageView) would not.
     */
    @Nullable
    private static Bitmap opaquePlaceholder(@NonNull Bitmap thumbnail) {
        try {
            final int srcWidth = Math.max(1, thumbnail.getWidth());
            final int srcHeight = Math.max(1, thumbnail.getHeight());
            final int longestEdge = Math.max(srcWidth, srcHeight);
            final int targetLongest = clamp(longestEdge / DOWNSCALE_DIVISOR, MIN_DOWNSCALE_SIZE, MAX_DOWNSCALE_SIZE);
            final float scale = (float) targetLongest / (float) longestEdge;
            final Bitmap placeholder = Bitmap.createBitmap(
                Math.max(1, Math.round(srcWidth * scale)),
                Math.max(1, Math.round(srcHeight * scale)),
                Bitmap.Config.ARGB_8888
            );
            placeholder.eraseColor(PLACEHOLDER_COLOR);
            return placeholder;
        } catch (Exception | OutOfMemoryError e) {
            logger.error("Failed to build spoiler placeholder", e);
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
