package ch.threema.app.processors.reflectedoutgoingmessage

import ch.threema.app.managers.ServiceManager
import ch.threema.app.tasks.runCommonEditMessageReceiveSteps
import ch.threema.domain.protocol.csp.messages.GroupEditMessage
import ch.threema.protobuf.common.CspE2eMessageType
import ch.threema.protobuf.d2d.OutgoingMessage
import ch.threema.storage.models.AbstractMessageModel

internal class ReflectedOutgoingGroupEditMessageTask(
    outgoingMessage: OutgoingMessage,
    serviceManager: ServiceManager,
) : ReflectedOutgoingGroupMessageTask<GroupEditMessage>(
    outgoingMessage = outgoingMessage,
    message = GroupEditMessage.fromReflected(outgoingMessage),
    type = CspE2eMessageType.GROUP_EDIT_MESSAGE,
    serviceManager = serviceManager,
) {
    private val messageService by lazy { serviceManager.messageService }
    private val identityStore by lazy { serviceManager.identityStore }

    override fun processOutgoingMessage() {
        check(outgoingMessage.conversation.hasGroup()) { "The message does not have a group identity set" }

        val myIdentity = identityStore.getIdentityString()
            ?: throw IllegalStateException("Cannot process reflected outgoing edit message when no identity exists")

        runCommonEditMessageReceiveSteps(
            myIdentity = myIdentity,
            // Note that we are processing only outgoing messages here
            editMessageSenderIdentity = myIdentity,
            editMessageCreatedAt = message.date,
            messageId = message.data.messageId,
            receiver = messageReceiver,
            messageService = messageService,
        )?.let { validEditMessageModel: AbstractMessageModel ->
            messageService.saveEditedMessageText(
                validEditMessageModel,
                message.data.text,
                message.date,
            )
        }
    }
}
