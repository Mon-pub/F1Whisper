package ch.threema.app.services.messageplayer

import android.content.ComponentName
import android.content.Context
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import ch.threema.app.R
import ch.threema.app.ThreemaApplication
import ch.threema.app.services.VoiceMessagePlayerService
import ch.threema.base.utils.getThreemaLogger
import com.google.common.util.concurrent.ListenableFuture

private val logger = getThreemaLogger("VoiceMessagePlaybackHolder")

/**
 * F1Whisper: observable state of the voice message currently playing in the background. `null` when
 * nothing is playing (or the playing message is listen-once, which is excluded from continuation).
 */
data class VoicePlaybackState(
    val isPlaying: Boolean,
    val title: String,
    val durationMs: Long,
    val messageId: Int,
    val messageType: String?,
    val chatUniqueId: String?,
)

/**
 * F1Whisper: app-scoped owner of a single [MediaController] that keeps a voice message playing when
 * the user leaves the chat it was started from (Signal-style "now playing" banner).
 *
 * Background: playback runs in [VoiceMessagePlayerService] (a media3 `MediaSessionService`). The
 * chat fragment owns its own controller and tears it down on leave; media3 then auto-stops the
 * service once the LAST controller disconnects. This holder keeps ONE additional controller
 * connected for the whole time a (non-listen-once) voice is playing, so releasing the fragment's
 * controller during the list<->chat handoff never empties the controller set and never stops the
 * service mid-playback.
 *
 * Listen-once voices are deliberately NOT continued: [ensureConnected] is not called for them and,
 * defensively, any listen-once media observed on the shared player is reported as no playback.
 */
class VoiceMessagePlaybackHolder private constructor(private val appContext: Context) {
    companion object {
        // Keys written into the playing MediaItem's metadata extras by AudioMessagePlayer so this
        // app-scoped holder (which has no access to the chat adapter) can identify the message.
        const val EXTRA_MESSAGE_ID = "f1w_msg_id"
        const val EXTRA_MESSAGE_TYPE = "f1w_msg_type"
        const val EXTRA_CHAT_UNIQUE_ID = "f1w_chat_uid"
        const val EXTRA_LISTEN_ONCE = "f1w_listen_once"

        @Volatile
        private var instance: VoiceMessagePlaybackHolder? = null

        @JvmStatic
        fun getInstance(context: Context): VoiceMessagePlaybackHolder =
            instance ?: synchronized(this) {
                instance ?: VoiceMessagePlaybackHolder(context.applicationContext).also { instance = it }
            }
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    // Message id of the voice whose chat was left running in the background (0 = attached/in-chat).
    // Only for this message does the holder release the kept player + delete its file when the track
    // ends out-of-chat; in-chat ends are left to the normal chat teardown.
    private var detachedMessageId: Int = 0

    private val mutableState = MutableLiveData<VoicePlaybackState?>(null)
    val state: LiveData<VoicePlaybackState?> = mutableState

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshState()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // If a detached background voice is replaced by another track, clean it up so its
            // decrypted file does not linger (its own STATE_ENDED will never fire now).
            val detached = detachedMessageId
            if (detached != 0) {
                val newId = mediaItem?.mediaMetadata?.extras?.getInt(EXTRA_MESSAGE_ID, 0) ?: 0
                if (newId != detached) {
                    detachedMessageId = 0
                    releaseKeptPlayer(detached)
                }
            }
            refreshState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Out of the originating chat there is no adapter to auto-advance to a next voice, so a
            // natural end (STATE_ENDED) — or, for a detached background session, a playback error that
            // lands in STATE_IDLE — means: hide the banner, release the kept player + delete its file,
            // and let the service shut down (this holder disconnecting is what allows the
            // last-controller auto-stop to fire).
            val treatAsEnd = VoiceBannerLogic.shouldTreatAsPlaybackEnd(
                playbackState == Player.STATE_ENDED,
                playbackState == Player.STATE_IDLE,
                detachedMessageId != 0,
            )
            if (treatAsEnd) {
                onPlaybackEnded()
            } else {
                refreshState()
            }
        }
    }

    /**
     * Connect the background controller (idempotent). Called by [AudioMessagePlayer] when a
     * non-listen-once voice message is opened for playback, so the controller is already connected
     * by the time the user might leave the chat.
     */
    @MainThread
    fun ensureConnected() {
        if (controller?.isConnected == true) {
            // Already connected and live.
            return
        }
        val pending = controllerFuture
        if (pending != null && !pending.isDone) {
            // A connection attempt is already in flight.
            return
        }
        // Either nothing is connected yet, or a stale completed future is holding a dead controller
        // (e.g. the service was stopped after all controllers disconnected). Drop the stale handle and
        // build a fresh connection — without this self-heal a non-null-but-dead future would make every
        // later ensureConnected() a silent no-op, so the holder could never reconnect.
        disconnect()
        val token = SessionToken(appContext, ComponentName(appContext, VoiceMessagePlayerService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener({
            // Guard against a superseded attempt: if this future completed (isDone) but a later
            // ensureConnected() ran before this posted callback and self-healed it away, `controllerFuture`
            // now points at a newer connection. Bail out untouched so we neither bind this (released)
            // controller nor tear down the newer in-flight future.
            if (controllerFuture !== future) {
                return@addListener
            }
            try {
                val connected = future.get()
                controller = connected
                connected.addListener(playerListener)
                refreshState()
            } catch (e: Exception) {
                logger.error("Failed to connect background voice controller", e)
                disconnect()
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    /**
     * True iff a non-listen-once voice message is actively playing right now. The chat fragment uses
     * this at leave-time to decide whether to keep the service alive (skip its teardown) or perform
     * the normal full stop.
     */
    @MainThread
    fun isPlayingContinuable(): Boolean {
        val c = controller ?: return false
        if (!c.isConnected || !c.isPlaying) {
            return false
        }
        val item = c.currentMediaItem ?: return false
        return !(item.mediaMetadata.extras?.getBoolean(EXTRA_LISTEN_ONCE, false) ?: false)
    }

    @MainThread
    fun togglePlayPause() {
        val c = controller ?: return
        if (!c.isConnected) {
            return
        }
        if (c.isPlaying) {
            c.pause()
        } else {
            c.play()
        }
    }

    /** Message id currently loaded on the background controller (0 if none). */
    @MainThread
    fun currentMessageId(): Int {
        val c = controller ?: return 0
        if (!c.isConnected) {
            return 0
        }
        val item = c.currentMediaItem ?: return 0
        return item.mediaMetadata.extras?.getInt(EXTRA_MESSAGE_ID, 0) ?: 0
    }

    /**
     * Mark the currently playing voice as a detached background session (its chat was just torn down
     * while it keeps playing). Called by the chat fragment on its keep-playing teardown.
     */
    @MainThread
    fun markDetached() {
        detachedMessageId = currentMessageId()
    }

    /**
     * The chat that owns [messageId] was re-entered and its player re-bound to a live controller, so
     * it is no longer an orphaned background session.
     */
    @MainThread
    fun markReattached(messageId: Int) {
        if (messageId != 0 && messageId == detachedMessageId) {
            detachedMessageId = 0
        }
    }

    /** Banner close button: stop playback, clear the queue and release the background controller. */
    @MainThread
    fun closePlayback() {
        val id = currentMessageId()
        controller?.takeIf { it.isConnected }?.let {
            it.stop()
            it.clearMediaItems()
        }
        mutableState.value = null
        disconnect()
        // A Close is only reachable out-of-chat, so it always ends a detached background session:
        // release the kept player + delete its decrypted file.
        if (VoiceBannerLogic.shouldReleaseKeptPlayerOnEnd(id, detachedMessageId)) {
            releaseKeptPlayer(id)
        }
        detachedMessageId = 0
    }

    /**
     * Release the background controller WITHOUT stopping the shared player. Used by the chat
     * fragment's normal (non-continue) teardown path, which already stops the player and the
     * service itself; this just makes sure the holder isn't the lingering last controller.
     */
    @MainThread
    fun disconnectQuietly() {
        mutableState.value = null
        // Defensive: the non-continue teardown never keeps a background session, so make sure an
        // abnormal controller death can't leave a stale detached mark set (which could otherwise
        // trigger a premature release/delete of a replayed message's cache later).
        detachedMessageId = 0
        disconnect()
    }

    @MainThread
    fun currentPositionMs(): Long {
        val c = controller ?: return 0L
        return if (c.isConnected) c.currentPosition else 0L
    }

    @MainThread
    fun currentDurationMs(): Long {
        val c = controller ?: return 0L
        if (!c.isConnected) {
            return 0L
        }
        val duration = c.duration
        return if (duration == C.TIME_UNSET) 0L else duration
    }

    @MainThread
    private fun onPlaybackEnded() {
        val currentId = currentMessageId()
        // On a playback error the current item may already be cleared (currentId == 0); fall back to
        // the detached id so the kept player + its decrypted file are still cleaned up.
        val targetId = if (currentId != 0) currentId else detachedMessageId
        mutableState.value = null
        disconnect()
        // Out-of-chat end (natural end, or an error that reset the player): release the kept player +
        // delete its decrypted file. In-chat ends (no detached session) are left to the normal chat
        // teardown.
        if (VoiceBannerLogic.shouldReleaseKeptPlayerOnEnd(targetId, detachedMessageId)) {
            releaseKeptPlayer(targetId)
        }
        if (targetId == detachedMessageId) {
            detachedMessageId = 0
        }
    }

    @MainThread
    private fun releaseKeptPlayer(messageId: Int) {
        if (messageId == 0) {
            return
        }
        try {
            val serviceManager = ThreemaApplication.getServiceManager() ?: return
            serviceManager.messagePlayerService.releasePlayer(messageId)
        } catch (e: Exception) {
            logger.error("Failed to release kept background player {}", messageId, e)
        }
    }

    @MainThread
    private fun refreshState() {
        val c = controller
        if (c == null || !c.isConnected) {
            mutableState.value = null
            return
        }
        val item = c.currentMediaItem
        val playbackState = c.playbackState
        if (item == null || playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            mutableState.value = null
            return
        }
        val extras = item.mediaMetadata.extras
        val isListenOnce = extras?.getBoolean(EXTRA_LISTEN_ONCE, false) ?: false
        if (isListenOnce) {
            // Listen-once voices never appear in the banner nor continue in the background.
            mutableState.value = null
            return
        }
        val title = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.voice_message)
        val duration = c.duration.let { if (it == C.TIME_UNSET) 0L else it }
        mutableState.value = VoicePlaybackState(
            isPlaying = c.isPlaying,
            title = title,
            durationMs = duration,
            messageId = extras?.getInt(EXTRA_MESSAGE_ID, 0) ?: 0,
            messageType = extras?.getString(EXTRA_MESSAGE_TYPE),
            chatUniqueId = extras?.getString(EXTRA_CHAT_UNIQUE_ID),
        )
    }

    @MainThread
    private fun disconnect() {
        // Exception-safe: the controller/future may be dead here (the service was stopped out from
        // under us), and this is also the stale-handle cleanup used by ensureConnected's self-heal.
        controller?.let { c ->
            try {
                c.removeListener(playerListener)
            } catch (e: Exception) {
                logger.debug("Ignoring error removing listener on disconnect", e)
            }
        }
        controllerFuture?.let {
            try {
                MediaController.releaseFuture(it)
            } catch (e: Exception) {
                logger.debug("Ignoring error releasing controller future", e)
            }
        }
        controllerFuture = null
        controller = null
    }
}
