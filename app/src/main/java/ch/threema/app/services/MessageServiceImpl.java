package ch.threema.app.services;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.text.format.DateUtils;
import android.util.SparseIntArray;
import android.widget.Toast;

import ch.threema.app.preference.service.SynchronizedSettingsService;
import ch.threema.base.crypto.NaCl;

import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.WorkerThread;
import androidx.collection.ArrayMap;
import androidx.core.app.NotificationManagerCompat;
import ch.threema.app.ExecutorServices;
import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.emojis.EmojiUtil;
import ch.threema.app.exceptions.NotAllowedException;
import ch.threema.app.exceptions.TranscodeCanceledException;
import ch.threema.app.managers.ListenerManager;
import ch.threema.app.messagereceiver.ContactMessageReceiver;
import ch.threema.app.messagereceiver.DistributionListMessageReceiver;
import ch.threema.app.messagereceiver.GroupMessageReceiver;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.multidevice.MultiDeviceManager;
import ch.threema.app.notifications.NotificationIDs;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.routines.MarkAsReadRoutine;
import ch.threema.app.services.ballot.BallotService;
import ch.threema.app.services.ballot.BallotUpdateResult;
import ch.threema.app.services.messageplayer.ListenOnceBurnRegistry;
import ch.threema.app.services.messageplayer.MessagePlayerService;
import ch.threema.app.services.notification.NotificationService;
import ch.threema.app.tasks.PersistentTaskRowGate;
import ch.threema.app.ui.MediaItem;
import ch.threema.app.utils.BallotUtil;
import ch.threema.app.utils.BitmapUtil;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.ContactUtil;
import ch.threema.app.utils.ExifInterface;
import ch.threema.app.utils.FileUtil;
import ch.threema.app.utils.GeoLocationUtil;
import ch.threema.app.utils.GroupUtil;
import ch.threema.app.utils.IconUtil;
import ch.threema.app.utils.MessageUtil;
import ch.threema.app.utils.MimeUtil;
import ch.threema.app.utils.NameUtil;
import ch.threema.data.datatypes.ContactNameFormat;
import ch.threema.app.utils.QuoteUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.app.utils.ElapsedTimeFormatter;
import ch.threema.app.utils.TestUtil;
import ch.threema.app.utils.ThumbnailUtil;
import ch.threema.app.utils.VideoUtil;
import ch.threema.app.video.transcoder.VideoConfig;
import ch.threema.app.video.transcoder.VideoTranscoder;
import ch.threema.app.voicemessage.AudioTrimmer;
import ch.threema.app.voip.groupcall.GroupCallDescription;
import ch.threema.base.ProgressListener;
import ch.threema.base.ThreemaException;
import ch.threema.base.crypto.SymmetricEncryptionResult;
import ch.threema.base.crypto.SymmetricEncryptionService;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

import ch.threema.base.utils.Utils;
import ch.threema.data.models.EmojiReactionData;
import ch.threema.data.models.GroupModel;
import ch.threema.data.repositories.EditHistoryRepository;
import ch.threema.data.repositories.EmojiReactionEntryCreateException;
import ch.threema.data.repositories.EmojiReactionEntryRemoveException;
import ch.threema.data.repositories.EmojiReactionsRepository;
import ch.threema.domain.models.GroupId;
import ch.threema.domain.models.MessageId;
import ch.threema.domain.protocol.blob.BlobScope;
import ch.threema.domain.protocol.blob.BlobUploader;
import ch.threema.domain.protocol.csp.MessageTooLongException;
import ch.threema.domain.protocol.csp.ProtocolDefines;
import ch.threema.domain.protocol.csp.messages.AbstractGroupMessage;
import ch.threema.domain.protocol.csp.messages.AbstractMessage;
import ch.threema.domain.protocol.csp.messages.BadMessageException;
import ch.threema.domain.protocol.csp.messages.DeleteMessage;
import ch.threema.domain.protocol.csp.messages.GroupImageMessage;
import ch.threema.domain.protocol.csp.messages.location.GroupLocationMessage;
import ch.threema.domain.protocol.csp.messages.GroupTextMessage;
import ch.threema.domain.protocol.csp.messages.ImageMessage;
import ch.threema.domain.protocol.csp.messages.location.LocationMessage;
import ch.threema.domain.protocol.csp.messages.TextMessage;
import ch.threema.domain.protocol.csp.messages.ballot.BallotData;
import ch.threema.domain.protocol.csp.messages.ballot.BallotSetupInterface;
import ch.threema.domain.protocol.csp.messages.ballot.GroupPollSetupMessage;
import ch.threema.domain.protocol.csp.messages.ballot.PollSetupMessage;
import ch.threema.domain.protocol.csp.messages.file.FileData;
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityMode;
import ch.threema.domain.protocol.csp.messages.location.Poi;
import ch.threema.domain.stores.IdentityStore;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.libthreema.CryptoException;
import ch.threema.protobuf.csp.e2e.Reaction;
import ch.threema.storage.DatabaseService;
import ch.threema.storage.MessageCacheCoherence;
import ch.threema.storage.MessageRowUpdate;
import ch.threema.storage.TimelineKeyset;
import ch.threema.storage.factories.ServerMessageModelFactory;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.DistributionListMessageModel;
import ch.threema.storage.models.FirstUnreadMessageModel;
import ch.threema.storage.models.group.GroupMessageModel;
import ch.threema.storage.models.group.GroupModelOld;
import ch.threema.storage.models.MessageModel;
import ch.threema.storage.models.MessageState;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.ServerMessageModel;
import ch.threema.storage.models.access.GroupAccessModel;
import ch.threema.storage.models.ballot.BallotModel;
import ch.threema.storage.models.data.DisplayTag;
import ch.threema.storage.models.data.LocationDataModel;
import ch.threema.storage.models.data.MessageContentsType;
import ch.threema.storage.models.data.media.BallotDataModel;
import ch.threema.storage.models.data.media.FileDataModel;
import ch.threema.storage.models.data.media.ImageDataModel;
import ch.threema.storage.models.data.media.MediaMessageDataInterface;
import ch.threema.storage.models.data.status.DisappearingStatusDataModel;
import ch.threema.storage.models.data.status.ForwardSecurityStatusDataModel;
import ch.threema.storage.models.data.status.GroupCallStatusDataModel;
import ch.threema.storage.models.data.status.GroupStatusDataModel;
import ch.threema.storage.models.data.status.VoipStatusDataModel;

import static ch.threema.app.AppConstants.MAX_BLOB_SIZE;
import static ch.threema.app.AppConstants.MAX_BLOB_SIZE_MB;
import static ch.threema.app.preference.service.PreferenceService.IMAGE_SCALE_DEFAULT;
import static ch.threema.app.ui.MediaItem.TIME_UNDEFINED;
import static ch.threema.app.ui.MediaItem.TYPE_AUDIO_FILE;
import static ch.threema.app.ui.MediaItem.TYPE_FILE;
import static ch.threema.app.ui.MediaItem.TYPE_IMAGE;
import static ch.threema.app.ui.MediaItem.TYPE_IMAGE_ANIMATED;
import static ch.threema.app.ui.MediaItem.TYPE_IMAGE_CAM;
import static ch.threema.app.ui.MediaItem.TYPE_LOCATION;
import static ch.threema.app.ui.MediaItem.TYPE_TEXT;
import static ch.threema.app.ui.MediaItem.TYPE_VIDEO;
import static ch.threema.app.ui.MediaItem.TYPE_VIDEO_CAM;
import static ch.threema.app.ui.MediaItem.TYPE_VOICEMESSAGE;
import static ch.threema.app.utils.MessageUtilKt.canBeEdited;
import static ch.threema.app.utils.StreamUtilKt.getFromUri;
import static ch.threema.common.ByteArrayExtensionsKt.toHexString;
import static ch.threema.common.SecureRandomExtensionsKt.generateRandomBytes;
import static ch.threema.common.SecureRandomExtensionsKt.secureRandom;
import static ch.threema.common.InputStreamExtensionsKt.copyTo;
import static ch.threema.domain.protocol.csp.messages.file.FileData.RENDERING_STICKER;
import static kotlin.io.ByteStreamsKt.readBytes;

public class MessageServiceImpl implements MessageService {
    private static final Logger logger = getThreemaLogger("MessageServiceImpl");

    public static final long FILE_AUTO_DOWNLOAD_MAX_SIZE_M = 5; // MB
    public static final long FILE_AUTO_DOWNLOAD_MAX_SIZE_ISO = FILE_AUTO_DOWNLOAD_MAX_SIZE_M * 1024 * 1024; // used for calculations
    public static final long FILE_AUTO_DOWNLOAD_MAX_SIZE_SI = FILE_AUTO_DOWNLOAD_MAX_SIZE_M * 1000 * 1000; // used for presentation only
    public static final int THUMBNAIL_SIZE_PX = 512;

    private final @NonNull Context context;

    // Services
    private final MessageSendingService messageSendingService;
    private final DatabaseService databaseService;
    @NonNull
    private final ServerMessageModelFactory serverMessageModelFactory;
    private final ContactService contactService;
    private final FileService fileService;
    private final IdentityStore identityStore;
    private final BallotService ballotService;
    @NonNull
    private final PreferenceService preferenceService;
    @NonNull
    private final SynchronizedSettingsService synchronizedSettingsService;
    private final LockAppService appLockService;
    private final GroupService groupService;
    private final ApiService apiService;
    private final DownloadService downloadService;
    private final ConversationCategoryService conversationCategoryService;
    @NonNull
    private final BlockedIdentitiesService blockedIdentitiesService;
    private final SymmetricEncryptionService symmetricEncryptionService;

    // Repositories
    private final EditHistoryRepository editHistoryRepository;
    private final EmojiReactionsRepository emojiReactionsRepository;

    // Managers
    private final MultiDeviceManager multiDeviceManager;

    // Caches
    private final Collection<MessageModel> contactMessageCache;
    private final Collection<GroupMessageModel> groupMessageCache;
    private final Collection<DistributionListMessageModel> distributionListMessageCache;

    private final SparseIntArray loadingProgress = new SparseIntArray();

    public MessageServiceImpl(
        @NonNull
        Context context,
        CacheService cacheService,
        DatabaseService databaseService,
        ContactService contactService,
        FileService fileService,
        IdentityStore identityStore,
        SymmetricEncryptionService symmetricEncryptionService,
        @NonNull PreferenceService preferenceService,
        @NonNull SynchronizedSettingsService synchronizedSettingsService,
        LockAppService appLockService,
        BallotService ballotService,
        GroupService groupService,
        ApiService apiService,
        DownloadService downloadService,
        @NonNull ConversationCategoryService conversationCategoryService,
        @NonNull BlockedIdentitiesService blockedIdentitiesService,
        MultiDeviceManager multiDeviceManager,
        EditHistoryRepository editHistoryRepository,
        EmojiReactionsRepository emojiReactionsRepository,
        @NonNull ServerMessageModelFactory serverMessageModelFactory
    ) {
        this.context = context;
        this.databaseService = databaseService;
        this.contactService = contactService;
        this.fileService = fileService;
        this.identityStore = identityStore;
        this.symmetricEncryptionService = symmetricEncryptionService;
        this.preferenceService = preferenceService;
        this.synchronizedSettingsService = synchronizedSettingsService;
        this.appLockService = appLockService;
        this.ballotService = ballotService;
        this.groupService = groupService;
        this.apiService = apiService;
        this.downloadService = downloadService;
        this.conversationCategoryService = conversationCategoryService;
        this.blockedIdentitiesService = blockedIdentitiesService;

        contactMessageCache = cacheService.getMessageModelCache();
        groupMessageCache = cacheService.getGroupMessageModelCache();
        distributionListMessageCache = cacheService.getDistributionListMessageCache();

        this.multiDeviceManager = multiDeviceManager;

        this.editHistoryRepository = editHistoryRepository;
        this.emojiReactionsRepository = emojiReactionsRepository;

        this.serverMessageModelFactory = serverMessageModelFactory;

        // init queue
        messageSendingService = new MessageSendingServiceExponentialBackOff(new MessageSendingService.MessageSendingServiceState() {
            @Override
            public void processingFailed(AbstractMessageModel messageModel, MessageReceiver<AbstractMessageModel> receiver, @Nullable Exception cause) {
                //remove send machine
                removeSendMachine(messageModel);

                // F1Whisper auto-resend: if the backoff exhausted its attempts because of a
                // transient connectivity failure (connection down / blob HTTP / auth-token), do
                // NOT mark SENDFAILED. Leave the message in its current sending/uploading/pending
                // state so the reconnect auto-resend scan re-runs the send silently once the
                // connection returns (the message keeps its original apiMessageId, so the receiver
                // dedupes if the earlier attempt actually landed). Only terminal failures (unknown
                // recipient, FS, ballot NotAllowed, MessageTooLong, encryption, user cancel) fall
                // through to SENDFAILED, exactly as upstream.
                if (SendFailureClassifier.isConnectivityFailure(cause)) {
                    logger.info(
                        "{} send failed with a connectivity-class error; leaving pending for the reconnect scan",
                        messageModel.getUid()
                    );
                    // Normalise the transient-failed state to PENDING so the scan's eligibility
                    // check is uniform and the UI shows the message as still in flight (not failed).
                    // Clear any stale terminal marker from a prior attempt.
                    markConnectivityPending(messageModel);
                    return;
                }

                markTerminalSendFailed(messageModel);
            }

            @Override
            public void exception(Exception x, int tries) {
                if (tries >= 5) {
                    logger.error("Exception", x);
                }
            }
        });
    }

    /**
     * F1Whisper auto-resend: mark an outgoing message SENDFAILED for a TERMINAL cause. The
     * DISPLAY_TAG_SEND_FAILED_TERMINAL bit is set centrally by
     * {@link #updateOutgoingMessageState} on entry to SENDFAILED, so the reconnect scan never
     * auto-resends it and the unsent-message notification still nags. Idempotent.
     */
    private void markTerminalSendFailed(@NonNull AbstractMessageModel messageModel) {
        if (messageModel.isDeleted()) {
            return;
        }
        updateOutgoingMessageState(messageModel, MessageState.SENDFAILED, new Date());
    }

    /**
     * F1Whisper auto-resend: after a transient connectivity failure, keep the message in flight
     * (not SENDFAILED) so the reconnect scan re-runs the send once the connection returns. The
     * message stays in its current in-flight state (PENDING/UPLOADING/SENDING) - all of which the
     * scan treats as "stuck, resend" - which renders as a sending indicator rather than a red
     * failure (Telegram clock semantics). A SENDFAILED left over from a prior terminal attempt is
     * pushed back to PENDING (the only transition {@code canChangeToState} allows into PENDING).
     * Clears any stale terminal marker and does not fire an unsent-message nag. Idempotent.
     */
    private void markConnectivityPending(@NonNull AbstractMessageModel messageModel) {
        if (messageModel.isDeleted()) {
            return;
        }
        // A previous attempt may have set the terminal bit; clear it since this failure is transient.
        boolean clearedTerminal = messageModel.isSendFailedTerminal();
        if (clearedTerminal) {
            messageModel.setSendFailedTerminal(false);
        }
        // Only SENDFAILED -> PENDING is a permitted transition; the other in-flight states
        // (PENDING/UPLOADING/SENDING) are already scan-eligible and are left untouched so we never
        // fight canChangeToState or resurrect a SENT message.
        if (messageModel.getState() == MessageState.SENDFAILED) {
            updateOutgoingMessageState(messageModel, MessageState.PENDING, new Date());
        } else if (clearedTerminal) {
            // Persist the cleared bit; updateOutgoingMessageState was not called above.
            // F1Whisper (seventh fork review, F7-05): the one column this owns, conditionally. It used to full-row-save
            // a model whose send is in flight, so it could write back the pre-transition state and countdown of a
            // message the send task had just completed.
            if (clearDisplayTag(messageModel, DisplayTag.DISPLAY_TAG_SEND_FAILED_TERMINAL)) {
                fireOnModifiedMessage(messageModel);
            }
        }
    }

    private void cache(AbstractMessageModel m) {
        if (m instanceof GroupMessageModel) {
            synchronized (groupMessageCache) {
                groupMessageCache.add((GroupMessageModel) m);
            }
        } else if (m instanceof MessageModel) {
            synchronized (contactMessageCache) {
                contactMessageCache.add((MessageModel) m);
            }
        }
    }

    @Override
    public AbstractMessageModel createStatusMessage(String statusMessage, MessageReceiver receiver) {
        AbstractMessageModel model = receiver.createAndSaveStatusModel(statusMessage, new Date());
        fireOnCreatedMessage(model);
        return model;
    }

    @Override
    public AbstractMessageModel createVoipStatus(
        @NonNull VoipStatusDataModel data,
        @NonNull MessageReceiver receiver,
        boolean isOutbox,
        boolean isRead
    ) {
        logger.info("Storing voip status message (outbox={}, status={}, reason={})",
            isOutbox, data.getStatus(), data.getReason());
        final AbstractMessageModel model = receiver.createLocalModel(
            MessageType.VOIP_STATUS,
            MessageContentsType.VOIP_STATUS,
            data.getDate() != null ? data.getDate() : new Date()
        );
        model.setOutbox(isOutbox);
        model.setVoipStatusData(data);
        model.setSaved(true);
        model.setRead(isRead);
        receiver.saveLocalModel(model);
        fireOnCreatedMessage(model);
        return model;
    }

    @Override
    @Nullable
    public AbstractMessageModel createGroupCallStatus(
        @NonNull GroupCallStatusDataModel data,
        @NonNull MessageReceiver receiver,
        @Nullable GroupCallDescription call,
        boolean isOutbox,
        Date postedDate) {
        if (receiver instanceof GroupMessageReceiver && ((GroupMessageReceiver) receiver).getGroup() == null) {
            logger.info("Unable to store group call status message. Group no longer exists");
            return null;
        }

        logger.info("Storing group call status message for call={}", call != null ? call.getCallId() : "n/a");
        final AbstractMessageModel model = receiver.createLocalModel(
            MessageType.GROUP_CALL_STATUS,
            MessageContentsType.GROUP_CALL_STATUS,
            new Date()
        );
        model.setPostedAt(postedDate);
        model.setOutbox(isOutbox);
        model.setGroupCallStatusData(data);
        model.setSaved(true);
        model.setStatusMessage(true);
        model.setRead(data.getStatus() != GroupCallStatusDataModel.STATUS_STARTED);
        receiver.saveLocalModel(model);
        fireOnCreatedMessage(model);
        return model;
    }

    @Override
    public AbstractMessageModel createForwardSecurityStatus(
        @NonNull MessageReceiver receiver,
        @ForwardSecurityStatusDataModel.ForwardSecurityStatusType int type,
        int quantity,
        @Nullable String staticText) {
        logger.info("Storing forward security status message of type {}", type);

        final AbstractMessageModel model = receiver.createLocalModel(
            MessageType.FORWARD_SECURITY_STATUS,
            MessageContentsType.FORWARD_SECURITY_STATUS,
            new Date()
        );
        model.setOutbox(false);
        model.setForwardSecurityStatusData(ForwardSecurityStatusDataModel.create(type, quantity, staticText));
        model.setSaved(true);
        model.setStatusMessage(true);
        model.setRead(true);
        receiver.saveLocalModel(model);
        fireOnCreatedMessage(model);
        return model;
    }

    @Override
    public AbstractMessageModel createGroupStatus(
        @NonNull GroupMessageReceiver receiver,
        @NonNull GroupStatusDataModel.GroupStatusType type,
        @Nullable String identity,
        @Nullable String ballotName,
        @Nullable String newGroupName
    ) {
        logger.info("Storing group status message of type {}", type.getType());

        final GroupMessageModel model = receiver.createLocalModel(
            MessageType.GROUP_STATUS,
            MessageContentsType.GROUP_STATUS,
            new Date()
        );
        model.setOutbox(false);
        model.setGroupStatusData(GroupStatusDataModel.create(type, identity, ballotName, newGroupName));
        model.setSaved(true);
        model.setStatusMessage(true);
        model.setRead(true);
        receiver.saveLocalModel(model);
        fireOnCreatedMessage(model);
        return model;
    }

    @Override
    public AbstractMessageModel createDisappearingStatus(
        @NonNull MessageReceiver receiver,
        @Nullable String changedByIdentity,
        int timerSeconds
    ) {
        logger.info("Storing disappearing status message: timer={}s changedBy={}", timerSeconds, changedByIdentity);
        final AbstractMessageModel model = receiver.createLocalModel(
            MessageType.DISAPPEARING_STATUS,
            MessageContentsType.STATUS,
            new Date()
        );
        model.setOutbox(changedByIdentity == null); // outbox=true if WE changed it
        model.setDisappearingStatusData(DisappearingStatusDataModel.create(timerSeconds, changedByIdentity));
        model.setSaved(true);
        model.setStatusMessage(true);
        model.setRead(true);
        receiver.saveLocalModel(model);
        fireOnCreatedMessage(model);
        return model;
    }

    public AbstractMessageModel createNewBallotMessage(
        MessageId messageId,
        BallotModel ballotModel,
        BallotDataModel.Type type,
        MessageReceiver receiver,
        int messageFlags,
        ForwardSecurityMode forwardSecurityMode) {
        return createNewBallotMessage(messageId, ballotModel, type, receiver, messageFlags, forwardSecurityMode, null);
    }

    /**
     * F1Whisper (fourth fork review, F4-05): as above, but for an INCOMING poll, whose sender advertised a per-message
     * disappearing timer that has to be on the row from its very first write. See {@link #freezeIncomingBeforeFirstWrite}.
     */
    private AbstractMessageModel createNewBallotMessage(
        MessageId messageId,
        BallotModel ballotModel,
        BallotDataModel.Type type,
        MessageReceiver receiver,
        int messageFlags,
        ForwardSecurityMode forwardSecurityMode,
        @Nullable Integer advertisedDisappearingTimerSeconds) {
        AbstractMessageModel model = receiver.createLocalModel(MessageType.BALLOT, MessageContentsType.BALLOT, TrustedClock.now()); // F1Whisper: server-corrected outgoing postedAt
        if (model != null) {
            //hack: save ballot id into body string
            model.setIdentity(ballotModel.getCreatorIdentity());
            model.setSaved(true);
            model.setBallotData(new BallotDataModel(type, ballotModel.getId()));
            model.setOutbox(ballotModel.getCreatorIdentity().equals(identityStore.getIdentityString()));
            model.setMessageId(messageId);
            model.setMessageFlags(messageFlags);
            model.setForwardSecurityMode(forwardSecurityMode);
            if (!model.isOutbox()) {
                freezeIncomingBeforeFirstWrite(model, advertisedDisappearingTimerSeconds);
            }
            receiver.saveLocalModel(model);
            cache(model);
            fireOnCreatedMessage(model);
        }

        return model;
    }

    /**
     * Send a text message to the specified receiver.
     *
     * @param message         The message text. May not be longer than {@link ProtocolDefines#MAX_TEXT_MESSAGE_LEN} UTF-8 bytes.
     * @param messageReceiver The receiver for this message.
     * @return the model of the sent message
     * @throws MessageTooLongException if the message is too long.
     * @throws ThreemaException        if the message text is empty after trimming.
     */
    @Override
    public AbstractMessageModel sendText(
        @NonNull String message,
        @NonNull MessageReceiver messageReceiver
    ) throws ThreemaException {
        final String tag = "sendTextMessage";

        logger.info("{}: start", tag);

        String trimmedMessage = validateTextMessage(message);

        logger.debug("{}: create model instance", tag);
        final AbstractMessageModel messageModel = messageReceiver.createLocalModel(MessageType.TEXT, MessageContentsType.TEXT, TrustedClock.now()); // F1Whisper: server-corrected outgoing postedAt
        logger.debug("{}: cache", tag);
        cache(messageModel);

        messageModel.setOutbox(true);
        messageModel.setBodyAndQuotedMessageId(trimmedMessage);
        messageModel.setState(MessageState.SENDING);
        messageModel.setSaved(true);

        logger.debug("{}: save db", tag);
        messageReceiver.saveLocalModel(messageModel);
        logger.debug("{}: fire create message", tag);
        fireOnCreatedMessage(messageModel);

        messageReceiver.createAndSendTextMessage(messageModel);
        String messageId = messageModel.getApiMessageId();
        logger.info("{}: message {} successfully queued", tag, (messageId != null ? messageId : messageModel.getId()));
        // F1Whisper (seventh fork review, F7-05): NO second save here. The receiver has already assigned the message id,
        // persisted the row and scheduled the persistent task, and a direct receiver save takes no cache monitor and
        // writes EVERY lifecycle column from a snapshot built before the SQL update. So the task's acknowledged
        // completion - terminal state, authoritative timestamp and the disappearing countdown, written together - could
        // land between that snapshot and its update and be overwritten by the stale SENDING and null clock. The
        // resulting row is neither due nor repairable (startup repair deliberately skips outgoing rows with no start),
        // so a reflected message kept its content past the interval it advertised.

        fireOnModifiedMessage(messageModel);

        return messageModel;
    }

    @Override
    public void sendEditedMessageText(
        @NonNull AbstractMessageModel message, // Let `message` be the referred message.
        @NonNull String newText,
        @NonNull Date editedAt,
        @NonNull MessageReceiver receiver
    ) throws ThreemaException {
        logger.debug("editText message = {}", message.getApiMessageId());

        if (!message.isOutbox()) {
            throw new ThreemaException("Tried editing a message that is not outgoing. message = " + message.getApiMessageId());
        }

        String trimmedNewText = validateTextMessage(newText);

        if (Objects.equals(message.getBody(), trimmedNewText)) {
            throw new ThreemaException("Tried editing a message with no changes. message = " + message.getApiMessageId());
        }

        if (message.getPostedAt() == null) {
            logger.error("postedAt is null for messageId={}}", message.getId());
            return;
        }

        if (!canBeEdited(message, isNotesGroup(receiver), editedAt, AbstractMessageModel::getPostedAt)) {
            logger.error("Message can not be edited");
            return;
        }

        if (!(receiver instanceof ContactMessageReceiver) && !(receiver instanceof GroupMessageReceiver)) {
            throw new ThreemaException("Unsupported receiver type of: " + receiver.getClass());
        }

        // F1Whisper (seventh fork review, F7-03): commit the edit LOCALLY FIRST, then announce it.
        //
        // The old order archived the outgoing edit task - carrying the new plaintext - before it even tried the local
        // write. Delete-for-everyone landing in between made the local write correctly refuse (a deleted row is out of
        // bounds for every lifecycle write) while the queued task still loaded the soft-deleted parent row and
        // transmitted the new text to the peer and to the user's other devices. The local message showed as deleted the
        // whole time, so nothing said the edit had gone out anyway.
        //
        // Committing first makes the row the single source of both the permission and the content: the task carries no
        // text at all and announces what the committed row says, or nothing.
        //
        // The text stored locally is now the TRIMMED text, the same string the peer receives. The two used to differ.
        if (!saveEditedMessageText(message, trimmedNewText, editedAt)) {
            logger.info("Not announcing the edit of {}: it did not commit locally", message.getApiMessageId());
            return;
        }

        if (receiver instanceof ContactMessageReceiver) {
            ((ContactMessageReceiver) receiver).sendEditMessage(message.getId(), editedAt);
        } else {
            ((GroupMessageReceiver) receiver).sendEditMessage(message.getId(), editedAt);
        }
    }

    private boolean isNotesGroup(@NonNull MessageReceiver receiver) {
        if (receiver instanceof GroupMessageReceiver) {
            return groupService.isNotesGroup(((GroupMessageReceiver) receiver).getGroup());
        }
        return false;
    }

    @Override
    public boolean saveEditedMessageText(@NonNull AbstractMessageModel message, String text, @Nullable Date editedAt) {
        logger.info("Save edited message = {}", message.getApiMessageId());

        final EditCommit committed = commitEditDurably(message, text, editedAt);
        if (committed == null) {
            logger.info("Not publishing the edit of {}: its row is gone or was superseded", message.getApiMessageId());
            return false;
        }

        // Committed. Only now does anything become visible: the caller's instance, the in-memory edit history, the
        // listeners. Publishing before the commit was how a rolled-back history entry stayed on screen.
        if (committed.historyEntry != null) {
            editHistoryRepository.publishEntry(committed.historyEntry);
        }
        applyEditTo(message, text);
        message.setEditedAt(editedAt);
        fireOnModifiedMessage(message);
        fireOnEditMessage(message);
        return true;
    }

    /**
     * A committed edit. Distinct from {@code null}, which means the row was lost: a commit that wrote no history entry
     * (an incoming edit carrying no {@code editedAt}) is still a commit.
     */
    private static final class EditCommit {
        @Nullable
        final EditHistoryRepository.PendingHistoryEntry historyEntry;

        EditCommit(@Nullable EditHistoryRepository.PendingHistoryEntry historyEntry) {
            this.historyEntry = historyEntry;
        }
    }

    /** Thrown inside the edit transaction to roll it back; never escapes {@link #commitEditDurably}. */
    private static final class EditSupersededException extends RuntimeException {
        EditSupersededException() {
            super(null, null, false, false);
        }
    }

    /**
     * F1Whisper (seventh fork review, F7-03): write the edit's history entry and the edited row as ONE transaction
     * against a freshly reloaded, undeleted row.
     *
     * <p>The defect this closes: the history entry - which stores the message's OLD plaintext - was inserted before the
     * row write was even attempted, and by an independent statement. Delete-for-everyone landing in between cleared the
     * row's body and deleted all of its history, and then this insert put a fresh copy of the old plaintext back. The
     * row write itself correctly refused (its deletion predicate is structural), so nothing rolled the entry back and
     * nothing pointed at it: the message showed as deleted while the text it was supposed to have destroyed was
     * recoverable from the history sheet. The row update's deletion predicate protects the message table only; anything
     * written outside its transaction can still contradict deletion.</p>
     *
     * <p>The history entry is taken from the RELOADED row rather than from the caller's instance, so the "old text" it
     * preserves is the text that was actually on disk a moment ago rather than whatever snapshot the caller held.</p>
     *
     * @return the commit, carrying the history entry to publish if it wrote one, or {@code null} if the row was lost.
     */
    @Nullable
    private EditCommit commitEditDurably(
        @NonNull AbstractMessageModel message,
        String text,
        @Nullable Date editedAt
    ) {
        for (int attempt = 0; attempt < CONDITIONAL_WRITE_ATTEMPTS; attempt++) {
            try {
                return databaseService.inTransaction(() -> {
                    final AbstractMessageModel current = reloadPersistedModel(message);
                    if (current == null || current.getDeletedAt() != null) {
                        // Never fall back to the caller's instance: that fallback is how an insert-capable save became
                        // reachable for a row that had gone.
                        throw new EditLostException();
                    }

                    // Read the pre-edit values for the history entry BEFORE the edit is applied to `current`.
                    final EditHistoryRepository.PendingHistoryEntry pending =
                        editedAt != null ? editHistoryRepository.createEntryDeferred(current) : null;

                    final String priorBody = current.getBody();
                    if (!applyEditTo(current, text)) {
                        throw new EditLostException();
                    }
                    final MessageRowUpdate update = MessageLifecycleUpdates.edit(
                        current.getBody(),
                        current.getCaption(),
                        editedAt,
                        priorBody
                    );
                    if (!applyRowUpdate(current, update)) {
                        // Rolls the history insert back with the transaction.
                        throw new EditSupersededException();
                    }
                    return new EditCommit(pending);
                });
            } catch (EditSupersededException superseded) {
                logger.debug("The edit of {} was superseded, re-reading (attempt {})", message.getId(), attempt + 1);
            } catch (EditLostException lost) {
                return null;
            }
        }
        logger.warn("Gave up storing the edit of uid={} after {} superseded attempts",
            message.getUid(), CONDITIONAL_WRITE_ATTEMPTS);
        return null;
    }

    /** Thrown inside the edit transaction when the row has gone or cannot carry an edit; never escapes. */
    private static final class EditLostException extends RuntimeException {
        EditLostException() {
            super(null, null, false, false);
        }
    }

    /**
     * Apply an edit's new text to {@code target}, in the shape the message type stores it.
     *
     * @return whether this type can carry an edit at all.
     */
    private boolean applyEditTo(@NonNull AbstractMessageModel target, String text) {
        switch (target.getType()) {
            case TEXT:
                target.setBody(text);
                return true;
            case FILE:
                target.setCaption(text);
                target.getFileData().setCaption(text);
                target.setBody(target.getFileData().toString());
                return true;
            default:
                logger.error("Tried saving an edited message of unsupported type {} for messageId = {}}", target.getType(), target.getId());
                return false;
        }
    }

    @Override
    public boolean saveEmojiReactionMessage(
        @NonNull AbstractMessageModel targetMessage,
        @NonNull String senderIdentity,
        @Nullable Reaction.ActionCase actionCase,
        @NonNull String emojiSequence
    ) {
        logger.debug("saving emoji reaction of type {} to message {}", actionCase, targetMessage.getApiMessageId());

        if (actionCase == Reaction.ActionCase.APPLY) {
            try {
                emojiReactionsRepository.createEntry(targetMessage, senderIdentity, emojiSequence);
            } catch (EmojiReactionEntryCreateException | IllegalStateException e) {
                logger.error("Unable to create emoji reaction.", e);
                return false;
            }
        } else if (actionCase == Reaction.ActionCase.WITHDRAW) {
            try {
                emojiReactionsRepository.removeEntry(targetMessage, senderIdentity, emojiSequence);
            } catch (EmojiReactionEntryRemoveException | IllegalStateException e) {
                logger.error("Unable to remove emoji reaction.", e);
                return false;
            }
        } else {
            logger.warn("Unsupported emoji reaction action case {}. Ignoring message.", actionCase);
            return false;
        }
        fireOnModifiedMessage(targetMessage);
        return true;
    }

    /**
     * F1Whisper (seventh fork review, F7-05): decided from, and written against, the CURRENT row.
     *
     * <p>It used to decide from the caller's instance - the emoji-reaction repository's timeline model - and full-row-save
     * it, so withdrawing a reaction wrote every lifecycle column that instance had captured back over whatever the row
     * had done since it was loaded.</p>
     */
    @Override
    public void clearMessageState(@NonNull AbstractMessageModel targetMessage) {
        final String myIdentity = identityStore != null ? identityStore.getIdentityString() : null;
        for (int attempt = 0; attempt < CONDITIONAL_WRITE_ATTEMPTS; attempt++) {
            final AbstractMessageModel current = reloadPersistedModel(targetMessage);
            if (current == null) {
                logger.info("Not clearing the reaction state of uid={}: its row is gone", targetMessage.getUid());
                return;
            }
            if (isDeletedForEveryone(current)) {
                logger.info("Not clearing the reaction state of uid={}: it was deleted for everyone",
                    targetMessage.getUid());
                return;
            }
            final MessageState priorState = current.getState();
            if (priorState != MessageState.USERACK && priorState != MessageState.USERDEC) {
                return;
            }

            final MessageState newMessageState;
            if (current.isRead()) {
                newMessageState = MessageState.READ;
            } else if (current.getDeliveredAt() != null) {
                newMessageState = MessageState.DELIVERED;
            } else {
                newMessageState = MessageState.SENT;
            }

            final boolean clearsGroupStates = current instanceof GroupMessageModel && myIdentity != null;
            String priorStates = null;
            String mergedStates = null;
            if (clearsGroupStates) {
                priorStates = MessageLifecycleUpdates.serialiseGroupMessageStates(
                    ((GroupMessageModel) current).getGroupMessageStates());
                groupService.removeGroupMessageState((GroupMessageModel) current, myIdentity);
                mergedStates = MessageLifecycleUpdates.serialiseGroupMessageStates(
                    ((GroupMessageModel) current).getGroupMessageStates());
            }

            if (applyRowUpdate(current, MessageLifecycleUpdates.clearedReactionState(
                newMessageState, priorState, clearsGroupStates, mergedStates, priorStates))) {
                targetMessage.setState(newMessageState);
                if (clearsGroupStates) {
                    ((GroupMessageModel) targetMessage).setGroupMessageStates(
                        ((GroupMessageModel) current).getGroupMessageStates());
                }
                return;
            }
            logger.debug("Clearing the reaction state of {} was superseded, re-reading (attempt {})",
                targetMessage.getId(), attempt + 1);
        }
        logger.warn("Gave up clearing the reaction state of uid={} after {} superseded attempts",
            targetMessage.getUid(), CONDITIONAL_WRITE_ATTEMPTS);
    }

    @WorkerThread
    @Override
    public synchronized boolean sendEmojiReaction(
        @NonNull AbstractMessageModel message,
        @NonNull String emojiSequence,
        @NonNull MessageReceiver receiver,
        boolean markAsRead
    ) throws ThreemaException {
        logger.debug("Send emoji reaction to message {} (id={})", message.getApiMessageId(), message.getId());
        logger.trace("Reaction: '{}'", emojiSequence);

        if (!EmojiUtil.isFullyQualifiedEmoji(emojiSequence)) {
            logger.warn("Attempt to send non fully-qualified emoji sequence '{}'", emojiSequence);
            // Return true, as the return value only indicates whether this failed due to
            // compatibility issues when a phase 1 client tries to send an emoji sequence
            // to a client without reactions support.
            return true;
        }

        @MessageReceiver.EmojiReactionsSupport final int reactionSupport = receiver.getEmojiReactionSupport();

        if (markAsRead) {
            markAsRead(
                /* message */ message,
                /* silent */ true
            );
        }

        final String myIdentity = identityStore.getIdentityString();
        List<EmojiReactionData> emojiReactionData =
            emojiReactionsRepository.safeGetReactionsByMessage(message);

        Reaction.ActionCase actionCase = Reaction.ActionCase.APPLY;

        // check if there's already an identical reaction with us as the sender. if yes, withdraw
        // it.
        if (containsEmojiSequence(emojiReactionData, emojiSequence, identityStore.getIdentityString())) {
            actionCase = Reaction.ActionCase.WITHDRAW;
        }

        // If there is a new message state set, it means that a legacy reaction was sent and the
        // state of the message needs to be updated.
        MessageState newMessageState = null;
        if (receiver instanceof ContactMessageReceiver) {
            newMessageState = ((ContactMessageReceiver) receiver).sendReaction(
                message,
                actionCase,
                emojiSequence,
                new Date() // use current timestamp for reaction message
            );
        } else if (receiver instanceof GroupMessageReceiver) {
            ((GroupMessageReceiver) receiver).sendReaction(
                message,
                actionCase,
                emojiSequence,
                new Date() // use current timestamp for reaction message
            );
        } else {
            throw new ThreemaException("Unsupported receiver type of: " + receiver.getClass());
        }

        if (newMessageState == null) {
            // In case the new message state is null, then an emoji reaction has been sent. The
            // sequence can be stored normally.
            if (actionCase == Reaction.ActionCase.APPLY) {
                emojiReactionsRepository.createEntry(message, myIdentity, emojiSequence);
            } else {
                emojiReactionsRepository.removeEntry(message, myIdentity, emojiSequence);
            }
        } else {
            // In case there is a new message state, then a legacy reaction has been used. In this
            // case we need to update the message state.
            updateAckDecState(message, newMessageState, null);
        }

        showToastOnPartialReactionSupport(
            reactionSupport,
            actionCase,
            emojiSequence
        );

        if (actionCase == Reaction.ActionCase.APPLY) {
            // F1Whisper: remember this reaction so the reaction picker can surface the user's
            // most-recently-used reactions first instead of a fixed list.
            rememberRecentEmojiReaction(emojiSequence);
        }

        fireOnModifiedMessage(message);
        return true;
    }

    /**
     * F1Whisper: push an applied reaction emoji to the front of the recent-reactions list
     * (de-duplicated, most-recent first, capped). Best-effort; never throws into the send path.
     */
    private void rememberRecentEmojiReaction(@NonNull String emojiSequence) {
        try {
            final int maxRecentReactions = 16;
            java.util.LinkedList<String> recents = preferenceService.getRecentEmojiReactions();
            recents.remove(emojiSequence);
            recents.addFirst(emojiSequence);
            while (recents.size() > maxRecentReactions) {
                recents.removeLast();
            }
            preferenceService.setRecentEmojiReactions(recents);
        } catch (Exception e) {
            logger.warn("Could not store recent emoji reaction", e);
        }
    }

    /**
     * Show a toast if reaction support is only partial for the current receiver.
     * Also note that for now a toast is only shown if the action is APPLY.
     * If the reaction can be mapped to ACK/DEC, no toast is shown.
     */
    @AnyThread
    private void showToastOnPartialReactionSupport(
        @MessageReceiver.EmojiReactionsSupport int reactionSupport,
        @NonNull Reaction.ActionCase actionCase,
        @NonNull String emojiSequence
    ) {
        if (reactionSupport == MessageReceiver.Reactions_PARTIAL
            && actionCase == Reaction.ActionCase.APPLY
            && !EmojiUtil.isThumbsUpOrDownEmoji(emojiSequence)) {
            RuntimeUtil.runOnUiThread(() ->
                Toast.makeText(context, R.string.group_emoji_reactions_partially_supported, Toast.LENGTH_SHORT).show());
        }
    }

    private boolean containsEmojiSequence(@Nullable List<EmojiReactionData> emojiReactionData, @NonNull String emojiSequence, @NonNull String senderIdentity) {
        return
            emojiReactionData != null &&
                emojiReactionData.stream().anyMatch(a -> a.senderIdentity.equals(senderIdentity) && a.emojiSequence.equals(emojiSequence));
    }

    @Override
    public void sendDeleteMessage(
        @NonNull AbstractMessageModel message, // Let `message` be the referred message.
        @NonNull MessageReceiver receiver
    ) throws Exception {
        logger.debug("sendDeleteMessage        message = {}", message.getApiMessageId());

        if (!message.isOutbox()) {
            logger.error("Tried deleting a message that is not outgoing. message = {}", message.getId());
        }

        if (message.getPostedAt() == null) {
            logger.error("postedAt is null for messageId={}}", message.getId());
            return;
        }

        // Let `created-at` be the current timestamp to be applied to the delete message
        Date createdAt = new Date();

        final @Nullable GroupModel groupModel;
        if (receiver instanceof GroupMessageReceiver) {
            groupModel = ((GroupMessageReceiver) receiver).getGroupModel();
        } else {
            groupModel = null;
        }
        final boolean isNotesGroup = groupModel != null && Boolean.TRUE.equals(groupModel.isNotesGroup());

        // If the group is not a notes group, we have to check the messages age
        if (!isNotesGroup) {
            long deltaTime = createdAt.getTime() - message.getPostedAt().getTime();
            // If the referred message has been sent (`sent-at`) more than 6 hours ago, prevent creation and abort these steps.
            if (deltaTime > DeleteMessage.DELETE_MESSAGES_MAX_AGE) {
                logger.error("Cannot delete message older than {}}ms", DeleteMessage.DELETE_MESSAGES_MAX_AGE);
                return;
            }
        }

        // Replace `message` with a message informing the user that the message of the user has been removed at `created-at`.
        // F1Whisper (seventh fork review, F7-01 / F7-02): this write CREATES the soft-deleted tombstone the delete
        // control task loads and transmits from. If it did not win the row there is no tombstone, and scheduling would
        // be exactly the load-a-vanished-model-from-the-cache transmission the review closes.
        if (!deleteMessageContentsAndRelatedData(message, createdAt)) {
            logger.info("Not announcing the deletion of {}: its row is gone or was already deleted",
                message.getApiMessageId());
            return;
        }

        if (receiver instanceof ContactMessageReceiver) {
            ((ContactMessageReceiver) receiver).sendDeleteMessage(
                message.getId(),
                createdAt
            );
        } else if (receiver instanceof GroupMessageReceiver) {
            ((GroupMessageReceiver) receiver).sendDeleteMessage(
                message.getId(),
                createdAt
            );
        } else {
            throw new ThreemaException("Unsupported receiver type of: " + receiver.getClass());
        }
    }

    /**
     * F1Whisper (seventh fork review, F7-02): claim the row FIRST, then remove what it governs.
     *
     * <p>The defect this closes: this used to delete the message's files and only afterwards write the emptied row. In
     * between, the row still existed and still had a null {@code deletedAt}, so a media download finishing in that
     * window wrote its file, won its conditional completion write against a row that looked perfectly current, and
     * published - listener, blob-complete, and with "save to gallery" enabled a permanent clear copy in the device
     * gallery. Deletion then emptied the row and never looked at the disk again. The message was gone and its media was
     * not.</p>
     *
     * <p>Now the deletion mark is a single conditional statement taken under the same per-type monitor every lifecycle
     * write takes, and it is what authorises everything after it. A completion racing it either wins the row first (and
     * this cleanup, which runs afterwards, removes what it wrote) or loses it (and cleans up its own write, publishing
     * nothing) - in both orders, nothing survives.</p>
     *
     * @return whether this caller won the row. {@code false} means it had already gone or had already been deleted for
     * everyone, in which case that owner did, or is doing, the cleanup.
     */
    @Override
    public boolean deleteMessageContentsAndRelatedData(@NonNull AbstractMessageModel message, Date deletedAt) {
        logger.info("deleteMessageContents = {}", message.getApiMessageId());

        if (!applyRowUpdate(message, MessageLifecycleUpdates.deletedForEveryone(
            deletedAt, message instanceof GroupMessageModel))) {
            logger.info("Not deleting the contents of {} for everyone: its row is gone or already deleted",
                message.getApiMessageId());
            return false;
        }

        message.setBody(null);
        message.setCaption(null);
        message.setState(null);
        if (message instanceof GroupMessageModel) {
            ((GroupMessageModel) message).setGroupMessageStates(null);
        }
        message.setDeletedAt(deletedAt);

        // Owned from here on: no concurrent download completion can win this row any more.
        //
        // F1Whisper (eighth fork review, H8-01): and no outgoing UPLOAD carries on either. The UI offers
        // delete-for-everyone for a PENDING, UPLOADING or SENDING media message, and an upload runs for seconds or
        // minutes, so "delete the photo I just picked while the progress bar is still moving" is an ordinary action
        // rather than a race. This path used to cancel only the incoming download, so the outgoing send machine kept
        // running and its handoff wrote the finished blob id and encryption key back into the row it had just emptied.
        // Hard deletion has always aborted the send here; deleting for everyone does the same now.
        abortPendingSend(message);
        cancelMessageDownload(message);
        fileService.removeMessageFiles(message, true);

        // Delete the edit history and emoji reactions. Note that the foreign keys do not work in this case, as the
        // original message entry is not removed from the database.
        editHistoryRepository.deleteByMessageUid(message.getUid());

        emojiReactionsRepository.deleteAllReactionsForMessage(message);

        fireOnModifiedMessage(message);
        fireOnMessageDeletedForAll(message);
        return true;
    }

    @Override
    public AbstractMessageModel sendLocation(@NonNull Location location, @Nullable String poiName, MessageReceiver receiver, final CompletionHandler completionHandler) throws ThreemaException {
        final String tag = "sendLocationMessage";
        logger.info("{}: start", tag);

        AbstractMessageModel messageModel = receiver.createLocalModel(MessageType.LOCATION, MessageContentsType.LOCATION, TrustedClock.now()); // F1Whisper: server-corrected outgoing postedAt
        cache(messageModel);

        @Nullable Poi poi = null;
        try {
            final @NonNull String lookedUpPoiAddress = GeoLocationUtil.getAddressFromLocation(
                context,
                location.getLatitude(),
                location.getLongitude()
            );
            if (poiName != null && !poiName.isBlank()) {
                poi = new Poi.Named(poiName, lookedUpPoiAddress);
            } else {
                poi = new Poi.Unnamed(lookedUpPoiAddress);
            }
        } catch (IOException e) {
            logger.error("Exception", e);
            //do not show this error!
        }

        messageModel.setLocationData(
            new LocationDataModel(
                location.getLatitude(),
                location.getLongitude(),
                (double) location.getAccuracy(),
                poi
            )
        );

        messageModel.setOutbox(true);
        messageModel.setState(MessageState.PENDING);
        messageModel.setSaved(true);
        receiver.saveLocalModel(messageModel);

        fireOnCreatedMessage(messageModel);

        receiver.createAndSendLocationMessage(messageModel);

        fireOnModifiedMessage(messageModel);

        if (completionHandler != null)
            completionHandler.sendQueued(messageModel);

        return messageModel;
    }

    @Override
    @WorkerThread
    public void resendMessage(
        @NonNull AbstractMessageModel messageModel,
        @NonNull MessageReceiver<AbstractMessageModel> receiver,
        @Nullable CompletionHandler completionHandler,
        @NonNull Collection<String> recipientIdentities,
        @NonNull MessageId messageId,
        @NonNull TriggerSource triggerSource
    ) throws Exception {
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        notificationManager.cancel(NotificationIDs.UNSENT_MESSAGE_NOTIFICATION_ID);

        // Manual (user-tapped) resend: only from a failed state, exactly as before.
        if (messageModel.getState() == MessageState.SENDFAILED || messageModel.getState() == MessageState.FS_KEY_MISMATCH) {
            dispatchResend(messageModel, receiver, completionHandler, recipientIdentities, messageId, triggerSource);
        }
    }

    /**
     * F1Whisper auto-resend: silently re-send a single auto-eligible outgoing message once
     * connectivity has returned. Unlike the manual {@link #resendMessage} path this:
     *
     *  - accepts only a FILE message whose blob-upload phase never completed (state PENDING or
     *    UPLOADING after a process death, or a connectivity-class SENDFAILED), never a terminal
     *    SENDFAILED and never a SENDING message (whose persistent task owns delivery) - see
     *    {@link AutoResendService#isAutoResendEligible}, the double-send guard;
     *  - REUSES the message's ORIGINAL apiMessageId end-to-end (never mints a random one), so if the
     *    earlier attempt actually reached the server the receiver dedupes the redelivery. The file
     *    resend already reuses the persisted id; the id passed to {@code dispatchResend} only governs
     *    the (unreached, since we are FILE-only) ballot path, so we pass the model's own id.
     *
     * The manual tap-to-resend path is left completely unchanged.
     */
    /**
     * F1Whisper auto-resend: re-read a scan snapshot fresh from the database (bypassing the model
     * cache via getByUid) so an auto-resend eligibility re-check sees the CURRENT persisted state, not
     * the possibly-stale snapshot captured when the scan queried. Returns null if the row no longer
     * exists (e.g. the user deleted the message) or the type is not auto-resendable.
     */
    @WorkerThread
    @Nullable
    private AbstractMessageModel reloadMessageModelForAutoResend(@NonNull AbstractMessageModel scanSnapshot) {
        final String uid = scanSnapshot.getUid();
        if (uid == null) {
            return null;
        }
        if (scanSnapshot instanceof GroupMessageModel) {
            return getGroupMessageModel(uid);
        } else if (scanSnapshot instanceof MessageModel) {
            return getContactMessageModel(uid);
        }
        // Distribution-list / other types are never auto-resent; let the caller's eligibility check
        // reject the snapshot itself rather than reloading a type we do not handle here.
        return scanSnapshot;
    }

    @Override
    @WorkerThread
    public void autoResendMessage(
        @NonNull AbstractMessageModel scanSnapshot,
        @NonNull TriggerSource triggerSource
    ) throws Exception {
        // The scan iterates DETACHED snapshots captured at query time; the user may have
        // deleted/cancelled the message between the query and now (remove() drops the row,
        // deleteMessageContentsAndRelatedData sets deletedAt + state=null, cancelMessageUpload marks
        // it terminal SENDFAILED). Re-read the model FRESH from the DB (getByUid bypasses the cache)
        // and re-verify eligibility against that fresh state so we never re-encrypt/re-upload content
        // the user believes gone, independently of the file-presence check inside resendFileMessage.
        AbstractMessageModel messageModel = reloadMessageModelForAutoResend(scanSnapshot);
        if (messageModel == null) {
            logger.debug("Auto-resend skipped (message gone) for {}", scanSnapshot.getUid());
            return;
        }

        // Single source of truth for eligibility (FILE-only, unsent blob-phase state, non-terminal,
        // never SENDING) - the critical double-send guard. See AutoResendService#isAutoResendEligible.
        if (!AutoResendService.isAutoResendEligible(messageModel)) {
            logger.debug("Auto-resend skipped (ineligible) for message {} type {} state {}",
                messageModel.getUid(), messageModel.getType(), messageModel.getState());
            return;
        }

        // Distribution-list messages cannot be resent (mirrors the manual path).
        if (messageModel instanceof DistributionListMessageModel) {
            logger.debug("Auto-resend skipped for distribution-list message {}", messageModel.getUid());
            return;
        }

        MessageState state = messageModel.getState();
        MessageReceiver<AbstractMessageModel> receiver = getMessageReceiver(messageModel);
        if (receiver == null) {
            logger.warn("Auto-resend skipped: no receiver for message {}", messageModel.getUid());
            return;
        }

        Collection<String> recipientIdentities;
        if (messageModel instanceof GroupMessageModel) {
            GroupModelOld groupModel = groupService.getById(((GroupMessageModel) messageModel).getGroupId());
            if (groupModel == null || !groupService.isGroupMember(groupModel)) {
                logger.debug("Auto-resend skipped: group gone / not a member for {}", messageModel.getUid());
                return;
            }
            recipientIdentities = groupService.getMembersWithoutUser(groupModel);
        } else {
            recipientIdentities = Collections.singleton(messageModel.getIdentity());
        }

        // Reuse the ORIGINAL message id. If it is somehow missing the message was never sent once,
        // so there is nothing to dedupe against; the type-specific resend re-derives/mints as usual.
        MessageId originalMessageId = messageModel.getMessageId();
        MessageId messageId = originalMessageId != null ? originalMessageId : MessageId.random();

        logger.info("Auto-resending message {} (state {})", messageModel.getUid(), state);
        dispatchResend(messageModel, receiver, null, recipientIdentities, messageId, triggerSource);
    }

    @Override
    @WorkerThread
    public void markAgedOutUnsentFailed(@NonNull AbstractMessageModel messageModel) {
        // Only give up on a message that is still genuinely eligible (FILE, unsent blob-phase,
        // non-terminal); it may have been resent or manually retried between the scan query and now.
        if (!AutoResendService.isAutoResendEligible(messageModel)) {
            return;
        }
        logger.info("Message {} exhausted the auto-resend window; marking terminally failed", messageModel.getUid());
        // updateOutgoingMessageState sets the terminal marker centrally and fires onModified, which
        // drives the unsent-message notification in HomeActivity.
        markTerminalSendFailed(messageModel);
    }

    /**
     * Route a (manual or auto) resend to the type-specific resend method. Shared by
     * {@link #resendMessage} and {@link #autoResendMessage}. The {@code messageId} governs only the
     * ballot path (the other types reuse the model's persisted apiMessageId).
     */
    @WorkerThread
    private void dispatchResend(
        @NonNull AbstractMessageModel messageModel,
        @NonNull MessageReceiver<AbstractMessageModel> receiver,
        @Nullable CompletionHandler completionHandler,
        @NonNull Collection<String> recipientIdentities,
        @NonNull MessageId messageId,
        @NonNull TriggerSource triggerSource
    ) throws Exception {
        if (messageModel.getType() == MessageType.FILE) {
            resendFileMessage(messageModel, receiver, completionHandler, recipientIdentities);
        } else if (messageModel.getType() == MessageType.BALLOT) {
            BallotModel ballotModel = ballotService.get(messageModel.getBallotData().getBallotId());
            if (ballotModel != null) {
                resendBallotMessage(messageModel, ballotModel, receiver, messageId, triggerSource);
            }
        } else if (messageModel.getType() == MessageType.TEXT) {
            resendTextMessage(messageModel, receiver, recipientIdentities);
        } else if (messageModel.getType() == MessageType.LOCATION) {
            resendLocationMessage(messageModel, receiver, completionHandler, recipientIdentities);
        }
    }

    @WorkerThread
    private void resendTextMessage(
        final @NonNull AbstractMessageModel messageModel,
        final MessageReceiver receiver,
        final Collection<String> recipientIdentities
    ) {
        if (receiver instanceof ContactMessageReceiver && messageModel instanceof MessageModel) {
            ((ContactMessageReceiver) receiver).resendTextMessage((MessageModel) messageModel);
        } else if (receiver instanceof GroupMessageReceiver && messageModel instanceof GroupMessageModel) {
            ((GroupMessageReceiver) receiver).resendTextMessage(
                (GroupMessageModel) messageModel,
                recipientIdentities
            );
        } else if (receiver instanceof DistributionListMessageReceiver) {
            logger.warn("Cannot resend messages in a distribution list");
            return;
        } else {
            logger.warn("Incompatible message receiver and message model type");
            return;
        }

        updateOutgoingMessageState(messageModel, MessageState.SENDING, new Date());
        fireOnModifiedMessage(messageModel);
    }

    @WorkerThread
    private void resendLocationMessage(
        @NonNull AbstractMessageModel messageModel,
        @NonNull MessageReceiver receiver,
        final @Nullable CompletionHandler completionHandler,
        @NonNull Collection<String> recipientIdentities
    ) {
        if (receiver instanceof ContactMessageReceiver && messageModel instanceof MessageModel) {
            ((ContactMessageReceiver) receiver).resendLocationMessage((MessageModel) messageModel);
        } else if (receiver instanceof GroupMessageReceiver && messageModel instanceof GroupMessageModel) {
            ((GroupMessageReceiver) receiver).resendLocationMessage(
                (GroupMessageModel) messageModel,
                recipientIdentities
            );
        } else if (receiver instanceof DistributionListMessageReceiver) {
            logger.warn("Cannot resend messages in a distribution list");
            return;
        } else {
            logger.error("Incompatible message receiver and message model type");
            return;
        }

        updateOutgoingMessageState(messageModel, MessageState.SENDING, new Date());
        fireOnModifiedMessage(messageModel);
        if (completionHandler != null) {
            completionHandler.sendQueued(messageModel);
        }
    }

    @WorkerThread
    private void resendFileMessage(
        final @NonNull AbstractMessageModel messageModel,
        final @NonNull MessageReceiver<AbstractMessageModel> receiver,
        final @Nullable CompletionHandler completionHandler,
        final @NonNull Collection<String> recipientIdentities
    ) throws Exception {

        // check if a message file exists that could be resent or abort immediately
        var messageUid = messageModel.getUid();
        if (messageUid == null || !fileService.hasMessageFile(messageUid)) {
            throw new ThreemaException("Message file not present");
        }

        updateOutgoingMessageState(messageModel, MessageState.PENDING, new Date());

        //enqueue processing and uploading stuff...
        messageSendingService.addToQueue(new MessageSendingService.MessageSendingProcess() {
            public byte[] blobIdThumbnail;
            public byte[] blobId;
            public byte[] thumbnailData;
            public byte[] fileData;
            public int fileDataBoxedLength;

            private SymmetricEncryptionResult contentEncryptResult;
            private SymmetricEncryptionResult thumbnailEncryptResult;

            public boolean success = false;

            @Override
            public MessageReceiver<AbstractMessageModel> getReceiver() {
                return receiver;
            }

            @Override
            public AbstractMessageModel getMessageModel() {
                return messageModel;
            }

            @Override
            public boolean send() throws Exception {
                // F1Whisper (ninth follow-up review, F9-01): the row decides whether this resend may still act. It has
                // been waiting behind another upload for as long as that upload took, and the user could delete it
                // throughout; nothing below asks again until the handoff, which is after both blobs are on the server.
                if (!mayStillSend(messageModel)) {
                    return false;
                }
                SendMachine sendMachine = getSendMachine(messageModel);
                sendMachine.reset()
                    .next(() -> {
                        // get file data
                        File decryptedMessageFile = fileService.getDecryptedMessageFile(messageModel);

                        if (decryptedMessageFile != null) {
                            try (FileInputStream inputStream = new FileInputStream(decryptedMessageFile)) {
                                fileDataBoxedLength = inputStream.available();
                                fileData = new byte[fileDataBoxedLength + NaCl.BOX_OVERHEAD_BYTES];
                                var readBytes = copyTo(inputStream, fileData, NaCl.BOX_OVERHEAD_BYTES, fileDataBoxedLength);
                                if (readBytes != fileDataBoxedLength) {
                                    throw new EOFException("Expected " + fileDataBoxedLength + " but got only " + readBytes + " bytes");
                                }
                            }
                        } else {
                            throw new ThreemaException("Message file not present");
                        }
                    })
                    .next(() -> {
                        // encrypt file data
                        contentEncryptResult = symmetricEncryptionService.encryptInplace(fileData, ProtocolDefines.FILE_NONCE);
                        if (contentEncryptResult.isEmpty()) {
                            throw new ThreemaException("File data encrypt failed");
                        }
                    })
                    .next(() -> {
                        // get thumbnail data
                        try (InputStream is = fileService.getDecryptedMessageThumbnailStream(messageModel)) {
                            if (is != null) {
                                thumbnailData = readBytes(is);
                            } else {
                                thumbnailData = null;
                            }
                        } catch (Exception e) {
                            logger.debug("No thumbnail for file message");
                        }
                    })
                    .next(() -> {
                        // upload (encrypted) file data
                        BlobUploader blobUploader = initUploader(
                            getMessageModel(),
                            contentEncryptResult.getData(),
                            getReceiver()
                        );
                        blobUploader.progressListener = new ProgressListener() {
                            @Override
                            public void updateProgress(int progress) {
                                updateMessageLoadingProgress(messageModel, progress);
                            }

                            @Override
                            public void onFinished(boolean success) {
                                setMessageLoadingFinished(messageModel);
                            }
                        };
                        blobId = blobUploader.upload();
                    })
                    .next(() -> {
                        if (thumbnailData != null) {
                            // encrypt and upload thumbnail data
                            thumbnailEncryptResult = symmetricEncryptionService.encrypt(thumbnailData, contentEncryptResult.getKey(), ProtocolDefines.FILE_THUMBNAIL_NONCE);

                            if (thumbnailEncryptResult.isEmpty()) {
                                throw new ThreemaException("Thumbnail encryption failed");
                            } else {
                                BlobUploader blobUploader = initUploader(
                                    getMessageModel(),
                                    thumbnailEncryptResult.getData(),
                                    getReceiver()
                                );
                                blobUploader.progressListener = new ProgressListener() {
                                    @Override
                                    public void updateProgress(int progress) {
                                        updateMessageLoadingProgress(messageModel, progress);
                                    }

                                    @Override
                                    public void onFinished(boolean success) {
                                        setMessageLoadingFinished(messageModel);
                                    }
                                };
                                blobIdThumbnail = blobUploader.upload();
                            }
                        }
                    })
                    .next(() -> {
                        if (!getReceiver().createAndSendFileMessage(
                            blobIdThumbnail,
                            blobId,
                            contentEncryptResult,
                            messageModel,
                            recipientIdentities
                        )) {
                            // F1Whisper (eighth fork review, H8-01): the row refused the handoff, so this resend has
                            // nothing left to announce. Aborting makes every remaining step a no-op, which is what
                            // "publish nothing" has to mean: no SENDING state, no listener, no completion handler.
                            // Deliberately not an exception - the sending service reacts to those by retrying and
                            // finally marking SENDFAILED, which would be a failure notice for a message the user
                            // deleted on purpose.
                            logger.info("Resend handoff refused for {}: its row is gone or was deleted",
                                messageModel.getId());
                            sendMachine.abort();
                            return;
                        }
                        // F1Whisper (seventh fork review, F7-05): NO save here. createAndSendFileMessage persisted the
                        // enriched file data before it scheduled the task; this second full-row save added nothing but
                        // a post-schedule writer of every lifecycle column. See sendText for the failure.
                    })
                    .next(() -> {
                        updateOutgoingMessageState(messageModel, MessageState.SENDING, new Date());

                        if (completionHandler != null)
                            completionHandler.sendComplete(messageModel);

                        success = true;
                    });

                if (success) {
                    removeSendMachine(sendMachine);
                }
                return success;
            }
        });
    }

    @Override
    public AbstractMessageModel sendBallotMessage(
        @NonNull BallotModel ballotModel,
        @NonNull MessageId messageId,
        @NonNull TriggerSource triggerSource
    ) throws MessageTooLongException {
        //create a new ballot model
        MessageReceiver receiver = ballotService.getReceiver(ballotModel);

        if (receiver != null) {
            //ok...
            logger.debug("sendBallotMessage to {}", receiver);
            final AbstractMessageModel messageModel = receiver.createLocalModel(MessageType.BALLOT, MessageContentsType.BALLOT, TrustedClock.now()); // F1Whisper: server-corrected outgoing postedAt
            cache(messageModel);

            messageModel.setOutbox(true);
            messageModel.setState(MessageState.PENDING);

            messageModel.setBallotData(new BallotDataModel(
                ballotModel.getState() == BallotModel.State.OPEN ?
                    BallotDataModel.Type.BALLOT_CREATED :
                    BallotDataModel.Type.BALLOT_CLOSED,
                ballotModel.getId()));

            messageModel.setSaved(true);
            receiver.saveLocalModel(messageModel);
            fireOnCreatedMessage(messageModel);
            resendBallotMessage(messageModel, ballotModel, receiver, messageId, triggerSource);

            return messageModel;
        }

        return null;
    }

    private void resendBallotMessage(
        AbstractMessageModel messageModel,
        BallotModel ballotModel,
        MessageReceiver<?> receiver,
        @NonNull MessageId messageId,
        @NonNull TriggerSource triggerSource
    ) throws MessageTooLongException {
        //get ballot data
        if (messageModel == null || ballotModel == null || receiver == null) {
            return;
        }
        updateOutgoingMessageState(messageModel, MessageState.PENDING, new Date());
        try {
            ballotService.publish(receiver, ballotModel, messageModel, messageId, triggerSource);
        } catch (NotAllowedException | MessageTooLongException x) {
            logger.error("Exception", x);
            if (x instanceof MessageTooLongException) {
                remove(messageModel);
                fireOnRemovedMessage(messageModel);
                throw new MessageTooLongException();
            } else {
                updateOutgoingMessageState(messageModel, MessageState.SENDFAILED, new Date());
            }
        }
    }

    @Nullable
    @Override
    public MessageModel getContactMessageModel(
        @NonNull final MessageId apiMessageId,
        @NonNull final String identity
    ) {
        // Check contact message cache first
        synchronized (contactMessageCache) {
            MessageModel messageModel = contactMessageCache.stream()
                .filter(model ->
                    model.getApiMessageId() != null
                    && model.getApiMessageId().equals(apiMessageId.toString())
                    && TestUtil.compare(model.getIdentity(), identity)
                )
                .findFirst()
                .orElse(null);
            if (messageModel != null) {
                return messageModel;
            }
        }

        // If not cached, load from database (and cache it)
        MessageModel contactMessageModel = databaseService.getMessageModelFactory().getByApiMessageIdAndIdentity(
            apiMessageId,
            identity);
        if (contactMessageModel != null) {
            cache(contactMessageModel);
        }

        return contactMessageModel;
    }

    /**
     * Get the AbstractMessageModel of a group message referenced by messageId, creatorId, and groupId
     *
     * @param messageId       the message
     * @param creatorIdentity the creator of the group
     * @param groupId         the group id
     * @return a GroupMessageModel of the matching message or null in case a message could not be found
     */
    @Override
    @Nullable
    public GroupMessageModel getGroupMessageModel(
        @NonNull final MessageId messageId,
        @NonNull final String creatorIdentity,
        @NonNull final GroupId groupId
    ) {
        String apiMessageIdString = messageId.toString();
        if (apiMessageIdString == null) {
            return null;
        }

        GroupModelOld groupModel = groupService.getByApiGroupIdAndCreator(groupId, creatorIdentity);
        if (groupModel == null) {
            return null;
        }

        // check group message cache first
        synchronized (groupMessageCache) {
            GroupMessageModel messageModel = groupMessageCache.stream()
                .filter(model -> (apiMessageIdString.equals(model.getApiMessageId()) && groupModel.getId() == model.getGroupId()))
                .findFirst()
                .orElse(null);
            if (messageModel != null) {
                return messageModel;
            }
        }

        // retrieve from database
        GroupMessageModel groupMessageModel = databaseService.getGroupMessageModelFactory().getByApiMessageIdAndGroupId(
            messageId,
            groupModel.getId());

        if (groupMessageModel != null) {
            cache(groupMessageModel);
            return groupMessageModel;
        }

        return null;
    }

    @Override
    public void updateOutgoingMessageState(
        @NonNull AbstractMessageModel messageModel,
        @NonNull MessageState state,
        @NonNull Date date
    ) {
        if (!messageModel.isOutbox()) {
            throw new IllegalArgumentException("Updating outgoing message state on incoming message " + messageModel.getApiMessageId());
        }

        if (MessageUtil.isReaction(state)) {
            throw new IllegalArgumentException("The given message state is a reaction: " + state);
        }

        if (messageModel.isDeleted()) {
            return;
        }

        final boolean applied;
        synchronized (this) {
            applied = applyOutgoingStateTransition(messageModel, state, date, null, false);
            if (applied) {
                fireOnModifiedMessage(messageModel);
            }
        }

        // F1Whisper: once an outgoing "listen once" voice message has actually been sent (its blob is
        // now on the server, ready for the recipient to fetch once), burn the sender's own copy too,
        // so the sender can never replay it either (Telegram/WhatsApp view-once behaviour).
        if (OutgoingClockDecision.hasLeftTheDevice(messageModel.getState())) {
            burnOutgoingListenOnceIfNeeded(messageModel);
        }
    }

    /**
     * F1Whisper (fifth fork review, F5-06): the ONE clock-aware, durable outgoing transition. Every writer of a
     * successful outgoing terminal state goes through it.
     *
     * <p><b>What it replaces.</b> Terminal state and countdown used to be TWO writes: the state (and its timestamp) went
     * to disk, the synchronized block was left, and only then did the clock arming read the wall clock and save again. A
     * process death in between left a terminal row with no deadline, and the startup repair pass deliberately refuses
     * outgoing rows with no start - so the message stayed forever unless another receipt happened to arrive. Both writes
     * were also full-row upserts from a detached model, so either could recreate a message deleted in between.</p>
     *
     * <p><b>What it fixes about the clock itself.</b> The start is derived from {@code transitionAt}, the timestamp of
     * the transition being recorded, not from "now". A {@code SENT} update reflected from another device carries an
     * authoritative send time that the old arming discarded, so a reflection delayed by minutes extended the message's
     * life by those minutes. See {@link OutgoingClockDecision#resolveStart}, which also encodes that a receipt may start
     * the clock provisionally, an authoritative send time may move it earlier, and nothing may move it later.</p>
     *
     * <p>State, state timestamp, modified timestamp, the terminal-failure display bit, start and deadline are ONE
     * conditional update-only write. It requires the row to exist and not be deleted for everyone, so it can neither
     * insert nor contradict a deletion.</p>
     *
     * <p>F1Whisper (sixth fork review, F6-03): what to write is decided by {@link OutgoingTransitionPlanner}, which is
     * reachable from a JVM test; what is left here is the reload, the write, the retry and the mirror. The defect that
     * forced the split lived in this method and not in the decision it called: the clock was asked about the state the
     * row ended up with rather than the state being processed, so an authoritative {@code SENT} arriving behind a
     * receipt could never shorten the countdown that receipt had provisionally started.</p>
     *
     * @param forwardSecurityMode  written in the same transition when the caller has it; {@code null} leaves the column alone.
     * @param bypassStateGate      set by the two callers that deliberately record a state {@code canChangeToState} would
     *                             refuse: the group completion, which must stamp {@code postedAt} even when the outcome is
     *                             {@code FS_KEY_MISMATCH}, and the task-layer terminal failure, which must set the
     *                             non-retryable marker for a message in any state at all.
     * @return whether anything was written.
     */
    @Override
    public boolean applyOutgoingStateTransition(
        @NonNull AbstractMessageModel messageModel,
        @NonNull MessageState state,
        @NonNull Date transitionAt,
        @Nullable ForwardSecurityMode forwardSecurityMode,
        boolean bypassStateGate
    ) {
        logger.debug(
            "Updating message state from {} to {} at {}",
            messageModel.getState(), state, transitionAt.getTime()
        );

        for (int attempt = 0; attempt < CONDITIONAL_WRITE_ATTEMPTS; attempt++) {
            // Decide from the CURRENT row, not from the caller's instance. Callbacks in the send pipeline hold a model
            // captured when their task started, which can be minutes old by the time an ack returns; deciding the clock
            // from that instance's null start is how a late DELIVERED receipt could move a start that a SENT transition
            // had already established, quietly extending the retention window the sender committed to.
            final AbstractMessageModel current = reloadPersistedModel(messageModel);
            if (current == null) {
                logger.info("Outgoing transition to {} for uid={} wrote nothing; the row is gone",
                    state, messageModel.getUid());
                return false;
            }
            if (isDeletedForEveryone(current)) {
                logger.info("Outgoing transition to {} for uid={} wrote nothing; it was deleted for everyone",
                    state, messageModel.getUid());
                return false;
            }

            final MessageRowUpdate built = OutgoingTransitionPlanner.plan(
                current, state, transitionAt, forwardSecurityMode, bypassStateGate);
            if (built == null) {
                return false;
            }
            final boolean startsCountdown = built.getAssignments().containsKey(AbstractMessageModel.COLUMN_EXPIRES_AT);

            if (applyRowUpdate(current, built)) {
                mirrorOutgoingTransition(messageModel, current);
                if (startsCountdown) {
                    try {
                        DisappearingMessageService.getInstance().rescheduleNextAlarm(ch.threema.app.ThreemaApplication.getAppContext());
                    } catch (Exception e) {
                        logger.warn("Could not rearm the disappearing alarm after an outgoing transition", e);
                    }
                }
                return true;
            }
            if (built.getConditions().isEmpty()) {
                // Unconditional, so a false answer means the row is gone or deleted for everyone - retrying cannot help.
                logger.info("Outgoing transition to {} for uid={} wrote nothing; the row is gone or deleted",
                    state, messageModel.getUid());
                return false;
            }
            logger.debug("Outgoing transition for {} was superseded, re-reading (attempt {})", messageModel.getId(), attempt + 1);
        }
        logger.warn("Gave up recording the outgoing transition to {} for uid={} after {} superseded attempts",
            state, messageModel.getUid(), CONDITIONAL_WRITE_ATTEMPTS);
        return false;
    }

    /** Make the caller's instance agree with the row the transition actually wrote. */
    private void mirrorOutgoingTransition(@NonNull AbstractMessageModel target, @NonNull AbstractMessageModel written) {
        if (target == written) {
            return;
        }
        target.setState(written.getState());
        target.setPostedAt(written.getPostedAt());
        target.setDeliveredAt(written.getDeliveredAt());
        target.setReadAt(written.getReadAt());
        target.setModifiedAt(written.getModifiedAt());
        target.setDisplayTags(written.getDisplayTags());
        target.setForwardSecurityMode(written.getForwardSecurityMode());
        target.setExpireStartedAt(written.getExpireStartedAt());
        target.setExpiresAt(written.getExpiresAt());
    }

    @Override
    public boolean updateForwardSecurityMode(@NonNull AbstractMessageModel messageModel, @NonNull ForwardSecurityMode mode) {
        // F1Whisper (fifth fork review, F5-06): the forward-security state arrives in a callback AFTER the terminal
        // transition, and used to be persisted by full-row-saving the detached model that callback had captured. That
        // could recreate a row deleted in between, and it wrote back the whole of a superseded snapshot - undoing a
        // higher state or an earlier clock that the terminal transition had just established. It is one column.
        messageModel.setForwardSecurityMode(mode);
        return applyRowUpdate(messageModel, MessageRowUpdate.builder()
            .set(AbstractMessageModel.COLUMN_FORWARD_SECURITY_MODE, mode.getValue())
            .build());
    }

    @Override
    public boolean toggleDisplayTag(@NonNull AbstractMessageModel messageModel, int tag) {
        return writeDisplayTag(messageModel, tag, true);
    }

    @Override
    public boolean clearDisplayTag(@NonNull AbstractMessageModel messageModel, int tag) {
        return writeDisplayTag(messageModel, tag, false);
    }

    /**
     * F1Whisper (sixth fork review, F6-01): the reload-recompute-compare-and-set loop behind
     * {@link #toggleDisplayTag} and {@link #clearDisplayTag}.
     */
    private boolean writeDisplayTag(@NonNull AbstractMessageModel messageModel, int tag, boolean toggle) {
        for (int attempt = 0; attempt < CONDITIONAL_WRITE_ATTEMPTS; attempt++) {
            final AbstractMessageModel current = reloadPersistedModel(messageModel);
            if (current == null) {
                logger.info("Not changing display tags of uid={}: its row is gone", messageModel.getUid());
                return false;
            }
            if (isDeletedForEveryone(current)) {
                logger.info("Not changing display tags of uid={}: it was deleted for everyone", messageModel.getUid());
                return false;
            }
            final int priorTags = current.getDisplayTags();
            final int newTags = toggle ? (priorTags ^ tag) : (priorTags & ~tag);
            if (newTags == priorTags) {
                return false;
            }
            if (applyRowUpdate(current, MessageLifecycleUpdates.displayTags(newTags, priorTags))) {
                messageModel.setDisplayTags(newTags);
                return true;
            }
            logger.debug("Display tags of {} moved under us, re-reading (attempt {})", messageModel.getId(), attempt + 1);
        }
        logger.warn("Gave up changing the display tags of uid={} after {} superseded attempts",
            messageModel.getUid(), CONDITIONAL_WRITE_ATTEMPTS);
        return false;
    }

    /**
     * F1Whisper: delete the sender's local media for a sent "listen once" voice message and mark it
     * burned, so the sender cannot replay it. Idempotent and a no-op for anything that is not an
     * outgoing, not-yet-burned listen-once voice file message. Best-effort, client-side only.
     */
    private void burnOutgoingListenOnceIfNeeded(@NonNull AbstractMessageModel messageModel) {
        if (!messageModel.isOutbox() || messageModel.getType() != MessageType.FILE) {
            return;
        }
        final FileDataModel fileDataModel = messageModel.getFileData();
        if (fileDataModel == null || !fileDataModel.isListenOnce() || fileDataModel.isListenOnceConsumed()) {
            return;
        }
        try {
            logger.info("Burning sent listen-once voice message {}", messageModel.getId());
            // F1Whisper (fifth fork review, F5-04): the metadata write comes FIRST, and it is conditional. It used to
            // delete the files, mutate the caller's detached instance and full-row-save it, so a message hard-deleted
            // between the send and this burn came back - as a row whose media had just been deleted. Writing the row
            // first means a lost race deletes nothing, and a crash after the write leaves flags that say "burned", which
            // is the state the repair burn already knows how to finish.
            final boolean burned = updateMediaMetadata(messageModel, current -> {
                final FileDataModel currentFileData = current.getFileData();
                if (currentFileData == null || !currentFileData.isListenOnce() || currentFileData.isListenOnceConsumed()) {
                    return false;
                }
                currentFileData.setListenOnceConsumed();
                currentFileData.isDownloaded(false);
                current.setFileData(currentFileData);
                return true;
            });
            if (!burned) {
                return;
            }
            // Delete the stored encrypted media + thumbnail; the recipient still gets it from the blob.
            fileService.removeMessageFiles(messageModel, true);
            // F1Whisper: play the one-shot burn animation on the sender's own bubble too (once, on
            // the re-render below). Consumed by the decorator; not replayed on chat reopen.
            ListenOnceBurnRegistry.markForBurnAnimation(messageModel.getId());
            fireOnModifiedMessage(messageModel);
        } catch (Exception e) {
            logger.error("Failed to burn sent listen-once voice message", e);
        }
    }

    @Override
    public void addMessageReaction(
        @NonNull AbstractMessageModel messageModel,
        @NonNull MessageState state,
        @NonNull String fromIdentity,
        @NonNull Date date
    ) {
        if (!MessageUtil.isReaction(state)) {
            throw new IllegalArgumentException("The given message state is not a reaction: " + state);
        }
        updateAckDecState(messageModel, state, fromIdentity);
    }

    /**
     * Special compatibility handling for state changes to ACK and DEC. Saves these messages to the reactions database
     *
     * @param messageModel   The target message model of this state change / reaction
     * @param newState       The desired new state (ACK or DEC)
     * @param senderIdentity The identity of the sender who sent this state change / reaction
     */
    private void updateAckDecState(@NonNull AbstractMessageModel messageModel, @NonNull MessageState newState, @Nullable String senderIdentity) {
        if (newState != MessageState.USERACK && newState != MessageState.USERDEC) {
            return;
        }

        if (senderIdentity == null) {
            senderIdentity = identityStore.getIdentityString();
        }

        clearMessageState(messageModel); // TODO(ANDR-3325): Remove

        handleEmojiReaction(messageModel, newState, senderIdentity);
    }

    /**
     * Map state changes (acknowledge and decline) to their emoji reaction equivalents keeping in account
     * the mutual exclusivity of acks and decs
     *
     * @param messageModel The AbstractMessageModel of the target message
     * @param state        The desired new state
     * @param fromIdentity The identity of the sender of this ack/dec reaction
     */
    private void handleEmojiReaction(AbstractMessageModel messageModel, MessageState state, String fromIdentity) {
        if (state == MessageState.USERACK) {
            saveEmojiReactionMessage(messageModel, fromIdentity, Reaction.ActionCase.WITHDRAW, EmojiUtil.THUMBS_DOWN_SEQUENCE);
            saveEmojiReactionMessage(messageModel, fromIdentity, Reaction.ActionCase.APPLY, EmojiUtil.THUMBS_UP_SEQUENCE);
        } else if (state == MessageState.USERDEC) {
            saveEmojiReactionMessage(messageModel, fromIdentity, Reaction.ActionCase.WITHDRAW, EmojiUtil.THUMBS_UP_SEQUENCE);
            saveEmojiReactionMessage(messageModel, fromIdentity, Reaction.ActionCase.APPLY, EmojiUtil.THUMBS_DOWN_SEQUENCE);
        }
    }

    @Override
    public boolean markAsRead(AbstractMessageModel message, boolean silent) {
        logger.debug("markAsRead message = {} silent = {}", message.getApiMessageId(), silent);
        boolean saved = false;

        if (MessageUtil.canMarkAsRead(message)) {
            ContactModel contactModel = contactService.getByIdentity(message.getIdentity());

            // Check whether the message allows read receipt before setting the message to read
            // because a message only allows a read receipt if has not been marked as read yet.
            boolean messageAllowsDeliveryReceipt = MessageUtil.canSendDeliveryReceipt(message, ProtocolDefines.DELIVERYRECEIPT_MSGREAD);

            Date readAt = new Date();

            // F1Whisper (fifth fork review, F5-04): read state and countdown are decided against a FRESHLY RELOADED row
            // and written as ONE conditional, non-inserting update. They used to be a mutation of the caller's detached
            // instance followed by a full-row save, which could recreate a message hard-deleted while this was deciding,
            // overwrite a newer delete-for-everyone, and clobber a concurrent freeze's corrected sender timer.
            final boolean countdownStarted = markReadDurably(message, readAt);
            if (!message.isRead()) {
                // The row had gone, had been deleted for everyone, or another thread had already marked it read. None of
                // those is a read this device performed, so no receipt is owed for it.
                logger.info("Not marking {} as read: the row was superseded", message.getApiMessageId());
                return false;
            }

            if (!silent) {
                //fire on modified if not silent
                fireOnModifiedMessage(message);
            }

            saved = true;

            if (countdownStarted) {
                logger.info("Disappearing: started incoming countdown uid={} timer={}s expiresAt={}",
                    message.getUid(), message.getDisappearingTimerSeconds(), message.getExpiresAt());
                try {
                    ch.threema.app.services.DisappearingMessageService.getInstance()
                        .rescheduleNextAlarm(ch.threema.app.ThreemaApplication.getAppContext());
                } catch (Exception ex) {
                    logger.warn("Could not rearm disappearing alarm after markAsRead", ex);
                }
            }

            // F1Whisper: belt-and-suspenders — delete immediately if already overdue at read time.
            ch.threema.app.services.DisappearingMessageService.enforceIfExpired(message);

            if (contactModel == null) {
                return saved;
            }

            if (message.getApiMessageId() == null) {
                logger.info("Message id is null; cannot send read receipt or reflect message update");
                return saved;
            }

            boolean receiverAllowsDeliveryReceipt = isDeliveryReceiptAllowedForContact(contactModel);

            if (messageAllowsDeliveryReceipt && receiverAllowsDeliveryReceipt) {
                if (message instanceof GroupMessageModel) {
                    // F1Whisper: a group read receipt goes ONLY to the message sender (not all members)
                    sendGroupReceiptToSender((GroupMessageModel) message, ProtocolDefines.DELIVERYRECEIPT_MSGREAD);
                    // F1Whisper (seventh fork review, F7-04): and the read INTENT goes to this user's other devices.
                    //
                    // A group delivery receipt is explicitly not reflected (GroupDeliveryReceiptMessage.reflectOutgoing
                    // returns false, and the reflected-outgoing handler accepts reactions only), so with read receipts
                    // enabled a group message read on a linked device produced nothing at all on the primary device: it
                    // stayed unread, with no countdown, and the startup repair pass will not touch an unread row either.
                    // The incoming-message update below is D2D-only, so emitting it as well announces the read to the
                    // user's own devices without sending the peer a second receipt.
                    reflectGroupReadToLinkedDevices((GroupMessageModel) message, readAt);
                } else {
                    contactService.createReceiver(contactModel).sendDeliveryReceipt(
                        ProtocolDefines.DELIVERYRECEIPT_MSGREAD,
                        new MessageId[]{message.getMessageId()},
                        readAt.getTime()
                    );
                }
                logger.info("Enqueued delivery receipt (read) message for message ID {} from {}",
                    message.getApiMessageId(), contactModel.getIdentity());
            } else {
                if (message instanceof MessageModel) {
                    contactService.createReceiver(contactModel).sendIncomingMessageUpdateRead(
                        Set.of(message.getMessageId()), readAt.getTime()
                    );
                } else if (message instanceof GroupMessageModel) {
                    reflectGroupReadToLinkedDevices((GroupMessageModel) message, readAt);
                }
            }
        }

        return saved;
    }

    // region F1Whisper: group delivery/read receipts (for the group message-details screen)

    /**
     * Whether a delivery/read receipt may be sent to the given contact, using the same gate as 1:1
     * read receipts: the per-contact override, falling back to the global "send read receipts"
     * preference.
     */
    /**
     * F1Whisper: returns the ONE shared conversation disappearing timer, the conversation-level
     * <em>setting</em>, for {@code message}'s conversation.
     *
     * <p><strong>Scope, since the per-message-timer wave.</strong> This is the timer that governs
     * messages <em>this device sends</em>, and it is the back-compat fallback for an incoming message
     * whose sender advertised nothing. It is NOT the authority for an incoming message in general:
     * that authority is the timer the sender put in the message's own encrypted metadata, resolved by
     * {@link DisappearingFreezeDecision#resolveIncomingTimer(Integer, Integer)}. An earlier revision of
     * this doc claimed {@code createLocalModel} stamps "the same value the freeze lookup would return"
     * so the two directions could not diverge — that is no longer true, and it must not be: the whole
     * point is that a recipient's setting can no longer rewrite a sender's per-message policy.
     *
     * <p>For 1:1 messages: reads {@code ContactModel.getDisappearingMessagesTimerSeconds()},
     * normalised through {@link DisappearingTimerConvergence#governingTimerSeconds(Integer)} so that
     * {@code 0} and any legacy negative value resolve to "off" in exactly one place.
     * {@code ContactModel.getPeerDisappearingTimerSeconds()} is NOT read here, nor by any other code:
     * the per-direction column is dead for 1:1 as well as for groups.
     *
     * <p>For group messages (single-shared-field convergence model, Option X): reads
     * {@code GroupModelOld.getDisappearingMessagesTimerSeconds()} — the ONE shared group timer that
     * also governs this device's outgoing group messages.
     *
     * <p>Returns {@code null} when the conversation timer is off.
     */
    @Nullable
    private Integer conversationDisappearingTimer(@NonNull AbstractMessageModel message) {
        try {
            if (message instanceof GroupMessageModel) {
                // F1Whisper GROUP convergence fix (Option X): groups use the SINGLE shared field for
                // BOTH outgoing and incoming freeze (not the per-direction peer column), so every
                // member freezes group messages at the same converged value.
                ch.threema.storage.models.group.GroupModelOld group =
                    groupService.getById(((GroupMessageModel) message).getGroupId());
                return group != null ? group.getDisappearingMessagesTimerSeconds() : null;
            } else {
                // F1Whisper 1:1 shared-field LWW: one conversation timer, both directions.
                ContactModel c = contactService.getByIdentity(message.getIdentity());
                return c != null
                    ? DisappearingTimerConvergence.governingTimerSeconds(c.getDisappearingMessagesTimerSeconds())
                    : null;
            }
        } catch (Exception e) {
            logger.warn("Could not read conversation disappearing timer for uid={}", message.getUid(), e);
            return null;
        }
    }

    /**
     * F1Whisper: freeze an incoming message at the timer its SENDER advertised, authoritatively.
     *
     * <p>Shared by the 1:1 and the group receive paths, which differ only in which model they hold.
     * The decision itself is pure and exhaustively unit-tested
     * ({@link DisappearingFreezeDecision#resolveIncomingTimer(Integer, Integer)}); this method is the
     * side-effecting half — persist, and emit the positive log line naming which of the three sources
     * the freeze came from.
     *
     * <p>The result OVERWRITES whatever {@code createLocalModel} provisionally stamped, including
     * overwriting a non-zero local timer with {@code 0} when the sender said OFF. That is why it goes
     * through {@link DisappearingMessageService#freezeIncomingTimer(AbstractMessageModel, Integer)}
     * and not {@code freezeTimer}, which is idempotent and cannot store a zero.
     *
     * @param advertisedBySender {@code AbstractMessage.getDisappearingTimerSeconds()} — {@code null}
     *                           when the peer transmitted no timer (a pre-v6.4.3-38 client).
     */
    @Override
    public void freezeIncomingDisappearingPolicy(@NonNull AbstractMessageModel messageModel, @Nullable Integer advertisedBySender) {
        repairDuplicateIncomingFreeze(messageModel, advertisedBySender);
    }

    /**
     * F1Whisper (fifth fork review, F5-05): repair a duplicate's frozen policy from what the SENDER advertised, and from
     * nothing else.
     *
     * <p>The defect: F4-05 made a duplicate redelivery repair the sender's policy, which is right when the sender
     * advertised one - that redelivery is the message's only second chance if the app died between the insert and the
     * freeze. But it ran the same repair when the sender advertised NOTHING, and an absent value resolves against the
     * conversation's timer AS IT IS NOW. So an at-least-once duplicate of an old message from a pre-v6.4.3-38 client
     * silently re-froze it at whatever the timer had since been changed to: receive and read a message with the timer
     * off, turn a 30-second timer on, and a duplicate arriving afterwards gave that already-read message a 30-second
     * deadline measured from its old read time, making it immediately overdue. A 30-to-300 change extended retention the
     * same way.</p>
     *
     * <p>A duplicate is a network event. It is not new information about policy, and it must not be treated as any. Only
     * an explicit advertised value - {@code 0} for "the sender says OFF" or a positive timer - is information, and only
     * that is applied. An absent value leaves the policy frozen at first acceptance, which is what "frozen" means.</p>
     *
     * <p>Not fixed by persisting receive-time provenance instead: that would be a schema change for a case where the
     * correct answer is already known, since the value frozen at first acceptance IS the provenance.</p>
     */
    private void repairDuplicateIncomingFreeze(@NonNull AbstractMessageModel messageModel, @Nullable Integer advertisedBySender) {
        if (advertisedBySender == null) {
            logger.info("Disappearing: duplicate of uid={} advertised no timer; keeping the policy frozen at first acceptance",
                messageModel.getUid());
            return;
        }
        applyIncomingFreeze(messageModel, advertisedBySender);
    }

    @Override
    public void freezeIncomingDisappearingPolicyBeforeFirstWrite(
        @NonNull AbstractMessageModel messageModel,
        @Nullable Integer advertisedBySender
    ) {
        freezeIncomingBeforeFirstWrite(messageModel, advertisedBySender);
    }

    /**
     * F1Whisper (fourth fork review, F4-05): stamp the timer the SENDER advertised onto a freshly built INCOMING model
     * BEFORE its first durable write, so that accepting the message and accepting its policy are one write rather than two.
     *
     * The defect this closes: the row was inserted carrying only the PROVISIONAL local conversation timer that
     * {@code createLocalModel} stamps, and the sender's authoritative value was applied by a second write afterwards. A
     * process death between the two left a row with the wrong policy, and the retry the server then performed hit the
     * duplicate-message guard, which returns success without ever reaching the freeze. The wrong timer therefore became
     * permanent: the message could be kept forever, deleted too early, or deleted too late, in defiance of what the sender
     * asked for. Nothing errored and nothing logged, because from the app's point of view the message had been delivered.
     *
     * Deliberately model-only. It performs no re-read and no save, because the row does not exist yet - the caller's own
     * insert is what persists this. That is the opposite of {@link #applyIncomingFreeze}, which corrects an already-written
     * row and therefore must re-read it first.
     *
     * Must be called after the model's identity (or group id) is set, since the conversation timer is resolved from it.
     */
    private void freezeIncomingBeforeFirstWrite(
        @NonNull AbstractMessageModel messageModel,
        @Nullable Integer advertisedBySender
    ) {
        DisappearingMessageService.freezeIncomingTimer(
            messageModel,
            DisappearingFreezeDecision.resolveIncomingTimer(
                advertisedBySender,
                conversationDisappearingTimer(messageModel)
            )
        );
    }

    private void applyIncomingFreeze(@NonNull AbstractMessageModel messageModel, @Nullable Integer advertisedBySender) {
        Integer resolved = DisappearingFreezeDecision.resolveIncomingTimer(
            advertisedBySender,
            conversationDisappearingTimer(messageModel)
        );
        // Named source, on every branch. Before this wave a message frozen at "off" logged NOTHING,
        // so the only signature of the defect was an ABSENT line — which is how two wrong conclusions
        // were reached while debugging it. No decision here is observable only by silence.
        final String source;
        if (advertisedBySender != null) {
            source = "metadata";
        } else if (resolved != null) {
            source = "shared-field";
        } else {
            source = "none";
        }
        // Re-read the row before deciding and writing. saveBoxMessage/saveGroupMessage have already
        // fired the new-message listener by the time we get here, and that reaches MarkAsReadRoutine
        // on another thread, working from its OWN re-read of the row; with "save media to gallery"
        // enabled there is blocking disk I/O in between, so it can comfortably win.
        //
        // F1Whisper (fifth fork review, F5-04): the two corrections this loop exists for.
        //
        // 1. A failed re-read no longer falls back to the caller's detached instance. That fallback was the one path by
        //    which an insert-capable full-row save could still run against a row that had gone, recreating a message the
        //    user had deleted; and against a row deleted for everyone it restored the old body and nulled the deletion.
        //    If the row cannot be read there is nothing to freeze, and that is the whole answer.
        //
        // 2. The write is column-scoped and conditional on the four fields the freeze decision READ, so a concurrent
        //    first-read that lands in between makes it fail rather than succeed wrongly. The retry re-reads, and
        //    freezeIncomingTimer then re-derives the deadline from the countdown that first-read just started - the two
        //    transitions compose instead of overwriting one another, in either completion order.
        AbstractMessageModel target = null;
        boolean wrote = false;
        for (int attempt = 0; attempt < CONDITIONAL_WRITE_ATTEMPTS; attempt++) {
            target = reloadPersistedModel(messageModel);
            if (target == null) {
                logger.info("Disappearing: not freezing uid={}; its row is gone or unreadable", messageModel.getUid());
                return;
            }
            if (isDeletedForEveryone(target)) {
                logger.info("Disappearing: not freezing uid={}; it was deleted for everyone", messageModel.getUid());
                return;
            }
            final Integer priorTimer = target.getDisappearingTimerSeconds();
            final Long priorStart = target.getExpireStartedAt();
            final Long priorExpires = target.getExpiresAt();
            final boolean priorRead = target.isRead();
            if (!DisappearingMessageService.freezeIncomingTimer(target, resolved)) {
                break;
            }
            wrote = applyRowUpdate(target, MessageRowUpdate.builder()
                .set(AbstractMessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS, target.getDisappearingTimerSeconds())
                .set(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT, target.getExpireStartedAt())
                .set(AbstractMessageModel.COLUMN_EXPIRES_AT, target.getExpiresAt())
                .expect(AbstractMessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS, priorTimer)
                .expect(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT, priorStart)
                .expect(AbstractMessageModel.COLUMN_EXPIRES_AT, priorExpires)
                .expect(AbstractMessageModel.COLUMN_IS_READ, priorRead)
                .build());
            if (wrote) {
                break;
            }
            logger.debug("Disappearing: freeze for {} was superseded, re-reading (attempt {})", messageModel.getId(), attempt + 1);
        }
        if (wrote) {
            if (target != messageModel) {
                // Keep the instance the caller, the cache and the already-notified listeners hold
                // coherent with what was written, for the disappearing fields this method owns.
                messageModel.setDisappearingTimerSeconds(target.getDisappearingTimerSeconds());
                messageModel.setExpireStartedAt(target.getExpireStartedAt());
                messageModel.setExpiresAt(target.getExpiresAt());
            }
            // The freeze can start, re-derive or cancel a countdown, so the earliest pending expiry
            // may have moved. Re-arm exactly as markAsRead does, including the guard: the alarm is a
            // best-effort optimisation over enforceIfExpired, never a correctness dependency.
            try {
                DisappearingMessageService.getInstance()
                    .rescheduleNextAlarm(ch.threema.app.ThreemaApplication.getAppContext());
            } catch (Exception ex) {
                logger.warn("Could not rearm disappearing alarm after incoming freeze", ex);
            }
        }
        // "E1 receive-freeze" is kept inside the message text on purpose: the two-phone smoke script
        // greps for it, and one line per incoming message is enough. Logged from `target`, which is the
        // instance actually written — logging the caller's would report a stale timer whenever the
        // freeze was a no-op, and the point of this line is that it can be trusted.
        final AbstractMessageModel logged = target != null ? target : messageModel;
        logger.info("Disappearing: froze incoming (E1 receive-freeze) uid={} timer={}s expiresAt={} source={}",
            logged.getUid(), logged.getDisappearingTimerSeconds(), logged.getExpiresAt(), source);
    }

    /**
     * F1Whisper (fifth fork review, F5-04): how many times a conditional lifecycle write re-reads and re-decides before
     * giving up.
     *
     * <p>Bounded rather than unbounded on purpose. Every retry is caused by a CONCURRENT transition winning the row, and
     * those are finite events (one first-read, one freeze, one claim) rather than a livelock source; a caller that loses
     * three in a row has almost certainly lost to something it should not be fighting, and spinning would be worse than
     * logging and leaving the row to the startup repair pass.</p>
     */
    private static final int CONDITIONAL_WRITE_ATTEMPTS = 3;

    /**
     * F1Whisper (fifth fork review, F5-04): mark {@code message} read, and start its countdown if reading it starts one, as
     * ONE conditional, non-inserting write against the CURRENT row.
     *
     * <p>The defect this closes: the old code mutated the caller's detached instance and called {@link #save}, a full-row
     * upsert. If the message had been hard-deleted while this was deciding, that upsert recreated it; if it had been
     * deleted for everyone, the write restored the old body and nulled the deletion timestamp; and because the whole row
     * went to disk, it also reverted whatever a concurrent incoming freeze had just written for the sender's corrected
     * timer.</p>
     *
     * <p>So: reload, decide from the reloaded values, and write with the four fields the decision READ as
     * compare-and-set conditions. A freeze that lands in between makes this write fail rather than succeed wrongly, and
     * the retry re-reads and applies the read state on top of the freeze's timer instead of underneath it. Both survive,
     * in either completion order, which is the property F4-05's model-only freeze could not give on its own.</p>
     *
     * <p>The caller's instance is updated to exactly what was written, and left untouched when nothing was, so
     * {@code markAsRead} can tell a real read from a superseded one by asking the model.</p>
     *
     * @return whether a countdown was started by this write (the caller re-arms the alarm for it).
     */
    private boolean markReadDurably(@NonNull AbstractMessageModel message, @NonNull Date readAt) {
        for (int attempt = 0; attempt < CONDITIONAL_WRITE_ATTEMPTS; attempt++) {
            final AbstractMessageModel current = reloadPersistedModel(message);
            if (current == null) {
                // Never fall back to the detached instance: that fallback is what made an insert-capable save reachable
                // for a row that had gone.
                return false;
            }
            if (isDeletedForEveryone(current)) {
                // U-02: permanent, not contention. Retrying re-reads the same tombstone and is refused again; this is
                // the branch whose absence spent three attempts and a warning on every open of the reported chat.
                logger.info("Not marking {} as read: it was deleted for everyone", message.getApiMessageId());
                return false;
            }
            if (current.isRead()) {
                // Another thread got there first. Its read is the read; this one owes no receipt. The caller's instance
                // adopts the winner so it does not go on claiming to be unread (sixth fork review, F6-01).
                message.adoptPersistedRow(current);
                return false;
            }

            final FirstReadDecision.Countdown countdown = FirstReadDecision.countdownAtFirstRead(
                current.isOutbox(),
                current.getType() == MessageType.DISAPPEARING_STATUS,
                current.getExpireStartedAt(),
                current.getDisappearingTimerSeconds(),
                conversationDisappearingTimer(current),
                readAt.getTime()
            );

            final MessageRowUpdate update = MessageLifecycleUpdates.firstRead(
                readAt,
                current.getDisappearingTimerSeconds(),
                current.getExpireStartedAt(),
                current.getExpiresAt(),
                countdown
            );

            if (applyRowUpdate(current, update)) {
                message.setRead(true);
                message.setReadAt(readAt);
                message.setModifiedAt(readAt);
                if (countdown != null) {
                    message.setDisappearingTimerSeconds(countdown.timerSeconds);
                    message.setExpireStartedAt(countdown.startedAt);
                    message.setExpiresAt(countdown.expiresAt);
                }
                return countdown != null;
            }
            logger.debug("markAsRead: row {} moved under us, re-reading (attempt {})", message.getId(), attempt + 1);
        }
        logger.warn("markAsRead: gave up marking {} read after {} superseded attempts",
            message.getApiMessageId(), CONDITIONAL_WRITE_ATTEMPTS);
        return false;
    }

    @Override
    public boolean updateReceivedTimestamp(@NonNull AbstractMessageModel message, @NonNull Date receivedAt) {
        final AbstractMessageModel current = reloadPersistedModel(message);
        if (current == null) {
            return false;
        }
        current.setCreatedAt(receivedAt);
        final boolean written = applyRowUpdate(current, MessageLifecycleUpdates.receivedTimestamp(
            receivedAt,
            TimelineKeyset.effectiveSortDate(current)
        ));
        if (written) {
            message.setCreatedAt(receivedAt);
        }
        return written;
    }

    @Override
    public boolean updateLocationAddress(@NonNull AbstractMessageModel message, @Nullable String address) {
        for (int attempt = 0; attempt < CONDITIONAL_WRITE_ATTEMPTS; attempt++) {
            final AbstractMessageModel current = reloadPersistedModel(message);
            if (current == null) {
                logger.info("Not storing a location address for uid={}: its row is gone", message.getUid());
                return false;
            }
            if (isDeletedForEveryone(current)) {
                logger.info("Not storing a location address for uid={}: it was deleted for everyone", message.getUid());
                return false;
            }
            final LocationDataModel locationData = current.getLocationData();
            if (locationData == null) {
                return false;
            }
            final Poi updatedPoi;
            if (address != null && locationData.poiNameOrNull != null) {
                updatedPoi = new Poi.Named(locationData.poiNameOrNull, address);
            } else if (address != null) {
                updatedPoi = new Poi.Unnamed(address);
            } else {
                updatedPoi = null;
            }

            final String priorBody = current.getBody();
            current.setLocationData(new LocationDataModel(
                locationData.latitude,
                locationData.longitude,
                locationData.accuracy,
                updatedPoi
            ));
            if (TestUtil.compare(priorBody, current.getBody())) {
                return true;
            }
            if (applyRowUpdate(current, MessageLifecycleUpdates.locationAddress(current.getBody(), priorBody))) {
                message.adoptPersistedBody(current.getBody());
                return true;
            }
            logger.debug("The location address of {} was superseded, re-reading (attempt {})",
                message.getId(), attempt + 1);
        }
        logger.warn("Gave up storing the location address of uid={} after {} superseded attempts",
            message.getUid(), CONDITIONAL_WRITE_ATTEMPTS);
        return false;
    }

    @Override
    public boolean markAsReadFromSync(@NonNull AbstractMessageModel message, @NonNull Date readAt) {
        final boolean countdownStarted = markReadDurably(message, readAt);
        if (!message.isRead()) {
            logger.info("Not recording the reflected read of {}: the row was superseded", message.getApiMessageId());
            return false;
        }
        if (countdownStarted) {
            logger.info("Disappearing: started incoming countdown from a reflected read uid={} expiresAt={}",
                message.getUid(), message.getExpiresAt());
            try {
                DisappearingMessageService.getInstance()
                    .rescheduleNextAlarm(ch.threema.app.ThreemaApplication.getAppContext());
            } catch (Exception e) {
                logger.warn("Could not rearm the disappearing alarm after a reflected read", e);
            }
        }
        return true;
    }

    /**
     * F1Whisper (fifth fork review, F5-04): run one {@link MessageRowUpdate} against {@code model}'s row, choosing the
     * factory from the model's own type. Never inserts; see {@link MessageRowUpdate}.
     *
     * <p>F1Whisper (sixth fork review, F6-01): the write and the reconciliation of every cached instance of that row are
     * ONE operation under the SAME monitor {@link #save} takes. Column-scoped writing was only half the invariant: the
     * service caches an incoming message from the moment it is created, and a full-row save from any other live instance
     * of the row - an edit resolved through the cache, a group receipt, a star or pin toggle - wrote its own
     * pre-transition snapshot straight back over the top. Serialising the two halves removes the window in which such a
     * save can observe a row that has already moved, and refreshing the instances removes the stale snapshot itself.</p>
     */
    private boolean applyRowUpdate(@NonNull AbstractMessageModel model, @NonNull MessageRowUpdate update) {
        try {
            if (model.getId() <= 0) {
                return false;
            }
            final Collection<? extends AbstractMessageModel> cache = cacheFor(model);
            if (cache == null) {
                return false;
            }
            synchronized (cache) {
                final boolean written;
                if (model instanceof GroupMessageModel) {
                    written = databaseService.getGroupMessageModelFactory().applyRowUpdate(model.getId(), update);
                } else if (model instanceof DistributionListMessageModel) {
                    written = databaseService.getDistributionListMessageModelFactory().applyRowUpdate(model.getId(), update);
                } else {
                    written = databaseService.getMessageModelFactory().applyRowUpdate(model.getId(), update);
                }
                if (written) {
                    reconcileCache(model, cache);
                }
                return written;
            }
        } catch (Exception e) {
            logger.warn("Could not apply a conditional row update to uid={}", model.getUid(), e);
            return false;
        }
    }

    /**
     * F1Whisper (sixth fork review, F6-01): the per-type cache whose monitor guards writes to {@code model}'s row, or
     * {@code null} for a model of no known type.
     *
     * <p>The collection IS the lock, exactly as in {@link #save}. Two writers that pick different monitors for the same
     * row are not serialised at all, so this must stay the only way either side chooses one.</p>
     */
    @Nullable
    private Collection<? extends AbstractMessageModel> cacheFor(@NonNull AbstractMessageModel model) {
        if (model instanceof GroupMessageModel) {
            return groupMessageCache;
        }
        if (model instanceof DistributionListMessageModel) {
            return distributionListMessageCache;
        }
        if (model instanceof MessageModel) {
            return contactMessageCache;
        }
        return null;
    }

    /**
     * F1Whisper (sixth fork review, F6-01): re-read the row that was just written and make every cached instance of it
     * agree. Called with the cache monitor already held.
     *
     * <p>The row is re-read rather than the update's assignments being replayed onto the instances: a column-to-setter
     * mapping would be a second definition of what each write means, and it would drift silently the first time a column
     * is added. Nothing is re-read when nothing is cached, which is the ordinary case.</p>
     */
    private void reconcileCache(
        @NonNull AbstractMessageModel model,
        @NonNull Collection<? extends AbstractMessageModel> cache
    ) {
        if (!MessageCacheCoherence.holds(cache, model.getId())) {
            return;
        }
        final AbstractMessageModel persisted = reloadPersistedModel(model);
        MessageCacheCoherence.reconcile(cache, model.getId(), persisted);
    }

    /**
     * F1Whisper (device report 2026-08-06, U-02): whether {@code current} has been deleted for everyone, which for
     * every conditional lifecycle write means REFUSED, permanently.
     *
     * <p>{@link MessageRowUpdate} carries {@code deletedAtUtc IS NULL} structurally, so a write against a tombstone
     * matches zero rows. The reload-decide-write loops read zero rows as "another transition won the row", which is
     * true of every OTHER cause of zero rows and false of this one: re-reading returns the same tombstone and deciding
     * again reaches the same refusal, so the loop spends every attempt it has and then reports contention that never
     * happened. It is the twin of the {@code current == null} check each of these loops already makes - a row that has
     * gone and a row that has been deleted are the same answer to these writers - and it was missing from all nine but
     * {@link #commitEditDurably}, which has refused a deleted row explicitly since F7-03.</p>
     *
     * <p>Only the read path showed it, because only the read path has a query that keeps handing the same row back:
     * the message stayed unread, so every chat open tried again (see U-01, and
     * {@code AbstractMessageModelFactory.UNREAD_ROW_WHERE}).</p>
     */
    private static boolean isDeletedForEveryone(@NonNull AbstractMessageModel current) {
        return current.getDeletedAt() != null;
    }

    /**
     * F1Whisper: re-read {@code messageModel}'s row straight from its factory, bypassing the message
     * caches, so a freeze decision is made and written against current state rather than an instance
     * that a concurrent {@code markAsRead} may already have superseded.
     *
     * @return the fresh model, or {@code null} if the row cannot be re-read (unsaved, unknown subtype,
     *         or a database error) — in which case the caller falls back to the instance it holds,
     *         which is exactly the pre-existing behaviour.
     */
    @Nullable
    private AbstractMessageModel reloadPersistedModel(@NonNull AbstractMessageModel messageModel) {
        try {
            if (messageModel.getId() <= 0) {
                return null;
            }
            if (messageModel instanceof GroupMessageModel) {
                return databaseService.getGroupMessageModelFactory().getById(messageModel.getId());
            }
            if (messageModel instanceof DistributionListMessageModel) {
                return databaseService.getDistributionListMessageModelFactory().getById(messageModel.getId());
            }
            if (messageModel instanceof MessageModel) {
                return databaseService.getMessageModelFactory().getById(messageModel.getId());
            }
            return null;
        } catch (Exception e) {
            logger.warn("Disappearing: could not re-read model uid={} before freezing", messageModel.getUid(), e);
            return null;
        }
    }

    private boolean isDeliveryReceiptAllowedForContact(@Nullable ContactModel contactModel) {
        if (contactModel == null) {
            return false;
        }
        switch (contactModel.getReadReceipts()) {
            case ContactModel.SEND:
                return true;
            case ContactModel.DONT_SEND:
                return false;
            default:
                return synchronizedSettingsService.areReadReceiptsEnabled();
        }
    }

    /**
     * Send a group delivery/read receipt for {@code messageModel} to its sender only. Does NOT gate
     * on preferences — callers are responsible for the gating.
     */
    /**
     * F1Whisper (seventh fork review, F7-04): announce a group message's read state to this user's OTHER devices.
     *
     * <p>The incoming-message update is D2D-only - the receiver schedules it solely when multi-device is active, and it
     * never reaches the peer - so it can be emitted alongside the peer-facing group receipt without duplicating it. It
     * is handled on the linked device by {@code ReflectedIncomingMessageUpdateTask}, which routes it through the same
     * durable first-read transition the local read uses, so every device ends up with the same read timestamp, start
     * and deadline.</p>
     */
    private void reflectGroupReadToLinkedDevices(@NonNull GroupMessageModel message, @NonNull Date readAt) {
        final int localGroupId = message.getGroupId();
        final GroupModelOld groupModel = groupService.getById(localGroupId);
        if (groupModel == null) {
            logger.warn("Could not find group with local group id {}", localGroupId);
            return;
        }
        groupService.createReceiver(groupModel).sendIncomingMessageUpdateRead(
            Set.of(message.getMessageId()),
            readAt.getTime()
        );
    }

    private void sendGroupReceiptToSender(@NonNull GroupMessageModel messageModel, int receiptType) {
        final String senderIdentity = messageModel.getIdentity();
        if (senderIdentity == null) {
            return;
        }
        final GroupModelOld groupModel = groupService.getById(messageModel.getGroupId());
        if (groupModel == null) {
            logger.warn("Could not find group {} while sending group delivery receipt", messageModel.getGroupId());
            return;
        }
        groupService.createReceiver(groupModel).sendGroupDeliveryReceipt(
            receiptType,
            messageModel,
            Set.of(senderIdentity)
        );
    }

    @Override
    public void sendGroupDeliveredReceipt(@NonNull GroupMessageModel messageModel) {
        if (messageModel.isOutbox()) {
            return;
        }
        if ((messageModel.getMessageFlags() & ProtocolDefines.MESSAGE_FLAG_NO_DELIVERY_RECEIPTS) == ProtocolDefines.MESSAGE_FLAG_NO_DELIVERY_RECEIPTS) {
            return;
        }
        final String senderIdentity = messageModel.getIdentity();
        if (senderIdentity == null) {
            return;
        }
        if (!isDeliveryReceiptAllowedForContact(contactService.getByIdentity(senderIdentity))) {
            return;
        }
        sendGroupReceiptToSender(messageModel, ProtocolDefines.DELIVERYRECEIPT_MSGRECEIVED);
    }

    @Override
    public void addGroupMessageState(
        @NonNull GroupMessageModel messageModel,
        @NonNull MessageState state,
        @NonNull String fromIdentity
    ) {
        if (state != MessageState.DELIVERED && state != MessageState.READ) {
            return;
        }
        // F1Whisper (sixth fork review, F6-01): a receipt owns ONE column, and it is merged into the row's current value
        // rather than into the caller's. The model reaching this method is resolved through the group message cache, so
        // it is whatever snapshot was cached when the message was sent; full-row-saving it restored that snapshot
        // wholesale - the outgoing state going back to FS_KEY_MISMATCH, the countdown a resolved-reject SENT transition
        // had just started being cleared. It could also recreate a row that expiry had claimed while the receipt was in
        // flight.
        for (int attempt = 0; attempt < CONDITIONAL_WRITE_ATTEMPTS; attempt++) {
            final AbstractMessageModel reloaded = reloadPersistedModel(messageModel);
            if (!(reloaded instanceof GroupMessageModel)) {
                logger.info("Not recording the {} receipt from {}: the row is gone", state, fromIdentity);
                return;
            }
            if (isDeletedForEveryone(reloaded)) {
                logger.info("Not recording the {} receipt from {}: the message was deleted for everyone",
                    state, fromIdentity);
                return;
            }
            final GroupMessageModel current = (GroupMessageModel) reloaded;
            final String priorStates = MessageLifecycleUpdates.serialiseGroupMessageStates(current.getGroupMessageStates());
            final Map<String, Object> merged = MessageLifecycleUpdates.mergeGroupReceipt(
                current.getGroupMessageStates(), fromIdentity, state);
            if (merged == null) {
                // Already recorded, or a late DELIVERED behind a READ from the same member.
                return;
            }
            final MessageRowUpdate update = MessageLifecycleUpdates.groupReceipt(
                MessageLifecycleUpdates.serialiseGroupMessageStates(merged), priorStates);
            if (applyRowUpdate(current, update)) {
                messageModel.setGroupMessageStates(merged);
                fireOnModifiedMessage(messageModel);
                return;
            }
            logger.debug("The {} receipt from {} was superseded, re-reading (attempt {})", state, fromIdentity, attempt + 1);
        }
        logger.warn("Gave up recording the {} receipt from {} after {} superseded attempts",
            state, fromIdentity, CONDITIONAL_WRITE_ATTEMPTS);
    }

    @NonNull
    @Override
    public List<MessageService.GroupReceiptState> getGroupReceiptStates(@NonNull GroupMessageModel messageModel) {
        final List<MessageService.GroupReceiptState> result = new ArrayList<>();
        if (!messageModel.isOutbox()) {
            return result;
        }
        final GroupModelOld groupModel = groupService.getById(messageModel.getGroupId());
        if (groupModel == null) {
            return result;
        }
        final Map<String, Object> states = messageModel.getGroupMessageStates();
        final String myIdentity = identityStore.getIdentityString();
        final ContactNameFormat nameFormat = preferenceService.getContactNameFormat();
        for (ContactModel member : groupService.getMembers(groupModel)) {
            final String identity = member.getIdentity();
            if (identity == null || identity.equals(myIdentity)) {
                continue;
            }
            MessageState state = null;
            if (states != null) {
                final Object stored = states.get(identity);
                if (MessageState.READ.toString().equals(stored)) {
                    state = MessageState.READ;
                } else if (MessageState.DELIVERED.toString().equals(stored)) {
                    state = MessageState.DELIVERED;
                }
            }
            final String displayName = NameUtil.getContactDisplayNameOrNickname(member, true, nameFormat);
            result.add(new MessageService.GroupReceiptState(identity, displayName, state));
        }
        return result;
    }

    // endregion

    @Override
    @WorkerThread
    public boolean markAsConsumed(AbstractMessageModel message) throws ThreemaException {
        logger.debug("markAsConsumed message = {}", message.getApiMessageId());

        // F1Whisper (fifth fork review, F5-04): consuming a message writes its state, and only its state. It used to
        // full-row-save the caller's detached instance, which could recreate a row deleted in between and reverted every
        // other column to whatever that instance happened to hold.
        final boolean saved = consumeAndUpdateMediaMetadata(message, current -> false);
        if (saved) {
            fireOnModifiedMessage(message);
        }
        return saved;
    }

    @Override
    @WorkerThread
    public boolean updateMediaMetadata(@NonNull AbstractMessageModel messageModel, @NonNull MediaMetadataMutation mutation) {
        return writeMediaMetadata(messageModel, mutation, false);
    }

    @Override
    @WorkerThread
    public boolean consumeAndUpdateMediaMetadata(@NonNull AbstractMessageModel messageModel, @NonNull MediaMetadataMutation mutation) {
        return writeMediaMetadata(messageModel, mutation, true);
    }

    /**
     * F1Whisper (fifth fork review, F5-04): the reload-merge-write-retry loop behind {@link #updateMediaMetadata} and
     * {@link #consumeAndUpdateMediaMetadata}.
     *
     * <p>The mutation is applied to a model read from the database on EVERY attempt, so a write that loses to a
     * concurrent one is not merely refused, it is recomputed on top of the value that won. The write names the body (and,
     * when consuming, the state and modified timestamp) and nothing else, and is conditional on both as they were read.
     * The caller's instance is then made to agree with what was actually stored.</p>
     */
    @WorkerThread
    private boolean writeMediaMetadata(
        @NonNull AbstractMessageModel messageModel,
        @NonNull MediaMetadataMutation mutation,
        boolean consume
    ) {
        for (int attempt = 0; attempt < CONDITIONAL_WRITE_ATTEMPTS; attempt++) {
            final AbstractMessageModel current = reloadPersistedModel(messageModel);
            if (current == null) {
                logger.info("Not writing media metadata for uid={}: its row is gone or unreadable", messageModel.getUid());
                return false;
            }
            if (isDeletedForEveryone(current)) {
                logger.info("Not writing media metadata for uid={}: it was deleted for everyone", messageModel.getUid());
                return false;
            }
            final String priorBody = current.getBody();
            final MessageState priorState = current.getState();
            final String priorCaption = current.getCaption();

            boolean changed;
            try {
                changed = mutation.apply(current);
            } catch (Exception e) {
                logger.error("A media-metadata mutation failed for uid={}", messageModel.getUid(), e);
                return false;
            }

            final Date consumedAt = new Date();
            final boolean consuming = consume && MessageUtil.canMarkAsConsumed(current);
            changed = changed || consuming;
            if (!changed) {
                return false;
            }

            // F1Whisper (sixth fork review, F6-02): the caption travels with the completion, because the legacy image
            // format carries it in the EXIF of the blob and this is the only moment it exists.
            final MessageRowUpdate update = MessageLifecycleUpdates.mediaMetadata(
                current.getBody(),
                priorBody,
                priorState,
                !TestUtil.compare(priorCaption, current.getCaption()),
                current.getCaption(),
                consuming ? consumedAt : null
            );

            if (applyRowUpdate(current, update)) {
                messageModel.adoptPersistedBody(current.getBody());
                if (consuming) {
                    messageModel.setState(MessageState.CONSUMED);
                    messageModel.setModifiedAt(consumedAt);
                }
                return true;
            }
            logger.debug("Media metadata for {} moved under us, re-reading (attempt {})", messageModel.getId(), attempt + 1);
        }
        logger.warn("Gave up writing media metadata for uid={} after {} superseded attempts",
            messageModel.getUid(), CONDITIONAL_WRITE_ATTEMPTS);
        return false;
    }

    @Override
    public void remove(AbstractMessageModel messageModel) {
        remove(messageModel, false);
    }

    /**
     * F1Whisper (seventh fork review, F7-02): delete the row and evict its cache FIRST, under the monitor every
     * lifecycle write takes, and only then touch what the row governed.
     *
     * <p>The defect this closes: hard removal used to delete the files first and the row afterwards, without taking
     * the cache monitor at all. A media download finishing between those two steps wrote its file and won its
     * conditional completion against a row that still existed, so it published a completion event, marked the blob
     * done, and - with "save to gallery" on - exported a permanent clear copy, all for a message that was a moment
     * later deleted. The one file cleanup had already run, so the media it had just written stayed on disk.</p>
     *
     * <p>Deleting the row first inverts that: any completion still in flight now loses its conditional write, cleans up
     * the media it wrote and publishes nothing, and any completion that got there first has its files removed by the
     * cleanup below. The cache eviction shares the deletion's monitor so no writer can be part-way through re-admitting
     * the model while it is being removed.</p>
     *
     * <p>The claim decides OWNERSHIP of the row, not whether cleanup is worth doing: a caller that finds no row lost a
     * race with another remover, and still deletes the files, because refusing to would leave exactly the orphan this
     * closes.</p>
     */
    @Override
    public void remove(final AbstractMessageModel messageModel, boolean silent) {
        final Collection<? extends AbstractMessageModel> cache = cacheFor(messageModel);
        if (cache != null) {
            synchronized (cache) {
                if (messageModel instanceof GroupMessageModel) {
                    databaseService.getGroupMessageModelFactory().delete((GroupMessageModel) messageModel);
                } else if (messageModel instanceof DistributionListMessageModel) {
                    databaseService.getDistributionListMessageModelFactory().delete((DistributionListMessageModel) messageModel);
                } else {
                    databaseService.getMessageModelFactory().delete((MessageModel) messageModel);
                }
                MessageCacheCoherence.reconcile(cache, messageModel.getId(), null);
            }
        }

        abortPendingSend(messageModel);
        cancelMessageDownload(messageModel);

        //remove from sdcard
        fileService.removeMessageFiles(messageModel, true);

        if (!silent) {
            fireOnRemovedMessage(messageModel);
        }
    }

    /**
     * F1Whisper (fifth fork review, F5-04): remove {@code messageModel} if, and only if, it is STILL the overdue row the
     * caller decided about.
     *
     * <p>The defect this closes: all three expiry paths (lazy enforcement, the alarm fire, the startup sweep) selected a
     * due row and then deleted its files, its ballot aggregate and the row itself from that DETACHED snapshot, with no
     * final check. Between the selection and the side effects a duplicate advertising an explicit OFF could clear the
     * timer, or a freeze could re-derive the deadline from the sender's corrected value; the snapshot was still overdue,
     * so content the sender had just said to KEEP was destroyed anyway. A deadline merely repaired earlier in the same
     * pass was likewise treated as authorisation for an unconditional delete much later.</p>
     *
     * <p>The conditional DELETE is the claim AND the deletion in one indivisible step: it re-checks timer, start, deadline,
     * deletion state and due-ness at write time. Winning it is what makes this caller the owner of everything that row
     * governed - files, caches, listeners, and the ballot aggregate the caller removes afterwards. Losing it destroys
     * nothing, and two concurrent enforcers can never both proceed.</p>
     *
     * @return {@code true} if this caller now owns the removal.
     */
    @Override
    public boolean removeIfStillDue(@NonNull AbstractMessageModel messageModel, long nowMillis) {
        final boolean claimed;
        try {
            if (messageModel.getId() <= 0) {
                return false;
            }
            final Long expireStartedAt = messageModel.getExpireStartedAt();
            final Long expiresAt = messageModel.getExpiresAt();
            final Collection<? extends AbstractMessageModel> cache = cacheFor(messageModel);
            if (cache == null || messageModel instanceof DistributionListMessageModel) {
                // Distribution-list messages carry no countdown (only Contact/GroupMessageReceiver stamp one), so there
                // is nothing here to claim.
                return false;
            }
            // F1Whisper (sixth fork review, F6-01): the claim and the cache eviction take the same monitor a full-row
            // save takes, so no writer can be part-way through re-adding this row while it is being claimed.
            synchronized (cache) {
                if (messageModel instanceof GroupMessageModel) {
                    claimed = databaseService.getGroupMessageModelFactory()
                        .deleteIfStillDue(messageModel.getId(), expireStartedAt, expiresAt, nowMillis);
                } else {
                    claimed = databaseService.getMessageModelFactory()
                        .deleteIfStillDue(messageModel.getId(), expireStartedAt, expiresAt, nowMillis);
                }
                if (claimed) {
                    MessageCacheCoherence.reconcile(cache, messageModel.getId(), null);
                }
            }
        } catch (Exception e) {
            logger.warn("Could not claim the expired row uid={}", messageModel.getUid(), e);
            return false;
        }
        if (!claimed) {
            return false;
        }

        abortPendingSend(messageModel);
        fileService.removeMessageFiles(messageModel, true);
        fireOnRemovedMessage(messageModel);
        return true;
    }

    /**
     * F1Whisper (ninth follow-up review, F9-01): stop this message from being sent, whether or not it has started.
     *
     * <p>The defect this closes: every media send goes through ONE worker
     * ({@link ch.threema.app.utils.ExponentialBackOffUtil}'s single-thread executor), so sending two attachments over a
     * slow link leaves the second one waiting - visible as PENDING, for as long as the first upload or its retry backoff
     * takes. This method used to look only for resources a RUNNING process owns: a {@link SendMachine}, which is created
     * inside {@code send()}, and a registered {@code BlobUploader}, which is registered in a later machine step. A
     * process still in the queue has neither, so a deletion found nothing to cancel and left it queued. When it reached
     * the front it encrypted the file, uploaded the content blob and the thumbnail blob, and only then met the guarded
     * handoff, which refused. The peer got nothing and the payload had already left the device - as a persistent
     * ({@code persist=1}) blob for a group, with nothing left to ask the server to remove it.</p>
     *
     * <p>The queued future was retained for exactly this, and nothing called it. It is cancelled FIRST, because a
     * process that never starts needs none of the rest; the machine and the uploader still follow, for the one that had
     * already started. Keyed by {@code getUid()}, which is per message per receiver, so deleting one recipient's copy
     * leaves its siblings in a multi-recipient batch alone.</p>
     *
     * <p>Cancellation is not the boundary on its own - {@code addToQueue} registers the future just after submitting it,
     * so a process can be running before there is anything to cancel. That window is closed by {@link #mayStillSend},
     * which every queued media process consults before it acts.</p>
     */
    private void abortPendingSend(@NonNull AbstractMessageModel messageModel) {
        //drop it from the sending queue if it has not started: nothing else here can reach a process that owns nothing yet
        if (messageSendingService != null) {
            messageSendingService.abort(messageModel.getUid());
        }

        SendMachine machine = getSendMachine(messageModel, false);
        if (machine != null) {
            //abort pending send machine
            //do not remove SendMachine (fix ANDR-522)
            machine.abort();
        }

        //remove pending uploads
        cancelUploader(messageModel);
    }

    /**
     * F1Whisper (ninth follow-up review, F9-01): whether a queued media send process may still act on its message.
     *
     * <p>Asked at the entry of every queued media process, and again before {@code encryptAndSend} persists or queues
     * anything, because between the row being created PENDING and its upload starting there are two intervals the user
     * can delete in and the process cannot see: preprocessing (image scaling, audio trimming) and the wait behind
     * another upload. The process holds a model captured when the message was created, and that instance says nothing
     * about a deletion that happened afterwards, so the ROW is asked - through the same gate a persistent content task
     * uses, because it is the same question.</p>
     *
     * <p>A refusal is quiet and final: the caller returns {@code false}, which the backoff runnable treats as a normal
     * completion, so there is no retry and no {@code processingFailed} - a message the user deleted on purpose must not
     * produce a send-failure notice. A row that cannot be re-read at all is refused too, as every reload-decide-write
     * loop in this class does: if the row is unreadable, the handoff save at the end cannot succeed either, so refusing
     * here only moves an already-doomed send in front of the upload instead of behind it.</p>
     */
    @WorkerThread
    private boolean mayStillSend(@NonNull AbstractMessageModel messageModel) {
        if (PersistentTaskRowGate.transmits(reloadPersistedModel(messageModel))) {
            return true;
        }
        logger.info("Not sending {}: its row is gone or was deleted before anything left the device",
            messageModel.getUid());
        return false;
    }

    @Override
    public boolean processIncomingContactMessage(final AbstractMessage message, @NonNull TriggerSource triggerSource) throws Exception {
        logger.info("processIncomingContactMessage: {}", message.getMessageId());

        final String senderIdentity = message.getFromIdentity();
        if (senderIdentity == null) {
            logger.error("Could not process a message of type {} without a sender identity", message.getType());
            return false;
        }

        MessageModel messageModel = null;

        MessageModel existingModel = databaseService.getMessageModelFactory()
            .getByApiMessageIdAndIdentity(message.getMessageId(), message.getFromIdentity());

        if (existingModel != null) {
            //first search in cache
            MessageModel savedMessageModel;
            logger.info("processIncomingContactMessage: {} check contact message cache", message.getMessageId());
            synchronized (contactMessageCache) {
                savedMessageModel = contactMessageCache.stream()
                    .filter(model ->
                        model.getApiMessageId() != null &&
                        model.getApiMessageId().equals(message.getMessageId().toString())
                        && senderIdentity.equals(model.getIdentity())
                    )
                    .findFirst()
                    .orElse(null);
            }
            logger.info("processIncomingContactMessage: {} check contact message cache end", message.getMessageId());

            if (savedMessageModel == null) {
                //get from sql result
                savedMessageModel = existingModel;
            }

            if (savedMessageModel.isSaved()) {
                // F1Whisper CHECKLIST item-edit (1:1): like the group path, the creator re-broadcasts
                // an edited checklist over the EXISTING Poll wire carrying the SAME api message id, so
                // this duplicate-message guard would drop it BEFORE it reaches the merge. For an
                // already-existing CHECKLIST PollSetup, reuse the saved model and fall through to the
                // normal save path -> ballotService.update() -> mergeChecklistUpdate(), so added /
                // removed / reordered items sync instead of being silently discarded. Every other 1:1
                // message keeps the duplicate guard unchanged -- no regression.
                if (isExistingChecklistPollSetupContact(message)) {
                    logger.info("ContactMessage {}: checklist re-broadcast, merging into existing ballot", message.getMessageId());
                    messageModel = savedMessageModel;
                } else {
                    // F1Whisper (fourth fork review, F4-05): a duplicate is the ONLY second chance this message gets, so
                    // repair the sender's policy here before dropping it. If the app died between the insert and the
                    // freeze, this redelivery is what would otherwise make the wrong timer permanent - the row exists, so
                    // the guard returns success and nothing downstream ever revisits it. Idempotent: freezeIncomingTimer
                    // returns false when the row already carries the sender's value, so an ordinary duplicate writes
                    // nothing.
                    //
                    // Fifth review, F5-05: ONLY an explicitly advertised value. An absent one resolves against the
                    // conversation timer as it is NOW, so repairing from it re-froze an old message at a setting chosen
                    // long after it arrived. See repairDuplicateIncomingFreeze.
                    repairDuplicateIncomingFreeze(savedMessageModel, message.getDisappearingTimerSeconds());
                    return true;
                }
            } else {
                messageModel = savedMessageModel;
            }
        }

        // Look up contact model
        //
        // Note: At this point, the contact should have been created by the IncomingMessageProcessor.
        final ContactModel contactModel = contactService.getByIdentity(senderIdentity);
        if (contactModel == null) {
            logger.error("Could not process a message of type {} from an unknown contact", message.getType());
            logger.info("processIncomingContactMessage: {} FAILED", message.getMessageId());
            return false;
        }

        // As soon as we get a direct message, unhide and unarchive the contact
        contactService.setAcquaintanceLevel(senderIdentity, ContactModel.AcquaintanceLevel.DIRECT);
        contactService.setIsArchived(senderIdentity, false, triggerSource);

        // Bump "lastUpdateAt" if necessary, depending on the message type. Note that due to the
        // listeners, we should bump the last update before saving the box message. Saving the box
        // message will trigger the listeners that, among other things, update the webclient. For
        // this purpose it is important that the last update flag has already been bumped.
        if (message.bumpLastUpdate()) {
            contactService.bumpLastUpdate(senderIdentity);
        }

        // Handle message depending on subtype
        final Class<? extends AbstractMessage> messageClass = message.getClass();
        if (messageClass.equals(TextMessage.class)) {
            messageModel = saveBoxMessage((TextMessage) message, messageModel, contactModel);
        } else if (messageClass.equals(ImageMessage.class)) {
            messageModel = saveBoxMessage((ImageMessage) message, messageModel, contactModel);
            // silently save to gallery if enabled
            if (
                preferenceService != null
                    && preferenceService.isSaveMedia()
                    && messageModel.getImageData().isDownloaded()
                    && !conversationCategoryService.isPrivateChat(ContactUtil.getUniqueIdString(messageModel.getIdentity()))
                    // F1Whisper (seventh fork review, F7-02): the row, not just the downloaded flag. See ownsCurrentRow.
                    && ownsCurrentRow(messageModel)
            ) {
                fileService.saveMedia(null, null, new CopyOnWriteArrayList<>(Collections.singletonList(messageModel)), true);
            }
        }  else if (messageClass.equals(LocationMessage.class)) {
            messageModel = saveBoxMessage((LocationMessage) message, messageModel, contactModel);
        } else if (messageClass.equals(PollSetupMessage.class)) {
            messageModel = saveBoxMessage((PollSetupMessage) message, messageModel, contactModel);
        }

        if (messageModel == null) {
            logger.info("processIncomingContactMessage: {} FAILED", message.getMessageId());
            return false;
        }

        // F1Whisper E1: freeze the timer the SENDER advertised for THIS message onto the incoming
        // model at receive time (not read time).  expireStartedAt/expiresAt NOT set here — the
        // countdown still starts in markAsRead.
        //
        // Fifth review, F5-05: only when the sender actually advertised something. The freeze that matters already
        // happened BEFORE the insert (freezeIncomingBeforeFirstWrite, on every save path above), so for an absent value
        // this call could only re-resolve the same question against a conversation timer that may have changed in the
        // meantime - a second answer to a question already answered correctly. For an explicit value it is kept, and is
        // idempotent, because it is also the correction for a countdown a racing markAsRead may have derived from the
        // provisional timer.
        if (message.getDisappearingTimerSeconds() != null) {
            applyIncomingFreeze(messageModel, message.getDisappearingTimerSeconds());
        }

        logger.info("processIncomingContactMessage: {} SUCCESS - Message ID = {}", message.getMessageId(), messageModel.getId());
        return true;
    }

    @Override
    public boolean processIncomingGroupMessage(
        @NonNull AbstractGroupMessage message,
        @NonNull TriggerSource triggerSource
    ) throws Exception {
        logger.info("processIncomingGroupMessage: {}", message.getMessageId());
        GroupMessageModel messageModel = null;

        // First of all, check if i can receive messages. Note that the common group receive steps
        // must have been executed at this point.
        GroupModelOld groupModel = groupService.getByGroupMessage(message);
        if (groupModel == null) {
            logger.error("GroupMessage {}: error: no groupModel", message.getMessageId());
            return false;
        }

        //is allowed?
        GroupAccessModel access = groupService.getAccess(groupModel, false);
        if (access == null ||
            !access.getCanReceiveMessageAccess().isAllowed()) {
            //not allowed to receive a message, ignore message but
            //set success to true (remove from server)
            logger.error("GroupMessage {}: error: not allowed", message.getMessageId());
            return true;
        }

        // is the user blocked?
        if (blockedIdentitiesService.isBlocked(message.getFromIdentity())) {
            //set success to true (remove from server)
            logger.info("GroupMessage {}: Sender is blocked, ignoring", message.getMessageId());
            return true;
        }

        // reset archived status
        groupService.setIsArchived(
            groupModel.getCreatorIdentity(),
            groupModel.getApiGroupId(),
            false,
            triggerSource
        );

        // Bump "lastUpdateAt" if necessary, depending on the message type
        //
        // Note: Do this before the message is saved! Saving the message will trigger listeners
        // that will re-sort the conversation list. At that point in time, lastUpdate should already
        // be correct.
        if (message.bumpLastUpdate()) {
            groupService.bumpLastUpdate(groupModel);
        }

        GroupMessageModel existingModel = databaseService.getGroupMessageModelFactory().getByApiMessageIdAndIdentity(
            message.getMessageId(),
            message.getFromIdentity()
        );

        if (existingModel != null) {
            if (existingModel.isSaved()) {
                // F1Whisper CHECKLIST item-edit (add / remove / reorder): the creator re-broadcasts the
                // updated checklist over the EXISTING Poll wire as a fresh GroupPollSetup carrying the
                // SAME ballot id AND the SAME carrier wire message id (so it refreshes one bubble rather
                // than spawning a new one per edit). That collides with the duplicate-message guard here.
                // For a CHECKLIST-displayType PollSetup whose ballot ALREADY EXISTS, do NOT reject as a
                // duplicate -- route it through the normal save path so it reaches
                // BallotServiceImpl.update() -> mergeChecklistUpdate() (which merges choices/order and
                // preserves votes for surviving items). Reuse the existing saved model so no duplicate
                // bubble is created. NON-checklist ballots (and every other message type) keep the
                // existing duplicate guard unchanged -- no regression.
                if (isExistingChecklistPollSetup(message)) {
                    logger.info("GroupMessage {}: checklist re-broadcast, merging into existing ballot", message.getMessageId());
                    messageModel = existingModel;
                } else {
                    // F1Whisper (fourth fork review, F4-05, fifth F5-05): same repair as the 1:1 duplicate path, and the
                    // same restriction to an explicitly advertised value. See the comment there.
                    repairDuplicateIncomingFreeze(existingModel, message.getDisappearingTimerSeconds());
                    logger.error("GroupMessage {}: error: message already exists", message.getMessageId());
                    return true;
                }
            } else {
                //use the first non saved model to edit!
                logger.error("GroupMessage {}: error: reusing unsaved model", message.getMessageId());
                messageModel = existingModel;
            }
        }

        if (message.getClass().equals(GroupTextMessage.class)) {
            messageModel = saveGroupMessage((GroupTextMessage) message, messageModel);
        } else if (message.getClass().equals(GroupImageMessage.class)) {
            messageModel = saveGroupMessage((GroupImageMessage) message, messageModel);
            // silently save to gallery if enabled
            if (messageModel != null
                && preferenceService != null
                && preferenceService.isSaveMedia()
                && messageModel.getImageData().isDownloaded()
                && !conversationCategoryService.isPrivateChat(GroupUtil.getUniqueIdString(groupModel))
                // F1Whisper (seventh fork review, F7-02): the row, not just the downloaded flag. See ownsCurrentRow.
                && ownsCurrentRow(messageModel)) {
                fileService.saveMedia(null, null, new CopyOnWriteArrayList<>(Collections.singletonList(messageModel)), true);
            }
        } else if (message.getClass().equals(GroupLocationMessage.class)) {
            messageModel = saveGroupMessage((GroupLocationMessage) message, messageModel);
        } else if (message.getClass().equals(GroupPollSetupMessage.class)) {
            messageModel = saveGroupMessage((GroupPollSetupMessage) message, messageModel);
        }

        if (messageModel != null) {
            // F1Whisper E1: freeze the timer the SENDING MEMBER advertised for this message (same
            // rationale as processIncomingContactMessage — the sender's per-message policy, not the
            // receiver's copy of the group setting). A group member turning the group timer off must
            // not retroactively un-time what other members already sent.
            //
            // Fifth review, F5-05: only when the member actually advertised something; see the 1:1 path.
            if (message.getDisappearingTimerSeconds() != null) {
                applyIncomingFreeze(messageModel, message.getDisappearingTimerSeconds());
            }
            logger.info("processIncomingGroupMessage: {} SUCCESS - Message ID = {}", message.getMessageId(), messageModel.getId());
            return true;
        } else {
            logger.info("processIncomingGroupMessage: {} FAILED", message.getMessageId());
            return false;
        }
    }

    /**
     * Process a 1:1 text message (0x01).
     */
    private MessageModel saveBoxMessage(
        @NonNull TextMessage message,
        MessageModel messageModel,
        @NonNull ContactModel contactModel
    ) {
        if (messageModel == null) {
            ContactMessageReceiver r = contactService.createReceiver(contactModel);
            messageModel = r.createLocalModel(MessageType.TEXT, MessageContentsType.TEXT, message.getDate());
            cache(messageModel);

            messageModel.setMessageId(message.getMessageId());
            messageModel.setMessageFlags(message.getMessageFlags());
            messageModel.setOutbox(false);
            // replace CR by LF for Window$ Phone compatibility - me be removed soon.
            String body = message.getText() != null ? message.getText().replace("\r", "\n") : null;

            messageModel.setBodyAndQuotedMessageId(body);
            messageModel.setIdentity(contactModel.getIdentity());
            messageModel.setForwardSecurityMode(message.getForwardSecurityMode());
            messageModel.setSaved(true);
            freezeIncomingBeforeFirstWrite(messageModel, message.getDisappearingTimerSeconds());

            databaseService.getMessageModelFactory().create(messageModel);

            fireOnNewMessage(messageModel);
        }

        return messageModel;
    }

    /**
     * Process a 1:1 poll setup message (0x15).
     */
    private MessageModel saveBoxMessage(
        @NonNull PollSetupMessage message,
        MessageModel messageModel,
        @NonNull ContactModel contactModel
    ) throws Exception {
        MessageReceiver messageReceiver = contactService.createReceiver(contactModel);
        return (MessageModel) saveBallotCreateMessage(
            messageReceiver,
            message.getMessageId(),
            message,
            messageModel,
            message.getMessageFlags(),
            message.getForwardSecurityMode(),
            message.getDisappearingTimerSeconds(),
            // Note that this may also be remote, but it is certainly never local. To be safe,
            // we use sync as this will prevent sending any csp messages.
            TriggerSource.SYNC
        );
    }

    /**
     * F1Whisper: whether an incoming group message is a re-broadcast of an ALREADY-EXISTING interactive
     * CHECKLIST (a {@link GroupPollSetupMessage} whose ballot data carries displayType == CHECKLIST and
     * whose ballot id resolves to a checklist ballot we already store). Such a re-broadcast is a
     * structure edit (add / remove / reorder), not a duplicate ballot, so the duplicate-message guard in
     * {@link #processIncomingGroupMessage} must let it through to the merge path. Returns {@code false}
     * for any non-checklist ballot (real polls keep the duplicate guard) and for any other message type.
     */
    private boolean isExistingChecklistPollSetup(@NonNull AbstractGroupMessage message) {
        if (!(message instanceof GroupPollSetupMessage)) {
            return false;
        }
        final GroupPollSetupMessage pollSetup = (GroupPollSetupMessage) message;
        final BallotData ballotData = pollSetup.getBallotData();
        // Only the CHECKLIST display type rides this merge path; a normal poll must NOT bypass the guard.
        if (ballotData == null || ballotData.getDisplayType() != BallotData.DisplayType.CHECKLIST) {
            return false;
        }
        if (pollSetup.getBallotId() == null || pollSetup.getBallotCreatorIdentity() == null) {
            return false;
        }
        // The ballot must already exist locally AND already be a checklist; otherwise treat normally.
        final BallotModel existingBallot = ballotService.get(
            pollSetup.getBallotId().toString(),
            pollSetup.getBallotCreatorIdentity()
        );
        return BallotUtil.isChecklist(existingBallot);
    }

    /**
     * F1Whisper: the 1:1 ({@link PollSetupMessage}) counterpart of {@link #isExistingChecklistPollSetup}.
     * Whether an incoming contact message is a re-broadcast of an ALREADY-EXISTING interactive
     * CHECKLIST (displayType == CHECKLIST whose ballot id resolves to a checklist we already store).
     * Such a re-broadcast is a structure edit (add / remove / reorder), so the duplicate-message guard
     * in {@link #processIncomingContactMessage} must let it through to the merge path. Returns
     * {@code false} for non-checklist ballots (real polls keep the duplicate guard) and other types.
     */
    private boolean isExistingChecklistPollSetupContact(@NonNull AbstractMessage message) {
        if (!(message instanceof PollSetupMessage)) {
            return false;
        }
        final PollSetupMessage pollSetup = (PollSetupMessage) message;
        final BallotData ballotData = pollSetup.getBallotData();
        if (ballotData == null || ballotData.getDisplayType() != BallotData.DisplayType.CHECKLIST) {
            return false;
        }
        if (pollSetup.getBallotId() == null || pollSetup.getBallotCreatorIdentity() == null) {
            return false;
        }
        final BallotModel existingBallot = ballotService.get(
            pollSetup.getBallotId().toString(),
            pollSetup.getBallotCreatorIdentity()
        );
        return BallotUtil.isChecklist(existingBallot);
    }

    private GroupMessageModel saveGroupMessage(GroupPollSetupMessage message, GroupMessageModel messageModel) throws Exception {
        GroupModelOld groupModel = groupService.getByGroupMessage(message);

        if (groupModel == null) {
            return null;
        }

        MessageReceiver messageReceiver = groupService.createReceiver(groupModel);

        return (GroupMessageModel) saveBallotCreateMessage(
            messageReceiver,
            message.getMessageId(),
            message,
            messageModel,
            message.getMessageFlags(),
            message.getForwardSecurityMode(),
            message.getDisappearingTimerSeconds(),
            // Note that this may also be remote, but it is certainly never local. To be safe,
            // we use sync as this will prevent sending any csp messages.
            TriggerSource.SYNC
        );
    }

    @Override
    public AbstractMessageModel saveBallotCreateMessage(
        @NonNull MessageReceiver<?> receiver,
        @NonNull MessageId messageId,
        @NonNull BallotSetupInterface message,
        @Nullable AbstractMessageModel messageModel,
        int messageFlags,
        @Nullable ForwardSecurityMode forwardSecurityMode,
        @NonNull TriggerSource triggerSource
    ) throws ThreemaException, BadMessageException {
        return saveBallotCreateMessage(
            receiver, messageId, message, messageModel, messageFlags, forwardSecurityMode, null, triggerSource
        );
    }

    /**
     * F1Whisper (fourth fork review, F4-05): as above, carrying the disappearing timer the sender of an INCOMING poll
     * advertised so it reaches the row's first write rather than a second one.
     */
    private AbstractMessageModel saveBallotCreateMessage(
        @NonNull MessageReceiver<?> receiver,
        @NonNull MessageId messageId,
        @NonNull BallotSetupInterface message,
        @Nullable AbstractMessageModel messageModel,
        int messageFlags,
        @Nullable ForwardSecurityMode forwardSecurityMode,
        @Nullable Integer advertisedDisappearingTimerSeconds,
        @NonNull TriggerSource triggerSource
    ) throws ThreemaException, BadMessageException {
        BallotUpdateResult result = ballotService.update(message, messageId, triggerSource);

        if (result.getBallotModel() == null) {
            throw new ThreemaException("could not create ballot model");
        }

        switch (result.getOperation()) {
            case CREATE:
            case CLOSE:
                messageModel = createNewBallotMessage(
                    messageId,
                    result.getBallotModel(),
                    (result.getOperation() == BallotUpdateResult.Operation.CREATE ?
                        BallotDataModel.Type.BALLOT_CREATED :
                        BallotDataModel.Type.BALLOT_CLOSED),
                    receiver,
                    messageFlags,
                    forwardSecurityMode,
                    advertisedDisappearingTimerSeconds);
        }

        return messageModel;
    }

    /**
     * @return {@code true} if the thumbnail was downloaded and saved
     */
    @Override
    public boolean downloadThumbnailIfPresent(@NonNull FileData fileData, @NonNull AbstractMessageModel messageModel) throws Exception {

        if (fileData.getThumbnailBlobId() == null) {
            return false;
        }

        logger.info("Downloading thumbnail of message {}", messageModel.getApiMessageId());
        final AbstractMessageModel messageModel1 = messageModel;

        // If multi-device is active, we always mark as done. Otherwise we do not mark as done if its a group message
        boolean shouldMarkAsDone = multiDeviceManager.isMultiDeviceActive() || !(messageModel instanceof GroupMessageModel);
        @Nullable BlobScope blobScopeMarkAsDone = null;
        if (shouldMarkAsDone) {
            blobScopeMarkAsDone = messageModel.getBlobScopeForMarkAsDone();
        }

        byte[] thumbnailBlob = downloadService.download(
            messageModel.getId(),
            fileData.getThumbnailBlobId(),
            messageModel.getBlobScopeForDownload(),
            blobScopeMarkAsDone,
            new ProgressListener() {
                @Override
                public void updateProgress(int progress) {
                    updateMessageLoadingProgress(messageModel1, progress);
                }

                @Override
                public void onFinished(boolean success) {
                    setMessageLoadingFinished(messageModel1);
                }
            });

        if (thumbnailBlob == null) {
            downloadService.error(messageModel.getId());
            logger.info("Error downloading thumbnail for message {}", messageModel.getApiMessageId());
            throw new ThreemaException("Error downloading thumbnail");
        }

        byte[] thumbnail = symmetricEncryptionService.decrypt(thumbnailBlob, fileData.getEncryptionKey(), ProtocolDefines.FILE_THUMBNAIL_NONCE);

        if (thumbnail != null) {
            try {
                fileService.writeConversationMediaThumbnail(messageModel, thumbnail);
            } catch (Exception exception) {
                downloadService.error(messageModel.getId());
                logger.info("Error writing thumbnail for message {}", messageModel.getApiMessageId());
                throw exception;
            }
        }

        downloadService.complete(messageModel.getId(), fileData.getThumbnailBlobId());
        return true;
    }

    private GroupMessageModel saveGroupMessage(GroupTextMessage message, GroupMessageModel messageModel) {
        GroupModelOld groupModel = groupService.getByGroupMessage(message);

        if (groupModel == null) {
            return null;
        }

        if (messageModel == null) {
            GroupMessageReceiver r = groupService.createReceiver(groupModel);
            messageModel = r.createLocalModel(MessageType.TEXT, MessageContentsType.TEXT, message.getDate());
            cache(messageModel);

            messageModel.setMessageId(message.getMessageId());
            messageModel.setMessageFlags(message.getMessageFlags());
            messageModel.setOutbox(false);
            // replace CR by LF for Window$ Phone compatibility - me be removed soon.
            String body = message.getText() != null ? message.getText().replace("\r", "\n") : null;

            messageModel.setBodyAndQuotedMessageId(body);
            messageModel.setSaved(true);
            messageModel.setIdentity(message.getFromIdentity());
            messageModel.setForwardSecurityMode(message.getForwardSecurityMode());
            freezeIncomingBeforeFirstWrite(messageModel, message.getDisappearingTimerSeconds());

            r.saveLocalModel(messageModel);

            fireOnNewMessage(messageModel);
        }

        return messageModel;
    }

    private boolean shouldAutoDownload(MessageType type) {
        if (preferenceService != null) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork != null) {
                switch (activeNetwork.getType()) {
                    case ConnectivityManager.TYPE_ETHERNET:
                        // fallthrough
                    case ConnectivityManager.TYPE_WIFI:
                        return preferenceService.getWifiAutoDownload().contains(String.valueOf(type.ordinal()));
                    case ConnectivityManager.TYPE_MOBILE:
                        return preferenceService.getMobileAutoDownload().contains(String.valueOf(type.ordinal()));
                    default:
                        break;
                }
            }
        }
        return false;
    }

    /**
     * Check if the file in question should be auto-downloaded or not
     * This depends on file type, file size and user preference (settings)
     *
     * @param messageModel AbstractMessageModel to check
     * @return true if file should be downloaded immediately, false otherwise
     */
    @Override
    public boolean shouldAutoDownload(@NonNull AbstractMessageModel messageModel) {
        MessageType type = MessageType.FILE;
        FileDataModel fileDataModel = messageModel.getFileData();

        if (fileDataModel.getRenderingType() != FileData.RENDERING_DEFAULT) {
            // treat media with default (file) rendering like a file for the sake of auto-download
            if (messageModel.getMessageContentsType() == MessageContentsType.IMAGE) {
                type = MessageType.IMAGE;
            } else if (messageModel.getMessageContentsType() == MessageContentsType.VIDEO) {
                type = MessageType.VIDEO;
            } else if (messageModel.getMessageContentsType() == MessageContentsType.VOICE_MESSAGE) {
                type = MessageType.VOICEMESSAGE;
            }
        }

        if (preferenceService != null) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork != null) {
                boolean canDownload = false;

                switch (activeNetwork.getType()) {
                    case ConnectivityManager.TYPE_ETHERNET:
                        // fallthrough
                    case ConnectivityManager.TYPE_WIFI:
                        canDownload = preferenceService.getWifiAutoDownload().contains(String.valueOf(type.ordinal()));
                        break;
                    case ConnectivityManager.TYPE_MOBILE:
                        canDownload = preferenceService.getMobileAutoDownload().contains(String.valueOf(type.ordinal()));
                        break;
                    default:
                        break;
                }

                if (canDownload) {
                    // images and voice messages are always auto-downloaded regardless of size
                    return
                        type == MessageType.IMAGE ||
                            type == MessageType.VOICEMESSAGE ||
                            fileDataModel.getFileSize() <= FILE_AUTO_DOWNLOAD_MAX_SIZE_ISO;
                }
            }
        }
        return false;
    }

    @Deprecated
    private GroupMessageModel saveGroupMessage(GroupImageMessage message, GroupMessageModel messageModel) {
        GroupModelOld groupModel = groupService.getByGroupMessage(message);

        if (groupModel == null) {
            return null;
        }

        //download thumbnail
        if (messageModel == null) {
            MessageReceiver r = groupService.createReceiver(groupModel);
            messageModel = (GroupMessageModel) r.createLocalModel(MessageType.IMAGE, MessageContentsType.IMAGE, message.getDate());
            cache(messageModel);

            messageModel.setMessageId(message.getMessageId());
            messageModel.setMessageFlags(message.getMessageFlags());
            messageModel.setOutbox(false);
            messageModel.setIdentity(message.getFromIdentity());

            messageModel.setImageData(new ImageDataModel(
                message.getBlobId(),
                message.getEncryptionKey(),
                ProtocolDefines.IMAGE_NONCE
            ));

            // Mark as saved to show message without image e.g.
            messageModel.setSaved(true);
            freezeIncomingBeforeFirstWrite(messageModel, message.getDisappearingTimerSeconds());
            r.saveLocalModel(messageModel);
        }

        fireOnNewMessage(messageModel);

        final GroupMessageModel messageModel1 = messageModel;

        if (shouldAutoDownload(MessageType.IMAGE) && !messageModel.getImageData().isDownloaded()) {

            // If multi-device is active, we always mark as done (even for a group message)
            boolean shouldMarkAsDone = multiDeviceManager.isMultiDeviceActive();
            @Nullable BlobScope blobScopeMarkAsDone = null;
            if (shouldMarkAsDone) {
                blobScopeMarkAsDone = messageModel.getBlobScopeForMarkAsDone();
            }

            byte[] blob = downloadService.download(
                messageModel.getId(),
                message.getBlobId(),
                messageModel.getBlobScopeForDownload(),
                blobScopeMarkAsDone,
                new ProgressListener() {

                    // do we really need a progress listener for images?
                    @Override
                    public void updateProgress(int progress) {
                        updateMessageLoadingProgress(messageModel1, progress);
                    }

                    @Override
                    public void onFinished(boolean success) {
                        setMessageLoadingFinished(messageModel1);
                    }
                }
            );

            if (blob != null && messageModel.getImageData().getEncryptionKey().length > 0) {
                try {
                    blob = symmetricEncryptionService.decrypt(
                        blob,
                        messageModel.getImageData().getEncryptionKey(),
                        messageModel.getImageData().getNonce()
                    );
                } catch (Exception e) {
                    blob = null;
                    logger.error("Exception", e);
                }

                if (blob != null && blob.length > 0) {

                    try {
                        if (saveStrippedImage(blob, messageModel)) {
                            // F1Whisper (sixth fork review, F6-02): the same current-row completion ownership the
                            // ordinary download path uses. This handler used to mutate the detached model, hand the
                            // whole row to a factory update that ignores its own affected-row count, and then let the
                            // caller copy the image into the device gallery - none of which asks whether the message
                            // still exists. A hard deletion during the download therefore left an orphaned file and a
                            // permanent gallery copy of content whose message was gone. A lost race now removes the
                            // media this attempt wrote and publishes nothing: no listener, no blob-complete, and no
                            // gallery copy, because the caller's model is only marked downloaded when the row was won.
                            if (!setDownloadCompleted(messageModel, messageModel.getImageData(), messageModel.getCaption())) {
                                logger.info("Legacy group image {} lost its row while downloading; publishing nothing",
                                    messageModel.getApiMessageId());
                            }
                            return messageModel;
                        }
                    } catch (Exception e) {
                        logger.error("Image save failed", e);
                    }
                } else {
                    logger.error("Invalid blob");
                }
            } else {
                logger.error("Blob is null");
            }
            downloadService.error(messageModel.getId());
        }

        // F1Whisper (sixth fork review, F6-02): the one column this tail owns, conditionally. It was a full-row update
        // whose only effect was this flag.
        applyRowUpdate(messageModel, MessageLifecycleUpdates.saved());
        messageModel.setSaved(true);

        // download failed...let adapter know
        fireOnModifiedMessage(messageModel);

        return messageModel;
    }

    @Nullable
    @WorkerThread
    private GroupMessageModel saveGroupMessage(GroupLocationMessage message, GroupMessageModel messageModel) {
        GroupModelOld groupModel = groupService.getByGroupMessage(message);
        boolean isNewMessage = false;
        if (groupModel == null) {
            return null;
        }

        MessageReceiver r = groupService.createReceiver(groupModel);

        if (messageModel == null) {
            messageModel = (GroupMessageModel) r.createLocalModel(MessageType.LOCATION, MessageContentsType.LOCATION, message.getDate());
            cache(messageModel);

            messageModel.setMessageId(message.getMessageId());
            messageModel.setMessageFlags(message.getMessageFlags());
            messageModel.setOutbox(false);
            messageModel.setIdentity(message.getFromIdentity());
            freezeIncomingBeforeFirstWrite(messageModel, message.getDisappearingTimerSeconds());

            r.saveLocalModel(messageModel);
            isNewMessage = true;
        }

        // If the location model is missing an address, we perform a lookup based on the coordinates
        @Nullable Poi effectivePoi = message.getPoi();
        if (effectivePoi == null) {
            try {
                // Will result in "Unknown address" as a fallback value
                final @NonNull String lookedUpPoiAddress = GeoLocationUtil.getAddressFromLocation(
                    context,
                    message.getLatitude(),
                    message.getLongitude()
                );
                effectivePoi = new Poi.Unnamed(lookedUpPoiAddress);
            } catch (IOException ioException) {
                logger.error("Exception", ioException);
            }
        }

        messageModel.setLocationData(new LocationDataModel(
            message.getLatitude(),
            message.getLongitude(),
            message.getAccuracy(),
            effectivePoi
        ));

        messageModel.setSaved(true);

        r.saveLocalModel(messageModel);
        if (isNewMessage) {
            fireOnNewMessage(messageModel);
        } else {
            fireOnModifiedMessage(messageModel);
        }

        return messageModel;
    }

    /**
     * Process a 1:1 image message (0x02).
     */
    @Deprecated
    private MessageModel saveBoxMessage(
        @NonNull ImageMessage message,
        MessageModel messageModel,
        @NonNull ContactModel contactModel
    ) {
        logger.info("saveBoxMessage: {}", message.getMessageId());

        logger.info("saveBoxMessage: {} - A", message.getMessageId());

        logger.info("saveBoxMessage: {} - B", message.getMessageId());

        if (messageModel == null) {
            ContactMessageReceiver r = contactService.createReceiver(contactModel);

            logger.info("saveBoxMessage: {} - C", message.getMessageId());

            messageModel = r.createLocalModel(MessageType.IMAGE, MessageContentsType.IMAGE, message.getDate());

            logger.info("saveBoxMessage: {} - D", message.getMessageId());

            messageModel.setMessageId(message.getMessageId());
            messageModel.setMessageFlags(message.getMessageFlags());
            messageModel.setOutbox(false);
            messageModel.setIdentity(contactModel.getIdentity());
            // Do not set an encryption key (asymmetric style)
            messageModel.setImageData(new ImageDataModel(message.blobId, contactModel.getPublicKey(), message.nonce));
            messageModel.setForwardSecurityMode(message.getForwardSecurityMode());

            // Mark as saved to show message without image e.g.
            messageModel.setSaved(true);
            freezeIncomingBeforeFirstWrite(messageModel, message.getDisappearingTimerSeconds());
            r.saveLocalModel(messageModel);
            /*
            //create the record
            messageModelFactory.create(messageModel);
            */
            logger.info("saveBoxMessage: {} - E", message.getMessageId());

            cache(messageModel);
        }

        fireOnNewMessage(messageModel);

        logger.info("saveBoxMessage: {} - F", message.getMessageId());

        if (shouldAutoDownload(MessageType.IMAGE) && !messageModel.getImageData().isDownloaded()) {

            // Use download class to handle failures after downloads
            byte[] imageBlob = downloadService.download(
                messageModel.getId(),
                message.blobId,
                messageModel.getBlobScopeForDownload(),
                messageModel.getBlobScopeForMarkAsDone(),
                null
            );
            if (imageBlob != null) {
                byte[] image = identityStore.decryptData(imageBlob, message.nonce, contactModel.getPublicKey());
                if (image != null) {
                    try {
                        if (saveStrippedImage(image, messageModel)) {
                            // F1Whisper (sixth fork review, F6-02): current-row completion ownership. See the group
                            // handler above for the failure this closes; the two are the same code and the same race.
                            if (!setDownloadCompleted(messageModel, messageModel.getImageData(), messageModel.getCaption())) {
                                logger.info("Legacy image {} lost its row while downloading; publishing nothing",
                                    messageModel.getApiMessageId());
                            }
                            return messageModel;
                        }
                    } catch (Exception e) {
                        logger.error("Image save failed", e);
                    }
                } else {
                    logger.error("Unable to decrypt blob for message {}", messageModel.getId());
                }
            } else {
                logger.error("Blob is null");
            }
            downloadService.error(messageModel.getId());
        }

        // F1Whisper (sixth fork review, F6-02): the one column this tail owns, conditionally.
        applyRowUpdate(messageModel, MessageLifecycleUpdates.saved());
        messageModel.setSaved(true);

        // download failed...let adapter know
        fireOnModifiedMessage(messageModel);

        return messageModel;
    }

    private boolean saveStrippedImage(byte[] image, AbstractMessageModel messageModel) throws Exception {
        boolean success = true;

        // extract caption from exif data (legacy image format only) and strip all metadata, if any
        try (ByteArrayOutputStream strippedImageOS = new ByteArrayOutputStream()) {
            try (ByteArrayInputStream originalImageIS = new ByteArrayInputStream(image)) {
                ExifInterface originalImageExif = new ExifInterface(originalImageIS);
                if (messageModel.getType() == MessageType.IMAGE) {
                    String caption = originalImageExif.getUTF8StringAttribute(ExifInterface.TAG_ARTIST);

                    if (TestUtil.isEmptyOrNull(caption)) {
                        caption = originalImageExif.getUTF8StringAttribute(ExifInterface.TAG_USER_COMMENT);
                    }

                    if (!TestUtil.isEmptyOrNull(caption)) {
                        // strip trailing zero character from EXIF, if any
                        if (caption.charAt(caption.length() - 1) == '\u0000') {
                            caption = caption.substring(0, caption.length() - 1);
                        }
                        messageModel.setCaption(caption);
                    }

                    originalImageIS.reset();
                }
                // strip all exif data while saving
                originalImageExif.saveAttributes(originalImageIS, strippedImageOS, true);
            } catch (IOException e) {
                logger.error("Exception", e);
                success = false;
            }

            // check if a file already exist
            fileService.removeMessageFiles(messageModel, true);

            logger.info("Writing image file...");
            if (success) {
                // write stripped file
                success = fileService.writeConversationMedia(messageModel, strippedImageOS.toByteArray());
            } else {
                // write original file
                success = fileService.writeConversationMedia(messageModel, image);
            }
            if (success) {
                logger.info("Image file successfully saved.");
            } else {
                logger.error("Image file save failed.");
            }
            messageModel.setSaved(true);
        }
        return success;
    }

    /**
     * Process a 1:1 location message (0x10).
     */
    @WorkerThread
    private MessageModel saveBoxMessage(
        @NonNull LocationMessage message,
        MessageModel messageModel,
        @NonNull ContactModel contactModel
    ) {
        ContactMessageReceiver r = contactService.createReceiver(contactModel);
        if (messageModel == null) {
            messageModel = r.createLocalModel(MessageType.LOCATION, MessageContentsType.LOCATION, message.getDate());
            cache(messageModel);
            messageModel.setMessageId(message.getMessageId());
            messageModel.setMessageFlags(message.getMessageFlags());
            messageModel.setOutbox(false);
        }

        messageModel.setIdentity(contactModel.getIdentity());
        messageModel.setForwardSecurityMode(message.getForwardSecurityMode());
        messageModel.setSaved(true);

        messageModel.setLocationData(
            new LocationDataModel(
                message.getLatitude(),
                message.getLongitude(),
                message.getAccuracy(),
                message.getPoi()
            )
        );

        // We save the message model already here to ensure it is in the database in case the app
        // gets killed before resolving the address.
        freezeIncomingBeforeFirstWrite(messageModel, message.getDisappearingTimerSeconds());
        databaseService.getMessageModelFactory().create(messageModel);

        // If the location model is missing an address, we perform a lookup based on the coordinates
        if (message.getPoi() == null) {
            try {
                // Will result in "Unknown address" as a fallback value
                final @NonNull String lookedUpPoiAddress = GeoLocationUtil.getAddressFromLocation(
                    context,
                    message.getLatitude(),
                    message.getLongitude()
                );

                messageModel.setLocationData(
                    new LocationDataModel(
                        message.getLatitude(),
                        message.getLongitude(),
                        message.getAccuracy(),
                        new Poi.Unnamed(lookedUpPoiAddress)
                    )
                );

                // Update the db record
                databaseService.getMessageModelFactory().update(messageModel);

            } catch (IOException ioException) {
                logger.error("Exception", ioException);
            }
        }

        fireOnNewMessage(messageModel);

        return messageModel;
    }

    @Override
    @NonNull
    public List<AbstractMessageModel> getMessagesForReceiver(@NonNull MessageReceiver receiver, MessageFilter messageFilter) {
        return getMessagesForReceiver(receiver, messageFilter, true);
    }

    @Override
    @NonNull
    public List<AbstractMessageModel> getMessagesForReceiver(@NonNull MessageReceiver receiver, MessageFilter messageFilter, boolean appendUnreadMessage) {
        final @NonNull List<AbstractMessageModel> messages = receiver.loadMessages(messageFilter);
        if (!appendUnreadMessage) {
            return messages;
        }
        switch (receiver.getType()) {
            case MessageReceiver.Type_GROUP:
            case MessageReceiver.Type_CONTACT:
                return markFirstUnread(messages);
            default:
                return messages;
        }
    }

    /**
     * Mark the first unread Message
     *
     * @param messageModels Message Models
     */
    @NonNull
    private List<AbstractMessageModel> markFirstUnread(@NonNull List<AbstractMessageModel> messageModels) {
        synchronized (messageModels) {
            // Anchor the divider immediately older than the OLDEST unread row in the window (not just
            // the newest contiguous run). Our send-time ordering can legitimately interleave a
            // read/outgoing row in the middle of a reconnect backlog of unread messages, so a scan
            // that stops at the first read/outgoing row would leave older unread rows above the
            // divider. See UnreadDividerLocator.
            final List<UnreadDividerLocator.MessageFlags> flags = new ArrayList<>(messageModels.size());
            for (AbstractMessageModel m : messageModels) {
                flags.add(m == null
                    ? UnreadDividerLocator.MessageFlags.NOT_UNREAD
                    : new UnreadDividerLocator.MessageFlags(
                        m.isOutbox(), m.isRead(), m.isStatusMessage(), m.isSaved(), m.getDeletedAt() != null));
            }

            final int insertIndex = UnreadDividerLocator.findDividerInsertIndex(flags);
            if (insertIndex > -1) {
                FirstUnreadMessageModel firstUnreadMessageModel = new FirstUnreadMessageModel();
                firstUnreadMessageModel.setCreatedAt(messageModels.get(insertIndex - 1).getCreatedAt());
                messageModels.add(insertIndex, firstUnreadMessageModel);
            }
        }

        return messageModels;
    }

    @Override
    public List<AbstractMessageModel> getMessagesForReceiver(@NonNull MessageReceiver receiver) {
        return getMessagesForReceiver(receiver, null);
    }

    @Override
    public List<AbstractMessageModel> getMessageForBallot(final BallotModel ballotModel) {
        MessageReceiver receiver = ballotService.getReceiver(ballotModel);

        if (receiver != null) {
            List<AbstractMessageModel> ballotMessages = receiver.loadMessages(new MessageFilter() {
                @Override
                public long getPageSize() {
                    return 0;
                }

                @Override
                public Integer getPageReferenceId() {
                    return null;
                }

                @Override
                public boolean withStatusMessages() {
                    return false;
                }

                @Override
                public boolean withUnsaved() {
                    return true;
                }

                @Override
                public boolean onlyUnread() {
                    return false;
                }

                @Override
                public boolean onlyDownloaded() {
                    return false;
                }

                @Override
                public MessageType[] types() {
                    return new MessageType[]{
                        MessageType.BALLOT
                    };
                }

                @Override
                public int[] contentTypes() {
                    return null;
                }

                @Override
                public int[] displayTags() {
                    return null;
                }
            });

            return ballotMessages.stream()
                .filter(model -> model.getBallotData().getBallotId() == ballotModel.getId())
                .collect(Collectors.toList());
        }
        return null;
    }

    private List<AbstractMessageModel> getContactMessagesForText(String query, boolean includeArchived, boolean starredOnly, boolean sortAscending) {
        return databaseService.getMessageModelFactory().getMessagesByText(query, includeArchived, starredOnly, sortAscending);
    }

    private List<AbstractMessageModel> getGroupMessagesForText(String query, boolean includeArchived, boolean starredOnly, boolean sortAscending) {
        return databaseService.getGroupMessageModelFactory().getMessagesByText(query, includeArchived, starredOnly, sortAscending);
    }

    @Override
    @NonNull
    public List<AbstractMessageModel> getMessagesForText(@Nullable String queryString, @MessageFilterFlags int filterFlags, boolean sortAscending) {
        List<AbstractMessageModel> messageModels = new ArrayList<>();

        boolean includeArchived = (filterFlags & FILTER_INCLUDE_ARCHIVED) == FILTER_INCLUDE_ARCHIVED;
        boolean starredOnly = (filterFlags & FILTER_STARRED_ONLY) == FILTER_STARRED_ONLY;

        if ((filterFlags & FILTER_CHATS) == FILTER_CHATS) {
            messageModels.addAll(getContactMessagesForText(queryString, includeArchived,
                starredOnly,
                sortAscending));
        }

        if ((filterFlags & FILTER_GROUPS) == FILTER_GROUPS) {
            messageModels.addAll(getGroupMessagesForText(queryString, includeArchived,
                starredOnly,
                sortAscending));
        }

        if (!messageModels.isEmpty()) {
            if (sortAscending) {
                Collections.sort(messageModels, (o1, o2) -> o1.getCreatedAt().compareTo(o2.getCreatedAt()));
            } else {
                Collections.sort(messageModels, (o1, o2) -> o2.getCreatedAt().compareTo(o1.getCreatedAt()));
            }
        }
        return messageModels;
    }

    @Override
    @WorkerThread
    public int unstarAllMessages() {
        return
            databaseService.getMessageModelFactory().unstarAllMessages() +
                databaseService.getGroupMessageModelFactory().unstarAllMessages();
    }

    @Override
    @WorkerThread
    public long countStarredMessages() throws SQLiteException {
        return
            databaseService.getMessageModelFactory().countStarredMessages() +
                databaseService.getGroupMessageModelFactory().countStarredMessages();
    }

    @Override
    @Nullable
    public MessageModel getContactMessageModel(final Integer id) {
        @Nullable MessageModel messageModel;
        synchronized (contactMessageCache) {
            messageModel = contactMessageCache.stream()
                .filter(model -> model.getId() == id)
                .findFirst()
                .orElse(null);
        }
        if (messageModel == null) {
            messageModel = databaseService.getMessageModelFactory().getById(id);
            if (messageModel != null) {
                synchronized (contactMessageCache) {
                    contactMessageCache.add(messageModel);
                }
            }
        }
        return messageModel;
    }

    private @Nullable MessageModel getContactMessageModel(
        @NonNull final String apiMessageId,
        @NonNull ContactMessageReceiver messageReceiver
    ) {
        MessageModel model;
        synchronized (contactMessageCache) {
            model = contactMessageCache.stream()
                .filter(messageModel ->
                    apiMessageId.equals(messageModel.getApiMessageId())
                        && messageReceiver.getContact().getIdentity().equals(messageModel.getIdentity())
                )
                .findFirst()
                .orElse(null);
        }
        if (model == null) {
            try {
                model = databaseService.getMessageModelFactory().getByApiMessageIdAndIdentity(
                    new MessageId(Utils.hexStringToByteArray(apiMessageId)),
                    messageReceiver.getContact().getIdentity()
                );
                if (model != null) {
                    synchronized (contactMessageCache) {
                        contactMessageCache.add(model);
                    }
                }
            } catch (IllegalArgumentException ignore) {
                logger.warn("Encountered invalid message ID in contact message");
            }
        }
        return model;
    }

    @Nullable
    @Override
    public MessageModel getContactMessageModel(final String uid) {
        return databaseService.getMessageModelFactory().getByUid(uid);
    }

    @Override
    @Nullable
    public GroupMessageModel getGroupMessageModel(final Integer id) {
        synchronized (groupMessageCache) {
            GroupMessageModel groupMessageModel = groupMessageCache.stream()
                .filter(model -> model.getId() == id)
                .findFirst()
                .orElse(null);
            if (groupMessageModel == null) {
                groupMessageModel = databaseService.getGroupMessageModelFactory().getById(id);
                if (groupMessageModel != null) {
                    groupMessageCache.add(groupMessageModel);
                }
            }
            return groupMessageModel;
        }
    }

    @Nullable
    @Override
    public GroupMessageModel getGroupMessageModel(final String uid) {
        return databaseService.getGroupMessageModelFactory().getByUid(uid);
    }

    private GroupMessageModel getGroupMessageModel(
        @NonNull final String apiMessageId,
        @NonNull GroupMessageReceiver messageReceiver
    ) {
        int groupId = messageReceiver.getGroup().getId();
        synchronized (groupMessageCache) {
            GroupMessageModel groupMessageModel = groupMessageCache.stream()
                .filter(messageModel ->
                    apiMessageId.equals(messageModel.getApiMessageId())
                        && groupId == messageModel.getGroupId()
                )
                .findFirst()
                .orElse(null);

            if (groupMessageModel == null) {
                try {
                    groupMessageModel = databaseService.getGroupMessageModelFactory().getByApiMessageIdAndGroupId(
                        new MessageId(Utils.hexStringToByteArray(apiMessageId)),
                        groupId
                    );
                    if (groupMessageModel != null) {
                        groupMessageCache.add(groupMessageModel);
                    }
                } catch (IllegalArgumentException ignore) {
                    logger.warn("Encountered invalid message ID in group message");
                }
            }
            return groupMessageModel;
        }
    }

    @Override
    @Nullable
    public DistributionListMessageModel getDistributionListMessageModel(long id) {
        return databaseService.getDistributionListMessageModelFactory().getById(id);
    }

    private void fireOnNewMessage(final AbstractMessageModel messageModel) {

        if (appLockService.isLocked()) {

            //do not fire messages, wait until app is unlocked
            appLockService.addOnLockAppStateListener(locked -> !locked);

        }
        fireOnCreatedMessage(messageModel);
    }

    @Override
    public MessageString getMessageString(AbstractMessageModel messageModel, int maxLength) {
        return getMessageString(messageModel, maxLength, true);
    }

    @NonNull
    @Override
    public MessageString getMessageString(AbstractMessageModel messageModel, int maxLength, boolean withPrefix) {
        boolean isPrivate;
        String prefix = "";

        if (messageModel instanceof GroupMessageModel) {
            //append Username
            if (withPrefix) {
                var shortName = NameUtil.getShortName(context, messageModel, contactService, preferenceService.getContactNameFormat());
                if (shortName != null) {
                    prefix = shortName + ": ";
                }
            }
            final GroupModelOld groupModel = groupService.getById(((GroupMessageModel) messageModel).getGroupId());
            isPrivate = conversationCategoryService.isPrivateChat(GroupUtil.getUniqueIdString(groupModel));
        } else {
            final String identity = messageModel.getIdentity();
            if (identity != null) {
                isPrivate = conversationCategoryService.isPrivateChat(ContactUtil.getUniqueIdString(messageModel.getIdentity()));
            } else {
                logger.error("The identity of the message model is null");
                isPrivate = false;
            }
        }

        if (isPrivate) {
            return new MessageString(context.getString(R.string.new_messages_locked));
        }

        if (messageModel.isDeleted()) {
            return new MessageString(context.getString(R.string.message_was_deleted));
        }

        switch (messageModel.getType()) {
            case TEXT:
                @Nullable String messageText = QuoteUtil.getMessageBody(
                    messageModel.getType(),
                    messageModel.getBody(),
                    messageModel.getCaption(),
                    messageModel.isOutbox(),
                    false,
                    preferenceService.getContactNameFormat()
                );
                String rawMessageText = prefix + messageText;
                if (maxLength > 0 && messageText != null && messageText.length() > maxLength) {
                    messageText = messageText.substring(0, maxLength - 3) + "...";
                }
                return new MessageString(messageText, rawMessageText);
            case VIDEO:
                return new MessageString(prefix + context.getString(R.string.video_placeholder));
            case LOCATION:
                String locationString = prefix + context.getString(R.string.location_placeholder);
                final @NonNull LocationDataModel locationDataModel = messageModel.getLocationData();
                if (locationDataModel.poiNameOrNull != null) {
                    locationString += ": " + locationDataModel.poiNameOrNull;
                }
                return new MessageString(locationString);
            case VOICEMESSAGE:
                String messageString = prefix + context.getString(R.string.audio_placeholder);
                messageString += " (" + ElapsedTimeFormatter.secondsToString(messageModel.getAudioData().getDuration()) + ")";
                return new MessageString(messageString);
            case FILE:
                if (MimeUtil.isImageFile(messageModel.getFileData().getMimeType())) {
                    if (TestUtil.isEmptyOrNull(messageModel.getCaption())) {
                        return new MessageString(prefix + context.getString(R.string.image_placeholder));
                    } else {
                        return new MessageString(prefix + context.getString(R.string.image_placeholder) + ": " + messageModel.getFileData().getCaption());
                    }
                } else if (MimeUtil.isVideoFile(messageModel.getFileData().getMimeType())) {
                    if (TestUtil.isEmptyOrNull(messageModel.getFileData().getCaption())) {
                        String durationString = messageModel.getFileData().getDurationString();
                        return new MessageString(prefix + context.getString(R.string.video_placeholder) + " (" + durationString + ")");
                    } else {
                        return new MessageString(prefix + context.getString(R.string.video_placeholder) + ": " + messageModel.getFileData().getCaption());
                    }
                } else if (MimeUtil.isAudioFile(messageModel.getFileData().getMimeType())) {
                    if (TestUtil.isEmptyOrNull(messageModel.getFileData().getCaption())) {
                        String durationString = messageModel.getFileData().getDurationString();
                        if ("00:00".equals(durationString)) {
                            return new MessageString(prefix + context.getString(R.string.audio_placeholder));
                        } else {
                            return new MessageString(prefix + context.getString(R.string.audio_placeholder) + " (" + durationString + ")");
                        }
                    } else {
                        return new MessageString(prefix + context.getString(R.string.audio_placeholder) + ": " + messageModel.getFileData().getCaption());
                    }
                } else {
                    if (TestUtil.isEmptyOrNull(messageModel.getFileData().getCaption())) {
                        return new MessageString(prefix + context.getString(R.string.file_placeholder) + ": " + messageModel.getFileData().getFileName());
                    } else {
                        return new MessageString(prefix + context.getString(R.string.file_placeholder) + ": " + messageModel.getFileData().getCaption());
                    }
                }
            case IMAGE:
                if (TestUtil.isEmptyOrNull(messageModel.getCaption())) {
                    return new MessageString(prefix + context.getString(R.string.image_placeholder));
                } else {
                    return new MessageString(prefix + context.getString(R.string.image_placeholder) + ": " + messageModel.getCaption());
                }
            case BALLOT:
                @NonNull String ballotNotificationString = "";
                if (messageModel.getBody() != null && !messageModel.getBody().isEmpty()) {
                    final @NonNull BallotDataModel ballotDataModel = BallotDataModel.create(messageModel.getBody());
                    ballotNotificationString = BallotUtil.getNotificationString(context, ballotDataModel.getBallotId());
                }
                return new MessageString(
                    prefix + context.getString(R.string.ballot_placeholder) + ": " + ballotNotificationString
                );
            case VOIP_STATUS:
                return new MessageString(
                    prefix + MessageUtil.getViewElement(context, messageModel, preferenceService.getContactNameFormat()).placeholder
                );
            default:
                return new MessageString(prefix);
        }
    }

    @Override
    public void saveIncomingServerMessage(final ServerMessageModel msg) {
        // Store server message into database
        serverMessageModelFactory.storeServerMessageModel(msg);
        // Show as alert
        ListenerManager.serverMessageListeners.handle(listener -> {
            if (msg.getType() == ServerMessageModel.TYPE_ALERT) {
                listener.onAlert(msg);
            } else {
                listener.onError(msg);
            }
        });
    }

    @Override
    public boolean downloadMediaMessage(
        @Nullable AbstractMessageModel mediaMessageModel,
        @Nullable ProgressListener progressListener
    ) throws Exception {
        if (!MessageUtil.hasDataFile(mediaMessageModel)) {
            throw new ThreemaException("message is not a media message");
        }

        MediaMessageDataInterface data = getDataForMessageType(mediaMessageModel);

        if (data != null && !data.isDownloaded()) {
            if (downloadAndWriteMediaData(mediaMessageModel, data, progressListener)) {
                if (!setDownloadCompleted(mediaMessageModel, data)) {
                    // F1Whisper (fifth fork review, F5-04): the row lost a deletion race while this download was
                    // running, so nothing may be published from it. Reporting failure here is what keeps the gallery
                    // save, the completion listener and the download-complete notification from firing for content whose
                    // message no longer exists.
                    return false;
                }
                saveImagesAndVideosToGalleryIfEnabled(mediaMessageModel, data);
                return true;
            } else {
                logger.error("Decryption failed");
                this.downloadService.error(mediaMessageModel.getId());
                throw new ThreemaException("Decryption failed");
            }
        }
        return false;
    }

    private @Nullable MediaMessageDataInterface getDataForMessageType(
        @NonNull AbstractMessageModel mediaMessageModel
    ) {
        switch (mediaMessageModel.getType()) {
            case IMAGE:
                return mediaMessageModel.getImageData();
            case VIDEO:
                return mediaMessageModel.getVideoData();
            case VOICEMESSAGE:
                return mediaMessageModel.getAudioData();
            case FILE:
                return mediaMessageModel.getFileData();
            default:
                return null;
        }
    }

    private @NonNull
    byte[] getNonceForMessageType(@NonNull MessageType messageType) throws ThreemaException {
        switch (messageType) {
            case IMAGE:
                return ProtocolDefines.IMAGE_NONCE;
            case VIDEO:
                return ProtocolDefines.VIDEO_NONCE;
            case VOICEMESSAGE:
                return ProtocolDefines.AUDIO_NONCE;
            case FILE:
                return ProtocolDefines.FILE_NONCE;
            default:
                throw new ThreemaException("Could not get nonce for messageType=" + messageType);
        }
    }

    private boolean downloadAndWriteMediaData(
        @NonNull AbstractMessageModel mediaMessageModel,
        @NonNull MediaMessageDataInterface data,
        @Nullable ProgressListener progressListener
    ) throws ThreemaException {
        if (mediaMessageModel.getType() != MessageType.IMAGE) {
            var messageUid = mediaMessageModel.getUid();
            if (messageUid != null && fileService.hasMessageFile(messageUid)) {
                return true;
            }
        }

        // If multi-device is active, we always mark as done. Otherwise we do not mark as done if its a group message
        boolean shouldMarkAsDone = multiDeviceManager.isMultiDeviceActive() || !(mediaMessageModel instanceof GroupMessageModel);
        @Nullable BlobScope blobScopeMarkAsDone = null;
        if (shouldMarkAsDone) {
            blobScopeMarkAsDone = mediaMessageModel.getBlobScopeForMarkAsDone();
        }

        byte[] blob = downloadService.download(
            mediaMessageModel.getId(),
            data.getBlobId(),
            mediaMessageModel.getBlobScopeForDownload(),
            blobScopeMarkAsDone,
            progressListener
        );
        if (blob == null || blob.length < NaCl.BOX_OVERHEAD_BYTES) {
            logger.error("Blob for message {} is empty", mediaMessageModel.getApiMessageId());

            downloadService.error(mediaMessageModel.getId());
            // blob download failed or empty or canceled
            throw new ThreemaException("failed to download message");
        }

        boolean success = mediaMessageModel.getType() != MessageType.IMAGE
            ? decryptNonImageMediaDataAndWriteConversationMedia(mediaMessageModel, data, blob)
            : decryptImageAndWriteConversationMedia(mediaMessageModel, blob);

        if (success && !fileService.hasMessageThumbnail(mediaMessageModel)) {
            createAndWriteMediaThumbnail(mediaMessageModel);
        }
        return success;
    }

    /**
     * F1Whisper (fifth fork review, F5-04): record that the media arrived, as a conditional non-inserting write that owns
     * the download metadata and nothing else.
     *
     * <p>The defect: a download takes as long as it takes, and this completion used to mutate the caller's DETACHED model
     * and full-row-save it. A message hard-deleted, deleted for everyone, first-read or burned while the bytes were in
     * flight therefore had that stale row written back over the top - recreated in the first case, un-deleted in the
     * second, and in the others silently reverted to whatever download-era values the instance carried.</p>
     *
     * <p>Now the flag is applied to the CURRENT row and re-applied on a lost race. If the row is gone or has been deleted
     * for everyone there is nothing to record, and the media this attempt just wrote is deleted rather than left orphaned
     * on disk - operation-owned by construction, because the files are keyed by the uid of the row that has gone, and a
     * replacement row would have a different one.</p>
     *
     * @return whether the completion was recorded. A {@code false} answer means the caller must publish nothing.
     */
    private boolean setDownloadCompleted(@NonNull AbstractMessageModel mediaMessageModel, @NonNull MediaMessageDataInterface data) {
        return setDownloadCompleted(mediaMessageModel, data, null);
    }

    /**
     * @param extractedCaption a caption recovered from the media itself during this download (the legacy image format
     *                         carries one in its EXIF), written in the same conditional statement, or {@code null}.
     */
    private boolean setDownloadCompleted(
        @NonNull AbstractMessageModel mediaMessageModel,
        @NonNull MediaMessageDataInterface data,
        @Nullable String extractedCaption
    ) {
        final boolean recorded = updateMediaMetadata(mediaMessageModel, current -> {
            final MediaMessageDataInterface currentData = getDataForMessageType(current);
            if (currentData == null) {
                return false;
            }
            boolean changed = false;
            if (extractedCaption != null && !TestUtil.compare(extractedCaption, current.getCaption())) {
                current.setCaption(extractedCaption);
                changed = true;
            }
            if (currentData.isDownloaded()) {
                return changed;
            }
            currentData.isDownloaded(true);
            current.writeDataModelToBody();
            return true;
        });

        if (!recorded) {
            final AbstractMessageModel current = reloadPersistedModel(mediaMessageModel);
            if (current == null || current.getDeletedAt() != null) {
                logger.info("Download for uid={} lost a deletion race; removing the media it had written",
                    mediaMessageModel.getUid());
                try {
                    fileService.removeMessageFiles(mediaMessageModel, true);
                } catch (Exception e) {
                    logger.warn("Could not remove media written for a message that no longer exists", e);
                }
                return false;
            }
            final MediaMessageDataInterface currentData = getDataForMessageType(current);
            if (currentData != null && currentData.isDownloaded()) {
                // Another downloader recorded the same arrival first. The files on disk belong to this still-current row,
                // so they are ITS media and must not be cleaned up; there is simply nothing left for this attempt to do.
                mediaMessageModel.adoptPersistedBody(current.getBody());
                logger.info("Download for uid={} was already recorded by another attempt", mediaMessageModel.getUid());
                return true;
            }
            return false;
        }

        fireOnModifiedMessage(mediaMessageModel);

        downloadService.complete(mediaMessageModel.getId(), data.getBlobId());
        return true;
    }

    /**
     * F1Whisper (seventh fork review, F7-02): whether {@code model}'s row is still there and still undeleted, asked
     * under the monitor deletion takes.
     *
     * <p>A gallery export writes a permanent CLEAR copy outside the message lifecycle: nothing in the app can take it
     * back, so it must not be started for a message that is already being deleted. Deletion claims the row under this
     * same monitor before it removes anything, so an export that asks here after that claim sees the row gone. The
     * residual window - deletion claiming between this answer and the asynchronous copy actually reading the file - is
     * closed by the file removal that deletion performs immediately after its claim: the copy then finds nothing to
     * read and fails.</p>
     */
    private boolean ownsCurrentRow(@NonNull AbstractMessageModel model) {
        final Collection<? extends AbstractMessageModel> cache = cacheFor(model);
        if (cache == null) {
            return false;
        }
        synchronized (cache) {
            final AbstractMessageModel current = reloadPersistedModel(model);
            return current != null && current.getDeletedAt() == null;
        }
    }

    private void saveImagesAndVideosToGalleryIfEnabled(@NonNull AbstractMessageModel mediaMessageModel, @NonNull MediaMessageDataInterface data) {
        if (preferenceService != null
            && preferenceService.isSaveMedia()
            && isImageOrVideoFile(mediaMessageModel, data)
            && ownsCurrentRow(mediaMessageModel)) {
            boolean isPrivate = mediaMessageModel instanceof GroupMessageModel
                ? conversationCategoryService.isPrivateChat(GroupUtil.getUniqueIdString(((GroupMessageModel) mediaMessageModel).getGroupId()))
                : conversationCategoryService.isPrivateChat(ContactUtil.getUniqueIdString(mediaMessageModel.getIdentity()));

            if (!isPrivate) {
                fileService.saveMedia(null, null, new CopyOnWriteArrayList<>(Collections.singletonList(mediaMessageModel)), true);
            }
        }
    }

    private boolean isImageOrVideoFile(@NonNull AbstractMessageModel mediaMessageModel, @NonNull MediaMessageDataInterface data) {
        MessageType type = mediaMessageModel.getType();
        return type == MessageType.IMAGE
            || type == MessageType.VIDEO
            || (type == MessageType.FILE && FileUtil.isImageOrVideoFile((FileDataModel) data));
    }

    private boolean decryptNonImageMediaDataAndWriteConversationMedia(
        @NonNull AbstractMessageModel messageModel,
        @NonNull MediaMessageDataInterface data,
        @NonNull byte[] blob
    ) throws ThreemaException {
        logger.info("Decrypting blob for message {}", messageModel.getApiMessageId());

        byte[] nonce = getNonceForMessageType(messageModel.getType());

        try {
            symmetricEncryptionService.decryptInplace(blob, data.getEncryptionKey(), nonce);
        } catch (IllegalArgumentException | CryptoException exception) {
            throw new ThreemaException("Unable to decrypt media", exception);
        }
        logger.info("Write conversation media for message {}", messageModel.getApiMessageId());

        // save the file
        try {
            if (fileService.writeConversationMedia(messageModel, blob, 0, blob.length - NaCl.BOX_OVERHEAD_BYTES, true)) {
                logger.info("Media for message {} successfully saved.", messageModel.getApiMessageId());
                return true;
            }
        } catch (Exception e) {
            logger.warn("Unable to save media");

            downloadService.error(messageModel.getId());

            throw new ThreemaException("Unable to save media");
        }
        return false;
    }

    private boolean decryptImageAndWriteConversationMedia(
        @NonNull AbstractMessageModel messageModel,
        @NonNull byte[] blob
    ) {
        ImageDataModel imageData = messageModel.getImageData();
        byte[] image = null;
        try {
            image = messageModel instanceof GroupMessageModel
                ? NaCl.symmetricDecryptData(blob, imageData.getEncryptionKey(), ProtocolDefines.IMAGE_NONCE)
                : identityStore.decryptData(blob, imageData.getNonce(), imageData.getEncryptionKey());
        } catch (CryptoException cryptoException) {
            logger.error("Failed to decrypt image data", cryptoException);
        }

        if (image != null && image.length > 0) {
            try {
                // save the file
                return saveStrippedImage(image, messageModel);
            } catch (Exception e) {
                logger.error("Exception", e);
            }
        }
        return false;
    }

    private void createAndWriteMediaThumbnail(@NonNull AbstractMessageModel messageModel) {
        if (!MessageUtil.canHaveThumbnailFile(messageModel)) {
            // ignore messages that cannot have a thumbnail
            return;
        }

        try {
            File file = fileService.getDecryptedMessageFile(messageModel);
            byte[] thumbnailData = ThumbnailUtil.generateThumbnailData(context, getMimeTypeString(messageModel), file);
            if (thumbnailData != null) {
                fileService.writeConversationMediaThumbnail(messageModel, thumbnailData);
            }
        } catch (Exception e) {
            logger.error("Could not write conversation media thumbnail", e);
        }
    }

    @Override
    public boolean cancelMessageDownload(AbstractMessageModel messageModel) {
        return downloadService.cancel(messageModel.getId());
    }

    private void fireOnCreatedMessage(final AbstractMessageModel messageModel) {
        logger.debug("fireOnCreatedMessage for message {}", messageModel.getApiMessageId());
        ListenerManager.messageListeners.handle(listener -> listener.onNew(messageModel));
    }

    private void fireOnModifiedMessage(final AbstractMessageModel messageModel) {
        ListenerManager.messageListeners.handle(listener -> {
            List<AbstractMessageModel> list = new ArrayList<>();
            list.add(messageModel);

            listener.onModified(list);
        });
    }

    private void fireOnMessageDeletedForAll(final AbstractMessageModel messageModel) {
        ListenerManager.messageDeletedForAllListener.handle(listener -> listener.onDeletedForAll(messageModel));
    }

    private void fireOnEditMessage(final AbstractMessageModel messageModel) {
        ListenerManager.editMessageListener.handle(listener -> listener.onEdit(messageModel));
    }

    private void fireOnRemovedMessage(final AbstractMessageModel messageModel) {
        ListenerManager.messageListeners.handle(listener -> listener.onRemoved(messageModel));
    }

    private void setMessageLoadingFinished(AbstractMessageModel messageModel) {
        loadingProgress.delete(messageModel.getId());
        cancelUploader(messageModel);
    }

    private void updateMessageLoadingProgress(final AbstractMessageModel messageModel, final int progress) {
        loadingProgress.put(messageModel.getId(), progress);

        //handle progress
        ListenerManager.messageListeners.handle(listener -> listener.onProgressChanged(messageModel, progress));
    }

    @Override
    public void removeAll() throws SQLException, IOException, ThreemaException {
        //use the fast way
        databaseService.getMessageModelFactory().deleteAll();
        databaseService.getGroupMessageModelFactory().deleteAll();
        databaseService.getDistributionListMessageModelFactory().deleteAll();

        //clear all caches
        synchronized (contactMessageCache) {
            contactMessageCache.clear();
        }

        //clear all caches
        synchronized (groupMessageCache) {
            groupMessageCache.clear();
        }

        //clear all caches
        synchronized (distributionListMessageCache) {
            distributionListMessageCache.clear();
        }

        //clear all media files
        fileService.deleteMediaFiles();
    }

    /**
     * F1Whisper (seventh fork review, F7-01): persist, and only then cache.
     *
     * <p>The defect this closes: the sixth review taught {@code createOrUpdate} to refuse to reinsert a row that had
     * gone, and this method threw that answer away. It cached the supplied model regardless, and the service's id
     * getters read the cache BEFORE the database, so a persistent outgoing task loading its message by local id got the
     * detached model back - body, blob id and encryption key intact - for a message the user had already deleted, and
     * sent it. The database was empty throughout, so nothing in the UI could show that the deleted payload had left the
     * device. A non-reinsertion guard is not a deletion boundary while a cache-backed sender can continue from the same
     * deleted model.</p>
     *
     * <p>So a failed save now EVICTS instead of admitting: every cached instance of that id goes, and the caller is
     * told. The persistence attempt and the cache reconciliation are one operation under the same per-type monitor
     * {@link #applyRowUpdate} takes, so a conditional lifecycle write can neither interleave with the write nor observe
     * a cache that disagrees with the row.</p>
     */
    @Override
    public boolean save(final AbstractMessageModel messageModel) {
        if (messageModel == null) {
            return false;
        }
        final Collection<? extends AbstractMessageModel> cache = cacheFor(messageModel);
        if (cache == null) {
            return false;
        }
        synchronized (cache) {
            final boolean persisted;
            if (messageModel instanceof GroupMessageModel) {
                persisted = databaseService.getGroupMessageModelFactory().createOrUpdate((GroupMessageModel) messageModel);
            } else if (messageModel instanceof DistributionListMessageModel) {
                persisted = databaseService.getDistributionListMessageModelFactory()
                    .createOrUpdate((DistributionListMessageModel) messageModel);
            } else {
                persisted = databaseService.getMessageModelFactory().createOrUpdate((MessageModel) messageModel);
            }

            if (!persisted) {
                // F1Whisper (eighth fork review, H8-01): "could not be persisted" now covers a row that is still there
                // but has been deleted for everyone. The content of a deleted message may not be written back, and a
                // model holding that content may not be cached, whichever of the two happened.
                logger.info("Not caching message {}: its row is gone or was deleted", messageModel.getId());
            }
            // Every OTHER live instance of this row now holds a pre-save snapshot, so on success it adopts what was
            // just written - the same reconciliation a conditional write performs, through the same code. It used to be
            // three different rules: the contact cache dropped stale instances, the group and distribution-list caches
            // copied a hand-maintained SUBSET of the columns across. That subset had drifted; GroupMessageModel.copyFrom
            // carries none of this fork's disappearing or display-tag columns, so a cached group instance kept a stale
            // timer after every full-row save.
            return MessageCacheCoherence.admit(
                cache,
                messageModel,
                persisted,
                !(messageModel instanceof DistributionListMessageModel)
            );
        }
    }

    @Override
    public long getTotalMessageCount() {
        //simple count
        return databaseService.getMessageModelFactory().count()
            + databaseService.getGroupMessageModelFactory().count()
            + databaseService.getDistributionListMessageModelFactory().count();

    }

    @NonNull
    private String getMimeTypeString(AbstractMessageModel model) {
        @Nullable MessageType type = model.getType();

        if (type == null) {
            logger.error("No message type set for message {} use fall back mime type '{}'", model.getApiMessageId(), MimeUtil.MIME_TYPE_ANY);
            return MimeUtil.MIME_TYPE_ANY;
        }

        switch (type) {
            case VIDEO:
                return MimeUtil.MIME_TYPE_VIDEO;
            case FILE:
                return model.getFileData().getMimeType();
            case VOICEMESSAGE:
                return MimeUtil.MIME_TYPE_AUDIO;
            case IMAGE:
                return MimeUtil.MIME_TYPE_IMAGE_JPEG;
            default:
                return MimeUtil.MIME_TYPE_ANY;
        }
    }

    private String getLeastCommonDenominatorMimeType(ArrayList<AbstractMessageModel> models) {
        String mimeType = getMimeTypeString(models.get(0));

        if (models.size() > 1) {
            for (int i = 1; i < models.size(); i++) {
                mimeType = MimeUtil.getCommonMimeType(mimeType, getMimeTypeString(models.get(i)));
            }
        }

        return mimeType;
    }

    @Override
    public boolean shareMediaMessages(final Context context, ArrayList<AbstractMessageModel> models, ArrayList<Uri> shareFileUris, String caption) {
        if (context != null && models != null && shareFileUris != null) {
            if (!models.isEmpty() && !shareFileUris.isEmpty()) {
                Intent intent;
                if (models.size() == 1) {
                    AbstractMessageModel model = models.get(0);
                    Uri shareFileUri = shareFileUris.get(0);

                    if (shareFileUri == null) {
                        logger.info("No file to share");
                        return false;
                    }

                    intent = new Intent(Intent.ACTION_SEND);
                    intent.putExtra(Intent.EXTRA_STREAM, shareFileUri);
                    intent.setType(getMimeTypeString(model));
                    if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(shareFileUri.getScheme())) {
                        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    }
                    if (!TestUtil.isEmptyOrNull(caption)) {
                        intent.putExtra(Intent.EXTRA_TEXT, caption);
                    }
                } else {
                    intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareFileUris);

                    Uri firstShareFileUri = shareFileUris.get(0);

                    intent.setType(getLeastCommonDenominatorMimeType(models));
                    if (firstShareFileUri != null) {
                        if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(firstShareFileUri.getScheme())) {
                            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        }
                    }
                }

                try {
                    context.startActivity(Intent.createChooser(intent, context.getResources().getText(R.string.share_via)));

                    return true;
                } catch (ActivityNotFoundException e) {
                    // make sure Toast runs in UI thread
                    RuntimeUtil.runOnUiThread(() -> Toast.makeText(context, R.string.no_activity_for_mime_type, Toast.LENGTH_SHORT).show());
                }
            }
        }
        return false;
    }

    @Override
    public boolean viewMediaMessage(final Context context, AbstractMessageModel model, Uri uri) {
        if (context != null && model != null && uri != null) {
            Intent intent = new Intent(Intent.ACTION_VIEW);

            String mimeType = getMimeTypeString(model);
            if (mimeType.isEmpty()) {
                logger.warn("Mime type is empty for message {}", model.getApiMessageId());
            }
            if (MimeUtil.isImageFile(mimeType)) {
                // some viewers cannot handle image/gif - give them a generic mime type
                mimeType = MimeUtil.MIME_TYPE_IMAGE;
            }
            intent.setDataAndType(uri, mimeType);
            if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme())) {
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            } else if (!(context instanceof Activity)) {
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }

            try {
                context.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                // make sure Toast runs in UI thread
                RuntimeUtil.runOnUiThread(() -> Toast.makeText(context, R.string.no_activity_for_mime_type, Toast.LENGTH_SHORT).show());
            } catch (SecurityException e) {
                logger.error("Error firing ACTION_VIEW intent", e);
                RuntimeUtil.runOnUiThread(() -> Toast.makeText(context, context.getString(R.string.no_activity_for_mime_type) + " " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }
        return false;
    }

    @Override
    public boolean shareTextMessage(Context context, AbstractMessageModel messageModel) {
        if (messageModel != null) {
            String text = "";

            Intent intent = new Intent();
            if (messageModel.getType() == MessageType.LOCATION) {
                Uri locationUri = GeoLocationUtil.getLocationUri(messageModel);

                final @NonNull LocationDataModel locationDataModel = messageModel.getLocationData();
                if (locationDataModel.poiAddressOrNull != null) {
                    text = locationDataModel.poiAddressOrNull + " - ";
                }
                text += locationUri.toString();
            } else {
                text = QuoteUtil.getMessageBody(
                    messageModel.getType(),
                    messageModel.getBody(),
                    messageModel.getCaption(),
                    messageModel.isOutbox(),
                    false,
                    preferenceService.getContactNameFormat()
                );
            }

            intent.setAction(Intent.ACTION_SEND);
            intent.putExtra(android.content.Intent.EXTRA_TEXT, text);
            intent.setType(MimeUtil.MIME_TYPE_TEXT);

            try {
                context.startActivity(Intent.createChooser(intent, context.getResources().getText(R.string.share_via)));
            } catch (Exception e) {
                Toast.makeText(context, R.string.no_activity_for_mime_type, Toast.LENGTH_LONG).show();
                logger.error("Exception", e);
            }
        }
        return false;
    }

    @Override
    public void markConversationAsRead(@NonNull MessageReceiver messageReceiver, NotificationService notificationService) {
        @SuppressWarnings("unchecked")
        List<AbstractMessageModel> unreadMessages = messageReceiver.loadMessages(new MessageService.MessageFilter() {
            @Override
            public long getPageSize() {
                return 0;
            }

            @Override
            public Integer getPageReferenceId() {
                return null;
            }

            @Override
            public boolean withStatusMessages() {
                return false;
            }

            @Override
            public boolean withUnsaved() {
                return false;
            }

            @Override
            public boolean onlyUnread() {
                return true;
            }

            @Override
            public boolean onlyDownloaded() {
                return false;
            }

            @Override
            public MessageType[] types() {
                return null;
            }

            @Override
            public int[] contentTypes() {
                return null;
            }

            @Override
            public int[] displayTags() {
                return null;
            }
        });

        new MarkAsReadRoutine(this, notificationService)
            .run(unreadMessages, messageReceiver);
        notificationService.cancel(messageReceiver);
    }

    @Override
    public AbstractMessageModel getMessageModelFromId(int id, String type) {
        if (id != 0 && !TestUtil.isEmptyOrNull(type)) {
            if (type.equals(MessageModel.class.toString())) {
                return getContactMessageModel(id);
            } else if (type.equals(GroupMessageModel.class.toString())) {
                return getGroupMessageModel(id);
            } else if (type.equals(DistributionListMessageModel.class.toString())) {
                return getDistributionListMessageModel(id);
            }
        }
        return null;
    }

    @Override
    @Nullable
    public AbstractMessageModel getMessageModelByApiMessageIdAndReceiver(
        @Nullable String apiMessageId,
        @NonNull MessageReceiver messageReceiver
    ) {
        if (apiMessageId != null) {
            if (messageReceiver instanceof ContactMessageReceiver) {
                return getContactMessageModel(apiMessageId, (ContactMessageReceiver) messageReceiver);
            } else if (messageReceiver instanceof GroupMessageReceiver) {
                return getGroupMessageModel(apiMessageId, (GroupMessageReceiver) messageReceiver);
            } else if (messageReceiver instanceof DistributionListMessageReceiver) {
                // We cannot return a message model with a certain api message id for distribution
                // lists, because the api message id is null for all distribution list messages
                return null;
            }
        }
        return null;
    }

    /*******************************************************************************************
     * Uploader Cache (used to cancel running downloads)
     *******************************************************************************************/

    private final Map<String, BlobUploader> uploaders = new ArrayMap<>();
    private final Map<String, WeakReference<VideoTranscoder>> videoTranscoders = new ArrayMap<>();


    /**
     * Create a new BlobUploader. An existing uploader will be canceled.
     */
    @NonNull
    private BlobUploader initUploader(
        AbstractMessageModel messageModel,
        byte[] data,
        @NonNull MessageReceiver<?> messageReceiver
    ) throws ThreemaException {
        synchronized (uploaders) {
            final @NonNull String key = cancelUploader(messageModel);

            final boolean isNotesGroup =
                messageReceiver instanceof GroupMessageReceiver &&
                    groupService.isNotesGroup(((GroupMessageReceiver) messageReceiver).getGroup());

            boolean shouldPersist = shouldPersistUploadForMessage(messageModel, isNotesGroup);

            // If the message is sent to a notes group, the blob scope must not be "public"
            @NonNull BlobScope blobScope = isNotesGroup ? BlobScope.Local.INSTANCE : BlobScope.Public.INSTANCE;

            BlobUploader uploader = apiService.createUploader(
                data,
                shouldPersist,
                blobScope
            );
            uploaders.put(key, uploader);

            logger.debug("Created new uploader for message {}, persist={}", key, shouldPersist);
            return uploader;
        }
    }

    private boolean shouldPersistUploadForMessage(@NonNull AbstractMessageModel messageModel, boolean isNotesGroup) {
        if (messageModel instanceof MessageModel) {
            // 1:1 messages do not need to be persisted
            return false;
        } else if (messageModel instanceof GroupMessageModel) {
            // Messages in groups need to be persisted if it is not a notes group
            return !isNotesGroup;
        } else if (messageModel instanceof DistributionListMessageModel) {
            // Messages in distribution lists must be persisted
            return true;
        } else {
            // This cannot happen
            logger.error("Unexpected message model. Cannot determine whether it should be persisted or not");
            return false;
        }
    }

    @NonNull
    private String getLoaderKey(@NonNull AbstractMessageModel messageModel) {
        return messageModel.getClass() + "-" + messageModel.getUid();
    }

    /**
     * Cancel an existing BlobUploader for the same {@code messageModel}
     */
    @NonNull
    private String cancelUploader(@NonNull AbstractMessageModel messageModel) {
        synchronized (uploaders) {
            String key = getLoaderKey(messageModel);

            final @Nullable BlobUploader blobUploader = uploaders.get(key);
            if (blobUploader != null) {
                logger.debug("cancel upload of message {}", key);
                blobUploader.cancel();
                uploaders.remove(key);
            }

            return key;
        }
    }

    /**
     * cancel an existing video transcoding
     */
    private String cancelTranscoding(AbstractMessageModel messageModel) {
        synchronized (videoTranscoders) {
            String key = getLoaderKey(messageModel);

            if (videoTranscoders.containsKey(key)) {
                logger.debug("cancel transcoding of message {}", key);
                WeakReference<VideoTranscoder> videoTranscoderRef = videoTranscoders.get(key);
                if (videoTranscoderRef != null) {
                    if (videoTranscoderRef.get() != null) {
                        videoTranscoderRef.get().cancel();
                    }
                }
                videoTranscoders.remove(key);
            }
            return key;
        }
    }

    @Override
    public void cancelMessageUpload(AbstractMessageModel messageModel) {
        updateOutgoingMessageState(messageModel, MessageState.SENDFAILED, new Date());

        if (messageSendingService != null) {
            messageSendingService.abort(messageModel.getUid());
        }
        removeSendMachine(messageModel);
        cancelUploader(messageModel);
    }

    @Override
    public void cancelVideoTranscoding(AbstractMessageModel messageModel) {
        updateOutgoingMessageState(messageModel, MessageState.SENDFAILED, new Date());

        removeSendMachine(messageModel);
        cancelTranscoding(messageModel);
    }

    /******************************************************************************************
     * Sending Message Machine
     * * Handling sending steps of image/video/audio or file messages
     * * Can be aborted
     ******************************************************************************************/

    public final Map<String, SendMachine> sendMachineInstances = new HashMap<>();

    /**
     * Remove a instantiated sendmachine if exists
     */
    public void removeSendMachine(SendMachine sendMachine) {
        if (sendMachine != null) {
            sendMachine.abort();

            //remove from instances
            synchronized (sendMachineInstances) {
                for (Iterator<Map.Entry<String, SendMachine>> it = sendMachineInstances.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, SendMachine> entry = it.next();
                    if (entry.getValue() == sendMachine) {
                        logger.debug("remove send machine from instance map");
                        it.remove();
                    }
                }
            }
        }
    }

    public void removeSendMachine(AbstractMessageModel messageModel) {
        if (messageModel == null) {
            //ignore
            return;
        }

        removeSendMachine(getSendMachine(messageModel, false));
    }

    /**
     * get or create a existing send machine
     */
    public SendMachine getSendMachine(AbstractMessageModel abstractMessageModel) {
        return getSendMachine(abstractMessageModel, true);
    }

    /**
     * get a send machine or create one (and cache into machine instances)
     * can return NULL
     */
    public SendMachine getSendMachine(AbstractMessageModel abstractMessageModel, boolean createIfNotExists) {
        synchronized (sendMachineInstances) {
            //be sure to "generate" a unique key
            String key = abstractMessageModel.getClass() + "-" + abstractMessageModel.getUid();

            SendMachine instance = null;
            if (sendMachineInstances.containsKey(key)) {
                instance = sendMachineInstances.get(key);
            } else if (createIfNotExists) {
                instance = new SendMachine();
                sendMachineInstances.put(key, instance);
            }
            return instance;
        }
    }

    interface SendMachineProcess {
        void run() throws Exception;
    }

    private static class SendMachine {
        private int nextStep = 0;
        private int currentStep = 0;
        private boolean aborted = false;

        public SendMachine reset() {
            currentStep = 0;
            return this;
        }

        public SendMachine abort() {
            logger.debug("SendMachine: Aborted");
            aborted = true;
            return this;
        }

        public SendMachine next(SendMachineProcess process) throws Exception {
            if (aborted) {
                logger.debug("SendMachine: Ignore step, aborted");
                //do nothing
                return this;
            }

            if (nextStep == currentStep++) {
                try {
                    if (process != null) {
                        process.run();
                    }

                    nextStep++;
                } catch (Exception x) {
                    logger.error("SendMachine: Exception", x);
                    throw x;
                }
            }
            return this;
        }
    }

    @Override
    public MessageReceiver getMessageReceiver(AbstractMessageModel messageModel) throws ThreemaException {
        if (messageModel instanceof MessageModel) {
            return contactService.createReceiver(contactService.getByIdentity(messageModel.getIdentity()));
        } else if (messageModel instanceof GroupMessageModel) {
            return groupService.createReceiver(groupService.getById(((GroupMessageModel) messageModel).getGroupId()));
        } else if (messageModel instanceof DistributionListMessageModel) {
            DistributionListService ds = ThreemaApplication.requireServiceManager().getDistributionListService();
            if (ds != null) {
                return ds.createReceiver(ds.getById(((DistributionListMessageModel) messageModel).getDistributionListId()));
            }
        }
        throw new ThreemaException("No receiver for this message");
    }


    /******************************************************************************************************/

    public interface SendResultListener {
        void onError(String errorMessage);

        void onCompleted();
    }

    /**
     * Send media messages of any kind to an arbitrary number of receivers using a thread pool
     *
     * @param mediaItems       List of MediaItems to be sent
     * @param messageReceivers List of MessageReceivers
     */
    @AnyThread
    @Override
    public void sendMediaAsync(@NonNull List<MediaItem> mediaItems, @NonNull List<MessageReceiver> messageReceivers) {
        sendMediaAsync(mediaItems, messageReceivers, null);
    }

    /**
     * Send media messages of any kind to an arbitrary number of receivers using a thread pool
     *
     * @param mediaItems         List of MediaItems to be sent
     * @param messageReceivers   List of MessageReceivers
     * @param sendResultListener Listener to notify when messages are queued
     */
    @AnyThread
    @Override
    public void sendMediaAsync(
        @NonNull final List<MediaItem> mediaItems,
        @NonNull final List<MessageReceiver> messageReceivers,
        @Nullable final SendResultListener sendResultListener
    ) {
        ExecutorServices.getSendMessageExecutorService().submit(() -> {
            sendMedia(mediaItems, messageReceivers, sendResultListener);
        });
    }

    /**
     * Send media messages of any kind to an arbitrary number of receivers in a single thread i.e. one message after the other
     *
     * @param mediaItems       List of MediaItems to be sent
     * @param messageReceivers List of MessageReceivers
     */
    @AnyThread
    @Override
    public void sendMediaSingleThread(
        @NonNull final List<MediaItem> mediaItems,
        @NonNull final List<MessageReceiver> messageReceivers) {
        ExecutorServices.getSendMessageSingleThreadExecutorService().submit(() -> {
            sendMedia(mediaItems, messageReceivers, null);
        });
    }

    /**
     * Send media messages of any kind to an arbitrary number of receivers
     *
     * @param mediaItems         List of MediaItems to be sent
     * @param messageReceivers   List of MessageReceivers
     * @param sendResultListener Listener to notify when messages are queued
     * @return AbstractMessageModel of a successfully queued message, null if no message could be queued
     */
    @WorkerThread
    @Override
    public @Nullable AbstractMessageModel sendMedia(
        @NonNull final List<MediaItem> mediaItems,
        @NonNull final List<MessageReceiver> messageReceivers,
        @Nullable final SendResultListener sendResultListener
    ) {
        AbstractMessageModel successfulMessageModel = null;
        int failedCounter = 0;

        // resolve receivers to account for distribution lists
        final MessageReceiver[] resolvedReceivers = MessageUtil.addDistributionListReceivers(messageReceivers.toArray(new MessageReceiver[0]));

        logger.info("sendMedia: Sending {} items to {} receivers", mediaItems.size(), resolvedReceivers.length);

        String correlationId = getCorrelationId();

        for (MediaItem mediaItem : mediaItems) {
            logger.info("sendMedia: Now sending item of type {}", mediaItem.getType());
            if (TYPE_TEXT == mediaItem.getType()) {
                String text = mediaItem.getCaption();
                if (!TestUtil.isEmptyOrNull(text)) {
                    for (MessageReceiver messageReceiver : resolvedReceivers) {
                        try {
                            successfulMessageModel = sendText(text, messageReceiver);
                            if (successfulMessageModel != null) {
                                logger.info("Text successfully sent");
                            } else {
                                failedCounter++;
                                logger.info("Text send failed");
                            }
                        } catch (Exception e) {
                            failedCounter++;
                            logger.error("Could not send text message", e);
                        }
                    }
                } else {
                    failedCounter++;
                    logger.info("Text is empty");
                }
                continue;
            } else if (TYPE_LOCATION == mediaItem.getType()) {
                Location location = GeoLocationUtil.getLocationFromUri(mediaItem.getUri());
                if (location != null) {
                    for (MessageReceiver messageReceiver : resolvedReceivers) {
                        try {
                            successfulMessageModel = sendLocation(location, "", messageReceiver, null);
                        } catch (Exception e) {
                            failedCounter++;
                            logger.error("Could not send location message");
                        }
                    }
                } else {
                    failedCounter++;
                    logger.info("Sending location failed: invalid location");
                }
                continue;
            }

            final Map<MessageReceiver, AbstractMessageModel> messageModels = new HashMap<>();

            final FileDataModel fileDataModel = createFileDataModel(context, mediaItem);
            if (fileDataModel == null) {
                logger.info("Unable to create FileDataModel");
                failedCounter++;
                continue;
            }

            if (!createFileMessagesAndSetPending(correlationId, mediaItem, resolvedReceivers, messageModels, fileDataModel)) {
                logger.info("Unable to create messages");
                failedCounter++;
                continue;
            }

            if (!allChatsArePrivate(resolvedReceivers)) {
                saveToGallery(mediaItem);
            }

            try {
                final Map<String, Object> metaData = new HashMap<>();
                final byte[] contentData = generateContentData(mediaItem, resolvedReceivers, messageModels, fileDataModel, metaData);
                final byte[] thumbnailData = generateThumbnailData(mediaItem, fileDataModel, metaData);
                // F1Whisper: tag forwarded media/file/voice so both the sender's copy and a receiving
                // F1Whisper client render the "Forwarded" header (rides the E2E file metadata).
                if (mediaItem.isForwarded()) {
                    metaData.put(FileDataModel.METADATA_KEY_FORWARDED, true);
                }
                // F1Whisper: carry Signal-style link-preview metadata E2E so a receiving client can
                // render the preview card WITHOUT fetching the URL (recipient IP never leaks). The
                // image blob is the og:image (or placeholder) and the caption is the user's text.
                if (mediaItem.isLinkPreview()) {
                    metaData.put(FileDataModel.METADATA_KEY_PREVIEW_URL, mediaItem.getLinkPreviewUrl());
                    if (mediaItem.getLinkPreviewTitle() != null && !mediaItem.getLinkPreviewTitle().isBlank()) {
                        metaData.put(FileDataModel.METADATA_KEY_PREVIEW_TITLE, mediaItem.getLinkPreviewTitle());
                    }
                    if (mediaItem.getLinkPreviewDescription() != null && !mediaItem.getLinkPreviewDescription().isBlank()) {
                        metaData.put(FileDataModel.METADATA_KEY_PREVIEW_DESCRIPTION, mediaItem.getLinkPreviewDescription());
                    }
                }
                // F1Whisper: carry the reply-quote apiMessageId E2E in the file metadata when this
                // media/file/voice was sent as an answer to a quoted message (Signal-style reply with
                // any type). The receiving client copies "qi" into its quotedMessageId column and
                // renders the quote header above the media bubble. Forward drops it (fresh MediaItem).
                final String quotedMessageId = mediaItem.getQuotedMessageId();
                if (quotedMessageId != null && !quotedMessageId.isBlank()) {
                    metaData.put(FileDataModel.METADATA_KEY_QUOTED_MESSAGE_ID, quotedMessageId);
                }
                fileDataModel.setMetaData(metaData);

                // F1Whisper: the per-receiver models were serialized (setFileData) in
                // createFileMessagesAndSetPending BEFORE this metadata existed, so their persisted
                // `body` lacks it (e.g. the "fwd" forwarded flag), and the header would vanish on the
                // next reload of the sender's own copy. Re-apply the now-complete fileData so each
                // model's body is reserialized with the full metadata before it is saved/sent.
                for (AbstractMessageModel messageModel : messageModels.values()) {
                    messageModel.setFileData(fileDataModel);
                    // F1Whisper: populate the sender's own quotedMessageId column so the reply-quote
                    // header + tap-to-jump render on the sender's copy too (mirrors the recipient, which
                    // gets it from the "qi" metadata). Persisted by the save() in encryptAndSend below.
                    if (quotedMessageId != null && !quotedMessageId.isBlank()) {
                        messageModel.setQuotedMessageId(quotedMessageId);
                    }
                }

                if (thumbnailData != null) {
                    writeThumbnails(messageModels, resolvedReceivers, thumbnailData);
                } else {
                    logger.info("Unable to generate thumbnails");
                }

                if (contentData != null) {
                    if (encryptAndSend(resolvedReceivers, messageModels, fileDataModel, thumbnailData, contentData)) {
                        successfulMessageModel = messageModels.get(resolvedReceivers[0]);
                    } else {
                        throw new ThreemaException("Error encrypting and sending");
                    }
                } else {
                    logger.info("Error encrypting and sending");
                    failedCounter++;
                    markAsTerminallyFailed(resolvedReceivers, messageModels);
                }
            } catch (ThreemaException e) {
                if (e instanceof TranscodeCanceledException) {
                    logger.info("Video transcoding canceled");
                    // canceling is not really a failure
                } else {
                    logger.error("Exception", e);
                    failedCounter++;
                }
                markAsTerminallyFailed(resolvedReceivers, messageModels);
            }
        }

        if (failedCounter == 0) {
            logger.info("sendMedia: Successfully queued.");
            if (sendResultListener != null) {
                sendResultListener.onCompleted();
            }
        } else {
            logger.error("sendMedia: Did not complete successfully, failedCounter={}", failedCounter);
            final String errorString = context.getString(R.string.an_error_occurred_during_send);
            RuntimeUtil.runOnUiThread(() -> Toast.makeText(context, errorString, Toast.LENGTH_LONG).show());
            if (sendResultListener != null) {
                sendResultListener.onError(errorString);
            }
        }
        return successfulMessageModel;
    }

    /**
     * Write thumbnails to local storage
     */
    private void writeThumbnails(Map<MessageReceiver, AbstractMessageModel> messageModels, MessageReceiver[] resolvedReceivers, byte[] thumbnailData) {
        for (MessageReceiver messageReceiver : resolvedReceivers) {
            if (thumbnailData != null) {
                try {
                    fileService.writeConversationMediaThumbnail(messageModels.get(messageReceiver), thumbnailData);
                    fireOnModifiedMessage(messageModels.get(messageReceiver));
                } catch (Exception ignored) {
                    // having no thumbnail is not really fatal
                }
            }
        }
    }

    /**
     * Generate content data for this MediaItem
     *
     * @return content data as a byte array or null if content data could not be generated
     */
    @WorkerThread
    private @Nullable byte[] generateContentData(
        @NonNull MediaItem mediaItem,
        @NonNull MessageReceiver[] resolvedReceivers,
        @NonNull Map<MessageReceiver, AbstractMessageModel> messageModels,
        @NonNull FileDataModel fileDataModel,
        @NonNull Map<String, Object> metaData
    ) throws ThreemaException {
        switch (mediaItem.getType()) {
            case TYPE_VIDEO:
                // fallthrough
            case TYPE_VIDEO_CAM:
                @VideoTranscoder.TranscoderResult int result = transcodeVideo(mediaItem, resolvedReceivers, messageModels);
                if (result == VideoTranscoder.SUCCESS) {
                    return getContentData(mediaItem);
                } else if (result == VideoTranscoder.CANCELED) {
                    throw new TranscodeCanceledException();
                }
                break;
            case TYPE_IMAGE:
                // scale and rotate / flip images
                int maxSize = ConfigUtils.getPreferredImageDimensions(mediaItem.getImageScale() == IMAGE_SCALE_DEFAULT ?
                    preferenceService.getImageScale() : mediaItem.getImageScale());

                Bitmap bitmap = null;
                try {
                    boolean hasNoTransparency = MimeUtil.MIME_TYPE_IMAGE_JPEG.equals(mediaItem.getMimeType());
                    bitmap = BitmapUtil.safeGetBitmapFromUri(context, mediaItem.getUri(), maxSize, false, false, false);
                    if (bitmap != null) {
                        bitmap = adjustBitmapOrientation(bitmap, mediaItem, metaData);

                        final byte[] imageByteArray;
                        if (hasNoTransparency) {
                            imageByteArray = BitmapUtil.getJpegByteArray(bitmap, mediaItem.getRotation(), mediaItem.getFlip());
                        } else {
                            imageByteArray = BitmapUtil.getPngByteArray(bitmap, mediaItem.getRotation(), mediaItem.getFlip());

                            if (!MimeUtil.MIME_TYPE_IMAGE_PNG.equals(mediaItem.getMimeType())) {
                                fileDataModel.setMimeType(MimeUtil.MIME_TYPE_IMAGE_PNG);

                                if (fileDataModel.getFileName() != null) {
                                    int dot = fileDataModel.getFileName().lastIndexOf(".");
                                    if (dot > 1) {
                                        String filenamePart = fileDataModel.getFileName().substring(0, dot);
                                        fileDataModel.setFileName(filenamePart + ".png");
                                    }
                                }
                            }
                        }
                        if (imageByteArray != null) {
                            fileDataModel.setFileSize(imageByteArray.length);
                            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                            outputStream.write(new byte[NaCl.BOX_OVERHEAD_BYTES]);
                            outputStream.write(imageByteArray);

                            return outputStream.toByteArray();
                        }
                    }
                } catch (Exception e) {
                    logger.error("Exception", e);
                } finally {
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                }
                break;
            case TYPE_IMAGE_CAM:
                // cam images will always be sent in their original size. no scaling needed but possibly rotate and flip
                try (InputStream inputStream = getFromUri(context, mediaItem.getUri())) {
                    if (inputStream != null && inputStream.available() > 0) {
                        bitmap = BitmapFactory.decodeStream(new BufferedInputStream(inputStream), null, null);
                        if (bitmap != null) {
                            bitmap = adjustBitmapOrientation(bitmap, mediaItem, metaData);

                            final byte[] imageByteArray = BitmapUtil.getJpegByteArray(bitmap, mediaItem.getRotation(), mediaItem.getFlip());
                            if (imageByteArray != null) {
                                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                                outputStream.write(new byte[NaCl.BOX_OVERHEAD_BYTES]);
                                outputStream.write(imageByteArray);

                                return outputStream.toByteArray();
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("Exception", e);
                }
                break;
            case TYPE_AUDIO_FILE:
                // F1Whisper: an attached audio file. If the user picked a trim window on the preview
                // timeline, losslessly crop it (per-container: AAC/Opus remux, MP3 frame-cut, WAV
                // PCM-cut) before sending. CRITICAL FAIL-SAFE: if the user requested a trim and it
                // cannot be performed, we ABORT the send entirely (throw) instead of silently sending
                // the untrimmed original, which would be a data/privacy leak (sending more audio than
                // the user intended to share). Then send the cropped clip as a regular file.
                trimAudio(mediaItem, fileDataModel);
                return getContentData(mediaItem);
            case TYPE_IMAGE_ANIMATED:
                metaData.put(FileDataModel.METADATA_KEY_ANIMATED, true);
                // fallthrough
            case TYPE_VOICEMESSAGE:
                // fallthrough
            case TYPE_FILE:
                // "regular" file messages
                return getContentData(mediaItem);
            default:
                // media type currently not supported
                break;
        }
        return null;
    }

    /**
     * Rotate/flip bitmap according to exif information and add final dimensions to the file message's meta data also keeping in
     * account local orientation (if any)
     *
     * @param bitmap    The Bitmap
     * @param mediaItem The MediaItem instance that contains orientation info about this particular item
     * @param metaData  A map with meta data that is going to be added to a file message
     * @return A new bitmap with adjusted orientation
     */
    @NonNull
    private Bitmap adjustBitmapOrientation(
        @NonNull Bitmap bitmap,
        @NonNull MediaItem mediaItem,
        @NonNull Map<String, Object> metaData
    ) {
        bitmap = BitmapUtil.rotateBitmap(
            bitmap,
            mediaItem.getExifRotation(),
            mediaItem.getExifFlip());

        boolean isRotated = mediaItem.getRotation() == 90 || mediaItem.getRotation() == 270;
        metaData.put(FileDataModel.METADATA_KEY_WIDTH, isRotated ? bitmap.getHeight() : bitmap.getWidth());
        metaData.put(FileDataModel.METADATA_KEY_HEIGHT, isRotated ? bitmap.getWidth() : bitmap.getHeight());

        return bitmap;
    }

    /**
     * Generate thumbnail data for this MediaItem
     *
     * @return byte array of the thumbnail bitmap, null if thumbnail could not be generated
     */
    @WorkerThread
    private @Nullable byte[] generateThumbnailData(
        @NonNull MediaItem mediaItem,
        @NonNull FileDataModel fileDataModel,
        @NonNull Map<String, Object> metaData
    ) {
        Bitmap thumbnailBitmap = null;

        int mediaType = mediaItem.getType();

        // we want thumbnails for images and videos even if they are to be sent as files
        if (MimeUtil.isSupportedImageFile(fileDataModel.getMimeType())) {
            mediaType = TYPE_IMAGE;
        } else if (MimeUtil.isVideoFile(fileDataModel.getMimeType())) {
            mediaType = TYPE_VIDEO;
        }

        switch (mediaType) {
            case MediaItem.TYPE_VIDEO:
                // fallthrough
            case MediaItem.TYPE_VIDEO_CAM:
                // add duration to metadata
                long trimmedDuration = mediaItem.getDurationMs();
                if (mediaItem.getEndTimeMs() != TIME_UNDEFINED && (mediaItem.getEndTimeMs() != 0L || mediaItem.getStartTimeMs() != 0L)) {
                    trimmedDuration = mediaItem.getEndTimeMs() - mediaItem.getStartTimeMs();
                } else {
                    if (mediaItem.getDurationMs() == 0) {
                        // empty duration means full video
                        trimmedDuration = VideoUtil.getVideoDuration(context, mediaItem.getUri());
                        mediaItem.setDurationMs(trimmedDuration);
                    }
                }
                metaData.put(FileDataModel.METADATA_KEY_DURATION, (float) trimmedDuration / (float) DateUtils.SECOND_IN_MILLIS);
                thumbnailBitmap = IconUtil.getVideoThumbnailFromUri(context, mediaItem);
                fileDataModel.setThumbnailMimeType(MimeUtil.MIME_TYPE_IMAGE_JPEG);
                break;
            case MediaItem.TYPE_IMAGE:
                BitmapUtil.ExifOrientation exifOrientation = BitmapUtil.getExifOrientation(context, mediaItem.getUri());
                mediaItem.setExifRotation((int) exifOrientation.getRotation());
                mediaItem.setExifFlip(exifOrientation.getFlip());
                boolean hasNoTransparency = MimeUtil.MIME_TYPE_IMAGE_JPEG.equals(mediaItem.getMimeType());
                if (hasNoTransparency && mediaItem.getRenderingType() != RENDERING_STICKER) {
                    fileDataModel.setThumbnailMimeType(MimeUtil.MIME_TYPE_IMAGE_JPEG);
                } else {
                    fileDataModel.setThumbnailMimeType(MimeUtil.MIME_TYPE_IMAGE_PNG);
                }
                thumbnailBitmap = BitmapUtil.safeGetBitmapFromUri(context, mediaItem.getUri(), THUMBNAIL_SIZE_PX, false, true, false);
                if (thumbnailBitmap != null) {
                    thumbnailBitmap = BitmapUtil.rotateBitmap(BitmapUtil.rotateBitmap(
                        thumbnailBitmap,
                        mediaItem.getExifRotation(),
                        mediaItem.getExifFlip()), mediaItem.getRotation(), mediaItem.getFlip());
                }
                break;
            case MediaItem.TYPE_IMAGE_CAM:
                // camera images are always sent as JPGs
                fileDataModel.setThumbnailMimeType(MimeUtil.MIME_TYPE_IMAGE_JPEG);
                thumbnailBitmap = BitmapUtil.safeGetBitmapFromUri(context, mediaItem.getUri(), THUMBNAIL_SIZE_PX, false, true, false);
                if (thumbnailBitmap != null) {
                    thumbnailBitmap = BitmapUtil.rotateBitmap(BitmapUtil.rotateBitmap(
                        thumbnailBitmap,
                        mediaItem.getExifRotation(),
                        mediaItem.getExifFlip()), mediaItem.getRotation(), mediaItem.getFlip());
                }
                break;
            case TYPE_IMAGE_ANIMATED:
                fileDataModel.setThumbnailMimeType(MimeUtil.MIME_TYPE_IMAGE_PNG);
                thumbnailBitmap = IconUtil.getThumbnailFromUri(context, mediaItem.getUri(), THUMBNAIL_SIZE_PX, fileDataModel.getMimeType(), true);
                break;
            case MediaItem.TYPE_VOICEMESSAGE:
                metaData.put(FileDataModel.METADATA_KEY_DURATION, (float) mediaItem.getDurationMs() / (float) DateUtils.SECOND_IN_MILLIS);
                if (mediaItem.isListenOnce()) {
                    // F1Whisper: carry the "listen once" flag inside the E2E-encrypted file metadata.
                    // Strictly for voice messages; the recipient enforces single playback client-side.
                    metaData.put(FileDataModel.METADATA_KEY_LISTEN_ONCE, true);
                }
                // voice messages do not have thumbnails
                thumbnailBitmap = null;
                break;
            case TYPE_AUDIO_FILE:
                // F1Whisper: attached audio file. Carry the (possibly trimmed) duration so the
                // recipient's audio player shows the right runtime; audio files have no thumbnail.
                if (mediaItem.getDurationMs() > 0) {
                    metaData.put(FileDataModel.METADATA_KEY_DURATION, (float) mediaItem.getTrimmedDurationMs() / (float) DateUtils.SECOND_IN_MILLIS);
                }
                thumbnailBitmap = null;
                break;
            case MediaItem.TYPE_FILE:
                // just an arbitrary file
                thumbnailBitmap = null;
                break;
            default:
                break;
        }

        // F1Whisper: carry the "spoiler" flag inside the E2E-encrypted file metadata for image/video
        // media. The recipient renders a blurred, tap-to-reveal thumbnail. Client-side only.
        if (mediaItem.isSpoiler()
            && (mediaType == TYPE_IMAGE || mediaType == TYPE_IMAGE_CAM
            || mediaType == TYPE_IMAGE_ANIMATED || mediaType == TYPE_VIDEO
            || mediaType == TYPE_VIDEO_CAM)) {
            metaData.put(FileDataModel.METADATA_KEY_SPOILER, true);
        }

        final byte[] thumbnailData;
        if (thumbnailBitmap != null) {
            // convert bitmap to byte array
            if (MimeUtil.MIME_TYPE_IMAGE_JPEG.equals(fileDataModel.getThumbnailMimeType())) {
                thumbnailData = BitmapUtil.bitmapToJpegByteArray(thumbnailBitmap);
                fileDataModel.setThumbnailMimeType(MimeUtil.MIME_TYPE_IMAGE_JPEG);
            } else {
                thumbnailData = BitmapUtil.bitmapToPngByteArray(thumbnailBitmap);
                fileDataModel.setThumbnailMimeType(MimeUtil.MIME_TYPE_IMAGE_PNG);
            }
            thumbnailBitmap.recycle();
        } else {
            thumbnailData = null;
        }
        return thumbnailData;
    }

    /**
     * Encrypt content and thumbnail data, upload blobs and queue messages for the specified MediaItem
     *
     * @param resolvedReceivers MessageReceivers to send the MediaItem to
     * @param messageModels     MessageModels for above MessageReceivers
     * @param fileDataModel     fileDataModel for this message
     * @param thumbnailData     Byte Array of thumbnail bitmap to be uploaded as a blob
     * @param contentData       Byte Array of Content to be uploaded as a blob
     * @return true if the message was queued successfully, false otherwise. Note that errors that occur during sending are not handled here.
     */
    @WorkerThread
    private boolean encryptAndSend(
        @NonNull MessageReceiver<AbstractMessageModel>[] resolvedReceivers,
        @NonNull Map<MessageReceiver, AbstractMessageModel> messageModels,
        @NonNull FileDataModel fileDataModel,
        @Nullable byte[] thumbnailData,
        @NonNull byte[] contentData
    ) {
        final SymmetricEncryptionResult[] contentEncryptResult = new SymmetricEncryptionResult[1];
        final SymmetricEncryptionResult[] thumbnailEncryptResult = new SymmetricEncryptionResult[1];
        thumbnailEncryptResult[0] = null;
        contentEncryptResult[0] = null;

        for (MessageReceiver messageReceiver : resolvedReceivers) {
            // save content first as it will be modified later on
            AbstractMessageModel messageModel = messageModels.get(messageReceiver);
            if (messageModel == null) {
                // no messagemodel has been created for this receiver - skip
                continue;
            }

            if (messageReceiver instanceof GroupMessageReceiver
                && groupService.isNotesGroup(((GroupMessageReceiver) messageReceiver).getGroup())
            ) {
                // In case of a notes group, we set the message state directly to read
                messageModel.setState(MessageState.READ);
            } else {
                // Otherwise we initialize the message model with pending to show a progress bar
                messageModel.setState(MessageState.PENDING); // shows a progress bar
            }
            // F1Whisper (ninth follow-up review, F9-01): this save IS the question, and its answer used to be thrown
            // away. Preprocessing ran between the PENDING row being created and this point - scaling an image, trimming
            // an audio file - and the message was selectable and deletable for all of it, so a message the user had
            // already deleted still got its derived media written to disk and its upload queued. The derived output the
            // losing operation wrote goes with it: the thumbnail written moments ago by writeThumbnails is the one file
            // the deletion's own cleanup cannot have caught, because it did not exist yet. The tombstone is untouched -
            // the deletion-control task still needs the row it announces.
            if (!save(messageModel)) {
                logger.info("Not sending {}: its row is gone or was deleted while its media was being prepared",
                    messageModel.getUid());
                fileService.removeMessageFiles(messageModel, true);
                continue;
            }

            try {
                fileService.writeConversationMedia(messageModel, contentData, NaCl.BOX_OVERHEAD_BYTES, contentData.length - NaCl.BOX_OVERHEAD_BYTES);
            } catch (Exception e) {
                // Failure to write local media is not necessarily fatal, continue
                logger.debug("Exception", e);
            }
        }

        for (MessageReceiver<AbstractMessageModel> messageReceiver : resolvedReceivers) {
            //enqueue processing and uploading stuff...
            AbstractMessageModel messageModel = messageModels.get(messageReceiver);
            if (messageModel == null) {
                // no messagemodel has been created for this receiver - skip
                logger.info("Mo MessageModel could be created for this receiver - skip");
                continue;
            }

            // F1Whisper (ninth follow-up review, F9-01): asked again rather than remembered from the loop above,
            // because the loop above writes the local media for every receiver before this one queues anything, so a
            // deletion can land in between. The process gates itself at entry too; this is what stops it being queued
            // at all, which is also what stops the deletion having to find a future to cancel.
            if (!mayStillSend(messageModel)) {
                continue;
            }

            messageSendingService.addToQueue(new MessageSendingService.MessageSendingProcess() {
                private byte[] thumbnailBlobId;
                private byte[] contentBlobId;

                public boolean success = false;

                @Override
                public MessageReceiver<AbstractMessageModel> getReceiver() {
                    return messageReceiver;
                }

                @Override
                public AbstractMessageModel getMessageModel() {
                    return messageModel;
                }

                @Override
                public boolean send() throws Exception {
                    // F1Whisper (ninth follow-up review, F9-01): ask the row FIRST. This process may have spent minutes
                    // in the single-worker queue behind another attachment, and the message is deletable for that whole
                    // time. Everything below leaves the device or writes to it; the next question anyone asked used to
                    // be the handoff, by which point both blobs were already on the server.
                    if (!mayStillSend(messageModel)) {
                        return false;
                    }
                    SendMachine sendMachine = getSendMachine(messageModel);
                    sendMachine.reset()
                        .next(() -> {
                            boolean hasChanges = false;
                            if (messageModel.getMessageId() == null) {
                                messageModel.setMessageId(MessageId.random());
                                hasChanges = true;
                            }
                            if (getReceiver().shouldSendMediaData()) {
                                // encrypt file data
                                // note that encryptFileData() will overwrite contents of provided content data!
                                if (contentEncryptResult[0] == null) {
                                    contentEncryptResult[0] = symmetricEncryptionService.encryptInplace(contentData, ProtocolDefines.FILE_NONCE);
                                    if (contentEncryptResult[0].isEmpty()) {
                                        throw new ThreemaException("File data encrypt failed");
                                    }
                                }
                                messageModel.setState(MessageState.UPLOADING);
                                hasChanges = true;
                            }
                            if (hasChanges && !save(messageModel)) {
                                // F1Whisper (ninth follow-up review, F9-01): this answer has been correct since H8-01
                                // and was thrown away. It is the same refusal the handoff acts on, three steps earlier -
                                // before the content blob and the thumbnail blob are uploaded rather than after - and it
                                // covers a deletion that lands between the entry gate above and this write.
                                logger.info("Media send refused for {}: its row is gone or was deleted", messageModel.getId());
                                sendMachine.abort();
                                return;
                            }
                            fileDataModel.setFileSize(contentData.length - NaCl.BOX_OVERHEAD_BYTES);
                            messageModel.setFileData(fileDataModel);
                            fireOnModifiedMessage(messageModel);
                        })
                        .next(() -> {
                            if (getReceiver().shouldSendMediaData()) {
                                // upload file data
                                BlobUploader blobUploader = initUploader(
                                    getMessageModel(),
                                    contentEncryptResult[0].getData(),
                                    getReceiver()
                                );
                                blobUploader.progressListener = new ProgressListener() {
                                    @Override
                                    public void updateProgress(int progress) {
                                        updateMessageLoadingProgress(messageModel, progress);
                                    }

                                    @Override
                                    public void onFinished(boolean success) {
                                        setMessageLoadingFinished(messageModel);
                                    }
                                };
                                contentBlobId = blobUploader.upload();
                            }
                        })
                        .next(() -> {
                            if (getReceiver().shouldSendMediaData()) {
                                // encrypt and upload thumbnail
                                if (thumbnailData != null) {
                                    thumbnailEncryptResult[0] = symmetricEncryptionService
                                        .encrypt(thumbnailData, contentEncryptResult[0].getKey(), ProtocolDefines.FILE_THUMBNAIL_NONCE);

                                    if (thumbnailEncryptResult[0].isEmpty()) {
                                        throw new ThreemaException("Thumbnail encrypt failed");
                                    } else {
                                        BlobUploader blobUploader = initUploader(
                                            getMessageModel(),
                                            thumbnailEncryptResult[0].getData(),
                                            getReceiver()
                                        );
                                        blobUploader.progressListener = new ProgressListener() {
                                            @Override
                                            public void updateProgress(int progress) {
                                                updateMessageLoadingProgress(messageModel, progress);
                                            }

                                            @Override
                                            public void onFinished(boolean success) {
                                                setMessageLoadingFinished(messageModel);
                                            }
                                        };
                                        thumbnailBlobId = blobUploader.upload();
                                        fireOnModifiedMessage(messageModel);
                                    }
                                }
                            }
                        })
                        .next(() -> {
                            if (!getReceiver().createAndSendFileMessage(
                                thumbnailBlobId,
                                contentBlobId,
                                contentEncryptResult[0],
                                messageModel,
                                null
                            )) {
                                // F1Whisper (eighth fork review, H8-01): the user deleted this message while its
                                // content blob was uploading, so the row refused to take the blob id and key back. The
                                // remaining steps exist to announce a send that is not happening - the dispatch state,
                                // the saved flag, the completion listener - so the machine stops here and publishes
                                // nothing. The upload itself was already cancelled by the deletion; this is the second
                                // half, for the handoff that had already begun when the cancellation arrived.
                                logger.info("Media handoff refused for {}: its row is gone or was deleted",
                                    messageModel.getId());
                                sendMachine.abort();
                                return;
                            }
                            // F1Whisper (fifth fork review, F5-02): the state this pipeline may claim once the send
                            // layer has the message. It used to be decided by `offerRetry()`, which answers a question
                            // about the retry UI and returns false for a group - so group media was recorded as SENT the
                            // instant its task had been SCHEDULED, and the disappearing countdown started on a message
                            // still sitting in an offline queue. See OutgoingSendBoundaryDecision.
                            updateOutgoingMessageState(messageModel,
                                OutgoingSendBoundaryDecision.stateAtDispatch(getReceiver().hasPendingRemoteCompletion()),
                                new Date());
                            // F1Whisper (seventh fork review, F7-01 / F7-05): NO full-row save here. The blob id and
                            // encryption key were written into the row by createAndSendFileMessage's own save, before
                            // it scheduled the task; re-setting the same FileDataModel instance and saving the whole row
                            // added nothing but a post-schedule writer of every lifecycle column. It was also the exact
                            // step the review pauses to reproduce F7-01: with the message hard-deleted while this step
                            // was suspended, the save re-admitted the detached model - body, blob id and key - to the
                            // service cache, and the already-archived task read it back and sent it.
                        })
                        .next(() -> {
                            messageModel.setSaved(true);
                            // Verify current saved state
                            updateOutgoingMessageState(messageModel,
                                OutgoingSendBoundaryDecision.stateAtDispatch(getReceiver().hasPendingRemoteCompletion()),
                                new Date());

                            if (!getReceiver().shouldSendMediaData()) {
                                // update status for message that stay local
                                fireOnModifiedMessage(messageModel);
                            }
                            success = true;
                        });

                    if (success) {
                        removeSendMachine(sendMachine);
                    }
                    return success;
                }
            });
        }
        return true;
    }

    /**
     * Create MessageModels for all receivers, save local thumbnail and set MessageModels to PENDING for instant UI feedback
     *
     * @return true if all was hunky dory, false if an error occurred
     */
    @WorkerThread
    private boolean createFileMessagesAndSetPending(
        String correlationId,
        MediaItem mediaItem,
        MessageReceiver[] resolvedReceivers,
        Map<MessageReceiver, AbstractMessageModel> messageModels,
        FileDataModel fileDataModel
    ) {
        for (MessageReceiver messageReceiver : resolvedReceivers) {

            final AbstractMessageModel messageModel = messageReceiver.createLocalModel(MessageType.FILE, MimeUtil.getContentTypeFromFileData(fileDataModel), TrustedClock.now()); // F1Whisper: server-corrected outgoing postedAt
            cache(messageModel);

            messageModel.setOutbox(true);
            messageModel.setState(MessageState.PENDING); // shows a progress bar
            messageModel.setFileData(fileDataModel);
            messageModel.setCorrelationId(correlationId);
            String trimmedCaption = mediaItem.getTrimmedCaption();
            if (trimmedCaption != null && !trimmedCaption.isBlank()) {
                messageModel.setCaption(trimmedCaption);
            }
            messageModel.setSaved(true);

            messageReceiver.saveLocalModel(messageModel);
            // F1Whisper: the disappearing countdown is deliberately NOT armed here. This model is
            // still PENDING — transcoding, encryption, thumbnail and content blob uploads and the
            // send handoff all happen afterwards, and an upload is not bounded by the picker's
            // shortest timer (30 s). Arming here let the alarm hard-delete the sender's own copy
            // mid-upload while the send machine still held the model. It is armed instead once the
            // handoff has succeeded, in the final stage of the media send machine below.

            messageReceiver.bumpLastUpdate();

            messageModels.put(messageReceiver, messageModel);

            fireOnCreatedMessage(messageModel);
        }
        return true;
    }

    @SuppressLint("Range")
    public @Nullable FileDataModel createFileDataModel(Context context, MediaItem mediaItem) {
        ContentResolver contentResolver = context.getContentResolver();
        String mimeType = mediaItem.getMimeType();
        String filename = mediaItem.getFilename();

        if (mediaItem.getUri() == null) {
            return null;
        }

        if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(mediaItem.getUri().getScheme())) {
            if (TestUtil.isEmptyOrNull(filename)) {
                File file = new File(mediaItem.getUri().getPath());

                filename = file.getName();
            }
        } else {
            if (TestUtil.isEmptyOrNull(filename) || TestUtil.isEmptyOrNull(mimeType)) {
                String[] proj = {
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                };

                try (Cursor cursor = contentResolver.query(mediaItem.getUri(), proj, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        if (TestUtil.isEmptyOrNull(filename)) {
                            filename = cursor.getString(
                                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                        }
                        if (TestUtil.isEmptyOrNull(mimeType) || MimeUtil.MIME_TYPE_DEFAULT.equals(mimeType)) {
                            mimeType = cursor.getString(
                                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE));
                        }
                    }
                } catch (Exception e) {
                    logger.error("Unable to query content provider", e);
                }
            }
        }

        if (TestUtil.isEmptyOrNull(mimeType) || MimeUtil.MIME_TYPE_DEFAULT.equals(mimeType)) {
            mimeType = FileUtil.getMimeTypeFromUri(context, mediaItem.getUri());
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // non-animated images are being sent as png files
            // we should fix the mime type before creating a local message model in order not to confuse the chat adapter
            if (MimeUtil.isAnimatedImageFormat(mimeType)
                && mediaItem.getType() != TYPE_IMAGE_ANIMATED
                && mediaItem.getType() != TYPE_FILE
                && mediaItem.getImageScale() != PreferenceService.IMAGE_SCALE_SEND_AS_FILE) {
                mimeType = MimeUtil.MIME_TYPE_IMAGE_PNG;
            }
        }

        @FileData.RenderingType int renderingType = mediaItem.getRenderingType();

        // rendering type overrides
        switch (mediaItem.getType()) {
            case TYPE_VOICEMESSAGE:
                filename = FileUtil.getDefaultFilename(mimeType); // the internal temporary file name is of no use to the recipient
                renderingType = FileData.RENDERING_MEDIA;
                break;
            case TYPE_IMAGE_ANIMATED:
                if (renderingType == FileData.RENDERING_DEFAULT) {
                    // do not override stickers
                    renderingType = FileData.RENDERING_MEDIA;
                }
                break;
            case TYPE_FILE:
                // "regular" file messages
                renderingType = FileData.RENDERING_DEFAULT;
                break;
            case TYPE_AUDIO_FILE:
                // F1Whisper: attached audio is sent as a regular file (keeps its real filename); the
                // trim window, if any, has already been baked into the file by trimAudio().
                renderingType = FileData.RENDERING_DEFAULT;
                break;
            case TYPE_VIDEO:
                if (renderingType == FileData.RENDERING_MEDIA) {
                    // videos in formats other than MP4 are always transcoded and result in an MP4 file
                    mimeType = MimeUtil.MIME_TYPE_VIDEO_MP4;
                }
                // fallthrough
            default:
                if (mediaItem.getImageScale() == PreferenceService.IMAGE_SCALE_SEND_AS_FILE || mediaItem.getVideoSize() == PreferenceService.VIDEO_SIZE_SEND_AS_FILE) {
                    // images with scale type "send as file" get the default rendering type and a file name
                    renderingType = FileData.RENDERING_DEFAULT;
                    mediaItem.setType(TYPE_FILE);
                } else {
                    // unlike with "real" files we override the filename for regular (RENDERING_MEDIA) images and videos with a generic one to prevent privacy leaks
                    // this mimics the behavior of traditional image messages that did not have a filename at all
                    filename = FileUtil.getDefaultFilename(mimeType);
                }
                break;
        }

        if (TestUtil.isEmptyOrNull(filename)) {
            filename = FileUtil.getDefaultFilename(mimeType);
        }

        String caption = mediaItem.getTrimmedCaption();
        if (caption != null && caption.isBlank()) {
            caption = null;
        }

        return new FileDataModel(mimeType,
            null,
            0,
            filename,
            renderingType,
            caption,
            true,
            null);
    }

    /**
     * Transcode and trim this video according to the parameters set in the MediaItem object
     *
     * @return Result of transcoding
     */
    @WorkerThread
    private @VideoTranscoder.TranscoderResult int transcodeVideo(MediaItem mediaItem, MessageReceiver[] resolvedReceivers, Map<MessageReceiver, AbstractMessageModel> messageModels) {
        final MessagePlayerService messagePlayerService;
        try {
            messagePlayerService = ThreemaApplication.requireServiceManager().getMessagePlayerService();
        } catch (ThreemaException e) {
            logger.error("Exception", e);
            return VideoTranscoder.FAILURE;
        }

        int targetBitrate;
        @PreferenceService.VideoSize int desiredVideoSize = preferenceService.getVideoSize();
        if (mediaItem.getVideoSize() != PreferenceService.VIDEO_SIZE_DEFAULT) {
            desiredVideoSize = mediaItem.getVideoSize();
        }

        try {
            targetBitrate = VideoConfig.getTargetVideoBitrate(context, mediaItem, desiredVideoSize);
        } catch (ThreemaException e) {
            logger.error("Error getting target bitrate", e);
            // skip this MediaItem
            markAsTerminallyFailed(resolvedReceivers, messageModels);
            return VideoTranscoder.FAILURE;
        }

        if (targetBitrate == -1) {
            // will not fit
            logger.info("Video file ist too large");
            RuntimeUtil.runOnUiThread(() -> Toast.makeText(context, context.getString(R.string.file_too_large, MAX_BLOB_SIZE_MB), Toast.LENGTH_SHORT).show());
            // skip this MediaItem
            markAsTerminallyFailed(resolvedReceivers, messageModels);
            return VideoTranscoder.FAILURE;
        }

        logger.info("Target bitrate = {}", targetBitrate);

        if (mediaItem.hasChanges() ||
            targetBitrate > 0 ||
            !MimeUtil.MIME_TYPE_VIDEO_MP4.equalsIgnoreCase(mediaItem.getMimeType())) {

            logger.info("Video needs transcoding");

            // set models to TRANSCODING state
            for (Map.Entry<MessageReceiver, AbstractMessageModel> entry : messageModels.entrySet()) {
                AbstractMessageModel messageModel = entry.getValue();
                messageModel.setState(MessageState.TRANSCODING);
                save(messageModel);
                fireOnModifiedMessage(messageModel);
            }

            File outputFile;
            try {
                outputFile = fileService.createTempFile(".trans", ".mp4");
            } catch (IOException e) {
                logger.error("Unable to open temp file");
                // skip this MediaItem
                markAsTerminallyFailed(resolvedReceivers, messageModels);
                return VideoTranscoder.FAILURE;
            }

            final VideoTranscoder.Builder transcoderBuilder = new VideoTranscoder.Builder(mediaItem.getUri(), outputFile);
            transcoderBuilder.includeAudio(!mediaItem.isMuted());

            if (mediaItem.needsTrimming()) {
                transcoderBuilder.trim(mediaItem.getStartTimeMs(), mediaItem.getEndTimeMs());
            }

            if (targetBitrate > 0) {
                int maxSize = VideoConfig.getMaxSizeFromBitrate(targetBitrate);
                transcoderBuilder.maxFrameHeight(maxSize);
                transcoderBuilder.maxFrameWidth(maxSize);
                transcoderBuilder.videoBitRate(targetBitrate);
                transcoderBuilder.iFrameInterval(2);
                transcoderBuilder.frameRate(25);
            }

            final VideoTranscoder videoTranscoder = transcoderBuilder.build(context);

            synchronized (videoTranscoders) {
                for (Map.Entry<MessageReceiver, AbstractMessageModel> entry : messageModels.entrySet()) {
                    AbstractMessageModel messageModel = entry.getValue();
                    String key = cancelTranscoding(messageModel);
                    videoTranscoders.put(key, new WeakReference<>(videoTranscoder));
                }
            }

            final @VideoTranscoder.TranscoderResult int transcoderResult = videoTranscoder.startSync(new VideoTranscoder.Listener() {
                @Override
                public void onStart() {
                    for (Map.Entry<MessageReceiver, AbstractMessageModel> entry : messageModels.entrySet()) {
                        AbstractMessageModel messageModel = entry.getValue();
                        messagePlayerService.setTranscodeStart(messageModel);
                    }
                }

                @Override
                public void onProgress(int progress) {
                    for (Map.Entry<MessageReceiver, AbstractMessageModel> entry : messageModels.entrySet()) {
                        AbstractMessageModel messageModel = entry.getValue();
                        messagePlayerService.setTranscodeProgress(messageModel, progress);
                    }
                }

                @Override
                public void onCanceled() {
                    for (Map.Entry<MessageReceiver, AbstractMessageModel> entry : messageModels.entrySet()) {
                        AbstractMessageModel messageModel = entry.getValue();
                        messagePlayerService.setTranscodeFinished(messageModel, true, null);
                    }
                }

                @Override
                public void onSuccess(VideoTranscoder.Stats stats) {
                    if (stats != null) {
                        logger.debug("success, stats = {}", stats);
                    }
                    for (Map.Entry<MessageReceiver, AbstractMessageModel> entry : messageModels.entrySet()) {
                        AbstractMessageModel messageModel = entry.getValue();
                        messagePlayerService.setTranscodeFinished(messageModel, true, null);
                    }
                }

                @Override
                public void onFailure() {
                    for (Map.Entry<MessageReceiver, AbstractMessageModel> entry : messageModels.entrySet()) {
                        AbstractMessageModel messageModel = entry.getValue();
                        messagePlayerService.setTranscodeFinished(messageModel, false, "Failure");
                    }
                }
            });

            if (transcoderResult != VideoTranscoder.SUCCESS) {
                // failure
                logger.info("Transcoding failure");
                return transcoderResult;
            }

            if (videoTranscoder.hasAudioTranscodingError()) {
                final int errorMessageResource;
                if (videoTranscoder.audioFormatUnsupported()) {
                    errorMessageResource = R.string.transcoder_unsupported_audio_format;
                } else {
                    errorMessageResource = R.string.transcoder_unknown_audio_error;
                }

                RuntimeUtil.runOnUiThread(() -> Toast.makeText(
                    ThreemaApplication.getAppContext(),
                    context.getString(errorMessageResource),
                    Toast.LENGTH_LONG
                ).show());
            }

            // remove original file and set transcoded file as new source file
            deleteTemporaryFile(mediaItem);
            mediaItem.setUri(Uri.fromFile(outputFile));
            mediaItem.setMimeType(MimeUtil.MIME_TYPE_VIDEO_MP4);
        } else {
            logger.info("No transcoding necessary");
        }
        return VideoTranscoder.SUCCESS;
    }

    /**
     * F1Whisper: losslessly crop an attached audio file ({@link MediaItem#TYPE_AUDIO_FILE}) to the
     * trim window the user chose on the preview timeline, mirroring {@link #transcodeVideo}'s trim.
     *
     * <p>Each container is cut losslessly by its own strategy (sniffed from the real magic bytes,
     * not the extension): AAC-in-MP4/m4a is frame-copied via MediaMuxer; MP3 is cut on MPEG-audio
     * frame boundaries; WAV is cut on PCM sample boundaries; Opus/Vorbis-in-Ogg is page-copied via
     * MediaMuxer (API 29+). FLAC and any other decode-only codec are unsupported.
     *
     * <p><b>CRITICAL FAIL-SAFE (data/privacy):</b> the user explicitly asked to trim, so if the trim
     * cannot be performed (unsupported format OR execution failure) this throws
     * {@link ThreemaException} to ABORT the entire send. We must NEVER silently fall back to sending
     * the untrimmed original after a trim request - that would transmit more audio than the user
     * intended to share. The caller turns the exception into a clear error and the user can retry or
     * remove the trim. (If no trim was requested, this is a no-op and the file is sent as-is.)
     *
     * <p>On success the media item's {@link MediaItem#getUri() uri} is repointed at the cropped temp
     * file and its duration is updated to the cropped length so the recipient sees the right runtime.
     */
    @WorkerThread
    private void trimAudio(@NonNull MediaItem mediaItem, @NonNull FileDataModel fileDataModel) throws ThreemaException {
        if (!mediaItem.needsTrimming()) {
            // No trim requested: send the file untouched.
            return;
        }

        final Uri sourceUri = mediaItem.getUri();
        if (sourceUri == null) {
            // A trim was requested but we have no source to trim -> fail-safe abort.
            throw new ThreemaException("Audio trim requested but the source is unavailable; send aborted");
        }

        final AudioTrimmer.TrimMethod method = AudioTrimmer.getTrimMethod(context, sourceUri);
        if (method == AudioTrimmer.TrimMethod.UNSUPPORTED) {
            logger.info("Attached audio cannot be trimmed losslessly; aborting send (fail-safe)");
            showAudioTrimFailedAndAbort(R.string.audio_trim_not_supported);
            return; // showAudioTrimFailedAndAbort always throws; this documents the abort intent
        }

        final String suffix;
        switch (method) {
            case AAC_MP4:
                suffix = ".m4a";
                break;
            case OGG_MUXER:
                suffix = ".ogg";
                break;
            case MP3_FRAMES:
                suffix = ".mp3";
                break;
            case WAV_PCM:
            default:
                suffix = ".wav";
                break;
        }

        final File croppedFile;
        try {
            croppedFile = fileService.createTempFile(".atrim", suffix);
        } catch (IOException e) {
            logger.error("Unable to open temp file for audio trim; aborting send (fail-safe)", e);
            showAudioTrimFailedAndAbort(R.string.audio_trim_failed);
            return; // unreachable; keeps the compiler happy about croppedFile being assigned
        }

        final long startTimeMs = mediaItem.getStartTimeMs();
        final long endTimeMs = mediaItem.getEndTimeMs() == TIME_UNDEFINED
            ? mediaItem.getDurationMs()
            : mediaItem.getEndTimeMs();

        final AudioTrimmer trimmer = new AudioTrimmer(context, sourceUri, startTimeMs, endTimeMs);
        if (!trimmer.trim(croppedFile)) {
            // The user requested a trim that could not be performed. ABORT - do not send anything.
            logger.warn("Audio trim failed; aborting send (fail-safe) - the untrimmed file is NOT sent");
            if (croppedFile.exists() && !croppedFile.delete()) {
                logger.warn("Failed to delete unused audio trim temp file");
            }
            showAudioTrimFailedAndAbort(R.string.audio_trim_failed);
            return; // unreachable
        }

        // Crop succeeded: drop the original (if expendable) and send the cropped clip. Normalize the
        // mime type / extension on the item and the file data model (the latter drives what the
        // recipient sees) to match the produced container; remuxed AAC/Opus get a fresh extension,
        // MP3/WAV keep theirs (we cut in place, same codec).
        deleteTemporaryFile(mediaItem);
        mediaItem.setUri(Uri.fromFile(croppedFile));
        mediaItem.setDeleteAfterUse(true);
        mediaItem.setDurationMs(Math.max(endTimeMs - startTimeMs, DateUtils.SECOND_IN_MILLIS));

        final String newMimeType;
        final String newExtension;
        switch (method) {
            case AAC_MP4:
                newMimeType = MimeUtil.MIME_TYPE_AUDIO_M4A;
                newExtension = ".m4a";
                break;
            case OGG_MUXER:
                newMimeType = MimeUtil.MIME_TYPE_AUDIO_OGG;
                newExtension = ".ogg";
                break;
            case MP3_FRAMES:
                newMimeType = MimeUtil.MIME_TYPE_AUDIO_MPEG;
                newExtension = ".mp3";
                break;
            case WAV_PCM:
            default:
                newMimeType = MimeUtil.MIME_TYPE_AUDIO_WAV;
                newExtension = ".wav";
                break;
        }
        mediaItem.setMimeType(newMimeType);
        fileDataModel.setMimeType(newMimeType);
        final String originalFilename = fileDataModel.getFileName();
        if (originalFilename != null) {
            final int dot = originalFilename.lastIndexOf('.');
            final String base = dot > 0 ? originalFilename.substring(0, dot) : originalFilename;
            fileDataModel.setFileName(base + newExtension);
        }
        // The window is now fully baked into the file; reset so nothing re-trims downstream and the
        // reported duration is the cropped length.
        resetAudioTrimWindow(mediaItem);
    }

    /**
     * F1Whisper: surface a clear audio-trim failure to the user and ABORT the send by throwing.
     * Used for both unsupported formats and trim execution failures so the untrimmed original is
     * never sent after a trim request.
     */
    @WorkerThread
    private void showAudioTrimFailedAndAbort(@StringRes int messageRes) throws ThreemaException {
        final String message = context.getString(messageRes);
        RuntimeUtil.runOnUiThread(() -> Toast.makeText(
            ThreemaApplication.getAppContext(),
            message,
            Toast.LENGTH_LONG
        ).show());
        throw new ThreemaException(message);
    }

    /**
     * F1Whisper: reset an audio item's trim window so {@link MediaItem#needsTrimming()} is false and
     * {@link MediaItem#getTrimmedDurationMs()} returns the item's current full duration. Used after
     * a successful crop (window baked in) and on every fall-back-to-full-file path.
     */
    private static void resetAudioTrimWindow(@NonNull MediaItem mediaItem) {
        mediaItem.setStartTimeMs(0L);
        mediaItem.setEndTimeMs(TIME_UNDEFINED);
    }

    /**
     * Generate a random correlation ID that identifies all media sent in one batch
     *
     * @return correlation Id
     */
    @Override
    public String getCorrelationId() {
        final byte[] random = generateRandomBytes(secureRandom(), 16);
        return toHexString(random);
    }

    @WorkerThread
    private void deleteTemporaryFile(MediaItem mediaItem) {
        if (mediaItem.getDeleteAfterUse()) {
            if (mediaItem.getUri() != null && ContentResolver.SCHEME_FILE.equalsIgnoreCase(mediaItem.getUri().getScheme())) {
                if (mediaItem.getUri().getPath() != null) {
                    FileUtil.deleteFileOrWarn(mediaItem.getUri().getPath(), null, logger);
                }
            }
        }
    }

    /**
     * Check if all chats in the supplied list of MessageReceivers are set to "hidden"
     *
     * @return true if all chats are hidden (i.e. marked as "private"), false if there is at least one chat that is always visible
     */
    private boolean allChatsArePrivate(MessageReceiver[] messageReceivers) {
        for (MessageReceiver messageReceiver : messageReceivers) {
            if (!conversationCategoryService.isPrivateChat(messageReceiver.getUniqueIdString())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Delete message models for specified receivers
     */
    private void markAsTerminallyFailed(
        MessageReceiver<AbstractMessageModel>[] resolvedReceivers,
        Map<MessageReceiver, AbstractMessageModel> messageModels
    ) {
        for (MessageReceiver messageReceiver : resolvedReceivers) {
            remove(messageModels.get(messageReceiver));
        }
    }

    /**
     * Get a byte array for the media represented by the MediaItem leaving room for NaCl Box header
     *
     * @param mediaItem MediaItem containing the Uri of the media
     * @return byte array of the media data or null if error occured
     */
    @WorkerThread
    private byte[] getContentData(MediaItem mediaItem) {
        try (InputStream inputStream = getFromUri(context, mediaItem.getUri())) {
            if (inputStream != null) {
                int fileLength = inputStream.available();

                if (fileLength > MAX_BLOB_SIZE) {
                    String errorMessage = context.getString(R.string.file_too_large, MAX_BLOB_SIZE_MB);
                    logger.info(errorMessage);
                    RuntimeUtil.runOnUiThread(() -> Toast.makeText(ThreemaApplication.getAppContext(), errorMessage, Toast.LENGTH_LONG).show());
                    return null;
                }

                if (fileLength == 0) {
                    // InputStream may not provide size
                    fileLength = MAX_BLOB_SIZE + 1;
                }

                if (ConfigUtils.checkAvailableMemory(fileLength + NaCl.BOX_OVERHEAD_BYTES)) {
                    byte[] fileData = new byte[fileLength + NaCl.BOX_OVERHEAD_BYTES];

                    try {
                        int readCount = 0;
                        try {
                            readCount = copyTo(inputStream, fileData, NaCl.BOX_OVERHEAD_BYTES, fileLength);
                        } catch (Exception e) {
                            // it's OK to get an EOF
                        }

                        if (readCount > MAX_BLOB_SIZE) {
                            String errorMessage = context.getString(R.string.file_too_large, MAX_BLOB_SIZE_MB);
                            logger.info(errorMessage);
                            RuntimeUtil.runOnUiThread(() -> Toast.makeText(ThreemaApplication.getAppContext(), errorMessage, Toast.LENGTH_LONG).show());
                            return null;
                        }

                        if (readCount < fileLength) {
                            return Arrays.copyOf(fileData, readCount + NaCl.BOX_OVERHEAD_BYTES);
                        }

                        return fileData;
                    } catch (OutOfMemoryError e) {
                        logger.error("Unable to create byte array", e);
                    }
                } else {
                    logger.info("Not enough memory to create byte array.");
                }
            } else {
                logger.info("Not enough memory to create byte array.");
            }
        } catch (IOException e) {
            logger.error("Unable to open file to send", e);
        }
        return null;
    }

    /**
     * Save outgoing media item recorded from within the app to gallery if enabled
     */
    @WorkerThread
    private void saveToGallery(MediaItem item) {
        if (item.getType() == MediaItem.TYPE_IMAGE_CAM || item.getType() == MediaItem.TYPE_VIDEO_CAM) {
            if (preferenceService.isSaveMedia()) {
                try {
                    AbstractMessageModel messageModel = new MessageModel();
                    messageModel.setType(item.getType() == TYPE_VIDEO_CAM ? MessageType.VIDEO : MessageType.IMAGE);
                    messageModel.setCreatedAt(new Date());
                    messageModel.setId(0);

                    fileService.copyDecryptedFileIntoGallery(item.getUri(), messageModel);
                } catch (Exception e) {
                    logger.error("Exception", e);
                }
            }
        }
    }

    /**
     * @param message the text message user input
     * @return trimmed message
     */
    @NonNull
    private String validateTextMessage(@NonNull String message) throws ThreemaException {
        // Strip leading/trailing whitespace and throw if nothing is left
        String trimmedMessage = message.trim();

        if (trimmedMessage.isEmpty()) {
            throw new ThreemaException("Tried to send empty message");
        }

        // Check maximum length in UTF-8 bytes (can be reached quickly with Unicode emojis etc.)
        if (message.getBytes(StandardCharsets.UTF_8).length > ProtocolDefines.MAX_TEXT_MESSAGE_LEN) {
            throw new MessageTooLongException();
        }

        return trimmedMessage;
    }
}
