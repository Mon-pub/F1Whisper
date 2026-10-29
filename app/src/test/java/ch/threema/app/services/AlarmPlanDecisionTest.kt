package ch.threema.app.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F1Whisper: regression tests for [AlarmPlanDecision], the rule that decides whether an alarm is
 * armed, cancelled or retried.
 *
 * The defect this exists to remove: [AlarmScheduler] used to take a `() -> Long?`, so "the queue is
 * empty" and "I could not read the queue" were the same value, and the second was treated as the
 * first. A re-arm attempted while the service graph was down - a locked master key, a cold boot
 * before initialisation, an unopenable database - therefore **cancelled** the alarm, and the
 * disappearing-message engine stayed disarmed until the next boot.
 * `DisappearingMessageService.fireDue` even logged "will re-arm retry" while taking that path,
 * because `factory?.getAllDueBefore(..).orEmpty()` laundered an unreachable table into an empty one.
 *
 * [legacyNullableLongCannotTellEmptyFromUnreadable] is the control and writes the old rule out
 * inline, so it does not call production code: routed through the fix it would stop reproducing.
 */
class AlarmPlanDecisionTest {

    private val now = 1_700_000_000_000L
    private val retryDelay = 5 * 60 * 1000L

    // region The control: what a nullable Long could and could not say

    @Test
    fun legacyNullableLongCannotTellEmptyFromUnreadable() {
        // The shipped rule, verbatim: "null means cancel".
        fun legacyCancels(earliest: Long?): Boolean = earliest == null

        // Correct for a genuinely empty queue.
        assertTrue(legacyCancels(null), "an empty queue should cancel the alarm")

        // And identical - therefore also cancelling - for the two ways of not knowing. Both of these
        // reached that branch in production: a null ServiceManager returned early with null, and a
        // failed read was caught and turned into null.
        val unreadableBecauseNoServiceGraph: Long? = null
        val unreadableBecauseQueryThrew: Long? = null
        assertTrue(
            legacyCancels(unreadableBecauseNoServiceGraph),
            "the defect: an unavailable service graph disarmed the engine",
        )
        assertTrue(
            legacyCancels(unreadableBecauseQueryThrew),
            "the defect: a failed read disarmed the engine",
        )
    }

    // endregion

    @Test
    fun aPendingDeadlineIsArmedAtExactlyThatTime() {
        val due = now + 30_000L
        assertEquals(
            AlarmAction.ArmAt(due),
            AlarmPlanDecision.resolve(AlarmTarget.At(due), now, retryDelay),
        )
    }

    @Test
    fun aDeadlineAlreadyInThePastIsStillArmed() {
        // Not clamped to `now`: an overdue row must fire immediately, and AlarmManager treats a past
        // time as exactly that. Clamping here would only obscure how late the engine already is.
        val overdue = now - 60_000L
        assertEquals(
            AlarmAction.ArmAt(overdue),
            AlarmPlanDecision.resolve(AlarmTarget.At(overdue), now, retryDelay),
        )
    }

    @Test
    fun onlyAGenuinelyEmptyQueueCancels() {
        assertEquals(AlarmAction.Cancel, AlarmPlanDecision.resolve(AlarmTarget.None, now, retryDelay))
    }

    @Test
    fun anUnreadableQueueNeverCancels() {
        // The whole point. Whatever else happens, this must not be Cancel.
        val action = AlarmPlanDecision.resolve(AlarmTarget.Unavailable, now, retryDelay)
        assertTrue(action is AlarmAction.ArmAt, "an unreadable queue must not disarm the engine")
    }

    @Test
    fun anUnreadableQueueArmsARetryAheadOfNow() {
        assertEquals(
            AlarmAction.ArmAt(now + retryDelay),
            AlarmPlanDecision.resolve(AlarmTarget.Unavailable, now, retryDelay),
        )
    }

    @Test
    fun aNonPositiveRetryDelayStillArmsInTheFuture() {
        // A zero or negative delay would arm an alarm at or before `now`, which fires immediately,
        // finds the graph still unavailable, and re-arms at the same time: a spin. The floor of one
        // millisecond keeps the retry monotonic even if a caller passes nonsense.
        assertEquals(
            AlarmAction.ArmAt(now + 1L),
            AlarmPlanDecision.resolve(AlarmTarget.Unavailable, now, 0L),
        )
        assertEquals(
            AlarmAction.ArmAt(now + 1L),
            AlarmPlanDecision.resolve(AlarmTarget.Unavailable, now, -10_000L),
        )
    }

    @Test
    fun theDefaultRetryDelayIsSaneForAWakelockFreeRecovery() {
        // Guards the constant itself: short enough that a locked-master-key window resolves without
        // waiting for a reboot, long enough that repeatedly retrying costs nothing measurable.
        assertTrue(AlarmScheduler.DEFAULT_RETRY_DELAY_MILLIS in 60_000L..900_000L)
    }
}
