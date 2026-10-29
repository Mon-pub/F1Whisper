package ch.threema.app.fragments.composemessage

import android.net.Uri
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.group.GroupMessageModel
import io.mockk.mockk
import java.io.File
import java.util.function.Consumer
import java.util.function.Predicate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * F1Whisper (twelfth fork review, F12-04): executable spec of [PendingMediaShare], the in-flight share that dies -
 * decrypted copies deleted - when a member expires or the operation is abandoned, and that never deletes after a
 * successful chooser handoff.
 *
 * The expiry authority is the injected predicate (production hands in `DisappearingMessageService::enforceIfExpired`);
 * "crossing the deadline mid-flight" is therefore executed literally: a member is marked expired AFTER the share was
 * built, then revalidation runs. Temp deletion is executed against real files on disk; URI-only deletion against the
 * recorded deleter seam (production: `ContentResolver.delete` on the FileProvider URI).
 */
class PendingMediaShareTest {

    private val expired = mutableSetOf<MessageModel>()
    private val expiryCheck = Predicate<ch.threema.storage.models.AbstractMessageModel> { expired.contains(it) }

    private val deletedUris = mutableListOf<Uri>()
    private val uriDeleter = Consumer<Uri> { deletedUris.add(it) }

    private val tempFiles = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempFiles.forEach { it.delete() }
    }

    private fun model(id: Int, uid: String? = "uid-$id"): MessageModel =
        MessageModel().apply {
            this.id = id
            this.uid = uid
            type = MessageType.FILE
        }

    private fun tempFile(): File = File.createTempFile("f12share", ".tmp").also { tempFiles.add(it) }

    private fun member(model: MessageModel, uri: Uri? = mockk<Uri>(), file: File? = null): PendingMediaShare.Member =
        PendingMediaShare.Member(model, uri, file)

    private fun shareOf(vararg members: PendingMediaShare.Member): PendingMediaShare =
        PendingMediaShare(members.toList(), expiryCheck, uriDeleter)

    // -----------------------------------------------------------------------------------------------------------------
    // 1. Revalidation: expired members are dropped and their decrypted copies deleted; the rest still exports
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a healthy share exports every member untouched`() {
        val fileA = tempFile()
        val uriA = mockk<Uri>()
        val uriB = mockk<Uri>()
        val modelA = model(1)
        val modelB = model(2)
        val share = shareOf(member(modelA, uriA, fileA), member(modelB, uriB))

        assertTrue(share.revalidateForExport())

        assertEquals(listOf<Any>(modelA, modelB), share.models())
        assertEquals<List<Uri?>>(listOf(uriA, uriB), share.uris())
        assertTrue(fileA.exists(), "no live member's decrypted copy may be touched")
        assertTrue(deletedUris.isEmpty())
    }

    @Test
    fun `a member that expires mid-flight is dropped and its decrypted copy deleted`() {
        val expiredFile = tempFile()
        val liveFile = tempFile()
        val expiredModel = model(1)
        val liveModel = model(2)
        val liveUri = mockk<Uri>()
        val share = shareOf(
            member(expiredModel, mockk<Uri>(), expiredFile),
            member(liveModel, liveUri, liveFile),
        )

        // The deadline passes AFTER the share was built - the review's mid-flight scenario.
        expired.add(expiredModel)

        assertTrue(share.revalidateForExport(), "the surviving member still shares")
        assertEquals(listOf<Any>(liveModel), share.models())
        assertEquals<List<Uri?>>(listOf(liveUri), share.uris())
        assertFalse(expiredFile.exists(), "the expired member's decrypted copy must be deleted")
        assertTrue(liveFile.exists(), "the survivor's copy is what the chooser will read")
    }

    @Test
    fun `an expired member with only a URI is deleted through the resolver`() {
        val expiredModel = model(1)
        val expiredUri = mockk<Uri>()
        val share = shareOf(member(expiredModel, expiredUri), member(model(2)))

        expired.add(expiredModel)

        assertTrue(share.revalidateForExport())
        assertEquals(listOf(expiredUri), deletedUris, "the multi path only knows the URI; deletion goes through it")
    }

    @Test
    fun `a share with nothing left to export refuses the export, copies already gone`() {
        val fileA = tempFile()
        val modelA = model(1)
        val modelB = model(2)
        val uriB = mockk<Uri>()
        val share = shareOf(member(modelA, mockk<Uri>(), fileA), member(modelB, uriB))

        expired.add(modelA)
        expired.add(modelB)

        assertFalse(share.revalidateForExport(), "everything expired - nothing may leave the device")
        assertFalse(fileA.exists())
        assertEquals(listOf(uriB), deletedUris)
        assertTrue(share.isEmpty())
    }

    @Test
    fun `a null placeholder URI survives a healthy revalidation positionally, as delivered`() {
        // The decrypt callback delivers one URI per model with null placeholders for failures; the share intent
        // consumed exactly that shape before this fix and must keep receiving it for live members.
        val modelA = model(1)
        val modelB = model(2)
        val uriB = mockk<Uri>()
        val share = shareOf(member(modelA, null), member(modelB, uriB))

        assertTrue(share.revalidateForExport())
        assertEquals(listOf<Any>(modelA, modelB), share.models())
        assertEquals(listOf(null, uriB), share.uris())
    }

    @Test
    fun `a share whose only URIs are null placeholders is not exportable`() {
        val share = shareOf(member(model(1), null), member(model(2), null))

        assertFalse(share.revalidateForExport())
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 2. Cancellation deletes everything; a successful handoff never deletes
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `cancel deletes every decrypted copy`() {
        val fileA = tempFile()
        val uriOnly = mockk<Uri>()
        val share = shareOf(member(model(1), mockk<Uri>(), fileA), member(model(2), uriOnly))

        share.cancelAndCleanup()

        assertFalse(fileA.exists(), "an abandoned share leaves no decrypted copy behind")
        assertEquals(listOf(uriOnly), deletedUris)
        assertTrue(share.isEmpty())
    }

    @Test
    fun `a successful handoff never deletes - the receiving app still reads the URIs`() {
        val fileA = tempFile()
        val share = shareOf(member(model(1), mockk<Uri>(), fileA))

        share.discardAfterHandoff()
        // A later cancellation (dialog teardown, onDestroy paths) must find nothing left to delete.
        share.cancelAndCleanup()

        assertTrue(fileA.exists(), "deleting after the chooser handoff would break the receiving app's read")
        assertTrue(deletedUris.isEmpty())
        assertTrue(share.isEmpty())
    }

    @Test
    fun `an expired member with a known temp file is deleted via the file, not the resolver`() {
        val expiredFile = tempFile()
        val expiredModel = model(1)
        val share = shareOf(member(expiredModel, mockk<Uri>(), expiredFile))

        expired.add(expiredModel)
        share.revalidateForExport()

        assertFalse(expiredFile.exists())
        assertTrue(deletedUris.isEmpty(), "the file is authoritative where known; the URI points at the same bytes")
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 3. Identity: contains/isSameMessage match the persisted message, never the instance
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `contains matches a fresh instance of the same row by uid`() {
        val inShare = model(7, uid = "stable-uid")
        val share = shareOf(member(inShare))
        val freshInstanceOfSameRow = model(999, uid = "stable-uid")

        assertTrue(share.contains(freshInstanceOfSameRow), "listener callbacks deliver fresh instances of the row")
        assertFalse(share.contains(model(7, uid = "other-uid")), "a different message is never matched")
    }

    @Test
    fun `identity does not cross the table namespaces even when the integer ids collide`() {
        // The F12-01 lesson, applied here: contact and group rows live in separate tables with separate
        // AUTOINCREMENT sequences, so equal integer ids across them are normal and must never match.
        val contact = MessageModel().apply {
            id = 47
            uid = null
        }
        val group = GroupMessageModel().apply {
            id = 47
            uid = null
        }

        assertFalse(PendingMediaShare.isSameMessage(contact, group))
        assertTrue(PendingMediaShare.isSameMessage(contact, MessageModel().apply { id = 47 }))
        assertFalse(
            PendingMediaShare.isSameMessage(MessageModel(), MessageModel()),
            "two unsaved uid-less models (id 0) are not the same persisted message",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 4. Positional construction from the decrypt callback's parallel lists
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `of() zips models and URIs positionally and keeps only the aligned prefix on mismatch`() {
        val modelA = model(1)
        val modelB = model(2)
        val uriA = mockk<Uri>()

        val share = PendingMediaShare.of(listOf(modelA, modelB), listOf(uriA), expiryCheck, uriDeleter)

        assertTrue(share.revalidateForExport())
        assertEquals(listOf<Any>(modelA), share.models(), "a misaligned pair must never be exported")
        assertSame(uriA, share.uris().single())
    }
}
