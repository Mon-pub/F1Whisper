package ch.threema.app.tasks

import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.models.MessageId
import ch.threema.domain.protocol.csp.messages.GroupTypingIndicatorMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.types.IdentityString
import java.util.Date

private val logger = getThreemaLogger("OutgoingGroupTypingIndicatorMessageTask")

/**
 * F1Whisper: send a [GroupTypingIndicatorMessage] to all recipients. Like the 1:1 typing task this
 * is a throw-away, non-persistent task ([serialize] returns null) so a stale typing indicator is
 * never re-sent after an app restart.
 */
class OutgoingGroupTypingIndicatorMessageTask(
    private val groupDatabaseId: Long,
    private val isTyping: Boolean,
    private val recipientIdentities: Set<IdentityString>,
) : OutgoingCspMessageTask() {
    override val type: String = "OutgoingGroupTypingIndicatorMessageTask"

    override suspend fun runSendingSteps(handle: ActiveTaskCodec) {
        val group = groupService.getById(groupDatabaseId)
        if (group == null) {
            logger.warn("Group ({}) is null while trying to send group typing indicator", groupDatabaseId)
            return
        }

        sendGroupMessage(
            group = group,
            recipients = recipientIdentities,
            messageModel = null,
            createdAt = Date(),
            messageId = MessageId.random(),
            createAbstractMessage = {
                GroupTypingIndicatorMessage().also { it.isTyping = isTyping }
            },
            handle = handle,
        )
    }

    override fun serialize(): SerializableTaskData? = null
}
