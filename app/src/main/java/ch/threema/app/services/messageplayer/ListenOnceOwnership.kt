package ch.threema.app.services.messageplayer

import java.util.concurrent.ConcurrentHashMap

/**
 * F1Whisper (fourth fork review, F4-10): who, in THIS process, is currently playing a listen-once voice message.
 *
 * The defect this exists to remove: the durable claim says only "plaintext was released to a player at least once". It does
 * not say whether that player is still going, and nothing else did either, so a claim that was live and a claim that was
 * abandoned by a process death were indistinguishable. That is fatal on the normal first-play path, which reaches the two
 * states in this order:
 *
 * ```
 * open()      -> claim written (claimed = true, consumed = false)
 * STATE_READY -> markAsConsumed() -> save + onModified -> the bubble rebinds
 * rebind      -> gate reads claimed-and-unconsumed -> BLOCKED_BURN_PENDING -> burn()
 * prepared()  -> play()                                   <- only now, after the refresh chain returns
 * ```
 *
 * The bubble finished the burn before playback had begun: the file was deleted and the controls collapsed under a message
 * the user had just started, and it became permanently unavailable. Not the accepted failed-playback tradeoff - this was the
 * active first playback.
 *
 * The missing fact is ownership, and it is deliberately NOT durable. A live session exists only in the process that is
 * playing, so:
 *
 * - [acquire] must happen BEFORE the claim is written, so no callback can ever observe the claim without also being able to
 *   observe the owner;
 * - a second session is refused rather than queued: one message, one playback, and the second caller must not be able to
 *   burn the first caller's audio out from under it;
 * - [isActive] is what the repair paths consult. A claim with a live owner is not abandoned, so they leave it alone;
 * - process death empties this map, so an abandoned claim goes back to looking abandoned and the bubble repairs it on the
 *   next bind - which is the behaviour the repair was written for in the first place. Same shape as
 *   [ListenOnceBurnRegistry], for the same reason.
 *
 * Only the holder of the token may [release], so a stale session that lost the race cannot unlock a message it does not own.
 */
object ListenOnceOwnership {
    private val owners = ConcurrentHashMap<Int, Any>()

    /**
     * Take ownership of [messageId] for [sessionToken].
     *
     * @return `true` if this token now owns the message - either because nothing did, or because this same token already
     * did (so a re-entrant open by the owning session is not refused). `false` if another live session owns it.
     */
    @JvmStatic
    fun acquire(messageId: Int, sessionToken: Any): Boolean {
        val existing = owners.putIfAbsent(messageId, sessionToken)
        return existing == null || existing === sessionToken
    }

    /**
     * Whether a live playback session in this process currently owns [messageId]. The repair paths use this to tell a live
     * claim from an abandoned one.
     */
    @JvmStatic
    fun isActive(messageId: Int): Boolean = owners.containsKey(messageId)

    /**
     * Give up ownership, if [sessionToken] is the owner. A non-owner's call is ignored.
     */
    @JvmStatic
    fun release(messageId: Int, sessionToken: Any) {
        owners.remove(messageId, sessionToken)
    }

    /** Test seam: forget every owner, as a process death would. */
    @JvmStatic
    fun forgetAll() {
        owners.clear()
    }
}
