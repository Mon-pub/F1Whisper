package ch.threema.domain.protocol.blob

import ch.threema.base.ProgressListener
import ch.threema.base.ThreemaException
import ch.threema.base.utils.getThreemaLogger
import ch.threema.common.Http
import ch.threema.common.buildNew
import ch.threema.common.buildRequest
import ch.threema.common.execute
import ch.threema.common.toHexString
import ch.threema.domain.protocol.ServerAddressProvider
import ch.threema.domain.protocol.Version
import ch.threema.domain.protocol.connection.d2m.MultiDevicePropertyProvider
import ch.threema.domain.protocol.connection.data.leBytes
import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.domain.protocol.getUserAgent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource

private val logger = getThreemaLogger("BlobLoader")

/**
 * Helper class that loads blobs (images, videos etc.) from the blob server given a blob ID. No
 * processing is done on the loaded data; any decryption etc. must be done separately.
 *
 * It can target both the default blob server and the mirror blob server for multi-device sessions.
 *
 * TODO(ANDR-2869): Rework exception handling (and maybe return types)
 */
class BlobLoader private constructor(
    private val baseOkHttpClient: OkHttpClient,
    private val blobId: ByteArray,
    private val version: Version,
    private val useMirror: Boolean,
    private val serverAddressProvider: ServerAddressProvider,
    @JvmField var progressListener: ProgressListener?,
    // used only for mirror blob sever requests:
    private val multiDevicePropertyProvider: MultiDevicePropertyProvider?,
) {
    @Volatile
    private var isCancelled = false

    @Volatile
    private var currentCall: Call? = null

    /** F1-PATCH: what a response to a (possibly ranged) blob request means for the partial download file. */
    internal sealed interface ResumeDecision {
        /** Write the body into the part file from [offset] (0 = from scratch); [total] is -1 when unknown. */
        data class Write(val offset: Long, val total: Long) : ResumeDecision

        /** The part file already holds the whole blob. */
        data object AlreadyComplete : ResumeDecision

        /** The part file does not match the blob: delete it and request the whole blob. */
        data object Restart : ResumeDecision

        /** The blob no longer exists on the server. */
        data object Gone : ResumeDecision

        data class Failed(val code: Int) : ResumeDecision
    }

    /** F1-PATCH: the part file cannot be continued (it does not match the blob); it is deleted. */
    internal class UnusablePartFileException(message: String) : IOException(message)

    companion object {
        private const val BUFFER_SIZE = 8192
        private const val PART_FILE_BUFFER_SIZE = 64 * 1024
        private const val HEADER_RANGE = "Range"
        private const val HEADER_CONTENT_RANGE = "Content-Range"
        private const val STATUS_PARTIAL_CONTENT = 206
        private const val STATUS_RANGE_NOT_SATISFIABLE = 416
        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+)""")
        private val UNSATISFIED_CONTENT_RANGE = Regex("""bytes \*/(\d+)""")

        /**
         * F1-PATCH: a 206 is only continued when it starts exactly at the end of the part file and runs to the end of the
         * blob; anything else (another range, a server that answers the whole blob) never gets appended to it.
         */
        internal fun decideResume(requestedOffset: Long, code: Int, contentRange: String?, contentLength: Long): ResumeDecision =
            when (code) {
                Http.StatusCode.OK -> ResumeDecision.Write(offset = 0, total = contentLength)
                STATUS_PARTIAL_CONTENT -> {
                    val range = contentRange?.let { CONTENT_RANGE.matchEntire(it.trim()) }?.groupValues
                    val start = range?.get(1)?.toLongOrNull()
                    val end = range?.get(2)?.toLongOrNull()
                    val total = range?.get(3)?.toLongOrNull()
                    if (start != null &&
                        end != null &&
                        total != null &&
                        requestedOffset > 0 &&
                        start == requestedOffset &&
                        end == total - 1 &&
                        total > start &&
                        (contentLength == -1L || contentLength == total - start)
                    ) {
                        ResumeDecision.Write(offset = start, total = total)
                    } else {
                        ResumeDecision.Restart
                    }
                }
                STATUS_RANGE_NOT_SATISFIABLE -> {
                    val total = contentRange?.let { UNSATISFIED_CONTENT_RANGE.matchEntire(it.trim()) }?.groupValues?.get(1)?.toLongOrNull()
                    if (requestedOffset > 0 && total == requestedOffset) ResumeDecision.AlreadyComplete else ResumeDecision.Restart
                }
                Http.StatusCode.NOT_FOUND -> ResumeDecision.Gone
                else -> ResumeDecision.Failed(code)
            }

        /**
         * F1-PATCH: writes [source] into [partFile] from [offset] on. Returns false when cancelled (the bytes so far stay).
         * A body that ends early fails with the bytes so far kept, so the next attempt continues from there.
         */
        @Throws(IOException::class)
        internal fun writeToPartFile(
            source: BufferedSource,
            partFile: File,
            offset: Long,
            total: Long,
            isCancelled: () -> Boolean,
            onProgress: (Int) -> Unit,
        ): Boolean {
            if (total > Int.MAX_VALUE) {
                throw UnusablePartFileException("Unsupported blob length $total")
            }
            FileOutputStream(partFile, offset > 0).use { output ->
                if (output.channel.size() != offset) {
                    throw UnusablePartFileException("Part file has ${output.channel.size()} bytes, expected $offset")
                }
                val buffer = ByteArray(PART_FILE_BUFFER_SIZE)
                var written = offset
                var reportedPercent = -1
                while (!isCancelled()) {
                    val read = try {
                        source.read(buffer)
                    } catch (e: IOException) {
                        if (isCancelled()) {
                            return false
                        }
                        throw e
                    }
                    if (read == -1) {
                        break
                    }
                    if ((total >= 0 && written + read > total) || written + read > Int.MAX_VALUE) {
                        throw UnusablePartFileException("More bytes than announced: ${written + read} of $total")
                    }
                    output.write(buffer, 0, read)
                    written += read
                    if (total > 0) {
                        val percent = (100 * written / total).toInt()
                        if (percent != reportedPercent) {
                            reportedPercent = percent
                            onProgress(percent)
                        }
                    }
                }
                if (isCancelled()) {
                    return false
                }
                if (total >= 0 && written != total) {
                    throw IOException("Blob download ended at byte $written of $total")
                }
                return true
            }
        }

        // F1-PATCH: with a known length the blob is read straight into one array of exactly that size. A growing
        // ByteArrayOutputStream plus its final copy needed more than twice the blob size in heap, which a large file
        // does not leave on many phones.
        @Throws(IOException::class)
        internal fun readKnownLengthBlob(
            source: BufferedSource,
            length: Long,
            isCancelled: () -> Boolean,
            onProgress: (Int) -> Unit,
        ): ByteArray? {
            if (length < 0 || length > Int.MAX_VALUE) {
                throw IOException("Unsupported blob length $length")
            }
            val blobData = try {
                ByteArray(length.toInt())
            } catch (_: OutOfMemoryError) {
                throw IOException("Out of memory on allocate")
            }
            var offset = 0
            while (!isCancelled() && offset < blobData.size) {
                val read = source.read(blobData, offset, minOf(BUFFER_SIZE, blobData.size - offset))
                if (read == -1) {
                    break
                }
                offset += read
                onProgress((100f * offset / length).toInt())
            }
            if (isCancelled()) {
                return null
            }
            if (offset != blobData.size || !source.exhausted()) {
                throw IOException("Unexpected read size. current: $offset, expected: $length")
            }
            return blobData
        }

        @JvmStatic
        fun usual(
            baseOkHttpClient: OkHttpClient,
            blobId: ByteArray,
            version: Version,
            serverAddressProvider: ServerAddressProvider,
            progressListener: ProgressListener?,
        ) = BlobLoader(
            baseOkHttpClient = baseOkHttpClient,
            blobId = blobId,
            version = version,
            useMirror = false,
            serverAddressProvider = serverAddressProvider,
            progressListener = progressListener,
            multiDevicePropertyProvider = null,
        )

        @JvmStatic
        fun mirror(
            baseOkHttpClient: OkHttpClient,
            blobId: ByteArray,
            version: Version,
            serverAddressProvider: ServerAddressProvider,
            progressListener: ProgressListener?,
            multiDevicePropertyProvider: MultiDevicePropertyProvider,
        ) = BlobLoader(
            baseOkHttpClient = baseOkHttpClient,
            blobId = blobId,
            version = version,
            useMirror = true,
            serverAddressProvider = serverAddressProvider,
            progressListener = progressListener,
            multiDevicePropertyProvider = multiDevicePropertyProvider,
        )
    }

    /**
     * Attempt to load the given blob.
     *
     * @param scope Sets the passed scope when downloading the data from the multi-device blob mirror server.
     * Will have no effect if multi-device is not active.
     *
     * @return blob data or null if download was cancelled
     */
    @Throws(IOException::class, ThreemaException::class)
    fun load(scope: BlobScope): ByteArray? {
        isCancelled = false

        val blobResult: BufferedSourceWithLength = requestBlob(scope)

        var read: Int = -1
        val buffer = ByteArray(BUFFER_SIZE)
        val bos = ByteArrayOutputStream()

        blobResult.source.use { source ->
            if (blobResult.containsContent()) {
                logger.debug("Blob content length is {}", blobResult.length)

                val blobData = try {
                    readKnownLengthBlob(
                        source = source,
                        length = blobResult.length,
                        isCancelled = { isCancelled },
                        onProgress = { progressListener?.updateProgress(it) },
                    )
                } catch (e: IOException) {
                    progressListener?.onFinished(false)
                    throw e
                }
                if (blobData == null) {
                    logger.info("Blob load cancelled")
                    progressListener?.onFinished(false)
                    return null
                }
                logger.info("Blob load complete ({} bytes received)", blobData.size)
                progressListener?.onFinished(true)
                return blobData
            } else {
                logger.debug("Blob content length is unknown")

                progressListener?.noProgressAvailable()

                while (!isCancelled && (blobResult.source.read(buffer).also { read = it }) != -1) {
                    bos.write(buffer, 0, read)
                }

                if (isCancelled) {
                    logger.info("Blob load cancelled")
                    progressListener?.onFinished(false)
                    return null
                }
            }
        }

        val blobData = bos.toByteArray()
        logger.info("Blob load complete ({} bytes received)", blobData.size)

        progressListener?.onFinished(true)

        return blobData
    }

    /**
     * F1-PATCH: downloads the blob into [partFile], continuing a part file left by an earlier attempt with a range
     * request. Upstream always started from byte 0, so a large download that broke had to start over.
     *
     * The part file holds ciphertext only. It is kept when the download is cancelled or the connection breaks, and
     * deleted when it does not match the blob or the blob is gone.
     *
     * @return true when [partFile] holds the whole blob, false when the download was cancelled
     */
    @Throws(IOException::class, ThreemaException::class)
    fun loadToFile(scope: BlobScope, partFile: File): Boolean {
        // Unlike load(), this does not reset isCancelled: a loader serves one download, and a cancel that arrives before
        // the download starts must not be lost.
        try {
            val complete = resumeIntoPartFile(scope, partFile)
            if (complete) {
                logger.info("Blob load complete ({} bytes)", partFile.length())
            } else {
                logger.info("Blob load cancelled")
            }
            progressListener?.onFinished(complete)
            return complete
        } catch (e: IOException) {
            progressListener?.onFinished(false)
            throw e
        } catch (e: ThreemaException) {
            progressListener?.onFinished(false)
            throw e
        }
    }

    @Throws(IOException::class, ThreemaException::class)
    private fun resumeIntoPartFile(scope: BlobScope, partFile: File): Boolean {
        val blobUrl: URL = getBlobDownloadUrl(blobId, scope)
        val okHttpClientLoad = baseOkHttpClient.buildNew {
            connectTimeout(ProtocolDefines.BLOB_CONNECT_TIMEOUT.seconds)
            readTimeout(ProtocolDefines.BLOB_LOAD_TIMEOUT.seconds)
        }
        var restarted = false
        while (true) {
            if (isCancelled) {
                return false
            }
            val offset = if (partFile.isFile) partFile.length() else 0L
            val request = buildRequest {
                get()
                url(blobUrl)
                header(Http.Header.USER_AGENT, getUserAgent(version))
                if (offset > 0) {
                    header(HEADER_RANGE, "bytes=$offset-")
                }
            }
            logger.info("Loading blob from {} at byte {}", blobUrl.host, offset)
            val call = okHttpClientLoad.newCall(request)
            currentCall = call
            if (isCancelled) {
                call.cancel()
            }
            val response = try {
                call.execute()
            } catch (e: IOException) {
                if (isCancelled) {
                    return false
                }
                throw e
            }
            response.use {
                val body = response.body
                when (val decision = decideResume(offset, response.code, response.header(HEADER_CONTENT_RANGE), body.contentLength())) {
                    is ResumeDecision.Write -> {
                        if (offset > 0) {
                            if (decision.offset == 0L) {
                                logger.info("Blob server sent the whole blob, starting over")
                            } else {
                                logger.info("Resuming blob download at byte {} of {}", decision.offset, decision.total)
                            }
                        }
                        if (decision.total < 0) {
                            progressListener?.noProgressAvailable()
                        }
                        return try {
                            writeToPartFile(
                                source = body.source(),
                                partFile = partFile,
                                offset = decision.offset,
                                total = decision.total,
                                isCancelled = { isCancelled },
                                onProgress = { progressListener?.updateProgress(it) },
                            )
                        } catch (e: UnusablePartFileException) {
                            deletePartFile(partFile)
                            throw e
                        }
                    }
                    ResumeDecision.AlreadyComplete -> {
                        logger.info("Blob part file already holds the whole blob")
                        progressListener?.updateProgress(100)
                        return true
                    }
                    ResumeDecision.Restart -> {
                        logger.warn("Blob server answered {} with an unusable range for byte {}", response.code, offset)
                        deletePartFile(partFile)
                        if (restarted) {
                            throw IOException("Blob server answered an unusable range twice")
                        }
                        restarted = true
                    }
                    ResumeDecision.Gone -> {
                        logger.error("Blob no longer exists on the server")
                        deletePartFile(partFile)
                        throw IOException("download request failed with code ${response.code}")
                    }
                    is ResumeDecision.Failed -> {
                        logger.error("Blob download failed. HTTP response code {}", decision.code)
                        throw IOException("download request failed with code ${decision.code}")
                    }
                }
            }
        }
    }

    private fun deletePartFile(partFile: File) {
        if (partFile.exists() && !partFile.delete()) {
            logger.warn("Could not delete the blob part file")
        }
    }

    /**
     * Hands through exceptions from [getBlobDownloadUrl]
     */
    @Throws(IOException::class, ThreemaException::class)
    private fun requestBlob(scope: BlobScope): BufferedSourceWithLength {
        val blobUrl: URL = getBlobDownloadUrl(blobId, scope)

        val okHttpClientLoad = baseOkHttpClient.buildNew {
            connectTimeout(ProtocolDefines.BLOB_CONNECT_TIMEOUT.seconds)
            readTimeout(ProtocolDefines.BLOB_LOAD_TIMEOUT.seconds)
        }

        val request = buildRequest {
            get()
            url(blobUrl)
            header(Http.Header.USER_AGENT, getUserAgent(version))
        }

        logger.info("Loading blob from {}", blobUrl.host)

        val response = okHttpClientLoad.execute(request)
        if (!response.isSuccessful) {
            logger.error("Blob download failed. HTTP response code not in range 200..299")
            throw IOException("download request failed with code ${response.code}")
        }

        val responseBody = response.body
        return BufferedSourceWithLength(
            responseBody.source(),
            responseBody.contentLength(),
        )
    }

    /**
     *  @param scope Sets the passed scope when marking the blob as done on the multi device blob mirror server.
     *  Will have no effect if multi-device is not active.
     */
    fun markAsDone(blobId: ByteArray, scope: BlobScope) {
        try {
            val blobDoneUrl: URL = getBlobDoneUrl(blobId, scope)

            val request = buildRequest {
                post("".toRequestBody(null))
                url(blobDoneUrl)
                header(Http.Header.USER_AGENT, getUserAgent(version))
            }

            baseOkHttpClient.execute(request).use { response ->
                if (!response.isSuccessful) {
                    logger.warn("Marking blob as done failed. HTTP response code: {}", response.code)
                }
            }
        } catch (exception: IOException) {
            logger.warn("Marking blob as done failed", exception)
        } catch (exception: ThreemaException) {
            logger.warn("Marking blob as done failed", exception)
        }
    }

    /**
     * Cancel a download in progress. load() will return null.
     */
    fun cancelDownload() {
        isCancelled = true
        // F1-PATCH: a read blocked on a stalled connection stops at once, which also frees the part file for a retry.
        currentCall?.cancel()
    }

    @Throws(ThreemaException::class)
    private fun getBlobDownloadUrl(blobId: ByteArray, scope: BlobScope): URL {
        if (useMirror) {
            if (multiDevicePropertyProvider == null) {
                throw ThreemaException("Missing argument")
            }
            val urlWithoutQueryParameters = serverAddressProvider.getBlobMirrorServerDownloadUrl(multiDevicePropertyProvider).get(blobId)
            return URL(
                appendMirrorQueryParameters(
                    urlWithoutQueryParameters,
                    multiDevicePropertyProvider,
                    scope,
                ),
            )
        } else {
            return URL(serverAddressProvider.getBlobServerDownloadUrl().get(blobId))
        }
    }

    @Throws(ThreemaException::class)
    private fun getBlobDoneUrl(blobId: ByteArray, scope: BlobScope): URL {
        if (useMirror) {
            if (multiDevicePropertyProvider == null) {
                throw ThreemaException("Missing argument")
            }
            val urlWithoutQueryParameters = serverAddressProvider.getBlobMirrorServerDoneUrl(multiDevicePropertyProvider).get(blobId)
            return URL(
                appendMirrorQueryParameters(
                    urlWithoutQueryParameters,
                    multiDevicePropertyProvider,
                    scope,
                ),
            )
        } else {
            return URL(serverAddressProvider.getBlobServerDoneUrl().get(blobId))
        }
    }

    /**
     * @param rawUrl A url string **without** any query parameters. The value of this will not be mutated.
     */
    @Throws(ThreemaException::class)
    private fun appendMirrorQueryParameters(
        rawUrl: String,
        multiDevicePropertyProvider: MultiDevicePropertyProvider,
        scope: BlobScope,
    ): String {
        val deviceIdHex = multiDevicePropertyProvider.get().mediatorDeviceId.leBytes().toHexString()
        val deviceGroupIdHex = multiDevicePropertyProvider.get().keys.dgid.toHexString()
        return "$rawUrl?deviceId=$deviceIdHex&deviceGroupId=$deviceGroupIdHex&scope=${scope.name}"
    }

    private data class BufferedSourceWithLength(
        val source: BufferedSource,
        val length: Long,
    ) {
        fun containsContent(): Boolean = length != -1L
    }
}
