package ch.threema.app.tasks

import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.services.MessageService
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.models.MessageId
import ch.threema.domain.types.IdentityString
import ch.threema.storage.models.AbstractMessageModel
import java.util.Date

private val logger = getThreemaLogger("EditMessageUtils")

fun runCommonEditMessageReceiveSteps(
    myIdentity: IdentityString,
    editMessageSenderIdentity: IdentityString,
    editMessageCreatedAt: Date,
    messageId: Long,
    receiver: MessageReceiver<*>,
    messageService: MessageService,
): AbstractMessageModel? {
    val apiMessageId = MessageId(messageId).toString()
    val message = messageService.getMessageModelByApiMessageIdAndReceiver(apiMessageId, receiver)

    // 2. "If referred-message is not defined or ..., discard"
    if (message == null) {
        logger.warn("Incoming Edit Message: No message found for id: {}", apiMessageId)
        return null
    }

    // 2.1 "If referred-message is ... or the sender is not the original sender of referred-message, discard"
    val originalMessageSender = if (message.isOutbox) {
        myIdentity
    } else {
        message.identity
    }
    if (editMessageSenderIdentity != originalMessageSender) {
        logger.warn(
            "Incoming Edit Message: original message's sender {} does not equal edit-message's sender {}",
            originalMessageSender,
            editMessageSenderIdentity,
        )
        return null
    }

    // 3. "If referred-message is not editable (...), discard"
    if (message.type?.canBeEdited != true) {
        logger.warn("Incoming Edit Message: Message of type {} cannot be edited", message.type)
        return null
    }

    // 4. "Edit referred-message ... and add an indicator to referred-message, informing the user that
    // the message has been edited by the sender at the message's (the EditMessage's) created-at."
    message.editedAt = editMessageCreatedAt

    return message
}
