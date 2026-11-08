package ch.threema.app.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import ch.threema.app.adapters.decorators.ChatAdapterDecorator
import ch.threema.app.adapters.decorators.ChatAdapterDecoratorListener
import ch.threema.app.emojireactions.EmojiReactionGroup
import ch.threema.app.test.koinTestModuleRule
import ch.threema.app.ui.AudioProgressBarView
import ch.threema.app.ui.ControllerView
import ch.threema.app.ui.listitemholder.ComposeMessageHolder
import ch.threema.app.utils.LinkifyUtil
import ch.threema.app.utils.StateBitmapUtil
import ch.threema.domain.protocol.csp.messages.file.FileData
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.data.media.FileDataModel
import com.google.android.material.card.MaterialCardView
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * F1Whisper: a recycled media row must not keep the link-preview card of the message it showed before.
 *
 * Incoming images and videos share `TYPE_MEDIA_RECV`, so one holder serves both. Only the file decorator owns the
 * link-preview card (title, description, domain) and shows or hides it; the video and animated-image decorators never
 * touched it. Scrolling a group with a previewed link from one member and a video from another therefore showed the
 * first member's title and description under the second member's video. The base `configure` now hides the card
 * before any decorator binds, and the file decorator re-shows it for a real preview.
 *
 * Runs the REAL `ChatAdapterDecorator.configure` through the public `decorate` entry.
 */
class LinkPreviewCardRecyclingTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(90)

    /** The decorator base class resolves its state-icon helper through Koin. */
    @get:Rule
    val koinRule = koinTestModuleRule {
        single<StateBitmapUtil> { mockk(relaxed = true) }
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic(AppCompatResources::class)
    }

    /** Stands in for a media decorator; [showsCard] mimics the file decorator re-showing a real preview. */
    private class MediaDecorator(
        model: MessageModel,
        listener: ChatAdapterDecoratorListener,
        linkifyListener: LinkifyUtil.LinkifyListener,
        helper: Helper,
        private val showsCard: Boolean,
    ) : ChatAdapterDecorator(model, listener, linkifyListener, helper) {
        var payloadBound = false

        override fun configureChatMessage(holder: ComposeMessageHolder, context: Context, position: Int) {
            payloadBound = true
            if (showsCard) {
                holder.linkPreviewInfo?.visibility = View.VISIBLE
            }
        }
    }

    private fun helper(): ChatAdapterDecorator.Helper =
        ChatAdapterDecorator.Helper(
            "01234567",
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            100,
            0,
            1000,
            100,
            mockk(),
        )

    private fun incomingVideo(): MessageModel =
        MessageModel().apply {
            type = MessageType.FILE
            isOutbox = false
            fileData = FileDataModel(
                "video/mp4",
                "image/jpeg",
                1024L,
                "clip.mp4",
                FileData.RENDERING_MEDIA,
                "a video caption",
                true,
                mutableMapOf(),
            )
        }

    /** A holder recycled from a previewed-link bind: its card is still visible. */
    private fun holderRecycledFromPreview(atPosition: Int): ComposeMessageHolder =
        ComposeMessageHolder().apply {
            position = atPosition
            bodyTextView = mockk(relaxed = true)
            secondaryTextView = mockk(relaxed = true)
            attachmentImage = mockk<ImageView>(relaxed = true)
            contentView = mockk<ViewGroup>(relaxed = true)
            controller = mockk<ControllerView>(relaxed = true)
            seekBar = mockk<AudioProgressBarView>(relaxed = true)
            emojiReactionGroup = mockk<EmojiReactionGroup>(relaxed = true)
            messageBlockView = mockk<MaterialCardView>(relaxed = true)
            dateView = mockk(relaxed = true)
            linkPreviewInfo = mockk<View>(relaxed = true)
        }

    private fun stubColors() {
        mockkStatic(AppCompatResources::class)
        every { AppCompatResources.getColorStateList(any(), any()) } returns mockk<ColorStateList>()
    }

    @Test
    fun `a video bound into a recycled preview holder hides the stale card`() {
        stubColors()
        val decorator = MediaDecorator(incomingVideo(), mockk(), mockk(), helper(), showsCard = false)
        val holder = holderRecycledFromPreview(atPosition = 2)

        decorator.decorate(holder, mockk<Context>(relaxed = true), 2)

        assertTrue(decorator.payloadBound)
        verify { holder.linkPreviewInfo!!.visibility = View.GONE }
        verify(exactly = 0) { holder.linkPreviewInfo!!.visibility = View.VISIBLE }
    }

    @Test
    fun `the reset runs before the decorator binds, so a real preview can still show its card`() {
        stubColors()
        val decorator = MediaDecorator(incomingVideo(), mockk(), mockk(), helper(), showsCard = true)
        val holder = holderRecycledFromPreview(atPosition = 5)

        decorator.decorate(holder, mockk<Context>(relaxed = true), 5)

        verifyOrder {
            holder.linkPreviewInfo!!.visibility = View.GONE
            holder.linkPreviewInfo!!.visibility = View.VISIBLE
        }
    }

    @Test
    fun `a layout without the card is left alone`() {
        stubColors()
        val decorator = MediaDecorator(incomingVideo(), mockk(), mockk(), helper(), showsCard = false)
        val holder = holderRecycledFromPreview(atPosition = 1).apply { linkPreviewInfo = null }

        decorator.decorate(holder, mockk<Context>(relaxed = true), 1)

        assertTrue(decorator.payloadBound)
    }
}
