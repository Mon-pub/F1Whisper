package ch.threema.app.tasks

import ch.threema.app.services.monotonicMillis
import ch.threema.base.utils.getThreemaLogger
import ch.threema.common.now
import ch.threema.domain.models.MessageId
import ch.threema.domain.protocol.csp.messages.TypingIndicatorMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.types.IdentityString

private val logger = getThreemaLogger("OutgoingTypingIndicatorMessageTask")

class OutgoingTypingIndicatorMessageTask @JvmOverloads constructor(
    private val isTyping: Boolean,
    private val toIdentity: IdentityString,
    /** When this task was scheduled, on the monotonic clock. See [isStaleTypingIndicator]. */
    private val scheduledAtMillis: Long = monotonicMillis(),
) : OutgoingCspMessageTask() {
    override val type: String = "OutgoingTypingIndicatorMessageTask"

    override suspend fun runSendingSteps(handle: ActiveTaskCodec) {
        if (isStaleTypingIndicator(scheduledAtMillis)) {
            logger.info("Dropping a typing indicator for {}: it waited longer than it stays true", toIdentity)
            return
        }

        val message = TypingIndicatorMessage().also {
            it.isTyping = isTyping
        }

        sendContactMessage(
            message = message,
            messageModel = null,
            toIdentity = toIdentity,
            messageId = MessageId.random(),
            createdAt = now(),
            handle = handle,
        )
    }

    override fun serialize(): SerializableTaskData? = null
}
