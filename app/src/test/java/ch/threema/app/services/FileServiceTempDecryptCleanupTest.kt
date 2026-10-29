package ch.threema.app.services

import ch.threema.app.files.AppDirectoryProvider
import ch.threema.app.files.MessageFileHandleProvider
import ch.threema.common.files.FileHandle
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageType
import ch.threema.testhelpers.createTempDirectory
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A temporary decrypt never leaves a file it created or truncated behind, and never deletes a deterministic export it did
 * not open for writing (an earlier share of the same message may still be in use).
 */
class FileServiceTempDecryptCleanupTest {
    private lateinit var shareDirectory: File
    private lateinit var fileHandle: FileHandle
    private lateinit var fileService: FileServiceImpl

    @BeforeTest
    fun setUp() {
        shareDirectory = createTempDirectory("share")
        fileHandle = mockk()
        val appDirectoryProvider = mockk<AppDirectoryProvider> {
            every { shareDirectory } returns this@FileServiceTempDecryptCleanupTest.shareDirectory
        }
        val messageFileHandleProvider = mockk<MessageFileHandleProvider>()
        every { messageFileHandleProvider.get(any()) } returns fileHandle
        val notificationPreferenceService = mockk<NotificationPreferenceService>(relaxed = true) {
            every { getLegacyVoipCallRingtone() } returns null
        }
        fileService = FileServiceImpl(
            appContext = mockk(relaxed = true),
            appDirectoryProvider = appDirectoryProvider,
            preferenceService = mockk(relaxed = true),
            notificationPreferenceService = notificationPreferenceService,
            avatarCacheService = mockk(relaxed = true),
            messageFileHandleProvider = messageFileHandleProvider,
            profilePictureFileHandleProvider = mockk(relaxed = true),
            groupProfilePictureFileHandleProvider = mockk(relaxed = true),
            timeProvider = mockk(relaxed = true),
        )
    }

    @AfterTest
    fun tearDown() {
        shareDirectory.deleteRecursively()
    }

    private fun namedFileMessage(): AbstractMessageModel = mockk {
        every { uid } returns "uid-1"
        every { id } returns 7
        every { type } returns MessageType.FILE
        every { fileData.fileName } returns "report.pdf"
    }

    private fun unnamedMessage(): AbstractMessageModel = mockk {
        every { uid } returns "uid-2"
        every { id } returns 8
        every { type } returns MessageType.TEXT
    }

    private fun failingStream(): InputStream = object : InputStream() {
        override fun read(): Int = throw IOException("broken source")
    }

    /** Delivers some plaintext, then fails, like a truncated encrypted source. */
    private fun failingAfterSomeBytes(): InputStream = object : InputStream() {
        private val head = ByteArrayInputStream(ByteArray(20_000) { 5 })
        override fun read(): Int = head.read().takeIf { it != -1 } ?: throw IOException("truncated source")
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            head.read(b, off, len).takeIf { it != -1 } ?: throw IOException("truncated source")
    }

    private fun shareDirectoryNames() = shareDirectory.listFiles()!!.map { it.name }.sorted()

    @Test
    fun `successful playback decrypt keeps its file`() {
        every { fileHandle.read() } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))

        val file = fileService.decryptMessageFileToPlaybackTempFile(unnamedMessage())

        assertEquals(listOf<Byte>(1, 2, 3), file!!.readBytes().toList())
    }

    @Test
    fun `two playback decrypts of the same message use distinct files`() {
        every { fileHandle.read() } answers { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }

        val first = fileService.decryptMessageFileToPlaybackTempFile(unnamedMessage())!!
        val second = fileService.decryptMessageFileToPlaybackTempFile(unnamedMessage())!!

        assertTrue(first.path != second.path, "a playback copy must never reuse another copy's path")
        assertEquals(listOf<Byte>(1, 2, 3), first.readBytes().toList())
        assertEquals(listOf<Byte>(1, 2, 3), second.readBytes().toList())
    }

    @Test
    fun `plaintext written before a failure is deleted from a unique playback file`() {
        every { fileHandle.read() } returns failingAfterSomeBytes()

        assertFailsWith<IOException> { fileService.decryptMessageFileToPlaybackTempFile(unnamedMessage()) }
        assertEquals(emptyList(), shareDirectoryNames())
    }

    @Test
    fun `plaintext written before a failure is deleted from a deterministic share file`() {
        every { fileHandle.read() } returns failingAfterSomeBytes()

        assertFailsWith<IOException> { fileService.decryptMessageFileToShareableTempFile(namedFileMessage()) }
        assertEquals(emptyList(), shareDirectoryNames())
    }

    @Test
    fun `missing source leaves no unique temp file`() {
        every { fileHandle.read() } returns null

        assertNull(fileService.decryptMessageFileToPlaybackTempFile(unnamedMessage()))
        assertEquals(emptyList(), shareDirectory.listFiles()!!.toList())
    }

    @Test
    fun `source that fails to open leaves no unique temp file`() {
        every { fileHandle.read() } throws IOException("Bad encrypted file (empty)")

        assertFailsWith<IOException> { fileService.decryptMessageFileToPlaybackTempFile(unnamedMessage()) }
        assertEquals(emptyList(), shareDirectory.listFiles()!!.toList())
    }

    @Test
    fun `failed copy deletes the truncated deterministic file`() {
        val earlierExport = File(shareDirectory, "7-report.pdf").apply { writeText("earlier") }
        every { fileHandle.read() } returns failingStream()

        assertFailsWith<IOException> { fileService.decryptMessageFileToShareableTempFile(namedFileMessage()) }
        assertTrue(!earlierExport.exists(), "a partial plaintext copy must not stay behind")
    }

    @Test
    fun `source that fails to open keeps an earlier deterministic export`() {
        val earlierExport = File(shareDirectory, "7-report.pdf").apply { writeText("earlier") }
        every { fileHandle.read() } throws IOException("Bad encrypted file (empty)")

        assertFailsWith<IOException> { fileService.decryptMessageFileToShareableTempFile(namedFileMessage()) }
        assertEquals("earlier", earlierExport.readText())
        assertEquals(listOf("7-report.pdf"), shareDirectoryNames())
    }

    @Test
    fun `missing source keeps an earlier deterministic export`() {
        val earlierExport = File(shareDirectory, "7-report.pdf").apply { writeText("earlier") }
        every { fileHandle.read() } returns null

        assertNull(fileService.decryptMessageFileToShareableTempFile(namedFileMessage()))
        assertEquals("earlier", earlierExport.readText())
        assertEquals(listOf("7-report.pdf"), shareDirectoryNames())
    }
}
