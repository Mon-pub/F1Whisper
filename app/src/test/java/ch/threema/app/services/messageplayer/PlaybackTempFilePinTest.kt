package ch.threema.app.services.messageplayer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper: playback decrypts listen-once media into the file-provider share directory (the player reads it through a
 * content URI). Under upstream's deterministic "<id>-<filename>" share name it could overwrite a file an earlier share
 * already granted to another app, so playback must use a fresh name every time.
 */
class PlaybackTempFilePinTest {
    private val player = File("src/main/java/ch/threema/app/services/messageplayer/MessagePlayer.java").readText()
    private val fileService = File("src/main/java/ch/threema/app/services/FileServiceImpl.kt").readText()

    @Test
    fun `the player decrypts through the playback variant only`() {
        assertTrue(player.contains("fileService.decryptMessageFileToPlaybackTempFile(messageModel)"))
        assertFalse(player.contains("decryptMessageFileToShareableTempFile("), "the deterministic share name must not be used for playback")
    }

    @Test
    fun `the playback variant always takes a fresh name`() {
        val playback = Regex(
            """override fun decryptMessageFileToPlaybackTempFile\(message: AbstractMessageModel\): File\? =\s*""" +
                """decryptMessageFileToTempFile\(message, targetDirectory = appDirectoryProvider\.shareDirectory, uniqueName = true\)""",
        )
        assertTrue(playback.containsMatchIn(fileService))
        assertTrue(
            fileService.contains("val useDeterministicName = fileName != null && !uniqueName") &&
                fileService.contains("val decryptedFile = if (useDeterministicName) {"),
            "a unique name must bypass the deterministic <id>-<filename> path",
        )
    }
}
