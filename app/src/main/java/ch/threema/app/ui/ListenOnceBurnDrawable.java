package ch.threema.app.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import java.util.Random;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * F1Whisper: a one-shot "burn" effect drawn over a listen-once voice bubble the moment it is
 * consumed (Telegram-style). Warm fire embers rise from the bottom of the bubble, each cooling
 * along a real fire colour ramp (white-hot -> amber -> orange -> red -> ember) as it ages and
 * flickers, over a soft flame glow that licks up from the bottom edge.
 *
 * <p>Implemented as a {@link Drawable} added to the bubble card's {@link android.view.ViewOverlay}
 * and driven by an external {@link android.animation.ValueAnimator} via {@link #setProgress(float)}.
 * Unlike an added child {@code View}, an overlay drawable needs no layout pass, so it draws reliably
 * over a recycled list item (where a mid-bind {@code addView} would never get measured/laid out).</p>
 */
public class ListenOnceBurnDrawable extends Drawable {

    /** Total burst duration; the host animator should run for exactly this long. */
    public static final long DURATION_MS = 1200L;

    private static final int PARTICLE_COUNT = 170;

    // Fire colour ramp (by particle age): white-hot -> amber -> orange -> red -> dim ember.
    private static final float[] RAMP_STOPS = {0f, 0.22f, 0.5f, 0.78f, 1f};
    private static final int[] RAMP_R = {255, 255, 255, 224, 122};
    private static final int[] RAMP_G = {238, 188, 112, 64, 26};
    private static final int[] RAMP_B = {176, 70, 30, 18, 10};

    private final Paint emberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Interpolator rise = new DecelerateInterpolator();
    private final Random random = new Random();
    private final float density;

    private float[] startX;
    private float[] startY;
    private float[] velX;
    private float[] riseY;
    private float[] wobbleAmp;
    private float[] wobblePhase;
    private float[] flickerPhase;
    private float[] baseRadius;
    private float[] emitAt;   // 0..1 normalized start time
    private float[] lifeFrac; // 0..1 normalized lifetime

    @Nullable
    private Shader bottomGlow;
    private boolean seeded = false;
    private float progress = 0f; // 0..1

    public ListenOnceBurnDrawable(float density) {
        this.density = density;
    }

    /** Drive the burst; {@code t} runs 0 -> 1 over {@link #DURATION_MS}. */
    public void setProgress(float t) {
        this.progress = t;
        invalidateSelf();
    }

    private void seed(int w, int h) {
        startX = new float[PARTICLE_COUNT];
        startY = new float[PARTICLE_COUNT];
        velX = new float[PARTICLE_COUNT];
        riseY = new float[PARTICLE_COUNT];
        wobbleAmp = new float[PARTICLE_COUNT];
        wobblePhase = new float[PARTICLE_COUNT];
        flickerPhase = new float[PARTICLE_COUNT];
        baseRadius = new float[PARTICLE_COUNT];
        emitAt = new float[PARTICLE_COUNT];
        lifeFrac = new float[PARTICLE_COUNT];
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            startX[i] = random.nextFloat() * w;
            // Fire rises from the bottom: start embers low in the bubble.
            startY[i] = h * (0.55f + random.nextFloat() * 0.45f);
            velX[i] = (random.nextFloat() - 0.5f) * 10f * density;
            riseY[i] = -(0.6f + random.nextFloat() * 0.5f) * h;
            wobbleAmp[i] = (1.5f + random.nextFloat() * 3.0f) * density;
            wobblePhase[i] = random.nextFloat() * 6.2832f;
            flickerPhase[i] = random.nextFloat() * 6.2832f;
            // Small, dense embers read as fire (not large festival dots).
            baseRadius[i] = (0.8f + random.nextFloat() * 1.7f) * density;
            emitAt[i] = random.nextFloat() * 0.28f;
            lifeFrac[i] = 0.4f + random.nextFloat() * 0.4f;
        }
        // Flame glow that licks up from the bottom edge.
        bottomGlow = new LinearGradient(
            0f, h, 0f, h * 0.12f,
            new int[]{0xFFFF6A14, 0x66FF3A00, 0x00000000},
            new float[]{0f, 0.45f, 1f},
            Shader.TileMode.CLAMP);
        seeded = true;
    }

    private static int fireColor(float pf) {
        int seg = 0;
        while (seg < RAMP_STOPS.length - 2 && pf > RAMP_STOPS[seg + 1]) {
            seg++;
        }
        final float span = RAMP_STOPS[seg + 1] - RAMP_STOPS[seg];
        final float f = span <= 0f ? 0f : (pf - RAMP_STOPS[seg]) / span;
        final int r = (int) (RAMP_R[seg] + (RAMP_R[seg + 1] - RAMP_R[seg]) * f);
        final int g = (int) (RAMP_G[seg] + (RAMP_G[seg + 1] - RAMP_G[seg]) * f);
        final int b = (int) (RAMP_B[seg] + (RAMP_B[seg + 1] - RAMP_B[seg]) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect b = getBounds();
        final int w = b.width();
        final int h = b.height();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (!seeded) {
            seed(w, h);
        }

        canvas.save();
        canvas.translate(b.left, b.top);

        // Flame glow from the bottom edge: ramps in then fades over the first ~60% of the burst.
        if (progress < 0.6f && bottomGlow != null) {
            final float g = progress < 0.18f ? (progress / 0.18f) : (1f - (progress - 0.18f) / 0.42f);
            glowPaint.setShader(bottomGlow);
            glowPaint.setAlpha((int) (Math.max(0f, Math.min(1f, g)) * 165));
            canvas.drawRect(0f, 0f, w, h, glowPaint);
            glowPaint.setShader(null);
        }
        // Brief bright ignite flash.
        if (progress < 0.1f) {
            glowPaint.setColor(0xFFFFE6B0);
            glowPaint.setAlpha((int) ((1f - progress / 0.1f) * 120));
            canvas.drawRect(0f, 0f, w, h, glowPaint);
        }

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            final float localT = progress - emitAt[i];
            if (localT < 0f) {
                continue; // not yet emitted
            }
            final float pf = localT / lifeFrac[i];
            if (pf >= 1f) {
                continue; // dead
            }
            final float r = rise.getInterpolation(pf);
            final float x = startX[i] + velX[i] * pf
                + wobbleAmp[i] * (float) Math.sin(wobblePhase[i] + pf * 7.5f);
            final float y = startY[i] + riseY[i] * r;
            final float flicker = 0.7f + 0.3f * (float) Math.sin(flickerPhase[i] + pf * 26f);
            final float alpha = Math.max(0f, (1f - pf) * flicker);
            final int col = fireColor(pf);
            final float radius = baseRadius[i] * (0.45f + 0.55f * (1f - pf));

            // Soft halo for a glowing-ember look, then a brighter core.
            emberPaint.setColor(col);
            emberPaint.setAlpha((int) (alpha * 70));
            canvas.drawCircle(x, y, radius * 2.4f, emberPaint);
            emberPaint.setAlpha((int) (alpha * 255));
            canvas.drawCircle(x, y, radius, emberPaint);
        }

        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        // no-op: per-particle alpha is computed in draw()
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // no-op
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
