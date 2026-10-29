package ch.threema.app.services

import ch.threema.storage.models.MessageState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper (tenth fork review, F10-03): the boundary at which a sender burns its own listen-once copy.
 *
 * **The defect.** The burn lived in `updateOutgoingMessageState`, the wrapper. F5-02/F5-06 correctly moved group
 * completion off premature queue dispatch onto real CSP/reflection acceptance, and the corrected group path calls the
 * lower-level `applyOutgoingStateTransition` directly so it can persist state, server timestamp, forward-security mode
 * and countdown as one write. That was right, and it stepped past the wrapper-only side effect. In the supplied trace,
 * group message 3474 was reflected, acknowledged by every recipient, and then played back by its own sender; the 1:1
 * control in the same log, message 999, burned as intended.
 *
 * **What is executable here.** The decision itself, exhaustively: it is a pure function over a state and two counts,
 * with no Android imports. That includes the case the review singles out, a non-notes group with intended recipients
 * and zero acceptance, which no `MessageState` can express.
 *
 * **What is asserted against the source, and why.** That the two completion paths reach the shared boundary, and that
 * acceptance is recorded after the server ack rather than before it. `MessageServiceImpl` cannot be constructed on the
 * JVM, and `runBundledMessagesSendSteps` needs a live task codec and the whole outgoing service graph, so neither is
 * reachable from a plain unit test; this is the same split, for the same reason, as `MediaMetadataWriteTest`. Every
 * source assertion below was proven red by removing the line it names.
 */
class OutgoingSenderBurnBoundaryTest {

    private val messageServiceImpl = File("src/main/java/ch/threema/app/services/MessageServiceImpl.java")
    private val outgoingCspMessageTask = File("src/main/java/ch/threema/app/tasks/OutgoingCspMessageTask.kt")
    private val outgoingCspMessageUtils = File("src/main/java/ch/threema/app/utils/OutgoingCspMessageUtils.kt")

    // -----------------------------------------------------------------------------------------------------------------
    // 1:1: the state answers on its own, and that is deliberately unchanged
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a one to one send burns once the state proves the message left the device`() {
        for (state in listOf(MessageState.SENT, MessageState.DELIVERED, MessageState.READ)) {
            assertTrue(
                OutgoingSendBoundaryDecision.burnsSenderCopy(state, OutgoingSendEvidence.contactSend()),
                "$state proves a 1:1 message left the device",
            )
        }
    }

    @Test
    fun `a one to one send that never left the device keeps the sender copy`() {
        for (state in listOf(
            MessageState.PENDING,
            MessageState.SENDING,
            MessageState.SENDFAILED,
            MessageState.FS_KEY_MISMATCH,
        )) {
            assertFalse(
                OutgoingSendBoundaryDecision.burnsSenderCopy(state, OutgoingSendEvidence.contactSend()),
                "$state does not prove a 1:1 message left the device",
            )
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Groups: counts, not state
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a group send accepted by every recipient burns`() {
        assertTrue(
            OutgoingSendBoundaryDecision.burnsSenderCopy(
                MessageState.SENT,
                OutgoingSendEvidence.groupSend(intendedRemoteRecipients = 3, acceptedRemoteRecipients = 3),
            ),
        )
    }

    @Test
    fun `a group send accepted by some recipients burns`() {
        // Partial acceptance still means the payload is on the server for someone to fetch, so the sender's copy has
        // done its job. The state here is FS_KEY_MISMATCH, which is how a partial group send reports itself.
        assertTrue(
            OutgoingSendBoundaryDecision.burnsSenderCopy(
                MessageState.FS_KEY_MISMATCH,
                OutgoingSendEvidence.groupSend(intendedRemoteRecipients = 3, acceptedRemoteRecipients = 1),
            ),
        )
    }

    @Test
    fun `a group send that no recipient accepted keeps the sender copy`() {
        // The case the review singles out, and the one no state can express: every intended recipient was filtered out
        // or rejected, so nothing reached the server and destroying the only copy would destroy the message.
        for (state in listOf(MessageState.SENT, MessageState.FS_KEY_MISMATCH)) {
            assertFalse(
                OutgoingSendBoundaryDecision.burnsSenderCopy(
                    state,
                    OutgoingSendEvidence.groupSend(intendedRemoteRecipients = 3, acceptedRemoteRecipients = 0),
                ),
                "$state must not be read as acceptance",
            )
        }
    }

    @Test
    fun `a notes group completion is itself the boundary`() {
        // No other members, so there is no remote recipient to wait for. Reaching the completion is the boundary, and
        // the completion already carries multi-device: the send steps call storeSentAt only after the sent-update
        // reflection has been acknowledged, so a multi-device notes message arrives here after reflection and a
        // local-only one after its durable local completion.
        assertTrue(
            OutgoingSendBoundaryDecision.burnsSenderCopy(
                MessageState.READ,
                OutgoingSendEvidence.groupSend(intendedRemoteRecipients = 0, acceptedRemoteRecipients = 0),
            ),
        )
    }

    @Test
    fun `a group with recipients is never mistaken for a notes group`() {
        // The distinction is the intended count, the same predicate the completion uses to choose READ. Collapsing the
        // two - for instance by deriving "notes group" from "nobody accepted" - would burn precisely the message that
        // reached nobody.
        assertFalse(
            OutgoingSendBoundaryDecision.burnsSenderCopy(
                MessageState.READ,
                OutgoingSendEvidence.groupSend(intendedRemoteRecipients = 2, acceptedRemoteRecipients = 0),
            ),
        )
    }

    @Test
    fun `the disappearing clock rule is not widened to cover this`() {
        // FS_KEY_MISMATCH deliberately does not prove a message left the device, and it must not start acquiring that
        // meaning to make the group burn work. The group decision takes counts precisely so this stays untouched.
        assertFalse(OutgoingClockDecision.hasLeftTheDevice(MessageState.FS_KEY_MISMATCH))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The wiring
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `both completion paths go through the one boundary`() {
        val impl = messageServiceImpl.readText()
        val wrapperBody = bodyOf(impl, "public void updateOutgoingMessageState(")
        assertTrue(
            wrapperBody.contains("completeOutgoingSend("),
            "the 1:1 wrapper must complete through the shared boundary",
        )
        val completionBody = bodyOf(impl, "public boolean applyOutgoingCompletion(")
        assertTrue(
            completionBody.contains("completeOutgoingSend("),
            "the group entry point must complete through the same shared boundary",
        )
        assertTrue(
            outgoingCspMessageTask.readText().contains("messageService.applyOutgoingCompletion("),
            "the group task must record its completion through applyOutgoingCompletion, not through the bare " +
                "transition; going straight to the transition is exactly how the burn went missing",
        )
    }

    @Test
    fun `the group completion reports the recipients it actually reached`() {
        // F11-04 sharpened the intended count from the raw recipient list to the canonical intended-remote set
        // (minus self, intersected with current members, minus a non-receiving creator); the accepted side is
        // unchanged. OutgoingNotesGroupFileSendTest executes the classification this pin only spells.
        assertTrue(
            outgoingCspMessageTask.readText()
                .contains("OutgoingSendEvidence.groupSend(intendedRemoteRecipients.size, acceptedRemoteRecipients)"),
            "the group completion must carry the intended and accepted counts, since no state distinguishes them",
        )
    }

    @Test
    fun `acceptance is recorded after the server ack, not before it`() {
        // Order is the whole content of this assertion. Recording before the await would count a recipient whose ack
        // never arrived, which turns "nobody accepted" into "everybody accepted" and burns the one copy that was left.
        val body = bodyOf(outgoingCspMessageUtils.readText(), "    suspend fun awaitServerAck(")
        val awaitIndex = body.indexOf("handle.awaitOutgoingMessageAck(")
        val recordIndex = body.indexOf("acceptedRecipients.add(")
        assertTrue(awaitIndex >= 0, "the server ack must still be awaited")
        assertTrue(recordIndex >= 0, "acceptance must be recorded")
        assertTrue(recordIndex > awaitIndex, "acceptance must be recorded only after the ack has been awaited")
    }

    @Test
    fun `the completion side effects run only when the conditional write applied`() {
        // A completion that lost the write is not this caller's completion: the row is gone, deleted for everyone, or
        // has moved on. Burning from that stale detached model would delete files for a message it no longer owns.
        val body = bodyOf(messageServiceImpl.readText(), "private void completeOutgoingSend(")
        assertTrue(
            body.contains("if (!transitionApplied) {"),
            "the shared boundary must refuse a transition that did not apply",
        )
    }

    /**
     * The body of the method whose declaration starts with [signature], by brace matching from the first `{` after it.
     */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "signature not found: $signature" }
        var index = source.indexOf('{', start)
        require(index >= 0) { "no body for: $signature" }
        var depth = 0
        val body = StringBuilder()
        while (index < source.length) {
            val character = source[index]
            if (character == '{') depth++
            if (depth > 0) body.append(character)
            if (character == '}') {
                depth--
                if (depth == 0) break
            }
            index++
        }
        return body.toString()
    }
}
