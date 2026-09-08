package ch.threema.app.services.messageplayer

/**
 * F1Whisper (tenth fork review, F10-04): what settling a listen-once playback session must do.
 */
enum class ListenOnceSettlement {
    /** Not this session's to settle: it never owned the message, or something already settled it. */
    NOTHING,

    /**
     * Give the ownership token back without burning. Playback never became audible, so the accepted failed-playback
     * policy applies: the durable claim stays, and with no live owner left the bubble's repair path can finish it.
     */
    RELEASE_ONLY,

    /** Audible playback had begun and the session is ending, so finish the burn and then release. */
    BURN_AND_RELEASE,
}

/**
 * F1Whisper (tenth fork review, F10-04): the pure rules of a listen-once playback SESSION, as opposed to
 * [ListenOnceDecision], which is about the durable state of a message.
 *
 * **The two defects this exists to separate.**
 *
 * *Presentation was conflated with replay admission.* The bubble defined spent as `consumed || claimed`, with no term for
 * a live owner. The claim is written before the first audible frame and persisting it triggers a rebind, so on the normal
 * first-play path the bubble re-read its own session's claim and rendered the message as spent while it was playing: the
 * controls were refused and the progress collapsed under a playback the user had just started. Those are two different
 * questions. May a SECOND playback begin - no, once claimed, never. Is this message finished as far as the screen is
 * concerned - not while its own session is still running.
 *
 * *Termination was modelled on one route out of six.* Ownership was released only from explicit playback-state
 * callbacks, so leaving the chat, a controller disconnect, a playback error, an idle transition or the service being
 * destroyed all ended the session while leaving the in-memory registry saying it was live. The durable row stayed
 * claimed-and-unburned and the bubble's repair path correctly refused to touch it, because the registry still named an
 * owner. Recovery then waited for process death. In the supplied trace, incoming 3476 and the 1:1 control 998 both
 * reached this state: claimed, playback begun, the user left the chat, the media service stopped, and no burn and no
 * release followed.
 *
 * No Android imports, so both rules are unit-testable without a device, and both are called from the production paths
 * they govern rather than restated there.
 */
object ListenOnceSessionDecision {

    /**
     * What a terminating session owes the message.
     *
     * @param ownsSession      whether the settling player is still the registered owner. This is what makes settlement
     *                         idempotent: several terminal routes legitimately fire for one session, and only the first
     *                         may act.
     * @param playbackHadBegun whether audible playback ever started, the player's `hasPlayed`. A session that never
     *                         became audible must not burn, or a `STATE_ENDED` arriving without playback would destroy
     *                         an unheard message - the defect the guard was added for.
     * @param gate             the message's durable state, so an already-burned message is not burned twice and a
     *                         message that is not listen-once is not touched.
     */
    @JvmStatic
    fun settlementOnTermination(
        ownsSession: Boolean,
        playbackHadBegun: Boolean,
        gate: ListenOnceGate,
    ): ListenOnceSettlement {
        if (!ownsSession) {
            return ListenOnceSettlement.NOTHING
        }
        if (!playbackHadBegun) {
            return ListenOnceSettlement.RELEASE_ONLY
        }
        if (gate == ListenOnceGate.NOT_APPLICABLE || gate == ListenOnceGate.BLOCKED_CONSUMED) {
            return ListenOnceSettlement.RELEASE_ONLY
        }
        return ListenOnceSettlement.BURN_AND_RELEASE
    }

    /**
     * Whether the bubble should render this message as spent: collapsed, no controls, no progress.
     *
     * Deliberately spans both directions, unlike the playback gate: the sender's own copy burns on send and its bubble
     * shows the neutral note. And deliberately reads the persisted metadata rather than [ch.threema.storage.models.MessageState],
     * because a voice message moves to CONSUMED at playback START, so keying off the state would expire the bubble the
     * instant it began playing.
     *
     * Replay admission is the OTHER question and is deliberately not restated here: it is
     * [ListenOnceDecision.isPlaybackRefused], which `AudioMessagePlayer.open()` already consults before releasing any
     * plaintext, and it stays absolute - once claimed, no new session may ever start, whatever is on screen. A second
     * copy of that rule is exactly the drift this separation exists to prevent.
     *
     * @param hasLiveOwner whether a session in this process is playing it right now. A claim with a live owner is that
     *                     session's own claim, not a spent message; a burned message is spent whatever is running.
     * @param burnSettling whether a burn for this message is in flight or failed-awaiting-retry in this process
     *                     ([ListenOnceBurnBarrier]). F1Whisper (eleventh fork review, F11-05): during that window the
     *                     row can still read unclaimed and unconsumed - the fail-open session never got its claim
     *                     written - so without this term the bubble would offer controls for a playback that
     *                     [AudioMessagePlayer.open] is going to refuse.
     */
    @JvmStatic
    fun isSpentForPresentation(
        isListenOnce: Boolean,
        isClaimed: Boolean,
        isConsumed: Boolean,
        hasLiveOwner: Boolean,
        burnSettling: Boolean,
    ): Boolean {
        if (!isListenOnce) {
            return false
        }
        if (burnSettling) {
            return true
        }
        if (isConsumed) {
            return true
        }
        return isClaimed && !hasLiveOwner
    }

    /**
     * Whether the asynchronous claim worker's callback may still release plaintext to the player.
     *
     * The claim is written on a worker and reports back on the UI thread, so a chat exit, an explicit stop or a
     * replacement session can all land in between. The callback was unconditional, so it could call the player's open
     * path after that player had already been torn down - which would release plaintext into a dead session and, worse,
     * do it for a message a newer session might be responsible for.
     *
     * The generation is bumped whenever a session settles or a new one begins, so an equal reading means the session
     * that asked for the claim is still the session that is running. This is what keeps the accepted fail-open policy -
     * playback proceeds even when the claim write fails - confined to a session that is still current, instead of
     * turning a delayed failed claim into playback after teardown.
     */
    @JvmStatic
    fun releasesPlaintext(sessionGenerationAtRequest: Int, currentSessionGeneration: Int): Boolean =
        sessionGenerationAtRequest == currentSessionGeneration
}
