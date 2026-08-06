package ch.threema.app.services

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (fifth fork review, F5-04): the disappearing-message lifecycle writes.
 *
 * Two kinds of claim are made here, and they are tested differently on purpose.
 *
 * **The decision** ([FirstReadDecision]) is pure and is tested by running it. It decides whether reading a message starts
 * a countdown and at what values, and the one rule that must never be got wrong is the tri-state: a frozen `0` means the
 * SENDER said OFF and the recipient keeps the message forever, so the local conversation timer may be consulted ONLY when
 * the sender advertised nothing at all.
 *
 * **The wiring** cannot be reached from the JVM. `markAsRead`, `applyIncomingFreeze` and `enforceIfExpired` need the
 * service graph, a real encrypted database and a real process kill to observe; what the review found was not a wrong
 * decision in them but a wrong WRITE - a detached full-row save where a conditional column-scoped update belonged, and a
 * fallback that reached that save for a row which no longer existed. The write itself is executable and is tested against
 * real SQLite in `MessageRowUpdateTest` and `ExpiryClaimTest`. What is left is that these three call sites actually use
 * it, so that is asserted against the source, narrowly, and each assertion was proven red by removing the line it names.
 */
class VolatileLifecycleWriteTest {

    private val messageServiceImpl = File("src/main/java/ch/threema/app/services/MessageServiceImpl.java")
    private val disappearingService = File("src/main/java/ch/threema/app/services/DisappearingMessageService.kt")

    // -----------------------------------------------------------------------------------------------------------------------------
    // The first-read decision.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a sender who explicitly said OFF is honoured, whatever the recipient's own timer says`() {
        val countdown = FirstReadDecision.countdownAtFirstRead(
            isOutbox = false,
            isDisappearingStatus = false,
            existingStart = null,
            frozenTimerSeconds = 0,
            conversationTimerSeconds = 30,
            readAtMillis = 1_700_000_000_000L,
        )

        assertNull(
            countdown,
            "a frozen 0 is the sender saying OFF; falling back to the recipient's 30s here would delete messages the " +
                "sender asked to keep, at the one point no receive-path test looks",
        )
    }

    @Test
    fun `a sender who advertised nothing falls back to the shared conversation timer`() {
        val countdown = FirstReadDecision.countdownAtFirstRead(
            isOutbox = false,
            isDisappearingStatus = false,
            existingStart = null,
            frozenTimerSeconds = null,
            conversationTimerSeconds = 30,
            readAtMillis = 1_700_000_000_000L,
        )

        assertNotNull(countdown)
        assertEquals(30, countdown.timerSeconds)
        assertEquals(1_700_000_000_000L, countdown.startedAt)
        assertEquals(1_700_000_030_000L, countdown.expiresAt)
    }

    @Test
    fun `the sender's own timer wins over the recipient's`() {
        val countdown = FirstReadDecision.countdownAtFirstRead(
            isOutbox = false,
            isDisappearingStatus = false,
            existingStart = null,
            frozenTimerSeconds = 10,
            conversationTimerSeconds = 300,
            readAtMillis = 1_700_000_000_000L,
        )

        assertNotNull(countdown)
        assertEquals(10, countdown.timerSeconds)
        assertEquals(1_700_000_010_000L, countdown.expiresAt)
    }

    @Test
    fun `a countdown that is already running is not restarted`() {
        val countdown = FirstReadDecision.countdownAtFirstRead(
            isOutbox = false,
            isDisappearingStatus = false,
            existingStart = 1_699_000_000_000L,
            frozenTimerSeconds = 30,
            conversationTimerSeconds = 30,
            readAtMillis = 1_700_000_000_000L,
        )

        assertNull(countdown, "restarting it at read time would hand the recipient a longer window than the sender allowed")
    }

    @Test
    fun `outgoing messages and timer status rows start nothing`() {
        assertNull(
            FirstReadDecision.countdownAtFirstRead(true, false, null, 30, 30, 1L),
            "an outgoing message's clock starts when it leaves the device, not when the sender reads their own chat",
        )
        assertNull(
            FirstReadDecision.countdownAtFirstRead(false, true, null, 30, 30, 1L),
            "the timer-change status row is not a disappearing message",
        )
    }

    @Test
    fun `both timers off starts nothing`() {
        assertNull(FirstReadDecision.countdownAtFirstRead(false, false, null, null, null, 1L))
        assertNull(FirstReadDecision.countdownAtFirstRead(false, false, null, null, 0, 1L))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The wiring.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `first read is one conditional write, not a full-row save`() {
        val source = messageServiceImpl.readText()
        // Sixth review, F6-01: the statement itself moved to MessageLifecycleUpdates, where the tests execute it against
        // a real database rather than reading it (LifecycleCacheCoherenceTest). Its predicates are asserted there.
        val update = File("src/main/java/ch/threema/app/services/MessageLifecycleUpdates.java").readText()

        assertTrue(
            source.contains("markReadDurably(message, readAt)"),
            "markAsRead must go through the reload-decide-compare-and-set write",
        )
        assertTrue(
            source.contains("MessageLifecycleUpdates.firstRead("),
            "and it must build that write in the one place every test drives",
        )
        assertTrue(
            update.contains(".expect(AbstractMessageModel.COLUMN_IS_READ, false)"),
            "the write must require the row to still be unread, or two readers both send a receipt",
        )
        assertTrue(
            update.contains(".expect(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT, priorStart)"),
            "and must require the countdown fields the decision READ, or it silently reverts a concurrent freeze",
        )
    }

    @Test
    fun `the incoming freeze never falls back to a detached model`() {
        val source = messageServiceImpl.readText()
        val freezeBody = bodyOf(source, "private void applyIncomingFreeze(")

        assertFalse(
            freezeBody.contains("target = messageModel;"),
            "the detached fallback is the one path by which an insert-capable save could still run for a row that had gone",
        )
        assertTrue(
            freezeBody.contains("its row is gone or unreadable"),
            "a failed re-read must end the freeze, not proceed from a stale instance",
        )
        assertTrue(
            freezeBody.contains("applyRowUpdate(target, MessageRowUpdate.builder()"),
            "the freeze must write only the three columns it owns",
        )
        assertFalse(
            freezeBody.contains("save(target)"),
            "a full-row save here restored the old body over a delete-for-everyone",
        )
    }

    @Test
    fun `lazy deadline repair cannot become the basis of a deletion it did not earn`() {
        val source = disappearingService.readText()
        val enforceBody = bodyOf(source, "private fun enforceIfExpiredInternal(")

        assertFalse(
            enforceBody.contains("messageService?.save(model)"),
            "the lazy repair used a full-row upsert, which recreated rows hard-deleted while it was computing",
        )
        assertTrue(
            enforceBody.contains("repairMissingDeadline(model)"),
            "it must go through the non-inserting conditional repair",
        )
        assertTrue(
            source.contains("return model.expiresAt != null"),
            "and a superseded repair must continue only from what is actually on disk",
        )
    }

    @Test
    fun `all three expiry paths claim the row before touching what it governs`() {
        val source = disappearingService.readText()

        // Named one by one rather than counted, so a path that stops routing through the claim fails by name.
        for (path in listOf("fun fireDue(", "fun purgeOverdueAndRearm(", "private fun enforceIfExpiredInternal(")) {
            assertTrue(
                bodyOf(source, path).contains("deleteExpiredMessage(serviceManager"),
                "$path must remove an expired message through the claim, not directly",
            )
        }
        assertTrue(
            source.contains("if (!messageService.removeIfStillDue(model, nowMillis))"),
            "and the claim must be the conditional delete, checked before the ballot aggregate is removed",
        )
        val deleteBody = bodyOf(source, "private fun deleteExpiredMessage(")
        assertFalse(
            deleteBody.contains("messageService.remove(model, false)"),
            "an unconditional remove here destroyed content whose timer had been turned off since the query",
        )
    }

    /**
     * The text from [signature] to the end of its body, matched by brace depth. Crude, and deliberately so: it exists only
     * to keep an assertion about one method from being satisfied by an identical line in another.
     */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "this test's anchor has drifted: $signature")
        var depth = 0
        var seenOpen = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> {
                    depth++
                    seenOpen = true
                }

                '}' -> {
                    depth--
                    if (seenOpen && depth == 0) {
                        return source.substring(start, index + 1)
                    }
                }
            }
        }
        error("unbalanced braces after $signature")
    }
}
