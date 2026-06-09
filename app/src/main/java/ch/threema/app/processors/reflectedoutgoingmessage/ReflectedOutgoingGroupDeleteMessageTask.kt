package ch.threema.app.processors.reflectedoutgoingmessage

import ch.threema.app.managers.ServiceManager
import ch.threema.app.tasks.runCommonDeleteMessageReceiveSteps
import ch.threema.domain.protocol.csp.messages.GroupDeleteMessage
import ch.threema.protobuf.common.CspE2eMessageType
import ch.threema.protobuf.d2d.OutgoingMessage

internal class ReflectedOutgoingGroupDeleteMessageTask(
    outgoingMessage: OutgoingMessage,
    serviceManager: ServiceManager,
) : ReflectedOutgoingGroupMessageTask<GroupDeleteMessage>(
    outgoingMessage = outgoingMessage,
    message = GroupDeleteMessage.fromReflected(outgoingMessage),
    type = CspE2eMessageType.GROUP_DELETE_MESSAGE,
    serviceManager = serviceManager,
) {
    private val messageService by lazy { serviceManager.messageService }
    private val identityStore by lazy { serviceManager.identityStore }

    override fun processOutgoingMessage() {
        val myIdentity = identityStore.getIdentityString()
            ?: throw IllegalStateException("Cannot process reflected outgoing edit message when no identity exists")

        runCommonDeleteMessageReceiveSteps(
            myIdentity = myIdentity,
            // Note that we are processing only outgoing messages here
            deleteMessageSenderIdentity = myIdentity,
            deleteMessageCreatedAt = message.date,
            messageId = message.data.messageId,
            receiver = messageReceiver,
            messageService = messageService,
        )?.let { validatedMessageModelToDelete ->
            messageService.deleteMessageContentsAndRelatedData(
                validatedMessageModelToDelete,
                message.date,
            )
        }
    }
}
