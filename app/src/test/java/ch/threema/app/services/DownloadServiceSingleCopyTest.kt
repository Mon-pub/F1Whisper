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
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
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
 *
 * F1-PATCH (resumable downloads): the loader writes a part file that a later attempt continues, only one attempt at a
 * time writes it, and the blob is marked as done only once the caller has completed the download.
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

    private fun partFile() = File(cacheDirectory, blobId.toHexString() + ".part")

    private fun download(messageId: Int, markAsDone: BlobScope? = null) =
        downloadService.download(messageId, blobId, BlobScope.Public, markAsDone, null)

    /** What a loader does with the part file it is given: write [result] (complete), or fail when it is null. */
    private fun writePart(part: File, result: ByteArray?): Boolean {
        if (result == null) {
            throw IOException("test: the download failed")
        }
        part.writeBytes(result)
        return true
    }

    private fun BlobLoader.loads(result: ByteArray?) {
        every { loadToFile(any(), any()) } answers { writePart(secondArg(), result?.clone()) }
    }

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

    /** The first load blocks until the returned latch is released, then writes [result] (null = it fails). */
    private fun blockingFirstLoad(result: ByteArray?, laterResult: ByteArray? = ciphertext): Pair<CountDownLatch, CountDownLatch> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        every { blobLoader.loadToFile(any(), any()) } answers {
            if (calls++ == 0) {
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                writePart(secondArg(), result?.clone())
            } else {
                writePart(secondArg(), laterResult?.clone())
            }
        }
        return started to release
    }

    /**
     * A loader whose load blocks until the returned latch is released, then writes [result] (null = it fails). Like a
     * cancelled loader that is still unwinding, a cancel does not release it.
     */
    private fun blockingLoader(result: ByteArray?): Triple<BlobLoader, CountDownLatch, CountDownLatch> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loader = mockk<BlobLoader>(relaxed = true)
        every { loader.loadToFile(any(), any()) } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            writePart(secondArg(), result?.clone())
        }
        return Triple(loader, started, release)
    }

    @Test
    fun `a download returns the loaded bytes and complete removes the temporary file`() {
        blobLoader.loads(ciphertext)

        assertContentEquals(ciphertext, download(1))
        assertTrue(temporaryFile().exists())
        assertFalse(partFile().exists(), "the complete part file became the downloaded file")

        downloadService.complete(1, blobId)
        assertFalse(temporaryFile().exists())
        assertFalse(downloadService.isDownloading())
    }

    @Test
    fun `a left-over temporary file is read without downloading again`() {
        temporaryFile().writeBytes(ciphertext)

        assertContentEquals(ciphertext, download(1))
        verify(exactly = 0) { blobLoader.loadToFile(any(), any()) }
    }

    @Test
    fun `a too short temporary file is replaced by a fresh download`() {
        temporaryFile().writeBytes(ByteArray(3))
        blobLoader.loads(ciphertext)

        assertContentEquals(ciphertext, download(1))
        verify(exactly = 1) { blobLoader.loadToFile(any(), any()) }
    }

    @Test
    fun `a download that breaks keeps its part file and the next attempt continues it`() {
        val half = ciphertext.copyOfRange(0, ciphertext.size / 2)
        val partsSeen = mutableListOf<ByteArray?>()
        var calls = 0
        every { blobLoader.loadToFile(any(), any()) } answers {
            val part = secondArg<File>()
            partsSeen.add(if (part.exists()) part.readBytes() else null)
            if (calls++ == 0) {
                part.writeBytes(half)
                throw IOException("test: connection reset")
            }
            part.appendBytes(ciphertext.copyOfRange(part.length().toInt(), ciphertext.size))
            true
        }

        assertNull(download(1))
        downloadService.error(1)
        assertContentEquals(half, partFile().readBytes(), "what arrived stays for the next attempt")

        assertContentEquals(ciphertext, download(1))
        assertNull(partsSeen[0])
        assertContentEquals(half, partsSeen[1], "the second attempt is given the part file the first one left")
    }

    @Test
    fun `the blob is marked as done only once the download is completed, and only once`() {
        blobLoader.loads(ciphertext)

        assertContentEquals(ciphertext, download(1, markAsDone = BlobScope.Public))
        Thread.sleep(100)
        verify(exactly = 0) { blobLoader.markAsDone(any(), any()) }

        downloadService.complete(1, blobId)
        verify(timeout = 5_000, exactly = 1) { blobLoader.markAsDone(blobId, BlobScope.Public) }
        downloadService.complete(1, blobId)
        Thread.sleep(100)
        verify(exactly = 1) { blobLoader.markAsDone(any(), any()) }
    }

    @Test
    fun `a blob completed by several callers while a joiner still reads it is marked as done once`() {
        val (started, release) = blockingFirstLoad(ciphertext)
        val (awake, gates) = gateJoiners(2)
        val creator = executor.submit<ByteArray?> { download(1, markAsDone = BlobScope.Public) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val joiner = downloadOnNamedThread(2)
        awaitCondition("the joiner is registered") { downloadService.registeredReaders(blobId) == 1 }
        release.countDown()
        assertContentEquals(ciphertext, creator.get(10, TimeUnit.SECONDS))
        assertTrue(awake.await(10, TimeUnit.SECONDS))

        // The download stays registered for the joiner, so every completion finds it.
        downloadService.complete(1, blobId)
        downloadService.complete(1, blobId)
        gates.getValue(2).countDown()
        assertContentEquals(ciphertext, joiner.get(10, TimeUnit.SECONDS))
        downloadService.complete(2, blobId)

        verify(timeout = 5_000, exactly = 1) { blobLoader.markAsDone(blobId, BlobScope.Public) }
        Thread.sleep(100)
        verify(exactly = 1) { blobLoader.markAsDone(any(), any()) }
    }

    @Test
    fun `an error after a successful download never marks the blob as done and discards the downloaded file`() {
        blobLoader.loads(ciphertext)

        assertContentEquals(ciphertext, download(1, markAsDone = BlobScope.Public))
        // What the caller does when the media does not decrypt (or cannot be saved).
        downloadService.error(1)
        assertFalse(temporaryFile().exists(), "the same bytes must not be decrypted again by the next attempt")

        assertContentEquals(ciphertext, download(1))
        verify(exactly = 2) { blobLoader.loadToFile(any(), any()) }
        Thread.sleep(100)
        verify(exactly = 0) { blobLoader.markAsDone(any(), any()) }
    }

    @Test
    fun `a download without a mark-as-done scope is never marked as done`() {
        blobLoader.loads(ciphertext)

        assertContentEquals(ciphertext, download(1))
        downloadService.complete(1, blobId)
        Thread.sleep(100)
        verify(exactly = 0) { blobLoader.markAsDone(any(), any()) }
    }

    @Test
    fun `a left-over temporary file is marked as done once it is completed`() {
        temporaryFile().writeBytes(ciphertext)

        assertContentEquals(ciphertext, download(1, markAsDone = BlobScope.Public))
        downloadService.complete(1, blobId)

        verify(timeout = 5_000, exactly = 1) { blobLoader.markAsDone(blobId, BlobScope.Public) }
        assertFalse(temporaryFile().exists())
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
        verify(exactly = 1) { blobLoader.loadToFile(any(), any()) }

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
        verify(exactly = 1) { blobLoader.loadToFile(any(), any()) }
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
    fun `a retry waits until the cancelled attempt has stopped writing the part file`() {
        val staleStarted = CountDownLatch(1)
        val staleRelease = CountDownLatch(1)
        val staleLoader = mockk<BlobLoader>(relaxed = true)
        val staleStoppedWriting = AtomicBoolean(false)
        every { staleLoader.loadToFile(any(), any()) } answers {
            staleStarted.countDown()
            staleRelease.await(10, TimeUnit.SECONDS)
            writePart(secondArg(), ciphertext.clone()).also { staleStoppedWriting.set(true) }
        }
        val retryLoader = mockk<BlobLoader>(relaxed = true)
        var retryStartedAfterStale = false
        every { retryLoader.loadToFile(any(), any()) } answers {
            retryStartedAfterStale = staleStoppedWriting.get()
            writePart(secondArg(), ciphertext.clone())
        }
        extraLoaders.addAll(listOf(staleLoader, retryLoader))
        val stale = downloadAsync(1)
        assertTrue(staleStarted.await(10, TimeUnit.SECONDS))
        assertTrue(downloadService.cancel(1))

        val retry = downloadAsync(2)
        Thread.sleep(200)
        assertTrue(downloadService.isDownloading(2))
        verify(exactly = 0) { retryLoader.loadToFile(any(), any()) }

        staleRelease.countDown()
        assertNull(stale.get(10, TimeUnit.SECONDS), "a cancelled download hands out nothing its caller could complete")
        assertContentEquals(ciphertext, retry.get(10, TimeUnit.SECONDS))
        assertTrue(retryStartedAfterStale, "the part file must never have two writers")
        assertTrue(downloadService.isDownloading(2), "the stale download must not unregister the newer one")
    }

    @Test
    fun `a cancelled retry that gives up waiting never lets a later retry write next to the stuck attempt`() {
        val (stuckLoader, stuckStarted, stuckRelease) = blockingLoader(null)
        val waitingLoader = mockk<BlobLoader>(relaxed = true)
        val laterLoader = mockk<BlobLoader>(relaxed = true)
        every { laterLoader.loadToFile(any(), any()) } answers { writePart(secondArg(), ciphertext.clone()) }
        extraLoaders.addAll(listOf(stuckLoader, waitingLoader, laterLoader))
        val stuck = downloadAsync(1)
        assertTrue(stuckStarted.await(10, TimeUnit.SECONDS))
        assertTrue(downloadService.cancel(1))

        val waiting = downloadAsync(2)
        awaitCondition("the first retry is registered") { downloadService.isDownloading(2) }
        assertTrue(downloadService.cancel(2))
        val later = downloadAsync(3)

        assertNull(waiting.get(5, TimeUnit.SECONDS), "a retry cancelled while it waits stops waiting")
        Thread.sleep(1_500)
        verify(exactly = 0) { waitingLoader.loadToFile(any(), any()) }
        verify(exactly = 0) { laterLoader.loadToFile(any(), any()) }

        stuckRelease.countDown()
        assertNull(stuck.get(10, TimeUnit.SECONDS))
        assertContentEquals(ciphertext, later.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun `while a stuck attempt holds the part file no later retry ever writes it`() {
        val (stuckLoader, stuckStarted, stuckRelease) = blockingLoader(null)
        val laterLoader = mockk<BlobLoader>(relaxed = true)
        every { laterLoader.loadToFile(any(), any()) } answers { writePart(secondArg(), ciphertext.clone()) }
        extraLoaders.addAll(listOf(stuckLoader, laterLoader, laterLoader, laterLoader))
        val stuck = downloadAsync(1)
        assertTrue(stuckStarted.await(10, TimeUnit.SECONDS))
        assertTrue(downloadService.cancel(1))
        downloadService.joinTimeoutMillis = 300

        val first = downloadAsync(2)
        awaitCondition("the first retry is registered") { downloadService.isDownloading(2) }
        assertTrue(downloadService.cancel(2))
        assertNull(downloadAsync(3).get(10, TimeUnit.SECONDS), "it gives up instead of writing next to the stuck attempt")
        assertNull(first.get(10, TimeUnit.SECONDS))
        verify(exactly = 0) { laterLoader.loadToFile(any(), any()) }

        stuckRelease.countDown()
        assertNull(stuck.get(10, TimeUnit.SECONDS))
        assertContentEquals(ciphertext, download(4))
    }

    @Test
    fun `a part file that is continued is refreshed against the cache cleanup, and so is the downloaded file`() {
        val dayOld = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(25)
        partFile().writeBytes(ciphertext)
        assertTrue(partFile().setLastModified(dayOld))
        var partAgeAtLoad = Long.MAX_VALUE
        every { blobLoader.loadToFile(any(), any()) } answers {
            // A real loader confirms a complete part file with one request and writes nothing.
            partAgeAtLoad = System.currentTimeMillis() - secondArg<File>().lastModified()
            true
        }

        assertContentEquals(ciphertext, download(1))

        assertTrue(partAgeAtLoad < TimeUnit.MINUTES.toMillis(1), "the part file was not refreshed: $partAgeAtLoad ms old")
        assertTrue(System.currentTimeMillis() - temporaryFile().lastModified() < TimeUnit.MINUTES.toMillis(1))
    }

    @Test
    fun `a completion still marks the blob as done after an unrelated error report removed the download`() {
        blobLoader.loads(ciphertext)

        assertContentEquals(ciphertext, download(1, markAsDone = BlobScope.Public))
        // Another download of the same message (its thumbnail) failed while this one was being decrypted.
        downloadService.error(1)
        downloadService.complete(1, blobId)

        verify(timeout = 5_000, exactly = 1) { blobLoader.markAsDone(blobId, BlobScope.Public) }
    }

    @Test
    fun `a completion still marks the blob as done after a late cancel`() {
        blobLoader.loads(ciphertext)

        assertContentEquals(ciphertext, download(1, markAsDone = BlobScope.Public))
        downloadService.cancel(1)
        downloadService.complete(1, blobId)

        verify(timeout = 5_000, exactly = 1) { blobLoader.markAsDone(blobId, BlobScope.Public) }
    }

    @Test
    fun `a pending mark as done does not keep the caller's progress listener`() {
        blobLoader.loads(ciphertext)
        val listener = mockk<ch.threema.base.ProgressListener>(relaxed = true)

        assertContentEquals(ciphertext, downloadService.download(1, blobId, BlobScope.Public, BlobScope.Public, listener))

        assertNull(blobLoader.progressListener)
    }

    @Test
    fun `pending marks of downloads that were never completed are bounded`() {
        blobLoader.loads(ciphertext)
        val count = DownloadServiceImpl.MAX_PENDING_MARKS_AS_DONE + 1
        val blobIds = (0 until count).map { byteArrayOf(9, (it shr 8).toByte(), it.toByte()) }
        blobIds.forEachIndexed { index, id ->
            assertContentEquals(ciphertext, downloadService.download(100 + index, id, BlobScope.Public, BlobScope.Public, null))
            // The media did not decrypt: an error, never a completion.
            downloadService.error(100 + index)
        }

        downloadService.complete(100, blobIds.first())
        downloadService.complete(100 + count - 1, blobIds.last())

        verify(timeout = 5_000, exactly = 1) { blobLoader.markAsDone(blobIds.last(), BlobScope.Public) }
        Thread.sleep(100)
        verify(exactly = 1) { blobLoader.markAsDone(any(), any()) }
    }

    @Test
    fun `no thread for marking as done is not a crash of the completion`() {
        blobLoader.loads(ciphertext)
        downloadService.markAsDoneStarter = Consumer { throw OutOfMemoryError("test: no thread") }

        assertContentEquals(ciphertext, download(1, markAsDone = BlobScope.Public))
        downloadService.complete(1, blobId)

        assertFalse(temporaryFile().exists())
        assertFalse(downloadService.isDownloading())
    }

    @Test
    fun `a cancelled download that fails never unregisters the newer download of the same blob`() {
        val (staleLoader, staleStarted, staleRelease) = blockingLoader(null)
        val (retryLoader, retryStarted, retryRelease) = blockingLoader(ciphertext)
        extraLoaders.addAll(listOf(staleLoader, retryLoader))
        val cancelled = downloadAsync(1)
        assertTrue(staleStarted.await(10, TimeUnit.SECONDS))
        assertTrue(downloadService.cancel(1))
        val retry = downloadAsync(2)

        staleRelease.countDown()
        assertNull(cancelled.get(10, TimeUnit.SECONDS))
        assertTrue(retryStarted.await(10, TimeUnit.SECONDS))
        assertTrue(downloadService.isDownloading(2))

        retryRelease.countDown()
        assertContentEquals(ciphertext, retry.get(10, TimeUnit.SECONDS))
        assertTrue(downloadService.isDownloading(2))
        assertEquals(listOf(temporaryFile().name), cacheDirectory.listFiles()!!.map { it.name })
    }

    @Test
    fun `a cancelled download that finished anyway keeps its part file for the retry`() {
        val (staleLoader, staleStarted, staleRelease) = blockingLoader(ciphertext)
        val retryLoader = mockk<BlobLoader>(relaxed = true)
        var partLengthSeenByRetry = -1L
        every { retryLoader.loadToFile(any(), any()) } answers {
            // A real loader confirms a complete part file with one request and downloads nothing.
            partLengthSeenByRetry = secondArg<File>().length()
            true
        }
        extraLoaders.addAll(listOf(staleLoader, retryLoader))
        val stale = downloadAsync(1)
        assertTrue(staleStarted.await(10, TimeUnit.SECONDS))
        assertTrue(downloadService.cancel(1))
        staleRelease.countDown()
        assertNull(stale.get(10, TimeUnit.SECONDS))
        assertFalse(temporaryFile().exists(), "a cancelled download does not move its part file into place")

        assertContentEquals(ciphertext, download(1))
        assertEquals(ciphertext.size.toLong(), partLengthSeenByRetry)
    }

    @Test
    fun `a retry that gives up waiting for a stuck cancelled attempt never touches the part file, and the next still waits`() {
        val (staleLoader, staleStarted, staleRelease) = blockingLoader(null)
        val laterLoader = mockk<BlobLoader>(relaxed = true)
        every { laterLoader.loadToFile(any(), any()) } answers { writePart(secondArg(), ciphertext.clone()) }
        extraLoaders.addAll(listOf(staleLoader, laterLoader, laterLoader, laterLoader))
        val stale = downloadAsync(1)
        assertTrue(staleStarted.await(10, TimeUnit.SECONDS))
        assertTrue(downloadService.cancel(1))
        downloadService.joinTimeoutMillis = 50

        assertNull(download(2))
        assertNull(download(3), "the stuck attempt is still the part file's writer")
        verify(exactly = 0) { laterLoader.loadToFile(any(), any()) }

        staleRelease.countDown()
        assertNull(stale.get(10, TimeUnit.SECONDS))
        assertContentEquals(ciphertext, download(4))
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
        staleRelease.countDown()
        assertNull(stale.get(10, TimeUnit.SECONDS))
        assertTrue(retryStarted.await(10, TimeUnit.SECONDS))
        val joiner = downloadOnNamedThread(2)
        awaitCondition("the joiner is registered on the retry") { downloadService.registeredReaders(blobId) == 1 }
        retryRelease.countDown()
        assertContentEquals(ciphertext, retry.get(10, TimeUnit.SECONDS))
        assertTrue(awake.await(10, TimeUnit.SECONDS))

        // Only now does the cancelled attempt's caller report its error; then the retry's caller completes.
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

    @Test
    fun `a cancelled attempt's error report never removes the retry of the same message`() {
        val (staleLoader, staleStarted, staleRelease) = blockingLoader(null)
        val (retryLoader, retryStarted, retryRelease) = blockingLoader(ciphertext)
        extraLoaders.addAll(listOf(staleLoader, retryLoader))
        val stale = downloadAsync(1)
        assertTrue(staleStarted.await(10, TimeUnit.SECONDS))
        assertTrue(downloadService.cancel(1))
        val retry = downloadAsync(1)

        staleRelease.countDown()
        assertNull(stale.get(10, TimeUnit.SECONDS))
        assertTrue(retryStarted.await(10, TimeUnit.SECONDS))
        // What the stale attempt's caller does with its null result.
        downloadService.error(1)
        assertTrue(downloadService.isDownloading(1), "the retry is still in flight and not the reporter's")

        retryRelease.countDown()
        assertContentEquals(ciphertext, retry.get(10, TimeUnit.SECONDS))
        assertContentEquals(ciphertext, temporaryFile().readBytes())
    }

    @Test
    fun `a part file that cannot be written fails the download without leaving a registration`() {
        blobLoader.loads(ciphertext)
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
        every { blobLoader.loadToFile(any(), any()) } throws OutOfMemoryError("test")

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
