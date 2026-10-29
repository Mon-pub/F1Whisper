package ch.threema.app.files

import ch.threema.testhelpers.createTempDirectory
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** A partial media download survives the periodic cleanup for a day, so it can still be continued. */
class TempFilesCleanupPartFileTest {
    private lateinit var directory: File
    private val now = Instant.parse("2026-10-29T12:00:00Z")

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("cleanup")
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun fileAged(name: String, age: Duration) = File(directory, name).apply {
        writeBytes(ByteArray(1))
        setLastModified(now.toEpochMilli() - age.inWholeMilliseconds)
    }

    private fun expired(file: File, ageThreshold: Duration) =
        TempFilesCleanupWorker.isExpired(
            file,
            Instant.ofEpochMilli(now.toEpochMilli() - ageThreshold.inWholeMilliseconds),
            TempFilesCleanupWorker.partFileThreshold(now, ageThreshold),
        )

    @Test
    fun `the periodic cleanup keeps a part file for 24 hours and other files for 2`() {
        assertTrue(expired(fileAged("0a0b", 3.hours), 2.hours))
        assertFalse(expired(fileAged("0a0b.part", 3.hours), 2.hours))
        assertFalse(expired(fileAged("0c0d.part", 23.hours), 2.hours))
        assertTrue(expired(fileAged("0e0f.part", 25.hours), 2.hours))
    }

    @Test
    fun `a longer cleanup age applies to part files too`() {
        assertEquals(now.minusSeconds(48 * 3600), TempFilesCleanupWorker.partFileThreshold(now, 48.hours))
    }

    @Test
    fun `the full cleanup removes part files as well`() {
        assertTrue(expired(fileAged("0a0b.part", 1.hours), Duration.ZERO))
        assertEquals(now, TempFilesCleanupWorker.partFileThreshold(now, Duration.ZERO))
    }

    @Test
    fun `a directory named like a part file follows the usual age`() {
        val partDirectory = File(directory, "x.part").apply {
            mkdir()
            setLastModified(now.toEpochMilli() - 3.hours.inWholeMilliseconds)
        }

        assertTrue(expired(partDirectory, 2.hours))
    }
}
