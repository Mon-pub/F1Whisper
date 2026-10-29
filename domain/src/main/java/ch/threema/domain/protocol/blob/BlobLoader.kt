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
import java.io.IOException
import java.net.URL
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds
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

    companion object {
        private const val BUFFER_SIZE = 8192

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
