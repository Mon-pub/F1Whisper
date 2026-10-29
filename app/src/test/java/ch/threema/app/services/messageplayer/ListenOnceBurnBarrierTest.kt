package ch.threema.app.services.messageplayer

import ch.threema.app.services.FileService
import ch.threema.app.services.MediaConsumeOutcome
import ch.threema.app.services.MessageService
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper (eleventh fork review, F11-05; twelfth fork review, F12-02): incoming listen-once ownership must not be
 * released before the burn settles, and the barrier must come down only on a DURABLE settlement.
 *
 * **The F11-05 defect.** When a session with audible playback ended, `settleListenOnceSession` queued the burn on a
 * worker and released ownership immediately. In the fail-open case the durable claim write had FAILED, so until the
 * worker's consumed-metadata write landed the row still read playable: no claim, no consumption, no registered owner -
 * and the same player's token is re-entrant by design, so nothing refused a second playback.
 *
 * **The F12-02 defect.** The barrier then cleared on any non-throwing return of the metadata write, but `false` from
 * that write also meant an unreadable row, a throwing mutation, or a conditional update that lost every retry - none
 * of which wrote anything durable. It also cleared BEFORE the file removal, leaving a preemption window in which a
 * stale detached model could pass the durable gate while the decryptable file still existed. The settlement now
 * decides from the write's discriminated [MediaConsumeOutcome] plus the confirmed state of the media
 * ([ListenOnceSettlementDecision]), and the removal attempt precedes any clear.
 *
 * **What these tests execute.** The REAL `ListenOnceEnforcer.burn` against a worker the test holds still, with the
 * message service answering each discriminated outcome and the file service confirming or denying that media remains -
 * the review is explicit that a mock mapping every declined write to "already terminal" is not proof, and these mocks
 * speak the same discriminated language the production write now reports (each real return site's classification is
 * pinned in `MediaMetadataWriteTest`). The refusal itself (`AudioMessagePlayer.open` routing through the admission
 * decision and re-driving the burn) is executed in [ListenOnceIdentityCollisionTest] and pinned here at the source,
 * since the player requires a device to construct; each such assertion was proven red by removing the line it names.
 */
class ListenOnceBurnBarrierTest {

    private val messageService = mockk<MessageService>()
    private val fileService = mockk<FileService>(relaxed = true)
    private val model = MessageModel().apply { id = 77 }
    private val identity = ListenOnceMessageIdentity.of(model)

    private val worker = ManualWorker()
    private lateinit var productionWorker: Executor

    private val audioMessagePlayer = File("src/main/java/ch/threema/app/services/messageplayer/AudioMessagePlayer.java")
    private val enforcer = File("src/main/java/ch/threema/app/services/messageplayer/ListenOnceEnforcer.java")

    @BeforeTest
    fun setUp() {
        ListenOnceBurnBarrier.forgetAll()
        productionWorker = ListenOnceEnforcer.burnWorker
        ListenOnceEnforcer.burnWorker = worker
        // Unless a test says otherwise: the removal attempt leaves no media behind.
        every { fileService.hasPersistedMessageMedia(any()) } returns false
    }

    @AfterTest
    fun tearDown() {
        ListenOnceEnforcer.burnWorker = productionWorker
        ListenOnceBurnBarrier.forgetAll()
    }

    private fun stubOutcome(outcome: MediaConsumeOutcome) {
        every { messageService.consumeAndUpdateMediaMetadata(any(), any()) } returns outcome
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The lifecycle, with the worker held still and then failed
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the barrier is up from the moment the burn is requested, before any worker runs`() {
        stubOutcome(MediaConsumeOutcome.APPLIED)

        ListenOnceEnforcer.burn(model, messageService, fileService, false)

        // The worker has not run: this is the window in which the row still reads playable and ownership is already
        // released. The barrier is the only thing standing between it and a second playback.
        assertEquals(1, worker.pending)
        assertTrue(ListenOnceBurnBarrier.isSettling(identity), "the barrier must be raised synchronously")
    }

    @Test
    fun `a throwing durable write retains the barrier`() {
        every { messageService.consumeAndUpdateMediaMetadata(any(), any()) } throws IllegalStateException("disk full")

        ListenOnceEnforcer.burn(model, messageService, fileService, false)
        worker.runNext()

        assertTrue(
            ListenOnceBurnBarrier.isSettling(identity),
            "a burn whose durable half failed leaves the message in exactly the replay window the barrier closes",
        )
    }

    @Test
    fun `a re-driven burn that succeeds clears the barrier`() {
        // The retry path: open() refuses a settling message AND re-drives the burn. First attempt fails, the retry's
        // write goes through, and only then does admission return to the row.
        every { messageService.consumeAndUpdateMediaMetadata(any(), any()) } throws IllegalStateException("disk full")
        ListenOnceEnforcer.burn(model, messageService, fileService, false)
        worker.runNext()
        assertTrue(ListenOnceBurnBarrier.isSettling(identity))

        stubOutcome(MediaConsumeOutcome.APPLIED)
        ListenOnceEnforcer.burn(model, messageService, fileService, false)
        worker.runNext()

        assertFalse(ListenOnceBurnBarrier.isSettling(identity), "successful settlement is the ONLY release")
    }

    @Test
    fun `process death empties the barrier, like the ownership registry`() {
        ListenOnceBurnBarrier.raise(identity)
        ListenOnceBurnBarrier.forgetAll()
        assertFalse(ListenOnceBurnBarrier.isSettling(identity))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // F12-02: the settlement decides from the discriminated outcome, never from "the call returned"
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `an applied write clears, and only after the removal attempt`() {
        stubOutcome(MediaConsumeOutcome.APPLIED)
        every { fileService.removeMessageFiles(any<AbstractMessageModel>(), any<Boolean>()) } answers {
            // Executed ordering, not a source pin: at the instant the media is being destroyed the barrier must
            // still be up. Clearing first is the preemption window the review names - a second open between the
            // clear and the removal would find no barrier and a decryptable file.
            assertTrue(
                ListenOnceBurnBarrier.isSettling(identity),
                "the barrier must still be raised while the removal runs",
            )
            true
        }

        ListenOnceEnforcer.burn(model, messageService, fileService, false)
        worker.runNext()

        assertFalse(ListenOnceBurnBarrier.isSettling(identity))
        verify(exactly = 1) { fileService.removeMessageFiles(any<AbstractMessageModel>(), any<Boolean>()) }
    }

    @Test
    fun `an applied write clears even when the removal fails, because the durable refusal stands`() {
        // The written metadata refuses replay on its own (the caches and the caller's instance are reconciled by the
        // write), and the repair burn knows how to finish "flags written, files still present". Holding the barrier
        // for a filesystem error would hold the message hostage to a state the row no longer needs protecting from.
        stubOutcome(MediaConsumeOutcome.APPLIED)
        every { fileService.removeMessageFiles(any<AbstractMessageModel>(), any<Boolean>()) } returns false
        every { fileService.hasPersistedMessageMedia(any()) } returns true

        ListenOnceEnforcer.burn(model, messageService, fileService, false)
        worker.runNext()

        assertFalse(ListenOnceBurnBarrier.isSettling(identity))
    }

    @Test
    fun `an already terminal row clears - its reconciliation is the write's, pinned at the source`() {
        stubOutcome(MediaConsumeOutcome.ALREADY_TERMINAL)

        ListenOnceEnforcer.burn(model, messageService, fileService, false)
        worker.runNext()

        assertFalse(ListenOnceBurnBarrier.isSettling(identity))
        // And the removal attempt still ran: an interrupted burn's shape is "flags written, files still on disk",
        // and the terminal pass IS the repair for it.
        verify(exactly = 1) { fileService.removeMessageFiles(any<AbstractMessageModel>(), any<Boolean>()) }
    }

    @Test
    fun `a gone row clears only once its media is confirmed gone`() {
        stubOutcome(MediaConsumeOutcome.ROW_GONE)
        every { fileService.hasPersistedMessageMedia(any()) } returns true

        ListenOnceEnforcer.burn(model, messageService, fileService, false)
        worker.runNext()

        assertTrue(
            ListenOnceBurnBarrier.isSettling(identity),
            "the row cannot refuse anything anymore and the player still holds a model; a decryptable file is one " +
                "admission away from replay, so the barrier must hold",
        )

        // The retry's removal succeeds; now nothing decryptable remains and the barrier may come down.
        every { fileService.hasPersistedMessageMedia(any()) } returns false
        ListenOnceEnforcer.burn(model, messageService, fileService, false)
        worker.runNext()

        assertFalse(ListenOnceBurnBarrier.isSettling(identity))
    }

    @Test
    fun `an indeterminate write retains the barrier and does not touch the media`() {
        stubOutcome(MediaConsumeOutcome.INDETERMINATE)

        ListenOnceEnforcer.burn(model, messageService, fileService, false)
        worker.runNext()

        assertTrue(
            ListenOnceBurnBarrier.isSettling(identity),
            "an unreadable row, a throwing mutation or an exhausted retry wrote nothing durable; clearing here is " +
                "exactly the replay window the review names",
        )
        verify(exactly = 0) { fileService.removeMessageFiles(any<AbstractMessageModel>(), any<Boolean>()) }
    }

    @Test
    fun `a throwing removal after an applied write retains the barrier until the retry`() {
        // Stricter than F11 on purpose: the removal now precedes the clear, so a removal that THROWS leaves the
        // barrier up. The retry declines as already-terminal, re-attempts the removal, and clears then.
        stubOutcome(MediaConsumeOutcome.APPLIED)
        every {
            fileService.removeMessageFiles(any<AbstractMessageModel>(), any<Boolean>())
        } throws IllegalStateException("fs error")

        ListenOnceEnforcer.burn(model, messageService, fileService, false)
        worker.runNext()
        assertTrue(ListenOnceBurnBarrier.isSettling(identity))

        stubOutcome(MediaConsumeOutcome.ALREADY_TERMINAL)
        every { fileService.removeMessageFiles(any<AbstractMessageModel>(), any<Boolean>()) } returns true
        ListenOnceEnforcer.burn(model, messageService, fileService, false)
        worker.runNext()
        assertFalse(ListenOnceBurnBarrier.isSettling(identity))
    }

    @Test
    fun `a retained settlement marks no burn animation`() {
        val animationModel = MessageModel().apply {
            id = 78
            uid = "burn-animation-indeterminate"
        }
        stubOutcome(MediaConsumeOutcome.INDETERMINATE)

        ListenOnceEnforcer.burn(animationModel, messageService, fileService, true)
        worker.runNext()

        assertFalse(
            ListenOnceBurnRegistry.consumeBurnAnimation(ListenOnceMessageIdentity.of(animationModel)),
            "a burn that settled nothing must not tell the bubble it just burned",
        )
    }

    @Test
    fun `a settled burn still marks the animation it was asked for`() {
        val animationModel = MessageModel().apply {
            id = 79
            uid = "burn-animation-applied"
        }
        stubOutcome(MediaConsumeOutcome.APPLIED)

        ListenOnceEnforcer.burn(animationModel, messageService, fileService, true)
        worker.runNext()

        assertTrue(ListenOnceBurnRegistry.consumeBurnAnimation(ListenOnceMessageIdentity.of(animationModel)))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The settlement rule itself, exhaustively
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the settlement decision, exhaustively`() {
        for (mediaGone in listOf(true, false)) {
            assertTrue(ListenOnceSettlementDecision.clearsBarrier(MediaConsumeOutcome.APPLIED, mediaGone))
            assertTrue(ListenOnceSettlementDecision.clearsBarrier(MediaConsumeOutcome.ALREADY_TERMINAL, mediaGone))
            assertFalse(
                ListenOnceSettlementDecision.clearsBarrier(MediaConsumeOutcome.INDETERMINATE, mediaGone),
                "nothing durable is known; media state cannot make an indeterminate settlement durable",
            )
        }
        assertTrue(ListenOnceSettlementDecision.clearsBarrier(MediaConsumeOutcome.ROW_GONE, mediaConfirmedGone = true))
        assertFalse(ListenOnceSettlementDecision.clearsBarrier(MediaConsumeOutcome.ROW_GONE, mediaConfirmedGone = false))
    }

    @Test
    fun `only an indeterminate settlement skips the removal attempt`() {
        assertTrue(ListenOnceSettlementDecision.attemptsFileRemoval(MediaConsumeOutcome.APPLIED))
        assertTrue(ListenOnceSettlementDecision.attemptsFileRemoval(MediaConsumeOutcome.ALREADY_TERMINAL))
        assertTrue(ListenOnceSettlementDecision.attemptsFileRemoval(MediaConsumeOutcome.ROW_GONE))
        assertFalse(ListenOnceSettlementDecision.attemptsFileRemoval(MediaConsumeOutcome.INDETERMINATE))
    }

    @Test
    fun `only a settlement with a row left animates`() {
        assertTrue(ListenOnceSettlementDecision.marksBurnAnimation(MediaConsumeOutcome.APPLIED))
        assertTrue(ListenOnceSettlementDecision.marksBurnAnimation(MediaConsumeOutcome.ALREADY_TERMINAL))
        assertFalse(ListenOnceSettlementDecision.marksBurnAnimation(MediaConsumeOutcome.ROW_GONE))
        assertFalse(ListenOnceSettlementDecision.marksBurnAnimation(MediaConsumeOutcome.INDETERMINATE))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Presentation during the window
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a message whose burn is settling presents as spent even while its row still reads playable`() {
        assertTrue(
            ListenOnceSessionDecision.isSpentForPresentation(
                isListenOnce = true,
                isClaimed = false,
                isConsumed = false,
                hasLiveOwner = false,
                burnSettling = true,
            ),
            "this is the fail-open row: unclaimed, unconsumed, no owner - and its playback WILL be refused",
        )
    }

    @Test
    fun `the settling term does not leak onto ordinary messages`() {
        assertFalse(
            ListenOnceSessionDecision.isSpentForPresentation(
                isListenOnce = false,
                isClaimed = false,
                isConsumed = false,
                hasLiveOwner = false,
                burnSettling = true,
            ),
        )
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The refusal and the ordering, pinned where they live
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `open routes through the admission decision, keyed by the model's own identity`() {
        // F1Whisper (twelfth fork review, F12-01): the inline barrier-before-gate order became
        // ListenOnceAdmissionDecision.decide, where settling still dominates the durable gate (executed in
        // ListenOnceIdentityCollisionTest) but applicability comes first, so a barrier hit can no longer refuse - or
        // burn - a model that is not an incoming listen-once message. What must hold HERE is the wiring: open()
        // derives the settling answer from the SAME model's identity, routes through the shared decision, and the
        // settling refusal re-drives the burn with that same model before returning.
        val source = audioMessagePlayer.readText()
        val openBody = bodyOf(source, "protected void open(File decryptedFile)")
        assertTrue(
            openBody.contains("ListenOnceAdmissionDecision.decide(gate, ListenOnceBurnBarrier.isSettling(identity))"),
            "admission must come from the shared decision, fed by this model's own gate and identity",
        )
        assertTrue(
            openBody.contains("ListenOnceMessageIdentity.of(messageModel)"),
            "the identity must be derived from the model being opened, never from a bare integer id",
        )
        val settlingIndex = openBody.indexOf("ListenOnceAdmission.REFUSE_SETTLING")
        assertTrue(settlingIndex >= 0, "the settling refusal must exist")
        val refusalBlock = openBody.substring(settlingIndex, openBody.indexOf("ListenOnceAdmission.REFUSE_SPENT"))
        assertTrue(
            refusalBlock.contains("ListenOnceEnforcer.burn(messageModel,"),
            "the refusal must re-drive the burn with the barred model itself; without the retry a failed settlement " +
                "would bar the message forever",
        )
        assertTrue(refusalBlock.contains("return;"), "and must not fall through to release plaintext")
    }

    @Test
    fun `the enforcer raises the barrier before the worker hop and never clears it on failure`() {
        val burnBody = bodyOf(enforcer.readText(), "public static void burn(")
        val raiseIndex = burnBody.indexOf("ListenOnceBurnBarrier.raise(")
        val hopIndex = burnBody.indexOf("burnWorker.execute(")
        assertTrue(raiseIndex in 0 until hopIndex, "raised synchronously, or there is an instant with no barrier and no owner")
        val catchIndex = burnBody.lastIndexOf("catch (Exception e)")
        assertTrue(catchIndex > 0, "the worker body must catch, or a failed burn would kill the worker thread")
        assertFalse(
            burnBody.substring(catchIndex).contains("ListenOnceBurnBarrier.clear("),
            "the failure path must NOT clear: a failed durable write is exactly the window the barrier closes",
        )
    }

    @Test
    fun `the enforcer clears only through the settlement decision, after the removal attempt`() {
        // F1Whisper (twelfth fork review, F12-02): the executable half is `an applied write clears, and only after
        // the removal attempt` above; this pins the shape - one clear, guarded by the decision, textually after the
        // removal attempt - so a future edit cannot quietly reintroduce the unconditional early clear.
        val burnBody = bodyOf(enforcer.readText(), "public static void burn(")
        val removalIndex = burnBody.indexOf("fileService.removeMessageFiles(messageModel, true)")
        val decisionIndex = burnBody.indexOf("ListenOnceSettlementDecision.clearsBarrier(")
        val clearIndex = burnBody.indexOf("ListenOnceBurnBarrier.clear(")
        assertTrue(removalIndex >= 0 && decisionIndex >= 0 && clearIndex >= 0)
        assertTrue(removalIndex < decisionIndex, "the removal attempt must precede the settlement decision")
        assertTrue(decisionIndex < clearIndex, "and the decision must guard the clear")
        assertEquals(
            clearIndex,
            burnBody.lastIndexOf("ListenOnceBurnBarrier.clear("),
            "one clear, one guard; a second clear site would be a second chance to get it wrong",
        )
    }

    @Test
    fun `settlement raises the barrier before ownership is released`() {
        // In settleListenOnceSession, ListenOnceEnforcer.burn (which raises synchronously) must precede the ownership
        // release, so there is no instant at which neither an owner nor the barrier stands in front of the message.
        val settleBody = bodyOf(audioMessagePlayer.readText(), "private void settleListenOnceSession(")
        val burnIndex = settleBody.indexOf("ListenOnceEnforcer.burn(")
        val releaseIndex = settleBody.indexOf("ListenOnceOwnership.release(")
        assertTrue(burnIndex in 0 until releaseIndex, "burn (and with it the barrier) must come before the release")
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------------------------------------------------

    private class ManualWorker : Executor {
        private val queue = ArrayDeque<Runnable>()
        val pending: Int get() = queue.size

        override fun execute(command: Runnable) {
            queue.addLast(command)
        }

        fun runNext() {
            queue.removeFirst().run()
        }
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
            val character = source[index]
            if (character == '{') depth++
            if (depth > 0) body.append(character)
            if (character == '}') {
                depth--
                if (depth == 0) break
            }
            index++
        }
        return body.toString()
    }
}
