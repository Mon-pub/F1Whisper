package ch.threema.app.services

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Structural pins for the callers a unit test cannot drive without the whole MessageServiceImpl: a download that does
 * not decrypt is reported as an error (which discards the downloaded file) and is never completed (which would mark
 * the blob as done, and the server may delete a blob marked as done).
 */
class MediaDownloadVerificationPinTest {
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
    fun `media that does not decrypt or cannot be saved is reported as a failed download`() {
        val body = bodyOf("private boolean downloadAndWriteMediaData(")

        assertTrue(
            Regex(
                """try\s*\{\s*success = decryptMediaDataAndWriteConversationMedia\(mediaMessageModel, data, blob\);\s*\}\s*""" +
                    """catch \(OutOfMemoryError e\)\s*\{[^{}]*\}\s*""" +
                    """catch \(ThreemaException e\)\s*\{\s*downloadService\.error\(mediaMessageModel\.getId\(\)\);\s*throw e;\s*\}""",
            ).containsMatchIn(body),
            "a ThreemaException from decrypting or saving must report the error and be rethrown",
        )
    }

    @Test
    fun `a legacy image that does not decrypt is a ThreemaException, not a NullPointerException`() {
        val body = bodyOf("private boolean decryptMediaDataAndWriteConversationMedia(")

        assertTrue(
            Regex(
                """blob = identityStore\.decryptData\(blob, nonce, encryptionKey\);\s*if \(blob == null\)\s*\{\s*""" +
                    """throw new ThreemaException\(""\);\s*\}\s*length = blob\.length;""",
            ).containsMatchIn(body),
        )
    }

    @Test
    fun `a thumbnail that does not decrypt reports an error and never reaches complete`() {
        val body = bodyOf("public boolean downloadThumbnailIfPresent(")
        val check = Regex(
            """byte\[] thumbnail = symmetricEncryptionService\.decrypt\([^;]*\);\s*if \(thumbnail == null\)\s*\{\s*""" +
                """downloadService\.error\(messageModel\.getId\(\)\);[^{}]*throw new ThreemaException\(""\);\s*\}""",
        ).find(body)

        assertTrue(check != null, "a null decrypt must report the error and throw")
        assertTrue(check.range.last < body.indexOf("downloadService.complete("), "the check must come before complete")
    }
}
