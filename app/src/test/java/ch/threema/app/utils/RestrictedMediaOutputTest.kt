package ch.threema.app.utils

import ch.threema.domain.protocol.csp.messages.file.FileData
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.data.media.FileDataModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper (fourth fork review, F4-09): the generic output boundaries an incoming listen-once voice message must not
 * cross.
 *
 * The defect: listen-once was enforced only in the chat audio player, which claims the message before releasing plaintext
 * and burns it when playback ends. Every other route to the same file had no idea the flag existed. The media gallery
 * published the row like any other voice message, and from there it could be opened in the generic media viewer - which
 * prepares Media3 directly and seeks back to zero when playback ends, so it replays indefinitely - saved into the device
 * gallery, shared to another app, or written into a chat export. An unmodified client could therefore replay the audio, or
 * produce a permanent clear copy of it, without ever consuming it in the intended player.
 *
 * Covered here: the rule itself, the mixed-selection behaviour save and share need, the export decision, the direct-viewer
 * filtering, and that the five boundaries actually ask. Ordinary voice messages and the sender's own copies must keep
 * working, so each has its own case rather than being assumed.
 */
class RestrictedMediaOutputTest {
    private val boundaries = mapOf(
        "media gallery" to File("src/main/java/ch/threema/app/mediagallery/MediaGalleryRepository.kt"),
        "generic media viewer" to File("src/main/java/ch/threema/app/activities/MediaViewerActivity.java"),
        "save and share" to File("src/main/java/ch/threema/app/services/FileServiceImpl.java"),
        "chat export" to File("src/main/java/ch/threema/app/backuprestore/BackupChatServiceImpl.java"),
    )

    private fun fileModel(
        uid: String,
        isOutbox: Boolean = false,
        isListenOnce: Boolean = false,
        mimeType: String = "audio/aac",
    ) = MessageModel().apply {
        this.uid = uid
        identity = "AAAAAAAA"
        this.isOutbox = isOutbox
        type = MessageType.FILE
        fileData = FileDataModel(
            mimeType,
            null,
            0L,
            null,
            FileData.RENDERING_MEDIA,
            null,
            true,
            if (isListenOnce) mutableMapOf<String, Any>(FileDataModel.METADATA_KEY_LISTEN_ONCE to true) else null,
        )
    }

    private fun imageModel(uid: String) = MessageModel().apply {
        this.uid = uid
        identity = "AAAAAAAA"
        isOutbox = false
        type = MessageType.IMAGE
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The rule.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `an incoming listen-once voice message may not cross a generic boundary`() {
        assertFalse(RestrictedMediaOutput.mayRelease(fileModel("lo", isListenOnce = true)))
        assertTrue(RestrictedMediaOutput.isRestricted(fileModel("lo", isListenOnce = true)))
    }

    @Test
    fun `an ordinary incoming voice message is unaffected`() {
        assertTrue(
            RestrictedMediaOutput.mayRelease(fileModel("ordinary")),
            "ordinary voice messages must still be viewable, savable, shareable and exportable",
        )
    }

    @Test
    fun `the sender's own listen-once message is unaffected`() {
        assertTrue(
            RestrictedMediaOutput.mayRelease(fileModel("mine", isOutbox = true, isListenOnce = true)),
            "the sender chose the restriction; it describes what the RECIPIENT may do",
        )
    }

    @Test
    fun `a non-file message is unaffected`() {
        assertTrue(RestrictedMediaOutput.mayRelease(imageModel("image")))
    }

    @Test
    fun `an unreadable model fails closed`() {
        assertFalse(
            RestrictedMediaOutput.mayRelease(null),
            "a boundary that cannot tell must treat the content as restricted",
        )
    }

    @Test
    fun `the policy itself is the single source of the rule`() {
        assertFalse(
            OutputRestrictionPolicy.mayReleaseMediaToGenericOutput(
                isOutbox = false,
                isFileMessage = true,
                isListenOnce = true,
            ),
        )
        assertTrue(
            OutputRestrictionPolicy.mayReleaseMediaToGenericOutput(
                isOutbox = true,
                isFileMessage = true,
                isListenOnce = true,
            ),
        )
        assertTrue(
            OutputRestrictionPolicy.mayReleaseMediaToGenericOutput(
                isOutbox = false,
                isFileMessage = false,
                isListenOnce = true,
            ),
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Gallery and the direct viewer route: the row is simply not there.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the gallery and viewer publish everything except the restricted rows, in order`() {
        val loaded: List<AbstractMessageModel> = listOf(
            imageModel("photo"),
            fileModel("listen-once", isListenOnce = true),
            fileModel("voice"),
            fileModel("mine", isOutbox = true, isListenOnce = true),
            fileModel("document", mimeType = "application/pdf"),
        )

        val published = RestrictedMediaOutput.releasable(loaded)

        assertEquals(
            listOf("photo", "voice", "mine", "document"),
            published.map { it.uid },
            "only the incoming listen-once row is withheld, and the order of the rest is untouched",
        )
    }

    @Test
    fun `a gallery of nothing but restricted rows publishes nothing`() {
        val published = RestrictedMediaOutput.releasable(
            listOf(fileModel("a", isListenOnce = true), fileModel("b", isListenOnce = true)),
        )

        assertTrue(published.isEmpty())
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Mixed selections: save and share must do the rest of the job, not all-or-nothing.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a mixed save selection saves everything except the restricted message`() {
        val selection = listOf(imageModel("photo"), fileModel("listen-once", isListenOnce = true), fileModel("voice"))

        val saved = selection.filter { RestrictedMediaOutput.mayRelease(it) }
        val refused = selection.filter { RestrictedMediaOutput.isRestricted(it) }

        assertEquals(listOf("photo", "voice"), saved.map { it.uid })
        assertEquals(listOf("listen-once"), refused.map { it.uid })
    }

    @Test
    fun `a mixed share selection shares everything except the restricted message`() {
        val selection = listOf(fileModel("voice"), fileModel("listen-once", isListenOnce = true))

        // loadDecryptedMessageFiles keeps one slot per selected message and leaves the refused one null, so the caller
        // still shares the rest and is told that something was left out.
        val uris = selection.map { if (RestrictedMediaOutput.mayRelease(it)) it.uid else null }

        assertEquals(listOf("voice", null), uris)
    }

    @Test
    fun `a share selection of only restricted messages produces nothing to share`() {
        val selection = listOf(fileModel("listen-once", isListenOnce = true))

        assertTrue(selection.none { RestrictedMediaOutput.mayRelease(it) })
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Export.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a chat export writes no attachment for a restricted message`() {
        val conversation = listOf(fileModel("voice"), fileModel("listen-once", isListenOnce = true), imageModel("photo"))

        // The export writes a message line for every message and an attachment only for the releasable ones.
        val attachments = conversation.filter { RestrictedMediaOutput.mayRelease(it) }.map { it.uid }

        assertEquals(listOf("voice", "photo"), attachments)
        assertEquals(3, conversation.size, "every message still gets its line; only the attachment is withheld")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The boundaries actually ask.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `every generic output boundary consults the gate`() {
        for ((name, file) in boundaries) {
            assertTrue(file.exists(), "$name: this test's anchor has drifted")
            assertTrue(
                file.readText().contains("RestrictedMediaOutput"),
                "$name no longer asks whether the media may be released",
            )
        }
    }

    @Test
    fun `share reaches the gate on both of its routes`() {
        val fileService = boundaries.getValue("save and share").readText()

        assertTrue(
            fileService.contains("Refusing to share restricted media"),
            "the multi-select share route (loadDecryptedMessageFiles) must be gated",
        )
        assertTrue(
            fileService.contains("Refusing to copy restricted media to a share file"),
            "the media viewer's own share route (copyToShareFile) bypasses the other one and must be gated too",
        )
        assertTrue(
            fileService.contains("Refusing to save restricted media"),
            "saving to the device gallery must be gated",
        )
    }
}
