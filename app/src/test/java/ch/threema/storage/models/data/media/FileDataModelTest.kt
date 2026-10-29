package ch.threema.storage.models.data.media

import ch.threema.domain.protocol.csp.messages.file.FileData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper: guards the "qi" reply-quote metadata carrier on [FileDataModel] (Signal-style "reply
 * with any type"). Exercises the in-memory metadata map only (a full toString/fromString round-trip
 * needs the Android `android.util.JsonWriter`, which is stubbed in unit tests); the round-trip is the
 * same generic mechanism already shipped for the lo/fwd/lp_* keys and is exercised on-device.
 */
class FileDataModelTest {

    private fun fileDataModelWithMeta(meta: MutableMap<String, Any>?): FileDataModel {
        return FileDataModel(
            /* mimeType = */
            "image/jpeg",
            /* thumbnailMimeType = */
            null,
            /* fileSize = */
            0L,
            /* fileName = */
            null,
            /* renderingType = */
            FileData.RENDERING_MEDIA,
            /* caption = */
            null,
            /* isDownloaded = */
            false,
            /* metaData = */
            meta,
        )
    }

    @Test
    fun testQuotedApiMessageIdPresent() {
        val model = fileDataModelWithMeta(
            mutableMapOf(FileDataModel.METADATA_KEY_QUOTED_MESSAGE_ID to "0011223344556677"),
        )
        assertEquals("0011223344556677", model.quotedApiMessageId)
    }

    @Test
    fun testQuotedApiMessageIdAbsent() {
        assertNull(fileDataModelWithMeta(mutableMapOf()).quotedApiMessageId)
        assertNull(fileDataModelWithMeta(null).quotedApiMessageId)
    }

    @Test
    fun testQuotedApiMessageIdCoexistsWithOtherFlags() {
        val model = fileDataModelWithMeta(
            mutableMapOf(
                FileDataModel.METADATA_KEY_LISTEN_ONCE to true,
                FileDataModel.METADATA_KEY_FORWARDED to true,
                FileDataModel.METADATA_KEY_QUOTED_MESSAGE_ID to "8899aabbccddeeff",
                // an unknown future key must survive alongside the known ones (graceful degradation).
                "unknownFutureKey" to "survives",
            ),
        )
        assertEquals("8899aabbccddeeff", model.quotedApiMessageId)
        assertTrue(model.isListenOnce)
        assertTrue(model.isForwarded)
        assertEquals("survives", model.getMetaDataString("unknownFutureKey"))
    }
}
