package ch.threema.app.tasks

import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.models.MessageId
import ch.threema.domain.protocol.csp.messages.EditMessage
import ch.threema.domain.protocol.csp.messages.EditMessageData
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.Task
import ch.threema.domain.taskmanager.TaskCodec
import ch.threema.domain.types.IdentityString
import java.util.Date
import kotlinx.serialization.Serializable

private val logger = getThreemaLogger("OutgoingContactEditMessageTask")

/**
 * F1Whisper (seventh fork review, F7-03): this task carries NO edit plaintext.
 *
 * It used to be constructed with the new text and serialised with it, and it was archived BEFORE the local edit was
 * attempted. So the plaintext sat at rest in the task archive for as long as the device was offline, and if
 * delete-for-everyone landed in the meantime the task still loaded the soft-deleted parent row and transmitted the new
 * text - after the message had already been deleted, with the local row showing nothing.
 *
 * Now the edit is committed locally first, in one transaction with its history entry, and this task announces what the
 * ROW says. The archive therefore holds only a local id, a message id and a timestamp, and the row is both the content
 * and the permission: a row that has gone, has been deleted for everyone, or has been superseded by a newer edit
 * announces nothing. See [PersistentTaskRowGate].
 */
class OutgoingContactEditMessageTask(
    private val toIdentity: IdentityString,
    private val messageModelId: Int,
    private val messageId: MessageId,
    private val editedAt: Date,
) : OutgoingCspMessageTask() {
    override val type: String = "OutgoingContactEditMessageTask"

    override suspend fun runSendingSteps(handle: ActiveTaskCodec) {
        val current = getContactContentRow(messageModelId)
        val editedText = PersistentTaskRowGate.committedEdit(current, editedAt)
        if (current == null || editedText == null) {
            logger.info(
                "Not announcing the edit of contact message {}: its row no longer carries it",
                messageModelId,
            )
            return
        }

        val editMessage = EditMessage(
            EditMessageData(
                messageId = current.messageId!!.messageIdLong,
                text = editedText,
            ),
        )

        sendContactMessage(
            message = editMessage,
            messageModel = null,
            toIdentity = toIdentity,
            messageId = messageId,
            createdAt = editedAt,
            handle = handle,
        )
    }

    override fun serialize(): SerializableTaskData = OutgoingContactEditMessageData(
        toIdentity = toIdentity,
        messageModelId = messageModelId,
        messageId = messageId.messageId,
        editedAt = editedAt.time,
    )

    @Serializable
    class OutgoingContactEditMessageData(
        private val toIdentity: IdentityString,
        private val messageModelId: Int,
        private val messageId: ByteArray,
        private val editedAt: Long,
    ) : SerializableTaskData {
        override fun createTask(): Task<*, TaskCodec> =
            OutgoingContactEditMessageTask(
                toIdentity = toIdentity,
                messageModelId = messageModelId,
                messageId = MessageId(messageId),
                editedAt = Date(editedAt),
            )
    }
}
