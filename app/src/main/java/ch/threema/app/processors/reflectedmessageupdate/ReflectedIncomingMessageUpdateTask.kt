package ch.threema.app.processors.reflectedmessageupdate

import ch.threema.app.eventbus.GlobalEventBuses
import ch.threema.app.eventbus.events.MessageEvent
import ch.threema.app.managers.ServiceManager
import ch.threema.base.utils.getThreemaLogger
import ch.threema.data.datatypes.ContactConversationId
import ch.threema.data.datatypes.GroupConversationId
import ch.threema.domain.models.GroupId
import ch.threema.domain.models.MessageId
import ch.threema.domain.types.IdentityString
import ch.threema.protobuf.common.GroupIdentity
import ch.threema.protobuf.d2d.ConversationId
import ch.threema.protobuf.d2d.IncomingMessageUpdate
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.group.GroupMessageModel
import java.time.Instant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private val logger = getThreemaLogger("ReflectedIncomingMessageUpdateTask")

class ReflectedIncomingMessageUpdateTask(
    private val incomingMessageUpdate: IncomingMessageUpdate,
    serviceManager: ServiceManager,
) : KoinComponent {
    private val messageService by lazy { serviceManager.messageService }
    private val notificationService by lazy { serviceManager.notificationService }
    private val globalEventBuses: GlobalEventBuses by inject()

    fun run() {
        logger.info("Processing reflected incoming message update")

        incomingMessageUpdate.updatesList.forEach { update ->
            when (update.updateCase) {
                IncomingMessageUpdate.Update.UpdateCase.READ -> applyReadUpdate(update)
                else -> logger.error("Received an unknown incoming message update '${update.updateCase}'")
            }
        }
    }

    private fun applyReadUpdate(update: IncomingMessageUpdate.Update) {
        val conversationId: ConversationId = update.conversation
        val messageId = MessageId(update.messageId)
        val readAt = update.read.at
        when (conversationId.idCase) {
            ConversationId.IdCase.CONTACT -> applyContactMessageReadUpdate(messageId, conversationId.contact, readAt)

            ConversationId.IdCase.GROUP -> applyGroupMessageReadUpdate(messageId, conversationId.group, readAt)

            ConversationId.IdCase.DISTRIBUTION_LIST -> throw IllegalStateException(
                "Received incoming message update for a distribution list",
            )

            ConversationId.IdCase.ID_NOT_SET -> logger.warn("Received incoming message update where id is not set")

            null -> logger.warn("Received incoming message update where id is null")
        }
    }

    private fun applyContactMessageReadUpdate(
        messageId: MessageId,
        senderIdentity: IdentityString,
        readAt: Long,
    ) {
        val abstractMessageModel = messageService.getContactMessageModel(messageId, senderIdentity)
        if (abstractMessageModel == null) {
            logger.warn("Message model for message {} of {} not found", messageId, senderIdentity)
            return
        }

        markMessageModelAsRead(abstractMessageModel, readAt)
    }

    private fun applyGroupMessageReadUpdate(
        messageId: MessageId,
        groupIdentity: GroupIdentity,
        readAt: Long,
    ) {
        val abstractMessageModel = messageService.getGroupMessageModel(
            messageId,
            groupIdentity.creatorIdentity,
            GroupId(groupIdentity.groupId),
        )

        if (abstractMessageModel == null) {
            logger.warn("Group message model for message {} not found", messageId)
            return
        }

        markMessageModelAsRead(abstractMessageModel, readAt)
    }

    private fun markMessageModelAsRead(abstractMessageModel: AbstractMessageModel, readAt: Long) {
        // F1Whisper (sixth fork review, F6-01): the reflected read goes through the same conditional, non-inserting
        // first-read write the local read goes through, which also starts the disappearing countdown this path never
        // started. See MessageService#markAsReadFromSync.
        if (!messageService.markAsReadFromSync(abstractMessageModel, Instant.ofEpochMilli(readAt))) {
            return
        }
        globalEventBuses.messages.emit(MessageEvent.MessagesUpdated(abstractMessageModel))
        cancelNotification(abstractMessageModel)
    }

    private fun cancelNotification(abstractMessageModel: AbstractMessageModel) {
        val conversationId: ch.threema.data.datatypes.ConversationId? =
            when (abstractMessageModel) {
                is MessageModel ->
                    abstractMessageModel.identity?.let { identity ->
                        ContactConversationId(identity)
                    }
                is GroupMessageModel ->
                    GroupConversationId(
                        groupDatabaseId = abstractMessageModel.groupId.toLong(),
                    )
                else -> {
                    // Distribution list messages are not supported here
                    null
                }
            }
        if (conversationId != null) {
            notificationService.cancel(conversationId)
        } else {
            logger.error("Failed to determine message receiver for message with id {}", abstractMessageModel.apiMessageId)
        }
    }
}
