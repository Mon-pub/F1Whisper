package ch.threema.app.utils

import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.protocol.csp.messages.AbstractMessage
import java.time.Instant

private val logger = getThreemaLogger("IncomingPostedAt")

/**
 * F1Whisper: the posted time of a NEW incoming message.
 *
 * The sender's phone stamps its messages with its own clock. A phone running days ahead would put every one of its
 * messages at the bottom of our chat, below everything that arrives later. When we know reliably when the message
 * reached us (an elapsed-anchored server-corrected time, or the mediator's reflect time), a sender time more than
 * [MAX_FUTURE_MS] after that is replaced by the receive time.
 *
 * Only the future is clamped. A sender time in the past is kept: it cannot be told apart from a message written
 * offline and delivered later. Only the local model's postedAt changes; the decoded message, its metadata and the
 * ciphertext are never touched.
 */
object IncomingPostedAt {

    /** Sender clocks and network delays within this margin are never corrected. */
    const val MAX_FUTURE_MS = 5L * 60 * 1000

    /** The posted time to store for the new incoming [message]. */
    @JvmStatic
    fun of(message: AbstractMessage): Instant {
        val senderTime = message.timestamp
        val effective = effective(senderTime, message.trustedReceivedAtMillis)
        if (effective != senderTime) {
            logger.info(
                "Message {}: sender time {} is {} ms after receipt, using the receive time",
                message.messageId,
                senderTime,
                senderTime.toEpochMilli() - effective.toEpochMilli(),
            )
        }
        return effective
    }

    /** Pure rule: [senderTime], unless it is more than [MAX_FUTURE_MS] after a known [trustedReceivedAtMillis]. */
    @JvmStatic
    fun effective(senderTime: Instant, trustedReceivedAtMillis: Long?): Instant {
        if (trustedReceivedAtMillis == null || trustedReceivedAtMillis <= 0L) {
            return senderTime
        }
        return if (senderTime.toEpochMilli() > trustedReceivedAtMillis + MAX_FUTURE_MS) {
            Instant.ofEpochMilli(trustedReceivedAtMillis)
        } else {
            senderTime
        }
    }
}
