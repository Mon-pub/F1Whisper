package ch.threema.app.services.messageplayer

/**
 * F1Whisper: what may be done with a listen-once voice message right now.
 *
 * The state that matters, and that the previous single-boolean model could not express, is
 * [BLOCKED_BURN_PENDING]: playback was started and never finished. Enforcement used to run entirely
 * from the `STATE_ENDED` callback and persist on a worker thread, so until that worker committed,
 * nothing on disk recorded that the message had been played. Killing the app mid-playback left the
 * message pristine, and it could be played again - as many times as the user was willing to kill the
 * app.
 */
enum class ListenOnceGate {
    /** Not an incoming listen-once voice message. Ordinary playback rules apply. */
    NOT_APPLICABLE,

    /** A listen-once message that has never been claimed. It may be played, exactly once. */
    PLAYABLE,

    /**
     * Claimed but not burned: playback began and did not reach the end, most likely because the
     * process died. Replay is refused and the interrupted burn is finished.
     */
    BLOCKED_BURN_PENDING,

    /** Already burned. The media is gone. */
    BLOCKED_CONSUMED,
}

/**
 * F1Whisper: the pure half of listen-once enforcement.
 *
 * The lifecycle is deliberately two-phase - claim, then burn - rather than one atomic burn:
 *
 * - **Claim** is written *before* the decrypted audio is handed to the media player. It is the point
 *   of no return for *replay*; from here on the message can never be played again, whatever happens
 *   to the process.
 * - **Burn** (delete the media, mark consumed) still happens when playback ends.
 *
 * Collapsing the two by burning at claim time would delete the file the player is about to read, and
 * would restore the "expires before being listened" defect that the player's `hasPlayed` guard was
 * added to fix: any `STATE_ENDED` arriving without real playback would destroy an unheard message.
 * Splitting them means the only thing an interrupted playback can produce is
 * [ListenOnceGate.BLOCKED_BURN_PENDING], which is repaired the next time the bubble binds - the same
 * claim-then-repair shape `ExpiryRepairDecision` uses for an interrupted expiry countdown.
 *
 * Enforcement is client-side and best-effort throughout: a modified client, a rooted device or a
 * screen recorder still defeats it. What this removes is the replay that an *unmodified* client
 * would otherwise hand to anyone who force-stops the app.
 */
object ListenOnceDecision {

    /**
     * @param isOutbox the sender never has playback restricted; they are the one who set it.
     * @param isFileMessage the listen-once flag lives in file-data metadata.
     * @param isListenOnce the `lo` metadata flag.
     * @param isClaimed the `locl` metadata flag: plaintext was released to a player at least once.
     * @param isConsumed the `loc` metadata flag: the burn completed.
     */
    @JvmStatic
    fun evaluate(
        isOutbox: Boolean,
        isFileMessage: Boolean,
        isListenOnce: Boolean,
        isClaimed: Boolean,
        isConsumed: Boolean,
    ): ListenOnceGate {
        if (isOutbox || !isFileMessage || !isListenOnce) {
            return ListenOnceGate.NOT_APPLICABLE
        }
        // Consumed outranks claimed: a burned message is always claimed, and reporting it as merely
        // burn-pending would make every bind re-run the burn.
        if (isConsumed) {
            return ListenOnceGate.BLOCKED_CONSUMED
        }
        if (isClaimed) {
            return ListenOnceGate.BLOCKED_BURN_PENDING
        }
        return ListenOnceGate.PLAYABLE
    }

    /**
     * @return whether the bubble must refuse to start playback. True for both blocked states, so a
     * caller that only wants "can the user press play" does not have to enumerate them.
     */
    @JvmStatic
    fun isPlaybackRefused(gate: ListenOnceGate): Boolean =
        gate == ListenOnceGate.BLOCKED_BURN_PENDING || gate == ListenOnceGate.BLOCKED_CONSUMED

    /**
     * @return whether a claim must be written before this message's plaintext may be released to a
     * player. Only [ListenOnceGate.PLAYABLE] needs one; the blocked states already have theirs and
     * [ListenOnceGate.NOT_APPLICABLE] never gets one.
     */
    @JvmStatic
    fun needsClaimBeforeRelease(gate: ListenOnceGate): Boolean = gate == ListenOnceGate.PLAYABLE
}
