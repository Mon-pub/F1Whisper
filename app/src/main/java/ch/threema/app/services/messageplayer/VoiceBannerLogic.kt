package ch.threema.app.services.messageplayer

/**
 * F1Whisper: pure (Android-free) decision + formatting helpers for the background voice-playback
 * "now playing" banner. Kept dependency-free so the logic is unit-testable without Robolectric.
 */
object VoiceBannerLogic {
    /**
     * Format the time shown on the banner. While the duration is known we show the REMAINING time
     * (Signal style, counts down to 0:00); if the duration is not yet known (== 0) we fall back to
     * the elapsed position so the label is never blank.
     */
    fun formatRemaining(durationMs: Long, positionMs: Long): String {
        val millis = if (durationMs > 0L) {
            (durationMs - positionMs).coerceAtLeast(0L)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        val totalSeconds = millis / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(minutes, seconds)
    }

    /**
     * Decide whether the banner should be visible.
     *
     * - hidden when nothing is playing,
     * - hidden for listen-once voices (excluded from background continuation entirely),
     * - hidden inside the very chat the playing message belongs to (that chat renders the bubble
     *   itself, so a banner there would be redundant). [openChatUniqueId] is null for the
     *   conversation list, where the banner is always shown while a non-listen-once voice plays.
     */
    fun shouldShowBanner(
        hasPlayback: Boolean,
        isListenOnce: Boolean,
        playingChatUniqueId: String?,
        openChatUniqueId: String?,
    ): Boolean {
        if (!hasPlayback) {
            return false
        }
        if (isListenOnce) {
            return false
        }
        if (openChatUniqueId != null && openChatUniqueId == playingChatUniqueId) {
            return false
        }
        return true
    }

    /**
     * Decide what a recycled voice player should do when it is re-bound to the live media session on
     * re-entering the chat it was left playing in (see AudioMessagePlayer.rebindMediaControllerIfChanged):
     *
     * - [RebindAction.RESUME]  — the shared player is still on THIS message and playing: reattach and
     *   keep the bubble live (advancing seekbar, working controls).
     * - [RebindAction.PAUSE]   — still on THIS message but paused (e.g. paused via the banner while
     *   away): reattach, show paused.
     * - [RebindAction.STOP]    — the shared player has moved on / ended: reset the bubble so it is not
     *   stuck showing "playing".
     */
    @JvmStatic
    fun reconcileRebind(mediaMatches: Boolean, isPlaying: Boolean, isEndedOrIdle: Boolean): RebindAction {
        if (!mediaMatches || isEndedOrIdle) {
            return RebindAction.STOP
        }
        return if (isPlaying) RebindAction.RESUME else RebindAction.PAUSE
    }

    /**
     * A kept (background) voice player is released + its decrypted file deleted only when the message
     * that just ended is the one whose chat was left running in the background (detached). In-chat
     * playback ends are left to the normal chat teardown, unchanged.
     */
    @JvmStatic
    fun shouldReleaseKeptPlayerOnEnd(endedMessageId: Int, detachedMessageId: Int): Boolean =
        endedMessageId != 0 && endedMessageId == detachedMessageId

    /**
     * Whether a media3 playback-state change should be handled as the end of a background session
     * (hide banner, release the kept player + delete its file, disconnect):
     *
     * - a natural STATE_ENDED always is,
     * - a STATE_IDLE is ONLY for a detached background session — a playback error out-of-chat lands
     *   in STATE_IDLE rather than STATE_ENDED, and would otherwise leave the plaintext lingering.
     *   Initial-connection and in-chat STATE_IDLE (no detached session) are left to refreshState.
     */
    @JvmStatic
    fun shouldTreatAsPlaybackEnd(isEnded: Boolean, isIdle: Boolean, hasDetachedSession: Boolean): Boolean =
        isEnded || (isIdle && hasDetachedSession)

    /**
     * Decide whether the voice playback service should stop when a controller disconnects.
     *
     * The service must stop iff NO real client controller remains connected. Two kinds of controllers
     * must NOT keep it alive:
     * - the media3 media-notification internal controller ([ConnectedControllerFlags.isMediaNotification]),
     *   which is always connected while the service runs (counting it would make the service immortal),
     * - the controller that is disconnecting right now ([ConnectedControllerFlags.isDisconnecting]),
     *   which may still appear in the connected-controllers list at onDisconnected time.
     *
     * All app controllers (a chat fragment's and the app-scoped background holder's) share one process
     * uid, so counting by uid is wrong — this counts the actual distinct controllers instead.
     */
    @JvmStatic
    fun shouldStopServiceOnDisconnect(controllers: List<ConnectedControllerFlags>): Boolean =
        controllers.none { !it.isMediaNotification && !it.isDisconnecting }
}

enum class RebindAction {
    RESUME,
    PAUSE,
    STOP,
}

/** Flags describing one controller connected to the voice playback MediaSession. */
data class ConnectedControllerFlags(
    val isMediaNotification: Boolean,
    val isDisconnecting: Boolean,
)
