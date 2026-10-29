package ch.threema.app.services.messageplayer

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper (tenth fork review, F10-04): the listen-once playback SESSION.
 *
 * Two defects, one state machine.
 *
 * *A live claim was presented as spent.* The bubble defined spent as `consumed || claimed`, with no term for a live
 * owner. The claim is written before the first audible frame and persisting it fires `onModified`, so the bubble
 * rebinds and re-reads its own session's claim while that session is running: the controls were refused on the next
 * tap and the progress collapsed under a message the user had just started.
 *
 * *Interrupted teardown never released its owner.* Ownership came back only through explicit playback-state callbacks,
 * so leaving the chat, a controller disconnect, a playback error, an idle transition or the service being destroyed all
 * ended the session with the registry still naming an owner. The durable row stayed claimed-and-unburned, and the
 * bubble's repair path correctly refused to finish it, because a live owner means the claim is not abandoned. Nothing
 * recovered until process death. In the supplied trace, incoming 3476 and the 1:1 control 998 both ended exactly there:
 * claimed, playback begun, chat left, media service stopped, no burn and no release.
 *
 * These drive [ListenOnceSessionDecision] - the state machine the production paths call rather than restate - together
 * with the real [ListenOnceOwnership] registry, so the settlement, the idempotence and the ownership interplay are all
 * executed rather than asserted about. The routes that reach it inside `AudioMessagePlayer` need a media3 controller and
 * a live service, so that they are wired up is asserted against the source; each of those was proven red by removing
 * the line it names.
 */
class ListenOnceSessionLifecycleTest {

    private val audioMessagePlayer = File("src/main/java/ch/threema/app/services/messageplayer/AudioMessagePlayer.java")
    private val audioDecorator = File("src/main/java/ch/threema/app/adapters/decorators/AudioChatAdapterDecorator.java")

    // F1Whisper (twelfth fork review, F12-01): the registries key by stable identity, so the tests do too.
    private val identity = ListenOnceMessageIdentity.of(ch.threema.storage.models.MessageModel().apply { id = 4711 })
    private lateinit var session: Any
    private lateinit var otherSession: Any

    @BeforeTest
    fun setUp() {
        ListenOnceOwnership.forgetAll()
        session = Any()
        otherSession = Any()
    }

    @AfterTest
    fun tearDown() {
        ListenOnceOwnership.forgetAll()
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Presentation is not replay admission
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a claimed message with a live owner is not presented as spent`() {
        assertFalse(
            ListenOnceSessionDecision.isSpentForPresentation(
                isListenOnce = true,
                isClaimed = true,
                isConsumed = false,
                hasLiveOwner = true,
                burnSettling = false,
            ),
            "this is the rebind the claim itself causes; rendering it as spent collapses a playback in progress",
        )
    }

    @Test
    fun `a claimed message with no live owner is presented as spent`() {
        assertTrue(
            ListenOnceSessionDecision.isSpentForPresentation(
                isListenOnce = true,
                isClaimed = true,
                isConsumed = false,
                hasLiveOwner = false,
                burnSettling = false,
            ),
            "an abandoned claim is a message whose one playback is over",
        )
    }

    @Test
    fun `a burned message is spent whatever is running`() {
        assertTrue(
            ListenOnceSessionDecision.isSpentForPresentation(
                isListenOnce = true,
                isClaimed = true,
                isConsumed = true,
                hasLiveOwner = true,
                burnSettling = false,
            ),
            "consumed outranks a live owner: the media is gone, so there is nothing to show controls for",
        )
    }

    @Test
    fun `an ordinary voice message is never spent`() {
        assertFalse(
            ListenOnceSessionDecision.isSpentForPresentation(
                isListenOnce = false,
                isClaimed = true,
                isConsumed = true,
                hasLiveOwner = false,
                burnSettling = false,
            ),
        )
    }

    @Test
    fun `replay admission stays absolute while a session is live`() {
        // The other half of the split, and it must NOT soften: presentation asks whether the screen is finished with
        // the message, admission asks whether a second playback may begin. Once claimed, never.
        assertTrue(ListenOnceDecision.isPlaybackRefused(ListenOnceGate.BLOCKED_BURN_PENDING))
        assertTrue(ListenOnceDecision.isPlaybackRefused(ListenOnceGate.BLOCKED_CONSUMED))
        assertFalse(ListenOnceDecision.isPlaybackRefused(ListenOnceGate.PLAYABLE))
    }

    @Test
    fun `a second session is refused while the first owns the message`() {
        assertTrue(ListenOnceOwnership.acquire(identity, session))
        assertFalse(
            ListenOnceOwnership.acquire(identity, otherSession),
            "one message, one playback; a second caller must not be able to burn the first one's audio out from under it",
        )
        assertTrue(ListenOnceOwnership.isOwnedBy(identity, session))
        assertFalse(ListenOnceOwnership.isOwnedBy(identity, otherSession))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Settlement
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a natural end burns and releases`() {
        assertEquals(
            ListenOnceSettlement.BURN_AND_RELEASE,
            settle(ownsSession = true, playbackHadBegun = true, gate = ListenOnceGate.BLOCKED_BURN_PENDING),
        )
    }

    @Test
    fun `a chat exit after audible playback burns and releases, exactly as a natural end does`() {
        // The trace's failing case. Nothing about the settlement depends on WHICH route ended the session, which is
        // the point: the rule used to live at one call site and the other five had none.
        assertEquals(
            ListenOnceSettlement.BURN_AND_RELEASE,
            settle(ownsSession = true, playbackHadBegun = true, gate = ListenOnceGate.BLOCKED_BURN_PENDING),
        )
    }

    @Test
    fun `a session that never became audible releases without burning`() {
        // The accepted failed-playback policy, preserved: the durable claim stays, and with no live owner left the
        // bubble's repair path can finish it. Burning here would destroy an unheard message.
        assertEquals(
            ListenOnceSettlement.RELEASE_ONLY,
            settle(ownsSession = true, playbackHadBegun = false, gate = ListenOnceGate.BLOCKED_BURN_PENDING),
        )
    }

    @Test
    fun `an already burned message is not burned again`() {
        assertEquals(
            ListenOnceSettlement.RELEASE_ONLY,
            settle(ownsSession = true, playbackHadBegun = true, gate = ListenOnceGate.BLOCKED_CONSUMED),
        )
    }

    @Test
    fun `an ordinary voice message settles without touching anything`() {
        assertEquals(
            ListenOnceSettlement.RELEASE_ONLY,
            settle(ownsSession = true, playbackHadBegun = true, gate = ListenOnceGate.NOT_APPLICABLE),
        )
    }

    @Test
    fun `settlement is idempotent across the routes that all fire for one session`() {
        // Natural end, then the explicit stop that follows it, then the player release that follows that. Only the
        // first may act; the rest must be no-ops, or a second burn would run against a row that has moved on.
        ListenOnceOwnership.acquire(identity, session)

        val first = settle(
            ownsSession = ListenOnceOwnership.isOwnedBy(identity, session),
            playbackHadBegun = true,
            gate = ListenOnceGate.BLOCKED_BURN_PENDING,
        )
        assertEquals(ListenOnceSettlement.BURN_AND_RELEASE, first)
        ListenOnceOwnership.release(identity, session)

        repeat(2) {
            assertEquals(
                ListenOnceSettlement.NOTHING,
                settle(
                    ownsSession = ListenOnceOwnership.isOwnedBy(identity, session),
                    playbackHadBegun = true,
                    gate = ListenOnceGate.BLOCKED_BURN_PENDING,
                ),
            )
        }
    }

    @Test
    fun `a session that lost ownership cannot settle a replacement`() {
        ListenOnceOwnership.acquire(identity, session)
        ListenOnceOwnership.release(identity, session)
        ListenOnceOwnership.acquire(identity, otherSession)

        assertEquals(
            ListenOnceSettlement.NOTHING,
            settle(
                ownsSession = ListenOnceOwnership.isOwnedBy(identity, session),
                playbackHadBegun = true,
                gate = ListenOnceGate.BLOCKED_BURN_PENDING,
            ),
            "a stale session must not burn the audio a newer one is playing",
        )
        assertTrue(ListenOnceOwnership.isOwnedBy(identity, otherSession), "and must not release its ownership either")
    }

    @Test
    fun `process death makes an abandoned claim look abandoned again`() {
        ListenOnceOwnership.acquire(identity, session)
        assertTrue(ListenOnceOwnership.isActive(identity))

        ListenOnceOwnership.forgetAll()

        assertFalse(ListenOnceOwnership.isActive(identity))
        assertTrue(
            ListenOnceSessionDecision.isSpentForPresentation(
                isListenOnce = true,
                isClaimed = true,
                isConsumed = false,
                hasLiveOwner = ListenOnceOwnership.isActive(identity),
                burnSettling = false,
            ),
            "the existing repair behaviour is unchanged: with the registry empty the bubble finishes the burn",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The asynchronous claim callback
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a claim callback for the still current session releases plaintext`() {
        assertTrue(ListenOnceSessionDecision.releasesPlaintext(sessionGenerationAtRequest = 7, currentSessionGeneration = 7))
    }

    @Test
    fun `a claim callback whose session ended releases nothing`() {
        // The worker writes the claim and reports back on the UI thread, so a chat exit, an explicit stop or a
        // replacement session can all land in between. Unconditional, it called the player's open path after teardown.
        assertFalse(ListenOnceSessionDecision.releasesPlaintext(sessionGenerationAtRequest = 7, currentSessionGeneration = 8))
    }

    @Test
    fun `settling then starting a new session moves the generation twice`() {
        // Both transitions have to bump, or a stale callback could match a later session by coincidence: settling
        // alone would let this player's next open() reuse the generation its own in-flight callback captured.
        var generation = 0
        val atRequest = generation
        generation++ // the session settles
        assertFalse(ListenOnceSessionDecision.releasesPlaintext(atRequest, generation))
        generation++ // a new session begins
        assertFalse(ListenOnceSessionDecision.releasesPlaintext(atRequest, generation))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The routes, asserted where a device would otherwise be needed
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `every terminal route settles the session`() {
        val source = audioMessagePlayer.readText()
        for ((signature, route) in listOf(
            "public void onPlayerError(" to "a playback error",
            "public void onPlaybackStateChanged(" to "the natural end and the idle transition",
            "public boolean stop(" to "the chat teardown",
            "private void releasePlayer(" to "the player release",
            "private void reconcileAfterRebind(" to "a controller that has moved on",
        )) {
            assertTrue(
                bodyOf(source, signature).contains("settleListenOnceSession("),
                "$route must settle the session; leaving the owner registered is what stranded the durable claim",
            )
        }
    }

    @Test
    fun `the chat teardown settles before its own early return`() {
        // stop() does nothing when this player IS the one on the controller, which is the normal case for the message
        // that was just playing. Settling after that guard would mean leaving the chat mid-playback settled nothing.
        val body = bodyOf(audioMessagePlayer.readText(), "public boolean stop(")
        val settleIndex = body.indexOf("settleListenOnceSession(")
        val guardIndex = body.indexOf("if (!playerMediaMatchesControllerMedia())")
        assertTrue(settleIndex >= 0 && guardIndex >= 0)
        assertTrue(settleIndex < guardIndex, "the settlement must not sit behind the early return")
    }

    @Test
    fun `the burn still requires audible playback of this message`() {
        // Both terms, unchanged in meaning from F4-10. hasPlayed alone would let a playback event belonging to another
        // item burn this message; the media match alone says nothing about whether a frame was ever heard.
        val body = bodyOf(audioMessagePlayer.readText(), "private boolean audiblePlaybackOfThisMessageBegan(")
        assertTrue(body.contains("hasPlayed && playerMediaMatchesControllerMedia()"))
    }

    @Test
    fun `the bubble asks the session state machine, not a local copy of the rule`() {
        val body = bodyOf(audioDecorator.readText(), "private static boolean isListenOnceSpent(")
        assertTrue(
            body.contains("ListenOnceSessionDecision.isSpentForPresentation("),
            "the presentation rule must come from the shared state machine",
        )
        assertTrue(
            body.contains("ListenOnceOwnership.isActive("),
            "and must include the live-owner term, which is the whole correction",
        )
    }

    @Test
    fun `the claim callback is gated on the session generation`() {
        val body = bodyOf(audioMessagePlayer.readText(), "protected void open(")
        assertTrue(
            body.contains("ListenOnceSessionDecision.releasesPlaintext("),
            "a callback whose session ended must not release plaintext into a torn-down player",
        )
    }

    private fun settle(ownsSession: Boolean, playbackHadBegun: Boolean, gate: ListenOnceGate): ListenOnceSettlement =
        ListenOnceSessionDecision.settlementOnTermination(ownsSession, playbackHadBegun, gate)

    /**
     * The body of the method whose declaration starts with [signature], by brace matching from the first `{` after it.
     */
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
