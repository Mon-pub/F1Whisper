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

    // F1Whisper (eleventh fork review, F11-06): TRUE, matching the group setup this message accompanies, and the 1:1
    // [DisappearingTimerMessage] deliberately stays FALSE.
    //
    // The same predicate gates both directions - the outgoing recipient filter in the bundled send steps and the
    // incoming discard in IncomingMessageTask - and group SETUP is exempt while this was not. So an explicitly
    // blocked contact who was added, re-added or resynced received the setup and joined the group, but the 0x95
    // bootstrap that follows it was silently dropped; the same happened inbound when the recipient had blocked the
    // group creator. The member ended up INSIDE the group but under the wrong retention policy: OFF where the group
    // says 30 seconds, or a stale positive timer where the group has since turned it off. Messages then outlive or
    // predecease what every other member agreed to, which is a policy defeat, not a cosmetic drift.
    //
    // The rule this encodes: the timer is GROUP STATE, like the setup, the name and the membership, and group state
    // must flow to members regardless of 1:1 blocking or the group desynchronises. Content stays blocked; the 1:1
    // timer stays blocked too, because there blocking the peer means exactly "I want no state from you".
    override fun exemptFromBlocking() = true

    override fun createImplicitlyDirectContact() = false

    override fun protectAgainstReplay() = true

    // Reflect timer controls to the device group so a linked follower stays in sync (D2D
    // multi-device). This mirrors the GroupEdit/GroupDelete/GroupReaction control-message contract:
    // a timer control mutates conversation state without creating a tracked outgoing message model,
    // so we reflect incoming + outgoing but NOT a sent-update (there is no outgoing message whose
    // sent state could be reflected). Both flags are no-ops when multi device is inactive.
    //
    // F1Whisper (tenth fork review, section 10): the flags above are LOAD-BEARING OUTWARD and
    // UNIMPLEMENTED INWARD, and that asymmetry is deliberate. Do not "tidy" either half without
    // reading this.
    //
    // OUTWARD (this device is the leader). Reflection works and linked Desktop depends on it.
    // `Reflect.kt` builds the envelope with `.setTypeValue(message.type)`, the RAW-INT setter, and a
    // proto3 enum field is int32 on the wire, so a type with no named constant serialises correctly.
    // `common.proto` therefore has no 0x85/0x95 entry and does not need one; that omission is
    // cosmetic for sending. Setting either flag to false would stop the timer reaching Desktop and
    // silently break a working feature.
    //
    // INWARD (this device as a follower). NOT supported, and it fails LOUDLY rather than quietly.
    // `IncomingReflectedMessageTask` and `ReflectedOutgoingMessageTask` both switch on the NAMED
    // enum and answer an unknown value with `UNRECOGNIZED -> throw IllegalStateException`. A
    // reflected timer control would therefore throw inside the task. It follows that
    // `executeMessageStepsFromSync() = DISCARD` in the two incoming timer subtasks is UNREACHABLE
    // for these types: the dispatcher throws before the subtask is built.
    //
    // Why that is fine today: this fork ships Android as the only leader, Desktop cannot set the
    // timer, and no second Android device is linked to one identity. The inward path is never taken.
    //
    // BEFORE linking a second Android device to one identity, or letting Desktop set the timer:
    // handle 0x85/0x95 in BOTH reflected dispatchers first, or the first reflected timer control
    // will throw. That is a topology change, not a code change, which is why it is written here
    // rather than fixed - see ANDROID-FORK-TENTH-REVIEW-REMEDIATION-HANDOFF-2026-08-13.md.
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
