package ch.threema.app.services

import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (fourth fork review, F4-04): lifecycle tests for when an outgoing disappearing countdown starts.
 *
 * The defect had two halves. Text and location armed the clock immediately after `saveLocalModel`, BEFORE the persistent send task
 * existed, so a short-timer message composed offline expired and was hard-deleted before the task could ever load it - the message
 * vanished from the sender without ever reaching the recipient. Successful media retries and every poll armed nothing at all, so
 * messages advertised as disappearing were kept on the sender forever.
 *
 * These tests walk a real [MessageModel] through the state sequence each path actually produces and apply the production rule at each
 * step - [OutgoingClockDecision.resolveStart] and [OutgoingClockDecision.deadlineFor] - so what is asserted is the shipped rule, not a
 * restatement of it.
 *
 * Updated for the fifth review (F5-06): the rule now derives the start from the TRANSITION's own timestamp rather than from the wall
 * clock at arming time, and state and countdown are persisted as one conditional write. The arming helpers this test used to call are
 * gone with the second write they performed.
 *
 * [legacyArmingAtCreationDeletesTheMessageBeforeItIsEverSent] is the control: it applies the OLD rule inline, calls no production
 * decision, and shows the payload being destroyed while still queued.
 *
 * What this cannot cover, recorded rather than glossed: the two hook sites (`MessageServiceImpl.updateOutgoingMessageState` and the
 * group branch of `OutgoingCspMessageTask`, which sets the state directly and therefore needs its own call) run inside the service
 * graph and the task manager. That wiring stays on the device-matrix debt list; the rule it applies is what is pinned here.
 */
class OutgoingClockLifecycleTest {
    private val timerSeconds = 30

    private fun outgoingModel(
        timer: Int? = timerSeconds,
        type: MessageType = MessageType.TEXT,
    ) = MessageModel().apply {
        uid = "uid-1"
        identity = "AAAAAAAA"
        isOutbox = true
        this.type = type
        disappearingTimerSeconds = timer
        state = MessageState.PENDING
    }

    /**
     * The production rule, applied exactly as `MessageServiceImpl.applyOutgoingStateTransition` applies it: on every persisted
     * outgoing state change, resolve the start from THAT transition's timestamp and write start and deadline together.
     */
    private fun MessageModel.transitionTo(newState: MessageState, atMillis: Long = nextTransitionAt()) {
        state = newState
        if (!isOutbox) {
            return
        }
        val start = OutgoingClockDecision.resolveStart(state, state, atMillis, expireStartedAt) ?: return
        val deadline = OutgoingClockDecision.deadlineFor(start, disappearingTimerSeconds) ?: return
        expireStartedAt = start
        expiresAt = deadline
    }

    /** A monotonically increasing transition clock, so "a later receipt must not restart it" is a real ordering. */
    private var transitionClock = 1_700_000_000_000L

    private fun nextTransitionAt(): Long {
        transitionClock += 1_000L
        return transitionClock
    }

    private fun MessageModel.isCountingDown() = expireStartedAt != null

    // -----------------------------------------------------------------------------------------------------------------------------
    // The three lifecycles the review named.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a short-timer text composed offline does not count down until it is actually sent`() {
        val model = outgoingModel()

        // Composed and queued. The task manager will not run the send task until a connection exists.
        model.transitionTo(MessageState.SENDING)

        assertFalse(model.isCountingDown(), "a message whose only copy is still on this device must not be expiring")
        assertNull(model.expiresAt)
        assertFalse(
            DisappearingMessageService.isExpired(model),
            "this is the whole point: expiry must not be able to delete the payload the send task still needs",
        )

        // Connection returns, the archived task runs, the server acks.
        model.transitionTo(MessageState.SENT)

        assertTrue(model.isCountingDown(), "once it has left the device the countdown must start")
        assertNotNull(model.expiresAt)
        assertEquals(model.expireStartedAt!! + timerSeconds * 1000L, model.expiresAt)
    }

    @Test
    fun `a media send that fails and is retried successfully arms exactly once, on the success`() {
        val model = outgoingModel(type = MessageType.FILE)

        // First attempt: upload, handoff, then the send fails.
        model.transitionTo(MessageState.UPLOADING)
        model.transitionTo(MessageState.SENDING)
        model.transitionTo(MessageState.SENDFAILED)
        assertFalse(model.isCountingDown(), "a message that never left the device has no countdown to run")

        // resendFileMessage: back to PENDING, re-upload, hand off again - and this time it lands.
        model.transitionTo(MessageState.PENDING)
        model.transitionTo(MessageState.SENDING)
        model.transitionTo(MessageState.SENT)

        assertTrue(model.isCountingDown(), "the retry succeeded, so the timer the recipient was promised must run here too")
        val armedAt = model.expireStartedAt

        // Later receipts must not restart it.
        model.transitionTo(MessageState.DELIVERED)
        model.transitionTo(MessageState.READ)
        assertEquals(armedAt, model.expireStartedAt, "arming is idempotent; a receipt is not a second send")
    }

    @Test
    fun `a poll arms when its carrier message is sent`() {
        val model = outgoingModel(type = MessageType.BALLOT)

        model.transitionTo(MessageState.PENDING)
        assertFalse(model.isCountingDown())

        model.transitionTo(MessageState.SENT)

        assertTrue(model.isCountingDown(), "a poll carries the conversation timer like any other message and must count down")
        assertEquals(model.expireStartedAt!! + timerSeconds * 1000L, model.expiresAt)
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The boundary itself.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `only states that prove the message left the device open the boundary`() {
        for (state in listOf(MessageState.SENT, MessageState.DELIVERED, MessageState.READ)) {
            assertTrue(OutgoingClockDecision.hasLeftTheDevice(state), "$state means the message left this device")
        }
        for (state in listOf(
            MessageState.PENDING,
            MessageState.TRANSCODING,
            MessageState.UPLOADING,
            MessageState.SENDING,
            MessageState.SENDFAILED,
            MessageState.FS_KEY_MISMATCH,
            MessageState.CONSUMED,
        )) {
            assertFalse(OutgoingClockDecision.hasLeftTheDevice(state), "$state does not prove the message left this device")
        }
        assertFalse(OutgoingClockDecision.hasLeftTheDevice(null))
    }

    @Test
    fun `a notes group message arms on the READ its own send path sets`() {
        // A group with no other members is completed by setting READ directly, not SENT.
        val model = outgoingModel()

        model.transitionTo(MessageState.READ)

        assertTrue(model.isCountingDown(), "a notes-group message still has to obey the conversation timer")
    }

    @Test
    fun `a message with no timer never counts down whatever state it reaches`() {
        val model = outgoingModel(timer = null)

        model.transitionTo(MessageState.SENT)
        model.transitionTo(MessageState.READ)

        assertFalse(model.isCountingDown())
        assertNull(model.expiresAt)
    }

    @Test
    fun `a permanently failed send never counts down`() {
        val model = outgoingModel()

        model.transitionTo(MessageState.SENDING)
        model.transitionTo(MessageState.SENDFAILED)

        assertFalse(model.isCountingDown(), "a message that never left has no recipient-side policy to mirror")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Legacy control: the old rule, written out inline. Calls no production decision on purpose.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun legacyArmingAtCreationDeletesTheMessageBeforeItIsEverSent() {
        val model = outgoingModel()

        // The old rule: arm right after saveLocalModel, before the send task is even scheduled.
        val composedAt = System.currentTimeMillis() - (timerSeconds + 1) * 1000L
        model.expireStartedAt = composedAt
        model.expiresAt = composedAt + timerSeconds * 1000L
        model.state = MessageState.SENDING

        // The device was offline for longer than the timer, so the task has still not run.
        assertTrue(
            DisappearingMessageService.isExpired(model),
            "this is the defect: expiry deletes the row while the send task is still waiting for a connection",
        )
        assertFalse(
            OutgoingClockDecision.hasLeftTheDevice(model.state),
            "and the message had not left the device at any point in that window",
        )
    }
}
