package ch.threema.app.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * F1Whisper: unit tests for {@link DisappearingTimerBadgeView#frameForProgress(float, int)}, the pure
 * progress-to-frame mapping that drives the disappearing-messages countdown clock.
 * <p>
 * The mapping is Signal's {@code frame = ceil((1 - progress) * (count - 1))} clamped into range, where
 * {@code progress} is the fraction ELAPSED. With 13 frames the returned INDEX runs 0..12: index 12 at
 * progress 0 (nothing elapsed) and index 0 at progress 1 (fully elapsed). The badge's {@code frames}
 * array is ordered so index 0 = empty face and index 12 = full disc, i.e. a fresh timer shows a full
 * clock and a fully-elapsed timer shows an empty one.
 */
public class DisappearingTimerBadgeViewTest {

    private static final int FRAME_COUNT = 13;
    private static final int LAST = FRAME_COUNT - 1; // 12

    private static int frame(float progress) {
        return DisappearingTimerBadgeView.frameForProgress(progress, FRAME_COUNT);
    }

    @Test
    public void freshTimerSelectsHighestIndex() {
        // 0% elapsed -> percentFull = 1 -> ceil(1 * 12) = 12 (the full-disc slot in the array).
        assertEquals(LAST, frame(0f));
    }

    @Test
    public void fullyElapsedSelectsLowestIndex() {
        // 100% elapsed -> percentFull = 0 -> ceil(0) = 0 (the empty slot in the array).
        assertEquals(0, frame(1f));
    }

    @Test
    public void clampsBelowZero() {
        // Negative progress (timer started in the future / clock skew) clamps to the highest index.
        assertEquals(LAST, frame(-0.5f));
    }

    @Test
    public void clampsAboveOne() {
        // Over-elapsed (past expiry) clamps to index 0.
        assertEquals(0, frame(2f));
    }

    @Test
    public void halfElapsedIsMiddleFrame() {
        // 50% elapsed -> percentFull = 0.5 -> ceil(0.5 * 12) = ceil(6.0) = 6.
        assertEquals(6, frame(0.5f));
    }

    @Test
    public void ceilRoundsUpTowardMoreTimeRemaining() {
        // Just past half -> percentFull just under 0.5 -> ceil(<6.0) rounds up to 6, never 5, so a
        // partly-elapsed timer never prematurely drops a frame.
        assertEquals(6, frame(0.5001f));
        // A tiny bit elapsed -> percentFull ~0.9999 -> ceil(11.998...) = 12.
        assertEquals(12, frame(0.0001f));
        // Almost fully elapsed -> percentFull ~0.0001 -> ceil(0.0012) = 1.
        assertEquals(1, frame(0.9999f));
    }

    @Test
    public void everyProgressStepStaysInRange() {
        for (int pct = 0; pct <= 100; pct++) {
            int f = frame(pct / 100f);
            assertTrue("progress " + pct + "% out of range: " + f, f >= 0 && f <= LAST);
        }
    }

    @Test
    public void monotonicNonIncreasingAsTimeElapses() {
        // As elapsed progress rises from 0 -> 1, the frame index must never increase (the clock only
        // ever runs down, never back up).
        int previous = frame(0f);
        for (int pct = 1; pct <= 100; pct++) {
            int current = frame(pct / 100f);
            assertTrue("frame must be non-increasing at " + pct + "%", current <= previous);
            previous = current;
        }
    }

    @Test
    public void differentFrameCountsClampCorrectly() {
        // Guard the generic clamp with a smaller frame set.
        assertEquals(4, DisappearingTimerBadgeView.frameForProgress(0f, 5));
        assertEquals(0, DisappearingTimerBadgeView.frameForProgress(1f, 5));
        assertEquals(0, DisappearingTimerBadgeView.frameForProgress(5f, 5));
        assertEquals(4, DisappearingTimerBadgeView.frameForProgress(-1f, 5));
    }
}
