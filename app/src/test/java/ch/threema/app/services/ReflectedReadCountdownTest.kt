package ch.threema.app.services

import ch.threema.app.services.MessageRowHarness.Companion.BASE_TIME
import ch.threema.app.services.MessageRowHarness.Companion.CONTACT_TABLE
import ch.threema.app.services.MessageRowHarness.Companion.GROUP_TABLE
import ch.threema.storage.models.AbstractMessageModel
import java.io.File
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (seventh fork review, F7-04): a read performed on a LINKED device arms the countdown on this one, in both
 * conversation types and in both of the ways such a read can be represented.
 *
 * The failure this reproduces: the sixth review converted the reflected-incoming-message-update path, but that path is
 * only used when NO read receipt is owed to the peer. With read receipts enabled - the default - a 1:1 read is
 * announced as an outgoing delivery receipt, reflected, and handled by a branch that simply set `readAt`, `modifiedAt`
 * and `isRead` on a cached model and full-row-saved it. The message therefore became read with no countdown start and
 * no deadline. Normal expiry only selects rows that HAVE a deadline, and the pass that would repair one runs at
 * startup, so the message outlived the interval its sender advertised until the app was next relaunched.
 *
 * The group case was worse: a group delivery receipt is deliberately not reflected at all, so the primary device
 * received nothing, stayed unread, and was not even eligible for the startup repair, which skips unread rows.
 *
 * What is executed here is the durable transition itself - the real [MessageLifecycleUpdates.firstRead] statement, with
 * the countdown decided by the real [FirstReadDecision] - against a real row, next to the legacy full-row save it
 * replaces.
 */
class ReflectedReadCountdownTest {

    private val harness = MessageRowHarness(CONTACT_TABLE, GROUP_TABLE)
    private val readAt = Date(BASE_TIME + 10_000)

    @AfterTest
    fun tearDown() = harness.close()

    // -----------------------------------------------------------------------------------------------------------------------------
    // The reflected READ is a durable first read.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a reflected read of a timed contact message arms the countdown immediately`() {
        harness.insertContactRow(messageId = 41, isRead = false, timerSeconds = 30)

        assertTrue(reflectedRead(CONTACT_TABLE, 41))

        assertEquals(1L, harness.longOf(CONTACT_TABLE, 41, "isRead"))
        assertEquals(readAt.time, harness.longOf(CONTACT_TABLE, 41, "readAtUtc"), "the ORIGINAL read timestamp")
        assertEquals(readAt.time, harness.longOf(CONTACT_TABLE, 41, "expireStartedAtUtc"))
        assertEquals(readAt.time + 30_000, harness.longOf(CONTACT_TABLE, 41, "expiresAtUtc"))
    }

    @Test
    fun `a reflected read of a timed group message arms the countdown immediately`() {
        harness.insertGroupRow(messageId = 42, isRead = false, outbox = false, timerSeconds = 60)

        assertTrue(reflectedRead(GROUP_TABLE, 42))

        assertEquals(1L, harness.longOf(GROUP_TABLE, 42, "isRead"))
        assertEquals(readAt.time, harness.longOf(GROUP_TABLE, 42, "expireStartedAtUtc"))
        assertEquals(readAt.time + 60_000, harness.longOf(GROUP_TABLE, 42, "expiresAtUtc"))
    }

    @Test
    fun `the legacy full-row save recorded the read with no clock at all`() {
        harness.insertContactRow(messageId = 41, isRead = false, timerSeconds = 30)

        // The control: exactly what ReflectedOutgoingDeliveryReceiptTask did - set three fields on a model and save it.
        val model = harness.requireModel(CONTACT_TABLE, 41)
        model.isRead = true
        model.readAt = readAt
        model.modifiedAt = readAt
        harness.legacyFullRowUpsert(CONTACT_TABLE, model)

        assertEquals(1L, harness.longOf(CONTACT_TABLE, 41, "isRead"))
        assertNull(harness.longOf(CONTACT_TABLE, 41, "expireStartedAtUtc"), "read, and invisible to normal expiry")
        assertNull(harness.longOf(CONTACT_TABLE, 41, "expiresAtUtc"))
    }

    @Test
    fun `a reflected read of an untimed message records the read and starts no clock`() {
        harness.insertContactRow(messageId = 41, isRead = false, timerSeconds = null)

        assertTrue(reflectedRead(CONTACT_TABLE, 41))

        assertEquals(1L, harness.longOf(CONTACT_TABLE, 41, "isRead"))
        assertNull(harness.longOf(CONTACT_TABLE, 41, "expiresAtUtc"))
    }

    @Test
    fun `a second reflected read does not move the clock`() {
        harness.insertContactRow(messageId = 41, isRead = false, timerSeconds = 30)
        assertTrue(reflectedRead(CONTACT_TABLE, 41))

        assertFalse(reflectedRead(CONTACT_TABLE, 41), "the row is already read; this reflection owes nothing")
        assertEquals(readAt.time + 30_000, harness.longOf(CONTACT_TABLE, 41, "expiresAtUtc"))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // A row that lost a deletion race publishes nothing.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a reflected read of a hard-deleted row writes nothing`() {
        harness.insertContactRow(messageId = 41, isRead = false, timerSeconds = 30)
        harness.hardDelete(CONTACT_TABLE, 41)

        assertFalse(reflectedRead(CONTACT_TABLE, 41))
        assertEquals(0, harness.rowCount(CONTACT_TABLE, 41), "and above all does not bring the row back")
    }

    @Test
    fun `a reflected read of a row deleted for everyone writes nothing`() {
        harness.insertGroupRow(messageId = 42, isRead = false, outbox = false, timerSeconds = 30)
        harness.deleteForEveryone(GROUP_TABLE, 42, BASE_TIME + 5_000)

        assertFalse(reflectedRead(GROUP_TABLE, 42))
        assertEquals(0L, harness.longOf(GROUP_TABLE, 42, "isRead"))
        assertNull(harness.longOf(GROUP_TABLE, 42, "expiresAtUtc"))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The reflected DELIVERED writes its own two columns.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a reflected delivered stores the received timestamp and the sort key together`() {
        harness.insertContactRow(messageId = 41, isRead = false, createdAt = BASE_TIME)
        val deliveredAt = Date(BASE_TIME + 2_000)

        assertTrue(
            harness.apply(
                CONTACT_TABLE,
                41,
                MessageLifecycleUpdates.receivedTimestamp(deliveredAt, deliveredAt),
            ),
        )

        assertEquals(deliveredAt.time, harness.longOf(CONTACT_TABLE, 41, "createdAtUtc"))
        assertEquals(deliveredAt.time, harness.longOf(CONTACT_TABLE, 41, "sortAtUtc"))
        assertEquals(0L, harness.longOf(CONTACT_TABLE, 41, "isRead"), "and nothing else")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Both representations reach the durable transition. Source assertions, supplementing the behaviour above: neither
    // MessageServiceImpl nor a reflected task can be constructed in a JVM unit test.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the read-receipts-enabled reflection routes through the durable transitions`() {
        val task = File(
            "src/main/java/ch/threema/app/processors/reflectedoutgoingmessage/ReflectedOutgoingDeliveryReceiptTask.kt",
        ).readText()

        assertTrue(task.contains("messageService.markAsReadFromSync(messageModel, date)"))
        assertTrue(task.contains("messageService.updateReceivedTimestamp(messageModel, date)"))
        assertFalse(
            task.contains("messageService.save(messageModel)"),
            "a full-row save of a cached model is the defect: it records the read and no clock",
        )
        assertTrue(
            task.contains("if (updated) {"),
            "the listener may only fire when the row was actually updated",
        )
        assertTrue(
            task.contains("if (updateMessage(messageModel, state) && state == MessageState.READ)"),
            "and the notification may only be cancelled then too",
        )
    }

    @Test
    fun `the group read intent reaches the linked devices in both branches`() {
        val service = File("src/main/java/ch/threema/app/services/MessageServiceImpl.java").readText()

        assertEquals(
            2,
            Regex("reflectGroupReadToLinkedDevices\\(\\(GroupMessageModel\\) message, readAt\\)")
                .findAll(service).count(),
            "the receipts-enabled branch and the incoming-update branch must both announce the read",
        )
        val peerReceipt = service.indexOf("sendGroupReceiptToSender((GroupMessageModel) message, ProtocolDefines.DELIVERYRECEIPT_MSGREAD)")
        val reflect = service.indexOf("reflectGroupReadToLinkedDevices((GroupMessageModel) message, readAt)")
        assertTrue(peerReceipt in 1..<reflect, "the peer receipt stays; the reflection is ADDED alongside it")

        // sendIncomingMessageUpdateRead is D2D-only, so it cannot duplicate the peer-facing receipt.
        val receiver = File("src/main/java/ch/threema/app/messagereceiver/GroupMessageReceiver.java").readText()
        val body = receiver.substringAfter("public void sendIncomingMessageUpdateRead(").substringBefore("\n    }")
        assertTrue(body.contains("multiDeviceManager.isMultiDeviceActive()"))
        assertTrue(body.contains("OutboundIncomingGroupMessageUpdateReadTask"))
    }

    /** `MessageServiceImpl.markReadDurably`, decided by the production rule from the row itself. */
    private fun reflectedRead(table: String, messageId: Int): Boolean {
        val current: AbstractMessageModel = harness.readModel(table, messageId) ?: return false
        if (current.isRead) {
            return false
        }
        val countdown = FirstReadDecision.countdownAtFirstRead(
            current.isOutbox,
            false,
            current.expireStartedAt,
            current.disappearingTimerSeconds,
            null,
            readAt.time,
        )
        return harness.apply(
            table,
            messageId,
            MessageLifecycleUpdates.firstRead(
                readAt,
                current.disappearingTimerSeconds,
                current.expireStartedAt,
                current.expiresAt,
                countdown,
            ),
        )
    }
}
