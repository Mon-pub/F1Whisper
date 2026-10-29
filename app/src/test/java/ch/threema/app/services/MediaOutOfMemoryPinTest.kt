package ch.threema.app.services

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Structural pins for the media paths a unit test cannot drive without the whole MessageServiceImpl: running out of
 * memory while decrypting, saving or thumbnailing a large download, or while reading a file to send, is a failure of
 * that operation and never escapes the download thread or the send task.
 */
class MediaOutOfMemoryPinTest {
    private val source: String = File("src/main/java/ch/threema/app/services/MessageServiceImpl.java").readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex(""""(?:\\.|[^"\\])*""""), "\"\"")

    private fun bodyOf(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "this test's anchor has drifted: $signature")
        var depth = 0
        var seenOpen = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> {
                    depth++
                    seenOpen = true
                }
                '}' -> {
                    depth--
                    if (seenOpen && depth == 0) {
                        return source.substring(start, index + 1)
                    }
                }
            }
        }
        error("unbalanced braces after $signature")
    }

    @Test
    fun `decrypting and saving a download turns an OutOfMemoryError into a failed download`() {
        val body = bodyOf("private boolean downloadAndWriteMediaData(")

        assertTrue(
            Regex(
                """try\s*\{\s*success = decryptMediaDataAndWriteConversationMedia\(mediaMessageModel, data, blob\);\s*\}\s*""" +
                    """catch \(OutOfMemoryError e\)\s*\{[^{}]*downloadService\.error\(mediaMessageModel\.getId\(\)\);[^{}]*""" +
                    """throw new ThreemaException\(""\);\s*\}""",
            ).containsMatchIn(body),
            "the decrypt-and-save call must be wrapped, report the error and throw an Exception",
        )
    }

    @Test
    fun `the large blob is released before the thumbnail is created`() {
        val body = bodyOf("private boolean downloadAndWriteMediaData(")
        val release = body.indexOf("blob = null;")

        assertTrue(release >= 0)
        assertTrue(release < body.indexOf("createAndWriteMediaThumbnail(mediaMessageModel);"))
    }

    @Test
    fun `a thumbnail that does not fit into memory is skipped`() {
        assertTrue(bodyOf("private void createAndWriteMediaThumbnail(").contains("catch (OutOfMemoryError e)"))
    }

    @Test
    fun `the send buffer is allocated inside the OutOfMemoryError catch and unknown sizes are not reserved up front`() {
        val body = bodyOf("private byte[] getContentData(MediaItem mediaItem)")

        assertTrue(
            Regex("""try\s*\{\s*byte\[] fileData = new byte\[fileLength \+ NaCl\.BOX_OVERHEAD_BYTES];""").containsMatchIn(body),
            "the allocation must be the first statement of the try that catches OutOfMemoryError",
        )
        assertTrue(body.contains("catch (OutOfMemoryError e)"))
        assertTrue(body.contains("return readContentDataOfUnknownLength(inputStream);"))
        assertFalse(body.contains("MAX_BLOB_SIZE + 1"), "an unknown size must not reserve the whole limit")
    }
}
