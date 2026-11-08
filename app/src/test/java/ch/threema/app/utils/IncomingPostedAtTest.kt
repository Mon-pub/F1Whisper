package ch.threema.app.utils

import ch.threema.domain.protocol.csp.messages.TextMessage
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * F1Whisper: a new incoming message whose sender clock runs far ahead is placed at its receive time. Seen in the
 * field: a contact's phone a week ahead kept its messages pinned below everything else in the chat.
 */
class IncomingPostedAtTest {

    private companion object {
        const val RECEIVED = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }

    private fun at(ms: Long): Instant = Instant.ofEpochMilli(ms)

    @Test
    fun `a sender a week ahead is placed at the receive time`() {
        assertEquals(at(RECEIVED), IncomingPostedAt.effective(at(RECEIVED + 7 * DAY), RECEIVED))
    }

    @Test
    fun `up to five minutes ahead is kept as sent`() {
        val edge = at(RECEIVED + IncomingPostedAt.MAX_FUTURE_MS)
        assertEquals(edge, IncomingPostedAt.effective(edge, RECEIVED))
        assertEquals(at(RECEIVED), IncomingPostedAt.effective(at(RECEIVED + IncomingPostedAt.MAX_FUTURE_MS + 1), RECEIVED))
    }

    @Test
    fun `the past is never changed, it may be a message written offline`() {
        val threeDaysAgo = at(RECEIVED - 3 * DAY)
        assertEquals(threeDaysAgo, IncomingPostedAt.effective(threeDaysAgo, RECEIVED))
    }

    @Test
    fun `without a trusted receive time the sender time is kept`() {
        val weekAhead = at(RECEIVED + 7 * DAY)
        assertEquals(weekAhead, IncomingPostedAt.effective(weekAhead, null))
        assertEquals(weekAhead, IncomingPostedAt.effective(weekAhead, 0L))
    }

    @Test
    fun `of uses the receive context of the message and never touches the message itself`() {
        val message = TextMessage().apply {
            text = "hello"
            timestamp = at(RECEIVED + 7 * DAY)
        }
        val bodyBefore = message.body

        message.trustedReceivedAtMillis = RECEIVED
        val postedAt = IncomingPostedAt.of(message)

        assertEquals(at(RECEIVED), postedAt)
        assertEquals(at(RECEIVED + 7 * DAY), message.timestamp, "the decoded sender time stays as received")
        assertContentEquals(bodyBefore, message.body, "the receive context is not part of the message bytes")
    }
}
