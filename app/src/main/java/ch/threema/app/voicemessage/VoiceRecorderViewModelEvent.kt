package ch.threema.app.voicemessage

import androidx.annotation.StringRes

sealed interface VoiceRecorderViewModelEvent {

    data object FailedToCreateAudioOutputFile : VoiceRecorderViewModelEvent

    data object FailedToOpenAudioRecorder : VoiceRecorderViewModelEvent

    data object RecorderReachedMaxDuration : VoiceRecorderViewModelEvent

    data object RecorderReachedMaxFileSize : VoiceRecorderViewModelEvent

    data object RecorderError : VoiceRecorderViewModelEvent

    data object FailedToPlayRecording : VoiceRecorderViewModelEvent

    data object FailedToDetermineDuration : VoiceRecorderViewModelEvent

    /**
     * F1Whisper: the user requested a trim window but the lossless crop could not be performed
     * (unsupported container or a crop failure). The send is ABORTED - nothing is sent. The activity
     * surfaces [messageRes] so the user can retry or remove the trim. This mirrors
     * [ch.threema.app.services.MessageServiceImpl]'s fail-safe for attached audio files; we must
     * NEVER silently send the untrimmed original after a trim request (data/privacy leak).
     */
    data class TrimFailedSendAborted(@StringRes val messageRes: Int) : VoiceRecorderViewModelEvent

    data class PlaybackFinished(val endProgress: Int) : VoiceRecorderViewModelEvent

    data object Sent : VoiceRecorderViewModelEvent

    data object ConfirmationRequiredToDiscard : VoiceRecorderViewModelEvent

    data object Discarded : VoiceRecorderViewModelEvent
}
