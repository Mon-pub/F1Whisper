package ch.threema.app.linkpreview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.slf4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

/**
 * F1Whisper: helpers for the link-preview image. Generates a domain-monogram placeholder for pages
 * that have no og:image (so a title-only link still produces a card), and writes preview image bytes
 * to a temporary cache file that the media-send pipeline can read.
 */
public final class LinkPreviewImageFactory {
    private static final Logger logger = getThreemaLogger("LinkPreviewImageFactory");

    private LinkPreviewImageFactory() {
    }

    private static final int PLACEHOLDER_WIDTH = 640;
    private static final int PLACEHOLDER_HEIGHT = 360;

    // A small, calm palette; the host hash picks a deterministic colour.
    private static final int[] PALETTE = {
        0xFF1E88E5, 0xFF43A047, 0xFF8E24AA, 0xFFF4511E,
        0xFF00897B, 0xFF3949AB, 0xFFD81B60, 0xFF6D4C41,
    };

    /**
     * Render a simple placeholder card image for {@code url}: a coloured background (deterministic
     * from the host) with the host text. Returns JPEG bytes, or {@code null} on failure.
     */
    @WorkerThread
    @Nullable
    public static byte[] createPlaceholder(@Nullable Context context, @NonNull String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null || host.isEmpty()) {
                host = url;
            }
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }

            final int color = PALETTE[Math.floorMod(host.hashCode(), PALETTE.length)];

            final Bitmap bitmap = Bitmap.createBitmap(PLACEHOLDER_WIDTH, PLACEHOLDER_HEIGHT, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(color);

            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);

            // Shrink the text size until the host fits within the width with a margin.
            float textSize = 64f;
            paint.setTextSize(textSize);
            final float maxWidth = PLACEHOLDER_WIDTH * 0.86f;
            while (paint.measureText(host) > maxWidth && textSize > 18f) {
                textSize -= 2f;
                paint.setTextSize(textSize);
            }

            final Paint.FontMetrics fm = paint.getFontMetrics();
            final float y = (PLACEHOLDER_HEIGHT / 2f) - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(host, PLACEHOLDER_WIDTH / 2f, y, paint);

            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            bitmap.recycle();
            return baos.toByteArray();
        } catch (Throwable t) {
            logger.debug("Placeholder generation failed: {}", t.getMessage());
            return null;
        }
    }

    /**
     * Write {@code imageBytes} to a uniquely-named temporary JPEG in the app cache and return a Uri
     * the media-send pipeline can read. Returns {@code null} on failure.
     */
    @WorkerThread
    @Nullable
    public static Uri writeTempImage(@Nullable Context context, @NonNull byte[] imageBytes) {
        if (context == null) {
            return null;
        }
        try {
            final File dir = new File(context.getCacheDir(), "link_preview");
            if (!dir.exists() && !dir.mkdirs()) {
                logger.warn("Could not create link preview cache dir");
                return null;
            }
            final File file = new File(dir, "lp_" + System.nanoTime() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(imageBytes);
            }
            return Uri.fromFile(file);
        } catch (Exception e) {
            logger.error("Could not write temp preview image", e);
            return null;
        }
    }
}
