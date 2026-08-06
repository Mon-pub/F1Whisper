package ch.threema.app.services

import ch.threema.storage.models.MessageState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (fifth fork review, F5-02 / F5-06): the outgoing send boundary and the one durable terminal transition.
 *
 * Two defects meet here.
 *
 * **F5-02.** The media pipeline chose its post-dispatch state with `shouldSendMediaData() && offerRetry()`. A group
 * returns `false` from `offerRetry()` - a question about the retry UI, never a send boundary - so group media was
 * recorded as `SENT` the instant its `OutgoingFileMessageTask` had been SCHEDULED. Scheduling is asynchronous and
 * execution waits for a chat-server connection, so with a disappearing timer the countdown started at enqueue: expiry
 * deleted the row while the task was still queued, and on reconnect the task found nothing to load and sent nothing. The
 * media disappeared from the sender without ever reaching the group.
 *
 * **F5-06.** Terminal state and countdown were TWO writes. A process death between them left a sent message with no
 * deadline - permanently, because the startup repair pass deliberately refuses outgoing rows with no start. The second
 * write also read the wall clock instead of the transition's own timestamp, so a `SENT` update reflected from another
 * device started a full interval from the moment the reflection was processed, extending the message's life by the
 * reflection delay.
 *
 * Both rules are pure and are tested by RUNNING them. The write they feed is executable and is tested against real
 * SQLite in `MessageRowUpdateTest`. What is left is that the call sites use them, asserted narrowly against the source;
 * each of those assertions was proven red by removing the line it names.
 */
class OutgoingTerminalTransitionTest {

    private val messageServiceImpl = File("src/main/java/ch/threema/app/services/MessageServiceImpl.java")
    private val cspTask = File("src/main/java/ch/threema/app/tasks/OutgoingCspMessageTask.kt")
    private val groupService = File("src/main/java/ch/threema/app/services/GroupServiceImpl.java")
    private val groupReceiver = File("src/main/java/ch/threema/app/messagereceiver/GroupMessageReceiver.java")
    private val planner = File("src/main/java/ch/threema/app/services/OutgoingTransitionPlanner.java")

    private val sentAt = 1_700_000_000_000L

    // -----------------------------------------------------------------------------------------------------------------------------
    // F5-02: what a dispatching pipeline may claim.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a send whose completion is still to come stays pre-terminal`() {
        assertEquals(
            MessageState.SENDING,
            OutgoingSendBoundaryDecision.stateAtDispatch(hasPendingRemoteCompletion = true),
            "claiming SENT for a message still sitting in an offline task queue is what let expiry delete its payload",
        )
    }

    @Test
    fun `a send with nothing left to wait for completes locally`() {
        assertEquals(
            MessageState.SENT,
            OutgoingSendBoundaryDecision.stateAtDispatch(hasPendingRemoteCompletion = false),
        )
    }

    @Test
    fun `a group defers its terminal state exactly when it has a recipient or a reflection to reach`() {
        val source = groupReceiver.readText()
        val body = bodyOf(source, "public boolean hasPendingRemoteCompletion()")

        assertTrue(
            body.contains("return shouldSendMediaData();"),
            "the boundary must be the same condition as 'is there a remote recipient or a multi-device reflection', not " +
                "a second re-derivation of it that can drift",
        )
    }

    @Test
    fun `the media pipeline asks the boundary and not the retry-UI question`() {
        val source = messageServiceImpl.readText()

        assertEquals(
            2,
            Regex("OutgoingSendBoundaryDecision\\.stateAtDispatch\\(getReceiver\\(\\)\\.hasPendingRemoteCompletion\\(\\)\\)")
                .findAll(source).count(),
            "both of the media send machine's state writes must use the boundary",
        )
        assertFalse(
            source.contains("getReceiver().shouldSendMediaData() && getReceiver().offerRetry()"),
            "this was the defect: offerRetry() is false for a group, so group media claimed SENT at enqueue",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // F5-06: where the countdown starts.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a send starts the clock at the moment the server accepted it`() {
        assertEquals(
            sentAt,
            OutgoingClockDecision.resolveStart(MessageState.SENT, MessageState.SENT, sentAt, currentStartMillis = null),
        )
        assertEquals(
            sentAt + 30_000L,
            OutgoingClockDecision.deadlineFor(sentAt, timerSeconds = 30),
        )
    }

    @Test
    fun `a reflected send preserves the timestamp it was given rather than starting from now`() {
        // The reflection was processed five minutes after the other device sent the message.
        val processedAt = sentAt + 300_000L

        val start = OutgoingClockDecision.resolveStart(MessageState.SENT, MessageState.SENT, sentAt, currentStartMillis = null)

        assertEquals(sentAt, start, "starting from $processedAt would extend the message's life by the reflection delay")
    }

    @Test
    fun `an overtaking receipt starts the clock provisionally and a later authoritative send time shortens it`() {
        val deliveredAt = sentAt + 2_000L

        val provisional = OutgoingClockDecision.resolveStart(MessageState.DELIVERED, MessageState.DELIVERED, deliveredAt, currentStartMillis = null)
        assertEquals(deliveredAt, provisional, "a receipt proves the message left, so it may start the clock")

        // Sixth review, F6-03: the persisted state stays DELIVERED, because that downgrade is correctly refused; the
        // authority to move the clock comes from the SENT being PROCESSED. Asking with DELIVERED for both, as
        // production did, is the defect OutgoingTransitionIntegrationTest reproduces against a real row.
        val corrected =
            OutgoingClockDecision.resolveStart(MessageState.SENT, MessageState.DELIVERED, sentAt, currentStartMillis = deliveredAt)
        assertEquals(sentAt, corrected, "the authoritative send time is earlier, and shortening is always safe")
    }

    @Test
    fun `nothing may move the start later`() {
        assertNull(
            OutgoingClockDecision.resolveStart(MessageState.READ, MessageState.READ, sentAt + 60_000L, currentStartMillis = sentAt),
            "a read receipt arriving a minute later must not hand the recipient another minute",
        )
        assertNull(
            OutgoingClockDecision.resolveStart(MessageState.DELIVERED, MessageState.DELIVERED, sentAt + 60_000L, currentStartMillis = sentAt),
        )
        assertNull(
            OutgoingClockDecision.resolveStart(MessageState.SENT, MessageState.SENT, sentAt + 60_000L, currentStartMillis = sentAt),
            "not even a duplicate SENT with a later timestamp",
        )
    }

    @Test
    fun `duplicate transitions are idempotent`() {
        assertNull(OutgoingClockDecision.resolveStart(MessageState.SENT, MessageState.SENT, sentAt, currentStartMillis = sentAt))
        assertNull(OutgoingClockDecision.resolveStart(MessageState.DELIVERED, MessageState.DELIVERED, sentAt, currentStartMillis = sentAt))
        assertNull(OutgoingClockDecision.resolveStart(MessageState.READ, MessageState.READ, sentAt, currentStartMillis = sentAt))
    }

    @Test
    fun `a message that has not left the device starts nothing`() {
        for (state in listOf(
            MessageState.PENDING,
            MessageState.TRANSCODING,
            MessageState.UPLOADING,
            MessageState.SENDING,
            MessageState.SENDFAILED,
            MessageState.FS_KEY_MISMATCH,
        )) {
            assertNull(
                OutgoingClockDecision.resolveStart(state, state, sentAt, currentStartMillis = null),
                "$state describes a message whose only copy is still here",
            )
        }
    }

    @Test
    fun `a message with no timer gets no deadline`() {
        assertNull(OutgoingClockDecision.deadlineFor(sentAt, timerSeconds = null))
        assertNull(OutgoingClockDecision.deadlineFor(sentAt, timerSeconds = 0))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // F5-06: the wiring.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `state timestamp and countdown are one write`() {
        // Sixth review, F6-03: the decision moved to OutgoingTransitionPlanner, where it is executable against a real
        // row (see OutgoingTransitionIntegrationTest). What stays here is that it is still ONE write.
        val body = bodyOf(messageServiceImpl.readText(), "public boolean applyOutgoingStateTransition(")
        val planner = bodyOf(planner.readText(), "public static MessageRowUpdate plan(")

        assertTrue(planner.contains("update.set(AbstractMessageModel.COLUMN_STATE, state.toString())"))
        assertTrue(planner.contains("update.set(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT, resolvedStart)"))
        assertEquals(
            1,
            Regex("applyRowUpdate\\(current, built\\)").findAll(body).count(),
            "one transition, one write: splitting them is what left a sent message with no deadline after a process kill",
        )
        assertTrue(
            planner.contains("transitionAt.getTime()"),
            "the start must come from the transition's own timestamp, not from the wall clock at arming time",
        )
        assertTrue(
            planner.contains("update.expect(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT, priorStart)"),
            "and the row must still hold the start the decision saw, so no write can move it later",
        )
        assertFalse(
            body.contains("save(messageModel)"),
            "a full-row upsert here could recreate a message deleted during the send",
        )
    }

    @Test
    fun `updateOutgoingMessageState no longer arms the clock in a second write`() {
        val body = bodyOf(messageServiceImpl.readText(), "public void updateOutgoingMessageState(")

        assertTrue(body.contains("applyOutgoingStateTransition(messageModel, state, date, null, false)"))
        assertFalse(
            body.contains("armOutgoingClockIfSent"),
            "arming after the synchronized block was the second write, and the process-death gap between them",
        )
    }

    @Test
    fun `the group completion is one write carrying the acceptance timestamp`() {
        val source = cspTask.readText()
        val body = bodyOf(source, "suspend fun sendGroupMessage(")

        assertTrue(body.contains("var acceptedAt: Date? = null"), "the acceptance timestamp is captured, not spent twice")
        assertTrue(
            body.contains("messageService.applyOutgoingStateTransition("),
            "state, timestamp, forward-security mode and countdown go to disk together",
        )
        assertFalse(body.contains("messageService.save(messageModel)"), "these were the two full-row saves it replaced")
        assertFalse(body.contains("armOutgoingClockIfSent"), "and this was the third write")
    }

    @Test
    fun `the contact completion persists forward security as one column`() {
        val body = bodyOf(cspTask.readText(), "suspend fun sendContactMessage(")

        assertTrue(body.contains("messageService.updateForwardSecurityMode(messageModel, forwardSecurityMode)"))
        assertFalse(
            body.contains("messageService.save(messageModel)"),
            "this callback full-row-saved a model captured when the task started, over the terminal transition",
        )
    }

    @Test
    fun `the task-layer terminal failure is conditional`() {
        val body = bodyOf(cspTask.readText(), "fun AbstractMessageModel.saveWithStateFailed()")

        assertTrue(
            body.contains("messageService.applyOutgoingStateTransition(this, MessageState.SENDFAILED, Date(), null, true)"),
            "including the bypass, because canChangeToState would skip the non-retryable marker for an edge state and " +
                "the auto-resend scan would then retry forever",
        )
        assertFalse(body.contains("messageService.save(this)"))
    }

    @Test
    fun `the resolved-reject cleanup starts a previously-unstarted clock`() {
        val body = bodyOf(groupService.readText(), "public void runRejectedMessagesRefreshSteps(")

        assertTrue(
            body.contains("messageService.applyOutgoingStateTransition(message, MessageState.SENT, resolvedAt, null, false)"),
            "this is a SUCCESSFUL terminal writer - the last rejecting member has gone - and writing the state column " +
                "directly left the message terminal and untimed, which the repair pass refuses to fix for outgoing rows",
        )
    }

    @Test
    fun `a notes group's local completion is durable`() {
        val source = groupReceiver.readText()

        for (path in listOf("public void createAndSendTextMessage(", "public void createAndSendLocationMessage(")) {
            assertTrue(
                bodyOf(source, path).contains("startNotesGroupCountdown(messageModel, completesLocally)"),
                "$path writes SENT before the insert to spare the user a spinner, so that IS the completion and it must " +
                    "carry the countdown; the CSP task that would otherwise start it does not run until the device is online",
            )
        }
        assertTrue(
            bodyOf(source, "private void startNotesGroupCountdown(").contains("applyOutgoingStateTransition("),
            "and it must go through the one clock-aware transition",
        )
    }

    /** The text from [signature] to the end of its body, matched by brace depth. */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "this test's anchor has drifted: $signature")
        var depth = 0
        var seenOpen = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> {
                    depth++
                    seenOpen = true
                }

                '}' -> {
                    depth--
                    if (seenOpen && depth == 0) {
                        return source.substring(start, index + 1)
                    }
                }
            }
        }
        error("unbalanced braces after $signature")
    }
}
