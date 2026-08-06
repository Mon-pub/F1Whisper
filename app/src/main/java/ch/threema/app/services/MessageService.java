package ch.threema.app.services;

import android.content.Context;
import android.database.sqlite.SQLiteException;
import android.location.Location;
import android.net.Uri;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import ch.threema.app.messagereceiver.GroupMessageReceiver;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.services.notification.NotificationService;
import ch.threema.app.ui.MediaItem;
import ch.threema.app.voip.groupcall.GroupCallDescription;
import ch.threema.base.ProgressListener;
import ch.threema.base.SessionScoped;
import ch.threema.base.ThreemaException;
import ch.threema.domain.models.GroupId;
import ch.threema.domain.models.MessageId;
import ch.threema.domain.protocol.csp.MessageTooLongException;
import ch.threema.domain.protocol.csp.messages.AbstractGroupMessage;
import ch.threema.domain.protocol.csp.messages.AbstractMessage;
import ch.threema.domain.protocol.csp.messages.BadMessageException;
import ch.threema.domain.protocol.csp.messages.ballot.BallotSetupInterface;
import ch.threema.domain.protocol.csp.messages.file.FileData;
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityMode;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.protobuf.csp.e2e.Reaction;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.DistributionListMessageModel;
import ch.threema.storage.models.group.GroupMessageModel;
import ch.threema.storage.models.MessageModel;
import ch.threema.storage.models.MessageState;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.ServerMessageModel;
import ch.threema.storage.models.ballot.BallotModel;
import ch.threema.storage.models.data.DisplayTag;
import ch.threema.storage.models.data.MessageContentsType;
import ch.threema.storage.models.data.status.ForwardSecurityStatusDataModel;
import ch.threema.storage.models.data.status.GroupCallStatusDataModel;
import ch.threema.storage.models.data.status.GroupStatusDataModel;
import ch.threema.storage.models.data.status.VoipStatusDataModel;

/**
 * Handling methods for messages
 */
@SessionScoped
public interface MessageService {
    int FILTER_CHATS = 1;
    int FILTER_GROUPS = 1 << 1;
    int FILTER_INCLUDE_ARCHIVED = 1 << 2;
    int FILTER_STARRED_ONLY = 1 << 3;

    @IntDef(
        flag = true,
        value = {
            FILTER_CHATS,
            FILTER_GROUPS,
            FILTER_INCLUDE_ARCHIVED,
            FILTER_STARRED_ONLY
        }
    )
    @Retention(RetentionPolicy.SOURCE)
    @interface MessageFilterFlags {
    }

    interface CompletionHandler {
        void sendComplete(AbstractMessageModel messageModel);

        void sendQueued(AbstractMessageModel messageModel);

        void sendError(int reason);
    }

    interface MessageFilter {
        /**
         * Max number of messages that are returned with a response
         */
        long getPageSize();

        /**
         * If this returns a non-null value, then only messages that sort strictly BELOW the
         * reference row in the timeline ordering will be returned (keyset pagination over the
         * (effective sort key, id) tuple — see TimelineKeyset).
         */
        Integer getPageReferenceId();

        /**
         * F1Whisper (follow-up review P0-6, second follow-up S2-05): the pagination boundary as
         * ONE immutable {@link ch.threema.storage.PageCursor} — the {@code (effective sort key,
         * id)} tuple captured atomically from an already-loaded model, so the boundary can never
         * be observed torn and never has to re-read a row that may have been deleted between page
         * requests. Consumers snapshot this ONCE per query. When null, the reference id from
         * {@link #getPageReferenceId()} is resolved once at query time (legacy webclient wire
         * cursor; unreachable in the onprem build, where the webclient is disabled).
         */
        @Nullable
        default ch.threema.storage.PageCursor getPageCursor() {
            return null;
        }

        boolean withStatusMessages();

        boolean withUnsaved();

        boolean onlyUnread();

        boolean onlyDownloaded();

        MessageType[] types();

        @MessageContentsType
        int[] contentTypes();

        /* Messages can be tagged with a star or other attributes that affect how they are displayed.
        If the implementation returns an array of tags, the result will be filtered to contain only messages that have one or more of the specified tags set.
        If this method returns null, no filtering for display tags will be performed */
        @DisplayTag
        int[] displayTags();
    }

    class MessageString {
        @Nullable
        private final String message;
        @Nullable
        private final String rawMessage;

        public MessageString(@Nullable String message) {
            this.message = message;
            this.rawMessage = message;
        }

        MessageString(@Nullable String message, @Nullable String rawMessage) {
            this.message = message;
            this.rawMessage = rawMessage;
        }

        @Nullable
        public String getMessage() {
            return message;
        }

        @Nullable
        public String getRawMessage() {
            return rawMessage;
        }
    }

    /**
     * @deprecated use createStatusMessage new style
     */
    @Deprecated
    AbstractMessageModel createStatusMessage(String statusMessage, MessageReceiver receiver);

    AbstractMessageModel createVoipStatus(VoipStatusDataModel data,
                                          MessageReceiver receiver,
                                          boolean isOutbox,
                                          boolean isRead);

    AbstractMessageModel createGroupCallStatus(@NonNull GroupCallStatusDataModel data,
                                               @NonNull MessageReceiver receiver,
                                               @Nullable GroupCallDescription call,
                                               boolean isOutbox,
                                               Date postedDate);

    AbstractMessageModel createForwardSecurityStatus(
        @NonNull MessageReceiver receiver,
        @ForwardSecurityStatusDataModel.ForwardSecurityStatusType int type,
        int quantity,
        @Nullable String staticText);

    /**
     * Create and save a group status message.
     *
     * @param receiver     the receiver
     * @param type         the type
     * @param identity     the identity that will be included in the message (needed for
     *                     MEMBER_ADDED, MEMBER_LEFT, MEMBER_KICKED, FIRST_VOTE, and RECEIVED_VOTE)
     * @param ballotName   the name of the ballot (needed for FIRST_VOTE, MODIFIED_VOTE,
     *                     RECEIVED_VOTE, and VOTES_COMPLETE)
     * @param newGroupName the new group name (needed for RENAMED)
     * @return the group status message model
     */
    AbstractMessageModel createGroupStatus(
        @NonNull GroupMessageReceiver receiver,
        @NonNull GroupStatusDataModel.GroupStatusType type,
        @Nullable String identity,
        @Nullable String ballotName,
        @Nullable String newGroupName
    );

    /**
     * F1Whisper: Create and save a DISAPPEARING_STATUS inline status message announcing a
     * per-conversation timer change ("X set disappearing messages to 1 day" / "turned off").
     *
     * @param receiver         the message receiver (contact or group)
     * @param changedByIdentity the identity that changed the timer; {@code null} = local user
     * @param timerSeconds     new timer in seconds; 0 means "off"
     * @return the newly inserted status message model
     */
    AbstractMessageModel createDisappearingStatus(
        @NonNull MessageReceiver receiver,
        @Nullable String changedByIdentity,
        int timerSeconds
    );

    /**
     * F1Whisper: freeze an INCOMING message at the disappearing timer its SENDER advertised, and
     * start, re-derive or cancel its countdown accordingly.
     *
     * <p>This is the single first-touch transition for an incoming message's disappearing policy,
     * exposed on the interface so that the file-message receive tasks
     * ({@code IncomingContactFileMessageTask} / {@code IncomingGroupFileMessageTask}) apply the
     * <em>same</em> implementation the text/image/location/poll path in
     * {@code processIncomingContactMessage} already does, rather than a second copy of it.
     *
     * <p>Those two tasks build their message model by hand instead of going through
     * {@code MessageReceiver.createLocalModel}, so before this existed they froze nothing at all:
     * every incoming file, video, voice message and document ignored the sender's advertised timer
     * and silently fell back to the RECIPIENT's own conversation setting at read time. That is the
     * policy defeat the per-message-timer wave closed for text, left wide open on the path that
     * carries most media.
     *
     * <p>Must be called AFTER the model has been saved (it re-reads the row) and, where possible,
     * BEFORE the new-message listener fires, so a concurrent mark-as-read sees the frozen value.
     *
     * @param messageModel       the freshly saved incoming model
     * @param advertisedBySender {@code AbstractMessage.getDisappearingTimerSeconds()} — {@code null}
     *                           when the sender transmitted no timer (a pre-v6.4.3-38 client)
     */
    /**
     * F1Whisper (fourth fork review, F4-05): stamp the timer the SENDER advertised onto an incoming model that has NOT
     * been written yet, so that accepting the message and accepting its policy are ONE write.
     *
     * <p>The sibling of {@link #freezeIncomingDisappearingPolicy}, and needed because that one re-reads the row and can
     * therefore only correct a message that is already stored. Inserting first and freezing second means a process death
     * between the two leaves the wrong policy on the row permanently: the server redelivers, the duplicate guard sees a
     * stored message, returns success, and nothing ever revisits the timer.
     *
     * <p>Model-only: performs no read and no save. The caller's own insert is what persists it.
     *
     * @param messageModel       an incoming model that is about to be written for the first time
     * @param advertisedBySender {@code AbstractMessage.getDisappearingTimerSeconds()} — {@code null} when the sender
     *                           transmitted no timer (a pre-v6.4.3-38 client)
     */
    void freezeIncomingDisappearingPolicyBeforeFirstWrite(
        @NonNull AbstractMessageModel messageModel,
        @Nullable Integer advertisedBySender
    );

    void freezeIncomingDisappearingPolicy(
        @NonNull AbstractMessageModel messageModel,
        @Nullable Integer advertisedBySender
    );

    AbstractMessageModel sendText(String message, MessageReceiver receiver) throws Exception;

    AbstractMessageModel sendLocation(@NonNull Location location, @Nullable String poiName, MessageReceiver receiver, CompletionHandler completionHandler) throws ThreemaException;

    /**
     * Edit a message's text, send it to a receiver and save the edited message as described in saveEditedMessageText.
     *
     * @param message original message to edit
     * @param newText new message text
     */
    void sendEditedMessageText(@NonNull AbstractMessageModel message, @NonNull String newText, @NonNull Date editedAt, @NonNull MessageReceiver receiver) throws Exception;

    /**
     * Save the edited text of a message. If editedAt is not null, an edit history entry will be created with the previous text of the message.
     * Note that if editedAt is null, the message will not be marked as edited
     *
     * @param message  Message model containing the previous text of the message
     * @param text     the new text for this message
     * @param editedAt the date when the message was edited or null
     *
     * <p>F1Whisper (seventh fork review, F7-03): the history entry and the row change are ONE transaction against a
     * freshly reloaded, undeleted row. Either both land or neither does, so a delete-for-everyone racing an edit can no
     * longer leave the old plaintext behind as a history entry pointing at a row it already emptied.</p>
     *
     * @return whether the edit committed. A {@code false} answer means the row was gone, deleted for everyone, or
     * superseded, and nothing at all was written or published.
     */
    boolean saveEditedMessageText(@NonNull AbstractMessageModel message, String text, @Nullable Date editedAt);

    /**
     * Save a reaction message
     *
     * @param targetMessage  Message model this reaction refers to
     * @param senderIdentity Identity of the sender of this message
     * @param actionCase     The action to take
     * @param emojiSequence  The emoji for the reaction
     * @return True if the reaction message was saved successfully
     */
    boolean saveEmojiReactionMessage(@NonNull AbstractMessageModel targetMessage, @NonNull String senderIdentity, @Nullable Reaction.ActionCase actionCase, @NonNull String emojiSequence);

    /**
     * Clear the MessageState of the supplied message if the current state is either USERACK or USERDEC
     *
     * @param targetMessage Message to clear the state for
     */
    // TODO(ANDR-3325): Remove ACK/DEC compatibility
    void clearMessageState(@NonNull AbstractMessageModel targetMessage);

    /**
     * Send an emoji reaction to a receiver and save it locally.
     * If the emoji reaction is not a fully-qualified emoji sequence, nothing is sent and `true` is returned
     * Performs "Legacy Reaction Mapping Steps" and sends an ack / dec message to some or all receivers instead if applicable.
     *
     * @param message       message to react to
     * @param emojiSequence emoji sequence of the reaction
     * @param receiver      receiver to send the reaction to
     * @param markAsRead    true if the message should be marked as read
     * @return false if and only if sending failed for compatibility reasons
     * true if the reaction has been sent, or the emojiSequence is not a fully-qualified emoji sequence
     */
    @WorkerThread
    boolean sendEmojiReaction(@NonNull AbstractMessageModel message, @NonNull String emojiSequence, @NonNull MessageReceiver receiver, boolean markAsRead) throws ThreemaException;

    /**
     * Delete a message's content and any related data (e.g. edit history, emoji reactions)
     *
     * @param message original message to delete
     *
     * <p>F1Whisper (seventh fork review, F7-02): the row is marked deleted and emptied BEFORE its files and related
     * records are removed, so the mark is what authorises the cleanup rather than following it.</p>
     *
     * @return whether this caller won the row. {@code false} means it had already gone or had already been deleted for
     * everyone, and nothing was cleaned up or published here.
     */
    boolean deleteMessageContentsAndRelatedData(@NonNull AbstractMessageModel message, Date deletedAt);

    String getCorrelationId();

    @AnyThread
    void sendMediaAsync(@NonNull List<MediaItem> mediaItems, @NonNull List<MessageReceiver> messageReceivers);

    @AnyThread
    void sendMediaAsync(@NonNull List<MediaItem> mediaItems, @NonNull List<MessageReceiver> messageReceivers, @Nullable MessageServiceImpl.SendResultListener sendResultListener);

    @AnyThread
    void sendMediaSingleThread(
        @NonNull List<MediaItem> mediaItems,
        @NonNull List<MessageReceiver> messageReceivers
    );

    @WorkerThread
    AbstractMessageModel sendMedia(@NonNull List<MediaItem> mediaItems, @NonNull List<MessageReceiver> messageReceivers, @Nullable MessageServiceImpl.SendResultListener sendResultListener);

    /**
     * Resend the message. Note that this is always triggered by a user interaction and therefore
     * creates a new task.
     *
     * @param messageModel      the message model of the failed message
     * @param receiver          the receiver of the message
     * @param completionHandler the completion handler that is triggered on completion
     */
    void resendMessage(
        @NonNull AbstractMessageModel messageModel,
        @NonNull MessageReceiver<AbstractMessageModel> receiver,
        @Nullable CompletionHandler completionHandler,
        @NonNull Collection<String> recipientIdentities,
        @NonNull MessageId messageId,
        @NonNull TriggerSource triggerSource
    ) throws Exception;

    /**
     * F1Whisper auto-resend: silently re-send an auto-eligible unsent outgoing message once
     * connectivity has returned (reconnect/startup scan), reusing the message's ORIGINAL
     * apiMessageId so the receiver dedupes a redelivery. Accepts in-flight (process-death) states
     * in addition to connectivity-class SENDFAILED; never resends a terminal failure. Unlike
     * {@link #resendMessage} this is not a user interaction and mints no random message id.
     *
     * <p>Resolves the receiver and recipient identities from the model internally (single identity
     * for 1:1, current group members for groups; distribution lists are not auto-resent).
     *
     * @param messageModel  the auto-eligible unsent message model
     * @param triggerSource the trigger source (LOCAL for the auto path)
     */
    @WorkerThread
    void autoResendMessage(
        @NonNull AbstractMessageModel messageModel,
        @NonNull TriggerSource triggerSource
    ) throws Exception;

    /**
     * F1Whisper auto-resend: mark an outgoing message that has exhausted the 24h auto-resend window
     * (still unsent, non-terminal) as terminally SENDFAILED, so the unsent-message notification
     * finally nags the user once. No-op if the message was deleted or is no longer in an unsent
     * state (it may have been resent or manually retried in the meantime).
     *
     * @param messageModel the aged-out unsent message model
     */
    @WorkerThread
    void markAgedOutUnsentFailed(@NonNull AbstractMessageModel messageModel);

    AbstractMessageModel sendBallotMessage(
        @NonNull BallotModel ballotModel,
        @NonNull MessageId messageId,
        @NonNull TriggerSource triggerSource
    ) throws MessageTooLongException;

    /**
     * Update message state of an outgoing message. Note that the state is only changed if it is a
     * legal transition. E.g. a message's state won't be changed from read to delivered. See
     * {@link ch.threema.app.utils.MessageUtil#canChangeToState(MessageState, MessageState, boolean)}
     * for possible state transitions.
     * <p>
     * The corresponding timestamps are changed in any case. E.g. the delivered at timestamp will be
     * saved even if the message has already been marked as read.
     * <p>
     * Do not use this method for reactions: Use
     * {@link #addMessageReaction(AbstractMessageModel, MessageState, String, Date)} instead.
     *
     * @param messageModel the message model that should be updated
     * @param state        the mew state
     * @param date         the date of the state change
     */
    void updateOutgoingMessageState(
        @NonNull final AbstractMessageModel messageModel,
        @NonNull MessageState state,
        @NonNull Date date
    );

    /**
     * F1Whisper (fifth fork review, F5-06): the ONE clock-aware, durable outgoing transition, for the writers that cannot
     * use {@link #updateOutgoingMessageState}.
     *
     * <p>State, its timestamp, the modified timestamp, the terminal-failure display bit, the forward-security mode and
     * the disappearing countdown are ONE conditional update-only write against the current row. It cannot insert and
     * cannot contradict a delete-for-everyone, the countdown start is derived from {@code transitionAt} rather than from
     * "now", and the start may only ever move earlier.</p>
     *
     * <p>Terminal state and countdown used to be two writes, so a process death between them left a sent message with no
     * deadline - permanently, because the startup repair pass deliberately refuses outgoing rows with no start.</p>
     *
     * @param forwardSecurityMode written in the same transition when the caller has it; {@code null} leaves it alone.
     * @param bypassStateGate     for the two callers that deliberately record a state
     *                            {@link ch.threema.app.utils.MessageUtil#canChangeToState} would refuse: the group
     *                            completion, which must stamp {@code postedAt} even when the outcome is
     *                            {@code FS_KEY_MISMATCH}, and the task-layer terminal failure, which must set the
     *                            non-retryable marker whatever state the message is in.
     * @return whether anything was written.
     */
    @WorkerThread
    boolean applyOutgoingStateTransition(
        @NonNull AbstractMessageModel messageModel,
        @NonNull MessageState state,
        @NonNull Date transitionAt,
        @Nullable ForwardSecurityMode forwardSecurityMode,
        boolean bypassStateGate
    );

    /**
     * F1Whisper (fifth fork review, F5-06): persist the forward-security mode alone, conditionally.
     *
     * <p>It arrives in a callback AFTER the terminal transition, and used to be persisted by full-row-saving the detached
     * model that callback had captured - which could recreate a row deleted in between and wrote back the whole of a
     * superseded snapshot, undoing the state and clock the terminal transition had just established.</p>
     */
    @WorkerThread
    boolean updateForwardSecurityMode(@NonNull AbstractMessageModel messageModel, @NonNull ForwardSecurityMode mode);

    /**
     * F1Whisper (sixth fork review, F6-01): flip one display-tag bit, conditionally, writing that column and no other.
     *
     * <p>Starring and pinning are the only lifecycle-adjacent operations the user can perform on a message that is
     * ALREADY on screen, and the model they act on is a timeline instance loaded when the page was. Persisting them with
     * a full-row save wrote that whole page-load snapshot back: a countdown started since, a read state, a terminal send
     * state, all reverted, and a message claimed by expiry between the tap and the save recreated with its body. The bit
     * is recomputed from the row's own current bitmask, so it also cannot clobber a bit set concurrently by something
     * else - the terminal-failure marker, or the other of star and pin.</p>
     *
     * <p>Not annotated {@code @WorkerThread}: both toggles are menu actions that ran on the UI thread through
     * {@code saveLocalModel} before, and this write is strictly smaller than the full-row save it replaces.</p>
     *
     * @return whether the row was updated. The model is updated to match when it was.
     */
    boolean toggleDisplayTag(@NonNull AbstractMessageModel messageModel, int tag);

    /**
     * F1Whisper (sixth fork review, F6-01): clear one display-tag bit. See {@link #toggleDisplayTag}.
     */
    boolean clearDisplayTag(@NonNull AbstractMessageModel messageModel, int tag);

    /**
     * Add a reaction to a contact or group message.
     *
     * @param messageModel the message model that should be updated
     * @param state        the reaction (as state, but only ACK and DEC allowed)
     * @param fromIdentity the identity that reacted to the message
     * @param date         the date of the state change
     */
    void addMessageReaction(
        @NonNull AbstractMessageModel messageModel,
        @NonNull MessageState state,
        @NonNull String fromIdentity,
        @NonNull Date date
    );

    /**
     * F1Whisper: send a "delivered" group receipt for an incoming group message to its sender, gated
     * by the "send read receipts" preference. No-op for outgoing messages or if receipts are off.
     */
    void sendGroupDeliveredReceipt(@NonNull GroupMessageModel messageModel);

    /**
     * F1Whisper: record a per-member delivered/read state for a group message (drives the
     * "Read by / Delivered to" sections of the group message-details screen). READ is never
     * downgraded to DELIVERED.
     */
    void addGroupMessageState(
        @NonNull GroupMessageModel messageModel,
        @NonNull MessageState state,
        @NonNull String fromIdentity
    );

    /**
     * F1Whisper: a single group member's delivered/read state for the group message-details screen.
     */
    class GroupReceiptState {
        @NonNull
        public final String identity;
        @NonNull
        public final String displayName;
        /** {@link MessageState#READ}, {@link MessageState#DELIVERED}, or {@code null} = sent only. */
        @Nullable
        public final MessageState state;

        public GroupReceiptState(@NonNull String identity, @NonNull String displayName, @Nullable MessageState state) {
            this.identity = identity;
            this.displayName = displayName;
            this.state = state;
        }
    }

    /**
     * F1Whisper: for an OUTGOING group message, the per-member delivered/read state of every other
     * member (drives the "Read by / Delivered to / Sent to" sections). Empty for incoming or
     * non-group messages.
     */
    @NonNull
    List<GroupReceiptState> getGroupReceiptStates(@NonNull GroupMessageModel messageModel);

    boolean markAsRead(AbstractMessageModel message, boolean silent);

    /**
     * F1Whisper (sixth fork review, F6-01): record a read that happened on ANOTHER of this user's devices.
     *
     * <p>The multi-device reflected read used to set three fields on a cache-resolved model and full-row-save it, which
     * is the same defect as the local first read had: it could recreate a message deleted while the reflection was in
     * flight, and it wrote back the whole of a snapshot cached before whatever else had touched the row. It also started
     * NO countdown, so a disappearing message read on a linked device was marked read on the phone and then kept
     * forever - the one state from which the startup repair pass deliberately declines to help.</p>
     *
     * <p>It therefore goes through exactly the write the local read goes through. No receipt is sent: the device that
     * performed the read owes that, not this one.</p>
     *
     * @return whether the read was recorded here.
     */
    @WorkerThread
    boolean markAsReadFromSync(@NonNull AbstractMessageModel message, @NonNull Date readAt);

    /**
     * F1Whisper (sixth fork review, F6-01): store the instant an incoming message was received, and nothing else.
     *
     * <p>The row's immutable sort key is derived from this timestamp, so both columns are written together; every other
     * column belongs to whoever last wrote it.</p>
     */
    @WorkerThread
    boolean updateReceivedTimestamp(@NonNull AbstractMessageModel message, @NonNull Date receivedAt);

    /**
     * F1Whisper (seventh fork review, F7-05): store the street address a reverse-geocoding lookup resolved for a
     * location message, and nothing else.
     *
     * <p>Deliberately NOT {@code @WorkerThread}: the lookup completes on a UI handler, exactly as before. What changed
     * is that it no longer full-row-saves the timeline instance it was given, which wrote back that instance's captured
     * state and countdown over whatever the send task had established since.</p>
     *
     * @return whether the address was stored.
     */
    boolean updateLocationAddress(@NonNull AbstractMessageModel message, @Nullable String address);

    @WorkerThread
    boolean markAsConsumed(AbstractMessageModel message) throws ThreemaException;

    /**
     * F1Whisper (fifth fork review, F5-04): a change to the serialised media metadata of one message, applied to the row
     * as it CURRENTLY is rather than to the instance the caller happens to hold.
     */
    interface MediaMetadataMutation {
        /**
         * @param current a model freshly read from the database. Change its media/file data model in place.
         * @return whether anything was changed and therefore needs writing.
         */
        boolean apply(@NonNull AbstractMessageModel current);
    }

    /**
     * F1Whisper (fifth fork review, F5-04): persist a media-metadata change as a conditional, non-inserting write of the
     * body column alone.
     *
     * <p>Listen-once claimed/consumed and media downloaded state all live inside the serialised body, so two transitions
     * deciding from two different reads of it used to discard one another's flags - and because they persisted through
     * {@code save}, a full-row upsert, either could also recreate a row hard-deleted while it was working, or restore the
     * body over a delete-for-everyone.</p>
     *
     * <p>{@code mutation} is applied to a FRESHLY READ model and the write is conditional on the body it read, so a
     * concurrent change makes the write fail; the caller's mutation is then re-applied to the new body and retried. That
     * is the reload-merge-retry the review asks for, expressed once instead of at each call site.</p>
     *
     * @return {@code true} if the change was written. {@code false} means the row had gone, had been deleted for
     * everyone, the mutation found nothing to change, or the write kept losing to concurrent ones - never that an insert
     * happened.
     */
    @WorkerThread
    boolean updateMediaMetadata(@NonNull AbstractMessageModel messageModel, @NonNull MediaMetadataMutation mutation);

    /**
     * F1Whisper (fifth fork review, F5-04): {@link #updateMediaMetadata} plus the move to {@link MessageState#CONSUMED},
     * as ONE conditional write.
     *
     * <p>Burning a listen-once message is a single fact - it is consumed, its media is gone, and its flags say so - and
     * persisting it as a state save followed by a metadata save left a window in which a process death could produce a
     * message that was CONSUMED but still advertised itself as playable, or the reverse.</p>
     */
    @WorkerThread
    boolean consumeAndUpdateMediaMetadata(@NonNull AbstractMessageModel messageModel, @NonNull MediaMetadataMutation mutation);

    void remove(AbstractMessageModel messageModel);

    /**
     * if silent is true, no event will be fired on delete
     */
    void remove(AbstractMessageModel messageModel, boolean silent);

    /**
     * F1Whisper (fifth fork review, F5-04): remove {@code messageModel} if, and only if, it is still the overdue row the
     * caller decided about, and report whether this caller now OWNS that removal.
     *
     * <p>Expiry is the one removal whose authorisation can expire between the decision and the act: the timer can be
     * turned off by a duplicate advertising an explicit OFF, or the deadline re-derived by a freeze correcting the
     * sender's value. The conditional delete inside re-checks timer, start, deadline, deletion state and due-ness at write
     * time, so the caller performs the file, cache and ballot side effects only for a row it actually claimed.</p>
     *
     * @param nowMillis the instant enforcement is happening at.
     * @return {@code true} if the row was claimed and removed.
     */
    @WorkerThread
    boolean removeIfStillDue(@NonNull AbstractMessageModel messageModel, long nowMillis);

    /**
     * Delete a message's content and send a delete message to a receiver. Any edit history entries
     * belonging to this message will also be deleted.
     */
    void sendDeleteMessage(@NonNull AbstractMessageModel messageModel, @NonNull MessageReceiver receiver) throws Exception;

    /**
     * Process an incoming contact message. Note that this method must not be used for voip and poll
     * vote messages.
     *
     * @param message the received contact message
     * @param triggerSource the trigger source
     * @return true if processing the message was successful, false if the message should be discarded
     * @throws Exception if processing the message failed
     */
    boolean processIncomingContactMessage(AbstractMessage message, @NonNull TriggerSource triggerSource) throws Exception;

    /**
     * Process an incoming group message. Note that this method must not be used for group control
     * messages. Additionally, the common group receive steps must be executed before calling this
     * method.
     *
     * @param message the received group message
     * @param triggerSource the trigger source of the incoming message
     * @return true if processing the message was successful, false if the message should be discarded
     * @throws Exception if processing the message failed
     */
    boolean processIncomingGroupMessage(
        @NonNull AbstractGroupMessage message,
        @NonNull TriggerSource triggerSource
    ) throws Exception;

    @WorkerThread
    @NonNull
    List<AbstractMessageModel> getMessagesForReceiver(@NonNull MessageReceiver receiver, MessageFilter messageFilter, boolean appendUnreadMessage);

    @WorkerThread
    @NonNull
    List<AbstractMessageModel> getMessagesForReceiver(@NonNull MessageReceiver receiver, MessageFilter messageFilter);

    @WorkerThread
    List<AbstractMessageModel> getMessagesForReceiver(@NonNull MessageReceiver receiver);

    List<AbstractMessageModel> getMessageForBallot(BallotModel ballotModel);

    @Nullable
    MessageModel getContactMessageModel(final Integer id);

    @Nullable
    MessageModel getContactMessageModel(String uid);

    @Nullable
    GroupMessageModel getGroupMessageModel(final Integer id);

    @Nullable
    GroupMessageModel getGroupMessageModel(String uid);

    @Nullable
    DistributionListMessageModel getDistributionListMessageModel(long id);

    /**
     * Get the contact message model by message id and identity.
     */
    @Nullable
    MessageModel getContactMessageModel(
        @NonNull final MessageId messageId,
        @NonNull final String identity
    );

    /**
     * Get the group message model by message id, creator identity, and group id.
     */
    @Nullable
    GroupMessageModel getGroupMessageModel(
        @NonNull MessageId messageId,
        @NonNull String creatorIdentity,
        @NonNull GroupId groupId
    );

    MessageString getMessageString(AbstractMessageModel messageModel, int maxLength);

    MessageString getMessageString(AbstractMessageModel messageModel, int maxLength, boolean withPrefix);

    void saveIncomingServerMessage(ServerMessageModel msg);

    boolean downloadThumbnailIfPresent(@NonNull FileData fileData, @NonNull AbstractMessageModel messageModel) throws Exception;

    boolean shouldAutoDownload(@NonNull AbstractMessageModel messageModel);

    boolean downloadMediaMessage(AbstractMessageModel mediaMessageModel, ProgressListener progressListener) throws Exception;

    boolean cancelMessageDownload(AbstractMessageModel messageModel);

    void cancelMessageUpload(AbstractMessageModel messageModel);

    AbstractMessageModel saveBallotCreateMessage(
        @NonNull MessageReceiver<?> receiver,
        @NonNull MessageId messageId,
        @NonNull BallotSetupInterface message,
        @Nullable AbstractMessageModel messageModel,
        int messageFlags,
        @Nullable ForwardSecurityMode forwardSecurityMode,
        @NonNull TriggerSource triggerSource
    ) throws ThreemaException, BadMessageException;

    /**
     * Get all messages in any chat that match the specified criteria - excluding distribution lists
     *
     * @param queryString   Substring to match or null to match all messages
     * @param filterFlags   @MessageFilterFlags for this query
     * @param sortAscending Date sort order of results. true = oldest messages first, false = newest messages first
     * @return A list of matching message models
     */
    @NonNull
    List<AbstractMessageModel> getMessagesForText(@Nullable String queryString, @MessageService.MessageFilterFlags int filterFlags, boolean sortAscending);


    /**
     * Remove the "star" display tag from all messages
     *
     * @return number of affected messages
     */
    @WorkerThread
    int unstarAllMessages();

    @WorkerThread
    long countStarredMessages() throws SQLiteException;

    void removeAll() throws SQLException, IOException, ThreemaException;

    /**
     * Persist {@code messageModel} in full and reconcile the service caches with the result.
     *
     * <p>F1Whisper (seventh fork review, F7-01): this reports whether the row was written, and a {@code false} answer
     * is a REFUSAL, not a detail. A model that carries an id whose row has gone is neither inserted nor admitted to the
     * cache, and every cached instance of that id is evicted, because the caches are what the persistent send tasks
     * read through: a deleted payload left in the cache is a payload that can still be transmitted.</p>
     *
     * @return {@code true} if the model is now persisted (and cached); {@code false} if its row no longer exists.
     */
    boolean save(AbstractMessageModel messageModel);

    void markConversationAsRead(MessageReceiver messageReceiver, NotificationService notificationService);

    /**
     * count all message records (normal, group and distribution lists)
     */
    long getTotalMessageCount();

    boolean shareMediaMessages(Context context, ArrayList<AbstractMessageModel> models, ArrayList<Uri> shareFileUris, String caption);

    boolean viewMediaMessage(Context context, AbstractMessageModel model, Uri uri);

    boolean shareTextMessage(Context context, AbstractMessageModel model);

    AbstractMessageModel getMessageModelFromId(int id, String type);

    @Nullable
    AbstractMessageModel getMessageModelByApiMessageIdAndReceiver(@Nullable String id, @NonNull MessageReceiver messageReceiver);

    void cancelVideoTranscoding(AbstractMessageModel messageModel);

    /**
     * Create a message receiver for the specified message model
     *
     * @param messageModel AbstractMessageModel to create a receiver for
     * @throws ThreemaException in case no MessageReceiver could be created or the AbstractMessageModel is none of the three possible message types
     */
    MessageReceiver getMessageReceiver(AbstractMessageModel messageModel) throws ThreemaException;
}
