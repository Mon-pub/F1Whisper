package ch.threema.app.services

import ch.threema.app.services.MessageRowHarness.Companion.BASE_TIME
import ch.threema.app.services.MessageRowHarness.Companion.GROUP_TABLE
import ch.threema.storage.models.MessageState
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (sixth fork review, F6-04): a notes-group text or location may only be written as complete, with its
 * countdown running, when nothing else is going to acknowledge it.
 *
 * A notes group has no other members, so the creation paths recorded one as `SENT` before the insert rather than showing
 * the user a spinner for a send with nowhere to go, and the fifth review made that boundary durable by starting the
 * countdown there. Both are right while the empty member set really does mean "nothing will acknowledge this" - and with
 * multi-device active it does not. The message is still reflected to the linked devices, and the group task stores its
 * completion only after that reflection is acknowledged. Deciding on the member set alone therefore started the
 * disappearing countdown at composition: compose offline with a short timer, stay offline past it, and expiry claimed
 * the row before the queued task ever ran. On reconnect the task found no message, so the linked device never received
 * it, and the content had disappeared from the device that wrote it without reaching anywhere at all.
 *
 * Two things are asserted: the predicate itself, and the consequence in the database - that the pre-terminal row the fix
 * writes cannot be claimed by expiry, while the row the old shape wrote can and is.
 */
class NotesGroupCompletionBoundaryTest {
    private lateinit var harness: MessageRowHarness

    private val messageId = 1
    private val timerSeconds = 30
    private val composedAt = BASE_TIME
    private val wellPastTheTimer = composedAt + 10 * 60_000L

    @BeforeTest
    fun setUp() {
        harness = MessageRowHarness(GROUP_TABLE)
    }

    @AfterTest
    fun tearDown() {
        harness.close()
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The predicate.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a notes group with multi-device active does not complete locally`() {
        assertFalse(
            OutgoingSendBoundaryDecision.completesLocally(hasNoOtherMembers = true, hasPendingRemoteCompletion = true),
            "the reflection to the linked devices is the completion, and it has not happened yet",
        )
    }

    @Test
    fun `a notes group without multi-device completes locally`() {
        assertTrue(
            OutgoingSendBoundaryDecision.completesLocally(hasNoOtherMembers = true, hasPendingRemoteCompletion = false),
            "there is no recipient and no reflection: creation IS the completion, and the countdown must be durable here",
        )
    }

    @Test
    fun `a group with other members never completes locally`() {
        assertFalse(OutgoingSendBoundaryDecision.completesLocally(hasNoOtherMembers = false, hasPendingRemoteCompletion = true))
        assertFalse(OutgoingSendBoundaryDecision.completesLocally(hasNoOtherMembers = false, hasPendingRemoteCompletion = false))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The consequence.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a multi-device notes message survives an expiry sweep until its reflection is acknowledged`() {
        // What creation now writes when the reflection is still pending: no terminal state, and no clock.
        harness.insertGroupRow(messageId, outbox = true, state = null, timerSeconds = timerSeconds, createdAt = composedAt)

        val model = harness.requireModel(GROUP_TABLE, messageId)
        assertFalse(
            harness.claimIfStillDue(GROUP_TABLE, messageId, model.expireStartedAt, model.expiresAt, wellPastTheTimer),
            "a message with no countdown is not due, however long the device stays offline",
        )
        assertEquals(1, harness.rowCount(GROUP_TABLE, messageId), "so the task still has a payload to send when it runs")
    }

    @Test
    fun `the acknowledged reflection then starts exactly one countdown`() {
        harness.insertGroupRow(messageId, outbox = true, state = null, timerSeconds = timerSeconds, createdAt = composedAt)

        // The group task's completion callback, with the reflection's own acknowledgement timestamp.
        val reflectedAt = Date(composedAt + 5 * 60_000L)
        val current = harness.requireModel(GROUP_TABLE, messageId)
        val update = OutgoingTransitionPlanner.plan(current, MessageState.READ, reflectedAt, null, true)
        assertNotNull(update)
        assertTrue(harness.apply(GROUP_TABLE, messageId, update))

        assertEquals(MessageState.READ.toString(), harness.stringOf(GROUP_TABLE, messageId, "state"))
        assertEquals(reflectedAt.time, harness.longOf(GROUP_TABLE, messageId, "expireStartedAtUtc"))
        assertEquals(reflectedAt.time + timerSeconds * 1000L, harness.longOf(GROUP_TABLE, messageId, "expiresAtUtc"))

        // A second completion (a retry, a duplicate reflect ack) must not buy it another interval.
        val again = harness.requireModel(GROUP_TABLE, messageId)
        val second = OutgoingTransitionPlanner.plan(again, MessageState.READ, Date(reflectedAt.time + 60_000L), null, true)
        if (second != null) {
            harness.apply(GROUP_TABLE, messageId, second)
        }
        assertEquals(reflectedAt.time, harness.longOf(GROUP_TABLE, messageId, "expireStartedAtUtc"))
    }

    @Test
    fun `a notes message without multi-device keeps its durable local boundary`() {
        harness.insertGroupRow(messageId, outbox = true, state = MessageState.SENT, timerSeconds = timerSeconds, createdAt = composedAt)

        val current = harness.requireModel(GROUP_TABLE, messageId)
        val update = OutgoingTransitionPlanner.plan(current, MessageState.SENT, Date(composedAt), null, true)
        assertNotNull(update)
        assertTrue(harness.apply(GROUP_TABLE, messageId, update))

        assertEquals(composedAt, harness.longOf(GROUP_TABLE, messageId, "expireStartedAtUtc"), "creation IS the completion here")
        assertEquals(composedAt + timerSeconds * 1000L, harness.longOf(GROUP_TABLE, messageId, "expiresAtUtc"))
        assertEquals(MessageState.SENT.toString(), harness.stringOf(GROUP_TABLE, messageId, "state"))
    }

    @Test
    fun legacyTerminalAtCompositionLetsExpiryDestroyThePayload() {
        // What creation wrote before the fix, with multi-device active: SENT and counting down from composition.
        harness.insertGroupRow(
            messageId,
            outbox = true,
            state = MessageState.SENT,
            timerSeconds = timerSeconds,
            createdAt = composedAt,
            expireStartedAt = composedAt,
            expiresAt = composedAt + timerSeconds * 1000L,
        )

        val model = harness.requireModel(GROUP_TABLE, messageId)
        assertTrue(
            harness.claimIfStillDue(GROUP_TABLE, messageId, model.expireStartedAt, model.expiresAt, wellPastTheTimer),
            "this is the defect: the sweep claims the row while the send task is still queued",
        )
        assertEquals(0, harness.rowCount(GROUP_TABLE, messageId))
        assertNull(
            harness.readModel(GROUP_TABLE, messageId),
            "so on reconnect the task loads nothing, and the linked device never receives the message",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Both creation paths ask the right question.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `both notes-group creation paths decide on remote completion, not on the member set`() {
        val receiver = java.io.File("src/main/java/ch/threema/app/messagereceiver/GroupMessageReceiver.java").readText()

        for (path in listOf("public void createAndSendTextMessage(", "public void createAndSendLocationMessage(")) {
            val body = bodyOf(receiver, path)
            assertTrue(
                body.contains("OutgoingSendBoundaryDecision.completesLocally(\n            otherMembers.isEmpty(), hasPendingRemoteCompletion())"),
                "$path must ask whether anything will acknowledge this message, not just whether it has recipients",
            )
            assertTrue(
                body.contains("startNotesGroupCountdown(messageModel, completesLocally)"),
                "$path must start the countdown only when creation really is the completion",
            )
            assertFalse(
                body.contains("if (otherMembers.isEmpty()) {"),
                "$path must no longer set a terminal state on the member set alone",
            )
        }
        assertTrue(
            bodyOf(receiver, "public boolean hasPendingRemoteCompletion()").contains("return shouldSendMediaData();"),
            "and the remote-completion truth must stay the media path's, which is true whenever multi-device is active",
        )
    }

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
