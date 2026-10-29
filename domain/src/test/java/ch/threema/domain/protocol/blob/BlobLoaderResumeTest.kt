package ch.threema.domain.protocol.blob

import ch.threema.base.ProgressListener
import ch.threema.domain.protocol.ServerAddressProvider
import ch.threema.domain.protocol.Version
import ch.threema.domain.protocol.urls.BlobUrl
import ch.threema.testhelpers.createTempDirectory
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer

/**
 * A blob download continues the part file an earlier attempt left behind, and never appends anything that is not
 * exactly the rest of the same blob.
 */
class BlobLoaderResumeTest {
    private lateinit var directory: File
    private lateinit var partFile: File
    private val blob = ByteArray(200_000) { (it % 251).toByte() }
    private val blobId = ByteArray(16) { it.toByte() }
    private val requests = CopyOnWriteArrayList<Request>()

    @Volatile
    private var lastCall: okhttp3.Call? = null
    private val progress = CopyOnWriteArrayList<Int>()
    private val finished = CopyOnWriteArrayList<Boolean>()
    private var noProgress = false
    private val executor = Executors.newSingleThreadExecutor()

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("blob-resume")
        partFile = File(directory, "blob.part")
    }

    @AfterTest
    fun tearDown() {
        executor.shutdownNow()
        directory.deleteRecursively()
    }

    private fun loader(respond: (Request) -> Response): BlobLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requests.add(chain.request())
                    lastCall = chain.call()
                    respond(chain.request())
                },
            )
            .build()
        val serverAddressProvider = mockk<ServerAddressProvider> {
            every { getBlobServerDownloadUrl() } returns BlobUrl("https://blob.example.invalid/{blobId}")
        }
        val listener = object : ProgressListener {
            override fun updateProgress(progress: Int) {
                this@BlobLoaderResumeTest.progress.add(progress)
            }

            override fun noProgressAvailable() {
                noProgress = true
            }

            override fun onFinished(success: Boolean) {
                finished.add(success)
            }
        }
        return BlobLoader.usual(client, blobId, Version(), serverAddressProvider, listener)
    }

    private fun Request.respond(code: Int, body: ResponseBody, contentRange: String? = null): Response =
        Response.Builder()
            .request(this)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .apply { if (contentRange != null) header("Content-Range", contentRange) }
            .body(body)
            .build()

    /** A server with Range support, answering like the blob server does. */
    private fun rangeServer(content: ByteArray = blob): (Request) -> Response = { request ->
        val range = request.header("Range")
        if (range == null) {
            request.respond(200, content.toResponseBody(OCTET_STREAM))
        } else {
            val start = Regex("""bytes=(\d+)-""").matchEntire(range)!!.groupValues[1].toInt()
            if (start >= content.size) {
                request.respond(416, ByteArray(0).toResponseBody(), "bytes */${content.size}")
            } else {
                request.respond(
                    206,
                    content.copyOfRange(start, content.size).toResponseBody(OCTET_STREAM),
                    "bytes $start-${content.size - 1}/${content.size}",
                )
            }
        }
    }

    private fun load(loader: BlobLoader) = loader.loadToFile(BlobScope.Public, partFile)

    @Test
    fun `a first download sends no range and writes the whole blob`() {
        assertTrue(load(loader(rangeServer())))

        assertContentEquals(blob, partFile.readBytes())
        assertNull(requests.single().header("Range"))
        assertEquals(100, progress.last())
        assertEquals(listOf(true), finished)
    }

    @Test
    fun `an existing part file is continued with a range request from its end`() {
        partFile.writeBytes(blob.copyOfRange(0, 150_000))

        assertTrue(load(loader(rangeServer())))

        assertContentEquals(blob, partFile.readBytes())
        assertEquals("bytes=150000-", requests.single().header("Range"))
        assertTrue(progress.first() >= 75, "progress continues from the bytes already there: $progress")
    }

    @Test
    fun `a server that ignores the range sends the whole blob and the part file starts over`() {
        partFile.writeBytes(blob.copyOfRange(0, 150_000))

        assertTrue(load(loader { request -> request.respond(200, blob.toResponseBody(OCTET_STREAM)) }))

        assertContentEquals(blob, partFile.readBytes(), "nothing may be appended to the old part")
    }

    @Test
    fun `a range that does not start at the end of the part file is never appended`() {
        partFile.writeBytes(blob.copyOfRange(0, 150_000))
        val server = rangeServer()

        assertTrue(
            load(
                loader { request ->
                    if (request.header("Range") != null) {
                        request.respond(206, blob.copyOfRange(100_000, blob.size).toResponseBody(), "bytes 100000-199999/200000")
                    } else {
                        server(request)
                    }
                },
            ),
        )

        assertContentEquals(blob, partFile.readBytes())
        assertEquals(listOf("bytes=150000-", null), requests.map { it.header("Range") })
    }

    @Test
    fun `a range whose length disagrees with its Content-Range is never appended`() {
        partFile.writeBytes(blob.copyOfRange(0, 150_000))
        val server = rangeServer()

        load(
            loader { request ->
                if (request.header("Range") != null) {
                    request.respond(206, ByteArray(60_000).toResponseBody(), "bytes 150000-199999/200000")
                } else {
                    server(request)
                }
            },
        )

        assertContentEquals(blob, partFile.readBytes())
        assertEquals(2, requests.size)
    }

    @Test
    fun `a part file that already holds the whole blob is confirmed by a 416 and not downloaded again`() {
        partFile.writeBytes(blob)

        assertTrue(load(loader(rangeServer())))

        assertContentEquals(blob, partFile.readBytes())
        assertEquals("bytes=200000-", requests.single().header("Range"))
    }

    @Test
    fun `a part file longer than the blob is replaced by the whole blob`() {
        partFile.writeBytes(blob + ByteArray(10))

        assertTrue(load(loader(rangeServer())))

        assertContentEquals(blob, partFile.readBytes())
        assertEquals(listOf("bytes=200010-", null), requests.map { it.header("Range") })
    }

    @Test
    fun `a server that keeps answering an unusable range fails after one restart`() {
        partFile.writeBytes(blob.copyOfRange(0, 150_000))

        assertFailsWith<IOException> {
            load(loader { request -> request.respond(206, ByteArray(10).toResponseBody(), "bytes 0-9/10") })
        }
        assertEquals(2, requests.size)
        assertFalse(partFile.exists())
        assertEquals(listOf(false), finished)
    }

    @Test
    fun `a blob that is gone deletes the part file`() {
        partFile.writeBytes(blob.copyOfRange(0, 150_000))

        assertFailsWith<IOException> { load(loader { request -> request.respond(404, ByteArray(0).toResponseBody()) }) }
        assertFalse(partFile.exists())
    }

    @Test
    fun `a server error keeps the part file for the next attempt`() {
        partFile.writeBytes(blob.copyOfRange(0, 150_000))

        assertFailsWith<IOException> { load(loader { request -> request.respond(503, ByteArray(0).toResponseBody()) }) }
        assertEquals(150_000, partFile.length())
    }

    @Test
    fun `a connection that breaks keeps what arrived, and the next attempt continues from there`() {
        val breaking = loader { request ->
            request.respond(200, BreakingBody(blob, breakAfter = 120_000))
        }
        assertFailsWith<IOException> { load(breaking) }
        assertEquals(120_000, partFile.length())

        assertTrue(load(loader(rangeServer())))
        assertContentEquals(blob, partFile.readBytes())
        assertEquals("bytes=120000-", requests.last().header("Range"))
    }

    @Test
    fun `a body that ends before the announced length fails and keeps what arrived`() {
        assertFailsWith<IOException> {
            load(loader { request -> request.respond(200, ShortBody(blob.copyOfRange(0, 1_000), announced = blob.size.toLong())) })
        }
        assertEquals(1_000, partFile.length())
    }

    @Test
    fun `cancelling before the download starts sends no request and keeps the part file`() {
        partFile.writeBytes(blob.copyOfRange(0, 150_000))
        val loader = loader(rangeServer())
        loader.cancelDownload()

        assertFalse(load(loader))
        assertTrue(requests.isEmpty())
        assertEquals(150_000, partFile.length())
        assertEquals(listOf(false), finished)
    }

    @Test
    fun `cancelling aborts a read that is blocked on a stalled connection and keeps the part file`() {
        partFile.writeBytes(blob.copyOfRange(0, 150_000))
        val reading = CountDownLatch(1)
        val loader = loader { request ->
            request.respond(206, StalledBody(reading) { lastCall!!.isCanceled() }, "bytes 150000-199999/200000")
        }

        val result = executor.submit<Boolean> { load(loader) }
        assertTrue(reading.await(10, TimeUnit.SECONDS))
        loader.cancelDownload()

        // Well before the stalled read would give up by itself (10 s): the cancel must abort the call.
        assertFalse(result.get(3, TimeUnit.SECONDS), "a cancelled download is not a failure")
        assertEquals(150_000, partFile.length())
    }

    @Test
    fun `an unknown length is read to the end without progress`() {
        assertTrue(load(loader { request -> request.respond(200, ShortBody(blob, announced = -1)) }))

        assertContentEquals(blob, partFile.readBytes())
        assertTrue(noProgress)
    }

    @Test
    fun `a resumed download that breaks again is continued again, and the result is the blob`() {
        assertFailsWith<IOException> { load(loader { request -> request.respond(200, BreakingBody(blob, breakAfter = 50_000)) }) }
        assertContentEquals(blob.copyOfRange(0, 50_000), partFile.readBytes())

        assertFailsWith<IOException> {
            load(
                loader { request ->
                    assertEquals("bytes=50000-", request.header("Range"))
                    request.respond(206, BreakingBody(blob.copyOfRange(50_000, blob.size), breakAfter = 70_000), "bytes 50000-199999/200000")
                },
            )
        }
        assertContentEquals(blob.copyOfRange(0, 120_000), partFile.readBytes(), "the part file is always a prefix of the blob")

        assertTrue(load(loader(rangeServer())))
        assertContentEquals(blob, partFile.readBytes())
        assertEquals("bytes=120000-", requests.last().header("Range"))
    }

    @Test
    fun `a continuation that ends early keeps the longer prefix for the next attempt`() {
        partFile.writeBytes(blob.copyOfRange(0, 150_000))

        assertFailsWith<IOException> {
            load(
                loader { request ->
                    request.respond(
                        206,
                        ShortBody(blob.copyOfRange(150_000, 170_000), announced = 50_000),
                        "bytes 150000-199999/200000",
                    )
                },
            )
        }

        assertContentEquals(blob.copyOfRange(0, 170_000), partFile.readBytes())
    }

    @Test
    fun `cancelling in the middle of the body keeps an exact prefix and is not a failure`() {
        lateinit var loader: BlobLoader
        loader = loader { request ->
            request.respond(200, BreakingBody(blob, breakAfter = blob.size, onRead = { position -> if (position >= 64_000) loader.cancelDownload() }))
        }

        assertFalse(load(loader))

        val kept = partFile.readBytes()
        assertTrue(kept.size in 64_000 until blob.size, "kept ${kept.size} bytes")
        assertContentEquals(blob.copyOfRange(0, kept.size), kept)
        assertEquals(listOf(false), finished)
    }

    @Test
    fun `the unusable answer is closed before the whole blob is requested`() {
        partFile.writeBytes(blob.copyOfRange(0, 150_000))
        val unusable = BreakingBody(ByteArray(10), breakAfter = 10)
        val server = rangeServer()

        assertTrue(
            load(
                loader { request ->
                    if (request.header("Range") != null) {
                        request.respond(206, unusable, "bytes 0-9/10")
                    } else {
                        assertTrue(unusable.closed, "the unusable answer must be closed before the whole blob is requested")
                        server(request)
                    }
                },
            ),
        )
        assertTrue(unusable.closed)
    }

    // The decision and the writing alone.

    @Test
    fun `decide - a whole-blob answer starts over, whatever was asked`() {
        assertEquals(BlobLoader.ResumeDecision.Write(0, 200), BlobLoader.decideResume(0, 200, null, 200))
        assertEquals(BlobLoader.ResumeDecision.Write(0, -1), BlobLoader.decideResume(50, 200, null, -1))
    }

    @Test
    fun `decide - only the exact rest of the blob is continued`() {
        assertEquals(BlobLoader.ResumeDecision.Write(50, 200), BlobLoader.decideResume(50, 206, "bytes 50-199/200", 150))
        assertEquals(BlobLoader.ResumeDecision.Write(50, 200), BlobLoader.decideResume(50, 206, "bytes 50-199/200", -1))
        assertEquals(BlobLoader.ResumeDecision.Restart, BlobLoader.decideResume(50, 206, "bytes 51-199/200", 149))
        assertEquals(BlobLoader.ResumeDecision.Restart, BlobLoader.decideResume(50, 206, "bytes 50-150/200", 101))
        assertEquals(BlobLoader.ResumeDecision.Restart, BlobLoader.decideResume(50, 206, "bytes 50-199/200", 100))
        assertEquals(BlobLoader.ResumeDecision.Restart, BlobLoader.decideResume(50, 206, "bytes 50-199/*", 150))
        assertEquals(BlobLoader.ResumeDecision.Restart, BlobLoader.decideResume(50, 206, null, 150))
        assertEquals(BlobLoader.ResumeDecision.Restart, BlobLoader.decideResume(0, 206, "bytes 0-199/200", 200))
    }

    @Test
    fun `decide - an unsatisfiable range means complete only when the total is the part length`() {
        assertEquals(BlobLoader.ResumeDecision.AlreadyComplete, BlobLoader.decideResume(200, 416, "bytes */200", 0))
        assertEquals(BlobLoader.ResumeDecision.Restart, BlobLoader.decideResume(210, 416, "bytes */200", 0))
        assertEquals(BlobLoader.ResumeDecision.Restart, BlobLoader.decideResume(200, 416, null, 0))
    }

    @Test
    fun `decide - an empty or padded range`() {
        assertEquals(BlobLoader.ResumeDecision.Restart, BlobLoader.decideResume(200, 206, "bytes 200-199/200", 0))
        assertEquals(BlobLoader.ResumeDecision.Write(50, 200), BlobLoader.decideResume(50, 206, " bytes 50-199/200 ", 150))
    }

    @Test
    fun `decide - gone and other codes`() {
        assertEquals(BlobLoader.ResumeDecision.Gone, BlobLoader.decideResume(50, 404, null, 0))
        assertEquals(BlobLoader.ResumeDecision.Failed(500), BlobLoader.decideResume(50, 500, null, 0))
        assertEquals(BlobLoader.ResumeDecision.Failed(204), BlobLoader.decideResume(0, 204, null, 0))
    }

    @Test
    fun `write - more bytes than announced make the part file unusable`() {
        assertIs<BlobLoader.UnusablePartFileException>(
            assertFailsWith<IOException> { write(Buffer().write(ByteArray(11)), offset = 0, total = 10) },
        )
    }

    @Test
    fun `write - a part file that changed size since it was measured is never appended to`() {
        partFile.writeBytes(ByteArray(20))

        assertFailsWith<BlobLoader.UnusablePartFileException> { write(Buffer().write(ByteArray(10)), offset = 10, total = 30) }
        assertEquals(20, partFile.length())
    }

    @Test
    fun `write - a blob too large for one array is refused`() {
        assertFailsWith<BlobLoader.UnusablePartFileException> { write(Buffer(), offset = 0, total = Int.MAX_VALUE.toLong() + 1) }
    }

    private fun write(source: BufferedSource, offset: Long, total: Long) =
        BlobLoader.writeToPartFile(source, partFile, offset, total, isCancelled = { false }, onProgress = {})

    /**
     * Delivers [content] up to [breakAfter] bytes, then the connection breaks (or, at the end of the content, it ends).
     * [onRead] sees the position after each read.
     */
    private class BreakingBody(
        private val content: ByteArray,
        private val breakAfter: Int,
        private val onRead: (Int) -> Unit = {},
    ) : ResponseBody() {
        @Volatile
        var closed = false

        override fun contentType() = OCTET_STREAM
        override fun contentLength() = content.size.toLong()
        override fun source(): BufferedSource = object : Source {
            private var position = 0
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (position >= content.size) {
                    return -1
                }
                if (position >= breakAfter) {
                    throw IOException("connection reset")
                }
                val count = minOf(byteCount.toInt(), breakAfter - position, 8192)
                sink.write(content, position, count)
                position += count
                onRead(position)
                return count.toLong()
            }

            override fun timeout() = Timeout.NONE
            override fun close() {
                closed = true
            }
        }.buffer()

        override fun close() {
            closed = true
            super.close()
        }
    }

    /** Delivers [content] and then ends, while announcing [announced] bytes (-1 = unknown). */
    private class ShortBody(private val content: ByteArray, private val announced: Long) : ResponseBody() {
        override fun contentType() = OCTET_STREAM
        override fun contentLength() = announced
        override fun source(): BufferedSource = Buffer().write(content)
    }

    /** Never delivers a byte until the call is cancelled; then fails like a cancelled socket read. */
    private class StalledBody(private val reading: CountDownLatch, private val isCanceled: () -> Boolean) : ResponseBody() {
        override fun contentType() = OCTET_STREAM
        override fun contentLength() = 50_000L
        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reading.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!isCanceled() && System.nanoTime() < deadline) {
                    Thread.sleep(5)
                }
                throw IOException("Canceled")
            }

            override fun timeout() = Timeout.NONE
            override fun close() {}
        }.buffer()
    }

    companion object {
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
