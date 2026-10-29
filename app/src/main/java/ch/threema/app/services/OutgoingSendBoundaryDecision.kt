package ch.threema.app.services

import ch.threema.storage.models.MessageState

/**
 * F1Whisper (fifth fork review, F5-02): the state a dispatching send pipeline may write once it has handed a message to
 * the send layer.
 *
 * **The defect.** The media pipeline chose between `SENDING` and `SENT` with
 * `shouldSendMediaData() && offerRetry()`. A group returns `false` from `offerRetry()` - a question about whether the
 * RETRY UI should be offered, never a send boundary - so group media was recorded as `SENT` the instant its
 * `OutgoingFileMessageTask` had been SCHEDULED. Scheduling is asynchronous and task execution waits for a chat-server
 * connection, so `SENT` was being claimed for a message that had not left the device and might not for hours.
 *
 * With F4-04 arming the disappearing countdown at exactly that boundary, the consequence was concrete: complete the blob
 * upload, lose the connection, wait past a short timer, and expiry deleted the row while the task was still queued. On
 * reconnect the task could not load its payload and sent nothing. The media disappeared from the sender's own chat
 * without ever reaching the group - the timer destroying the payload it was supposed to govern, which is the exact
 * failure F4-04 was written to end and which survived on this path because an upstream state NAME was trusted as a
 * boundary.
 *
 * **The rule.** A pipeline may write a terminal state only when nothing else is going to. If some later acknowledgement
 * is authoritative - a server ack for a real group, a reflect ack for a multi-device notes group, the contact task's own
 * completion - the pipeline leaves the message pre-terminal and that acknowledgement writes the terminal state, its
 * timestamp and the countdown together (F5-06). Only a receiver with no remote completion at all, a local-only notes
 * group or a distribution-list record, completes locally.
 *
 * No Android imports, so the rule is unit-testable without a device.
 */
object OutgoingSendBoundaryDecision {

    /**
     * @param hasPendingRemoteCompletion [ch.threema.app.messagereceiver.MessageReceiver.hasPendingRemoteCompletion]
     * @return the state to record now.
     */
    @JvmStatic
    fun stateAtDispatch(hasPendingRemoteCompletion: Boolean): MessageState =
        if (hasPendingRemoteCompletion) MessageState.SENDING else MessageState.SENT

    /**
     * F1Whisper (sixth fork review, F6-04): whether creating this message IS its completion, so it may be inserted
     * terminal with its countdown already running.
     *
     * **The defect.** A notes group is a group with no other members, and the text and location creation paths recorded
     * one as `SENT` before the insert so the user is not shown a spinner for a send that has nowhere to go. That
     * reasoning holds only while nothing else is going to acknowledge the message - and with multi-device active
     * something is: the message is reflected to the linked devices, and the group task stores its completion only after
     * the reflection is acknowledged. Deciding on the empty member set alone therefore started the disappearing
     * countdown at composition. Compose offline with a short timer, stay offline past it, and expiry deleted the row
     * before the queued task ever ran; on reconnect the task found no message, so the linked device never received it.
     * The content disappeared from the device that wrote it without reaching anywhere.
     *
     * Empty CSP recipients and "no remote completion" are different questions, and this is the second one.
     *
     * @param hasNoOtherMembers          the empty-recipient condition the creation paths already computed.
     * @param hasPendingRemoteCompletion [ch.threema.app.messagereceiver.MessageReceiver.hasPendingRemoteCompletion],
     *                                   which is true whenever multi-device is active.
     */
    @JvmStatic
    fun completesLocally(hasNoOtherMembers: Boolean, hasPendingRemoteCompletion: Boolean): Boolean =
        hasNoOtherMembers && !hasPendingRemoteCompletion

    /**
     * F1Whisper (tenth fork review, F10-03): whether a completion that ACTUALLY APPLIED has proved enough for the
     * sender to burn its own listen-once copy.
     *
     * **The defect.** The burn lived in `updateOutgoingMessageState`, the wrapper. F5-02/F5-06 correctly moved group
     * completion off premature queue dispatch and onto real CSP/reflection acceptance, and the corrected group path
     * calls the lower-level transition directly so it can persist state, server timestamp, forward-security mode and
     * countdown as one write. Correct in itself, and it stepped straight past the wrapper-only side effect: in the
     * supplied trace, group message 3474 was reflected, acknowledged by every recipient, and then played back by its
     * own sender. The 1:1 control in the same log burned as intended.
     *
     * **Why the state cannot answer this for a group.** [MessageState.FS_KEY_MISMATCH] deliberately does not satisfy
     * [OutgoingClockDecision.hasLeftTheDevice], and it cannot distinguish a send that reached some members from one
     * that reached none: it means "at least one rejection", not "no acceptance". Nor can `SENT` distinguish them, since
     * a send to recipients who were all filtered out reports the same state as one that reached everybody. So the
     * decision takes counts, not a state: it burns when at least one remote recipient actually accepted the payload,
     * and keeps the sender copy when none did.
     *
     * **Notes groups keep their own rule.** A group with no other members has no remote recipient to wait for, so
     * reaching its completion IS the boundary - and that completion already carries multi-device: the send steps call
     * `storeSentAt` only after the sent-update reflection has been acknowledged, so a multi-device notes message
     * reaches here after reflection and a local-only one after its durable local completion. An unsent draft reaches
     * neither.
     *
     * No Android imports, so the rule is unit-testable without a device.
     */
    @JvmStatic
    fun burnsSenderCopy(state: MessageState?, evidence: OutgoingSendEvidence): Boolean {
        if (!evidence.isGroupSend) {
            return OutgoingClockDecision.hasLeftTheDevice(state)
        }
        if (evidence.intendedRemoteRecipients == 0) {
            return true
        }
        return evidence.acceptedRemoteRecipients > 0
    }
}

/**
 * F1Whisper (tenth fork review, F10-03): what a completed outgoing send actually achieved, as opposed to what its
 * [MessageState] is called.
 *
 * Deliberately counts rather than flags. "Some recipients rejected" and "no recipient accepted" are different facts,
 * only the second of them keeps the sender's copy, and no single state distinguishes them.
 */
class OutgoingSendEvidence private constructor(
    val isGroupSend: Boolean,
    val intendedRemoteRecipients: Int,
    val acceptedRemoteRecipients: Int,
) {
    companion object {
        /**
         * A 1:1 send, whose completion the state already answers on its own: there is exactly one recipient, and the
         * task's own completion is what records the state.
         */
        @JvmStatic
        fun contactSend(): OutgoingSendEvidence =
            OutgoingSendEvidence(isGroupSend = false, intendedRemoteRecipients = 1, acceptedRemoteRecipients = 1)

        /**
         * @param intendedRemoteRecipients the recipient set the send was directed at. Zero means a notes group, the
         *   same predicate the completion uses to choose [MessageState.READ].
         * @param acceptedRemoteRecipients how many of them the server acknowledged. Recipients that were filtered out
         *   before anything was sent, for instance because they are blocked, count as intended and not accepted.
         */
        @JvmStatic
        fun groupSend(intendedRemoteRecipients: Int, acceptedRemoteRecipients: Int): OutgoingSendEvidence =
            OutgoingSendEvidence(
                isGroupSend = true,
                intendedRemoteRecipients = intendedRemoteRecipients,
                acceptedRemoteRecipients = acceptedRemoteRecipients,
            )
    }
}
