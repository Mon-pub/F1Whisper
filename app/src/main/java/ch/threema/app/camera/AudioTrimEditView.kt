/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * F1Whisper - audio trim editor for the send/preview flow.
 */
package ch.threema.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media3.common.C.TIME_END_OF_SOURCE
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import ch.threema.app.ExecutorServices
import ch.threema.app.R
import ch.threema.app.ui.AudioTrimView
import ch.threema.app.ui.MediaItem
import ch.threema.app.utils.IconUtil
import ch.threema.app.utils.LocaleUtil
import ch.threema.app.utils.RuntimeUtil
import ch.threema.app.utils.VideoUtil
import ch.threema.base.utils.getThreemaLogger

private val logger = getThreemaLogger("AudioTrimEditView")

/**
 * F1Whisper: trim editor for an attached audio file ([MediaItem.TYPE_AUDIO_FILE]) on the
 * send/preview screen. It mirrors the role of [VideoEditView] for audio:
 *
 * - renders the clip's waveform with dual start/end handles (reusing [AudioTrimView]),
 * - shows live start/end time labels,
 * - previews the selected window through an [ExoPlayer] clipped via [ClippingMediaSource], and
 * - writes the chosen window back into the media item's [MediaItem.startTimeMs]/[MediaItem.endTimeMs].
 *
 * The lossless crop itself happens later, on send, in `MessageServiceImpl.trimAudio` (per-container:
 * AAC/Opus remux, MP3 frame-cut, WAV PCM-cut; unsupported formats abort the send rather than ship
 * the untrimmed original). A moving playback cursor tracks the preview position on [AudioTrimView],
 * mirroring [VideoEditView]. All colors come from Material 3 theme attributes via the reused
 * [AudioTrimView] widget and the layout's theme-attribute references.
 */
@UnstableApi
class AudioTrimEditView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val audioTrimView: AudioTrimView
    private val playerView: PlayerView
    private val iconView: ImageView
    private val filenameView: TextView
    private val startTimeView: TextView
    private val durationTimeView: TextView
    private val endTimeView: TextView

    private var audioItem: MediaItem? = null
    private var player: ExoPlayer? = null
    private val mediaSourceFactory = DefaultMediaSourceFactory(context)

    private var durationMs: Long = 0L
    private var dragListener: OnTimelineDragListener? = null

    /**
     * F1Whisper: drives the moving playback cursor on [audioTrimView] while the clipped preview
     * plays, mirroring [VideoEditView]'s progressHandler. ExoPlayer has no per-frame position
     * callback, so we poll getCurrentPosition() on a ~100ms loop while playing (and once on
     * pause/stop to clear the cursor).
     */
    private val playheadHandler = Handler(Looper.getMainLooper())
    private val playheadUpdateRunnable = object : Runnable {
        override fun run() {
            updatePlayhead()
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusRequest: AudioFocusRequestCompat =
        AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { focusChange ->
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    player?.pause()
                }
            }
            .build()

    init {
        LayoutInflater.from(context).inflate(R.layout.view_audio_edit, this, true)
        audioTrimView = findViewById(R.id.audio_trim_view)
        playerView = findViewById(R.id.audio_player_view)
        iconView = findViewById(R.id.audio_icon)
        filenameView = findViewById(R.id.audio_filename)
        startTimeView = findViewById(R.id.audio_start_time)
        durationTimeView = findViewById(R.id.audio_duration_time)
        endTimeView = findViewById(R.id.audio_end_time)

        audioTrimView.setOnTrimChangedListener { startMs, endMs ->
            onTrimChanged(startMs, endMs)
        }
        audioTrimView.setOnDragStateListener(object : AudioTrimView.OnDragStateListener {
            override fun onDragStart() {
                dragListener?.onTimelineDragStart()
            }

            override fun onDragStop() {
                dragListener?.onTimelineDragStop()
                // Re-clip the preview to the committed window once the drag settles.
                preparePlayer()
            }
        })
    }

    /**
     * Set a timeline drag listener (used by the preview to suspend the ViewPager swipe while
     * trimming). Mirrors [VideoEditView.setOnTimelineDragListener].
     */
    fun setOnTimelineDragListener(listener: OnTimelineDragListener?) {
        dragListener?.onTimelineDragStop()
        this.dragListener = listener
    }

    /**
     * Bind the audio media item and render its trim editor. Reads the playback duration from the
     * container, loads the waveform, restores any previously chosen window, and prepares the
     * clipped preview player. Only effective while attached to the window.
     */
    fun setAudio(mediaItem: MediaItem) {
        this.audioItem = mediaItem
        releasePlayer()

        iconView.setImageResource(IconUtil.getMimeIcon(mediaItem.mimeType))
        filenameView.text = mediaItem.filename

        // Seed the duration from the item if known; the precise value is read off the thread below.
        durationMs = mediaItem.durationMs

        if (!isAttachedToWindow) {
            logger.warn("Audio edit view is not attached to window")
            return
        }

        initPlayer()
        loadDurationAndWaveform(mediaItem)
        updateTimeLabels(currentStartMs(), currentEndMs())
        preparePlayer()
    }

    private fun loadDurationAndWaveform(mediaItem: MediaItem) {
        val uri = mediaItem.uri ?: return
        // Render the waveform (the widget reads its own duration too, but we want it on the labels
        // and for the player clip before the decode finishes).
        audioTrimView.load(uri)

        ExecutorServices.voiceMessageThumbnailExecutorService.execute {
            val resolved = readDurationMs(uri)
            if (resolved > 0L) {
                RuntimeUtil.runOnUiThread {
                    if (audioItem !== mediaItem) {
                        return@runOnUiThread
                    }
                    durationMs = resolved
                    mediaItem.durationMs = resolved
                    // If the item has no window yet, treat it as the full clip.
                    if (mediaItem.endTimeMs == MediaItem.TIME_UNDEFINED) {
                        mediaItem.endTimeMs = resolved
                    }
                    audioTrimView.setSelection(currentStartMs(), currentEndMs(), resolved)
                    updateTimeLabels(currentStartMs(), currentEndMs())
                }
            }
        }
    }

    private fun readDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            logger.info("Unable to read audio duration", e)
            0L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                logger.debug("Failed to release MediaMetadataRetriever")
            }
        }
    }

    private fun onTrimChanged(startMs: Long, endMs: Long) {
        val item = audioItem ?: return
        item.startTimeMs = startMs
        // A full-length selection is stored as TIME_UNDEFINED so it is not treated as a trim.
        item.endTimeMs = if (durationMs > 0L && endMs >= durationMs) MediaItem.TIME_UNDEFINED else endMs
        updateTimeLabels(startMs, endMs)
    }

    private fun currentStartMs(): Long = audioItem?.startTimeMs ?: 0L

    private fun currentEndMs(): Long {
        val item = audioItem ?: return durationMs
        return if (item.endTimeMs == MediaItem.TIME_UNDEFINED) durationMs else item.endTimeMs
    }

    private fun updateTimeLabels(startMs: Long, endMs: Long) {
        startTimeView.text = LocaleUtil.formatTimerText(startMs, false)
        endTimeView.text = LocaleUtil.formatTimerText(endMs, false)
        // Center label shows the selected window length, e.g. how long the cropped clip will be.
        durationTimeView.text = LocaleUtil.formatTimerText((endMs - startMs).coerceAtLeast(0L), false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initPlayer() {
        val exoPlayer = VideoUtil.getExoPlayer(context)
        exoPlayer.playWhenReady = false
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    AudioManagerCompat.requestAudioFocus(audioManager, audioFocusRequest)
                    // Start the ~100ms playhead poll loop while the preview is actually playing.
                    startPlayheadUpdates()
                } else {
                    AudioManagerCompat.abandonAudioFocusRequest(audioManager, audioFocusRequest)
                    // Stop polling and hide the cursor when paused/stopped.
                    stopPlayheadUpdates()
                    audioTrimView.clearPlayhead()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                    stopPlayheadUpdates()
                    audioTrimView.clearPlayhead()
                }
            }
        })
        playerView.player = exoPlayer
        playerView.controllerShowTimeoutMs = 0
        playerView.controllerAutoShow = true
        player = exoPlayer
    }

    private fun preparePlayer() {
        val exoPlayer = player ?: return
        val item = audioItem ?: return
        val uri = item.uri ?: return

        val source = androidx.media3.common.MediaItem.fromUri(uri)

        val startUs = currentStartMs() * 1000L
        val end = currentEndMs()
        val endUs = if (durationMs > 0L && end >= durationMs) {
            TIME_END_OF_SOURCE
        } else {
            end * 1000L
        }

        if (exoPlayer.isLoading || exoPlayer.isPlaying) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }

        val clippingSource = ClippingMediaSource(
            mediaSourceFactory.createMediaSource(source),
            startUs,
            endUs,
        )
        exoPlayer.setMediaSource(clippingSource)
        exoPlayer.playWhenReady = false
        exoPlayer.prepare()
    }

    /**
     * F1Whisper: begin polling the preview position so the moving cursor tracks playback. Posts
     * itself every ~100ms (matching VideoEditView's playing-state cadence) until stopped.
     */
    private fun startPlayheadUpdates() {
        playheadHandler.removeCallbacks(playheadUpdateRunnable)
        playheadHandler.post(playheadUpdateRunnable)
    }

    /**
     * F1Whisper: stop the preview-position poll loop.
     */
    private fun stopPlayheadUpdates() {
        playheadHandler.removeCallbacks(playheadUpdateRunnable)
    }

    /**
     * F1Whisper: push the current absolute preview position to the trim view and re-schedule the
     * next poll while still playing. The ClippingMediaSource rebases playback to the clip start, so
     * the absolute position on the timeline is currentStartMs() + getCurrentPosition().
     */
    private fun updatePlayhead() {
        val exoPlayer = player ?: return
        if (!exoPlayer.isPlaying) {
            audioTrimView.clearPlayhead()
            return
        }
        val absolutePositionMs = currentStartMs() + exoPlayer.currentPosition
        audioTrimView.setPlayheadPosition(absolutePositionMs)
        playheadHandler.removeCallbacks(playheadUpdateRunnable)
        playheadHandler.postDelayed(playheadUpdateRunnable, PLAYHEAD_UPDATE_INTERVAL_MS)
    }

    fun releasePlayer() {
        stopPlayheadUpdates()
        audioTrimView.clearPlayhead()
        player?.let {
            it.stop()
            it.release()
        }
        player = null
    }

    override fun onDetachedFromWindow() {
        dragListener = null
        stopPlayheadUpdates()
        releasePlayer()
        if (playerView.player != null) {
            playerView.player = null
        }
        super.onDetachedFromWindow()
    }

    interface OnTimelineDragListener {
        fun onTimelineDragStart()

        fun onTimelineDragStop()
    }

    companion object {
        // Matches VideoEditView's playing-state progress cadence (100ms) for a smooth cursor.
        private const val PLAYHEAD_UPDATE_INTERVAL_MS = 100L
    }
}
