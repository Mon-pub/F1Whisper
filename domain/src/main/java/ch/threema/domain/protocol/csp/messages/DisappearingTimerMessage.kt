package ch.threema.domain.protocol.csp.messages

import ch.threema.common.buildByteArray
import ch.threema.common.readLittleEndianInt
import ch.threema.common.writeLittleEndianInt
import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.protobuf.csp.e2e.fs.Version

/**
 * F1Whisper: the 1:1 disappearing-messages control message. Carries the per-conversation
 * disappearing timer (in seconds, `0` = off) that the sender has set for this contact. Unlike the
 * upstream keep-messages-N-days purge, this is a Signal-style short-timer per-conversation control.
 *
 * It is a durable, state-changing message: it MUST be queued and acknowledged so an offline contact
 * still receives the timer change on reconnect (hence the [MessageFlags] defaults are kept, i.e.
 * [flagNoServerQueuing]/[flagNoServerAck] are NOT overridden to true).
 *
 * The body is a single 4-byte little-endian integer holding the timer in seconds.
 *
 * Decoded purely in Kotlin (MessageCoder); no libthreema/proto change needed.
 */
class DisappearingTimerMessage : AbstractMessage() {
    var timerSeconds: Int = 0

    override fun getType() = ProtocolDefines.MSGTYPE_DISAPPEARING_TIMER

    override fun getMinimumRequiredForwardSecurityVersion() = Version.V1_1

    override fun allowUserProfileDistribution() = false

    override fun exemptFromBlocking() = false

    override fun createImplicitlyDirectContact() = false

    override fun protectAgainstReplay() = true

    // Reflect timer controls to the device group so a linked follower stays in sync (D2D
    // multi-device). This mirrors the Edit/Delete/Reaction control-message contract: a timer
    // control mutates conversation state without creating a tracked outgoing message model, so we
    // reflect incoming + outgoing but NOT a sent-update (there is no outgoing message whose sent
    // state could be reflected). Both flags are no-ops when multi device is inactive.
    override fun reflectIncoming() = true

    override fun reflectOutgoing() = true

    override fun reflectSentUpdate() = false

    override fun sendAutomaticDeliveryReceipt() = false

    override fun bumpLastUpdate() = false

    override fun getBody(): ByteArray =
        buildByteArray(TIMER_SECONDS_BYTE_LENGTH) {
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
        ): DisappearingTimerMessage {
            if (offset < 0) {
                throw BadMessageException("Bad offset ($offset) for disappearing timer message")
            }
            if (length != TIMER_SECONDS_BYTE_LENGTH) {
                throw BadMessageException("Bad length ($length) for disappearing timer message")
            }
            if (data.size < offset + length) {
                throw BadMessageException(
                    "Invalid byte array length (${data.size}) for offset $offset and length $length",
                )
            }

            return DisappearingTimerMessage().apply {
                timerSeconds = data.readLittleEndianInt(offset)
            }
        }
    }
}
