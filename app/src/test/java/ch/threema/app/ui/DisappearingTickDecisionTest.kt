package ch.threema.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F1Whisper (twelfth fork review, F12-03): exhaustive spec of [DisappearingTickDecision], the pure per-tick decision
 * behind [DisappearingTimerBadgeView]'s countdown. The three outcomes partition the remaining time completely: past
 * (or at) the deadline the tick becomes the DEADLINE event, inside the final 30 s the clock ticks fast, above that it
 * relaxes to once a second. [DisappearingDeadlineTransitionTest] proves what the DEADLINE outcome does on the bound
 * row; this test proves when each outcome is chosen.
 */
class DisappearingTickDecisionTest {

    // ------------------------------------------------------------------------------------------------------------
    // decide(): the three-way partition of remaining time
    // ------------------------------------------------------------------------------------------------------------

    @Test
    fun `zero remaining is the deadline, not a fast tick`() {
        // The boundary matters: `remaining <= 0` must fire the event. A `< 0` comparison would let a tick landing
        // exactly on the deadline repaint and re-post, deferring the transition by another cadence interval.
        assertEquals(DisappearingTick.DEADLINE, DisappearingTickDecision.decide(0L))
    }

    @Test
    fun `negative remaining is the deadline however late the tick runs`() {
        assertEquals(DisappearingTick.DEADLINE, DisappearingTickDecision.decide(-1L))
        assertEquals(DisappearingTick.DEADLINE, DisappearingTickDecision.decide(-30_000L))
        assertEquals(DisappearingTick.DEADLINE, DisappearingTickDecision.decide(Long.MIN_VALUE))
    }

    @Test
    fun `the final thirty seconds tick fast`() {
        assertEquals(DisappearingTick.FAST_TICK, DisappearingTickDecision.decide(1L))
        assertEquals(DisappearingTick.FAST_TICK, DisappearingTickDecision.decide(15_000L))
        assertEquals(DisappearingTick.FAST_TICK, DisappearingTickDecision.decide(29_999L))
    }

    @Test
    fun `at and above thirty seconds the clock ticks slow`() {
        // Exactly 30 s is OUTSIDE the fast window (`< 30_000`), preserving the pre-F12-03 cadence boundary.
        assertEquals(DisappearingTick.SLOW_TICK, DisappearingTickDecision.decide(30_000L))
        assertEquals(DisappearingTick.SLOW_TICK, DisappearingTickDecision.decide(600_000L))
        assertEquals(DisappearingTick.SLOW_TICK, DisappearingTickDecision.decide(Long.MAX_VALUE))
    }

    @Test
    fun `every remaining value maps to exactly one outcome across the boundaries`() {
        for (remaining in -2L..2L) {
            val expected = if (remaining <= 0L) DisappearingTick.DEADLINE else DisappearingTick.FAST_TICK
            assertEquals(expected, DisappearingTickDecision.decide(remaining), "remaining=$remaining")
        }
        for (remaining in 29_998L..30_002L) {
            val expected = if (remaining < 30_000L) DisappearingTick.FAST_TICK else DisappearingTick.SLOW_TICK
            assertEquals(expected, DisappearingTickDecision.decide(remaining), "remaining=$remaining")
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // delayFor(): the cadence each scheduling decision requests
    // ------------------------------------------------------------------------------------------------------------

    @Test
    fun `fast ticks re-post at fifty milliseconds, slow ticks at one second`() {
        assertEquals(50L, DisappearingTickDecision.delayFor(DisappearingTick.FAST_TICK))
        assertEquals(1000L, DisappearingTickDecision.delayFor(DisappearingTick.SLOW_TICK))
    }

    @Test
    fun `arming an already-due countdown schedules the prompt deadline-delivering tick`() {
        // startAnimation() derives its first delay from decide(); a countdown armed at or past its deadline must
        // get the DEADLINE event promptly, not after a one-second wait. (The deadline tick itself never re-posts -
        // the runnable retires instead of asking for a delay; DisappearingDeadlineTransitionTest pins that.)
        assertEquals(50L, DisappearingTickDecision.delayFor(DisappearingTick.DEADLINE))
    }
}
