package ch.threema.app.services

import ch.threema.app.services.MessageRowHarness.Companion.BASE_TIME
import ch.threema.app.services.MessageRowHarness.Companion.CONTACT_TABLE
import ch.threema.app.services.MessageRowHarness.Companion.GROUP_TABLE
import ch.threema.app.tasks.PersistentTaskRowGate
import ch.threema.domain.protocol.csp.messages.file.FileData
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.data.media.FileDataModel
import java.io.File
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (seventh fork review, F7-03): an edit's history entry and its row change commit together, and no edit
 * plaintext survives a deletion - not on the wire, and not at rest.
 *
 * Two independent failures were reproduced by the review:
 *
 * 1. The history entry, which stores the message's OLD plaintext, was inserted by a statement of its own BEFORE the row
 *    write was even attempted. Delete-for-everyone landing in between emptied the row and deleted all of its history;
 *    the insert then put a fresh copy of the old plaintext back, and the row write correctly refused, so nothing rolled
 *    it back. The message showed as deleted while the text deletion was meant to destroy was readable from the history
 *    sheet.
 * 2. The outgoing edit task was archived, carrying the NEW plaintext, before the local edit was tried at all. So the
 *    plaintext sat in the task archive for as long as the device was offline, and when the task finally ran it loaded
 *    the soft-deleted parent row - loadable by design - and transmitted the new text to the peer and to the user's
 *    other devices, after the message had already been deleted.
 *
 * The history entry is modelled here as a row in a table because `EditHistoryDao` is Android-bound; what is executed is
 * the transaction boundary that governs it (rolled back with the edit or committed with it) and the real
 * [MessageLifecycleUpdates.edit] and [MessageLifecycleUpdates.deletedForEveryone] statements that decide the race. The
 * task side executes the real [PersistentTaskRowGate.committedEdit].
 */
class EditHistoryDeletionRaceTest {

    private val harness = MessageRowHarness(CONTACT_TABLE, GROUP_TABLE)

    /** The edit history: message uid to the old plaintexts recorded for it. */
    private val history = mutableMapOf<String, MutableList<String?>>()

    /** What the in-memory history cache is showing the user. */
    private val historyCache = mutableMapOf<String, MutableList<String?>>()

    private val editedAt = Date(BASE_TIME + 60_000)

    @AfterTest
    fun tearDown() = harness.close()

    // -----------------------------------------------------------------------------------------------------------------------------
    // The history entry and the row commit together.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `an edit losing the row to delete-for-everyone leaves no history plaintext`() {
        harness.insertContactRow(messageId = 41, body = "the original secret", outbox = true)

        // The reflected deletion from another linked device lands while the edit is deciding.
        assertTrue(deleteForEveryone(CONTACT_TABLE, 41, group = false))
        assertTrue(history["uid-41"].isNullOrEmpty())

        assertFalse(commitEdit(CONTACT_TABLE, 41, "the new secret"))

        assertNull(harness.stringOf(CONTACT_TABLE, 41, "body"))
        assertTrue(history["uid-41"].isNullOrEmpty(), "the insert rolled back with the edit that refused")
        assertTrue(historyCache["uid-41"].isNullOrEmpty(), "and nothing was published to the sheet")
    }

    @Test
    fun `the legacy order left the old plaintext behind`() {
        harness.insertContactRow(messageId = 41, body = "the original secret", outbox = true)

        // The control: insert the history entry first, from the caller's pre-deletion instance, as the old code did.
        val stale = harness.requireModel(CONTACT_TABLE, 41)
        assertTrue(deleteForEveryone(CONTACT_TABLE, 41, group = false))
        insertHistory(stale.uid!!, stale.body)
        // The row write then refuses, and nothing removes what was just inserted.
        assertFalse(
            harness.apply(CONTACT_TABLE, 41, MessageLifecycleUpdates.edit("the new secret", null, editedAt, null)),
        )

        assertNull(harness.stringOf(CONTACT_TABLE, 41, "body"), "the message reads as deleted")
        assertEquals<List<String?>?>(
            listOf("the original secret"),
            history["uid-41"],
            "while the text the deletion was supposed to destroy is back in the history",
        )
    }

    @Test
    fun `an edit losing the row to a hard deletion leaves no history plaintext`() {
        harness.insertGroupRow(messageId = 42, body = "the original secret")
        harness.hardDelete(GROUP_TABLE, 42)

        assertFalse(commitEdit(GROUP_TABLE, 42, "the new secret"))
        assertTrue(history["uid-42"].isNullOrEmpty())
    }

    @Test
    fun `a committed edit records exactly one history entry and publishes it`() {
        harness.insertContactRow(messageId = 41, body = "the original secret", outbox = true)

        assertTrue(commitEdit(CONTACT_TABLE, 41, "the new secret"))

        assertEquals("the new secret", harness.stringOf(CONTACT_TABLE, 41, "body"))
        assertEquals(editedAt.time, harness.longOf(CONTACT_TABLE, 41, "editedAtUtc"))
        assertEquals<List<String?>?>(listOf("the original secret"), history["uid-41"])
        assertEquals<List<String?>?>(listOf("the original secret"), historyCache["uid-41"], "published after the commit")
    }

    @Test
    fun `a deletion after a committed edit removes the history it created`() {
        harness.insertContactRow(messageId = 41, body = "the original secret", outbox = true)

        assertTrue(commitEdit(CONTACT_TABLE, 41, "the new secret"))
        assertTrue(deleteForEveryone(CONTACT_TABLE, 41, group = false))

        assertNull(harness.stringOf(CONTACT_TABLE, 41, "body"))
        assertTrue(history["uid-41"].isNullOrEmpty(), "the reverse order must also end with no plaintext anywhere")
    }

    @Test
    fun `an edit superseded by another writer leaves no orphan history entry`() {
        harness.insertContactRow(messageId = 41, body = "the original secret", outbox = true)
        val stale = harness.requireModel(CONTACT_TABLE, 41)

        // Something else wins the body first - a media download recording its arrival, another edit.
        assertTrue(
            harness.apply(
                CONTACT_TABLE,
                41,
                MessageLifecycleUpdates.edit("someone else's text", null, editedAt, "the original secret"),
            ),
        )

        // This edit decided from `stale`, so its compare-and-set fails and its history insert rolls back with it.
        assertFalse(commitEditAgainst(CONTACT_TABLE, 41, "the new secret", priorBody = stale.body))
        assertTrue(history["uid-41"].isNullOrEmpty())
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The queued edit announces the row, or nothing.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a queued edit announces nothing after delete-for-everyone`() {
        harness.insertContactRow(messageId = 41, body = "the original secret", outbox = true)
        assertTrue(commitEdit(CONTACT_TABLE, 41, "the new secret"))

        // The device is offline; the deletion arrives before the task ever runs.
        assertTrue(deleteForEveryone(CONTACT_TABLE, 41, group = false))

        assertNull(PersistentTaskRowGate.committedEdit(harness.readModel(CONTACT_TABLE, 41), editedAt))
    }

    @Test
    fun `a queued edit announces nothing after a hard deletion`() {
        harness.insertGroupRow(messageId = 42, body = "the original secret")
        assertTrue(commitEdit(GROUP_TABLE, 42, "the new secret"))
        harness.hardDelete(GROUP_TABLE, 42)

        assertNull(PersistentTaskRowGate.committedEdit(harness.readModel(GROUP_TABLE, 42), editedAt))
    }

    @Test
    fun `a queued edit announces exactly what committed`() {
        harness.insertContactRow(messageId = 41, body = "the original secret", outbox = true)
        assertTrue(commitEdit(CONTACT_TABLE, 41, "the new secret"))

        assertEquals(
            "the new secret",
            PersistentTaskRowGate.committedEdit(harness.readModel(CONTACT_TABLE, 41), editedAt),
        )
    }

    @Test
    fun `a queued edit superseded by a later edit announces nothing`() {
        harness.insertContactRow(messageId = 41, body = "the original secret", outbox = true)
        assertTrue(commitEdit(CONTACT_TABLE, 41, "the new secret"))

        val later = Date(BASE_TIME + 120_000)
        assertTrue(
            harness.apply(
                CONTACT_TABLE,
                41,
                MessageLifecycleUpdates.edit("the newest secret", null, later, "the new secret"),
            ),
        )

        assertNull(
            PersistentTaskRowGate.committedEdit(harness.readModel(CONTACT_TABLE, 41), editedAt),
            "the surviving text is announced by the task that committed it, not by this one",
        )
        assertEquals(
            "the newest secret",
            PersistentTaskRowGate.committedEdit(harness.readModel(CONTACT_TABLE, 41), later),
        )
    }

    @Test
    fun `a file message's queued edit announces its caption, not its body`() {
        // The caption of a FILE lives inside the serialised body, which this fork's edit path rewrites; a JVM test
        // cannot serialise one (android.util.JsonWriter), so the parsed form is supplied directly. What is under test
        // is which field the gate reads for which type.
        val model = MessageModel().apply {
            id = 41
            type = MessageType.FILE
            body = "{opaque file json}"
            editedAt = this@EditHistoryDeletionRaceTest.editedAt
            fileData = FileDataModel(
                "image/jpeg",
                "image/jpeg",
                1L,
                "photo.jpg",
                FileData.RENDERING_MEDIA,
                "the new caption",
                true,
                emptyMap(),
            )
        }

        assertEquals("the new caption", PersistentTaskRowGate.committedEdit(model, editedAt))
        assertNull(
            PersistentTaskRowGate.committedEdit(model, Date(BASE_TIME + 120_000)),
            "and only for the edit that committed",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The archive stores no plaintext at all, and the order is commit-then-announce. Source assertions, supplementing the
    // behaviour above: MessageServiceImpl cannot be constructed in a JVM unit test.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `neither edit task serialises the edited text`() {
        listOf("OutgoingContactEditMessageTask", "OutgoingGroupEditMessageTask").forEach { task ->
            val source = File("src/main/java/ch/threema/app/tasks/$task.kt").readText()
            assertFalse(
                source.contains("private val editedText"),
                "$task must not hold the plaintext: an archived task is plaintext at rest while the device is offline",
            )
            assertFalse(source.contains("editedText = editedText"))
            assertTrue(source.contains("PersistentTaskRowGate.committedEdit("), "$task must read the committed row")
        }
    }

    @Test
    fun `the edit commits before it is announced`() {
        val service = File("src/main/java/ch/threema/app/services/MessageServiceImpl.java").readText()

        // Both anchors are unique in the file, so the ordering is read from the file directly rather than from a
        // brace-matched body: sendEditedMessageText contains the log format "messageId={}}", whose unbalanced brace
        // truncates any naive body extraction.
        val commit = service.indexOf("if (!saveEditedMessageText(message, trimmedNewText, editedAt))")
        val announce = service.indexOf("sendEditMessage(message.getId(), editedAt)")
        assertTrue(commit >= 0, "the local commit must happen in sendEditedMessageText")
        assertTrue(announce > commit, "archiving the task before the local write is the failure itself")
        assertTrue(
            service.contains("Not announcing the edit of {}: it did not commit locally"),
            "and a refused commit must announce nothing",
        )
    }

    @Test
    fun `the history entry and the row write share one transaction`() {
        val service = File("src/main/java/ch/threema/app/services/MessageServiceImpl.java").readText()
        val body = bodyOf(service, "private EditCommit commitEditDurably(")

        assertTrue(body.contains("databaseService.inTransaction("))
        assertTrue(body.contains("createEntryDeferred(current)"), "the entry comes from the RELOADED row")
        assertTrue(body.contains("throw new EditSupersededException()"), "a refused row write must roll the entry back")
        assertFalse(body.contains("publishEntry("), "the cache is updated after the commit, not inside it")
        assertTrue(
            bodyOf(service, "public boolean saveEditedMessageText(").contains("editHistoryRepository.publishEntry("),
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The shipped sequences.
    // -----------------------------------------------------------------------------------------------------------------------------

    /** `MessageServiceImpl.commitEditDurably`: reload, insert the history entry, write the row, all or nothing. */
    private fun commitEdit(table: String, messageId: Int, text: String): Boolean {
        val current = harness.readModel(table, messageId) ?: return false
        return commitEditAgainst(table, messageId, text, priorBody = current.body)
    }

    private fun commitEditAgainst(table: String, messageId: Int, text: String, priorBody: String?): Boolean {
        val current = harness.readModel(table, messageId) ?: return false
        if (current.deletedAt != null) {
            return false
        }
        // Inside the transaction: the entry first, from the pre-edit row.
        val uid = current.uid!!
        insertHistory(uid, current.body)
        val written = harness.apply(
            table,
            messageId,
            MessageLifecycleUpdates.edit(text, null, editedAt, priorBody),
        )
        if (!written) {
            // Rollback.
            history[uid]?.removeLastOrNull()
            return false
        }
        // Committed: only now is the entry published to the in-memory cache.
        historyCache.getOrPut(uid) { mutableListOf() } += current.body
        return true
    }

    /** `MessageServiceImpl.deleteMessageContentsAndRelatedData`: claim the row, then delete the history. */
    private fun deleteForEveryone(table: String, messageId: Int, group: Boolean): Boolean {
        val uid = "uid-$messageId"
        val claimed = harness.apply(
            table,
            messageId,
            MessageLifecycleUpdates.deletedForEveryone(Date(BASE_TIME + 90_000), group),
        )
        if (claimed) {
            history.remove(uid)
            historyCache.remove(uid)
        }
        return claimed
    }

    private fun insertHistory(uid: String, oldText: String?) {
        history.getOrPut(uid) { mutableListOf() } += oldText
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
