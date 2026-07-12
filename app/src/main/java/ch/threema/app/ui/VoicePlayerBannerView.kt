package ch.threema.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Observer
import ch.threema.app.R
import ch.threema.app.ThreemaApplication
import ch.threema.app.services.messageplayer.VoiceBannerLogic
import ch.threema.app.services.messageplayer.VoiceMessagePlaybackHolder
import ch.threema.app.services.messageplayer.VoicePlaybackState
import ch.threema.app.utils.IntentDataUtil
import ch.threema.app.utils.RuntimeUtil
import ch.threema.base.utils.getThreemaLogger

private val logger = getThreemaLogger("VoicePlayerBannerView")

/**
 * F1Whisper: slim "now playing" bar for a voice message that keeps playing after the user leaves the
 * chat it was started in. Self-contained: it observes the app-scoped [VoiceMessagePlaybackHolder]
 * and drives all controls through it, so a host only needs to place the view (and, inside a chat,
 * tell it which chat is open via [setOpenChatUniqueId] so the banner hides for that chat's messages).
 */
class VoicePlayerBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val holder = VoiceMessagePlaybackHolder.getInstance(context)
    private val uiHandler = Handler(Looper.getMainLooper())

    private val playPauseButton: ImageView
    private val titleView: TextView
    private val timeView: TextView
    private val closeButton: ImageView

    private var latestState: VoicePlaybackState? = null

    /** Unique id of the chat currently open in the host (null in the conversation list). */
    private var openChatUniqueId: String? = null

    private val stateObserver = Observer<VoicePlaybackState?> { state ->
        latestState = state
        render()
    }

    private val timeTicker = object : Runnable {
        override fun run() {
            updateTime()
            uiHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_voice_player_banner, this, true)
        playPauseButton = findViewById(R.id.voice_banner_play_pause)
        titleView = findViewById(R.id.voice_banner_title)
        timeView = findViewById(R.id.voice_banner_time)
        closeButton = findViewById(R.id.voice_banner_close)

        // Enable the title marquee (requires the view to be "selected").
        titleView.isSelected = true

        playPauseButton.setOnClickListener { holder.togglePlayPause() }
        closeButton.setOnClickListener { holder.closePlayback() }
        findViewById<android.view.View>(R.id.voice_banner_tap_area).setOnClickListener { jumpToPlayingMessage() }

        visibility = GONE
    }

    fun setOpenChatUniqueId(uniqueId: String?) {
        if (openChatUniqueId != uniqueId) {
            openChatUniqueId = uniqueId
            render()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        holder.state.observeForever(stateObserver)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        holder.state.removeObserver(stateObserver)
        stopTicker()
    }

    private fun render() {
        val state = latestState
        val show = VoiceBannerLogic.shouldShowBanner(
            hasPlayback = state != null,
            isListenOnce = false, // listen-once never reaches here: the holder reports it as no playback
            playingChatUniqueId = state?.chatUniqueId,
            openChatUniqueId = openChatUniqueId,
        )
        if (!show || state == null) {
            visibility = GONE
            stopTicker()
            return
        }

        visibility = VISIBLE
        titleView.text = state.title
        if (state.isPlaying) {
            playPauseButton.setImageResource(R.drawable.ic_pause)
            playPauseButton.contentDescription = context.getString(R.string.pause)
            startTicker()
        } else {
            playPauseButton.setImageResource(R.drawable.ic_play)
            playPauseButton.contentDescription = context.getString(R.string.play)
            stopTicker()
        }
        updateTime()
    }

    private fun updateTime() {
        val durationMs = latestState?.durationMs ?: holder.currentDurationMs()
        timeView.text = VoiceBannerLogic.formatRemaining(durationMs, holder.currentPositionMs())
    }

    private fun startTicker() {
        uiHandler.removeCallbacks(timeTicker)
        uiHandler.post(timeTicker)
    }

    private fun stopTicker() {
        uiHandler.removeCallbacks(timeTicker)
    }

    private fun jumpToPlayingMessage() {
        val state = latestState ?: return
        val type = state.messageType ?: return
        if (state.messageId == 0) {
            return
        }
        // Resolve the message off the main thread (DB read), then open the chat + jump on the UI thread.
        RuntimeUtil.runOnWorkerThread {
            try {
                val serviceManager = ThreemaApplication.getServiceManager() ?: return@runOnWorkerThread
                val messageModel = serviceManager.messageService.getMessageModelFromId(state.messageId, type)
                    ?: return@runOnWorkerThread
                val intent = IntentDataUtil.getJumpToMessageIntent(context, messageModel)
                RuntimeUtil.runOnUiThread { context.startActivity(intent) }
            } catch (e: Exception) {
                logger.error("Unable to jump to playing voice message", e)
            }
        }
    }

    companion object {
        private const val TICK_INTERVAL_MS = 500L
    }
}
