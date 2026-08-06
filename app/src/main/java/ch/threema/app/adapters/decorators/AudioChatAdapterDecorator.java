package ch.threema.app.adapters.decorators;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.Toast;

import java.util.Collections;

import org.slf4j.Logger;

import java.io.File;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import ch.threema.app.R;
import ch.threema.app.managers.ListenerManager;
import ch.threema.app.services.messageplayer.ListenOnceBurnRegistry;
import ch.threema.app.services.messageplayer.ListenOnceDecision;
import ch.threema.app.services.messageplayer.ListenOnceEnforcer;
import ch.threema.app.services.messageplayer.ListenOnceGate;
import ch.threema.app.services.messageplayer.ListenOnceOwnership;
import ch.threema.app.services.messageplayer.MessagePlayer;
import ch.threema.app.ui.AudioProgressBarView;
import ch.threema.app.ui.ControllerView;
import ch.threema.app.ui.ListenOnceBurnDrawable;
import ch.threema.app.ui.listitemholder.ComposeMessageHolder;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.ElapsedTimeFormatter;
import ch.threema.app.utils.LinkifyUtil;
import ch.threema.app.utils.MessageUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.logging.ThreemaLogger;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.MessageState;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.data.media.AudioDataModel;
import ch.threema.storage.models.data.media.FileDataModel;

import static ch.threema.app.utils.MessageUtilKt.getUiContentColor;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

public class AudioChatAdapterDecorator extends ChatAdapterDecorator {
    private static final Logger logger = getThreemaLogger("AudioChatAdapterDecorator");

    // F1Whisper: centralised so the background-voice teardown can drop exactly these listeners.
    private static final String LISTENER_TAG = MessagePlayer.LISTENER_TAG_DECORATOR;

    public interface UserInteractionListener {
        void interact();
    }

    @NonNull
    private final MessagePlayerFactory messagePlayerFactory;

    @NonNull
    private final UserInteractionListener userInteractionListener;

    public AudioChatAdapterDecorator(
        AbstractMessageModel messageModel,
        @NonNull ChatAdapterDecoratorListener chatAdapterDecoratorListener,
        @NonNull LinkifyUtil.LinkifyListener linkifyListener,
        @NonNull MessagePlayerFactory messagePlayerFactory,
        @NonNull UserInteractionListener userInteractionListener,
        Helper helper
    ) {
        super(messageModel, chatAdapterDecoratorListener, linkifyListener, helper);
        this.messagePlayerFactory = messagePlayerFactory;
        this.userInteractionListener = userInteractionListener;
        if (logger instanceof ThreemaLogger) {
            ((ThreemaLogger) logger).setPrefix(String.valueOf(getMessageModel().getId()));
        }

        logger.info("New AudioChatAdapterDecorator instance for {}", messageModel.getId());
    }

    @Override
    protected void applyContentColor(
        final @NonNull ComposeMessageHolder viewHolder,
        final @NonNull ColorStateList contentColor
    ) {
        super.applyContentColor(viewHolder, contentColor);
        viewHolder.audioMessageIcon.setImageTintList(getUiContentColor(getMessageModel(), viewHolder.audioMessageIcon.getContext()));
    }

    /**
     * F1Whisper: freeze the audio row at full width (full waveform, no controls/icon) so the ember
     * burst has the whole bubble to consume before it collapses to the small note.
     */
    private void showBurningRow(@NonNull ComposeMessageHolder holder) {
        holder.controller.setHidden();
        holder.readOnButton.setVisibility(View.GONE);
        holder.audioMessageIcon.setVisibility(View.GONE);
        if (holder.seekBar != null) {
            holder.seekBar.setEnabled(false);
            holder.seekBar.setProgress(holder.seekBar.getMax());
        }
        if (holder.size != null) {
            holder.size.setVisibility(View.GONE);
        }
    }

    /**
     * F1Whisper: restore the audio row (its include root = the controller's parent, plus the seek
     * bar / time / icon) to the default visible state. Called on every bind so a recycled holder
     * never keeps a previous message's collapsed/hidden burned state (which renders blank).
     */
    private void restoreAudioRow(@NonNull ComposeMessageHolder holder) {
        if (holder.controller != null && holder.controller.getParent() instanceof View) {
            ((View) holder.controller.getParent()).setVisibility(View.VISIBLE);
        }
        if (holder.seekBar != null) {
            holder.seekBar.setVisibility(View.VISIBLE);
        }
        if (holder.size != null) {
            holder.size.setVisibility(View.VISIBLE);
        }
        holder.audioMessageIcon.setVisibility(View.VISIBLE);
        holder.audioMessageIcon.setAlpha(1f);
    }

    /**
     * F1Whisper: collapse the whole audio bubble to just the small "voice message expired" note by
     * hiding the entire audio row (the include's root view = the controller's parent) and shrinking
     * the bubble to wrap the note. The note text itself is supplied by {@code configureBodyText}.
     */
    private void collapseToExpiredNote(@NonNull ComposeMessageHolder holder) {
        if (holder.controller != null && holder.controller.getParent() instanceof View) {
            ((View) holder.controller.getParent()).setVisibility(View.GONE);
        }
        if (holder.contentView != null) {
            holder.contentView.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
            holder.contentView.requestLayout();
        }
    }

    /**
     * F1Whisper: play the one-shot ember-burst over the bubble card while it is still full size, then
     * collapse to the small note. The burst is a {@link ListenOnceBurnDrawable} on the card's
     * {@link android.view.ViewOverlay} driven by a {@link ValueAnimator} — drawn reliably without a
     * layout pass (a mid-bind {@code addView} child would never get laid out inside a recycled list
     * item, which is why earlier overlay-View attempts never animated). When the burst ends we fire a
     * normal {@code onModified} re-render so the CORRECT (possibly re-bound) holder collapses — never
     * a stale captured holder.
     */
    private void playBurnAnimation(@NonNull ComposeMessageHolder holder, final int burnId) {
        final ViewGroup card = holder.messageBlockView;
        final AbstractMessageModel model = getMessageModel();
        // Run inline: at the burned bind the card was just visible during playback, so it is already
        // laid out (getWidth()/getHeight() valid). We deliberately do NOT defer via post() — a posted
        // runnable that never runs (view detached right after bind) would strand the "burning" state
        // and the bubble would never collapse. If the card is missing or unmeasured, just collapse.
        final int w = card != null ? card.getWidth() : 0;
        final int h = card != null ? card.getHeight() : 0;
        if (card == null || w <= 0 || h <= 0) {
            ListenOnceBurnRegistry.clearBurning(burnId);
            collapseToExpiredNote(holder);
            return;
        }

        final ListenOnceBurnDrawable burn =
            new ListenOnceBurnDrawable(card.getResources().getDisplayMetrics().density);
        burn.setBounds(0, 0, w, h);
        card.getOverlay().add(burn);

        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ListenOnceBurnDrawable.DURATION_MS);
        animator.addUpdateListener(va -> burn.setProgress((float) va.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                card.getOverlay().remove(burn);
                ListenOnceBurnRegistry.clearBurning(burnId);
                // Collapse via a normal re-render (now no longer burning) so whichever holder
                // currently shows this message collapses correctly.
                ListenerManager.messageListeners.handle(listener -> listener.onModified(Collections.singletonList(model)));
            }
        });
        animator.start();
    }

    @Override
    protected void configureChatMessage(final ComposeMessageHolder holder, Context context, final int position) {

        logger.info("configureChatMessage for {}", getMessageModel().getId());

        AudioDataModel audioDataModel;
        FileDataModel fileDataModel = null;
        final long duration;
        final boolean isDownloaded;
        String caption = null;

        if (getMessageModel().getType() == MessageType.VOICEMESSAGE) {
            audioDataModel = getMessageModel().getAudioData();
            duration = audioDataModel.getDuration();
            isDownloaded = audioDataModel.isDownloaded();
        } else {
            fileDataModel = getMessageModel().getFileData();
            duration = fileDataModel.getDurationSeconds();
            isDownloaded = fileDataModel.isDownloaded();
            caption = fileDataModel.getCaption();
        }

        // F1Whisper: "listen once" voice messages. Only relevant for incoming messages.
        final boolean isListenOnce = !getMessageModel().isOutbox()
            && fileDataModel != null
            && fileDataModel.isListenOnce();
        // F1-PATCH: finish a burn whose playback never ended. A claim with no consumption means the
        // process died between the two, and the bubble is the natural repair point: it already holds
        // the model, so unlike the expiry repair this costs no query and needs no boot pass.
        //
        // F1Whisper (fourth fork review, F4-10): only when nothing in this process is actively playing it. The durable
        // claim cannot tell a live playback from an abandoned one, and on the normal first-play path the bubble rebinds
        // between the claim and the first audible frame (STATE_READY -> markAsConsumed -> onModified), so this used to
        // burn the message the user had just started - deleting the file and collapsing the controls before playback
        // began. An active owner means the claim is live, not abandoned.
        if (ListenOnceEnforcer.gateOf(getMessageModel()) == ListenOnceGate.BLOCKED_BURN_PENDING
            && !ListenOnceOwnership.isActive(getMessageModel().getId())) {
            ListenOnceEnforcer.burn(getMessageModel(), getMessageService(), getFileService(), false);
        }
        final boolean alreadyListened = isListenOnceSpent(getMessageModel());

        MessagePlayer audioMessagePlayer = messagePlayerFactory.create(getMessageModel(), helper.getMediaControllerFuture());

        setOnClickListener(view -> {
            // no action on onClick
        }, holder.messageBlockView);

        holder.messagePlayer = audioMessagePlayer;
        holder.readOnButton.setOnClickListener(v -> {
            float currentSpeed = getPreferenceService().getAudioPlaybackSpeed();
            float speed = audioMessagePlayer.togglePlaybackSpeed(currentSpeed);
            setSpeedButtonText(holder, speed);
        });

        setSpeedButtonText(holder, getPreferenceService().getAudioPlaybackSpeed());
        holder.seekBar.setMessageModel(getMessageModel(), helper.getThumbnailCache());
        holder.seekBar.setEnabled(false);
        holder.readOnButton.setVisibility(View.GONE);
        holder.audioMessageIcon.setVisibility(View.VISIBLE);
        // F1Whisper: a recycled holder may carry a collapsed/burned listen-once state from a previous
        // message (audio row hidden). Restore the row to its default visible state on every bind so a
        // fresh/unplayed voice never renders blank.
        restoreAudioRow(holder);
        // F1Whisper: show the "1" (listen once) badge in place of the microphone icon for incoming
        // listen-once voice messages.
        if (isListenOnce) {
            holder.audioMessageIcon.setImageResource(R.drawable.ic_listen_once);
            holder.audioMessageIcon.setContentDescription(context.getString(R.string.listen_once_badge_content_description));
        } else {
            holder.audioMessageIcon.setImageResource(R.drawable.ic_microphone_outline);
        }
        holder.controller.setOnClickListener(v -> {
            // F1-PATCH: re-read the model instead of closing over the bind-time value. The burn
            // persists on a worker thread, so between bind and tap this bubble's message can go from
            // playable to spent; a captured boolean would authorise exactly the replay the burn just
            // took away. Cheap: the flags are already-parsed metadata on the in-memory model.
            if (isListenOnceSpent(getMessageModel())) {
                // Already played once: replay is blocked.
                Toast.makeText(context, R.string.listen_once_already_listened, Toast.LENGTH_SHORT).show();
                return;
            }
            int status = holder.controller.getStatus();

            switch (status) {
                case ControllerView.STATUS_READY_TO_RETRY:
                    propagateControllerRetryClickToParent();
                    break;
                case ControllerView.STATUS_READY_TO_PLAY:
                case ControllerView.STATUS_PLAYING:
                case ControllerView.STATUS_READY_TO_DOWNLOAD:
                    if (holder.seekBar != null && audioMessagePlayer != null) {
                        audioMessagePlayer.togglePlayPause();
                    }
                    break;
                case ControllerView.STATUS_PROGRESSING:
                    if (MessageUtil.isFileMessageBeingSent(getMessageModel())) {
                        getMessageService().cancelMessageUpload(getMessageModel());
                    } else {
                        audioMessagePlayer.cancel();
                    }
                    break;
                default:
                    // no action taken for other statuses
                    break;
            }
        });

        RuntimeUtil.runOnUiThread(() -> {
            setupResendStatus(holder);

            holder.controller.setNeutral();

            //reset progressbar
            updateProgressCount(holder, 0);

            if (alreadyListened) {
                // F1Whisper: a played-once (burned) listen-once voice message collapses to a small
                // localized "voice message expired" note (Telegram-style), NOT a flame bubble. If it
                // JUST burned, the ember burst first plays over the full bubble and THEN it collapses;
                // on reopen/scroll the registry is empty so the collapsed note shows with no animation.
                final int burnId = getMessageModel().getId();
                if (ListenOnceBurnRegistry.isBurning(burnId)) {
                    // Burst already running over this bubble; keep it full (do NOT collapse, do NOT
                    // restart) so the extra re-renders the burn fires cannot collapse it mid-burst.
                    showBurningRow(holder);
                } else if (ListenOnceBurnRegistry.consumeBurnAnimation(burnId)) {
                    ListenOnceBurnRegistry.setBurning(burnId);
                    showBurningRow(holder);
                    playBurnAnimation(holder, burnId);
                } else {
                    collapseToExpiredNote(holder);
                }
                return;
            }

            if (audioMessagePlayer != null) {
                boolean isPlaying = false;
                if (holder.seekBar != null) {
                    holder.seekBar.setEnabled(false);
                }

                switch (audioMessagePlayer.getState()) {
                    case MessagePlayer.State_NONE:
                        if (isDownloaded) {
                            if (holder.seekBar != null) {
                                updateProgressCount(holder, 0);
                                holder.seekBar.setMessageModel(getMessageModel(), helper.getThumbnailCache());
                            }
                            holder.controller.setPlay();
                        } else {
                            if (helper.getDownloadService().isDownloading(getMessageModel().getId())) {
                                holder.controller.setProgressing(false);
                            } else {
                                holder.controller.setReadyToDownload();
                            }
                        }
                        break;
                    case MessagePlayer.State_DOWNLOADING:
                    case MessagePlayer.State_DECRYPTING:
                        //show loading
                        holder.controller.setProgressing();
                        break;
                    case MessagePlayer.State_DOWNLOADED:
                    case MessagePlayer.State_DECRYPTED:
                        if (holder.seekBar != null) {
                            updateProgressCount(holder, 0);
                            holder.seekBar.setMessageModel(getMessageModel(), helper.getThumbnailCache());
                        }
                        holder.controller.setPlay();
                        break;
                    case MessagePlayer.State_PLAYING:
                        isPlaying = true;
                        logger.debug("playing");
                        // fallthrough
                    case MessagePlayer.State_PAUSE:
                        if (isPlaying) {
                            holder.controller.setPause();
                        } else {
                            holder.controller.setPlay();
                        }
                        changePlayingState(holder, isPlaying);

                        if (holder.seekBar != null && audioMessagePlayer.getDuration() > 0) {
                            holder.seekBar.setEnabled(true);
                            logger.debug("SeekBar: Duration = {}", audioMessagePlayer.getDuration());
                            holder.seekBar.setMax(audioMessagePlayer.getDuration());
                            logger.debug("SeekBar: Position = {}", audioMessagePlayer.getPosition());
                            updateProgressCount(holder, audioMessagePlayer.getPosition());
                            holder.seekBar.setOnSeekBarChangeListener(new AudioProgressBarView.OnSeekBarChangeListener() {
                                @Override
                                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                                }

                                @Override
                                public void onStartTrackingTouch(SeekBar seekBar) {
                                }

                                @Override
                                public void onStopTrackingTouch(SeekBar seekBar) {
                                    audioMessagePlayer.seekTo(seekBar.getProgress());
                                }
                            });
                        }
                        break;
                }

                Context applicationContext = context.getApplicationContext();
                audioMessagePlayer
                    .addListener(LISTENER_TAG, humanReadableMessage -> RuntimeUtil.runOnUiThread(() -> Toast.makeText(applicationContext, humanReadableMessage, Toast.LENGTH_SHORT).show()))

                    .addListener(LISTENER_TAG, new MessagePlayer.DecryptionListener() {
                        @Override
                        public void onStart(AbstractMessageModel messageModel) {
                            invalidate(holder, context, position);
                        }

                        @Override
                        public void onEnd(final AbstractMessageModel messageModel, boolean success, final String message, File decryptedFile) {
                            if (!success) {
                                RuntimeUtil.runOnUiThread(() -> {
                                    holder.controller.setPlay();
                                    if (!TestUtil.isEmptyOrNull(message)) {
                                        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                            invalidate(holder, context, position);
                        }
                    })

                    .addListener(LISTENER_TAG, new MessagePlayer.DownloadListener() {
                        @Override
                        public void onStart(AbstractMessageModel messageModel) {
                            invalidate(holder, context, position);
                        }

                        @Override
                        public void onStatusUpdate(AbstractMessageModel messageModel, int progress) {
                        }

                        @Override
                        public void onEnd(final AbstractMessageModel messageModel, boolean success, final String message) {
                            if (!success) {
                                RuntimeUtil.runOnUiThread(() -> {
                                    holder.controller.setReadyToDownload();
                                    if (!TestUtil.isEmptyOrNull(message)) {
                                        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                            invalidate(holder, context, position);
                        }
                    })

                    .addListener(LISTENER_TAG, new MessagePlayer.PlaybackListener() {
                        @Override
                        public void onPlay(final AbstractMessageModel messageModel, boolean autoPlay) {
                            RuntimeUtil.runOnUiThread(() -> {
                                if (holder.position == position && getMessageModel().getId() == messageModel.getId()) {
                                    logger.debug("onPlay");
                                    invalidate(holder, context, position);
                                    changePlayingState(holder, true);
                                }
                            });
                        }

                        @Override
                        public void onPause(final AbstractMessageModel messageModel) {
                            RuntimeUtil.runOnUiThread(() -> {
                                if (holder.position == position && getMessageModel().getId() == messageModel.getId()) {
                                    logger.debug("onPause");
                                    invalidate(holder, context, position);
                                    changePlayingState(holder, false);
                                }
                            });
                        }

                        @Override
                        public void onStatusUpdate(final AbstractMessageModel messageModel, final int pos) {
                            RuntimeUtil.runOnUiThread(() -> {
                                if (holder.position == position && getMessageModel().getId() == messageModel.getId()) {
                                    if (holder.seekBar != null) {
                                        if (holder.seekBar.getMax() != holder.messagePlayer.getDuration()) {
                                            logger.info("Audio message player duration changed old={} new={}", holder.seekBar.getMax(), holder.messagePlayer.getDuration());
                                            holder.seekBar.setMax(holder.messagePlayer.getDuration());
                                        }
                                    }
                                    updateProgressCount(holder, pos);

                                    // make sure pinlock is not activated while playing
                                    userInteractionListener.interact();
                                }
                            });
                        }

                        @Override
                        public void onStop(final AbstractMessageModel messageModel) {
                            RuntimeUtil.runOnUiThread(() -> {
                                if (holder.position == position && getMessageModel().getId() == messageModel.getId()) {
                                    logger.debug("onStop getMessageModel {} messageModel {} position {}", getMessageModel().getId(), messageModel.getId(), position);
                                    invalidate(holder, context, position);
                                    if (messageModel.isAvailable()) {
                                        holder.controller.setPlay();
                                    } else {
                                        holder.controller.setReadyToDownload();
                                    }
                                    holder.seekBar.setEnabled(false);
                                    updateProgressCount(holder, 0);
                                    changePlayingState(holder, false);
                                }
                            });
                        }
                    });
            } else {
                //no player => no playable file
                holder.controller.setNeutral();

                if (getMessageModel().getState() == MessageState.SENDFAILED) {
                    holder.controller.setRetry();
                }
            }

            // Message state will be null if the message was deleted
            if (getMessageModel().isOutbox() && getMessageModel().getState() != null) {
                // outgoing message
                switch (getMessageModel().getState()) {
                    case TRANSCODING:
                        holder.controller.setTranscoding();
                        break;
                    case PENDING:
                    case UPLOADING:
                    case SENDING:
                        holder.controller.setProgressing();
                        break;
                    case SENDFAILED:
                    case FS_KEY_MISMATCH:
                        holder.controller.setRetry();
                        break;
                    default:
                        break;
                }
            }
        });

        //do not show duration if 0
        if (duration > 0) {
            setDatePrefix(ElapsedTimeFormatter.secondsToString(duration));
            setDuration(duration);
            dateContentDescriptionPrefix = context.getString(R.string.duration) + ": " + ElapsedTimeFormatter.getDurationStringHuman(context, duration);
        }

        if (holder.contentView != null) {
            // F1Whisper: a fully-collapsed burned bubble (audio row hidden) wraps the small note;
            // every other audio bubble (including one mid-burn, whose row is still visible) uses the
            // fixed preferred audio width.
            final boolean burnedCollapsed = holder.controller != null
                && holder.controller.getParent() instanceof View
                && ((View) holder.controller.getParent()).getVisibility() == View.GONE;
            holder.contentView.getLayoutParams().width = burnedCollapsed
                ? ViewGroup.LayoutParams.WRAP_CONTENT
                : ConfigUtils.getPreferredAudioMessageWidth(context, false);
        }

        if (alreadyListened) {
            // F1Whisper: the burned bubble collapses to a small localized note (Telegram-style). The
            // recipient sees "Voice message expired"; the SENDER (whose copy burns on send, before the
            // recipient has listened) sees the neutral "Listen-once voice message" instead.
            caption = context.getString(getMessageModel().isOutbox()
                ? R.string.listen_once_sent
                : R.string.listen_once_expired);
        }

        configureBodyText(holder, caption);
    }

    /**
     * F1Whisper: has this listen-once voice message been spent, so that the bubble collapses to the
     * small note and refuses playback?
     *
     * <p>Spent means <em>claimed or consumed</em>, not consumed alone. A claim is written before the
     * decrypted audio reaches the player; a message carrying a claim but no consumption had its
     * playback interrupted, and treating it as still playable is precisely the replay that
     * {@link ListenOnceDecision} exists to remove.</p>
     *
     * <p>Deliberately spans both directions, unlike the playback gate: the sender's own copy burns
     * on send and its bubble shows the neutral "listen-once voice message" note.</p>
     *
     * <p>And deliberately reads the persisted metadata rather than {@link MessageState#CONSUMED}: a
     * voice message moves to CONSUMED at playback <em>start</em>, so keying off the state would
     * expire the bubble the instant it began playing, hiding the controls and progress mid-play.</p>
     */
    private static boolean isListenOnceSpent(@NonNull AbstractMessageModel messageModel) {
        if (messageModel.getType() != MessageType.FILE) {
            return false;
        }
        final FileDataModel fileData = messageModel.getFileData();
        return fileData != null
            && fileData.isListenOnce()
            && (fileData.isListenOnceConsumed() || fileData.isListenOnceClaimed());
    }

    @UiThread
    private void updateProgressCount(final ComposeMessageHolder holder, int value) {
        if (holder != null && holder.size != null && holder.seekBar != null) {
            holder.seekBar.setProgress(value);
            holder.size.setText(ElapsedTimeFormatter.millisecondsToString(value));
        }
    }

    @UiThread
    private synchronized void changePlayingState(final ComposeMessageHolder holder, boolean isPlaying) {
        logger.debug("changePlayingState for {} to {}", getMessageModel().getId(), isPlaying);
        holder.readOnButton.setVisibility(isPlaying ? View.VISIBLE : View.GONE);
        holder.audioMessageIcon.setVisibility(isPlaying ? View.GONE : View.VISIBLE);
    }

    @SuppressLint("DefaultLocale")
    private void setSpeedButtonText(final ComposeMessageHolder holder, float speed) {
        holder.readOnButton.setText(
            speed % 1.0 != 0L ?
                String.format("%sx", speed) :
                String.format(" %.0fx ", speed)
        );
    }
}
