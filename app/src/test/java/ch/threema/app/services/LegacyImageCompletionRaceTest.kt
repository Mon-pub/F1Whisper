package ch.threema.app.services

import ch.threema.app.services.MessageRowHarness.Companion.BASE_TIME
import ch.threema.app.services.MessageRowHarness.Companion.CONTACT_TABLE
import ch.threema.app.services.MessageRowHarness.Companion.GROUP_TABLE
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (sixth fork review, F6-02): the two deprecated image auto-download handlers must own the current row before
 * anything is published from what they downloaded.
 *
 * `ImageMessage` (0x02) and `GroupImageMessage` are deprecated wire types that a stock older client still sends, so both
 * handlers are live production inputs. The fifth review fixed download completion in `downloadMediaMessage`; these two
 * kept their own copy of the old logic - write the image, mutate a detached body, call a factory `update()` that ignores
 * its own affected-row count, fire the listeners, and let the caller copy the image into the device gallery. None of
 * that asks whether the message still exists. The new-message event fires BEFORE the download, so the user can delete
 * the message while it runs; when they did, the media was written after the deletion, the update matched zero rows and
 * said nothing, and the gallery kept a permanent copy of content whose message was gone.
 *
 * The completion write is what decides ownership now, so it is what is executed here: against a row that is still there,
 * a row that has been hard-deleted, a row deleted for everyone, and a row another downloader has already completed.
 * [legacyUnconditionalUpdateReportsSuccessForAVanishedRow] is the control.
 *
 * Recorded limitation: an image body is serialised by `android.util.JsonWriter`, which is not available in a JVM unit
 * test, so the bodies here are opaque strings. What is asserted is which row the statement matches and which columns it
 * changes - which is where the finding lives. The EXIF caption is a column of its own and IS asserted exactly.
 */
class LegacyImageCompletionRaceTest {
    private lateinit var harness: MessageRowHarness

    private val messageId = 1
    private val pendingBody = """{"b":"blob","d":false}"""
    private val downloadedBody = """{"b":"","d":true}"""
    private val exifCaption = "from the sender's camera"

    @BeforeTest
    fun setUp() {
        harness = MessageRowHarness(CONTACT_TABLE, GROUP_TABLE)
    }

    @AfterTest
    fun tearDown() {
        harness.close()
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The success control: both types complete, and the legacy caption survives.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a direct image completes and keeps the caption extracted from its EXIF`() {
        harness.insertContactRow(messageId, body = pendingBody)

        assertTrue(complete(CONTACT_TABLE))

        assertEquals(downloadedBody, harness.stringOf(CONTACT_TABLE, messageId, "body"))
        assertEquals(
            exifCaption,
            harness.stringOf(CONTACT_TABLE, messageId, "caption"),
            "the legacy image format carries its caption in the blob's EXIF, so this write is the only moment it exists",
        )
    }

    @Test
    fun `a group image completes and keeps the caption extracted from its EXIF`() {
        harness.insertGroupRow(messageId, outbox = false, body = pendingBody)

        assertTrue(complete(GROUP_TABLE))

        assertEquals(downloadedBody, harness.stringOf(GROUP_TABLE, messageId, "body"))
        assertEquals(exifCaption, harness.stringOf(GROUP_TABLE, messageId, "caption"))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The race.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a direct image whose row was hard-deleted mid-download publishes nothing`() {
        harness.insertContactRow(messageId, body = pendingBody)
        // The user deletes the message while the blob is being written to disk.
        harness.hardDelete(CONTACT_TABLE, messageId)

        assertFalse(complete(CONTACT_TABLE), "the download does not own a row that has gone")
        assertEquals(0, harness.rowCount(CONTACT_TABLE, messageId), "and it must not recreate one")
    }

    @Test
    fun `a group image whose row was hard-deleted mid-download publishes nothing`() {
        harness.insertGroupRow(messageId, outbox = false, body = pendingBody)
        harness.hardDelete(GROUP_TABLE, messageId)

        assertFalse(complete(GROUP_TABLE))
        assertEquals(0, harness.rowCount(GROUP_TABLE, messageId))
    }

    @Test
    fun `a message deleted for everyone mid-download keeps its deletion`() {
        harness.insertContactRow(messageId, body = pendingBody)
        harness.deleteForEveryone(CONTACT_TABLE, messageId, atUtc = BASE_TIME + 1_000L)

        assertFalse(complete(CONTACT_TABLE))

        assertEquals(BASE_TIME + 1_000L, harness.longOf(CONTACT_TABLE, messageId, "deletedAtUtc"))
        assertNull(harness.stringOf(CONTACT_TABLE, messageId, "body"), "the removed body must not come back as media")
    }

    @Test
    fun `a completion decided from a body that has since changed is refused`() {
        harness.insertContactRow(messageId, body = pendingBody)

        // Something else writes the same serialised metadata in between: another download attempt, a listen-once claim.
        val concurrent = """{"b":"blob","d":false,"j":1}"""
        harness.apply(
            CONTACT_TABLE,
            messageId,
            MessageLifecycleUpdates.mediaMetadata(concurrent, pendingBody, null, false, null, null),
        )

        assertFalse(
            complete(CONTACT_TABLE),
            "the attempt decided against a body that no longer exists; it must be recomputed on top of the winner, not " +
                "applied over it",
        )
        assertEquals(concurrent, harness.stringOf(CONTACT_TABLE, messageId, "body"))
    }

    @Test
    fun legacyUnconditionalUpdateReportsSuccessForAVanishedRow() {
        harness.insertContactRow(messageId, body = pendingBody)
        val inFlight = harness.requireModel(CONTACT_TABLE, messageId)
        harness.hardDelete(CONTACT_TABLE, messageId)

        // The old shape: mutate the detached model and hand the whole row to a factory update whose return value is a
        // constant `true`. The caller then fired its listeners and copied the image into the gallery.
        inFlight.body = downloadedBody
        harness.legacyFullRowUpsert(CONTACT_TABLE, inFlight)

        assertEquals(
            1,
            harness.rowCount(CONTACT_TABLE, messageId),
            "this is the defect: `createOrUpdate` even brought the deleted message back, and either way the caller was " +
                "told the completion had succeeded",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Both handlers are wired to the shared completion.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `both legacy handlers route through the shared completion ownership`() {
        val service = java.io.File("src/main/java/ch/threema/app/services/MessageServiceImpl.java").readText()
        val group = bodyOf(service, "private GroupMessageModel saveGroupMessage(GroupImageMessage message")
        val direct = bodyOf(service, "private MessageModel saveBoxMessage(\n        @NonNull ImageMessage message")

        for ((name, body) in listOf("group" to group, "direct" to direct)) {
            assertTrue(
                body.contains("setDownloadCompleted(messageModel, messageModel.getImageData(), messageModel.getCaption())"),
                "the $name handler must take completion ownership through the shared write, carrying the EXIF caption",
            )
            assertFalse(
                body.contains("messageModelFactory.update(messageModel)"),
                "the $name handler must not treat an unconditional factory update as ownership",
            )
            assertFalse(
                body.contains("downloadService.complete(messageModel.getId()"),
                "the $name handler must not announce the blob complete itself; the owning write does that on success",
            )
        }
    }

    @Test
    fun `the shared completion removes the media it wrote when it loses the row`() {
        val service = java.io.File("src/main/java/ch/threema/app/services/MessageServiceImpl.java").readText()
        val body = bodyOf(service, "private boolean setDownloadCompleted(\n        @NonNull AbstractMessageModel mediaMessageModel,")

        assertTrue(
            body.contains("fileService.removeMessageFiles(mediaMessageModel, true)"),
            "media written for a message that no longer exists is an orphan on disk, and with the save-media preference " +
                "a permanent gallery copy",
        )
        assertTrue(body.contains("adoptPersistedBody(current.getBody())"))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------------------------------------------

    /**
     * The write `setDownloadCompleted` performs: the downloaded body and the EXIF caption, conditional on the body the
     * attempt decided from and on the row still being there and undeleted.
     *
     * @param decidedFrom the body this attempt read when it started - which is what makes it an IN-FLIGHT attempt, and
     *                    what the deletion, or another writer, can invalidate underneath it.
     */
    private fun complete(table: String, decidedFrom: String? = pendingBody): Boolean =
        harness.apply(
            table,
            messageId,
            MessageLifecycleUpdates.mediaMetadata(
                downloadedBody,
                decidedFrom,
                null,
                true,
                exifCaption,
                null,
            ),
        )

    private fun bodyOf(source: String, signature: String): String {
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
}
