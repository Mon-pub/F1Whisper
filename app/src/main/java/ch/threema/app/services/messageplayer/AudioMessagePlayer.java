package ch.threema.app.services.messageplayer;

import static android.text.format.DateUtils.SECOND_IN_MILLIS;
import static androidx.media3.common.C.TIME_UNSET;
import static ch.threema.app.ThreemaApplication.getAppContext;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;

import com.google.common.util.concurrent.ListenableFuture;

import org.slf4j.Logger;

import java.io.File;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import ch.threema.app.R;
import ch.threema.app.managers.ListenerManager;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.services.ConversationCategoryService;
import ch.threema.app.services.FileService;
import ch.threema.app.services.MessageService;
import ch.threema.app.services.NotificationPreferenceService;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.utils.BitmapUtil;
import ch.threema.app.utils.LocaleUtil;
import ch.threema.app.utils.RuntimeUtil;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import ch.threema.logging.ThreemaLogger;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.data.media.AudioDataModel;
import ch.threema.storage.models.data.media.FileDataModel;
import ch.threema.storage.models.data.media.MediaMessageDataInterface;

public class AudioMessagePlayer extends MessagePlayer {
    private final Logger logger = getThreemaLogger("AudioMessagePlayer");

    private static final int SEEKBAR_UPDATE_FREQUENCY = 50;
    private File decryptedFile = null;
    private Uri decryptedFileUri = null;
    private int duration = 0; // duration in milliseconds
    private int position = 0; // position in milliseconds
    // F1Whisper: set true once this player has actually started audible playback (onIsPlayingChanged
    // true). A listen-once message must NEVER be burned by a STATE_ENDED that arrives WITHOUT real
    // playback (broken/zero-length media, a spurious end event, or a prepare-only open) - otherwise
    // it "expires before being listened". Reset on every open().
    private boolean hasPlayed = false;
    private Thread mediaPositionListener;
    private final PreferenceService preferenceService;
    @NonNull
    private final NotificationPreferenceService notificationPreferenceService;
    private final FileService fileService;
    @NonNull
    private final MessageService messageService;
    private final ConversationCategoryService conversationCategoryService;
    // F1Whisper: not final — re-bound to a fresh fragment controller on chat re-entry
    // (rebindMediaControllerIfChanged) and nulled while the player is kept alive detached in the
    // background (detachController), so getMediaController() must tolerate a null future.
    @Nullable
    private ListenableFuture<MediaController> mediaControllerFuture;

    protected AudioMessagePlayer(
        @NonNull Context context,
        @NonNull MessageService messageService,
        @NonNull FileService fileService,
        @NonNull PreferenceService preferenceService,
        @NonNull NotificationPreferenceService notificationPreferenceService,
        @NonNull ConversationCategoryService conversationCategoryService,
        @NonNull MessageReceiver<?> messageReceiver,
        @NonNull ListenableFuture<MediaController> mediaControllerFuture,
        @NonNull AbstractMessageModel messageModel
    ) {
        super(context, messageService, fileService, messageReceiver, messageModel);

        this.preferenceService = preferenceService;
        this.notificationPreferenceService = notificationPreferenceService;
        this.fileService = fileService;
        this.messageService = messageService;
        this.conversationCategoryService = conversationCategoryService;
        this.mediaControllerFuture = mediaControllerFuture;

        logger.info("New AudioMediaPlayer instance: {}", messageModel.getId());

        if (logger instanceof ThreemaLogger) {
            ((ThreemaLogger) logger).setPrefix(String.valueOf(messageModel.getId()));
        }
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlayerError(@Nullable PlaybackException error) {
            logger.error("Error while playing audio", error);
        }

        @Override
        public void onEvents(@Nullable Player player, @Nullable Player.Events events) {
            if (events == null) {
                return;
            }

            StringBuilder eventsString = new StringBuilder();
            for (int i = 0; i < events.size(); i++) {
                eventsString.append(events.get(i));
                eventsString.append(", ");
            }
            logger.info("Events: {}", eventsString);
        }

        @Override
        public void onIsLoadingChanged(boolean isLoading) {
            logger.info(isLoading ? "is now loading" : "is not loading");
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            MediaController mediaController = getMediaController();
            if (mediaController != null) {
                if (isPlaying) {
                    logger.info("onPlay");
                    // F1Whisper: record that audible playback actually began, so a listen-once is only
                    // burned after it was genuinely played (see hasPlayed / enforceListenOnceIfNeeded).
                    hasPlayed = true;
                    makeResume(SOURCE_UI_TOGGLE);
                } else if (mediaController.getPlaybackState() != Player.STATE_ENDED && playerMediaMatchesControllerMedia()) {
                    logger.info("onPause");
                    makePause(SOURCE_UI_TOGGLE);
                }
            }
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_ENDED) {
                logger.info("onStopped");
                // F1Whisper: a "listen once" voice message is deleted once playback completes, so it
                // can never be replayed (best-effort, client-side enforcement). Gate on hasPlayed +
                // matching media so a STATE_ENDED that arrives without real, audible playback of THIS
                // message never burns it ("expires before being listened").
                if (hasPlayed && playerMediaMatchesControllerMedia()) {
                    enforceListenOnceIfNeeded();
                } else {
                    // F1Whisper (fourth fork review, F4-10): playback ended without ever becoming audible, so this
                    // session is over and did not burn. Stop claiming to be the live owner, which lets the bubble
                    // finish the interrupted burn - the accepted failed-playback tradeoff.
                    releaseListenOnceOwnership();
                }
                AudioMessagePlayer.super.stop();
                ListenerManager.messagePlayerListener.handle(listener -> listener.onAudioPlayEnded(getMessageModel(), mediaControllerFuture));
            } else if (playbackState == Player.STATE_READY) {
                logger.info("onReady");
                markAsConsumed();
                prepared();
            }
        }

        @Override
        public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                logger.info("onSeekEnded {} {} {}", reason, oldPosition.positionMs, newPosition.positionMs);

                // seek ended
                if (oldPosition != newPosition) {
                    position = (int) newPosition.positionMs;
                    onSeekCompleted();
                }
            }
        }
    };

    @Override
    public MediaMessageDataInterface getData() {
        if (getMessageModel().getType() == MessageType.VOICEMESSAGE) {
            return this.getMessageModel().getAudioData();
        } else {
            return this.getMessageModel().getFileData();
        }
    }

    @Override
    protected AbstractMessageModel setData(MediaMessageDataInterface data) {
        AbstractMessageModel messageModel = this.getMessageModel();
        if (messageModel.getType() == MessageType.VOICEMESSAGE) {
            messageModel.setAudioData((AudioDataModel) data);
        } else {
            messageModel.setFileData((FileDataModel) data);
        }
        return messageModel;
    }

    @Override
    protected void open(File decryptedFile) {
        // F1-PATCH: this is the boundary where a message's decrypted audio is released to the media
        // player, so it is where the listen-once restriction has to be enforced - not at the button,
        // which is only one of several ways to get here (auto-play, download-complete, rebind).
        final AbstractMessageModel messageModel = getMessageModel();
        if (messageModel != null) {
            final ListenOnceGate gate = ListenOnceEnforcer.gateOf(messageModel);
            if (ListenOnceDecision.isPlaybackRefused(gate)) {
                logger.info("Refusing to open listen-once message {} ({})", messageModel.getId(), gate);
                if (gate == ListenOnceGate.BLOCKED_BURN_PENDING
                    && !ListenOnceOwnership.isActive(messageModel.getId())) {
                    // A claim with no burn AND no live owner: playback began in an earlier process
                    // and never finished. Finish it now so the media stops occupying disk and the
                    // bubble settles. F1Whisper (fourth fork review, F4-10): with a live owner this
                    // is the session's OWN claim, and burning here would delete the audio it is
                    // about to play.
                    ListenOnceEnforcer.burn(messageModel, messageService, fileService, false);
                }
                return;
            }
            if (ListenOnceDecision.needsClaimBeforeRelease(gate)) {
                // F1Whisper (fourth fork review, F4-10): become the message's active owner BEFORE the
                // claim is written, so no callback can observe the claim without also being able to
                // observe that it is live. A second session is refused rather than queued: one
                // message, one playback, and a second caller must not be able to burn this one's
                // audio out from under it.
                if (!ListenOnceOwnership.acquire(messageModel.getId(), listenOnceSessionToken)) {
                    logger.info("Refusing to open listen-once message {}: another session is playing it", messageModel.getId());
                    return;
                }
                // Claim first, release second. The hop through the worker is what makes the claim
                // durable before the player can read a byte; open() runs on the UI thread (media3
                // requires main-thread controller calls) so the claim cannot be written inline.
                ListenOnceEnforcer.claim(messageModel, messageService, () -> openInternal(decryptedFile));
                return;
            }
        }
        openInternal(decryptedFile);
    }

    /**
     * F1Whisper (fourth fork review, F4-10): this player's identity as the active owner of a listen-once message. A plain
     * object rather than the player itself, so ownership cannot be confused with any other use of the instance, and only
     * this player can release what it took.
     */
    private final Object listenOnceSessionToken = new Object();

    /**
     * Give up active ownership of the listen-once message, if this player held it. Called wherever the playback session
     * ends, so a message is not left looking permanently live in a process that has stopped playing it.
     */
    private void releaseListenOnceOwnership() {
        final AbstractMessageModel messageModel = getMessageModel();
        if (messageModel != null) {
            ListenOnceOwnership.release(messageModel.getId(), listenOnceSessionToken);
        }
    }

    private void openInternal(File decryptedFile) {
        this.decryptedFile = decryptedFile;
        this.decryptedFileUri = fileService.getShareFileUri(decryptedFile, null);
        this.position = 0;
        this.duration = 0;
        this.hasPlayed = false; // F1Whisper: new playback session; don't burn until audio actually plays

        logger.info("Open voice message file {}", decryptedFileUri);

        MediaController mediaController = getMediaController();
        if (mediaController != null) {
            String displayName;
            Bitmap artworkBitmap = null;
            if (!this.notificationPreferenceService.isShowMessagePreview() || this.conversationCategoryService.isPrivateChat(currentMessageReceiver.getUniqueIdString())) {
                displayName = getContext().getString(R.string.notification_channel_voice_message_player);
            } else {
                displayName = currentMessageReceiver.getDisplayName(preferenceService.getContactNameFormat());
                artworkBitmap = currentMessageReceiver.getAvatar();
            }

            MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder()
                .setTitle(displayName)
                .setArtist(getContext().getString(R.string.voice_message_from,
                    LocaleUtil.formatTimeStampStringAbsolute(getAppContext(), getMessageModel().getCreatedAt().getTime())));

            if (artworkBitmap != null) {
                metadataBuilder.setArtworkData(BitmapUtil.bitmapToByteArray(artworkBitmap, Bitmap.CompressFormat.JPEG, 80), MediaMetadata.PICTURE_TYPE_FRONT_COVER);
            }

            // F1Whisper: carry the message identity in the media metadata so the app-scoped
            // VoiceMessagePlaybackHolder (which has no access to the chat adapter) can drive the
            // background "now playing" banner: identify the chat, resolve the message for tap-to-jump,
            // and exclude listen-once voices from background continuation.
            final boolean isListenOnce = isListenOnceVoice();
            final Bundle extras = new Bundle();
            extras.putInt(VoiceMessagePlaybackHolder.EXTRA_MESSAGE_ID, getMessageModel().getId());
            extras.putString(VoiceMessagePlaybackHolder.EXTRA_MESSAGE_TYPE, getMessageModel().getClass().toString());
            extras.putString(VoiceMessagePlaybackHolder.EXTRA_CHAT_UNIQUE_ID, currentMessageReceiver.getUniqueIdString());
            extras.putBoolean(VoiceMessagePlaybackHolder.EXTRA_LISTEN_ONCE, isListenOnce);
            metadataBuilder.setExtras(extras);

            final MediaItem mediaItem = new MediaItem.Builder()
                .setMediaMetadata(metadataBuilder.build())
                .setMediaId(decryptedFileUri.toString())
                .setUri(decryptedFileUri)
                .build();

            // cleanup old media player instance
            if (this.mediaPositionListener != null) {
                this.mediaPositionListener.interrupt();
            }
            mediaController.stop();
            mediaController.removeListener(playerListener);
            mediaController.clearMediaItems();

            // add new item and prepare
            mediaController.addMediaItem(mediaItem);
            mediaController.setPlayWhenReady(false);
            mediaController.addListener(playerListener);
            mediaController.prepare();

            // F1Whisper: keep an app-scoped controller connected while a normal voice message plays
            // so playback survives leaving the chat (media3 auto-stops the service only when the LAST
            // controller disconnects). Listen-once voices are intentionally NOT continued.
            if (!isListenOnce) {
                VoiceMessagePlaybackHolder.getInstance(getAppContext()).ensureConnected();
            }

            logger.info("MediaController prepared");
        } else {
            logger.info("Unable to get MediaController");
        }
    }

    /**
     * F1Whisper: true if this message is an (unburned) listen-once voice message. Listen-once voices
     * are excluded from background continuation and from the "now playing" banner.
     */
    private boolean isListenOnceVoice() {
        final AbstractMessageModel messageModel = getMessageModel();
        if (messageModel == null || messageModel.getType() != MessageType.FILE) {
            return false;
        }
        final FileDataModel fileDataModel = messageModel.getFileData();
        return fileDataModel != null && fileDataModel.isListenOnce();
    }

    /**
     * called after the media player was prepared
     */
    private void prepared() {
        logger.info("Media Player is prepared");

        MediaController mediaController = getMediaController();
        if (mediaController == null) {
            return;
        }

        if (!playerMediaMatchesControllerMedia()) {
            // another media player
            logger.info("Player media does not match controller media");
            return;
        }

        final long longDuration = mediaController.getDuration();
        duration = (int) longDuration;
        if (longDuration == TIME_UNSET) {
            MediaMessageDataInterface d = this.getData();
            if (d instanceof AudioDataModel) {
                duration = (int) (((AudioDataModel) d).getDuration() * SECOND_IN_MILLIS);
            } else if (d instanceof FileDataModel) {
                duration = (int) (((FileDataModel) d).getDurationSeconds() * SECOND_IN_MILLIS);
            }
        }
        logger.info("Duration = {}", duration);

        if (this.position > mediaController.getCurrentPosition()) {
            mediaController.seekTo(this.position);
        } else {
            onSeekCompleted();
        }
    }

    private void onSeekCompleted() {
        logger.info("Seek completed. Play from position {}", this.position);

        MediaController mediaController = getMediaController();
        if (mediaController != null) {

            float audioPlaybackSpeed = preferenceService.getAudioPlaybackSpeed();
            mediaController.setPlaybackSpeed(audioPlaybackSpeed);

            float newPlaybackSpeed = mediaController.getPlaybackParameters().speed;

            if (audioPlaybackSpeed != newPlaybackSpeed) {
                preferenceService.setAudioPlaybackSpeed(newPlaybackSpeed);
            }

            mediaController.play();

            initPositionListener();
        }
    }

    private void initPositionListener() {
        logger.debug("initPositionListener");

        if (this.mediaPositionListener != null) {
            this.mediaPositionListener.interrupt();
        }

        this.mediaPositionListener = new Thread(() -> {
            logger.debug("initPositionListener Thread started");

            boolean cont = true;
            while (cont) {
                try {
                    Thread.sleep(SEEKBAR_UPDATE_FREQUENCY);

                    RuntimeUtil.runOnUiThread(() -> {
                        MediaController mediaController = getMediaController();
                        if (mediaController != null && mediaController.isConnected() && mediaController.isPlaying()) {
                            int newPosition = (int) mediaController.getCurrentPosition();
                            if (newPosition > position) {
                                position = newPosition;
                                this.updatePlayState();
                            }
                        }
                    });

                    cont = !Thread.interrupted();
                } catch (Exception e) {
                    cont = false;
                }
            }
            logger.debug("initPositionListener Thread ended");
        });
        this.mediaPositionListener.start();
    }

    @Override
    public void pause(int source) {
        MediaController mediaController = getMediaController();
        if (mediaController != null && playerMediaMatchesControllerMedia()) {
            mediaController.pause();
        }
    }

    @Override
    protected void makePause(int source) {
        logger.info("makePause with source {}", source);
        this.state = State_PAUSE;
        synchronized (this.playbackListeners) {
            for (Map.Entry<String, PlaybackListener> l : this.playbackListeners.entrySet()) {
                l.getValue().onPause(
                    getMessageModel()
                );
            }
        }
    }

    @Override
    protected void play(final boolean autoPlay) {
        logger.info("Play button pressed");
        if (this.state == State_PAUSE) {
            MediaController mediaController = getMediaController();
            if (mediaController != null) {
                if (playerMediaMatchesControllerMedia()) {
                    mediaController.play();
                } else {
                    open(decryptedFile);
                }
            }
            return;
        }

        super.play(autoPlay);
    }

    @Override
    protected void makeResume(int source) {
        logger.info("makeResume with source {} state {} (should be != 5)", source, state);
        this.state = State_PLAYING;
        synchronized (this.playbackListeners) {
            for (Map.Entry<String, PlaybackListener> l : this.playbackListeners.entrySet()) {
                l.getValue().onPlay(getMessageModel(), false);
            }
        }
    }

    private void releasePlayer() {
        logger.info("Release Player");

        if (mediaPositionListener != null) {
            logger.debug("mediaPositionListener.interrupt()");
            mediaPositionListener.interrupt();
            mediaPositionListener = null;
        }

        MediaController mediaController = getMediaController();
        if (mediaController != null) {
            if (playerMediaMatchesControllerMedia()) {
                logger.info("MediaController stopped and cleared");
                mediaController.stop();
                mediaController.clearMediaItems();
                this.position = 0;
                this.duration = 0;
            } else {
                mediaController.removeListener(playerListener);
                synchronized (this.playbackListeners) {
                    for (Map.Entry<String, PlaybackListener> l : this.playbackListeners.entrySet()) {
                        l.getValue().onStop(getMessageModel());
                    }
                }
            }
        }
    }

    private boolean playerMediaMatchesControllerMedia() {
        if (decryptedFile != null && decryptedFileUri != null) {
            MediaController mediaController = getMediaController();
            if (mediaController != null && mediaController.getMediaItemCount() > 0) {
                return decryptedFileUri.toString().equals(mediaController.getMediaItemAt(0).mediaId);
            }
        }
        return false;
    }

    @Override
    public boolean stop() {
        if (!playerMediaMatchesControllerMedia()) {
            logger.debug("stop");
            super.stop();
            releasePlayer();
        }
        return true;
    }

    @Override
    public float togglePlaybackSpeed(float preferenceSpeed) {
        float currentSpeed = preferenceSpeed;
        MediaController mediaController = getMediaController();
        if (mediaController != null) {
            currentSpeed = mediaController.getPlaybackParameters().speed;
        }

        float newSpeed = 1f;

        if (currentSpeed == 1f) {
            newSpeed = 1.25f;
        } else if (currentSpeed == 1.25f) {
            newSpeed = 1.5f;
        } else if (currentSpeed == 1.5f) {
            newSpeed = 2f;
        } else if (currentSpeed == 2f) {
            newSpeed = 0.5f;
        }

        if (mediaController != null) {
            mediaController.setPlaybackSpeed(newSpeed);
        }
        preferenceService.setAudioPlaybackSpeed(newSpeed);
        return newSpeed;
    }

    @Override
    public void seekTo(int pos) {
        if (pos >= 0) {
            MediaController mediaController = getMediaController();
            if (mediaController != null && playerMediaMatchesControllerMedia()) {
                mediaController.seekTo(pos);
            }
        }
    }

    @Override
    public int getDuration() {
        return this.duration;
    }

    @Override
    public int getPosition() {
        if (this.getState() == State_PLAYING || this.getState() == State_PAUSE) {
            return this.position;
        }
        return 0;
    }

    /**
     * F1Whisper: if the just-finished message is an incoming "listen once" voice message, mark it
     * consumed and delete its stored (encrypted) media so it can never be played again.
     *
     * <p>This is the second half of the two-phase enforcement in {@link ListenOnceDecision}. The
     * first half - the durable claim that makes replay impossible - was already written in
     * {@link #open(File)}, before the player was given the audio, so reaching this method is no
     * longer what stops a replay; it only completes the cleanup.</p>
     *
     * <p>This enforcement is purely client-side and best-effort: a modified client, a rooted device
     * or a screen recorder can still capture the audio. It is NOT a cryptographic guarantee.</p>
     */
    private void enforceListenOnceIfNeeded() {
        final AbstractMessageModel messageModel = getMessageModel();
        if (messageModel == null) {
            return;
        }
        final ListenOnceGate gate = ListenOnceEnforcer.gateOf(messageModel);
        if (gate == ListenOnceGate.NOT_APPLICABLE || gate == ListenOnceGate.BLOCKED_CONSUMED) {
            releaseListenOnceOwnership();
            return;
        }
        // The user watched this one finish, so the bubble plays the burn burst.
        ListenOnceEnforcer.burn(messageModel, messageService, fileService, true);
        // F1Whisper (fourth fork review, F4-10): the session is over, so it stops being the live owner. The burn it just
        // handed off is what settles the message from here.
        releaseListenOnceOwnership();
    }

    @Nullable
    public MediaController getMediaController() {
        final ListenableFuture<MediaController> future = mediaControllerFuture;
        if (future != null && future.isDone()) {
            try {
                return future.get();
            } catch (ExecutionException e) {
                logger.error("Media Controller exception", e);
            } catch (InterruptedException e) {
                logger.error("Media Controller interrupted exception", e);
                Thread.currentThread().interrupt();
            } catch (CancellationException e) {
                logger.error("Media Controller cancelled", e);
            }
        }
        return null;
    }

    /**
     * F1Whisper: detach this player from its (about-to-be-released) controller so it can be kept in
     * the background without poking a dead controller or running a stale position poller. Playback
     * itself continues on the shared media3 session; only THIS player's observation is dropped.
     */
    public void detachController() {
        logger.info("Detaching controller for background playback");
        if (this.mediaPositionListener != null) {
            this.mediaPositionListener.interrupt();
            this.mediaPositionListener = null;
        }
        final MediaController mediaController = getMediaController();
        if (mediaController != null) {
            try {
                mediaController.removeListener(playerListener);
            } catch (Exception e) {
                logger.debug("Could not remove listener on detach", e);
            }
        }
        this.mediaControllerFuture = null;
    }

    /**
     * F1Whisper: on re-entering the chat this kept player belongs to, re-bind it to the fresh
     * fragment's live controller and reconcile the bubble state with the shared session (still
     * playing / paused / already moved on). No-op if the controller future is unchanged.
     */
    public void rebindMediaControllerIfChanged(@NonNull ListenableFuture<MediaController> newFuture) {
        if (newFuture == this.mediaControllerFuture) {
            return;
        }
        logger.info("Re-binding controller on chat re-entry");
        // Drop our listener from the previous controller if it is still around.
        final MediaController previous = getMediaController();
        if (previous != null) {
            try {
                previous.removeListener(playerListener);
            } catch (Exception e) {
                logger.debug("Could not remove listener on rebind", e);
            }
        }
        this.mediaControllerFuture = newFuture;
        newFuture.addListener(this::reconcileAfterRebind, ContextCompat.getMainExecutor(getContext()));
    }

    private void reconcileAfterRebind() {
        final MediaController mediaController = getMediaController();
        if (mediaController == null || !mediaController.isConnected()) {
            return;
        }
        final int playbackState = mediaController.getPlaybackState();
        final boolean isEndedOrIdle = playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE;
        final RebindAction action = VoiceBannerLogic.reconcileRebind(
            playerMediaMatchesControllerMedia(),
            mediaController.isPlaying(),
            isEndedOrIdle
        );
        if (action == RebindAction.STOP) {
            // The shared player has ended or moved to another message: make sure the bubble is not
            // stuck showing "playing". Also clear the detached mark — this player is being recycled
            // under the decorator right now, so it is re-owned by the chat; its normal in-chat
            // lifecycle releases + deletes on the next chat exit (pre-feature parity). Do NOT release
            // it here (it is mid-recycle).
            VoiceMessagePlaybackHolder.getInstance(getAppContext()).markReattached(getMessageModel().getId());
            super.stop();
            return;
        }

        mediaController.addListener(playerListener);
        final long controllerDuration = mediaController.getDuration();
        if (controllerDuration != TIME_UNSET) {
            this.duration = (int) controllerDuration;
        }
        this.position = (int) mediaController.getCurrentPosition();
        this.hasPlayed = true;

        VoiceMessagePlaybackHolder.getInstance(getAppContext()).markReattached(getMessageModel().getId());

        if (action == RebindAction.RESUME) {
            makeResume(SOURCE_LIFECYCLE);
            initPositionListener();
        } else {
            makePause(SOURCE_LIFECYCLE);
        }
    }
}

