package ch.threema.app.services

import ch.threema.app.services.MessageRowHarness.Companion.BASE_TIME
import ch.threema.app.services.MessageRowHarness.Companion.CONTACT_TABLE
import ch.threema.app.services.MessageRowHarness.Companion.GROUP_TABLE
import java.io.File
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper (seventh fork review, F7-02): deletion owns the row BEFORE it touches anything the row governs.
 *
 * The failure this reproduces: both deletion forms removed the message's files first and only then deleted or emptied
 * the row. A media download finishing in that window wrote its file and won its conditional completion against a row
 * that still looked perfectly current, so it published - completion listener, blob marked done, and with "save to
 * gallery" enabled a permanent clear copy outside the message lifecycle. Deletion then removed the row and never looked
 * at the disk again: the message was gone, its media was not. Completion's own lost-race cleanup never ran, because
 * completion had WON.
 *
 * What is executed here is the ownership itself: the real deletion claim (the row delete, and the real
 * [MessageLifecycleUpdates.deletedForEveryone] statement) against a real database, raced in both orders with the real
 * [MessageLifecycleUpdates.mediaMetadata] completion, with the file store modelled as a set of uids. Exactly one side
 * wins the row in every interleaving, and whichever loses cleans up after itself.
 */
class MediaDeletionOwnershipTest {

    private val harness = MessageRowHarness(CONTACT_TABLE, GROUP_TABLE)

    /** The app's media directory: what `FileService.removeMessageFiles` deletes and the gallery export reads. */
    private val appMedia = mutableSetOf<String>()

    /** The device gallery: a permanent clear copy that nothing in the app can take back. */
    private val gallery = mutableSetOf<String>()

    private val published = mutableListOf<String>()

    @AfterTest
    fun tearDown() = harness.close()

    // -----------------------------------------------------------------------------------------------------------------------------
    // Hard deletion.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `hard deletion claiming first leaves a racing download nothing to publish`() {
        harness.insertContactRow(messageId = 41, body = "not downloaded")

        // Deletion claims the row, then removes the files it now owns.
        assertTrue(claimHardDeletion(CONTACT_TABLE, 41))
        // The download was already in flight and finishes now.
        completeDownload(CONTACT_TABLE, 41, priorBody = "not downloaded")

        assertEquals(0, harness.rowCount(CONTACT_TABLE, 41))
        assertTrue(appMedia.isEmpty(), "the media this download wrote must not outlive the message")
        assertTrue(gallery.isEmpty())
        assertTrue(published.isEmpty(), "no listener, no blob-complete, no gallery export for a row that is gone")
    }

    @Test
    fun `the legacy files-first order left the media orphaned and published it`() {
        harness.insertContactRow(messageId = 41, body = "not downloaded")

        // The control: remove the files, then - latched here, as the review latches it - let the download finish, and
        // only then delete the row.
        appMedia.remove("uid-41")
        completeDownload(CONTACT_TABLE, 41, priorBody = "not downloaded")
        harness.hardDelete(CONTACT_TABLE, 41)

        assertEquals(0, harness.rowCount(CONTACT_TABLE, 41))
        assertEquals(setOf("uid-41"), appMedia, "the media of a message that no longer exists")
        assertEquals(setOf("uid-41"), gallery, "and a permanent clear copy outside the message lifecycle")
        assertEquals(listOf("modified:uid-41", "blob-complete:uid-41"), published)
    }

    @Test
    fun `a download that wins first has its media removed by the deletion that follows`() {
        harness.insertContactRow(messageId = 41, body = "not downloaded")

        completeDownload(CONTACT_TABLE, 41, priorBody = "not downloaded")
        assertEquals(setOf("uid-41"), appMedia)

        assertTrue(claimHardDeletion(CONTACT_TABLE, 41))

        assertEquals(0, harness.rowCount(CONTACT_TABLE, 41))
        assertTrue(appMedia.isEmpty(), "the reverse order: deletion removes what completion wrote")
    }

    @Test
    fun `only one hard deletion can own the row`() {
        harness.insertContactRow(messageId = 41)

        assertTrue(claimHardDeletion(CONTACT_TABLE, 41))
        assertFalse(claimHardDeletion(CONTACT_TABLE, 41), "a second remover lost the race and owns nothing")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Delete for everyone.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `delete-for-everyone claiming first leaves a racing download nothing to publish`() {
        harness.insertGroupRow(messageId = 42, body = "not downloaded", outbox = false)

        assertTrue(claimDeleteForEveryone(GROUP_TABLE, 42, group = true))
        completeDownload(GROUP_TABLE, 42, priorBody = "not downloaded")

        assertEquals(1, harness.rowCount(GROUP_TABLE, 42), "the tombstone stays, by design")
        assertEquals(null, harness.stringOf(GROUP_TABLE, 42, "body"))
        assertEquals(BASE_TIME + 5_000, harness.longOf(GROUP_TABLE, 42, "deletedAtUtc"))
        assertTrue(appMedia.isEmpty())
        assertTrue(gallery.isEmpty())
        assertTrue(published.isEmpty())
    }

    @Test
    fun `delete-for-everyone claims the row exactly once`() {
        harness.insertContactRow(messageId = 41, body = "secret")

        assertTrue(claimDeleteForEveryone(CONTACT_TABLE, 41, group = false))
        assertFalse(
            claimDeleteForEveryone(CONTACT_TABLE, 41, group = false),
            "a second delete-for-everyone must not re-run the cleanup or move the deletion timestamp",
        )
    }

    @Test
    fun `delete-for-everyone empties the group receipt map too`() {
        harness.insertGroupRow(messageId = 42, body = "secret")
        harness.apply(
            GROUP_TABLE,
            42,
            MessageLifecycleUpdates.groupReceipt("""{"MEMBER01":"READ"}""", null),
        )

        assertTrue(claimDeleteForEveryone(GROUP_TABLE, 42, group = true))
        assertEquals(null, harness.stringOf(GROUP_TABLE, 42, "groupMessageStates"))
    }

    @Test
    fun `a download that wins first has its media removed by the delete-for-everyone that follows`() {
        harness.insertContactRow(messageId = 41, body = "not downloaded")

        completeDownload(CONTACT_TABLE, 41, priorBody = "not downloaded")
        assertEquals(setOf("uid-41"), appMedia)

        assertTrue(claimDeleteForEveryone(CONTACT_TABLE, 41, group = false))
        assertTrue(appMedia.isEmpty())
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The success control the sixth review's fix has to keep.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `an uncontested legacy image completion still stores its EXIF caption`() {
        harness.insertContactRow(messageId = 41, body = "not downloaded")

        val update = MessageLifecycleUpdates.mediaMetadata(
            "downloaded",
            "not downloaded",
            null,
            true,
            "from the exif",
            null,
        )
        assertTrue(harness.apply(CONTACT_TABLE, 41, update))
        assertEquals("from the exif", harness.stringOf(CONTACT_TABLE, 41, "caption"))
        assertEquals("downloaded", harness.stringOf(CONTACT_TABLE, 41, "body"))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The order inside the two deletion methods. Source assertions, supplementing the behaviour above: MessageServiceImpl
    // cannot be constructed in a JVM unit test (see the class doc of MessageRowHarness).
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `hard removal claims the row before it removes the files`() {
        val body = bodyOf(service(), "public void remove(final AbstractMessageModel messageModel, boolean silent) {")

        val claim = body.indexOf("MessageCacheCoherence.reconcile(cache, messageModel.getId(), null)")
        val files = body.indexOf("fileService.removeMessageFiles(")
        assertTrue(claim in 1..<files, "removing the files before the row is what leaves them orphaned")
        assertTrue(body.contains("synchronized (cache)"), "and the claim shares the monitor completion writes under")
        assertTrue(body.contains("cancelMessageDownload(messageModel)"), "an active download must be cancelled")
    }

    @Test
    fun `delete-for-everyone marks the row before it removes anything`() {
        val body = bodyOf(service(), "public boolean deleteMessageContentsAndRelatedData(")

        val claim = body.indexOf("MessageLifecycleUpdates.deletedForEveryone(")
        val files = body.indexOf("fileService.removeMessageFiles(")
        val history = body.indexOf("editHistoryRepository.deleteByMessageUid(")
        assertTrue(claim in 1..<files)
        assertTrue(claim < history)
        assertTrue(body.contains("cancelMessageDownload(message)"))
    }

    @Test
    fun `the gallery export asks whether the row is still there`() {
        val source = service()
        assertTrue(bodyOf(source, "private void saveImagesAndVideosToGalleryIfEnabled(").contains("ownsCurrentRow("))
        assertEquals(
            3,
            Regex("&& ownsCurrentRow\\(").findAll(source).count(),
            "the common media path and both legacy image handlers",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The two shipped sequences, and the download they race.
    // -----------------------------------------------------------------------------------------------------------------------------

    /** `MessageServiceImpl.remove`: claim the row, then remove what it governed. */
    private fun claimHardDeletion(table: String, messageId: Int): Boolean {
        val uid = "uid-$messageId"
        val claimed = harness.claimHardDelete(table, messageId)
        // The files go whether or not this caller won: a loser is cleaning up after a race, and refusing would leave
        // exactly the orphan this closes.
        appMedia.remove(uid)
        return claimed
    }

    /** `MessageServiceImpl.deleteMessageContentsAndRelatedData`: mark the row, then remove what it governed. */
    private fun claimDeleteForEveryone(table: String, messageId: Int, group: Boolean): Boolean {
        val claimed = harness.apply(
            table,
            messageId,
            MessageLifecycleUpdates.deletedForEveryone(Date(BASE_TIME + 5_000), group),
        )
        if (claimed) {
            appMedia.remove("uid-$messageId")
        }
        return claimed
    }

    /** `MessageServiceImpl.setDownloadCompleted` and its caller: write the media, then try to own the row. */
    private fun completeDownload(table: String, messageId: Int, priorBody: String) {
        val uid = "uid-$messageId"
        appMedia += uid
        val won = harness.apply(
            table,
            messageId,
            MessageLifecycleUpdates.mediaMetadata("downloaded", priorBody, null, false, null, null),
        )
        if (!won) {
            // Lost the row: clean up only what this attempt wrote, and publish nothing.
            appMedia -= uid
            return
        }
        published += "modified:$uid"
        published += "blob-complete:$uid"
        if (harness.readModel(table, messageId)?.deletedAt == null && harness.rowCount(table, messageId) > 0) {
            gallery += uid
        }
    }

    private fun service() = File("src/main/java/ch/threema/app/services/MessageServiceImpl.java").readText()

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
