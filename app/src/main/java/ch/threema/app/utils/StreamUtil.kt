package ch.threema.app.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import ch.threema.base.utils.getThreemaLogger
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

private val logger = getThreemaLogger("StreamUtil")

@Throws(IOException::class)
fun getFromUri(context: Context, uri: Uri?): InputStream {
    if (uri == null || uri.scheme == null) {
        throw FileNotFoundException()
    }

    if (ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) {
        try {
            context.contentResolver.openInputStream(uri)
                ?.let { inputStream ->
                    return inputStream
                }
        } catch (_: FileNotFoundException) {
            logger.info("Unable to get an InputStream for this file using ContentResolver: $uri")
        } catch (e: SecurityException) {
            throw IOException(e)
        }
    }

    // try to open as local file if openInputStream fails for a content Uri
    val filePath = FileUtil.getRealPathFromURI(context, uri)
        ?: throw FileNotFoundException()
    val tmpPath = context.cacheDir.absolutePath
    val appPath = context.applicationInfo.dataDir

    // do not allow sending of files from local directories - but allow tmp dir
    if (filePath.startsWith(appPath) && !filePath.startsWith(tmpPath)) {
        throw FileNotFoundException("File on private directory")
    }
    return FileInputStream(filePath)
}

private const val UNKNOWN_SIZE_CHUNK_BYTES = 1024 * 1024

/**
 * F1-PATCH: reads a stream that does not report its size into one array with [headerBytes] free bytes in front of the
 * content, or returns null when the content is longer than [maxContentBytes]. The content is collected in chunks and
 * copied once into an exact-size array, so the peak is about twice the content (only for streams of unknown size)
 * instead of reserving the whole limit up front for every such stream.
 */
@JvmOverloads
@Throws(IOException::class)
fun InputStream.readUnknownSizeWithHeaderRoom(
    headerBytes: Int,
    maxContentBytes: Int,
    chunkBytes: Int = UNKNOWN_SIZE_CHUNK_BYTES,
): ByteArray? {
    require(headerBytes >= 0 && maxContentBytes >= 0 && chunkBytes > 0)
    val chunks = mutableListOf<ByteArray>()
    var total = 0L
    var lastChunkFill = chunkBytes
    while (true) {
        if (lastChunkFill == chunkBytes) {
            chunks.add(ByteArray(chunkBytes))
            lastChunkFill = 0
        }
        val read = read(chunks.last(), lastChunkFill, chunkBytes - lastChunkFill)
        if (read == -1) {
            break
        }
        lastChunkFill += read
        total += read
        if (total > maxContentBytes) {
            return null
        }
    }
    val result = ByteArray(headerBytes + total.toInt())
    var offset = headerBytes
    for (chunk in chunks) {
        val length = minOf(chunk.size, headerBytes + total.toInt() - offset)
        System.arraycopy(chunk, 0, result, offset, length)
        offset += length
    }
    return result
}
