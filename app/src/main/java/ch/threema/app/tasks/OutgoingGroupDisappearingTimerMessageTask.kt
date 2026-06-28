package ch.threema.app.tasks

import ch.threema.domain.models.GroupId
import ch.threema.domain.models.MessageId
import ch.threema.domain.protocol.csp.messages.GroupDisappearingTimerMessage
import ch.threema.domain.taskmanager.Task
import ch.threema.domain.taskmanager.TaskCodec
import ch.threema.domain.types.IdentityString
import kotlinx.serialization.Serializable

/**
 * F1Whisper: outgoing group disappearing-messages control message (type 0x95).
 *
 * Sends a [GroupDisappearingTimerMessage] to all [recipientIdentities] announcing a per-group
 * timer change. This is a durable task: [serialize] returns real [SerializableTaskData] so the
 * task survives process death and is replayed on reconnect.
 */
class OutgoingGroupDisappearingTimerMessageTask(
    override val groupId: GroupId,
    override val creatorIdentity: IdentityString,
    override val recipientIdentities: Set<IdentityString>,
    private val timerSeconds: Int,
    override val messageId: MessageId = MessageId.random(),
) : OutgoingCspGroupControlMessageTask() {
    override val type: String = "OutgoingGroupDisappearingTimerMessageTask"

    override fun createGroupMessage() = GroupDisappearingTimerMessage().apply {
        this.timerSeconds = this@OutgoingGroupDisappearingTimerMessageTask.timerSeconds
    }

    override fun serialize(): SerializableTaskData = OutgoingGroupDisappearingTimerData(
        groupId = groupId.groupId,
        creatorIdentity = creatorIdentity,
        recipientIdentities = recipientIdentities,
        timerSeconds = timerSeconds,
        messageId = messageId.messageId,
    )

    @Serializable
    data class OutgoingGroupDisappearingTimerData(
        private val groupId: ByteArray,
        private val creatorIdentity: IdentityString,
        private val recipientIdentities: Set<IdentityString>,
        private val timerSeconds: Int,
        private val messageId: ByteArray,
    ) : SerializableTaskData {
        override fun createTask(): Task<*, TaskCodec> = OutgoingGroupDisappearingTimerMessageTask(
            groupId = GroupId(groupId),
            creatorIdentity = creatorIdentity,
            recipientIdentities = recipientIdentities,
            timerSeconds = timerSeconds,
            messageId = MessageId(messageId),
        )
    }
}
