package ch.threema.app.voicemessage

import android.media.AudioManager
import java.io.File
import kotlin.time.Duration

data class VoiceRecorderScreenState(
    val mediaState: MediaState,
    val scoAudioState: Int,
    /**
     * F1Whisper: when true, the voice message is sent as "listen once" (the recipient may play it a
     * single time before it is deleted and replay is blocked). Toggled by the user via the recorder UI.
     */
    val listenOnce: Boolean,
) {
    companion object {
        fun initial() = VoiceRecorderScreenState(
            mediaState = MediaState.Record(
                file = null,
                isRecording = false,
                duration = Duration.ZERO,
            ),
            scoAudioState = AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
            listenOnce = false,
        )
    }
}

sealed interface MediaState {

    val file: File?

    /**
     *  @param duration The current duration of the recorder (only accurate to one full second)
     */
    data class Record(
        override val file: File?,
        val isRecording: Boolean,
        val duration: Duration,
    ) : MediaState

    data class FinishedRecording(
        override val file: File,
    ) : MediaState

    data class Playback(
        override val file: File,
        val isPlaying: Boolean,
        val duration: Duration,
    ) : MediaState
}
