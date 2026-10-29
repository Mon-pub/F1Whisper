package ch.threema.app.tasks

import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.messagereceiver.MessageReceiver.MessageReceiverType
import ch.threema.domain.protocol.csp.messages.poll.GroupPollSetupMessage
import ch.threema.domain.protocol.csp.messages.poll.PollData
import ch.threema.domain.protocol.csp.messages.poll.PollId
import ch.threema.domain.protocol.csp.messages.poll.PollSetupMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.Task
import ch.threema.domain.taskmanager.TaskCodec
import ch.threema.domain.types.IdentityString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class OutgoingPollSetupMessageTask(
    private val messageModelId: Int,
    @MessageReceiverType
    private val receiverType: Int,
    private val recipientIdentities: Set<IdentityString>,
    private val pollId: PollId,
    private val pollData: PollData,
) : OutgoingCspMessageTask() {
    override val type: String = "OutgoingPollSetupMessageTask"

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
        // F1Whisper (seventh fork review, F7-01): the row, not the cache. See PersistentTaskRowGate.
        val messageModel = getContactContentRow(messageModelId) ?: return

        // Create the message
        val message = PollSetupMessage().also {
            it.pollCreatorIdentity = identityStore.getIdentityString()
            it.pollId = pollId
            it.pollData = pollData
        }

        sendContactMessage(
            message,
            messageModel,
            messageModel.identity!!,
            ensureMessageId(messageModel),
            messageModel.createdAt!!,
            handle,
        )
    }

    private suspend fun sendGroupMessage(handle: ActiveTaskCodec) {
        // F1Whisper (seventh fork review, F7-01): the row, not the cache. See PersistentTaskRowGate.
        val messageModel = getGroupContentRow(messageModelId) ?: return

        val group = groupService.getById(messageModel.groupId)
            ?: throw IllegalStateException("Could not get group for message model ${messageModel.apiMessageId}")

        sendGroupMessage(
            group,
            recipientIdentities,
            messageModel,
            messageModel.createdAt!!,
            ensureMessageId(messageModel),
            {
                GroupPollSetupMessage().also {
                    it.pollCreatorIdentity = identityStore.getIdentityString()
                    it.pollId = pollId
                    it.pollData = pollData
                }
            },
            handle,
        )
    }

    override fun serialize(): SerializableTaskData = OutgoingPollSetupMessageData(
        messageModelId,
        receiverType,
        recipientIdentities,
        pollId.pollId,
        pollData.generateString(),
    )

    @Serializable
    class OutgoingPollSetupMessageData(
        private val messageModelId: Int,
        @MessageReceiverType
        private val receiverType: Int,
        private val recipientIdentities: Set<IdentityString>,
        @SerialName("ballotId")
        private val pollId: ByteArray,
        @SerialName("ballotData")
        private val pollData: String,
    ) : SerializableTaskData {
        override fun createTask(): Task<*, TaskCodec> =
            OutgoingPollSetupMessageTask(
                messageModelId = messageModelId,
                receiverType = receiverType,
                recipientIdentities = recipientIdentities,
                pollId = PollId(pollId),
                pollData = PollData.parse(pollData),
            )
    }
}
