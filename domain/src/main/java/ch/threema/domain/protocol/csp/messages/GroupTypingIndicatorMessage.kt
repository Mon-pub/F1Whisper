package ch.threema.domain.protocol.csp.messages

import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.models.GroupId
import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.protobuf.csp.e2e.fs.Version
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

private val logger = getThreemaLogger("GroupTypingIndicatorMessage")

/**
 * F1Whisper: the group variant of [TypingIndicatorMessage]. A throw-away message signalling that a
 * group member is currently typing a message (or has stopped). Like the 1:1 typing indicator it is
 * not queued on the server, not acknowledged and not persisted. The body is the group-member
 * container (creator identity + group id) followed by a single typing-flag byte.
 */
class GroupTypingIndicatorMessage : AbstractGroupMessage() {
    var isTyping: Boolean = false

    override fun getType() = ProtocolDefines.MSGTYPE_GROUP_TYPING_INDICATOR

    override fun getMinimumRequiredForwardSecurityVersion() = Version.V1_2

    override fun allowUserProfileDistribution() = false

    override fun exemptFromBlocking() = false

    override fun createImplicitlyDirectContact() = false

    override fun protectAgainstReplay() = false

    override fun reflectIncoming() = false

    override fun reflectOutgoing() = false

    override fun reflectSentUpdate() = false

    override fun sendAutomaticDeliveryReceipt() = false

    override fun bumpLastUpdate() = false

    override fun flagNoServerQueuing() = true

    override fun flagNoServerAck() = true

    override fun getBody(): ByteArray? {
        return try {
            val bos = ByteArrayOutputStream()
            bos.write(groupCreator.toByteArray(StandardCharsets.US_ASCII))
            bos.write(apiGroupId.groupId)
            bos.write(byteArrayOf(if (isTyping) 1.toByte() else 0.toByte()))
            bos.toByteArray()
        } catch (e: Exception) {
            logger.error("Failed to serialize group typing indicator", e)
            null
        }
    }

    companion object {
        @JvmStatic
        @Throws(BadMessageException::class)
        fun fromByteArray(
            data: ByteArray,
            offset: Int,
            length: Int,
        ): GroupTypingIndicatorMessage {
            if (data.size < offset + length) {
                throw BadMessageException(
                    "Invalid byte array length (${data.size}) for offset $offset and length $length",
                )
            }
            val expectedLength = ProtocolDefines.IDENTITY_LEN + ProtocolDefines.GROUP_ID_LEN + 1
            if (length != expectedLength) {
                throw BadMessageException("Bad length ($length) for group typing indicator message")
            }

            return GroupTypingIndicatorMessage().apply {
                groupCreator = String(
                    data,
                    offset,
                    ProtocolDefines.IDENTITY_LEN,
                    StandardCharsets.US_ASCII,
                )
                apiGroupId = GroupId(data, offset + ProtocolDefines.IDENTITY_LEN)
                isTyping = data[offset + ProtocolDefines.IDENTITY_LEN + ProtocolDefines.GROUP_ID_LEN] > 0
            }
        }
    }
}
