package ch.threema.domain.protocol.csp.messages

import ch.threema.common.buildByteArray
import ch.threema.common.readLittleEndianInt
import ch.threema.common.writeLittleEndianInt
import ch.threema.domain.models.GroupId
import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.protobuf.csp.e2e.fs.Version
import java.nio.charset.StandardCharsets

/**
 * F1Whisper: the group variant of [DisappearingTimerMessage]. Carries the per-group disappearing
 * timer (in seconds, `0` = off) that the sender has set for this group. Signal-style short-timer
 * per-conversation control (distinct from the upstream keep-messages-N-days purge).
 *
 * It is a durable, state-changing message: it MUST be queued and acknowledged so an offline member
 * still receives the timer change on reconnect (hence the [MessageFlags] defaults are kept, i.e.
 * [flagNoServerQueuing]/[flagNoServerAck] are NOT overridden to true).
 *
 * The body is the group-member container (creator identity + group id) followed by a single 4-byte
 * little-endian integer holding the timer in seconds.
 *
 * Decoded purely in Kotlin (MessageCoder); no libthreema/proto change needed.
 */
class GroupDisappearingTimerMessage : AbstractGroupMessage() {
    var timerSeconds: Int = 0

    override fun getType() = ProtocolDefines.MSGTYPE_GROUP_DISAPPEARING_TIMER

    override fun getMinimumRequiredForwardSecurityVersion() = Version.V1_2

    override fun allowUserProfileDistribution() = false

    override fun exemptFromBlocking() = false

    override fun createImplicitlyDirectContact() = false

    override fun protectAgainstReplay() = true

    // Reflect timer controls to the device group so a linked follower stays in sync (D2D
    // multi-device). This mirrors the GroupEdit/GroupDelete/GroupReaction control-message contract:
    // a timer control mutates conversation state without creating a tracked outgoing message model,
    // so we reflect incoming + outgoing but NOT a sent-update (there is no outgoing message whose
    // sent state could be reflected). Both flags are no-ops when multi device is inactive.
    override fun reflectIncoming() = true

    override fun reflectOutgoing() = true

    override fun reflectSentUpdate() = false

    override fun sendAutomaticDeliveryReceipt() = false

    override fun bumpLastUpdate() = false

    override fun getBody(): ByteArray =
        buildByteArray(
            ProtocolDefines.IDENTITY_LEN + ProtocolDefines.GROUP_ID_LEN + TIMER_SECONDS_BYTE_LENGTH,
        ) {
            write(groupCreator.toByteArray(StandardCharsets.US_ASCII))
            write(apiGroupId.groupId)
            writeLittleEndianInt(timerSeconds)
        }

    companion object {
        private const val TIMER_SECONDS_BYTE_LENGTH = 4

        @JvmStatic
        @Throws(BadMessageException::class)
        fun fromByteArray(
            data: ByteArray,
            offset: Int,
            length: Int,
        ): GroupDisappearingTimerMessage {
            if (offset < 0) {
                throw BadMessageException("Bad offset ($offset) for group disappearing timer message")
            }
            val expectedLength =
                ProtocolDefines.IDENTITY_LEN + ProtocolDefines.GROUP_ID_LEN + TIMER_SECONDS_BYTE_LENGTH
            if (length != expectedLength) {
                throw BadMessageException("Bad length ($length) for group disappearing timer message")
            }
            if (data.size < offset + length) {
                throw BadMessageException(
                    "Invalid byte array length (${data.size}) for offset $offset and length $length",
                )
            }

            return GroupDisappearingTimerMessage().apply {
                groupCreator = String(
                    data,
                    offset,
                    ProtocolDefines.IDENTITY_LEN,
                    StandardCharsets.US_ASCII,
                )
                apiGroupId = GroupId(data, offset + ProtocolDefines.IDENTITY_LEN)
                timerSeconds = data.readLittleEndianInt(
                    offset + ProtocolDefines.IDENTITY_LEN + ProtocolDefines.GROUP_ID_LEN,
                )
            }
        }
    }
}
