package ch.threema.app.services

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper (fifth fork review, F5-04): the media-metadata lifecycle writes.
 *
 * Listen-once claimed/consumed and media-downloaded state all live inside the SERIALISED BODY of the message, not in
 * columns of their own. So two transitions deciding from two different reads of that body discarded one another's
 * flags - and because each persisted through `MessageService#save`, a full-row upsert, either could also recreate a row
 * hard-deleted while it was working, restore the body over a delete-for-everyone, or revert every other column to whatever
 * its detached instance happened to hold.
 *
 * The write is now one conditional column-scoped update conditional on the body it read, with the mutation re-applied to
 * the winner's body on a lost race. The statement itself is executable and is tested against real SQLite in
 * `MessageRowUpdateTest`, including the case where a superseded body refuses the write - which is the half of the merge
 * that can fail. What is left is that these five call sites actually use it, and in the right order, so that is asserted
 * narrowly against the source; none of them is reachable without the service graph, a real encrypted database and a real
 * interleaving.
 *
 * Recorded rather than glossed: the merge itself - re-parsing the winner's body and re-applying the mutation to it - is
 * NOT exercised here, because `FileDataModel` serialises through `android.util.JsonWriter`, which is stubbed to return
 * null in a plain JVM unit test. Its round trip is device-only for this fork today.
 *
 * Each source assertion was proven red by removing the line it names.
 */
class MediaMetadataWriteTest {

    private val messageServiceImpl = File("src/main/java/ch/threema/app/services/MessageServiceImpl.java")
    private val listenOnceEnforcer = File("src/main/java/ch/threema/app/services/messageplayer/ListenOnceEnforcer.java")
    private val messagePlayer = File("src/main/java/ch/threema/app/services/messageplayer/MessagePlayer.java")

    // -----------------------------------------------------------------------------------------------------------------------------
    // Every mutation is applied to the row as it CURRENTLY is.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `every media mutation reads its data model from the reloaded row, never from the caller's instance`() {
        // This is what makes a retry a MERGE rather than an overwrite: the lambda is handed the row that won, so the
        // flags it did not set are the winner's, not the ones the caller read minutes earlier.
        for ((file, signature) in listOf(
            listenOnceEnforcer to "public static void claim(",
            listenOnceEnforcer to "public static void burn(",
            messageServiceImpl to "private void burnOutgoingListenOnceIfNeeded(",
            messageServiceImpl to "private boolean setDownloadCompleted(\n        @NonNull AbstractMessageModel mediaMessageModel,",
        )) {
            val body = bodyOf(file.readText(), signature)
            assertTrue(
                body.contains("current.getFileData()") || body.contains("getDataForMessageType(current)"),
                "$signature must mutate the freshly read row's data model",
            )
        }
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The wiring.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the incoming claim writes the current row, and playback still proceeds when it cannot`() {
        val source = listenOnceEnforcer.readText()
        val claimBody = bodyOf(source, "public static void claim(")

        assertTrue(
            claimBody.contains("messageService.updateMediaMetadata(messageModel, current -> {"),
            "the claim must be applied to a freshly read row, not to the player's retained instance",
        )
        assertFalse(claimBody.contains("messageService.save("), "a full-row save here could recreate a deleted message")
        assertTrue(
            claimBody.contains("RuntimeUtil.runOnUiThread(onClaimed)") && claimBody.contains("} finally {"),
            "the owner-approved tradeoff must survive: a failed claim write still releases the plaintext to playback",
        )
    }

    @Test
    fun `the burn is one write and its media removal is unconditional`() {
        val source = listenOnceEnforcer.readText()
        val burnBody = bodyOf(source, "public static void burn(")

        assertTrue(
            burnBody.contains("messageService.consumeAndUpdateMediaMetadata(messageModel, current -> {"),
            "consumed state and burned metadata are one fact and must be one write",
        )
        assertFalse(burnBody.contains("messageService.markAsConsumed("), "that was the first of three separate saves")
        assertFalse(burnBody.contains("messageService.save("), "and this was the third")

        val writeAt = burnBody.indexOf("consumeAndUpdateMediaMetadata")
        val removeAt = burnBody.indexOf("fileService.removeMessageFiles(messageModel, true)")
        assertTrue(writeAt in 0 until removeAt, "the durable write must land before the media is destroyed")
        assertFalse(
            burnBody.substring(writeAt, removeAt).contains("return;"),
            "the removal must not be skipped when the write changed nothing: an interrupted burn leaves exactly that " +
                "shape, and this call is its repair",
        )
    }

    @Test
    fun `the outgoing burn writes before it deletes and gives up when the row has gone`() {
        val source = messageServiceImpl.readText()
        val burnBody = bodyOf(source, "private void burnOutgoingListenOnceIfNeeded(")

        assertTrue(burnBody.contains("updateMediaMetadata(messageModel, current -> {"))
        val writeAt = burnBody.indexOf("updateMediaMetadata")
        val removeAt = burnBody.indexOf("fileService.removeMessageFiles(messageModel, true)")
        assertTrue(writeAt in 0 until removeAt, "a lost race must delete nothing")
        assertTrue(burnBody.contains("if (!burned) {"), "and must publish nothing")
    }

    @Test
    fun `download completion cannot publish for a row it lost`() {
        val source = messageServiceImpl.readText()
        // The anchor names the MULTI-LINE overload: `indexOf` would otherwise match the one-line delegating one,
        // whose body is a single call and satisfies nothing (sixth review).
        val completionBody = bodyOf(source, "private boolean setDownloadCompleted(\n        @NonNull AbstractMessageModel mediaMessageModel,")

        assertTrue(completionBody.contains("updateMediaMetadata(mediaMessageModel, current -> {"))
        assertFalse(completionBody.contains("save(mediaMessageModel)"), "a detached full-row save undid whatever landed during the download")
        assertTrue(
            completionBody.contains("current == null || current.getDeletedAt() != null"),
            "gone AND deleted-for-everyone both mean this attempt owns the media it wrote",
        )
        assertTrue(
            completionBody.contains("fileService.removeMessageFiles(mediaMessageModel, true)"),
            "and must clean it up rather than leave it orphaned on disk",
        )
        assertTrue(
            completionBody.contains("currentData.isDownloaded()"),
            "but media adopted by a still-current row belongs to that row and must NOT be cleaned up",
        )

        val callerBody = bodyOf(source, "public boolean downloadMediaMessage(")
        assertTrue(
            callerBody.contains("if (!setDownloadCompleted(mediaMessageModel, data)) {"),
            "a completion that was refused must stop the gallery save, the listener and the notification",
        )
    }

    @Test
    fun `the player no longer repeats the download completion as a full-row save`() {
        val openBody = bodyOf(messagePlayer.readText(), "public boolean open(final boolean autoPlay)")

        assertFalse(
            openBody.contains("messageService.save(setData(data))"),
            "this was the last insert-capable write on the media path, from a model the player had retained across the " +
                "whole download",
        )
    }

    /** The text from [signature] to the end of its body, matched by brace depth, so one method's assertion cannot be satisfied by another's. */
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
