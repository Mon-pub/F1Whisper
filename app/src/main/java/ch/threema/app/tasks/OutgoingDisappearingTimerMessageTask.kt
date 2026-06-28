package ch.threema.app.tasks

import ch.threema.domain.models.MessageId
import ch.threema.domain.protocol.csp.messages.DisappearingTimerMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.Task
import ch.threema.domain.taskmanager.TaskCodec
import ch.threema.domain.types.IdentityString
import java.util.Date
import kotlinx.serialization.Serializable

/**
 * F1Whisper: outgoing 1:1 disappearing-messages control message (type 0x85).
 *
 * Sends a [DisappearingTimerMessage] to [toIdentity] announcing a per-conversation timer change.
 * This is a durable task: [serialize] returns real [SerializableTaskData] so the task survives
 * process death and is replayed on reconnect.
 */
class OutgoingDisappearingTimerMessageTask(
    private val toIdentity: IdentityString,
    private val timerSeconds: Int,
    private val messageId: MessageId = MessageId.random(),
    private val createdAt: Date = Date(),
) : OutgoingCspMessageTask() {
    override val type: String = "OutgoingDisappearingTimerMessageTask"

    override suspend fun runSendingSteps(handle: ActiveTaskCodec) {
        val message = DisappearingTimerMessage().apply {
            this.timerSeconds = this@OutgoingDisappearingTimerMessageTask.timerSeconds
        }
        sendContactMessage(message, null, toIdentity, messageId, createdAt, handle)
    }

    override fun serialize(): SerializableTaskData = OutgoingDisappearingTimerData(
        toIdentity = toIdentity,
        timerSeconds = timerSeconds,
        messageId = messageId.messageId,
        createdAt = createdAt.time,
    )

    @Serializable
    data class OutgoingDisappearingTimerData(
        private val toIdentity: IdentityString,
        private val timerSeconds: Int,
        private val messageId: ByteArray,
        private val createdAt: Long,
    ) : SerializableTaskData {
        override fun createTask(): Task<*, TaskCodec> = OutgoingDisappearingTimerMessageTask(
            toIdentity = toIdentity,
            timerSeconds = timerSeconds,
            messageId = MessageId(messageId),
            createdAt = Date(createdAt),
        )
    }
}
