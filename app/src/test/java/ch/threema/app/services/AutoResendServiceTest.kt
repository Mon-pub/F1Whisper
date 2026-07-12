package ch.threema.app.services

import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.MessageType
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper auto-resend: scan sequencing, ordering, aged-out sweep, single-in-flight guard, and
 * batch-continues-on-error. Uses in-memory fakes for the DB + resend seams.
 */
class AutoResendServiceTest {

    private val nowMillis = 1_000_000_000_000L

    private fun outgoing(id: Int, createdAtMillis: Long): MessageModel =
        MessageModel().apply {
            this.id = id
            isOutbox = true
            createdAt = Date(createdAtMillis)
        }

    /** A CandidateSource returning the given fixed lists (no age filtering; the test controls it). */
    private class FakeSource(
        val candidates: List<AbstractMessageModel>,
        val agedOut: List<AbstractMessageModel> = emptyList(),
    ) : AutoResendService.CandidateSource {
        override fun findAutoResendCandidates(minCreatedAtMillis: Long) = candidates
        override fun findAgedOutUnsentMessages(maxCreatedAtMillis: Long) = agedOut
    }

    /** A Resender recording the order of resent + aged-out message ids. */
    private class RecordingResender(
        private val failIds: Set<Int> = emptySet(),
    ) : AutoResendService.Resender {
        val resent = CopyOnWriteArrayList<Int>()
        val agedOut = CopyOnWriteArrayList<Int>()
        override fun autoResend(messageModel: AbstractMessageModel) {
            if (messageModel.id in failIds) {
                throw RuntimeException("simulated resend failure for ${messageModel.id}")
            }
            resent.add(messageModel.id)
        }
        override fun markAgedOutFailed(messageModel: AbstractMessageModel) {
            agedOut.add(messageModel.id)
        }
    }

    private fun serviceWith(
        source: AutoResendService.CandidateSource,
        resender: AutoResendService.Resender,
    ): AutoResendService {
        val svc = AutoResendService(source, resender, Executors.newSingleThreadScheduledExecutor())
        return svc
    }

    @Test
    fun `runScan resends oldest compose-time first`() {
        // Provided out of order; expect ascending createdAt.
        val a = outgoing(1, nowMillis - 5_000)
        val b = outgoing(2, nowMillis - 20_000)
        val c = outgoing(3, nowMillis - 12_000)
        val resender = RecordingResender()
        val svc = serviceWith(FakeSource(listOf(a, b, c)), resender)

        svc.runScan("test")

        assertEquals(listOf(2, 3, 1), resender.resent.toList())
    }

    @Test
    fun `runScan with no candidates resends nothing`() {
        val resender = RecordingResender()
        val svc = serviceWith(FakeSource(emptyList()), resender)

        svc.runScan("test")

        assertTrue(resender.resent.isEmpty())
        assertTrue(resender.agedOut.isEmpty())
    }

    @Test
    fun `runScan continues the batch when one message fails`() {
        val a = outgoing(1, nowMillis - 30_000)
        val b = outgoing(2, nowMillis - 20_000)
        val c = outgoing(3, nowMillis - 10_000)
        // Middle one throws; the others must still be resent.
        val resender = RecordingResender(failIds = setOf(2))
        val svc = serviceWith(FakeSource(listOf(a, b, c)), resender)

        svc.runScan("test")

        assertEquals(listOf(1, 3), resender.resent.toList())
    }

    @Test
    fun `runScan marks aged-out messages failed before resending live ones`() {
        val live = outgoing(10, nowMillis - 1_000)
        val old1 = outgoing(20, nowMillis - 100_000)
        val old2 = outgoing(21, nowMillis - 200_000)
        val resender = RecordingResender()
        val svc = serviceWith(FakeSource(candidates = listOf(live), agedOut = listOf(old1, old2)), resender)

        svc.runScan("test")

        assertEquals(listOf(20, 21), resender.agedOut.toList())
        assertEquals(listOf(10), resender.resent.toList())
    }

    @Test
    fun `null createdAt sorts as oldest and is not starved`() {
        val withNull = MessageModel().apply { id = 99; isOutbox = true; createdAt = null }
        val normal = outgoing(1, nowMillis - 5_000)
        val resender = RecordingResender()
        val svc = serviceWith(FakeSource(listOf(normal, withNull)), resender)

        svc.runScan("test")

        // null (treated as MIN) comes first.
        assertEquals(listOf(99, 1), resender.resent.toList())
    }

    // ---- isAutoResendEligible: the double-send guard ----

    private fun fileMsg(state: MessageState, terminal: Boolean = false) =
        MessageModel().apply {
            isOutbox = true
            type = MessageType.FILE
            this.state = state
            setSendFailedTerminal(terminal)
        }

    @Test
    fun `eligible - FILE in PENDING or UPLOADING`() {
        assertTrue(AutoResendService.isAutoResendEligible(fileMsg(MessageState.PENDING)))
        assertTrue(AutoResendService.isAutoResendEligible(fileMsg(MessageState.UPLOADING)))
    }

    @Test
    fun `NOT eligible - FILE TRANSCODING (no resumable blob)`() {
        assertFalse(AutoResendService.isAutoResendEligible(fileMsg(MessageState.TRANSCODING)))
    }

    @Test
    fun `eligible - FILE in connectivity SENDFAILED (no terminal bit)`() {
        assertTrue(AutoResendService.isAutoResendEligible(fileMsg(MessageState.SENDFAILED, terminal = false)))
    }

    @Test
    fun `NOT eligible - FILE SENDING (persistent task owns it, double-send guard)`() {
        assertFalse(AutoResendService.isAutoResendEligible(fileMsg(MessageState.SENDING)))
    }

    @Test
    fun `NOT eligible - terminal SENDFAILED`() {
        assertFalse(AutoResendService.isAutoResendEligible(fileMsg(MessageState.SENDFAILED, terminal = true)))
    }

    @Test
    fun `NOT eligible - FILE already SENT or SENT-ish`() {
        assertFalse(AutoResendService.isAutoResendEligible(fileMsg(MessageState.SENT)))
        assertFalse(AutoResendService.isAutoResendEligible(fileMsg(MessageState.DELIVERED)))
        assertFalse(AutoResendService.isAutoResendEligible(fileMsg(MessageState.READ)))
    }

    @Test
    fun `NOT eligible - TEXT or LOCATION or BALLOT (task queue owns them, double-send guard)`() {
        for (type in listOf(MessageType.TEXT, MessageType.LOCATION, MessageType.BALLOT)) {
            val m = MessageModel().apply {
                isOutbox = true
                this.type = type
                state = MessageState.PENDING
            }
            assertFalse(AutoResendService.isAutoResendEligible(m), "type $type must not be eligible")
        }
    }

    @Test
    fun `NOT eligible - incoming message`() {
        val m = MessageModel().apply {
            isOutbox = false
            type = MessageType.FILE
            state = MessageState.PENDING
        }
        assertFalse(AutoResendService.isAutoResendEligible(m))
    }

    @Test
    fun `concurrent scheduleScan collapses to a single scan run`() {
        val runCount = java.util.concurrent.atomic.AtomicInteger(0)
        val source = object : AutoResendService.CandidateSource {
            override fun findAutoResendCandidates(minCreatedAtMillis: Long): List<AbstractMessageModel> {
                runCount.incrementAndGet()
                return emptyList()
            }
            override fun findAgedOutUnsentMessages(maxCreatedAtMillis: Long) = emptyList<AbstractMessageModel>()
        }
        val svc = AutoResendService(source, RecordingResender(), Executors.newSingleThreadScheduledExecutor())

        // Fire many requests rapidly; the debounce must collapse them.
        repeat(20) { svc.scheduleScan("burst-$it") }

        // Wait past the debounce window for the single scan to run.
        Thread.sleep(AutoResendService.DEBOUNCE_MS + 1_500)

        assertEquals(1, runCount.get())
    }
}
