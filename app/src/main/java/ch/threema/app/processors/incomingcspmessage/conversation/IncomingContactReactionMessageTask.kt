package ch.threema.app.processors.incomingcspmessage.conversation

import ch.threema.app.managers.ServiceManager
import ch.threema.app.processors.incomingcspmessage.IncomingCspMessageSubTask
import ch.threema.app.processors.incomingcspmessage.ReceiveStepsResult
import ch.threema.app.tasks.runCommonReactionMessageReceiveEmojiSequenceConversion
import ch.threema.app.tasks.runCommonReactionMessageReceiveSteps
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.protocol.csp.messages.ReactionMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.TriggerSource
import ch.threema.protobuf.csp.e2e.Reaction.ActionCase

private val logger = getThreemaLogger("IncomingContactReactionMessageTask")

class IncomingContactReactionMessageTask(
    message: ReactionMessage,
    triggerSource: TriggerSource,
    serviceManager: ServiceManager,
) : IncomingCspMessageSubTask<ReactionMessage>(message, triggerSource, serviceManager) {
    private val messageService by lazy { serviceManager.messageService }
    private val contactService by lazy { serviceManager.contactService }
    private val notificationService by lazy { serviceManager.notificationService }

    override suspend fun executeMessageStepsFromRemote(handle: ActiveTaskCodec) =
        processContactReactionMessage()

    override suspend fun executeMessageStepsFromSync() = processContactReactionMessage()

    private fun processContactReactionMessage(): ReceiveStepsResult {
        logger.debug("IncomingContactReactionMessageTask id: {}", message.data.messageId)

        val contactModel = contactService.getByIdentity(message.fromIdentity)
        if (contactModel == null) {
            logger.warn("Incoming Reaction Message: No contact found for ${message.fromIdentity}")
            return ReceiveStepsResult.DISCARD
        }

        val receiver = contactService.createReceiver(contactModel)
        val targetMessage = runCommonReactionMessageReceiveSteps(message, receiver, messageService)
            ?: return ReceiveStepsResult.DISCARD
        val emojiSequence =
            runCommonReactionMessageReceiveEmojiSequenceConversion(message.data.emojiSequenceBytes)
                ?: return ReceiveStepsResult.DISCARD

        messageService.saveEmojiReactionMessage(
            targetMessage,
            message.fromIdentity,
            message.data.actionCase,
            emojiSequence,
        )

        // F1Whisper: notify when someone reacts to one of the user's own messages
        if (message.data.actionCase == ActionCase.APPLY && targetMessage.isOutbox) {
            notificationService.showReactionNotification(
                receiver,
                targetMessage,
                message.fromIdentity,
                emojiSequence,
            )
        }

        return ReceiveStepsResult.SUCCESS
    }
}
