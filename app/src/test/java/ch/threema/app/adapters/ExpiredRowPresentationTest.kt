package ch.threema.app.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import ch.threema.app.adapters.decorators.ChatAdapterDecorator
import ch.threema.app.adapters.decorators.ChatAdapterDecoratorListener
import ch.threema.app.adapters.decorators.DeletedChatAdapterDecorator
import ch.threema.app.emojireactions.EmojiReactionGroup
import ch.threema.app.services.DisappearingMessageService
import ch.threema.app.ui.AudioProgressBarView
import ch.threema.app.ui.ControllerView
import ch.threema.app.ui.listitemholder.ComposeMessageHolder
import ch.threema.app.utils.LinkifyUtil
import ch.threema.app.utils.QuoteUtil
import ch.threema.domain.protocol.csp.messages.file.FileData
import ch.threema.storage.models.ConversationModel
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.data.media.FileDataModel
import com.google.android.material.card.MaterialCardView
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.File
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * F1Whisper (eleventh fork review, F11-07): expired content is withheld at bind time, on the production path.
 *
 * **The defect.** `enforceIfExpired` answered `true` synchronously and queued the durable cleanup, and every
 * presentation surface then IGNORED that answer: `ComposeMessageAdapter.getView` and `ChatAdapterDecorator.configure`
 * bound the normal payload anyway, and the conversation-list sweep returned the stale cached preview. So whenever the
 * worker deletion was delayed or failed, overdue text and media stayed visible and interactive past their deadline.
 *
 * **What executes here - all of it production code, none of it a mirror:**
 * - the REAL router `ComposeMessageAdapter.getItemType`, which now classifies an overdue row as the deleted tombstone
 *   type. That single point feeds `getItemViewType` (the ListView recycling key, so a recycled holder whose type no
 *   longer matches is re-inflated from the payload-free deleted layout) and `getView`'s layout + decorator selection
 *   (the deleted branch binds `DeletedChatAdapterDecorator`: date only, neutral click);
 * - the REAL `ChatAdapterDecorator.configure` (via the public `decorate` entry), whose second belt withholds every
 *   payload view and overwrites the bubble's listeners when the deadline passes between routing and bind - driven
 *   here against a recycled holder still carrying views from its previous bind;
 * - the REAL `QuoteUtil.isQuoteable`, the predicate behind every quote action (swipe-to-reply, menu, composer);
 * - the REAL `DisappearingMessageService.sweepConversationPreview`, the enforce step `ConversationServiceImpl.getAll`
 *   hands to `ExpirySweep`, against real `ConversationModel`s.
 *
 * **The review's "block the worker executor" precondition holds structurally in this harness**, not by arrangement:
 * the durable half of the funnel cannot run here at all (the models carry no uid, so the worker hop is skipped; and
 * no service manager exists for a hop to use), so every assertion below executes in exactly the state the finding
 * names - synchronous decision `true`, deletion still pending - and each case additionally asserts the model kept its
 * content, i.e. that withholding is presentation-side and nothing was deleted on the bind path.
 *
 * **Argued deviation (see the remediation handoff): no Robolectric.** The review asks for a bound expired row's
 * pixels; inflating the real layouts on the JVM needs Robolectric's resource processing, which this hermetic gate
 * does not carry and which mid-remediation would add its own hazards (custom-view static init, shadow drift). The
 * routed-to tombstone rendering is the upstream-shipped deleted-message path, unchanged by this fix; what F11-07
 * changed is WHICH rows route there, and that decision plus the belt plus the preview are executed above the seam.
 * The on-device test plan carries the visible-behaviour half.
 */
class ExpiredRowPresentationTest {

    private companion object {
        private const val PAST = 1_600_000_000_000L
    }

    /**
     * Pre-fix code must FAIL these tests, never hang them: with the second belt reverted, an expired row proceeds
     * into the full payload bind, and real androidx resource resolution on a mock Context does not terminate. The
     * red probe for the belt therefore times out here instead of stalling the suite.
     */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(90)

    @AfterTest
    fun tearDown() {
        unmockkStatic(AppCompatResources::class)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Model fixtures. uid stays null ON PURPOSE: enqueueExpiryEnforcement skips the worker hop for a uid-less model
    // while the decision stays `true`, which pins every test below to the "deletion pending" state under review.
    // postedAt/modifiedAt stay null so date binding resolves without the android date-format stubs.
    // -----------------------------------------------------------------------------------------------------------------

    private fun expiredText(outbox: Boolean = false): MessageModel =
        MessageModel().apply {
            type = MessageType.TEXT
            body = "must not be shown"
            isOutbox = outbox
            disappearingTimerSeconds = 30
            expireStartedAt = PAST
            expiresAt = PAST + 30_000L
        }

    private fun liveText(outbox: Boolean = false): MessageModel =
        MessageModel().apply {
            type = MessageType.TEXT
            body = "hello"
            isOutbox = outbox
            disappearingTimerSeconds = 30
            expireStartedAt = System.currentTimeMillis()
            expiresAt = System.currentTimeMillis() + 600_000L
        }

    private fun mediaFileModel(expired: Boolean): MessageModel =
        MessageModel().apply {
            type = MessageType.FILE
            isOutbox = false
            fileData = FileDataModel(
                "image/jpeg",
                "image/jpeg",
                1024L,
                "photo.jpg",
                FileData.RENDERING_MEDIA,
                "caption that must not be shown",
                true,
                mutableMapOf(),
            )
            if (expired) {
                disappearingTimerSeconds = 30
                expireStartedAt = PAST
                expiresAt = PAST + 30_000L
            }
        }

    // -----------------------------------------------------------------------------------------------------------------
    // 1. The router: the REAL getItemType classifies overdue rows as the deleted tombstone
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `an expired text row routes to the deleted tombstone type, both directions`() {
        assertEquals(ComposeMessageAdapter.TYPE_DELETED_SEND, ComposeMessageAdapter.getItemType(expiredText(outbox = true)))
        assertEquals(ComposeMessageAdapter.TYPE_DELETED_RECV, ComposeMessageAdapter.getItemType(expiredText(outbox = false)))
    }

    @Test
    fun `an expired media row routes to the tombstone before its media is even inspected`() {
        // The FILE branch of the switch reads mime type and rendering type to pick a media layout; the expiry
        // decision must win before any of that runs, or a thumbnail-bearing layout gets selected.
        assertEquals(ComposeMessageAdapter.TYPE_DELETED_RECV, ComposeMessageAdapter.getItemType(mediaFileModel(expired = true)))
    }

    @Test
    fun `a live row keeps its payload type`() {
        assertEquals(ComposeMessageAdapter.TYPE_RECV, ComposeMessageAdapter.getItemType(liveText(outbox = false)))
        assertEquals(ComposeMessageAdapter.TYPE_SEND, ComposeMessageAdapter.getItemType(liveText(outbox = true)))
        assertEquals(ComposeMessageAdapter.TYPE_MEDIA_RECV, ComposeMessageAdapter.getItemType(mediaFileModel(expired = false)))
    }

    @Test
    fun `a deleted row still routes to the tombstone, unchanged`() {
        val deleted = liveText(outbox = false).apply { deletedAt = Date() }
        assertEquals(ComposeMessageAdapter.TYPE_DELETED_RECV, ComposeMessageAdapter.getItemType(deleted))
    }

    @Test
    fun `routing decides presentation only - the model keeps its content and deletion stays pending`() {
        val model = expiredText()
        ComposeMessageAdapter.getItemType(model)

        assertFalse(model.isDeleted, "the router must not delete; the durable removal is the worker's, conditionally")
        assertEquals("must not be shown", model.body, "the model still carries its content - only presentation is decided")
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 2. The second belt: the REAL configure() withholds when expiry lands between routing and bind
    // -----------------------------------------------------------------------------------------------------------------

    private class RecordingDecorator(
        model: MessageModel,
        listener: ChatAdapterDecoratorListener,
        linkifyListener: LinkifyUtil.LinkifyListener,
        helper: Helper,
    ) : ChatAdapterDecorator(model, listener, linkifyListener, helper) {
        var payloadBound = false

        override fun configureChatMessage(holder: ComposeMessageHolder, context: Context, position: Int) {
            payloadBound = true
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

    /** A holder recycled from a payload bind: its views still show the previous content. */
    private fun recycledPayloadHolder(atPosition: Int): ComposeMessageHolder =
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
        }

    @Test
    fun `an expired text row binds no payload - views blanked, actions overwritten, date kept`() {
        val model = expiredText(outbox = false)
        val decorator = RecordingDecorator(model, mockk(), mockk(), helper())
        val holder = recycledPayloadHolder(atPosition = 3)

        decorator.decorate(holder, mockk<Context>(relaxed = true), 3)

        assertFalse(decorator.payloadBound, "configureChatMessage must not run for a row decided gone")
        verify { holder.bodyTextView!!.text = "" }
        verify { holder.secondaryTextView!!.text = "" }
        verify { holder.attachmentImage!!.setImageBitmap(null) }
        verify { holder.attachmentImage!!.visibility = View.GONE }
        verify { holder.contentView!!.visibility = View.GONE }
        verify { holder.controller!!.visibility = View.GONE }
        verify { holder.seekBar!!.visibility = View.GONE }
        verify { holder.emojiReactionGroup!!.visibility = View.GONE }
        // The stale listeners of the recycled view are OVERWRITTEN, not merely skipped: skipping would leave the
        // previous bind's click, long-press and touch handlers live on a bubble that still showed its old content.
        verify { holder.messageBlockView!!.setOnClickListener(any()) }
        verify { holder.messageBlockView!!.setOnLongClickListener(any()) }
        verify { holder.messageBlockView!!.setOnTouchListener(any()) }
        verify { holder.dateView!!.text = "" }
        assertFalse(model.isDeleted, "the belt withholds; it does not delete")
    }

    @Test
    fun `an expired media row binds no payload either`() {
        val model = mediaFileModel(expired = true)
        val decorator = RecordingDecorator(model, mockk(), mockk(), helper())
        val holder = recycledPayloadHolder(atPosition = 7)

        decorator.decorate(holder, mockk<Context>(relaxed = true), 7)

        assertFalse(decorator.payloadBound)
        verify { holder.attachmentImage!!.setImageBitmap(null) }
        verify { holder.attachmentImage!!.visibility = View.GONE }
        verify { holder.seekBar!!.visibility = View.GONE }
        assertEquals("caption that must not be shown", model.fileData.caption, "content untouched, deletion pending")
    }

    @Test
    fun `a live row binds its payload normally`() {
        mockkStatic(AppCompatResources::class)
        every { AppCompatResources.getColorStateList(any(), any()) } returns mockk<ColorStateList>()

        val decorator = RecordingDecorator(liveText(outbox = false), mockk(), mockk(), helper())
        val holder = recycledPayloadHolder(atPosition = 4)

        decorator.decorate(holder, mockk<Context>(relaxed = true), 4)

        assertTrue(decorator.payloadBound, "the belt must not withhold a live row")
        verify(exactly = 0) { holder.bodyTextView!!.text = "" }
    }

    @Test
    fun `the tombstone decorator itself still binds under expiry - the routed target works`() {
        // The first belt routes expired rows to DeletedChatAdapterDecorator; the second belt must therefore NOT fire
        // for it, or the tombstone's own date/click binding would be withheld too and the row would bind nothing.
        mockkStatic(AppCompatResources::class)
        every { AppCompatResources.getColorStateList(any(), any()) } returns mockk<ColorStateList>()

        val model = expiredText(outbox = false)
        val decorator = DeletedChatAdapterDecorator(model, mockk(), mockk(), helper())
        val holder = recycledPayloadHolder(atPosition = 5)

        decorator.decorate(holder, mockk<Context>(relaxed = true), 5)

        verify { holder.dateView!!.text = "" }
        verify { holder.messageBlockView!!.setOnClickListener(any()) }
        verify(exactly = 0) { holder.bodyTextView!!.text = any<CharSequence>() }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 3. No quote action: the REAL isQuoteable refuses an expired message
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `an expired message is not quoteable, a live one is`() {
        val expired = expiredText().apply { apiMessageId = "0011223344556677" }
        val live = liveText().apply { apiMessageId = "0011223344556677" }

        assertFalse(
            QuoteUtil.isQuoteable(expired),
            "quoting copies the content into the composer and outlives the deadline; every quote entry point " +
                "(swipe-to-reply, menu, composer) runs through this predicate",
        )
        assertTrue(QuoteUtil.isQuoteable(live))
        assertFalse(expired.isDeleted, "the refusal is presentation-side; deletion stays with the worker")
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 4. The conversation preview: the REAL sweep step hides an expired latest message immediately
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `an expired latest message is dropped from the conversation preview while deletion is pending`() {
        val expired = expiredText()
        val conversation = ConversationModel(mockk()).apply { latestMessage = expired }

        DisappearingMessageService.sweepConversationPreview(conversation)

        assertNull(conversation.latestMessage, "the stale preview must disappear with the decision, not with the worker")
        assertFalse(expired.isDeleted, "and the message itself is still awaiting its conditional worker delete")
        assertEquals("must not be shown", expired.body)
    }

    @Test
    fun `a live latest message stays in the preview`() {
        val live = liveText()
        val conversation = ConversationModel(mockk()).apply { latestMessage = live }

        DisappearingMessageService.sweepConversationPreview(conversation)

        assertEquals(live, conversation.latestMessage)
    }

    @Test
    fun `a conversation with no latest message is left alone`() {
        val conversation = ConversationModel(mockk())

        DisappearingMessageService.sweepConversationPreview(conversation)

        assertNull(conversation.latestMessage)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 5. Structural pins: the wiring around the executed seams, each provable red by reverting the line it names
    // -----------------------------------------------------------------------------------------------------------------

    private val composeAdapter = File("src/main/java/ch/threema/app/adapters/ComposeMessageAdapter.java")
    private val chatDecorator = File("src/main/java/ch/threema/app/adapters/decorators/ChatAdapterDecorator.java")
    private val fragment = File("src/main/java/ch/threema/app/fragments/composemessage/ComposeMessageFragment.java")

    @Test
    fun `the router consults expiry before the payload-type switch, and is the recycler key`() {
        val itemType = bodyOf(composeAdapter.readText(), "static @ItemLayoutType int getItemType(")
        val decision = itemType.indexOf("m.isDeleted() || DisappearingMessageService.enforceIfExpired(m)")
        val typeSwitch = itemType.indexOf("switch (m.getType())")
        assertTrue(decision >= 0, "getItemType must route on isDeleted OR the synchronous expiry decision")
        assertTrue(decision < typeSwitch, "and must do so BEFORE the switch that would select a payload layout")

        assertTrue(
            bodyOf(composeAdapter.readText(), "public @ItemLayoutType int getItemViewType(").contains("getItemType("),
            "getItemViewType must delegate to getItemType - that is what makes the decision the ListView's " +
                "recycling key, so a recycled holder re-inflates instead of keeping payload views",
        )
    }

    @Test
    fun `getView re-inflates on a type change and binds the tombstone decorator for deleted types`() {
        val getView = bodyOf(composeAdapter.readText(), "public View getView(")
        assertTrue(
            getView.contains("holder == null || holder.itemType != itemType"),
            "a recycled holder whose itemType no longer matches must be re-inflated - this is the recycled-holder " +
                "half of the fix",
        )
        val deletedBranch = getView.indexOf("itemType == TYPE_DELETED_SEND || itemType == TYPE_DELETED_RECV")
        assertTrue(deletedBranch >= 0, "getView must branch on the tombstone types")
        assertTrue(
            getView.indexOf("new DeletedChatAdapterDecorator(", deletedBranch) >= 0,
            "and the branch must bind DeletedChatAdapterDecorator (date only, no payload)",
        )
        assertFalse(
            getView.contains("enforceIfExpired"),
            "getView must not carry its own standalone expiry call - the routing in getItemType is the single " +
                "adapter-side decision point; a second, result-ignoring call is how F11-07 happened",
        )
    }

    @Test
    fun `the second belt precedes payload binding in configure`() {
        val configure = bodyOf(chatDecorator.readText(), "final protected void configure(")
        val belt = configure.indexOf("withholdExpiredContent(")
        val payload = configure.indexOf("configureChatMessage(")
        assertTrue(belt >= 0, "configure must withhold through withholdExpiredContent when the decision says gone")
        assertTrue(belt < payload, "and must decide BEFORE configureChatMessage binds any payload")
        assertTrue(
            configure.contains("!(this instanceof DeletedChatAdapterDecorator)"),
            "the tombstone decorator is exempt - it IS the withheld presentation",
        )
    }

    @Test
    fun `the selection menu collapses for an expired message like a deleted one`() {
        val menu = bodyOf(fragment.readText(), "private void updateActionMenu(")
        assertTrue(
            menu.contains("m.isDeleted() || DisappearingMessageService.enforceIfExpired(m)"),
            "updateActionMenu must collapse to discard/info on the same synchronous decision - otherwise " +
                "copy/forward/save/share on the tombstone would expose the content the row no longer shows",
        )
    }

    /** The body of the declaration starting with [signature], by brace matching from the first `{` after it. */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "signature not found: $signature" }
        var index = source.indexOf('{', start)
        require(index >= 0) { "no body for: $signature" }
        var depth = 0
        val body = StringBuilder()
        while (index < source.length) {
            val c = source[index]
            body.append(c)
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) break
            }
            index++
        }
        return body.toString()
    }
}
