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
import ch.threema.app.ui.AudioProgressBarView
import ch.threema.app.ui.ControllerView
import ch.threema.app.ui.DisappearingTimerBadgeView
import ch.threema.app.ui.listitemholder.ComposeMessageHolder
import ch.threema.app.utils.LinkifyUtil
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageType
import com.google.android.material.card.MaterialCardView
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * F1Whisper (twelfth fork review, F12-03): a row bound while live transitions AT its deadline, on the bound holder.
 *
 * **The defect.** Expiry ran only at bind time (`ComposeMessageAdapter.getItemType`, the F11-07 belts in
 * `ChatAdapterDecorator.configure`). The countdown badge's tick repainted frames - every 50 ms inside the final
 * 30 s - and at zero did nothing. A row bound while live therefore stayed fully visible and actionable past its
 * deadline until the durable worker deletion happened to land, and indefinitely when that deletion failed (its
 * exceptions are caught). The review's required proof crosses the deadline AFTER the initial bind, with durable
 * deletion blocked; binding an already-expired model does not count.
 *
 * **What executes here - production code, none of it a mirror:**
 * - the REAL `ChatAdapterDecorator.configure` (via the public `decorate` entry) binding a LIVE disappearing row and
 *   arming the badge's deadline reaction. The badge is the holder's mockk seam, so the armed reaction - the actual
 *   production lambda - is captured and invoked exactly the way the badge's deadline tick invokes it;
 * - the REAL `DisappearingMessageService.enforceIfExpired` inside that reaction, answering synchronously from the
 *   model (the authority; it also enqueues the conditional durable removal);
 * - the REAL `withholdExpiredContent` on the same real decorator and holder the bind used.
 *
 * **Durable deletion is blocked structurally, not by arrangement** (same seam as [ExpiredRowPresentationTest]): the
 * models carry no uid, so `enqueueExpiryEnforcement` skips the worker hop while the synchronous decision stays
 * `true`. Every deadline assertion below therefore runs in exactly the state the finding names - deadline crossed,
 * deletion still pending - and additionally asserts the model kept its content, i.e. the transition is
 * presentation-side.
 *
 * The badge's own tick mechanics (retire at deadline, fire once, still-current re-check, listener cleared on stop)
 * cannot execute on the JVM - the runnable rides `RuntimeUtil.handler`, an android main-thread Handler - so they are
 * pinned at source below, each pin provable red by reverting the line it names. [DisappearingTickDecisionTest]
 * executes the tick's decision exhaustively.
 */
class DisappearingDeadlineTransitionTest {

    private companion object {
        private const val PAST = 1_600_000_000_000L
    }

    /** Pre-fix code must FAIL, never hang: same androidx-on-mock-Context hazard as [ExpiredRowPresentationTest]. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(90)

    @AfterTest
    fun tearDown() {
        unmockkStatic(AppCompatResources::class)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Fixtures. uid stays null ON PURPOSE: the worker hop is skipped while the decision stays `true`, pinning the
    // deadline tests to the "deletion pending" state under review. postedAt/modifiedAt stay null so date binding
    // resolves without android date-format stubs.
    // -----------------------------------------------------------------------------------------------------------------

    private fun liveDisappearingText(): MessageModel =
        MessageModel().apply {
            type = MessageType.TEXT
            body = "must vanish at the deadline"
            isOutbox = false
            disappearingTimerSeconds = 30
            expireStartedAt = System.currentTimeMillis()
            expiresAt = System.currentTimeMillis() + 600_000L
        }

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

    /** A holder recycled from a payload bind, carrying the countdown badge seam. */
    private fun boundHolder(atPosition: Int, badge: DisappearingTimerBadgeView): ComposeMessageHolder =
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
            disappearingIcon = badge
        }

    private fun mockLiveBindResources() {
        mockkStatic(AppCompatResources::class)
        every { AppCompatResources.getColorStateList(any(), any()) } returns mockk<ColorStateList>()
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 1. The bind arms the reaction - for the running countdown only, after tearing down the previous binding's
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the running countdown arms its deadline reaction after stopping the previous one`() {
        mockLiveBindResources()
        val badge = mockk<DisappearingTimerBadgeView>(relaxed = true)
        val armed = slot<Runnable>()
        every { badge.setDeadlineListener(capture(armed)) } just Runs
        val decorator = RecordingDecorator(liveDisappearingText(), mockk(), mockk(), helper())

        decorator.decorate(boundHolder(atPosition = 3, badge = badge), mockk<Context>(relaxed = true), 3)

        assertTrue(decorator.payloadBound, "a live row binds its payload - this test's row is NOT pre-expired")
        assertTrue(armed.isCaptured, "the running-countdown bind must arm the deadline reaction")
        verifyOrder {
            // Re-bind order is the recycled-holder protection: the previous binding's countdown AND reaction die
            // first (stopAnimation clears the listener), then THIS binding arms before the countdown starts, so
            // there is no window in which the clock ticks with no reaction armed.
            badge.stopAnimation()
            badge.setDeadlineListener(any())
            badge.setExpirationTime(any(), any())
            badge.startAnimation()
        }
    }

    @Test
    fun `rows without a running countdown arm no deadline reaction`() {
        mockLiveBindResources()

        // Not disappearing at all: badge hidden, nothing armed (the teardown still runs - recycled row).
        val plainBadge = mockk<DisappearingTimerBadgeView>(relaxed = true)
        val plain = MessageModel().apply {
            type = MessageType.TEXT
            body = "keeps forever"
            isOutbox = false
        }
        RecordingDecorator(plain, mockk(), mockk(), helper())
            .decorate(boundHolder(atPosition = 4, badge = plainBadge), mockk<Context>(relaxed = true), 4)
        verify { plainBadge.stopAnimation() }
        verify(exactly = 0) { plainBadge.setDeadlineListener(any()) }

        // Frozen (timer exists, countdown not started): static full disc, nothing armed - there is no deadline yet.
        val frozenBadge = mockk<DisappearingTimerBadgeView>(relaxed = true)
        val frozen = MessageModel().apply {
            type = MessageType.TEXT
            body = "unread incoming"
            isOutbox = false
            disappearingTimerSeconds = 30
        }
        RecordingDecorator(frozen, mockk(), mockk(), helper())
            .decorate(boundHolder(atPosition = 5, badge = frozenBadge), mockk<Context>(relaxed = true), 5)
        verify { frozenBadge.setPercentComplete(0f) }
        verify(exactly = 0) { frozenBadge.setDeadlineListener(any()) }
        verify(exactly = 0) { frozenBadge.startAnimation() }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 2. The deadline crossed AFTER bind: the reaction withholds the bound holder while deletion is pending
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a deadline crossed after bind withholds the bound row while deletion is pending`() {
        mockLiveBindResources()
        val badge = mockk<DisappearingTimerBadgeView>(relaxed = true)
        val armed = slot<Runnable>()
        every { badge.setDeadlineListener(capture(armed)) } just Runs
        val model = liveDisappearingText()
        val decorator = RecordingDecorator(model, mockk(), mockk(), helper())
        val holder = boundHolder(atPosition = 7, badge = badge)

        decorator.decorate(holder, mockk<Context>(relaxed = true), 7)
        assertTrue(decorator.payloadBound, "the row is LIVE at bind - the belts did not withhold it")
        verify(exactly = 0) { holder.bodyTextView!!.text = "" }

        // The review's required scenario: the deadline passes AFTER the initial bind, on the bound holder, with
        // the durable deletion structurally blocked (uid-less seam). Then the badge's deadline tick fires the
        // armed reaction.
        model.expireStartedAt = PAST
        model.expiresAt = PAST + 30_000L
        armed.captured.run()

        // The bound holder transitioned synchronously: payload blanked/hidden, stale actions overwritten, the
        // countdown badge itself stopped and hidden - the tombstone-equivalent presentation of the F11-07 belt.
        verify { holder.bodyTextView!!.text = "" }
        verify { holder.attachmentImage!!.setImageBitmap(null) }
        verify { holder.attachmentImage!!.visibility = View.GONE }
        verify { holder.contentView!!.visibility = View.GONE }
        verify { holder.emojiReactionGroup!!.visibility = View.GONE }
        verify { holder.messageBlockView!!.setOnClickListener(any()) }
        verify { holder.messageBlockView!!.setOnLongClickListener(any()) }
        verify { holder.messageBlockView!!.setOnTouchListener(any()) }
        verify(exactly = 2) { badge.stopAnimation() }
        verify { badge.visibility = View.GONE }

        // And the transition is presentation-side: nothing was deleted on this thread, the durable removal is
        // still pending - exactly the state in which the old code kept the row on screen.
        assertEquals("must vanish at the deadline", model.body, "the model still carries its content")
        assertFalse(model.isDeleted, "the durable deletion stays with the worker, and here it is blocked")
    }

    @Test
    fun `the deadline reaction re-asks the authority - a still-live row stays bound`() {
        mockLiveBindResources()
        val badge = mockk<DisappearingTimerBadgeView>(relaxed = true)
        val armed = slot<Runnable>()
        every { badge.setDeadlineListener(capture(armed)) } just Runs
        val model = liveDisappearingText()
        val decorator = RecordingDecorator(model, mockk(), mockk(), helper())
        val holder = boundHolder(atPosition = 9, badge = badge)

        decorator.decorate(holder, mockk<Context>(relaxed = true), 9)

        // A reaction that fires while the model is NOT past its deadline (timer extended after arming, clock skew,
        // a late tick after the conversation timer was cleared) must leave the row alone: the reaction consults
        // the synchronous authority, it does not blindly withhold.
        armed.captured.run()

        verify(exactly = 0) { holder.bodyTextView!!.text = "" }
        verify(exactly = 1) { badge.stopAnimation() }
        assertEquals("must vanish at the deadline", model.body)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 3. Source pins on the badge's tick mechanics and the decorator wiring - each provable red by reverting the
    // line it names (the tick rides the android main-thread Handler, so these cannot execute on the JVM)
    // -----------------------------------------------------------------------------------------------------------------

    private val badgeView = File("src/main/java/ch/threema/app/ui/DisappearingTimerBadgeView.java")
    private val chatDecorator = File("src/main/java/ch/threema/app/adapters/decorators/ChatAdapterDecorator.java")

    @Test
    fun `stopAnimation clears the deadline reaction along with the countdown`() {
        val stop = bodyOf(badgeView.readText(), "public void stopAnimation()")
        assertTrue(
            stop.contains("deadlineListener = null"),
            "stopAnimation must clear the deadline listener - it runs first on every re-bind, withhold and detach, " +
                "and is what guarantees a recycled row can never fire the previous binding's reaction against its " +
                "new content",
        )
    }

    @Test
    fun `the tick decides through the pure decision and retires at the deadline instead of re-posting`() {
        val run = bodyOf(badgeView.readText(), "public void run()")
        assertTrue(
            run.contains("DisappearingTickDecision.decide("),
            "the tick must derive its outcome from DisappearingTickDecision - that is what makes the deadline " +
                "boundary (remaining <= 0) executable in DisappearingTickDecisionTest",
        )

        val deadlineBranch = run.indexOf("tick == DisappearingTick.DEADLINE")
        assertTrue(deadlineBranch >= 0, "the tick must branch on the DEADLINE outcome")
        val branch = run.substring(deadlineBranch)

        assertTrue(
            branch.contains("view.pendingUpdate != this"),
            "the deadline branch must re-confirm under the lock that it is STILL the current tick - a stop or " +
                "re-bind racing in must win, or a stale binding's reaction could fire against a recycled holder",
        )
        assertTrue(
            branch.contains("view.stopped = true") && branch.contains("view.pendingUpdate = null"),
            "the deadline branch must retire the countdown so a later bind restarts cleanly",
        )

        val cleared = branch.indexOf("view.deadlineListener = null")
        val fired = branch.indexOf("deadlineListener.run()")
        assertTrue(cleared >= 0 && fired >= 0 && cleared < fired, "the listener is cleared under the lock BEFORE it fires, so it fires exactly once")

        val afterFire = branch.substring(fired)
        val returnAfterFire = afterFire.indexOf("return;")
        val repostAfterFire = afterFire.indexOf("postDelayed")
        assertTrue(returnAfterFire >= 0, "the deadline branch must return")
        assertTrue(
            repostAfterFire == -1 || returnAfterFire < repostAfterFire,
            "the deadline tick must NOT re-post - ticking past zero forever with no event is the F12-03 defect",
        )
    }

    @Test
    fun `the countdown cadence and the deadline threshold have a single source`() {
        val delay = bodyOf(badgeView.readText(), "private long calculateAnimationDelay(")
        assertTrue(
            delay.contains("DisappearingTickDecision.delayFor(DisappearingTickDecision.decide("),
            "the arming delay must come from the same decision as the tick - a duplicated threshold is how the " +
                "tick and the scheduler drift apart (e.g. a countdown armed past its deadline waiting a full second " +
                "for the tick that delivers the event)",
        )
    }

    @Test
    fun `the binder arms the reaction for the running countdown, after teardown and before start`() {
        val configure = bodyOf(chatDecorator.readText(), "final protected void configure(")
        val teardown = configure.indexOf("disappearingIcon.stopAnimation()")
        val arm = configure.indexOf("disappearingIcon.setDeadlineListener(")
        val start = configure.indexOf("disappearingIcon.startAnimation()")
        assertTrue(teardown >= 0 && arm >= 0 && start >= 0, "configure must tear down, arm and start the badge")
        assertTrue(
            teardown < arm && arm < start,
            "order must be teardown -> arm -> start: the previous binding's reaction dies first, and THIS binding's " +
                "is in place before its countdown can tick",
        )

        val reaction = configure.substring(arm, start)
        assertTrue(
            reaction.contains("DisappearingMessageService.enforceIfExpired(getMessageModel())"),
            "the reaction must re-ask the synchronous authority (which also re-enqueues the conditional durable " +
                "removal), never blindly withhold",
        )
        assertTrue(
            reaction.contains("withholdExpiredContent(viewHolder, context)"),
            "and on true must withhold this exact holder - the same tombstone-equivalent presentation as the " +
                "F11-07 belt",
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
