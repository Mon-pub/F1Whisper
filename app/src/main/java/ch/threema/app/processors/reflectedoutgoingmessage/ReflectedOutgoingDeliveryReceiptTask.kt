package ch.threema.app.processors.reflectedoutgoingmessage

import ch.threema.app.managers.ListenerManager
import ch.threema.app.managers.ServiceManager
import ch.threema.app.utils.MessageUtil
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.protocol.csp.messages.DeliveryReceiptMessage
import ch.threema.protobuf.common.CspE2eMessageType
import ch.threema.protobuf.d2d.OutgoingMessage
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageState
import java.util.Date

private val logger = getThreemaLogger("ReflectedOutgoingDeliveryReceiptTask")

internal class ReflectedOutgoingDeliveryReceiptTask(
    outgoingMessage: OutgoingMessage,
    serviceManager: ServiceManager,
) : ReflectedOutgoingContactMessageTask<DeliveryReceiptMessage>(
    outgoingMessage = outgoingMessage,
    message = DeliveryReceiptMessage.fromReflected(outgoingMessage),
    type = CspE2eMessageType.DELIVERY_RECEIPT,
    serviceManager = serviceManager,
) {
    private val messageService by lazy { serviceManager.messageService }
    private val notificationService by lazy { serviceManager.notificationService }
    private val myIdentity by lazy { serviceManager.identityStore.getIdentityString()!! }

    override fun processOutgoingMessage() {
        logger.info("Processing reflected outgoing delivery receipt")

        val deliveryReceiptMessage = DeliveryReceiptMessage.fromReflected(outgoingMessage)
        val state = MessageUtil.receiptTypeToMessageState(deliveryReceiptMessage.receiptType)

        if (state == null) {
            logger.warn("Message {} error: unknown delivery receipt type", outgoingMessage.messageId)
            return
        }

        val identity = outgoingMessage.conversation.contact

        for (messageId in deliveryReceiptMessage.receiptMessageIds) {
            val messageModel = messageService.getContactMessageModel(messageId, identity)
            if (messageModel == null) {
                logger.warn(
                    "Message model ({}) for reflected outgoing delivery receipt is null",
                    messageId,
                )
                continue
            }

            if (updateMessage(messageModel, state) && state == MessageState.READ) {
                notificationService.cancel(messageReceiver)
            }
        }
    }

    /**
     * F1Whisper (seventh fork review, F7-04): this is the ORDINARY multi-device read path, and it had no durable
     * first-read transition at all.
     *
     * The sixth review converted [ch.threema.app.processors.reflectedmessageupdate.ReflectedIncomingMessageUpdateTask],
     * but that branch only runs when NO read receipt is owed to the peer. With read receipts enabled - the default - a
     * 1:1 read is announced as an outgoing delivery receipt and reflected here, and this handler simply set `readAt`,
     * `modifiedAt` and `isRead` on a cached model and full-row-saved it. So the message became read on the primary
     * device with no countdown start and no deadline: normal expiry only selects rows that have a deadline, and the
     * repair pass that would notice runs at startup, so a message read on a linked device outlived the interval its
     * sender advertised until the app happened to be relaunched.
     *
     * Both branches now go through the same conditional, non-inserting operations the local read goes through, which
     * also means a row hard-deleted or deleted for everyone while the reflection was in flight stays gone and publishes
     * nothing.
     *
     * @return whether the row was actually updated, which is what gates the listener and the notification.
     */
    private fun updateMessage(messageModel: AbstractMessageModel, state: MessageState): Boolean {
        if (MessageUtil.isReaction(state)) {
            messageService.addMessageReaction(
                messageModel,
                state,
                // the identity that reacted (this is us => reflected outgoing message)
                myIdentity,
                Date(outgoingMessage.createdAt),
            )
            return true
        }
        val date = Date(outgoingMessage.createdAt)
        val updated = when (state) {
            // The delivered at date is stored in created at for incoming messages
            MessageState.DELIVERED -> messageService.updateReceivedTimestamp(messageModel, date)

            MessageState.READ -> messageService.markAsReadFromSync(messageModel, date)

            else -> {
                logger.error("Unsupported delivery receipt reflected of state {}", state)
                false
            }
        }
        if (updated) {
            ListenerManager.messageListeners.handle { l -> l.onModified(listOf(messageModel)) }
        }
        return updated
    }
}
