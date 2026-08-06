package ch.threema.app.services

import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageType
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper: state-machine tests for [DisappearingMessageService.freezeIncomingTimer] — the mutation half of the incoming
 * freeze, as opposed to [DisappearingFreezeDecisionTest] which covers the pure decision that feeds it.
 *
 * These pin the behaviours added after the security audit and the independent validation:
 *
 *  - an OUTGOING model is never touched, whatever a caller passes;
 *  - when the countdown has ALREADY started, the deadline is RE-DERIVED from the sender's timer (or cleared outright when
 *    the sender said OFF) instead of being left inconsistent with it;
 *  - when the message is already READ but has NO countdown, the clock is STARTED here, anchored at `readAt`. That cell is
 *    the one validation caught: `markAsRead` runs at most once (`MessageUtil.canMarkAsRead` requires `!isRead`), so a
 *    message it passed over while the model still had no timer would otherwise carry a live timer that never begins and
 *    be kept forever — Finding 3's user-visible outcome by another route.
 *
 * All three raced cells are reachable because the new-message listener fires before the receive path reaches the freeze,
 * so a concurrent `markAsRead` can get to the row first — with `saveMedia`'s disk write in between for image messages.
 *
 * **Scope, stated honestly**: this is a test of the state transition, NOT of the race. The interleaving itself —
 * listener ordering, the `MarkAsReadRoutine` thread, the fresh re-read in `MessageServiceImpl.applyIncomingFreeze`, and
 * the full-row semantics of `AbstractMessageModelFactory.buildContentValues` — needs a real database and a real service
 * graph, so it is instrumentation-level work and is not covered here. What is covered is that when a raced model DOES
 * reach this function, the resulting row is self-consistent and follows the sender's policy.
 *
 * Design: `.claude/tasks/disappearing-per-message-timer-metadata.md`.
 */
class DisappearingFreezeModelStateTest {

    private fun incomingModel(
        frozenTimer: Int? = null,
        expireStartedAt: Long? = null,
        expiresAt: Long? = null,
        type: MessageType = MessageType.TEXT,
        isRead: Boolean = false,
        readAt: Date? = null,
    ) = MessageModel().apply {
        this.uid = "uid-1"
        this.identity = "AAAAAAAA"
        this.isOutbox = false
        this.type = type
        this.disappearingTimerSeconds = frozenTimer
        this.expireStartedAt = expireStartedAt
        this.expiresAt = expiresAt
        this.isRead = isRead
        this.readAt = readAt
    }

    // ---- no-ops ----

    @Test
    fun `a null resolved timer leaves the model untouched`() {
        val model = incomingModel(frozenTimer = 30)

        assertFalse(
            DisappearingMessageService.freezeIncomingTimer(model, null),
            "null means 'nothing to store', so nothing may be mutated and nothing needs saving",
        )
        assertEquals(30, model.disappearingTimerSeconds, "the model's existing timer must survive untouched")
    }

    @Test
    fun `an outgoing model is never touched`() {
        val model = incomingModel(frozenTimer = 30).apply { isOutbox = true }

        assertFalse(
            DisappearingMessageService.freezeIncomingTimer(model, 0),
            "an outgoing message carries THIS device's own policy, already advertised and already counting down; " +
                "nothing arriving from the network may rewrite it",
        )
        assertEquals(30, model.disappearingTimerSeconds, "the outgoing timer must be exactly as it was")
    }

    @Test
    fun `a status model is never touched`() {
        val model = incomingModel(type = MessageType.STATUS)

        assertFalse(
            DisappearingMessageService.freezeIncomingTimer(model, 30),
            "status and system rows are never disappearing messages",
        )
        assertNull(model.disappearingTimerSeconds, "no timer may be stamped on a status row")
    }

    @Test
    fun `an unchanged timer is not rewritten`() {
        val model = incomingModel(frozenTimer = 30)

        assertFalse(
            DisappearingMessageService.freezeIncomingTimer(model, 30),
            "the value is already in force, so returning false spares the caller a pointless full-row write",
        )
    }

    // ---- the ordinary path: countdown not yet started ----

    @Test
    fun `an unstarted incoming model is frozen without starting the countdown`() {
        val model = incomingModel(frozenTimer = null)

        assertTrue(DisappearingMessageService.freezeIncomingTimer(model, 300), "a fresh freeze must report a mutation")
        assertEquals(300, model.disappearingTimerSeconds, "the sender's 300s must be frozen onto the model")
        assertNull(model.expireStartedAt, "the countdown still starts at first read, not at receive")
        assertNull(model.expiresAt, "no deadline may be derived before the countdown starts")
    }

    @Test
    fun `an explicit sender OFF overwrites the provisional local stamp`() {
        // createLocalModel stamped the RECIPIENT's 30s; the sender advertised OFF.
        val model = incomingModel(frozenTimer = 30)

        assertTrue(DisappearingMessageService.freezeIncomingTimer(model, 0), "overwriting 30 with an explicit 0 is a mutation")
        assertEquals(
            0,
            model.disappearingTimerSeconds,
            "the sender said OFF, so the provisional 30 must be overwritten with 0 — freezeTimer cannot express this, " +
                "which is why freezeIncomingTimer exists",
        )
    }

    // ---- the raced path: countdown already started from the provisional timer ----

    @Test
    fun `an already-running countdown is re-derived from the sender's longer timer`() {
        // markAsRead won the race and started a 30s countdown from the provisional stamp.
        val startedAt = 1_000_000L
        val model = incomingModel(frozenTimer = 30, expireStartedAt = startedAt, expiresAt = startedAt + 30_000L)

        assertTrue(DisappearingMessageService.freezeIncomingTimer(model, 300), "correcting a raced freeze is a mutation")
        assertEquals(300, model.disappearingTimerSeconds, "the sender's 300s must win over the provisional 30s")
        assertEquals(
            startedAt + 300_000L,
            model.expiresAt,
            "the deadline must be re-derived from the sender's timer; leaving it at startedAt+30s would delete the " +
                "message 30s in, against the sender's stated 300s",
        )
        assertEquals(startedAt, model.expireStartedAt, "the moment the message was read does not change")
    }

    @Test
    fun `an already-running countdown is re-derived from the sender's shorter timer`() {
        val startedAt = 1_000_000L
        val model = incomingModel(frozenTimer = 300, expireStartedAt = startedAt, expiresAt = startedAt + 300_000L)

        assertTrue(DisappearingMessageService.freezeIncomingTimer(model, 30), "correcting a raced freeze is a mutation")
        assertEquals(30, model.disappearingTimerSeconds, "the sender's 30s must win over the provisional 300s")
        assertEquals(
            startedAt + 30_000L,
            model.expiresAt,
            "shortening works the same way — the recipient may not retain a message longer than the sender allowed",
        )
    }

    // ---- the orphaned path: markAsRead already ran and declined to start a countdown ----
    // This cell — isRead == true AND expireStartedAt == null — is how a 30s timer could be frozen onto a message whose
    // clock never started, leaving it kept forever: the Finding 3 outcome by another route.

    @Test
    fun `an already-read message with no countdown gets its clock started from readAt`() {
        // Local timer OFF so createLocalModel stamped nothing; markAsRead won the race, found no timer,
        // started no countdown, and set isRead. It can never run again.
        val readAt = Date(1_000_000L)
        val model = incomingModel(frozenTimer = null, isRead = true, readAt = readAt)

        assertTrue(DisappearingMessageService.freezeIncomingTimer(model, 30), "freezing an orphaned model is a mutation")
        assertEquals(30, model.disappearingTimerSeconds, "the sender's 30s must be frozen on")
        assertEquals(
            readAt.time,
            model.expireStartedAt,
            "the clock must start when the message was actually READ, not now — using now would hand the recipient a " +
                "longer window than the sender allowed, by the whole receive-path delay",
        )
        assertEquals(readAt.time + 30_000L, model.expiresAt, "and the deadline follows from that instant")
    }

    @Test
    fun `an already-read message with no readAt timestamp falls back to starting the clock now`() {
        val before = System.currentTimeMillis()
        val model = incomingModel(frozenTimer = null, isRead = true, readAt = null)

        assertTrue(DisappearingMessageService.freezeIncomingTimer(model, 30), "the clock must still start")
        val startedAt = model.expireStartedAt
        assertNotNull(startedAt, "a read message with a live timer must never be left without a countdown")
        assertTrue(
            startedAt >= before && startedAt <= System.currentTimeMillis(),
            "with no read timestamp to anchor to, the clock starts now; got $startedAt outside the test window",
        )
        assertEquals(startedAt + 30_000L, model.expiresAt, "the deadline follows from the fallback instant")
    }

    @Test
    fun `an explicit sender OFF on an already-read message does not start a clock`() {
        val model = incomingModel(frozenTimer = 30, isRead = true, readAt = Date(1_000_000L))

        assertTrue(DisappearingMessageService.freezeIncomingTimer(model, 0), "overwriting 30 with 0 is still a mutation")
        assertEquals(0, model.disappearingTimerSeconds, "the sender said OFF")
        assertNull(model.expireStartedAt, "a message the sender said to keep must never be armed")
        assertNull(model.expiresAt, "and it must have no deadline")
    }

    @Test
    fun `an unread message is never given a clock here`() {
        val model = incomingModel(frozenTimer = null, isRead = false)

        assertTrue(DisappearingMessageService.freezeIncomingTimer(model, 30), "the freeze itself still applies")
        assertEquals(30, model.disappearingTimerSeconds, "the sender's timer is frozen on")
        assertNull(
            model.expireStartedAt,
            "an unread message's countdown correctly starts later, at markAsRead — starting it here would expire it " +
                "before the user ever saw it",
        )
        assertNull(model.expiresAt, "and no deadline may be derived yet")
    }

    @Test
    fun `an explicit sender OFF cancels an already-running countdown`() {
        val startedAt = 1_000_000L
        val model = incomingModel(frozenTimer = 30, expireStartedAt = startedAt, expiresAt = startedAt + 30_000L)

        assertTrue(DisappearingMessageService.freezeIncomingTimer(model, 0), "cancelling a raced countdown is a mutation")
        assertEquals(0, model.disappearingTimerSeconds, "the sender said OFF")
        assertNull(
            model.expireStartedAt,
            "a message the sender said to keep must not stay armed; enforceIfExpired short-circuits on a null " +
                "expireStartedAt, so leaving it set would delete it 30s after it was read",
        )
        assertNull(model.expiresAt, "and its deadline must go with it")
    }
}
