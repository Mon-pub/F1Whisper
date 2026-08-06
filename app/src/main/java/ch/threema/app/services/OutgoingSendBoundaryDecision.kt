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
}
