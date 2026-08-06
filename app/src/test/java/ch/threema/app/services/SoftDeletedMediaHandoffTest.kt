package ch.threema.app.services

import ch.threema.app.services.MessageRowHarness.Companion.BASE_TIME
import ch.threema.app.services.MessageRowHarness.Companion.CONTACT_TABLE
import ch.threema.app.services.MessageRowHarness.Companion.GROUP_TABLE
import ch.threema.app.tasks.PersistentTaskRowGate
import ch.threema.storage.MessageCacheCoherence
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageState
import java.io.File
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (eighth fork review, H8-01): a finished upload may not put the media back into a message that was deleted
 * while it was uploading.
 *
 * **The failure this reproduces.** The user picks the wrong photo, sends it, and while the progress bar is still moving
 * chooses "delete for everyone". The deletion marks the row and empties it, but it cancelled only an incoming download,
 * so the outgoing upload carried on; when it finished, the send handoff wrote the blob id, the encryption key, the body
 * and the caption into that same row. The full-row save's only predicate was `id = ?`, and a tombstone still has its id,
 * so the write succeeded. The chat went on saying the message was deleted while the database held everything needed to
 * fetch it again. No two operations had to land in the same millisecond: an upload lasts seconds or minutes and the UI
 * offers delete-for-everyone for a PENDING, UPLOADING or SENDING message throughout.
 *
 * **The three shipped decisions, all executed here.**
 *
 * - the full-row save now runs the same structural `deletedAtUtc IS NULL` predicate the column-scoped write has run
 *   since F5-04 ([MessageRowHarness.fullRowUpdate], which takes the clause from the factory that ships it);
 * - the deletion aborts the send machine and its uploader, as hard deletion always did;
 * - the media handoff reports the refusal and both send machines stop rather than publishing a state, a listener or a
 *   completion for a send that is not happening.
 *
 * Both interleavings are asserted, because a boundary that only holds in one order is not a boundary: the deletion
 * either gets there first (the handoff is refused) or arrives second (its own clearing write erases what the handoff
 * had just written).
 */
class SoftDeletedMediaHandoffTest {

    private val harness = MessageRowHarness(CONTACT_TABLE, GROUP_TABLE)

    @AfterTest
    fun tearDown() = harness.close()

    // -----------------------------------------------------------------------------------------------------------------------------
    // The deletion gets there first: the handoff is refused and writes nothing.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a finished upload cannot refill a contact message deleted for everyone`() {
        val uploading = startUpload(CONTACT_TABLE, 41)

        assertTrue(harness.deleteForEveryone(CONTACT_TABLE, 41, DELETED_AT))
        assertFalse(harness.fullRowUpdate(CONTACT_TABLE, finishUpload(uploading)), "the tombstone must refuse it")
        assertFalse(
            harness.fullRowUpdate(CONTACT_TABLE, finishUpload(uploading).also { it.deletedAt = Date(DELETED_AT) }),
            "and refuse it whether or not the instance doing the writing has heard about the deletion",
        )

        assertTombstoneIntact(CONTACT_TABLE, 41)
    }

    @Test
    fun `a finished upload cannot refill a group message deleted for everyone`() {
        val uploading = startUpload(GROUP_TABLE, 42)

        assertTrue(harness.deleteForEveryone(GROUP_TABLE, 42, DELETED_AT))
        assertFalse(harness.fullRowUpdate(GROUP_TABLE, finishUpload(uploading)), "the tombstone must refuse it")

        assertTombstoneIntact(GROUP_TABLE, 42)
    }

    @Test
    fun `the unguarded save put the credentials back into the tombstone`() {
        val uploading = startUpload(CONTACT_TABLE, 41)
        harness.deleteForEveryone(CONTACT_TABLE, 41, DELETED_AT)

        // The control: the full-row save as it shipped before this wave, matching on the id alone. The instance is the
        // one the deletion's cache reconciliation reached, so it knows the row is deleted - and writes the content back
        // anyway, because a full-row save writes every column it holds and asks nothing about the row it lands on.
        harness.legacyFullRowUpsert(CONTACT_TABLE, finishUpload(uploading).also { it.deletedAt = Date(DELETED_AT) })

        assertEquals(
            UPLOADED_BODY,
            harness.stringOf(CONTACT_TABLE, 41, "body"),
            "the control: the blob id and encryption key are back in a row the user deleted",
        )
        assertEquals(CAPTION, harness.stringOf(CONTACT_TABLE, 41, "caption"))
        assertEquals(DELETED_AT, harness.longOf(CONTACT_TABLE, 41, "deletedAtUtc"), "and the chat still says deleted")
    }

    @Test
    fun `the unguarded save could also erase the deletion itself`() {
        val uploading = startUpload(CONTACT_TABLE, 41)
        harness.deleteForEveryone(CONTACT_TABLE, 41, DELETED_AT)

        // The same control with the instance the reconciliation never reached - it still believes the message is
        // current. `deletedAtUtc` is one of the columns a full-row save writes, so this one does not merely refill the
        // tombstone: it removes the tombstone. Worth stating, because the guard has to hold for a writer that is wrong
        // about the row, which is every writer that has been paused for the length of an upload.
        harness.legacyFullRowUpsert(CONTACT_TABLE, finishUpload(uploading))

        assertEquals(UPLOADED_BODY, harness.stringOf(CONTACT_TABLE, 41, "body"))
        assertNull(harness.longOf(CONTACT_TABLE, 41, "deletedAtUtc"), "the control: the message is simply back")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The deletion arrives second: what the handoff wrote is erased by the deletion itself.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a handoff that won the row is undone by the deletion that follows it`() {
        val uploading = startUpload(CONTACT_TABLE, 41)

        assertTrue(harness.fullRowUpdate(CONTACT_TABLE, finishUpload(uploading)), "the row was still current")
        assertEquals(UPLOADED_BODY, harness.stringOf(CONTACT_TABLE, 41, "body"))

        assertTrue(harness.deleteForEveryone(CONTACT_TABLE, 41, DELETED_AT))

        assertTombstoneIntact(CONTACT_TABLE, 41)
        assertFalse(PersistentTaskRowGate.transmits(harness.readModel(CONTACT_TABLE, 41)))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // What must NOT change: the ordinary send, the hard-deletion answer, and the tombstone the delete task announces.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `an ordinary upload still persists its credentials and stays sendable`() {
        val uploading = startUpload(CONTACT_TABLE, 41)

        assertTrue(harness.fullRowUpdate(CONTACT_TABLE, finishUpload(uploading)))

        assertEquals(UPLOADED_BODY, harness.stringOf(CONTACT_TABLE, 41, "body"))
        assertEquals(CAPTION, harness.stringOf(CONTACT_TABLE, 41, "caption"))
        assertNull(harness.longOf(CONTACT_TABLE, 41, "deletedAtUtc"))
        assertTrue(
            PersistentTaskRowGate.transmits(harness.readModel(CONTACT_TABLE, 41)),
            "the content task must still find a row it may send",
        )
    }

    @Test
    fun `a hard-deleted row answers the same way as a deleted one`() {
        val uploading = startUpload(GROUP_TABLE, 42)
        harness.hardDelete(GROUP_TABLE, 42)

        assertFalse(harness.fullRowUpdate(GROUP_TABLE, finishUpload(uploading)))
        assertEquals(0, harness.rowCount(GROUP_TABLE, 42), "and it may not come back")
    }

    @Test
    fun `the deletion-control task still finds its tombstone after a refused handoff`() {
        val uploading = startUpload(CONTACT_TABLE, 41)
        harness.deleteForEveryone(CONTACT_TABLE, 41, DELETED_AT)
        harness.fullRowUpdate(CONTACT_TABLE, finishUpload(uploading))

        val tombstone = harness.readModel(CONTACT_TABLE, 41)
        assertNotNull(tombstone, "the delete task needs this row to know what to announce")
        assertNull(tombstone.body)
        assertNull(tombstone.caption)
        assertFalse(PersistentTaskRowGate.transmits(tombstone), "while a content task must refuse the same row")
    }

    @Test
    fun `a column-scoped lifecycle write refuses the tombstone too, as it always has`() {
        harness.insertContactRow(messageId = 41, body = MEDIA_BODY, outbox = true, state = MessageState.UPLOADING)
        harness.deleteForEveryone(CONTACT_TABLE, 41, DELETED_AT)

        assertFalse(
            harness.apply(CONTACT_TABLE, 41, MessageLifecycleUpdates.saved()),
            "one rule for every writer is the point: the full-row save now answers the way this one already did",
        )
    }

    @Test
    fun `a refused handoff evicts the model rather than caching the payload`() {
        val cache = mutableListOf<AbstractMessageModel>()
        val uploading = startUpload(CONTACT_TABLE, 41)
        cache += uploading

        harness.deleteForEveryone(CONTACT_TABLE, 41, DELETED_AT)
        val finished = finishUpload(uploading)

        assertFalse(MessageCacheCoherence.admit(cache, finished, harness.fullRowUpdate(CONTACT_TABLE, finished), true))
        assertTrue(cache.isEmpty(), "a cached model still holding the blob id is a model a queued task can send")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The call sites. Source assertions, supplementing the behaviour above: neither MessageServiceImpl nor the receivers
    // can be constructed in a JVM unit test (see the class doc of MessageRowHarness).
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `every full-row save runs the deletion boundary`() {
        listOf(
            "src/main/java/ch/threema/storage/factories/MessageModelFactory.java",
            "src/main/java/ch/threema/storage/factories/GroupMessageModelFactory.java",
            "src/main/java/ch/threema/storage/factories/DistributionListMessageModelFactory.java",
        ).forEach { path ->
            val source = File(path).readText()
            val update = source.substring(source.indexOf("boolean update("))
            val clause = update.substring(0, update.indexOf("> 0;"))

            assertTrue(
                clause.contains("CONTENT_ROW_WHERE"),
                "$path must match on the deletion boundary, not on the id alone",
            )
            assertFalse(
                clause.contains("COLUMN_ID + \"=?\""),
                "$path still has an id-only full-row update",
            )
        }
        assertTrue(
            MessageRowHarness.contentRowWhere().contains("deletedAtUtc IS NULL"),
            "and the clause the tests above executed is that one",
        )
    }

    @Test
    fun `deleting for everyone aborts the send before it cleans up`() {
        val service = File(SERVICE).readText()
        val claim = service.indexOf("MessageLifecycleUpdates.deletedForEveryone(")
        val abort = service.indexOf("abortPendingSend(message);")
        val cleanup = service.indexOf("fileService.removeMessageFiles(message, true);")

        assertTrue(claim >= 0 && abort >= 0 && cleanup >= 0, "anchors must exist")
        assertTrue(claim < abort, "the row is claimed first: the claim is what authorises stopping the send")
        assertTrue(
            abort < cleanup,
            "and the send stops before the files go, or the upload it is still running rewrites what was removed",
        )
    }

    @Test
    fun `both media send machines stop when the handoff is refused`() {
        val service = File(SERVICE).readText()
        val handoffs = Regex("if \\(!getReceiver\\(\\)\\.createAndSendFileMessage\\(").findAll(service).toList()

        assertEquals(2, handoffs.size, "the first-send machine and the resend machine both gate on the result")
        handoffs.forEach { handoff ->
            val step = service.substring(handoff.range.first, service.indexOf(".next(", handoff.range.first))
            assertTrue(step.contains("sendMachine.abort();"), "a refused handoff must publish nothing after it")
        }
    }

    @Test
    fun `the receivers report the refusal instead of scheduling`() {
        listOf(
            "src/main/java/ch/threema/app/messagereceiver/ContactMessageReceiver.java",
            "src/main/java/ch/threema/app/messagereceiver/GroupMessageReceiver.java",
        ).forEach { path ->
            val source = File(path).readText()
            val signature = source.indexOf("public boolean createAndSendFileMessage(")
            assertTrue(signature >= 0, "$path must report whether it scheduled anything")

            val refusal = source.indexOf("return false;", signature)
            val schedule = source.indexOf("OutgoingFileMessageTask(", signature)
            assertTrue(refusal in (signature + 1) until schedule, "$path must refuse before it schedules")
        }
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The scenario, written once.
    // -----------------------------------------------------------------------------------------------------------------------------

    /** The row as it stands while the content blob is uploading, plus the detached model the send machine holds. */
    private fun startUpload(table: String, messageId: Int): AbstractMessageModel {
        if (table == GROUP_TABLE) {
            harness.insertGroupRow(
                messageId = messageId,
                body = MEDIA_BODY,
                caption = CAPTION,
                state = MessageState.UPLOADING,
            )
        } else {
            harness.insertContactRow(
                messageId = messageId,
                body = MEDIA_BODY,
                caption = CAPTION,
                outbox = true,
                state = MessageState.UPLOADING,
            )
        }
        return harness.requireModel(table, messageId)
    }

    /** What `createAndSendFileMessage` puts on the model before it saves: the uploaded blob id and its key. */
    private fun finishUpload(model: AbstractMessageModel): AbstractMessageModel = model.apply {
        body = UPLOADED_BODY
        state = MessageState.SENDING
        modifiedAt = Date(BASE_TIME + 90_000)
    }

    private fun assertTombstoneIntact(table: String, messageId: Int) {
        assertEquals(1, harness.rowCount(table, messageId), "the tombstone itself must survive")
        assertNull(harness.stringOf(table, messageId, "body"), "no blob id, no encryption key")
        assertNull(harness.stringOf(table, messageId, "caption"), "and no caption")
        assertEquals(DELETED_AT, harness.longOf(table, messageId, "deletedAtUtc"), "still deleted")
    }

    private companion object {
        const val SERVICE = "src/main/java/ch/threema/app/services/MessageServiceImpl.java"
        const val MEDIA_BODY = "{file-data: thumbnail only}"
        const val UPLOADED_BODY = "{file-data: blob-id + encryption-key}"
        const val CAPTION = "the wrong photo"
        const val DELETED_AT = BASE_TIME + 60_000
    }
}
