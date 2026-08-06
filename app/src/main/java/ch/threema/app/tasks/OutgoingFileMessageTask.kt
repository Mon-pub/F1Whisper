package ch.threema.app.tasks

import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.messagereceiver.MessageReceiver.MessageReceiverType
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.domain.protocol.csp.messages.file.FileData
import ch.threema.domain.protocol.csp.messages.file.FileMessage
import ch.threema.domain.protocol.csp.messages.file.GroupFileMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.Task
import ch.threema.domain.taskmanager.TaskCodec
import ch.threema.domain.types.IdentityString
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.data.media.FileDataModel
import kotlinx.serialization.Serializable

private val logger = getThreemaLogger("OutgoingFileMessageTask")

class OutgoingFileMessageTask(
    private val messageModelId: Int,
    @MessageReceiverType
    private val receiverType: Int,
    private val recipientIdentities: Set<IdentityString>,
    private val thumbnailBlobId: ByteArray?,
) : OutgoingCspMessageTask() {
    private val myIdentity by lazy { userService.identity }

    private val isMultiDeviceActive by lazy { multiDeviceManager.isMultiDeviceActive }

    override val type: String = "OutgoingFileMessageTask"

    override suspend fun runSendingSteps(handle: ActiveTaskCodec) {
        when (receiverType) {
            MessageReceiver.Type_CONTACT -> sendContactMessage(handle)
            MessageReceiver.Type_GROUP -> sendGroupMessage(handle)
            else -> throw IllegalStateException("Invalid message receiver type $receiverType")
        }
    }

    override fun onSendingStepsFailed(e: Exception) {
        getMessageModel(receiverType, messageModelId)?.saveWithStateFailed()
    }

    private suspend fun sendContactMessage(handle: ActiveTaskCodec) {
        // F1Whisper (seventh fork review, F7-01): the row, not the cache. The blob id and encryption key live in
        // this row's body, so a cached copy of a deleted message is a complete, sendable payload.
        val messageModel = getContactContentRow(messageModelId) ?: return

        val fileDataModel = messageModel.fileData

        val apiMessageId = ensureMessageId(messageModel)

        // Create the message
        val message = FileMessage().apply {
            fileData = fileDataModel.toFileData(thumbnailBlobId, messageModel)
            toIdentity = messageModel.identity
            messageId = apiMessageId
        }

        sendContactMessage(
            message = message,
            messageModel = messageModel,
            toIdentity = messageModel.identity!!,
            messageId = apiMessageId,
            createdAt = messageModel.createdAt!!,
            handle = handle,
        )
    }

    private suspend fun sendGroupMessage(handle: ActiveTaskCodec) {
        // F1Whisper (seventh fork review, F7-01): the row, not the cache. See sendContactMessage above.
        val messageModel = getGroupContentRow(messageModelId) ?: return

        val group = groupService.getById(messageModel.groupId)
            ?: throw IllegalStateException("Could not get group for message model ${messageModel.apiMessageId}")

        val fileDataModel = messageModel.fileData

        sendGroupMessage(
            group = group,
            recipients = recipientIdentities,
            messageModel = messageModel,
            createdAt = messageModel.createdAt!!,
            messageId = ensureMessageId(messageModel),
            createAbstractMessage = {
                GroupFileMessage().apply {
                    fileData = fileDataModel.toFileData(thumbnailBlobId, messageModel)
                }
            },
            handle = handle,
        )
    }

    private fun FileDataModel.toFileData(
        thumbnailBlobId: ByteArray?,
        messageModel: AbstractMessageModel,
    ): FileData {
        // In case there are recipients or multi device is active, we need a blob id and an
        // encryption key. Otherwise the message will be invalid and cannot be sent. In case there
        // is no recipient and multi device is not active, the message is sent in a notes group
        // where we do not upload the blob.
        if (recipientIdentities.minus(myIdentity).isNotEmpty() || isMultiDeviceActive) {
            // Validate that the blob id has the correct length
            if (blobId == null || blobId.size != ProtocolDefines.BLOB_ID_LEN) {
                logger.error("Invalid blob id of length {}", blobId?.size)
                throw IllegalStateException("Invalid blob id")
            }

            // Validate that the encryption key has the correct length
            if (encryptionKey == null || encryptionKey.size != ProtocolDefines.BLOB_KEY_LEN) {
                logger.error("Invalid encryption key of length {}", encryptionKey?.size)
                throw IllegalStateException("Invalid blob encryption key")
            }
        }

        return FileData().also {
            it.fileBlobId = blobId
            it.thumbnailBlobId = thumbnailBlobId
            it.encryptionKey = encryptionKey
            it.mimeType = mimeType
            it.thumbnailMimeType = thumbnailMimeType
            it.fileSize = fileSize
            it.fileName = fileName
            it.renderingType = renderingType
            it.caption = caption
            it.correlationId = messageModel.correlationId
            // F1Whisper: the "loc" (listen-once consumed/burned) flag is LOCAL-ONLY state. It must
            // NEVER be serialized onto the wire: once the sender burns its own copy (burnOutgoing on
            // SENT), a later reflection/redelivery of the same model would otherwise carry loc=true to
            // recipients, who then render the voice as already-consumed so it "expires before being
            // listened" (the group instant-expiry bug). Strip it here; "lo" is kept so the recipient
            // still knows the message is listen-once.
            it.metaData = metaData?.filterKeys { key ->
                key != FileDataModel.METADATA_KEY_LISTEN_ONCE_CONSUMED
            }
        }
    }

    override fun serialize(): SerializableTaskData = OutgoingFileMessageData(
        messageModelId = messageModelId,
        receiverType = receiverType,
        recipientIdentities = recipientIdentities,
        thumbnailBlobId = thumbnailBlobId,
    )

    @Serializable
    class OutgoingFileMessageData(
        private val messageModelId: Int,
        @MessageReceiverType
        private val receiverType: Int,
        private val recipientIdentities: Set<IdentityString>,
        private val thumbnailBlobId: ByteArray?,
    ) : SerializableTaskData {
        override fun createTask(): Task<*, TaskCodec> =
            OutgoingFileMessageTask(
                messageModelId = messageModelId,
                receiverType = receiverType,
                recipientIdentities = recipientIdentities,
                thumbnailBlobId = thumbnailBlobId,
            )
    }
}
