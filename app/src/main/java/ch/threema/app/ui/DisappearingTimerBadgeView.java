package ch.threema.app.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

import ch.threema.app.R;
import ch.threema.app.utils.RuntimeUtil;

/**
 * F1Whisper: animated disappearing-messages countdown clock, a port of Signal's
 * {@code ExpirationTimerView} scheme onto our own redrawn 13-frame clock face.
 * <p>
 * The remaining time is rendered as one of 13 static frames ({@code ic_timer_00}..{@code ic_timer_60},
 * one every 5% elapsed). {@link #setPercentComplete(float)} maps progress to a frame with
 * {@code frame = ceil((1 - progress) * 12)}, clamped into range. {@link #startAnimation()} schedules a
 * self-rescheduling main-thread runnable that re-derives the frame every second (or every 50ms in the
 * final 30s so the clock visibly ticks down to zero).
 * <p>
 * The runnable holds only a {@link WeakReference} to this view. The binder ({@code ChatAdapterDecorator})
 * is the primary driver: on every (recycle) bind it stops any prior countdown and restarts it only if
 * still running; {@link #onDetachedFromWindow()} is an additional safety net. {@link #stopAnimation()}
 * cancels the pending tick outright ({@code removeCallbacks}) AND clears {@code startedAt}/{@code expiresIn}
 * so a recycled {@code ListView} row can never leak a trailing tick that repaints the previous message's
 * frame onto the newly-bound one. The runnable also checks liveness FIRST and bails without repainting
 * if it has been superseded, as belt-and-braces against a tick that slips past the cancellation.
 * {@link #startAnimation()} is idempotent (guarded by {@code stopped}), so re-binding the same row never
 * stacks multiple runnables.
 */
public class DisappearingTimerBadgeView extends androidx.appcompat.widget.AppCompatImageView {

    private long startedAt;
    private long expiresIn;

    private boolean visible = false;
    private boolean stopped = true;

    // The single pending tick, retained so stopAnimation() can cancel it (removeCallbacks) instead of
    // letting one trailing tick fire on a recycled row and repaint a previous message's frame.
    @Nullable
    private AnimationUpdateRunnable pendingUpdate;

    // Ordered by frame INDEX, not by asset name. The Signal frame math is
    // {@code frame = ceil((1 - progress) * 12)}: at progress 0 (nothing elapsed) it selects index 12,
    // at progress 1 (fully elapsed) index 0. So index 0 must be the EMPTY face and index 12 the FULL
    // disc. Our assets are named the other way round (ic_timer_00 = full disc, ic_timer_60 = empty),
    // so this array is reversed relative to the file names on purpose.
    private final int[] frames = new int[]{
        R.drawable.ic_timer_60, // index 0  -> fully elapsed (empty outline)
        R.drawable.ic_timer_55,
        R.drawable.ic_timer_50,
        R.drawable.ic_timer_45,
        R.drawable.ic_timer_40,
        R.drawable.ic_timer_35,
        R.drawable.ic_timer_30,
        R.drawable.ic_timer_25,
        R.drawable.ic_timer_20,
        R.drawable.ic_timer_15,
        R.drawable.ic_timer_10,
        R.drawable.ic_timer_05,
        R.drawable.ic_timer_00, // index 12 -> nothing elapsed (full disc)
    };

    public DisappearingTimerBadgeView(Context context) {
        super(context);
    }

    public DisappearingTimerBadgeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public DisappearingTimerBadgeView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * Binds the running countdown window and immediately paints the frame for "now".
     *
     * @param startedAt epoch-millis at which the timer started (message's {@code expireStartedAt})
     * @param expiresIn total timer duration in millis ({@code disappearingTimerSeconds * 1000})
     */
    public void setExpirationTime(long startedAt, long expiresIn) {
        this.startedAt = startedAt;
        this.expiresIn = expiresIn;
        setPercentComplete(calculateProgress(this.startedAt, this.expiresIn));
    }

    /**
     * Paints the frame for a given elapsed fraction. {@code percentage} is the fraction ELAPSED in
     * {@code [0, 1]}; the frame shows the fraction REMAINING. Exposed for unit-testing the frame math
     * and for the static "not yet started" full-disc state.
     */
    public void setPercentComplete(float percentage) {
        setImageResource(frames[frameForProgress(percentage, frames.length)]);
    }

    /**
     * Pure frame-index math (extracted for unit testing). Given the elapsed fraction and the frame
     * count, returns {@code ceil((1 - progress) * (count - 1))} clamped to {@code [0, count - 1]}.
     * Frame 0 = full disc (nothing elapsed), frame {@code count - 1} = empty (fully elapsed).
     */
    public static int frameForProgress(float percentage, int frameCount) {
        float percentFull = 1f - percentage;
        int frame = (int) Math.ceil(percentFull * (frameCount - 1));
        return Math.max(0, Math.min(frame, frameCount - 1));
    }

    public void startAnimation() {
        final AnimationUpdateRunnable update;
        synchronized (this) {
            visible = true;
            if (!stopped) {
                // Already scheduled -- do not stack a second runnable (idempotent re-bind of the row).
                return;
            }
            stopped = false;
            update = new AnimationUpdateRunnable(this);
            pendingUpdate = update;
        }

        RuntimeUtil.handler.postDelayed(update, calculateAnimationDelay(this.startedAt, this.expiresIn));
    }

    public void stopAnimation() {
        final AnimationUpdateRunnable update;
        synchronized (this) {
            visible = false;
            // Cancel the pending tick outright so it can never fire on a recycled row and repaint a
            // previous message's frame. Mark stopped=true so a following startAnimation() re-schedules
            // (the removed runnable will never run to set this itself).
            stopped = true;
            update = pendingUpdate;
            pendingUpdate = null;
            // Clear the timing fields so a subsequent bind that only paints the static full-disc frame
            // (setPercentComplete(0f), the frozen/not-started branch) cannot carry over a prior
            // message's startedAt/expiresIn if any stray repaint were to occur.
            startedAt = 0L;
            expiresIn = 0L;
        }
        if (update != null) {
            RuntimeUtil.handler.removeCallbacks(update);
        }
    }

    private float calculateProgress(long startedAt, long expiresIn) {
        if (expiresIn <= 0L) {
            return 0f;
        }
        long progressed = System.currentTimeMillis() - startedAt;
        float percentComplete = (float) progressed / (float) expiresIn;
        return Math.max(0f, Math.min(percentComplete, 1f));
    }

    private long calculateAnimationDelay(long startedAt, long expiresIn) {
        long progressed = System.currentTimeMillis() - startedAt;
        long remaining = expiresIn - progressed;

        if (remaining < TimeUnit.SECONDS.toMillis(30)) {
            return 50;
        } else {
            return 1000;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        // Lazy-stop safety net: a row scrolled fully out of the ListView window detaches; mark the
        // countdown invisible so its next tick returns instead of re-posting. The binder
        // (ChatAdapterDecorator) is the primary start/stop driver on every (recycle) bind, so we do
        // NOT auto-start/stop from onVisibilityChanged -- that would race the binder's explicit calls
        // with possibly-stale timing fields.
        stopAnimation();
        super.onDetachedFromWindow();
    }

    private static class AnimationUpdateRunnable implements Runnable {

        private final WeakReference<DisappearingTimerBadgeView> viewReference;

        private AnimationUpdateRunnable(@NonNull DisappearingTimerBadgeView view) {
            this.viewReference = new WeakReference<>(view);
        }

        @Override
        public void run() {
            DisappearingTimerBadgeView view = viewReference.get();
            if (view == null) {
                // View was garbage-collected; nothing to paint or reschedule.
                return;
            }

            // Check liveness FIRST and bail WITHOUT repainting. This runnable may still be dispatched
            // after stopAnimation() (e.g. a queued tick that slips past removeCallbacks); repainting
            // here would paint this runnable's stale startedAt/expiresIn onto an already-recycled row.
            synchronized (view) {
                if (view.pendingUpdate != this) {
                    // A stopAnimation()/startAnimation() cycle has superseded us. We own none of the
                    // view's scheduling state anymore, so touch nothing -- the current owner drives it.
                    return;
                }
                if (!view.visible) {
                    // We are the current update but the row was stopped: retire cleanly.
                    view.stopped = true;
                    view.pendingUpdate = null;
                    return;
                }
            }

            view.setExpirationTime(view.startedAt, view.expiresIn);

            RuntimeUtil.handler.postDelayed(
                this,
                view.calculateAnimationDelay(view.startedAt, view.expiresIn)
            );
        }
    }
}
