package ch.threema.app.messagereceiver;

import android.content.Intent;
import android.graphics.Bitmap;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.data.datatypes.ContactNameFormat;
import ch.threema.data.datatypes.NotificationTriggerPolicyOverride;
import ch.threema.app.services.MessageService;
import ch.threema.base.ThreemaException;
import ch.threema.base.crypto.SymmetricEncryptionResult;
import ch.threema.data.models.ContactModel;
import ch.threema.data.models.ContactModelData;
import ch.threema.data.models.GroupModel;
import ch.threema.data.models.GroupModelData;
import ch.threema.domain.models.MessageId;
import ch.threema.domain.protocol.csp.messages.ballot.BallotData;
import ch.threema.domain.protocol.csp.messages.ballot.BallotVote;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.ballot.BallotModel;
import ch.threema.storage.models.data.MessageContentsType;

public interface MessageReceiver<M extends AbstractMessageModel> {
    int Type_CONTACT = 0;
    int Type_GROUP = 1;
    int Type_DISTRIBUTION_LIST = 2;

    // Receiver model type annotation
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({Type_CONTACT, Type_GROUP, Type_DISTRIBUTION_LIST})
    @interface MessageReceiverType {
    }

    int Reactions_NONE = 0;
    int Reactions_FULL = 1;
    int Reactions_PARTIAL = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({Reactions_NONE, Reactions_FULL, Reactions_PARTIAL})
    @interface EmojiReactionsSupport {
    }

    /**
     * Return all affected contact message receivers.
     * <p>
     * Note: Only used in a distribution list, other subtypes should return null.
     */
    default @Nullable List<ContactMessageReceiver> getAffectedMessageReceivers() {
        return null;
    }

    /**
     * create a local (unsaved) db model for the given message type
     */
    M createLocalModel(MessageType type, @MessageContentsType int contentsType, Date postedAt);

    /**
     * create a db model for the given message type and save it
     *
     * @deprecated use createAndSaveStatusDataModel instead.
     */
    @Deprecated
    AbstractMessageModel createAndSaveStatusModel(String statusBody, Date postedAt);

    /**
     * save a message model to the database
     *
     * <p>F1Whisper (seventh fork review, F7-01): reports whether the row was written. A {@code false} answer means the
     * model carries an id whose row has gone - hard-deleted, or claimed by the disappearing-message expiry - and it was
     * neither inserted nor updated. A caller that goes on to schedule a persistent send task for that id would be
     * scheduling a transmission of content the user has already deleted, so the schedulers below refuse.</p>
     *
     * <p>F1Whisper (eighth fork review, H8-01): {@code false} also means the row is still there but has been deleted
     * for everyone. A full-row save carries the user's content, and a message deleted for everyone has had its content
     * destroyed on purpose, so the save writes nothing rather than putting it back.</p>
     *
     * @return {@code true} if the model is now persisted.
     */
    boolean saveLocalModel(M messageModel);

    /**
     * send a text message
     */
    void createAndSendTextMessage(@NonNull M messageModel);

    /**
     * send a location message
     */
    void createAndSendLocationMessage(@NonNull M messageModel);

    /**
     * Send a file message: enrich the row with the uploaded blob id and encryption key, then schedule the send.
     *
     * <p>F1Whisper (eighth fork review, H8-01): this is the one scheduler whose caller has to know the answer, so it is
     * the one that reports it. The others are called microseconds after the row was created; this one is called after
     * an upload that ran for seconds or minutes, and the UI lets the user delete the message for everyone throughout.
     * Persistence is what decides - a row deleted meanwhile refuses the write - and a caller that went on to publish a
     * state, a listener or a completion for a message that no longer has content would be announcing the send of
     * something the user watched disappear.</p>
     *
     * @return {@code true} if the enriched row was persisted and the send was scheduled. {@code false} means the row
     * has gone or has been deleted for everyone; nothing was written and nothing was scheduled.
     */
    boolean createAndSendFileMessage(
        @Nullable byte[] thumbnailBlobId,
        @Nullable byte[] fileBlobId,
        @Nullable SymmetricEncryptionResult encryptionResult,
        @NonNull M messageModel,
        @Nullable Collection<String> recipientIdentities
    ) throws ThreemaException;

    /**
     * Send a ballot (create) message. Note that the message is only sent if the trigger source is
     * local. The message id is added to the message model in any case.
     * TODO(ANDR-3518): The trigger source should not be passed until here. This is only a security
     *  measure as the ballot service has many side effects. Ideally, this method would only be
     *  called if a csp message should really be sent out.
     */
    void createAndSendBallotSetupMessage(
        @NonNull final BallotData ballotData,
        @NonNull final BallotModel ballotModel,
        @NonNull M abstractMessageModel,
        @NonNull MessageId messageId,
        @Nullable Collection<String> recipientIdentities,
        @NonNull TriggerSource triggerSource
    ) throws ThreemaException;

    /**
     * Send a ballot vote message. Note that the message is only sent if the trigger source is
     * local.
     * TODO(ANDR-3518): The trigger source should not be passed until here. This is only a security
     *  measure as the ballot service has many side effects. Ideally, this method would only be
     *  called if a csp message should really be sent out.
     */
    void createAndSendBallotVoteMessage(
        BallotVote[] votes,
        BallotModel ballotModel,
        @NonNull TriggerSource triggerSource
    ) throws ThreemaException;

    /**
     * select and filter (if filter is set) all message models
     */
    @NonNull
    List<M> loadMessages(MessageService.MessageFilter filter);

    /**
     * Count messages for this receiver
     */
    long getMessagesCount();

    /**
     * count the unread message
     */
    long getUnreadMessagesCount();

    /**
     * get all unread messages
     *
     * @return a list of unread messages
     */
    @NonNull
    List<M> getUnreadMessages() throws SQLException;

    /**
     * compare
     */
    boolean isEqual(MessageReceiver o);

    /**
     * displaying name in gui
     */
    String getDisplayName(@NonNull ContactNameFormat contactNameFormat);

    /**
     * short displaying name in gui
     */
    String getShortName(@NonNull ContactNameFormat contactNameFormat);

    void prepareIntent(@NonNull Intent intent);

    /**
     * @return the bitmap of the avatar in the notification
     */
    Bitmap getNotificationAvatar();

    /**
     * @return the bitmap of the avatar in maximally available resolution and without being cropped to a circle
     */
    Bitmap getHighResAvatar();

    @Nullable
    Bitmap getAvatar();

    /**
     * @return a unique id
     */
    @Deprecated
    int getUniqueId();

    @NonNull
    String getUniqueIdString();

    /**
     * check, if the message model belongs to this receiver
     */
    boolean isMessageBelongsToMe(AbstractMessageModel message);

    /**
     * check if media should really be sent to this receiver
     * notable exceptions:
     * - distribution lists
     * - groups without members ("notes"), unless MD is active
     */
    boolean shouldSendMediaData();

    /**
     * check if we should offer the user a possibility to retry sending in the UI if the message was queued but there was an IO error in the sender thread
     */
    boolean offerRetry();

    /**
     * F1Whisper (fifth fork review, F5-02): whether a persistent send task will report this message's TERMINAL state
     * later, so the dispatching pipeline must leave it in a pre-terminal state.
     *
     * <p>The defect this exists to answer: the media pipeline chose its post-dispatch state from
     * {@code shouldSendMediaData() && offerRetry()}, and a group returns {@code false} from {@code offerRetry()}, so
     * group media was written as {@code SENT} the instant the {@code OutgoingFileMessageTask} had been SCHEDULED. Task
     * scheduling is asynchronous and task execution waits for a chat-server connection, so with a disappearing timer the
     * countdown started at enqueue: expiry deleted the row while the task was still queued, and on reconnect the task
     * found nothing to load and sent nothing. The media disappeared from the sender without ever reaching the group.
     * {@code offerRetry()} answers a question about the RETRY UI, and was never a send boundary.</p>
     *
     * <p>The default is {@code true} - assume a task will complete the send - because being wrong that way leaves a
     * message showing as still sending, while being wrong the other way destroys it.</p>
     */
    default boolean hasPendingRemoteCompletion() {
        return true;
    }

    /**
     * validate sending permission
     */
    @NonNull
    SendingPermissionValidationResult validateSendingPermission();

    /**
     * type of the receiver
     */
    @MessageReceiverType
    int getType();

    /**
     * all receiving identities
     *
     * @return array of identities
     */
    String[] getIdentities();

    /**
     * Set the `lastUpdate` field of the specified contact to the current date.
     * This will also save the model and notify listeners.
     * <p>
     * Not that this method only has an effect if it is supported by the implementing receiver.
     */
    void bumpLastUpdate();

    /**
     * Check how this particular MessageReceiver supports emoji reactions
     *
     * @return @EmojiReactionsSupport
     */
    @EmojiReactionsSupport
    int getEmojiReactionSupport();

    /**
     * @return The current {@code NotificationTriggerPolicyOverride} for contact- and group-receivers. Distribution lists
     * do not have this setting.
     */
    @Nullable
    default NotificationTriggerPolicyOverride getNotificationTriggerPolicyOverrideOrNull() {
        if (this instanceof ContactMessageReceiver) {
            final @Nullable ContactModel contactModel = ((ContactMessageReceiver) this).getContactModel();
            if (contactModel != null) {
                ContactModelData contactModelData = contactModel.getData();
                return contactModelData != null ? contactModelData.getCurrentNotificationTriggerPolicyOverride() : null;
            }
            return null;
        } else if (this instanceof GroupMessageReceiver) {
            final @Nullable GroupModel groupModel = ((GroupMessageReceiver) this).getGroupModel();
            if (groupModel != null) {
                GroupModelData groupModelData = groupModel.getData();
                return groupModelData != null ? groupModelData.getCurrentNotificationTriggerPolicyOverride() : null;
            }
            return null;
        } else {
            return null;
        }
    }
}
