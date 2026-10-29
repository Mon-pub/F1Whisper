package ch.threema.app.services

import android.content.Context
import android.os.PowerManager
import ch.threema.common.toHexString
import ch.threema.domain.protocol.blob.BlobLoader
import ch.threema.domain.protocol.blob.BlobScope
import ch.threema.testhelpers.createTempDirectory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Assume

/**
 * A download holds the ciphertext once: no retained copy for a possible duplicate caller, a duplicate reads the
 * creator's temporary file (which stays until the last registered reader has opened it), a download only ever touches
 * its own registration, and running out of memory is a failed download rather than a crash.
 */
class DownloadServiceSingleCopyTest {
    private lateinit var cacheDirectory: File
    private lateinit var blobLoader: BlobLoader
    private val extraLoaders = ArrayDeque<BlobLoader>()
    private lateinit var downloadService: DownloadServiceImpl
    private val executor = Executors.newCachedThreadPool()
    private val blobId = byteArrayOf(1, 2, 3, 4)
    private val ciphertext = ByteArray(40_000) { (it % 253).toByte() }

    @BeforeTest
    fun setUp() {
        cacheDirectory = createTempDirectory("cache")
        blobLoader = mockk(relaxed = true)
        val context = mockk<Context> {
            every { cacheDir } returns cacheDirectory
            every { getSystemService(Context.POWER_SERVICE) } returns mockk<PowerManager>(relaxed = true)
        }
        val apiService = mockk<ApiService> {
            every { createLoader(any()) } answers { extraLoaders.removeFirstOrNull() ?: blobLoader }
        }
        downloadService = DownloadServiceImpl(context, apiService)
    }

    @AfterTest
    fun tearDown() {
        executor.shutdownNow()
        cacheDirectory.deleteRecursively()
    }

    private fun temporaryFile() = File(cacheDirectory, blobId.toHexString())

    private fun download(messageId: Int) = downloadService.download(messageId, blobId, BlobScope.Public, null, null)

    private fun downloadAsync(messageId: Int): Future<ByteArray?> = executor.submit<ByteArray?> { download(messageId) }

    /** Runs a download on a thread named after its message id, so a hook can tell the callers apart. */
    private fun downloadOnNamedThread(messageId: Int): Future<ByteArray?> {
        val task = FutureTask<ByteArray?> { download(messageId) }
        Thread(task, "download-$messageId").start()
        return task
    }

    /** Holds every joining caller after its wait until its own gate is opened; gates are keyed by message id. */
    private fun gateJoiners(vararg messageIds: Int): Pair<CountDownLatch, Map<Int, CountDownLatch>> {
        val awake = CountDownLatch(messageIds.size)
        val gates = messageIds.associateWith { CountDownLatch(1) }
        downloadService.joinerAwakeHook = Runnable {
            val gate = gates.getValue(Thread.currentThread().name.removePrefix("download-").toInt())
            awake.countDown()
            gate.await(10, TimeUnit.SECONDS)
        }
        return awake to gates
    }

    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting until $what")
            }
            Thread.sleep(5)
        }
    }

    /** The first load blocks until the returned latch is released, then returns [result]. */
    private fun blockingFirstLoad(result: ByteArray?, laterResult: ByteArray? = ciphertext): Pair<CountDownLatch, CountDownLatch> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        every { blobLoader.load(any()) } answers {
            if (calls++ == 0) {
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                result?.clone()
            } else {
                laterResult?.clone()
            }
        }
        return started to release
    }

    @Test
    fun `a download returns the loaded bytes and complete removes the temporary file`() {
        every { blobLoader.load(any()) } returns ciphertext.clone()

        assertContentEquals(ciphertext, download(1))
        assertTrue(temporaryFile().exists())

        downloadService.complete(1, blobId)
        assertFalse(temporaryFile().exists())
        assertFalse(downloadService.isDownloading())
    }

    @Test
    fun `a left-over temporary file is read without downloading again`() {
        temporaryFile().writeBytes(ciphertext)

        assertContentEquals(ciphertext, download(1))
        verify(exactly = 0) { blobLoader.load(any()) }
    }

    @Test
    fun `a too short temporary file is replaced by a fresh download`() {
        temporaryFile().writeBytes(ByteArray(3))
        every { blobLoader.load(any()) } returns ciphertext.clone()

        assertContentEquals(ciphertext, download(1))
        verify(exactly = 1) { blobLoader.load(any()) }
    }

    @Test
    fun `a duplicate caller waits for the creator and reads its temporary file`() {
        val (started, release) = blockingFirstLoad(ciphertext)
        val creator = downloadAsync(1)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val joiner = downloadAsync(2)
        awaitCondition("the joiner is registered") { downloadService.registeredReaders(blobId) == 1 }
        release.countDown()

        val creatorBytes = creator.get(10, TimeUnit.SECONDS)
        val joinerBytes = joiner.get(10, TimeUnit.SECONDS)
        assertContentEquals(ciphertext, creatorBytes)
        assertContentEquals(ciphertext, joinerBytes)
        assertNotSame(creatorBytes, joinerBytes, "both callers decrypt in place, so they must not share an array")
        verify(exactly = 1) { blobLoader.load(any()) }

        downloadService.complete(1, blobId)
        assertFalse(temporaryFile().exists())
    }

    @Test
    fun `complete keeps the temporary file for readers that have not opened it, the last one deletes it`() {
        val (started, release) = blockingFirstLoad(ciphertext)
        val (awake, gates) = gateJoiners(2, 3)
        val creator = downloadAsync(1)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val firstJoiner = downloadOnNamedThread(2)
        val secondJoiner = downloadOnNamedThread(3)
        awaitCondition("both joiners are registered") { downloadService.registeredReaders(blobId) == 2 }
        release.countDown()
        assertContentEquals(ciphertext, creator.get(10, TimeUnit.SECONDS))
        assertTrue(awake.await(10, TimeUnit.SECONDS))

        // The creator's caller completes (twice, harmlessly) before either joiner has opened the file.
        downloadService.complete(1, blobId)
        downloadService.complete(1, blobId)
        assertTrue(temporaryFile().exists())

        gates.getValue(2).countDown()
        assertContentEquals(ciphertext, firstJoiner.get(10, TimeUnit.SECONDS))
        // The first joiner's caller completes too; the second joiner still has to open the file.
        downloadService.complete(2, blobId)
        assertTrue(temporaryFile().exists())

        gates.getValue(3).countDown()
        assertContentEquals(ciphertext, secondJoiner.get(10, TimeUnit.SECONDS))
        assertFalse(temporaryFile().exists(), "the last reader deletes the temporary file")
        assertFalse(downloadService.isDownloading())
    }

    @Test
    fun `a duplicate caller of a failed download gets null`() {
        val (started, release) = blockingFirstLoad(null)
        val creator = downloadAsync(1)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val joiner = downloadAsync(2)
        awaitCondition("the joiner is registered") { downloadService.registeredReaders(blobId) == 1 }
        release.countDown()

        assertNull(creator.get(10, TimeUnit.SECONDS))
        assertNull(joiner.get(10, TimeUnit.SECONDS))
        verify(exactly = 1) { blobLoader.load(any()) }
    }

    @Test
    fun `an interrupted joiner gives up its registration and the creator still cleans up`() {
        val (started, release) = blockingFirstLoad(ciphertext)
        val creator = downloadAsync(1)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val joiner = downloadAsync(2)
        awaitCondition("the joiner is registered") { downloadService.registeredReaders(blobId) == 1 }
        joiner.cancel(true)
        awaitCondition("the joiner gave up its registration") { downloadService.registeredReaders(blobId) == 0 }
        release.countDown()

        assertContentEquals(ciphertext, creator.get(10, TimeUnit.SECONDS))
        downloadService.complete(1, blobId)
        assertFalse(temporaryFile().exists())
    }

    @Test
    fun `a cancelled download never overwrites or unregisters the newer download of the same blob`() {
        val staleBytes = ByteArray(ciphertext.size) { 7 }
        val (started, release) = blockingFirstLoad(staleBytes)
        val cancelled = downloadAsync(1)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        assertTrue(downloadService.cancel(1))

        assertContentEquals(ciphertext, download(2))
        release.countDown()
        assertNull(cancelled.get(10, TimeUnit.SECONDS), "a cancelled download hands out nothing its caller could complete")

        assertContentEquals(ciphertext, temporaryFile().readBytes(), "the stale download must not write the file")
        assertTrue(downloadService.isDownloading(2), "the stale download must not unregister the newer one")
    }

    @Test
    fun `a cancelled download that fails never unregisters the newer download of the same blob`() {
        val (started, release) = blockingFirstLoad(null)
        val cancelled = downloadAsync(1)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        assertTrue(downloadService.cancel(1))

        assertContentEquals(ciphertext, download(2))
        release.countDown()
        assertNull(cancelled.get(10, TimeUnit.SECONDS))

        assertTrue(downloadService.isDownloading(2))
        assertEquals(1, cacheDirectory.listFiles()!!.size)
    }

    @Test
    fun `complete for a blob never unregisters a newer download of it that is still in flight`() {
        val (started, release) = blockingFirstLoad(ciphertext)
        val inFlight = downloadAsync(1)
        assertTrue(started.await(10, TimeUnit.SECONDS))

        downloadService.complete(9, blobId)
        assertTrue(downloadService.isDownloading(1))

        release.countDown()
        assertContentEquals(ciphertext, inFlight.get(10, TimeUnit.SECONDS))
        assertContentEquals(ciphertext, temporaryFile().readBytes(), "the in-flight download still writes its file")
    }

    @Test
    fun `a stale error after the retry finished keeps the file for the retry's pending reader`() {
        val (staleLoader, staleStarted, staleRelease) = blockingLoader(null)
        val (retryLoader, retryStarted, retryRelease) = blockingLoader(ciphertext)
        extraLoaders.addAll(listOf(staleLoader, retryLoader))
        val (awake, gates) = gateJoiners(2)
        val stale = downloadAsync(1)
        assertTrue(staleStarted.await(10, TimeUnit.SECONDS))
        assertTrue(downloadService.cancel(1))
        val retry = downloadAsync(1)
        assertTrue(retryStarted.await(10, TimeUnit.SECONDS))
        val joiner = downloadOnNamedThread(2)
        awaitCondition("the joiner is registered on the retry") { downloadService.registeredReaders(blobId) == 1 }
        retryRelease.countDown()
        assertContentEquals(ciphertext, retry.get(10, TimeUnit.SECONDS))
        assertTrue(awake.await(10, TimeUnit.SECONDS))

        // Only now does the cancelled attempt finish and its caller report the error; then the retry's caller completes.
        staleRelease.countDown()
        assertNull(stale.get(10, TimeUnit.SECONDS))
        downloadService.error(1)
        downloadService.complete(1, blobId)
        assertTrue(temporaryFile().exists(), "the retry's joiner has not opened the file yet")

        gates.getValue(2).countDown()
        assertContentEquals(ciphertext, joiner.get(10, TimeUnit.SECONDS))
        assertFalse(temporaryFile().exists(), "the last reader deletes the temporary file")
        assertFalse(downloadService.isDownloading())
    }

    @Test
    fun `a cancel after the download finished keeps the file for its pending reader`() {
        val (started, release) = blockingFirstLoad(ciphertext)
        val (awake, gates) = gateJoiners(2)
        val creator = downloadAsync(1)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val joiner = downloadOnNamedThread(2)
        awaitCondition("the joiner is registered") { downloadService.registeredReaders(blobId) == 1 }
        release.countDown()
        assertContentEquals(ciphertext, creator.get(10, TimeUnit.SECONDS))
        assertTrue(awake.await(10, TimeUnit.SECONDS))

        assertTrue(downloadService.cancel(1), "a settled download still counts as cancelled")
        downloadService.complete(1, blobId)
        assertTrue(temporaryFile().exists())

        gates.getValue(2).countDown()
        assertContentEquals(ciphertext, joiner.get(10, TimeUnit.SECONDS))
        assertFalse(temporaryFile().exists())
        assertFalse(downloadService.isDownloading())
    }

    /** A loader whose load blocks until the returned latch is released, then returns [result]. */
    private fun blockingLoader(result: ByteArray?): Triple<BlobLoader, CountDownLatch, CountDownLatch> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loader = mockk<BlobLoader>(relaxed = true)
        every { loader.load(any()) } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            result?.clone()
        }
        return Triple(loader, started, release)
    }

    @Test
    fun `a cancelled attempt's error report never removes the retry of the same message`() {
        val (staleLoader, staleStarted, staleRelease) = blockingLoader(null)
        val (retryLoader, retryStarted, retryRelease) = blockingLoader(ciphertext)
        extraLoaders.addAll(listOf(staleLoader, retryLoader))
        val stale = downloadAsync(1)
        assertTrue(staleStarted.await(10, TimeUnit.SECONDS))
        assertTrue(downloadService.cancel(1))
        val retry = downloadAsync(1)
        assertTrue(retryStarted.await(10, TimeUnit.SECONDS))

        staleRelease.countDown()
        assertNull(stale.get(10, TimeUnit.SECONDS))
        // What the stale attempt's caller does with its null result.
        downloadService.error(1)
        assertTrue(downloadService.isDownloading(1), "the retry is still in flight and not the reporter's")

        retryRelease.countDown()
        assertContentEquals(ciphertext, retry.get(10, TimeUnit.SECONDS))
        assertContentEquals(ciphertext, temporaryFile().readBytes())
    }

    @Test
    fun `a temporary file that cannot be written fails the download without leaving a registration`() {
        every { blobLoader.load(any()) } answers { ciphertext.clone() }
        assertTrue(cacheDirectory.setWritable(false))
        try {
            Assume.assumeFalse("running with permissions that ignore the read-only directory", cacheDirectory.canWrite())

            assertNull(download(1))
            assertFalse(downloadService.isDownloading(), "a failed download must not stay registered for later callers to join")
            assertFalse(temporaryFile().exists())
        } finally {
            cacheDirectory.setWritable(true)
        }

        assertContentEquals(ciphertext, download(1), "the next attempt downloads normally")
        assertContentEquals(ciphertext, temporaryFile().readBytes())
    }

    @Test
    fun `a joiner that hits an error releases its registration and the creator still cleans up`() {
        val (started, release) = blockingFirstLoad(ciphertext)
        downloadService.joinerAwakeHook = Runnable { throw OutOfMemoryError("test") }
        val creator = downloadAsync(1)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val joiner = downloadAsync(2)
        awaitCondition("the joiner is registered") { downloadService.registeredReaders(blobId) == 1 }
        release.countDown()

        assertContentEquals(ciphertext, creator.get(10, TimeUnit.SECONDS))
        assertNull(joiner.get(10, TimeUnit.SECONDS), "the joiner's error is a failed download, not a crash")
        assertEquals(0, downloadService.registeredReaders(blobId))
        downloadService.complete(1, blobId)
        assertFalse(temporaryFile().exists())
        assertFalse(downloadService.isDownloading())
    }

    @Test
    fun `a joiner that times out gives up its registration and the creator still cleans up`() {
        val (started, release) = blockingFirstLoad(ciphertext)
        downloadService.joinTimeoutMillis = 50
        val creator = downloadAsync(1)
        assertTrue(started.await(10, TimeUnit.SECONDS))

        assertNull(downloadAsync(2).get(10, TimeUnit.SECONDS))
        assertEquals(0, downloadService.registeredReaders(blobId))

        release.countDown()
        assertContentEquals(ciphertext, creator.get(10, TimeUnit.SECONDS))
        downloadService.complete(1, blobId)
        assertFalse(temporaryFile().exists())
    }

    @Test
    fun `an error report after completion still lets a pending joiner read, and nothing stays registered`() {
        val (started, release) = blockingFirstLoad(ciphertext)
        val (awake, gates) = gateJoiners(2)
        val creator = downloadAsync(1)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val joiner = downloadOnNamedThread(2)
        awaitCondition("the joiner is registered") { downloadService.registeredReaders(blobId) == 1 }
        release.countDown()
        assertContentEquals(ciphertext, creator.get(10, TimeUnit.SECONDS))
        assertTrue(awake.await(10, TimeUnit.SECONDS))

        downloadService.complete(1, blobId)
        downloadService.error(1)

        gates.getValue(2).countDown()
        assertContentEquals(ciphertext, joiner.get(10, TimeUnit.SECONDS))
        assertFalse(downloadService.isDownloading())
    }

    @Test
    fun `running out of memory while loading is a failed download, not a crash`() {
        every { blobLoader.load(any()) } throws OutOfMemoryError("test")

        assertNull(download(1))
        assertFalse(downloadService.isDownloading(1))
    }

    @Test
    fun `spelling pin - the service source has no clone and no readBytes call`() {
        val source = File("src/main/java/ch/threema/app/services/DownloadServiceImpl.java").readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

        assertFalse(source.contains(".clone()"), "a retained or joined copy doubles the heap a large file needs")
        assertFalse(source.contains("readBytes("), "readBytes needs about twice the file size")
    }
}
