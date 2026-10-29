package ch.threema.app.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F1Whisper: regression tests for [ExpiryRepairDecision], the rule that rescues disappearing rows
 * whose countdown can never reach a deadline.
 *
 * Two shapes are invisible to the entire enforcement engine, because the sweep
 * (`getMessagesExpiredBefore`) and the alarm (`getEarliestExpiry`) both select on `expiresAtUtc IS
 * NOT NULL`: a started countdown with no deadline, and a read incoming message whose countdown never
 * started. The second is the fallout of the non-atomic first-read write that `markAsRead` used to
 * perform - `read = true` in one row write, the countdown in a second - because
 * `MessageUtil.canMarkAsRead` refuses to re-mark a read message, so nothing could ever start that
 * countdown again. The write is atomic now; rows already on disk still need repairing.
 *
 * [legacySweepCannotSeeARowWithNoDeadline] is the control. It writes the sweep's own predicate out
 * inline so it does not call production code.
 */
class ExpiryRepairDecisionTest {

    private val now = 1_700_000_000_000L
    private val readAt = now - 60_000L
    private val startedAt = now - 90_000L
    private val timer = 300 // 5 minutes

    // region The control: why an unstamped row is not merely late

    @Test
    fun legacySweepCannotSeeARowWithNoDeadline() {
        // Both the sweep and the alarm select on this, verbatim.
        fun visibleToTheEngine(expiresAt: Long?, now: Long): Boolean = expiresAt != null && expiresAt <= now

        assertEquals(true, visibleToTheEngine(now - 1L, now), "an overdue stamped row is swept")
        assertEquals(false, visibleToTheEngine(now + 1L, now), "a future stamped row waits, correctly")
        assertEquals(
            false,
            visibleToTheEngine(null, now),
            "the defect: an unstamped row is not late, it is invisible - no sweep and no alarm will " +
                "ever consider it, at any future `now`",
        )
    }

    // endregion

    // region Started but unstamped

    @Test
    fun aStartedCountdownWithNoDeadlineIsDerivedFromItsOwnStart() {
        val repair = ExpiryRepairDecision.repairFor(
            isOutbox = false,
            isRead = true,
            timerSeconds = timer,
            expireStartedAt = startedAt,
            expiresAt = null,
            readAt = readAt,
            nowMillis = now,
        )
        assertEquals(ExpiryRepair(startedAt, startedAt + timer * 1000L), repair)
    }

    @Test
    fun anOutgoingStartedCountdownIsRepairedToo() {
        // The outgoing direction gets the same derivation: the start is already on the row, so there
        // is nothing to guess and no reason to treat the two directions differently here.
        val repair = ExpiryRepairDecision.repairFor(
            isOutbox = true,
            isRead = false,
            timerSeconds = timer,
            expireStartedAt = startedAt,
            expiresAt = null,
            readAt = null,
            nowMillis = now,
        )
        assertEquals(ExpiryRepair(startedAt, startedAt + timer * 1000L), repair)
    }

    @Test
    fun aRowThatAlreadyHasBothStampsIsLeftAlone() {
        assertNull(
            ExpiryRepairDecision.repairFor(
                isOutbox = false,
                isRead = true,
                timerSeconds = timer,
                expireStartedAt = startedAt,
                expiresAt = startedAt + timer * 1000L,
                readAt = readAt,
                nowMillis = now,
            ),
        )
    }

    // endregion

    // region Read but never started - the crash fallout

    @Test
    fun aReadIncomingMessageWithNoCountdownStartsFromWhenItWasRead() {
        val repair = ExpiryRepairDecision.repairFor(
            isOutbox = false,
            isRead = true,
            timerSeconds = timer,
            expireStartedAt = null,
            expiresAt = null,
            readAt = readAt,
            nowMillis = now,
        )
        assertEquals(
            ExpiryRepair(readAt, readAt + timer * 1000L),
            repair,
            "the countdown restarts from the read, not from now: `now` would hand the recipient a " +
                "longer window than the sender allowed, by however long the row stayed broken",
        )
    }

    @Test
    fun aReadIncomingMessageWithNoReadTimestampFallsBackToNow() {
        val repair = ExpiryRepairDecision.repairFor(
            isOutbox = false,
            isRead = true,
            timerSeconds = timer,
            expireStartedAt = null,
            expiresAt = null,
            readAt = null,
            nowMillis = now,
        )
        assertEquals(ExpiryRepair(now, now + timer * 1000L), repair)
    }

    @Test
    fun anUnreadIncomingMessageIsNotStartedEarly() {
        assertNull(
            ExpiryRepairDecision.repairFor(
                isOutbox = false,
                isRead = false,
                timerSeconds = timer,
                expireStartedAt = null,
                expiresAt = null,
                readAt = null,
                nowMillis = now,
            ),
            "an incoming countdown starts at first read; starting it here would delete a message " +
                "before the recipient ever saw it",
        )
    }

    @Test
    fun anUnstartedOutgoingMessageIsNotArmed() {
        assertNull(
            ExpiryRepairDecision.repairFor(
                isOutbox = true,
                isRead = false,
                timerSeconds = timer,
                expireStartedAt = null,
                expiresAt = null,
                readAt = null,
                nowMillis = now,
            ),
            "the outgoing clock is armed by the send path after the handoff; a missing start means " +
                "the message has not been sent yet, and expiring a draft is not a repair",
        )
    }

    // endregion

    // region The tri-state must survive the repair pass

    @Test
    fun anExplicitSenderOffIsNeverGivenACountdown() {
        // `0` is the sender saying "never expire" (DisappearingFreezeDecision's tri-state). Inventing
        // a deadline for it here would delete messages the sender asked to keep - the exact policy
        // defeat the per-message-timer wave closed, reintroduced through the back door of a repair.
        assertNull(
            ExpiryRepairDecision.repairFor(
                isOutbox = false,
                isRead = true,
                timerSeconds = 0,
                expireStartedAt = null,
                expiresAt = null,
                readAt = readAt,
                nowMillis = now,
            ),
        )
        assertNull(
            ExpiryRepairDecision.repairFor(
                isOutbox = false,
                isRead = true,
                timerSeconds = 0,
                expireStartedAt = startedAt,
                expiresAt = null,
                readAt = readAt,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun aMessageWithNoTimerAtAllIsNeverGivenACountdown() {
        assertNull(
            ExpiryRepairDecision.repairFor(
                isOutbox = false,
                isRead = true,
                timerSeconds = null,
                expireStartedAt = null,
                expiresAt = null,
                readAt = readAt,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun aLegacyNegativeTimerIsTreatedAsOff() {
        assertNull(
            ExpiryRepairDecision.repairFor(
                isOutbox = false,
                isRead = true,
                timerSeconds = -1,
                expireStartedAt = null,
                expiresAt = null,
                readAt = readAt,
                nowMillis = now,
            ),
        )
    }

    // endregion

    @Test
    fun theLongestAllowedTimerDoesNotOverflow() {
        // The receive-path clamp caps the timer at 365 days; the arithmetic must stay in Long.
        val maxTimer = 365 * 24 * 60 * 60
        val repair = ExpiryRepairDecision.repairFor(
            isOutbox = false,
            isRead = true,
            timerSeconds = maxTimer,
            expireStartedAt = null,
            expiresAt = null,
            readAt = readAt,
            nowMillis = now,
        )
        assertEquals(readAt + maxTimer.toLong() * 1000L, repair?.expiresAt)
    }

    @Test
    fun repairIsIdempotent() {
        // Running the pass twice must not move the deadline: the second pass sees a row with both
        // stamps and declines. Otherwise every launch would push the deadline further out.
        val first = ExpiryRepairDecision.repairFor(
            isOutbox = false,
            isRead = true,
            timerSeconds = timer,
            expireStartedAt = null,
            expiresAt = null,
            readAt = readAt,
            nowMillis = now,
        )!!
        val second = ExpiryRepairDecision.repairFor(
            isOutbox = false,
            isRead = true,
            timerSeconds = timer,
            expireStartedAt = first.expireStartedAt,
            expiresAt = first.expiresAt,
            readAt = readAt,
            nowMillis = now + 86_400_000L,
        )
        assertNull(second)
    }
}
