package ch.threema.app.tasks

import ch.threema.base.ThreemaException
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.models.MessageId
import ch.threema.domain.protocol.csp.messages.EditMessageData
import ch.threema.domain.protocol.csp.messages.GroupEditMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.Task
import ch.threema.domain.taskmanager.TaskCodec
import ch.threema.domain.types.IdentityString
import java.util.Date
import kotlinx.serialization.Serializable

private val logger = getThreemaLogger("OutgoingGroupEditMessageTask")

/**
 * F1Whisper (seventh fork review, F7-03): this task carries NO edit plaintext. See
 * [OutgoingContactEditMessageTask] for the failure that removed it and for what the row now decides.
 */
class OutgoingGroupEditMessageTask(
    private val messageModelId: Int,
    private val messageId: MessageId,
    private val editedAt: Date,
    private val recipientIdentities: Set<IdentityString>,
) : OutgoingCspMessageTask() {
    override val type: String = "OutgoingGroupEditMessageTask"

    override suspend fun runSendingSteps(handle: ActiveTaskCodec) {
        val current = getGroupContentRow(messageModelId)
        val editedText = PersistentTaskRowGate.committedEdit(current, editedAt)
        if (current == null || editedText == null) {
            logger.info(
                "Not announcing the edit of group message {}: its row no longer carries it",
                messageModelId,
            )
            return
        }

        val group = groupService.getById(current.groupId)
            ?: throw ThreemaException("No group model found for groupId=${current.groupId}")

        val editedMessageIdLong = current.messageId!!.messageIdLong

        sendGroupMessage(
            group,
            groupService.getGroupMemberIdentities(group).toSet(),
            null,
            editedAt,
            messageId,
            createAbstractMessage = { createEditMessage(editedMessageIdLong, editedText) },
            handle,
        )
    }

    private fun createEditMessage(messageId: Long, editedText: String) = GroupEditMessage(
        EditMessageData(
            messageId = messageId,
            text = editedText,
        ),
    )

    override fun serialize(): SerializableTaskData = OutgoingGroupEditMessageData(
        messageModelId,
        messageId.messageId,
        editedAt.time,
        recipientIdentities,
    )

    @Serializable
    class OutgoingGroupEditMessageData(
        private val messageModelId: Int,
        private val messageId: ByteArray,
        private val editedAt: Long,
        private val recipientIdentities: Set<IdentityString>,
    ) : SerializableTaskData {
        override fun createTask(): Task<*, TaskCodec> =
            OutgoingGroupEditMessageTask(
                messageModelId = messageModelId,
                messageId = MessageId(messageId),
                editedAt = Date(editedAt),
                recipientIdentities = recipientIdentities,
            )
    }
}
