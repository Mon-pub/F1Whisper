package ch.threema.app.processors.incomingcspmessage.conversation

import ch.threema.app.managers.ListenerManager
import ch.threema.app.managers.ServiceManager
import ch.threema.app.processors.incomingcspmessage.IncomingCspMessageSubTask
import ch.threema.app.processors.incomingcspmessage.ReceiveStepsResult
import ch.threema.app.utils.MimeUtil
import ch.threema.base.utils.getThreemaLogger
import ch.threema.common.now
import ch.threema.domain.protocol.csp.messages.file.FileData
import ch.threema.domain.protocol.csp.messages.file.FileMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.TriggerSource
import ch.threema.storage.models.ContactModel
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.data.media.FileDataModel
import java.util.UUID

private val logger = getThreemaLogger("IncomingContactFileMessageTask")

class IncomingContactFileMessageTask(
    fileMessage: FileMessage,
    triggerSource: TriggerSource,
    serviceManager: ServiceManager,
) : IncomingCspMessageSubTask<FileMessage>(
    fileMessage,
    triggerSource,
    serviceManager,
) {
    private val messageService = serviceManager.messageService
    private val contactService = serviceManager.contactService
    private val contactRepository = serviceManager.modelRepositories.contacts

    override suspend fun executeMessageStepsFromRemote(handle: ActiveTaskCodec) =
        processIncomingMessage(
            triggerSource = TriggerSource.REMOTE,
        )

    override suspend fun executeMessageStepsFromSync() = processIncomingMessage(
        triggerSource = TriggerSource.SYNC,
    )

    private fun processIncomingMessage(triggerSource: TriggerSource): ReceiveStepsResult {
        // 0: Contact must exist locally at this point
        if (!contactRepository.existsByIdentity(message.fromIdentity)) {
            logger.error("Discarding message ${message.messageId}: Sender contact with identity ${message.fromIdentity} does not exist locally.")
            return ReceiveStepsResult.DISCARD
        }

        // 1: Check if the message already exists locally (from previous run(s) of this task).
        //    If so, cancel and accept that the download for the content(s) might not be complete.
        messageService.getContactMessageModel(
            message.messageId,
            message.fromIdentity,
        )?.run {
            // In this case the message has been processed earlier. Therefore we consider this as success. This causes the message to be reflected.
            logger.info("Message model already exists. Aborting successfully.")
            // F1Whisper (fourth fork review, F4-05): a redelivery is this message's only second chance. If the app died
            // between the insert and the freeze on the previous run, nothing else will ever revisit the timer - this
            // branch returns success and the message is gone from the server. Idempotent: it writes nothing when the row
            // already carries the sender's value.
            // Fifth review, F5-05: the repair applies an EXPLICITLY advertised value only. An absent one would be
            // re-resolved against the conversation timer as it is now, which re-froze old messages at a setting
            // chosen long after they arrived.
            messageService.freezeIncomingDisappearingPolicy(this, message.disappearingTimerSeconds)
            return ReceiveStepsResult.SUCCESS
        }

        val fileData: FileData = message.fileData ?: run {
            logger.error("Discarding message ${message.messageId}: Missing file data")
            return ReceiveStepsResult.DISCARD
        }

        // 2. Map the FileData object to a FileDataModel instance (field "downloaded" is false)
        val fileDataModel: FileDataModel = FileDataModel.fromIncomingFileData(fileData)

        // 3. Create the actual AbstractMessageModel containing the file and sender information
        val messageModel: MessageModel = createMessageModelFromFileMessage(
            fileMessage = message,
            fileDataModel = fileDataModel,
            fileData = fileData,
        )

        // 4. Un-archive the contact and set the the acquaintance level to "direct" because it is a 1:1 chat now
        if (triggerSource == TriggerSource.REMOTE) {
            contactService.setIsArchived(message.fromIdentity, false, triggerSource)
            contactService.setAcquaintanceLevel(
                message.fromIdentity,
                ContactModel.AcquaintanceLevel.DIRECT,
            )
        }

        // 5. Bump last updated timestamp if necessary to move conversation up in list
        if (message.bumpLastUpdate()) {
            contactService.bumpLastUpdate(message.fromIdentity)
        }

        // 6. F1Whisper E1: freeze the timer the SENDER advertised for THIS message, using the same
        //    transition the text/image/location/poll path uses in processIncomingContactMessage.
        //    This task builds its model by hand rather than through createLocalModel, so without
        //    this call an incoming file, video, voice message or document froze nothing and fell
        //    back to the RECIPIENT's own conversation timer at read time — the policy defeat the
        //    per-message-timer wave closed for text, still open on the path that carries most media.
        //
        //    F1Whisper (fourth fork review, F4-05): BEFORE the insert, not after it. The row and the
        //    sender's policy are now one write, so a process death cannot leave a stored message
        //    carrying the recipient's timer - which a redelivery would then make permanent, because
        //    the duplicate guard above returns success without reaching this point. Being before the
        //    insert also puts it before the listener, so MarkAsReadRoutine (which runs off that
        //    listener, on another thread) sees the frozen value rather than racing it.
        messageService.freezeIncomingDisappearingPolicyBeforeFirstWrite(messageModel, message.disappearingTimerSeconds)

        // 6a. Save message model and inform listeners about new message
        messageService.save(messageModel)

        ListenerManager.messageListeners.handle { messageListener ->
            messageListener.onNew(
                messageModel,
            )
        }

        // 7. Download thumbnail and content blob (if auto download enabled)
        //    We still return SUCCESS even if the blobs could net be downloaded
        processMediaContent(fileData, messageModel)

        return ReceiveStepsResult.SUCCESS
    }

    /**
     *  @return A new instance of `AbstractMessageModel` with type [MessageType.FILE] containing
     *  the `body` and `dataObject` from the passed file information.
     */
    private fun createMessageModelFromFileMessage(
        fileMessage: FileMessage,
        fileDataModel: FileDataModel,
        fileData: FileData,
    ): MessageModel {
        return MessageModel().apply {
            uid = UUID.randomUUID().toString()
            apiMessageId = message.messageId.toString()

            identity = fileMessage.fromIdentity

            this.fileData = fileDataModel
            messageContentsType = MimeUtil.getContentTypeFromFileData(fileDataModel)

            // F1Whisper: a media/file/voice reply-quote carries the quoted message's apiMessageId in
            // the "qi" file metadata key. Copy it into the type-agnostic quotedMessageId column so the
            // existing quote resolver/render path shows the quote header above the media bubble (same
            // path as text quotes). Absent key => null => no header (unchanged behavior).
            fileDataModel.quotedApiMessageId?.let { quotedMessageId = it }

            postedAt = message.date
            createdAt = now()

            messageFlags = message.messageFlags

            isOutbox = false
            isSaved = true

            correlationId = fileData.correlationId
            forwardSecurityMode = message.forwardSecurityMode
        }
    }

    /**
     *  **Synchronously**
     *
     *  Attempt to download the thumbnail and the actual media content. Even if
     *  the thumbnail download failed, we try to download the actual blob contents.
     */
    private fun processMediaContent(fileData: FileData, messageModel: MessageModel) {
        runCatching {
            messageService.downloadThumbnailIfPresent(fileData, messageModel)
        }.onSuccess { thumbnailWasDownloaded: Boolean ->
            if (thumbnailWasDownloaded) {
                ListenerManager.messageListeners.handle { messageListener ->
                    messageListener.onModified(
                        listOf(messageModel),
                    )
                }
            }
        }.onFailure { throwable ->
            logger.error("Unable to download thumbnail blob", throwable)
        }
        if (messageService.shouldAutoDownload(messageModel)) {
            runCatching {
                messageService.downloadMediaMessage(messageModel, null)
            }.onFailure { throwable ->
                // a failed blob auto-download should not be considered a failure as the user can try again manually
                logger.error("Unable to auto-download blob", throwable)
            }
        }
    }
}
