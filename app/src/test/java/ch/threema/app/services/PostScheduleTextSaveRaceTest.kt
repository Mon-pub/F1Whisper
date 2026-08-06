package ch.threema.app.services

import ch.threema.app.services.MessageRowHarness.Companion.BASE_TIME
import ch.threema.app.services.MessageRowHarness.Companion.CONTACT_TABLE
import ch.threema.app.services.MessageRowHarness.Companion.GROUP_TABLE
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageState
import java.io.File
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (seventh fork review, F7-05): nothing full-row-saves a message after a persistent task has been scheduled
 * for it.
 *
 * The failure this reproduces: `sendText` saved the model, called the receiver - which assigns the message id, persists
 * the row and SCHEDULES the task - and then saved the whole row again. A direct receiver save takes no cache monitor
 * and builds its values from a snapshot taken before its SQL update, so the task's acknowledged completion could land
 * in between and be overwritten by that snapshot's `SENDING` and null clock. The resulting outgoing row has no deadline,
 * which makes it invisible to normal expiry, and no start, which makes the startup repair pass skip it by design. A
 * message whose reflection was acknowledged therefore kept its content indefinitely.
 *
 * What is executed here is the collision itself: the real [OutgoingTransitionPlanner] transition (state, authoritative
 * timestamp and countdown, written together by the real statement) against a real row, then the legacy full-row write
 * built from the pre-transition snapshot, in both orders.
 */
class PostScheduleTextSaveRaceTest {

    private val harness = MessageRowHarness(CONTACT_TABLE, GROUP_TABLE)
    private val acknowledgedAt = Date(BASE_TIME + 5_000)

    @AfterTest
    fun tearDown() = harness.close()

    // -----------------------------------------------------------------------------------------------------------------------------
    // The collision.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a trailing full-row save destroys the acknowledged state and countdown`() {
        harness.insertGroupRow(messageId = 42, state = MessageState.SENDING, timerSeconds = 30)
        // sendText's trailing save builds its values here, from the model as it was before the task ran.
        val snapshot = harness.requireModel(GROUP_TABLE, 42)

        completeTask(GROUP_TABLE, 42, MessageState.READ)
        assertEquals(MessageState.READ.toString(), harness.stringOf(GROUP_TABLE, 42, "state"))
        assertEquals(acknowledgedAt.time + 30_000, harness.longOf(GROUP_TABLE, 42, "expiresAtUtc"))

        // ... and only now does the trailing save reach the database.
        assertTrue(harness.fullRowUpdate(GROUP_TABLE, snapshot))

        assertEquals(
            MessageState.SENDING.toString(),
            harness.stringOf(GROUP_TABLE, 42, "state"),
            "the control: a message whose reflection was acknowledged has regressed to in-flight",
        )
        assertNull(harness.longOf(GROUP_TABLE, 42, "expiresAtUtc"), "and lost its deadline")
        assertNull(
            harness.longOf(GROUP_TABLE, 42, "expireStartedAtUtc"),
            "with no start, the startup repair pass will not touch it either",
        )
    }

    @Test
    fun `with no trailing save the acknowledged state and countdown survive`() {
        harness.insertGroupRow(messageId = 42, state = MessageState.SENDING, timerSeconds = 30)

        completeTask(GROUP_TABLE, 42, MessageState.READ)

        assertEquals(MessageState.READ.toString(), harness.stringOf(GROUP_TABLE, 42, "state"))
        assertEquals(acknowledgedAt.time, harness.longOf(GROUP_TABLE, 42, "expireStartedAtUtc"))
        assertEquals(acknowledgedAt.time + 30_000, harness.longOf(GROUP_TABLE, 42, "expiresAtUtc"))
    }

    @Test
    fun `a contact text keeps its terminal state too`() {
        harness.insertContactRow(messageId = 41, outbox = true, state = MessageState.SENDING, timerSeconds = 30)

        completeTask(CONTACT_TABLE, 41, MessageState.SENT)

        assertEquals(MessageState.SENT.toString(), harness.stringOf(CONTACT_TABLE, 41, "state"))
        assertEquals(acknowledgedAt.time + 30_000, harness.longOf(CONTACT_TABLE, 41, "expiresAtUtc"))
    }

    @Test
    fun `the ordinary order still stores the generated id and payload`() {
        // The reverse-order control: the save that legitimately precedes scheduling writes the message id and body, and
        // the transition that follows keeps them.
        harness.insertContactRow(messageId = 41, body = "hello", outbox = true, state = MessageState.PENDING, timerSeconds = 30)
        val model = harness.requireModel(CONTACT_TABLE, 41)
        model.apiMessageId = "0011223344556677"
        model.state = MessageState.SENDING
        assertTrue(harness.fullRowUpdate(CONTACT_TABLE, model))

        completeTask(CONTACT_TABLE, 41, MessageState.SENT)

        assertEquals("0011223344556677", harness.stringOf(CONTACT_TABLE, 41, "apiMessageId"))
        assertEquals("hello", harness.stringOf(CONTACT_TABLE, 41, "body"))
        assertEquals(MessageState.SENT.toString(), harness.stringOf(CONTACT_TABLE, 41, "state"))
        assertEquals(acknowledgedAt.time + 30_000, harness.longOf(CONTACT_TABLE, 41, "expiresAtUtc"))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The audit: no existing-row full writer survives where a task can own the row. Source assertions, supplementing the
    // behaviour above: MessageServiceImpl cannot be constructed in a JVM unit test.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `sendText does not save after the receiver has scheduled the task`() {
        val service = service()
        val schedule = service.indexOf("messageReceiver.createAndSendTextMessage(messageModel)")
        val tail = service.indexOf("fireOnModifiedMessage(messageModel)", schedule)

        assertTrue(schedule > 0)
        assertFalse(
            service.substring(schedule, tail).contains("saveLocalModel"),
            "the receiver already persisted the generated message id before it scheduled the task",
        )
    }

    @Test
    fun `neither media send machine saves after createAndSendFileMessage`() {
        val service = service()
        Regex("createAndSendFileMessage\\(").findAll(service).forEach { match ->
            val window = service.substring(match.range.first, minOf(service.length, match.range.first + 1400))
            assertFalse(
                window.contains("save(messageModel);"),
                "a full-row save after the file task is scheduled can overwrite its acknowledged completion",
            )
        }
    }

    @Test
    fun `the converted existing-row writers own one column each`() {
        val service = service()

        assertTrue(
            service.contains("clearDisplayTag(messageModel, DisplayTag.DISPLAY_TAG_SEND_FAILED_TERMINAL)"),
            "clearing the terminal failure marker used to full-row-save a message whose send is in flight",
        )
        assertTrue(
            service.contains("MessageLifecycleUpdates.clearedReactionState("),
            "withdrawing a reaction used to full-row-save the repository's timeline instance",
        )
        assertTrue(
            service.contains("MessageLifecycleUpdates.locationAddress("),
            "a geocoder result used to full-row-save the adapter's timeline instance",
        )

        val geo = File("src/main/java/ch/threema/app/utils/GeoLocationUtil.java").readText()
        assertTrue(geo.contains("messageService.updateLocationAddress(messageModel, address)"))
        assertFalse(geo.contains("messageService.save(messageModel)"))
    }

    /** The acknowledged completion an outgoing task performs: state, timestamp and countdown as one write. */
    private fun completeTask(table: String, messageId: Int, state: MessageState) {
        val current: AbstractMessageModel = harness.requireModel(table, messageId)
        val update = OutgoingTransitionPlanner.plan(current, state, acknowledgedAt, null, false)
        assertTrue(update != null, "the transition must produce a write")
        assertTrue(harness.apply(table, messageId, update!!))
    }

    private fun service() = File("src/main/java/ch/threema/app/services/MessageServiceImpl.java").readText()
}
