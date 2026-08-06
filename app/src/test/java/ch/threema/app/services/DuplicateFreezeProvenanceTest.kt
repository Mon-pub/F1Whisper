package ch.threema.app.services

import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (fifth fork review, F5-05): a duplicate must restore the policy accepted for that message, never apply a
 * later conversation setting.
 *
 * The defect. F4-05 made a duplicate redelivery repair the sender's policy, which is right when the sender advertised
 * one - that redelivery is the message's only second chance if the app died between the insert and the freeze. But it
 * ran the same repair when the sender advertised NOTHING, and an absent value resolves against the conversation's timer
 * AS IT IS NOW. So an at-least-once duplicate of an old message from a pre-v6.4.3-38 client silently re-froze it at
 * whatever the timer had since been changed to.
 *
 * The concrete failure: receive and READ a message while the shared timer is off; later turn a 30-second timer on; a
 * duplicate then arrives with metadata still absent. The old message was given a 30-second deadline measured from its
 * old read time, so it became immediately overdue - content deleted early because a network duplicate arrived after an
 * unrelated settings change. A 30-to-300 change extended retention the same way.
 *
 * The tri-state is what makes the rule expressible, and 30-to-OFF is a NO-OP CONTROL rather than a fix: absent metadata
 * resolves to `null`, so it never cleared the frozen 30 either. That case is asserted here as a no-op precisely so a
 * future reader does not mistake it for something the fix was supposed to change.
 *
 * The resolution is pure and is tested by running it. The four call sites are asserted against the source; each of those
 * assertions was proven red by removing the guard it names.
 */
class DuplicateFreezeProvenanceTest {

    private val messageServiceImpl = File("src/main/java/ch/threema/app/services/MessageServiceImpl.java")
    private val contactFileTask =
        File("src/main/java/ch/threema/app/processors/incomingcspmessage/conversation/IncomingContactFileMessageTask.kt")
    private val groupFileTask =
        File("src/main/java/ch/threema/app/processors/incomingcspmessage/conversation/IncomingGroupFileMessageTask.kt")

    private val readAtMillis = 1_700_000_000_000L

    // -----------------------------------------------------------------------------------------------------------------------------
    // What a duplicate is allowed to change.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `an absent advertised timer resolves against current conversation state, which is why it must not be applied`() {
        // This is the mechanism, stated plainly: the resolution itself is correct for a FIRST acceptance and wrong for a
        // duplicate, so the fix belongs at the call site rather than in the resolver.
        assertEquals(
            30,
            DisappearingFreezeDecision.resolveIncomingTimer(null, 30),
            "an absent value is answered from the LOCAL timer as it is now",
        )
        assertEquals(
            300,
            DisappearingFreezeDecision.resolveIncomingTimer(null, 300),
        )
        assertNull(
            DisappearingFreezeDecision.resolveIncomingTimer(null, null),
            "and 30-to-OFF resolves to null, which is why that direction was always a no-op",
        )
    }

    @Test
    fun `a legacy message read while the timer was off keeps no countdown when a duplicate arrives after it is turned on`() {
        // OFF-to-30 before the duplicate: the reported failure.
        val model = alreadyReadLegacyMessage()

        applyDuplicateRepair(model, advertisedBySender = null)

        assertNull(model.disappearingTimerSeconds, "the policy accepted for this message was 'no timer'")
        assertNull(model.expireStartedAt)
        assertNull(model.expiresAt)
        assertFalse(
            DisappearingMessageService.isExpired(model),
            "under the old behaviour this message became overdue the moment the duplicate landed, measured from its " +
                "old read time",
        )
    }

    @Test
    fun `a duplicate after a 30-to-300 change does not extend the accepted interval`() {
        val model = alreadyReadLegacyMessage().apply {
            disappearingTimerSeconds = 30
            expireStartedAt = readAtMillis
            expiresAt = readAtMillis + 30_000L
        }

        applyDuplicateRepair(model, advertisedBySender = null)

        assertEquals(30, model.disappearingTimerSeconds)
        assertEquals(readAtMillis + 30_000L, model.expiresAt, "a network duplicate is not new information about policy")
    }

    @Test
    fun `30-to-OFF is a no-op control, not a fix`() {
        val model = alreadyReadLegacyMessage().apply {
            disappearingTimerSeconds = 30
            expireStartedAt = readAtMillis
            expiresAt = readAtMillis + 30_000L
        }

        applyDuplicateRepair(model, advertisedBySender = null)

        assertEquals(
            30,
            model.disappearingTimerSeconds,
            "absent metadata resolves to null, so it never cleared the frozen 30 - do not read this as the fix failing",
        )
    }

    @Test
    fun `an explicit OFF from the sender still repairs a crash-damaged row`() {
        val model = alreadyReadLegacyMessage().apply {
            // The previous run inserted the row with the PROVISIONAL local timer and died before the freeze.
            disappearingTimerSeconds = 30
            expireStartedAt = readAtMillis
            expiresAt = readAtMillis + 30_000L
        }

        applyDuplicateRepair(model, advertisedBySender = 0)

        assertEquals(0, model.disappearingTimerSeconds, "the sender said OFF, and the duplicate is the only second chance")
        assertNull(model.expireStartedAt, "so the countdown derived from the provisional timer is cancelled")
        assertNull(model.expiresAt)
    }

    @Test
    fun `an explicit positive timer from the sender still repairs a crash-damaged row`() {
        val model = alreadyReadLegacyMessage()

        applyDuplicateRepair(model, advertisedBySender = 10)

        assertEquals(10, model.disappearingTimerSeconds)
        assertEquals(readAtMillis, model.expireStartedAt, "the countdown starts when the message was actually read")
        assertEquals(readAtMillis + 10_000L, model.expiresAt)
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // All four duplicate paths, and the redundant post-insert re-resolution.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the contact and group duplicate branches repair only an explicit value`() {
        val source = messageServiceImpl.readText()

        assertTrue(
            source.contains("repairDuplicateIncomingFreeze(savedMessageModel, message.getDisappearingTimerSeconds())"),
            "the 1:1 duplicate branch",
        )
        assertTrue(
            source.contains("repairDuplicateIncomingFreeze(existingModel, message.getDisappearingTimerSeconds())"),
            "the group duplicate branch",
        )
        assertTrue(
            bodyOf(source, "private void repairDuplicateIncomingFreeze(").contains("if (advertisedBySender == null) {"),
            "and the repair itself must refuse an absent value rather than resolve it against current state",
        )
    }

    @Test
    fun `both FILE duplicate branches go through the same repair`() {
        for (file in listOf(contactFileTask, groupFileTask)) {
            assertTrue(
                file.readText().contains("messageService.freezeIncomingDisappearingPolicy(this, message.disappearingTimerSeconds)"),
                "${file.name} must repair through the service entry point, which applies the same restriction",
            )
        }
        assertTrue(
            bodyOf(messageServiceImpl.readText(), "public void freezeIncomingDisappearingPolicy(")
                .contains("repairDuplicateIncomingFreeze(messageModel, advertisedBySender)"),
            "and that entry point must be the restricted repair, not the raw freeze",
        )
    }

    @Test
    fun `a newly accepted absent-metadata message is not re-resolved after its insert`() {
        val source = messageServiceImpl.readText()

        for (path in listOf("public boolean processIncomingContactMessage(", "public boolean processIncomingGroupMessage(")) {
            val body = bodyOf(source, path)
            assertTrue(
                body.contains("if (message.getDisappearingTimerSeconds() != null) {"),
                "$path must not answer the absent-metadata question a second time: the freeze that matters already " +
                    "happened before the insert, and the conversation timer may have changed since",
            )
        }
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------------------------------------------

    /**
     * The production repair, applied exactly as `MessageServiceImpl.repairDuplicateIncomingFreeze` applies it: refuse an
     * absent advertised value, otherwise resolve and freeze. The conversation timer is 30s throughout, standing for "the
     * user turned a timer on after this message arrived".
     */
    private fun applyDuplicateRepair(model: MessageModel, advertisedBySender: Int?) {
        if (advertisedBySender == null) {
            return
        }
        DisappearingMessageService.freezeIncomingTimer(
            model,
            DisappearingFreezeDecision.resolveIncomingTimer(advertisedBySender, 30),
        )
    }

    private fun alreadyReadLegacyMessage() = MessageModel().apply {
        uid = "uid-1"
        identity = "AAAAAAAA"
        isOutbox = false
        type = MessageType.TEXT
        isRead = true
        readAt = java.util.Date(readAtMillis)
    }

    /** The text from [signature] to the end of its body, matched by brace depth. */
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
