package ch.threema.app.services

import ch.threema.app.services.MessageRowHarness.Companion.BASE_TIME
import ch.threema.app.services.MessageRowHarness.Companion.CONTACT_TABLE
import ch.threema.app.services.MessageRowHarness.Companion.GROUP_TABLE
import ch.threema.app.tasks.PersistentTaskRowGate
import ch.threema.storage.MessageCacheCoherence
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageState
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (seventh fork review, F7-01): a failed save may neither insert, nor cache, nor feed a send.
 *
 * The failure this reproduces: the sixth review made `createOrUpdate` refuse to reinsert a row that had gone, and the
 * service threw that answer away - it cached the supplied model regardless. The service's id getters read the cache
 * before the database and the persistent send tasks load their message by local id, so a media message the user
 * hard-deleted after its blob had been uploaded, but before the send machine's trailing save, was handed back to its
 * already-archived task complete with body, blob id and encryption key, and sent. Nothing in the database or the UI
 * could show it.
 *
 * Three shipped decisions carry the fix and all three are executed here against a real database and real models:
 *
 * - the full-row write for a positive id is an UPDATE ONLY, so the existence decision is the write and it reports
 *   whether a row was touched ([MessageRowHarness.fullRowUpdate], versus [MessageRowHarness.legacyFullRowUpsert]);
 * - [MessageCacheCoherence.admit] evicts on a refused save instead of admitting;
 * - [PersistentTaskRowGate] refuses to let a content task transmit from a row that is gone or deleted, while leaving
 *   the deletion-control tombstone loadable.
 */
class DeletedMessageCacheAndSendTest {

    private val harness = MessageRowHarness(CONTACT_TABLE, GROUP_TABLE)
    private val cache = mutableListOf<AbstractMessageModel>()

    @AfterTest
    fun tearDown() = harness.close()

    // -----------------------------------------------------------------------------------------------------------------------------
    // The persistence result is real.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a positive-id save of a hard-deleted row writes nothing and reports failure`() {
        harness.insertContactRow(messageId = 41, body = "blob-id + key", outbox = true, state = MessageState.SENDING)
        val model = harness.requireModel(CONTACT_TABLE, 41)

        harness.hardDelete(CONTACT_TABLE, 41)

        assertFalse(harness.fullRowUpdate(CONTACT_TABLE, model), "the row is gone, so nothing was written")
        assertEquals(0, harness.rowCount(CONTACT_TABLE, 41))
    }

    @Test
    fun `the legacy upsert put the deleted row back`() {
        harness.insertContactRow(messageId = 41, body = "blob-id + key", outbox = true, state = MessageState.SENDING)
        val model = harness.requireModel(CONTACT_TABLE, 41)

        harness.hardDelete(CONTACT_TABLE, 41)
        harness.legacyFullRowUpsert(CONTACT_TABLE, model)

        assertEquals(1, harness.rowCount(CONTACT_TABLE, 41), "the control: the deleted message came back")
        assertEquals("blob-id + key", harness.stringOf(CONTACT_TABLE, 41, "body"))
    }

    @Test
    fun `a save of a row that is still there succeeds`() {
        harness.insertContactRow(messageId = 41, body = "hello", outbox = true, state = MessageState.SENDING)
        val model = harness.requireModel(CONTACT_TABLE, 41)
        model.body = "hello, edited"

        assertTrue(harness.fullRowUpdate(CONTACT_TABLE, model))
        assertEquals("hello, edited", harness.stringOf(CONTACT_TABLE, 41, "body"))
    }

    @Test
    fun `a group save reports the same way`() {
        harness.insertGroupRow(messageId = 42, body = "media", state = MessageState.SENDING)
        val model = harness.requireModel(GROUP_TABLE, 42)

        assertTrue(harness.fullRowUpdate(GROUP_TABLE, model))
        harness.hardDelete(GROUP_TABLE, 42)
        assertFalse(harness.fullRowUpdate(GROUP_TABLE, model))
        assertEquals(0, harness.rowCount(GROUP_TABLE, 42))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // A refused save evicts; it never admits.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a refused save admits nothing and evicts every cached instance`() {
        harness.insertContactRow(messageId = 41, body = "blob-id + key", outbox = true, state = MessageState.SENDING)
        val fromCreation = harness.requireModel(CONTACT_TABLE, 41)
        val fromTimeline = harness.requireModel(CONTACT_TABLE, 41)
        cache += fromTimeline

        harness.hardDelete(CONTACT_TABLE, 41)
        val persisted = harness.fullRowUpdate(CONTACT_TABLE, fromCreation)

        assertFalse(MessageCacheCoherence.admit(cache, fromCreation, persisted, true))
        assertTrue(cache.isEmpty(), "a deleted payload left in the cache is a payload a queued task can still send")
    }

    @Test
    fun `the legacy admission left the deleted payload where the send task looks for it`() {
        harness.insertContactRow(messageId = 41, body = "blob-id + key", outbox = true, state = MessageState.SENDING)
        val fromCreation = harness.requireModel(CONTACT_TABLE, 41)

        harness.hardDelete(CONTACT_TABLE, 41)
        // The control: cache unconditionally, as the service did before this wave.
        cache += fromCreation

        assertTrue(MessageCacheCoherence.holds(cache, 41))
        assertEquals("blob-id + key", cache.single().body)
    }

    @Test
    fun `a successful save admits the model once and refreshes the other instances`() {
        harness.insertContactRow(messageId = 41, body = "hello", outbox = true, state = MessageState.SENDING)
        val stale = harness.requireModel(CONTACT_TABLE, 41)
        cache += stale

        val saved = harness.requireModel(CONTACT_TABLE, 41)
        saved.state = MessageState.SENT
        saved.expireStartedAt = BASE_TIME
        saved.expiresAt = BASE_TIME + 30_000

        assertTrue(MessageCacheCoherence.admit(cache, saved, harness.fullRowUpdate(CONTACT_TABLE, saved), true))
        assertEquals(2, cache.size)
        assertEquals(MessageState.SENT, stale.state, "the stale instance adopts what was written")
        assertEquals(BASE_TIME + 30_000, stale.expiresAt)

        // Saving the same instance again must not grow the cache.
        assertTrue(MessageCacheCoherence.admit(cache, saved, harness.fullRowUpdate(CONTACT_TABLE, saved), true))
        assertEquals(2, cache.size)
    }

    @Test
    fun `a distribution-list model is persisted but never admitted`() {
        harness.insertContactRow(messageId = 41, outbox = true)
        val model = harness.requireModel(CONTACT_TABLE, 41)

        assertTrue(MessageCacheCoherence.admit(cache, model, true, false))
        assertTrue(cache.isEmpty())
    }

    @Test
    fun `a brand new model is admitted`() {
        harness.insertContactRow(messageId = 41, outbox = true)
        val model = harness.requireModel(CONTACT_TABLE, 41)

        assertTrue(MessageCacheCoherence.admit(cache, model, true, true))
        assertEquals(listOf<AbstractMessageModel>(model), cache)
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // A content task transmits only from a current, undeleted row.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a content task will not transmit from a hard-deleted row`() {
        harness.insertContactRow(messageId = 41, body = "blob-id + key", outbox = true, state = MessageState.SENDING)
        harness.hardDelete(CONTACT_TABLE, 41)

        assertNull(harness.readModel(CONTACT_TABLE, 41))
        assertFalse(PersistentTaskRowGate.transmits(harness.readModel(CONTACT_TABLE, 41)))
    }

    @Test
    fun `a content task will not transmit from a row deleted for everyone`() {
        harness.insertGroupRow(messageId = 42, body = "media", state = MessageState.SENDING)
        harness.deleteForEveryone(GROUP_TABLE, 42, BASE_TIME + 5_000)

        assertFalse(PersistentTaskRowGate.transmits(harness.readModel(GROUP_TABLE, 42)))
    }

    @Test
    fun `a content task transmits from a current row`() {
        harness.insertContactRow(messageId = 41, body = "blob-id + key", outbox = true, state = MessageState.SENDING)

        assertTrue(PersistentTaskRowGate.transmits(harness.readModel(CONTACT_TABLE, 41)))
    }

    @Test
    fun `the deletion-control tombstone stays loadable for its own task`() {
        harness.insertContactRow(messageId = 41, body = "secret", outbox = true, state = MessageState.SENT)
        harness.deleteForEveryone(CONTACT_TABLE, 41, BASE_TIME + 5_000)

        val tombstone = harness.readModel(CONTACT_TABLE, 41)
        assertEquals(1, harness.rowCount(CONTACT_TABLE, 41), "the delete task needs this row to know what to announce")
        assertNull(tombstone!!.body, "and it carries no content")
        assertFalse(
            PersistentTaskRowGate.transmits(tombstone),
            "while a CONTENT task must refuse the very same row",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The call sites honour the result. Source assertions, supplementing the behaviour above: MessageServiceImpl and the
    // receivers cannot be constructed in a JVM unit test (see the class doc of MessageRowHarness).
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the factories write update-only for a positive id`() {
        listOf(
            "src/main/java/ch/threema/storage/factories/MessageModelFactory.java",
            "src/main/java/ch/threema/storage/factories/GroupMessageModelFactory.java",
            "src/main/java/ch/threema/storage/factories/DistributionListMessageModelFactory.java",
        ).forEach { path ->
            val source = File(path).readText()
            val body = bodyOf(source, "public boolean createOrUpdate(")
            assertFalse(
                body.contains("getReadableDatabase().query("),
                "$path still asks whether the row exists before writing it, which is the window the review names",
            )
            assertTrue(body.contains("refusesReinsertion("), "$path must still refuse to insert a positive id")
            assertTrue(
                body.contains("return false;"),
                "$path must report the refusal rather than returning a constant true",
            )
        }
    }

    @Test
    fun `the service caches only what persisted`() {
        val service = File("src/main/java/ch/threema/app/services/MessageServiceImpl.java").readText()
        val body = bodyOf(service, "public boolean save(")

        assertTrue(body.contains("MessageCacheCoherence.admit("), "the admission rule is the one executed above")
        assertFalse(
            body.contains("cache(messageModel);"),
            "the unconditional cache() call is the defect: it admitted the model whatever persistence said",
        )
    }

    @Test
    fun `the schedulers refuse to queue a task for a row that is gone`() {
        listOf(
            "src/main/java/ch/threema/app/messagereceiver/ContactMessageReceiver.java",
            "src/main/java/ch/threema/app/messagereceiver/GroupMessageReceiver.java",
        ).forEach { path ->
            val source = File(path).readText()
            assertTrue(
                source.contains("if (!saveLocalModel(messageModel)) {"),
                "$path must not schedule a persistent send after a refused save",
            )
            assertEquals(
                4,
                Regex("if \\(!saveLocalModel\\(messageModel\\)\\) \\{").findAll(source).count(),
                "$path has four content schedulers: text, location, file and poll setup",
            )
        }
    }

    @Test
    fun `every content task loads its payload from the row and no deletion-control task does`() {
        listOf(
            "OutgoingTextMessageTask",
            "OutgoingLocationMessageTask",
            "OutgoingFileMessageTask",
            "OutgoingPollSetupMessageTask",
        ).forEach { task ->
            val source = File("src/main/java/ch/threema/app/tasks/$task.kt").readText()
            assertTrue(source.contains("getContactContentRow(messageModelId)"), "$task (contact)")
            assertTrue(source.contains("getGroupContentRow(messageModelId)"), "$task (group)")
        }
        listOf("OutgoingContactDeleteMessageTask", "OutgoingGroupDeleteMessageTask").forEach { task ->
            val source = File("src/main/java/ch/threema/app/tasks/$task.kt").readText()
            assertFalse(
                source.contains("ContentRow("),
                "$task exists to announce a deletion; the tombstone it loads is exactly the row the gate rejects",
            )
        }
    }

    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "signature not found: $signature")
        var depth = 0
        var index = source.indexOf('{', start)
        val from = index
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(from, index + 1)
            }
            index++
        }
        error("unbalanced body for $signature")
    }
}
