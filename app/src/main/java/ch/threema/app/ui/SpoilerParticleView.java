package ch.threema.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * F1Whisper: lightweight Telegram-style "spoiler" particle overlay. Draws a field of small white
 * dots that continuously twinkle (fade in/out) and slowly drift, sitting on top of the blurred and
 * darkened media thumbnail so the content reads as genuinely hidden until revealed.
 * <p>
 * Kept deliberately cheap: a small fixed number of particles, redrawn via {@link #postOnAnimation}
 * only while the view is attached to a window (the animation stops automatically when the bubble is
 * recycled or the chat is left).
 */
public class SpoilerParticleView extends View {

    private static final int PARTICLE_COUNT = 90;
    private static final float PARTICLE_RADIUS_DP = 0.9f;
    // How far (px-per-frame, at the configured density) a particle drifts.
    private static final float DRIFT_SPEED = 0.25f;
    // Twinkle speed: alpha phase advance per frame.
    private static final float TWINKLE_SPEED = 0.06f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();

    private float[] x;
    private float[] y;
    private float[] driftX;
    private float[] driftY;
    private float[] phase;

    private float radiusPx;
    private boolean attached = false;
    private boolean initialized = false;

    public SpoilerParticleView(Context context) {
        super(context);
        init();
    }

    public SpoilerParticleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SpoilerParticleView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        radiusPx = PARTICLE_RADIUS_DP * getResources().getDisplayMetrics().density;
        paint.setColor(Color.WHITE);
        setWillNotDraw(false);
    }

    private void seedParticles(int width, int height) {
        x = new float[PARTICLE_COUNT];
        y = new float[PARTICLE_COUNT];
        driftX = new float[PARTICLE_COUNT];
        driftY = new float[PARTICLE_COUNT];
        phase = new float[PARTICLE_COUNT];
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            x[i] = random.nextFloat() * width;
            y[i] = random.nextFloat() * height;
            driftX[i] = (random.nextFloat() - 0.5f) * 2f * DRIFT_SPEED;
            driftY[i] = (random.nextFloat() - 0.5f) * 2f * DRIFT_SPEED;
            phase[i] = random.nextFloat() * (float) (2 * Math.PI);
        }
        initialized = true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            seedParticles(w, h);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        postOnAnimation(this::invalidate);
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        final int width = getWidth();
        final int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (!initialized) {
            seedParticles(width, height);
        }

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            // Drift and wrap around the edges.
            x[i] += driftX[i];
            y[i] += driftY[i];
            if (x[i] < 0) {
                x[i] += width;
            } else if (x[i] > width) {
                x[i] -= width;
            }
            if (y[i] < 0) {
                y[i] += height;
            } else if (y[i] > height) {
                y[i] -= height;
            }

            // Twinkle: alpha oscillates between ~0.2 and 1.0.
            phase[i] += TWINKLE_SPEED;
            final float twinkle = (float) (0.6 + 0.4 * Math.sin(phase[i]));
            paint.setAlpha((int) (twinkle * 255));
            canvas.drawCircle(x[i], y[i], radiusPx, paint);
        }

        if (attached) {
            postOnAnimation(this::invalidate);
        }
    }
}
