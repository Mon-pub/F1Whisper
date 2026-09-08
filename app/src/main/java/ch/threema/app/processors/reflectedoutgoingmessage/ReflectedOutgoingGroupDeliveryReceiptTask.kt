package ch.threema.app.processors.reflectedoutgoingmessage

import ch.threema.app.managers.ListenerManager
import ch.threema.app.managers.ServiceManager
import ch.threema.app.messagereceiver.GroupMessageReceiver
import ch.threema.app.utils.MessageUtil
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.models.MessageId
import ch.threema.domain.protocol.csp.messages.GroupDeliveryReceiptMessage
import ch.threema.protobuf.common.CspE2eMessageType
import ch.threema.protobuf.d2d.OutgoingMessage
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.group.GroupMessageModel
import java.util.Date

private val logger = getThreemaLogger("ReflectedOutgoingGroupDeliveryReceiptTask")

internal class ReflectedOutgoingGroupDeliveryReceiptTask(
    outgoingMessage: OutgoingMessage,
    serviceManager: ServiceManager,
) : ReflectedOutgoingContactMessageTask<GroupDeliveryReceiptMessage>(
    outgoingMessage = outgoingMessage,
    message = GroupDeliveryReceiptMessage.fromReflected(outgoingMessage),
    type = CspE2eMessageType.GROUP_DELIVERY_RECEIPT,
    serviceManager = serviceManager,
) {
    private val messageService by lazy { serviceManager.messageService }
    private val notificationService by lazy { serviceManager.notificationService }
    private val groupService by lazy { serviceManager.groupService }
    private val myIdentity by lazy { serviceManager.identityStore.getIdentityString()!! }

    /**
     * The receiver of the message the receipt is ABOUT, which is the GROUP.
     *
     * [ReflectedOutgoingContactMessageTask.messageReceiver] is a different conversation: the contact this receipt was
     * addressed to. That outer contact conversation is correct for the receipt itself - it is sent to one member - so
     * the superclass stays as it is, but a notification for a group message cannot be cancelled through it.
     */
    private val groupMessageReceiver: GroupMessageReceiver? by lazy {
        groupService.getByApiGroupIdAndCreator(message.apiGroupId, message.groupCreator)
            ?.let(groupService::createReceiver)
    }

    override fun processOutgoingMessage() {
        logger.info(
            "Processing message {}: reflected outgoing group delivery receipt",
            outgoingMessage.messageId,
        )

        val messageState: MessageState? =
            MessageUtil.receiptTypeToMessageState(message.receiptType)
        if (messageState == null) {
            logger.warn(
                "Message {} error: unknown delivery receipt type: {}",
                message.messageId,
                message.receiptType,
            )
            return
        }

        for (receiptMessageId: MessageId in message.receiptMessageIds) {
            logger.info(
                "Processing message {}: group delivery receipt for {} (state = {})",
                outgoingMessage.messageId,
                receiptMessageId,
                messageState,
            )
            val groupMessageModel: GroupMessageModel? = messageService.getGroupMessageModel(
                receiptMessageId,
                message.groupCreator,
                message.apiGroupId,
            )
            if (groupMessageModel == null) {
                logger.warn(
                    "Group message model ({}) for reflected outgoing group delivery receipt is null",
                    receiptMessageId,
                )
                continue
            }
            if (updateMessage(groupMessageModel, messageState) && messageState == MessageState.READ) {
                groupMessageReceiver?.let(notificationService::cancel)
            }
        }
    }

    /**
     * F1Whisper: a group message read on a LINKED device stayed unread on this one.
     *
     * This handler accepted reactions only and returned early for every other receipt type, so a reflected outgoing
     * group DELIVERED or READ was dropped with a warning before the target message was ever looked up. Android's own
     * read path announces a group read twice - the peer receipt AND
     * [ch.threema.app.services.MessageServiceImpl.reflectGroupReadToLinkedDevices] - so phone-to-Desktop worked; but
     * Desktop with read receipts enabled sends only the group receipt, which arrives here. The message therefore
     * stayed unread on the phone, the first-read disappearing countdown never started, and the notification was never
     * cancelled.
     *
     * Both non-reaction states now take the same conditional, non-inserting operations the 1:1 twin
     * [ReflectedOutgoingDeliveryReceiptTask.updateMessage] takes, so a row hard-deleted or deleted for everyone while
     * the reflection was in flight stays gone and publishes nothing.
     *
     * The states are NOT written to the per-member map via `addGroupMessageState`: that map holds what OTHER members
     * did to messages WE sent, whereas this receipt is one WE sent about a message WE received.
     *
     * @return whether the row was actually updated, which is what gates the listener and the notification.
     */
    private fun updateMessage(messageModel: GroupMessageModel, state: MessageState): Boolean {
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
