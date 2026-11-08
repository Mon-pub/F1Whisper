package ch.threema.app.activities

import android.net.Uri
import ch.threema.app.ui.MediaItem
import ch.threema.domain.protocol.csp.messages.file.FileData
import ch.threema.storage.models.data.media.FileDataModel
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper: forwarding a link preview keeps its card (URL, title, description), and only those three keys travel.
 * Both forward routes in [RecipientListBaseActivity] call [RecipientListBaseActivity.copyLinkPreview].
 */
class ForwardLinkPreviewCopyTest {

    private fun fileData(metaData: MutableMap<String, Any>): FileDataModel =
        FileDataModel(
            "image/jpeg",
            "image/jpeg",
            1024L,
            "preview.jpg",
            FileData.RENDERING_MEDIA,
            "look https://example.org/page",
            true,
            metaData,
        )

    private fun mediaItem(): MediaItem = MediaItem(mockk<Uri>(relaxed = true), MediaItem.TYPE_IMAGE)

    @Test
    fun `a previewed original hands its card to the forwarded item`() {
        val item = mediaItem()
        val original = fileData(
            mutableMapOf(
                FileDataModel.METADATA_KEY_PREVIEW_URL to "https://example.org/page",
                FileDataModel.METADATA_KEY_PREVIEW_TITLE to "Example title",
                FileDataModel.METADATA_KEY_PREVIEW_DESCRIPTION to "Example description",
            ),
        )

        RecipientListBaseActivity.copyLinkPreview(item, original)

        assertTrue(item.isLinkPreview)
        assertEquals("https://example.org/page", item.linkPreviewUrl)
        assertEquals("Example title", item.linkPreviewTitle)
        assertEquals("Example description", item.linkPreviewDescription)
    }

    @Test
    fun `a preview without title or description still keeps its url`() {
        val item = mediaItem()
        RecipientListBaseActivity.copyLinkPreview(
            item,
            fileData(mutableMapOf(FileDataModel.METADATA_KEY_PREVIEW_URL to "https://example.org/page")),
        )

        assertEquals("https://example.org/page", item.linkPreviewUrl)
        assertNull(item.linkPreviewTitle)
        assertNull(item.linkPreviewDescription)
    }

    @Test
    fun `other metadata of the original is not copied`() {
        val item = mediaItem()
        val original = fileData(
            mutableMapOf(
                FileDataModel.METADATA_KEY_PREVIEW_URL to "https://example.org/page",
                FileDataModel.METADATA_KEY_QUOTED_MESSAGE_ID to "0123456789abcdef",
            ),
        )

        RecipientListBaseActivity.copyLinkPreview(item, original)

        assertNull(item.quotedMessageId, "a forward must not inherit the original's reply-quote")
    }

    @Test
    fun `a plain media original or a missing file data leaves the item without a card`() {
        val plain = mediaItem()
        RecipientListBaseActivity.copyLinkPreview(plain, fileData(mutableMapOf()))
        assertFalse(plain.isLinkPreview)

        val blank = mediaItem()
        RecipientListBaseActivity.copyLinkPreview(blank, fileData(mutableMapOf(FileDataModel.METADATA_KEY_PREVIEW_URL to "  ")))
        assertFalse(blank.isLinkPreview)

        val none = mediaItem()
        RecipientListBaseActivity.copyLinkPreview(none, null)
        assertFalse(none.isLinkPreview)
    }
}
