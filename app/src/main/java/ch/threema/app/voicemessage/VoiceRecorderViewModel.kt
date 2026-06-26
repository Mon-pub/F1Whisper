package ch.threema.app.voicemessage

import android.app.Application
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import ch.threema.app.R
import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.services.FileService
import ch.threema.app.services.MessageService
import ch.threema.app.ui.MediaItem
import ch.threema.app.utils.MediaPlayerStateWrapper
import ch.threema.app.utils.MimeUtil
import ch.threema.app.voicemessage.VoiceRecorderActivity.Companion.VOICE_MESSAGE_FILE_EXTENSION
import ch.threema.app.voicemessage.VoiceRecorderActivity.Companion.defaultSamplingRate
import ch.threema.base.utils.getThreemaLogger
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val logger = getThreemaLogger("VoiceRecorderViewModel")

class VoiceRecorderViewModel(
    application: Application,
    private val fileService: FileService,
    private val messageService: MessageService,
    private val preferenceService: PreferenceService,
    private val messageReceiver: MessageReceiver<*>,
) : AndroidViewModel(application) {

    private val _events: MutableSharedFlow<VoiceRecorderViewModelEvent> = MutableSharedFlow()
    val events: SharedFlow<VoiceRecorderViewModelEvent> = _events

    private val _state: MutableStateFlow<VoiceRecorderScreenState> = MutableStateFlow(VoiceRecorderScreenState.initial())
    val state: StateFlow<VoiceRecorderScreenState> = _state

    private var audioOutputUri: Uri? = null

    // F1Whisper: user-chosen trim window (start/end, in ms). Defaults to no trim (the full clip).
    // [trimEndMs] == TRIM_END_UNSET means "until the end of the recording".
    private var trimStartMs: Long = 0L
    private var trimEndMs: Long = TRIM_END_UNSET

    private var mediaRecorder: MediaRecorder? = null
    var mediaPlayer: MediaPlayerStateWrapper? = null
        private set

    private var recordingTimerJob: Job? = null

    private val onStopRecordingListener = object : AudioRecorder.OnStopListener {
        override fun onRecordingReachedMaxDuration() {
            viewModelScope.launch {
                _events.emit(VoiceRecorderViewModelEvent.RecorderReachedMaxDuration)
            }
        }

        override fun onRecordingReachedMaxFileSize() {
            viewModelScope.launch {
                _events.emit(VoiceRecorderViewModelEvent.RecorderReachedMaxFileSize)
            }
        }

        override fun onRecordingError() {
            viewModelScope.launch {
                _events.emit(VoiceRecorderViewModelEvent.RecorderError)
            }
        }
    }

    fun startRecording() {
        val currentMediaRecordState = _state.value.mediaState
        if (currentMediaRecordState !is MediaState.Record || currentMediaRecordState.isRecording) {
            return
        }
        logger.info("Start recording")
        viewModelScope.launch {
            val outputUri: Uri = try {
                createAudioOutputFile()
            } catch (e: IOException) {
                logger.error("Failed to create temp audio file", e)
                _events.emit(VoiceRecorderViewModelEvent.FailedToCreateAudioOutputFile)
                return@launch
            }
            logger.info("Created recording output file {}", outputUri)
            val audioRecorder = AudioRecorder(application).apply {
                setOnStopListener(onStopRecordingListener)
            }
            try {
                mediaRecorder = audioRecorder.prepare(outputUri, defaultSamplingRate)!!
                    .also { mediaRecorder ->
                        mediaRecorder.start()
                        logger.info("Started recording with {}", mediaRecorder)
                    }
            } catch (e: Exception) {
                _events.emit(VoiceRecorderViewModelEvent.FailedToOpenAudioRecorder)
                logger.error("AudioRecorder exception occurred", e)
                runCatching {
                    mediaRecorder?.reset()
                    mediaRecorder?.release()
                    mediaRecorder = null
                }
                return@launch
            }
            audioOutputUri = outputUri
            _state.value = _state.value.copy(
                mediaState = MediaState.Record(
                    isRecording = true,
                    duration = Duration.ZERO,
                ),
            )
            startOrResumeRecordingTimer()
        }
    }

    private fun startOrResumeRecordingTimer() {
        val currentMediaRecordState = _state.value.mediaState
        if (currentMediaRecordState !is MediaState.Record || !currentMediaRecordState.isRecording || recordingTimerJob?.isActive == true) {
            return
        }
        recordingTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1.seconds)
                val currentState = _state.value
                if (currentState.mediaState is MediaState.Record && currentState.mediaState.isRecording) {
                    _state.compareAndSet(
                        expect = currentState,
                        update = currentState.copy(
                            mediaState = currentState.mediaState.copy(
                                duration = currentState.mediaState.duration + 1.seconds,
                            ),
                        ),
                    )
                } else {
                    break
                }
            }
        }
    }

    fun pauseRecording() {
        val currentMediaRecordState = _state.value.mediaState
        if (currentMediaRecordState !is MediaState.Record || !currentMediaRecordState.isRecording) {
            return
        }
        logger.info("Pause recording")
        try {
            mediaRecorder?.pause()
        } catch (e: Exception) {
            logger.error("Exception while pausing recording", e)
        }
        _state.value = _state.value.copy(
            mediaState = currentMediaRecordState.copy(
                isRecording = false,
            ),
        )
    }

    fun resumeRecording() {
        val currentMediaRecordState = _state.value.mediaState
        if (currentMediaRecordState !is MediaState.Record || currentMediaRecordState.isRecording) {
            return
        }
        logger.info("Resume recording")
        try {
            mediaRecorder?.resume()
        } catch (e: Exception) {
            logger.error("Exception while resuming recording", e)
        }
        _state.value = _state.value.copy(
            mediaState = currentMediaRecordState.copy(
                isRecording = true,
            ),
        )
        startOrResumeRecordingTimer()
    }

    fun stopRecording() {
        val currentMediaRecordState = _state.value.mediaState
        if (currentMediaRecordState !is MediaState.Record) {
            return
        }
        logger.info("Stop recording")
        try {
            mediaRecorder?.let { recorder ->
                recorder.stop()
                logger.info("Stopped recording with {}", recorder)
            }
        } catch (e: Exception) {
            logger.error("Exception while stopping recording", e)
        }
        releaseMediaRecorder()
        _state.value = _state.value.copy(
            mediaState = MediaState.FinishedRecording(
                uri = audioOutputUri!!,
            ),
        )
    }

    fun startPlayback() {
        val currentMediaFinishedRecordingState = _state.value.mediaState
        if (currentMediaFinishedRecordingState !is MediaState.FinishedRecording) {
            return
        }
        val mediaPlayer = MediaPlayerStateWrapper().apply {
            if (_state.value.scoAudioState == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                setAudioStreamType(AudioManager.STREAM_VOICE_CALL)
            } else {
                setAudioStreamType(AudioManager.STREAM_MUSIC)
            }
        }
        logger.info("Initializing media player")
        try {
            mediaPlayer.apply {
                setDataSource(application, currentMediaFinishedRecordingState.uri)
                setOnPreparedListener { player: MediaPlayer ->
                    mediaPlayer.start()
                    logger.info("Started media player {}", player)
                    _state.value = _state.value.copy(
                        mediaState = MediaState.Playback(
                            uri = currentMediaFinishedRecordingState.uri,
                            isPlaying = true,
                            duration = player.duration.coerceAtLeast(0).milliseconds,
                        ),
                    )
                }
                setOnCompletionListener { player: MediaPlayer ->
                    _state.value = _state.value.let { state ->
                        if (state.mediaState is MediaState.Playback) {
                            state.copy(
                                mediaState = state.mediaState.copy(
                                    isPlaying = false,
                                ),
                            )
                        } else {
                            state
                        }
                    }
                    viewModelScope.launch {
                        _events.emit(VoiceRecorderViewModelEvent.PlaybackFinished(player.duration))
                    }
                }
                prepare()
            }
            this@VoiceRecorderViewModel.mediaPlayer = mediaPlayer
        } catch (e: Exception) {
            viewModelScope.launch {
                _events.emit(VoiceRecorderViewModelEvent.FailedToPlayRecording)
            }
            logger.error("Failed to play recording", e)
            releaseMediaPlayer()
        }
    }

    fun pausePlayback() {
        val currentMediaPlaybackState = _state.value.mediaState
        val mediaPlayer = this.mediaPlayer
        if (currentMediaPlaybackState !is MediaState.Playback || !currentMediaPlaybackState.isPlaying || mediaPlayer == null) {
            return
        }
        logger.info("Pause media player {}", mediaPlayer)
        try {
            mediaPlayer.pause()
        } catch (e: Exception) {
            logger.error("Exception while pausing media player {}", mediaPlayer, e)
        }
        _state.value = _state.value.copy(
            mediaState = currentMediaPlaybackState.copy(isPlaying = false),
        )
    }

    fun seekPlaybackTo(duration: Duration) {
        val currentMediaPlaybackState = _state.value.mediaState
        val mediaPlayer = this.mediaPlayer
        if (currentMediaPlaybackState !is MediaState.Playback || mediaPlayer == null) {
            return
        }
        logger.info("Seek media player {} to {}", mediaPlayer, duration)
        if (duration > currentMediaPlaybackState.duration) {
            logger.warn("Cant seek media player to a frame exceeding its total duration")
            return
        }
        mediaPlayer.seekTo(
            /* msec = */
            duration.inWholeMilliseconds.toInt(),
        )
    }

    fun resumePlayback() {
        val currentMediaPlaybackState = _state.value.mediaState
        val mediaPlayer = this.mediaPlayer
        if (currentMediaPlaybackState !is MediaState.Playback || currentMediaPlaybackState.isPlaying || mediaPlayer == null) {
            return
        }
        logger.info("Resume media player {}", mediaPlayer)
        try {
            mediaPlayer.start()
        } catch (e: Exception) {
            logger.error("Exception while resuming media player {}", mediaPlayer, e)
        }
        _state.value = _state.value.copy(
            mediaState = currentMediaPlaybackState.copy(isPlaying = true),
        )
    }

    fun onPause() {
        when (_state.value.mediaState) {
            is MediaState.Record -> pauseRecording()
            is MediaState.Playback -> pausePlayback()
            else -> {}
        }
    }

    /**
     * Toggle the "listen once" flag. When enabled, the resulting voice message can be played a
     * single time by the recipient before being deleted (client-side, best-effort enforcement).
     */
    fun toggleListenOnce() {
        val newValue = !_state.value.listenOnce
        logger.info("Toggle listen once to {}", newValue)
        _state.value = _state.value.copy(listenOnce = newValue)
    }

    /**
     * F1Whisper: set the user-chosen trim window (in ms from the start of the recording). The clip
     * is losslessly cropped to this window on send. Pass [startMs] == 0 and [endMs] == [TRIM_END_UNSET]
     * (or the full duration) to keep the whole clip.
     */
    fun setTrimWindow(startMs: Long, endMs: Long) {
        trimStartMs = startMs.coerceAtLeast(0L)
        trimEndMs = endMs
    }

    fun send() {
        val currentMediaState = _state.value.mediaState
        if (currentMediaState is MediaState.Record) {
            stopRecording()
        } else if (currentMediaState is MediaState.Playback) {
            releaseMediaPlayer()
        }

        val uri = audioOutputUri ?: run {
            logger.warn("Audio output uri is missing")
            viewModelScope.launch {
                _events.emit(VoiceRecorderViewModelEvent.FailedToDetermineDuration)
            }
            return
        }

        // Snapshot the trim window + listen-once flag before going off-thread.
        val startMs = trimStartMs
        val endMs = trimEndMs
        val listenOnce = _state.value.listenOnce

        // Determining the duration (MediaPlayer) and the lossless crop (MediaExtractor/MediaMuxer)
        // are I/O-bound, so run them off the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            val audioFileDuration = getDurationFromFile(uri)
            if (audioFileDuration == Duration.ZERO) {
                _events.emit(VoiceRecorderViewModelEvent.FailedToDetermineDuration)
                return@launch
            }

            // F1Whisper: crop the recording to the chosen window before sending, if the user trimmed
            // it. The crop is a lossless per-container copy (AAC remux / MP3 frame-cut / WAV PCM-cut /
            // Opus page-copy) via [AudioTrimmer] - no re-encode.
            //
            // CRITICAL FAIL-SAFE (data/privacy): the user explicitly asked to trim, so if the crop
            // cannot be performed we ABORT the send entirely - we send NOTHING - and surface a clear
            // error so the user can retry or remove the trim. We must NEVER silently send the
            // untrimmed original after a trim request: that would transmit more audio than the user
            // intended to share. This mirrors MessageServiceImpl.trimAudio() for attached audio files.
            //
            // The voice send path reads the raw file and reports [MediaItem.durationMs] directly (it
            // has no audio-trim machinery), so we point the MediaItem at the cropped file and report
            // the trimmed duration here.
            val fullDurationMs = audioFileDuration.inWholeMilliseconds
            val resolvedEndMs = if (endMs == TRIM_END_UNSET) fullDurationMs else endMs
            val isTrimmed = startMs > 0L || resolvedEndMs < fullDurationMs
            var sendUri = uri
            var sendDurationMs = fullDurationMs
            if (isTrimmed) {
                // Pick the precise abort message before attempting the crop: an unsupported container
                // gets "format not supported", anything else gets the generic "couldn't trim". This
                // matches the attached-audio fail-safe so the UX is identical across both flows.
                val method = AudioTrimmer.getTrimMethod(application, uri)
                if (method == AudioTrimmer.TrimMethod.UNSUPPORTED) {
                    logger.warn("Voice message container is not losslessly trimmable; aborting send (fail-safe)")
                    abortSendAfterTrimFailure(uri, R.string.audio_trim_not_supported)
                    return@launch
                }
                val croppedUri = cropRecording(uri, startMs, resolvedEndMs)
                if (croppedUri != null) {
                    sendUri = croppedUri
                    sendDurationMs = (resolvedEndMs - startMs).coerceAtLeast(DateUtils.SECOND_IN_MILLIS)
                } else {
                    // The user requested a trim that could not be performed. ABORT - send NOTHING.
                    // Never fall back to the untrimmed original.
                    logger.warn("Voice message trim failed; aborting send (fail-safe) - the untrimmed clip is NOT sent")
                    abortSendAfterTrimFailure(uri, R.string.audio_trim_failed)
                    return@launch
                }
            }

            val mediaItem = MediaItem(sendUri, MimeUtil.MIME_TYPE_AUDIO_AAC, null).apply {
                durationMs = sendDurationMs.coerceAtLeast(
                    minimumValue = DateUtils.SECOND_IN_MILLIS,
                )
                isListenOnce = listenOnce
            }
            messageService.sendMediaAsync(
                /* mediaItems = */
                listOf(mediaItem),
                /* messageReceivers = */
                listOf(messageReceiver),
            )
            _events.emit(VoiceRecorderViewModelEvent.Sent)
        }
    }

    /**
     * F1Whisper: losslessly crop [sourceUri] to the [startMs, endMs] window into a new temp file.
     *
     * @return the cropped file's [Uri], or null if cropping failed. On null the caller MUST abort the
     * send (fail-safe); it must NEVER send the untrimmed original after a trim request.
     */
    private fun cropRecording(sourceUri: Uri, startMs: Long, endMs: Long): Uri? {
        return try {
            val croppedFile = File.createTempFile(
                /* prefix = */
                "voice-trimmed-",
                /* suffix = */
                VOICE_MESSAGE_FILE_EXTENSION,
                /* directory = */
                fileService.tempPath,
            )
            val trimmer = AudioTrimmer(application, sourceUri, startMs, endMs)
            if (trimmer.trim(croppedFile)) {
                croppedFile.toUri()
            } else {
                if (croppedFile.exists() && !croppedFile.delete()) {
                    logger.warn("Failed to delete unused trim temp file")
                }
                null
            }
        } catch (e: IOException) {
            logger.error("Failed to create temp file for trimmed voice message", e)
            null
        }
    }

    /**
     * F1Whisper: fail-safe abort after a requested trim could not be performed. Surfaces [messageRes]
     * to the user and restores the screen to [MediaState.FinishedRecording] so the recording (still
     * intact, with the chosen trim handles) can be replayed, re-trimmed, re-sent, or discarded. We
     * NEVER send the untrimmed original after a trim request - that would leak more audio than the
     * user intended to share. Mirrors MessageServiceImpl.showAudioTrimFailedAndAbort().
     */
    private suspend fun abortSendAfterTrimFailure(recordingUri: Uri, @StringRes messageRes: Int) {
        _state.value = _state.value.copy(
            mediaState = MediaState.FinishedRecording(uri = recordingUri),
        )
        _events.emit(VoiceRecorderViewModelEvent.TrimFailedSendAborted(messageRes))
    }

    fun discard(force: Boolean = false) {
        viewModelScope.launch {
            when (val currentMediaState = _state.value.mediaState) {
                is MediaState.Record -> {
                    stopRecording()
                    if (currentMediaState.duration >= discardConfirmationThresholdDuration && !force) {
                        _events.emit(VoiceRecorderViewModelEvent.ConfirmationRequiredToDiscard)
                    } else {
                        _events.emit(VoiceRecorderViewModelEvent.Discarded)
                    }
                }
                is MediaState.FinishedRecording -> {
                    val duration = getDurationFromFile(currentMediaState.uri)
                    if (duration >= discardConfirmationThresholdDuration && !force) {
                        _events.emit(VoiceRecorderViewModelEvent.ConfirmationRequiredToDiscard)
                    } else {
                        _events.emit(VoiceRecorderViewModelEvent.Discarded)
                    }
                }
                is MediaState.Playback -> {
                    releaseMediaPlayer()
                    _events.emit(VoiceRecorderViewModelEvent.Discarded)
                }
            }
        }
    }

    fun onLostAudioFocus() {
        when (val currentMediaState = _state.value.mediaState) {
            is MediaState.Record -> {
                stopRecording()
            }
            is MediaState.FinishedRecording -> {
                // Do nothing
            }
            is MediaState.Playback -> {
                releaseMediaPlayer()
                _state.value = _state.value.copy(
                    mediaState = currentMediaState.copy(isPlaying = false),
                )
            }
        }
    }

    private fun getDurationFromFile(uri: Uri): Duration {
        logger.info("Attempting to retrieve duration from file {}", uri)
        val durationCheckMediaPlayer: MediaPlayer = MediaPlayer.create(application, uri)
            ?: run {
                logger.info("Unable to create a media player for checking size. File already deleted by OS?")
                return Duration.ZERO
            }
        val durationMs = durationCheckMediaPlayer.duration
        durationCheckMediaPlayer.release()
        logger.info("Duration in ms {}", durationMs)
        return if (durationMs > 0) {
            durationMs.milliseconds
        } else {
            Duration.ZERO
        }
    }

    @Throws(IOException::class)
    private fun createAudioOutputFile(): Uri =
        File.createTempFile(
            /* prefix = */
            "voice-",
            /* suffix = */
            VOICE_MESSAGE_FILE_EXTENSION,
            /* directory = */
            fileService.tempPath,
        ).toUri()

    /**
     *  Safe to call for an already released and cleared local media recorder
     */
    private fun releaseMediaRecorder() {
        mediaRecorder?.let { recorder ->
            logger.info("Releasing media recorder {}", recorder)
            runCatching {
                recorder.reset()
                recorder.release()
                mediaRecorder = null
                logger.info("Released media recorder {}", recorder)
            }
        }
    }

    /**
     *  Safe to call for an already released and cleared local media player
     */
    private fun releaseMediaPlayer() {
        mediaPlayer?.let { player ->
            runCatching {
                logger.info("Releasing media player {}", player)
                player.reset()
                player.release()
                mediaPlayer = null
                logger.info("Released media player {}", player)
            }
        }
    }

    fun onScoStateChanged(scoAudioState: Int) {
        val voiceRecorderBluetoothDisableSetting: Boolean? = when (scoAudioState) {
            AudioManager.SCO_AUDIO_STATE_CONNECTED -> false
            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> true
            AudioManager.SCO_AUDIO_STATE_ERROR -> false
            else -> null
        }
        voiceRecorderBluetoothDisableSetting?.let { setting ->
            preferenceService.setVoiceRecorderBluetoothDisabled(setting)
        }
        _state.value = _state.value.copy(
            scoAudioState = scoAudioState,
        )
        logger.info(
            "SCO audio state: {}",
            when (scoAudioState) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> "connected"
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> "disconnected"
                AudioManager.SCO_AUDIO_STATE_CONNECTING -> "connecting"
                AudioManager.SCO_AUDIO_STATE_ERROR -> "error"
                else -> ""
            },
        )
    }

    override fun onCleared() {
        releaseMediaRecorder()
        releaseMediaPlayer()
    }

    companion object {
        private val discardConfirmationThresholdDuration = 10.seconds

        // F1Whisper: sentinel for "no explicit trim end set" -> crop until the end of the recording.
        const val TRIM_END_UNSET = -1L
    }
}
