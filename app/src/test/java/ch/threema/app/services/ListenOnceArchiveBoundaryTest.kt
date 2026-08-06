package ch.threema.app.services

import ch.threema.app.utils.OutputRestrictionPolicy
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper (fifth fork review, F5-01): the two stock output boundaries F4-09 did not reach.
 *
 * **Linked Web.** `BlobRequestHandler` resolved the message and handed it to a `WebClientMessagePlayer`, which calls only
 * the ordinary `markAsConsumed` and never writes listen-once claimed/consumed metadata or burns the source. So an
 * unmodified recipient could request an unplayed incoming listen-once voice message from their paired browser, receive
 * the complete decrypted file, request it again, and still play the untouched message on the phone afterwards.
 *
 * **Data backup.** The leak was double. The media file went into the archive through `getDecryptedMessageStream`, and the
 * CSV preserved the message body verbatim - which for a FILE message is the serialised file-data metadata, including the
 * blob id, the blob encryption key and an unclaimed `lo=true`. Back up an unclaimed listen-once message, restore and play
 * it, then restore the same archive again and play it again; or ignore the file and re-fetch the blob with the
 * credentials the CSV kept. Withholding only one half fixes neither, and a restore-only correction leaves a
 * password-decryptable reusable payload sitting in the archive.
 *
 * Neither is the accepted failed-playback tradeoff and neither needs a modified client.
 *
 * The policy itself is pure and is exercised here. The three boundaries need a msgpack session, a zip archive and the
 * service graph respectively, so their wiring is asserted narrowly against the source; each assertion was proven red by
 * removing the line it names.
 *
 * Recorded rather than glossed: `spendForArchive`'s actual rewrite is not exercised, because `FileDataModel` serialises
 * through `android.util.JsonWriter`, which is stubbed to return null in a plain JVM unit test. Its round trip is
 * device-only for this fork today, which is why the backup/restore round-trip cases stay on the device checklist.
 */
class ListenOnceArchiveBoundaryTest {

    private val blobRequestHandler =
        File("src/main/java/ch/threema/app/webclient/services/instance/message/receiver/BlobRequestHandler.java")
    private val backupService = File("src/main/java/ch/threema/app/backuprestore/csv/BackupService.java")
    private val restoreService = File("src/main/java/ch/threema/app/backuprestore/csv/RestoreService.java")
    private val restrictedMediaOutput = File("src/main/java/ch/threema/app/utils/RestrictedMediaOutput.kt")

    // -----------------------------------------------------------------------------------------------------------------------------
    // The policy these boundaries consult.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `an incoming listen-once file message may not cross a generic output boundary`() {
        assertFalse(
            OutputRestrictionPolicy.mayReleaseMediaToGenericOutput(
                isOutbox = false,
                isFileMessage = true,
                isListenOnce = true,
            ),
            "released to exactly one consumer, the claim/burn owner in the chat player",
        )
    }

    @Test
    fun `the sender's own copy and ordinary media are unaffected`() {
        assertTrue(
            OutputRestrictionPolicy.mayReleaseMediaToGenericOutput(isOutbox = true, isFileMessage = true, isListenOnce = true),
            "the sender chose the restriction, and their copy is burned by the send anyway",
        )
        assertTrue(
            OutputRestrictionPolicy.mayReleaseMediaToGenericOutput(isOutbox = false, isFileMessage = true, isListenOnce = false),
        )
        assertTrue(
            OutputRestrictionPolicy.mayReleaseMediaToGenericOutput(isOutbox = false, isFileMessage = false, isListenOnce = false),
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Linked Web.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a linked-Web blob request is refused before the download and before the decryption`() {
        val source = blobRequestHandler.readText()
        val body = bodyOf(source, "protected void receive(Map<String, Value> message)")

        val refusalAt = body.indexOf("if (RestrictedMediaOutput.isRestricted(messageModel)) {")
        val playerAt = body.indexOf("new WebClientMessagePlayer(")
        assertTrue(refusalAt >= 0, "the handler must consult the output restriction")
        assertTrue(
            refusalAt < playerAt,
            "and must do so before the player is even constructed: the transferred bytes stay reusable whatever this " +
                "device records afterwards, so modelling the transfer as 'the one playback' would destroy the message " +
                "AND leak it",
        )
        assertTrue(
            body.substring(refusalAt).contains("postFailed(receiverType, receiverId, temporaryId, messageId,"),
            "a defined refusal, not a silent drop",
        )
    }

    @Test
    fun `a repeated request is refused for the same reason as the first`() {
        // The refusal reads only the restriction flag, not any claim or consumption state, so it is stateless by
        // construction and a second request cannot be answered differently from the first.
        val body = bodyOf(blobRequestHandler.readText(), "protected void receive(Map<String, Value> message)")
        val refusal = body.substring(body.indexOf("if (RestrictedMediaOutput.isRestricted(messageModel)) {"))
            .substringBefore("final WebClientMessagePlayer")

        assertFalse(refusal.contains("isListenOnceClaimed"), "a claim-aware refusal would let the second request through")
        assertFalse(refusal.contains("markAsConsumed"), "and consuming here would destroy the message on the way out")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Data backup.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a new backup writes a spent placeholder rather than the payload or its credentials`() {
        val source = backupService.readText()

        assertEquals(
            2,
            Regex("RestrictedMediaOutput\\.spendForArchive\\(").findAll(source).count(),
            "both the contact and the group message loops must reduce the row before writing anything about it",
        )
        for (loopMarker in listOf(
            "RestrictedMediaOutput.spendForArchive(messageModel);",
            "RestrictedMediaOutput.spendForArchive(groupMessageModel);",
        )) {
            val spendAt = source.indexOf(loopMarker)
            val bodyWriteAt = source.indexOf(".write(Tags.TAG_MESSAGE_BODY", spendAt)
            assertTrue(
                spendAt in 0 until bodyWriteAt,
                "the CSV body is the blob id and the blob key, so it must be stripped BEFORE the row is written; a " +
                    "restore-only correction leaves a decryptable reusable payload in the archive",
            )
        }
        assertTrue(
            bodyOf(source, "private void backupMediaFile(")
                .contains("if (RestrictedMediaOutput.isRestricted(messageModel)) {"),
            "and the file half is guarded independently, so a future call site that forgets cannot reintroduce it",
        )
    }

    @Test
    fun `stripping the body also withholds the file`() {
        val spend = bodyOf(restrictedMediaOutput.readText(), "fun spendForArchive(")

        assertTrue(spend.contains("fileData.setBlobId(null)"), "no blob id")
        assertTrue(spend.contains("fileData.setEncryptionKey(null)"), "no blob key")
        assertTrue(
            spend.contains("fileData.isDownloaded(false)"),
            "and not downloaded, which is what backupMediaFile keys its media decision on",
        )
        assertTrue(
            spend.contains("fileData.setListenOnceClaimed()") && spend.contains("fileData.setListenOnceConsumed()"),
            "so a restored bubble renders as an already-heard message rather than an unopened one",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Legacy archives.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a legacy archive cannot reconstruct a replayable message`() {
        val source = restoreService.readText()

        assertTrue(
            bodyOf(source, "private void setMessageContent(").contains("RestrictedMediaOutput.spendForArchive(messageModel)"),
            "archives created before the backup fix already hold the payload and the credentials; what is rebuilt from " +
                "them must be the same spent placeholder",
        )
        assertTrue(
            bodyOf(source, "private long restoreMessageMediaFiles(\n        @NonNull List<FileHeader> fileHeaders,")
                .contains("if (model != null && RestrictedMediaOutput.isRestricted(model)) {"),
            "and no media may be written for it, or the file would contradict the row",
        )
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
