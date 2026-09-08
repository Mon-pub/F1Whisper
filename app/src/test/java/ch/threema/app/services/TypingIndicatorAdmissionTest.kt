package ch.threema.app.services

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper: typing indicators were 54% of all outbound end-to-end messages in 13 days of device logs - 4603 of 8524 -
 * and 54% of everything wrapped in Forward Security, each one burning a ratchet counter. The group indicator is the
 * expensive half: 242 typing events became 2516 wire messages, a measured fan-out of 10.4.
 *
 * What is asserted here is the admission decision itself, on a fake clock and a fake scheduler, because the property
 * that matters is not "fewer messages" but "fewer messages AND never a gap the receiver's 15 s lease outlives".
 */
class TypingIndicatorAdmissionTest {

    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val emitted = mutableListOf<Pair<String, Boolean>>()

    private val admission = TypingIndicatorAdmission<String>(
        emit = { conversation, isTyping -> emitted += conversation to isTyping },
        nowMillis = clock::now,
        scheduler = scheduler,
    )

    // -----------------------------------------------------------------------------------------------------------------------------
    // Starts.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the first start of a conversation goes out immediately`() {
        admission.admit(ALICE, true)

        assertEquals(listOf(ALICE to true), emitted)
    }

    @Test
    fun `a second start inside the window does not go out immediately`() {
        admission.admit(ALICE, true)
        clock.advance(3_000)

        admission.admit(ALICE, true)

        assertEquals(listOf(ALICE to true), emitted, "still just the first one")
    }

    /**
     * The property a bare "return if too soon" would break. Dropping the suppressed start would skip a heartbeat, and
     * the receiver expires typing 15 s after the last one it saw, so the indicator would flicker off mid-sentence.
     */
    @Test
    fun `a suppressed start is coalesced into a trailing one at the end of the window`() {
        admission.admit(ALICE, true)
        clock.advance(3_000)
        admission.admit(ALICE, true)

        clock.advance(7_000)
        scheduler.runDue()

        assertEquals(listOf(ALICE to true, ALICE to true), emitted)
    }

    @Test
    fun `many starts inside one window cost exactly one trailing send`() {
        admission.admit(ALICE, true)
        repeat(20) {
            clock.advance(400)
            admission.admit(ALICE, true)
        }

        clock.advance(10_000)
        scheduler.runDue()

        assertEquals(2, emitted.size, "the immediate one and a single trailing one")
    }

    @Test
    fun `no two admitted starts are ever further apart than the receiver lease`() {
        val admittedAt = mutableListOf<Long>()
        val recording = TypingIndicatorAdmission<String>(
            emit = { _, _ -> admittedAt += clock.now() },
            nowMillis = clock::now,
            scheduler = scheduler,
        )

        // A caller far faster than the composer's own heartbeat: legacy Web drives this method directly.
        repeat(100) {
            recording.admit(ALICE, true)
            clock.advance(1_000)
            scheduler.runDue()
        }

        assertTrue(admittedAt.size in 10..12, "about one per window over 100 s, was ${admittedAt.size}")
        admittedAt.zipWithNext { previous, next ->
            assertTrue(next - previous <= RECEIVER_LEASE_MS, "gap of ${next - previous} ms outlives the lease")
        }
    }

    @Test
    fun `a start after the window has closed goes out immediately`() {
        admission.admit(ALICE, true)
        clock.advance(10_000)

        admission.admit(ALICE, true)

        assertEquals(listOf(ALICE to true, ALICE to true), emitted)
        assertFalse(scheduler.hasPending(), "and nothing was scheduled")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Stops.
    // -----------------------------------------------------------------------------------------------------------------------------

    /** The stop is the second half of the traffic saving: it ends the burst here and never reaches the wire. */
    @Test
    fun `a stop puts nothing on the wire`() {
        admission.admit(ALICE, true)
        clock.advance(100)

        admission.admit(ALICE, false)

        assertEquals(listOf(ALICE to true), emitted)
    }

    /**
     * The reason the end of a burst must still be REPORTED even though it is not sent. A start coalesced behind the
     * window and never cancelled arrives after the user has already sent and walked away, turning the peer's indicator
     * back on for a fresh 15 s lease - after the message itself had cleared it.
     */
    @Test
    fun `a stop cancels a trailing start that has not fired yet`() {
        admission.admit(ALICE, true)
        clock.advance(2_000)
        admission.admit(ALICE, true)
        assertTrue(scheduler.hasPending())

        admission.admit(ALICE, false)
        clock.advance(20_000)
        scheduler.runDue()

        assertEquals(listOf(ALICE to true), emitted)
    }

    /**
     * Belt and braces for the same property: a task that already escaped cancellation - it was picked up by the timer
     * thread before the stop took the lock - must still not emit.
     */
    @Test
    fun `a trailing start that escaped cancellation still refuses to fire after a stop`() {
        admission.admit(ALICE, true)
        clock.advance(2_000)
        admission.admit(ALICE, true)
        val escaped = scheduler.takePendingWithoutCancelling()

        admission.admit(ALICE, false)
        escaped.run()

        assertEquals(listOf(ALICE to true), emitted)
    }

    @Test
    fun `the start after a stop is a new burst and is not held back`() {
        admission.admit(ALICE, true)
        clock.advance(1_000)
        admission.admit(ALICE, false)
        clock.advance(1_000)

        admission.admit(ALICE, true)

        assertEquals(listOf(ALICE to true, ALICE to true), emitted)
    }

    /**
     * The defect the fourteenth review found, stated as the property that prevents it: once a burst has ended, nothing
     * it coalesced may still be waiting to fire. Type, heartbeat inside the window, send. Without the end reaching the
     * gate the held-back start fired seconds after the message had already landed and cleared the peer's indicator,
     * turning it back on for a fresh 15 s lease with an empty composer.
     */
    @Test
    fun `a burst that ends leaves nothing scheduled to fire later`() {
        admission.admit(ALICE, true)
        clock.advance(2_000)
        admission.admit(ALICE, true)
        assertTrue(scheduler.hasPending(), "the heartbeat was coalesced")

        admission.admit(ALICE, false)

        assertFalse(scheduler.hasPending(), "and ending the burst must take it with it")
        clock.advance(60_000)
        scheduler.runDue()
        assertEquals(listOf(ALICE to true), emitted)
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Conversations do not interfere.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the window is per conversation`() {
        admission.admit(ALICE, true)
        clock.advance(1_000)

        admission.admit(BOB, true)

        assertEquals(listOf(ALICE to true, BOB to true), emitted)
    }

    @Test
    fun `a stop in one conversation does not cancel a trailing start in another`() {
        admission.admit(ALICE, true)
        admission.admit(BOB, true)
        clock.advance(1_000)
        admission.admit(BOB, true)

        admission.admit(ALICE, false)
        clock.advance(10_000)
        scheduler.runDue()

        assertEquals(listOf(ALICE to true, BOB to true, BOB to true), emitted)
    }

    /**
     * Not every burst ends through this gate - an app kill, a fragment torn down mid-word - so state that only expired
     * on a stop would accumulate for the life of the process.
     */
    @Test
    fun `idle conversation state is not retained forever`() {
        repeat(50) { index -> admission.admit("CONTACT$index", true) }
        clock.advance(120_000)

        admission.admit(ALICE, true)

        assertEquals(1, admission.trackedConversationCount(), "only the conversation that is still typing")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The gate is reachable from every caller, and the composer no longer pays for a stop.
    // Source assertions: neither service can be constructed in a JVM unit test.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `both service entries admit through the gate rather than sending directly`() {
        val contact = File("src/main/java/ch/threema/app/services/ContactServiceImpl.java").readText()
        val contactEntry = contact.substringAfter("public void sendTypingIndicator(String toIdentity")
            .substringBefore("private void sendAdmittedTypingIndicator")
        assertTrue(contactEntry.contains("typingAdmission.admit(toIdentity, isTyping);"))
        assertFalse(
            contactEntry.contains("sendTypingIndicatorMessage"),
            "the entry decides, the admitted callback sends",
        )

        val group = File("src/main/java/ch/threema/app/services/GroupServiceImpl.java").readText()
        val groupEntry = group.substringAfter("public void sendTypingIndicator(long groupDatabaseId")
            .substringBefore("private void sendAdmittedTypingIndicator")
        assertTrue(groupEntry.contains("typingAdmission.admit(groupDatabaseId, isTyping);"))
        assertFalse(
            groupEntry.contains("OutgoingGroupTypingIndicatorMessageTask"),
            "the entry decides, the admitted callback schedules",
        )
    }

    /**
     * The gate has to be at the service entry, not in the composer's watcher: the watcher is not the only caller.
     */
    @Test
    fun `the legacy web handler reaches the same gated entry`() {
        val handler = File("src/main/java/ch/threema/app/webclient/services/instance/message/receiver/IsTypingHandler.java")
            .readText()

        assertTrue(handler.contains("sendTypingIndicator("))
        assertFalse(handler.contains("TypingIndicatorTextWatcher"), "it bypasses the composer entirely")
    }

    /**
     * The composer must keep reporting BOTH transitions. Suppressing the end of a burst here rather than at the wire
     * is what let a coalesced start outlive the send that should have cancelled it.
     */
    @Test
    fun `the composer still reports the end of a burst`() {
        val watcher = File("src/main/java/ch/threema/app/ui/TypingIndicatorTextWatcher.kt").readText()
        val observer = watcher.substringAfter("private suspend fun observeIsTypingStateFlow()")
            .substringBefore("private suspend fun periodicallyCheckTypingActivity")

        assertTrue(observer.contains("sendTypingIndicator(isTyping)"))
        assertFalse(observer.contains("if (isTyping) {"), "the wire suppression belongs to the admission gate")
    }

    /**
     * The suppression above is only safe because the receiver derives the stop from the message itself.
     */
    @Test
    fun `an incoming message clears the sender's typing state on both conversation types`() {
        val task = File("src/main/java/ch/threema/app/processors/IncomingMessageTask.kt").readText()
        val clear = task.substringAfter("private fun clearSenderTyping(")

        assertTrue(clear.contains("if (!message.flagSendPush()) {"), "scoped to what the composer produces")
        assertTrue(clear.contains("setMemberTyping(group.id.toLong(), senderIdentity, false)"))
        assertTrue(clear.contains("contactService.setIsTyping(senderIdentity, false)"))
        assertTrue(
            task.contains("clearSenderTyping(message)"),
            "and it is actually called once the message has been processed",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------

    private class FakeClock {
        private var millis = 1_000_000L

        fun now(): Long = millis

        fun advance(byMillis: Long) {
            millis += byMillis
        }
    }

    private class FakeScheduler(private val clock: FakeClock) : TypingIndicatorAdmission.Scheduler {
        private val pending = mutableListOf<Pending>()

        private class Pending(val dueAt: Long, val action: Runnable) {
            var cancelled = false
        }

        override fun schedule(delayMillis: Long, action: Runnable): TypingIndicatorAdmission.Cancellable {
            val task = Pending(clock.now() + delayMillis, action)
            pending += task
            return TypingIndicatorAdmission.Cancellable { task.cancelled = true }
        }

        fun hasPending(): Boolean = pending.any { !it.cancelled }

        fun runDue() {
            val due = pending.filter { !it.cancelled && it.dueAt <= clock.now() }
            pending.removeAll(due)
            due.forEach { it.action.run() }
        }

        /** A task the timer thread already picked up, so a later cancel cannot reach it. */
        fun takePendingWithoutCancelling(): Runnable {
            val task = pending.last { !it.cancelled }
            pending.remove(task)
            return task.action
        }
    }

    private companion object {
        const val ALICE = "ALICE123"
        const val BOB = "BOBBOB12"

        /** `ContactServiceImpl.TYPING_RECEIVE_TIMEOUT` and `GroupServiceImpl.GROUP_TYPING_RESET_TIMEOUT_MS`. */
        const val RECEIVER_LEASE_MS = 15_000L
    }
}
