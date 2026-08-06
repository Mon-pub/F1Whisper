package ch.threema.app.messagereceiver;

import android.content.Intent;
import android.graphics.Bitmap;

import org.slf4j.Logger;

import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.app.AppConstants;
import ch.threema.app.emojis.EmojiUtil;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.multidevice.MultiDeviceManager;
import ch.threema.app.services.GroupService;
import ch.threema.app.services.MessageService;
import ch.threema.app.services.OutgoingSendBoundaryDecision;
import ch.threema.app.services.UserService;
import ch.threema.app.tasks.OutboundIncomingGroupMessageUpdateReadTask;
import ch.threema.app.tasks.OutgoingFileMessageTask;
import ch.threema.app.tasks.OutgoingGroupDeleteMessageTask;
import ch.threema.app.tasks.OutgoingGroupDeliveryReceiptMessageTask;
import ch.threema.app.tasks.OutgoingGroupEditMessageTask;
import ch.threema.app.tasks.OutgoingGroupReactionMessageTask;
import ch.threema.app.tasks.OutgoingLocationMessageTask;
import ch.threema.app.tasks.OutgoingPollSetupMessageTask;
import ch.threema.app.tasks.OutgoingPollVoteGroupMessageTask;
import ch.threema.app.tasks.OutgoingTextMessageTask;
import ch.threema.app.utils.BallotUtil;
import ch.threema.app.utils.GroupFeatureSupport;
import ch.threema.app.utils.GroupUtil;
import ch.threema.app.utils.NameUtil;
import ch.threema.base.ThreemaException;
import ch.threema.base.crypto.SymmetricEncryptionResult;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import ch.threema.base.utils.Utils;
import ch.threema.data.datatypes.ContactNameFormat;
import ch.threema.data.models.GroupModel;
import ch.threema.data.models.GroupModelData;
import ch.threema.data.repositories.ContactModelRepository;
import ch.threema.data.repositories.GroupModelRepository;
import ch.threema.domain.models.MessageId;
import ch.threema.domain.protocol.ThreemaFeature;
import ch.threema.domain.protocol.csp.messages.ballot.BallotData;
import ch.threema.domain.protocol.csp.messages.ballot.BallotId;
import ch.threema.domain.protocol.csp.messages.ballot.BallotVote;
import ch.threema.domain.taskmanager.TaskManager;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.protobuf.csp.e2e.Reaction;
import ch.threema.storage.DatabaseService;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.group.GroupMessageModel;
import ch.threema.storage.models.group.GroupModelOld;
import ch.threema.storage.models.MessageState;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.access.GroupAccessModel;
import ch.threema.storage.models.ballot.BallotModel;
import ch.threema.storage.models.data.MessageContentsType;
import ch.threema.storage.models.data.media.FileDataModel;

import static ch.threema.app.utils.MessageUtil.canSendUserAcknowledge;
import static ch.threema.domain.protocol.csp.ProtocolDefines.DELIVERYRECEIPT_MSGUSERACK;
import static ch.threema.domain.protocol.csp.ProtocolDefines.DELIVERYRECEIPT_MSGUSERDEC;

public class GroupMessageReceiver implements MessageReceiver<GroupMessageModel> {
    private static final Logger logger = getThreemaLogger("GroupMessageReceiver");

    private final GroupModelOld group;
    @Nullable
    private final GroupModel groupModel;
    private final GroupService groupService;
    private final DatabaseService databaseService;
    @NonNull
    private final UserService userService;
    @NonNull
    private final ContactModelRepository contactModelRepository;
    @NonNull
    private final GroupModelRepository groupModelRepository;
    private final @NonNull ServiceManager serviceManager;
    private final TaskManager taskManager;
    private final MultiDeviceManager multiDeviceManager;

    public GroupMessageReceiver(
        GroupModelOld group,
        GroupService groupService,
        DatabaseService databaseService,
        @NonNull UserService userService,
        @NonNull ContactModelRepository contactModelRepository,
        @NonNull GroupModelRepository groupModelRepository,
        @NonNull ServiceManager serviceManager
    ) {
        this.group = group;
        this.groupService = groupService;
        this.databaseService = databaseService;
        this.userService = userService;
        this.contactModelRepository = contactModelRepository;
        this.groupModelRepository = groupModelRepository;
        this.serviceManager = serviceManager;
        this.taskManager = serviceManager.getTaskManager();
        this.multiDeviceManager = serviceManager.getMultiDeviceManager();

        this.groupModel = group != null
            ? groupModelRepository.getByCreatorIdentityAndId(group.getCreatorIdentity(), group.getApiGroupId())
            : null;
    }

    @Override
    public GroupMessageModel createLocalModel(MessageType type, @MessageContentsType int messageContentsType, Date postedAt) {
        GroupMessageModel m = new GroupMessageModel();
        m.setType(type);
        m.setMessageContentsType(messageContentsType);
        m.setGroupId(group.getId());
        m.setPostedAt(postedAt);
        // F1Whisper: stamp outgoing createdAt from the server-corrected, monotonic TrustedClock so
        // timestamps are cross-device-comparable even when the phone's own clock is wrong.
        m.setCreatedAt(ch.threema.app.services.TrustedClock.stampNow());
        m.setSaved(false);
        m.setUid(UUID.randomUUID().toString());
        // F1Whisper: stamp the shared group disappearing timer on every model this receiver builds.
        // Authoritative for an OUTGOING group message (it is what gets advertised on the wire and what
        // the sender's own countdown uses); only a provisional placeholder for an INBOUND one, which
        // MessageServiceImpl.applyIncomingFreeze overwrites with the timer the SENDING MEMBER
        // advertised. See ContactMessageReceiver.createLocalModel for the full rationale.
        ch.threema.app.services.DisappearingMessageService.stampOutgoing(
            m, group.getDisappearingMessagesTimerSeconds());
        return m;
    }

    @Override
    @Deprecated
    public GroupMessageModel createAndSaveStatusModel(String statusBody, Date postedAt) {
        GroupMessageModel m = new GroupMessageModel(true);
        m.setType(MessageType.TEXT);
        m.setGroupId(group.getId());
        m.setPostedAt(postedAt);
        m.setCreatedAt(new Date());
        m.setSaved(true);
        m.setUid(UUID.randomUUID().toString());
        m.setBody(statusBody);

        saveLocalModel(m);
        return m;
    }


    @Override
    public boolean saveLocalModel(GroupMessageModel save) {
        return databaseService.getGroupMessageModelFactory().createOrUpdate(save);
    }

    @Override
    public void createAndSendTextMessage(@NonNull GroupMessageModel messageModel) {
        Set<String> otherMembers = groupService.getMembersWithoutUser(group);

        final boolean completesLocally = OutgoingSendBoundaryDecision.completesLocally(
            otherMembers.isEmpty(), hasPendingRemoteCompletion());
        if (completesLocally) {
            // In case the recipients set is empty, we are sending the message in a notes group. In
            // this case we directly set the message state to sent to prevent confusion when the
            // user is offline and therefore the task has not yet been run.
            messageModel.setState(MessageState.SENT);
        }

        // Create and assign a new message id
        messageModel.setMessageId(MessageId.random());
        if (!saveLocalModel(messageModel)) {
            // F1Whisper (seventh fork review, F7-01): the row has gone since this send began. Scheduling now would
            // archive a persistent task whose payload the user has already deleted.
            logger.info("Not scheduling a group text send for {}: its row is gone", messageModel.getId());
            return;
        }
        startNotesGroupCountdown(messageModel, completesLocally);

        bumpLastUpdate();

        // Schedule outgoing text message task
        taskManager.schedule(new OutgoingTextMessageTask(
            messageModel.getId(),
            Type_GROUP,
            otherMembers
        ));
    }

    /**
     * F1Whisper (fifth fork review, F5-02 / F5-06): make a notes-group message's LOCAL completion a durable boundary.
     *
     * <p>The two callers above set {@code SENT} directly before the insert, so a notes-group message is written as
     * terminal to spare the user a spinner for a send that has no recipient. That is the message's completion, but it
     * carried no countdown: the disappearing clock was only started later, by the CSP task, which does not run until the
     * device has a chat-server connection. A notes-group message composed offline was therefore terminal and untimed for
     * as long as the device stayed offline, and the startup repair pass deliberately refuses to fix outgoing rows with no
     * start - so a process death in that window kept it forever.</p>
     *
     * <p>Running the one clock-aware transition here closes it. It is idempotent and column-scoped, and it derives the
     * start from the boundary timestamp, so the later task completion neither repeats nor extends it.</p>
     *
     * <p>F1Whisper (sixth fork review, F6-04): only when the creation really IS the completion. With multi-device active
     * the message still has to be reflected, and the group task stores its completion after that reflection is
     * acknowledged; starting a clock here would race the linked device for the payload, and could win. See
     * {@link OutgoingSendBoundaryDecision#completesLocally}.</p>
     */
    private void startNotesGroupCountdown(@NonNull GroupMessageModel messageModel, boolean completesLocally) {
        if (!completesLocally || messageModel.getState() != MessageState.SENT) {
            return;
        }
        try {
            serviceManager.getMessageService().applyOutgoingStateTransition(
                messageModel,
                MessageState.SENT,
                new Date(),
                null,
                true
            );
        } catch (Exception e) {
            logger.warn("Could not make the notes-group completion durable for {}", messageModel.getApiMessageId(), e);
        }
    }

    public void resendTextMessage(@NonNull GroupMessageModel messageModel, @NonNull Collection<String> recipientIdentities) {
        taskManager.schedule(new OutgoingTextMessageTask(
            messageModel.getId(),
            Type_GROUP,
            getRecipientIdentities(recipientIdentities)
        ));
    }

    @Override
    public void createAndSendLocationMessage(@NonNull GroupMessageModel messageModel) {
        Set<String> otherMembers = groupService.getMembersWithoutUser(group);

        final boolean completesLocally = OutgoingSendBoundaryDecision.completesLocally(
            otherMembers.isEmpty(), hasPendingRemoteCompletion());
        if (completesLocally) {
            // In case the recipients set is empty, we are sending the message in a notes group. In
            // this case we directly set the message state to sent to prevent confusion when the
            // user is offline and therefore the task has not yet been run.
            messageModel.setState(MessageState.SENT);
        }

        // Create and assign a new message id
        messageModel.setMessageId(MessageId.random());
        if (!saveLocalModel(messageModel)) {
            // F1Whisper (seventh fork review, F7-01): see createAndSendTextMessage.
            logger.info("Not scheduling a group location send for {}: its row is gone", messageModel.getId());
            return;
        }
        startNotesGroupCountdown(messageModel, completesLocally);

        bumpLastUpdate();

        // Schedule outgoing text message task
        taskManager.schedule(new OutgoingLocationMessageTask(
            messageModel.getId(),
            Type_GROUP,
            otherMembers
        ));
    }

    public void resendLocationMessage(
        @NonNull GroupMessageModel messageModel,
        @NonNull Collection<String> recipientIdentities
    ) {
        // Schedule outgoing location message task
        taskManager.schedule(new OutgoingLocationMessageTask(
            messageModel.getId(),
            Type_GROUP,
            getRecipientIdentities(recipientIdentities)
        ));
    }

    @Override
    public boolean createAndSendFileMessage(
        @Nullable final byte[] thumbnailBlobId,
        @Nullable final byte[] fileBlobId,
        @Nullable SymmetricEncryptionResult encryptionResult,
        @NonNull final GroupMessageModel messageModel,
        @Nullable Collection<String> recipientIdentities
    ) {
        // Enrich file data model with blob id and encryption key
        FileDataModel modelFileData = messageModel.getFileData();
        modelFileData.setBlobId(fileBlobId);
        if (encryptionResult != null) {
            modelFileData.setEncryptionKey(encryptionResult.getKey());
        }

        // Set file data model again explicitly to enforce that the body of the message is rewritten
        // and therefore updated.
        messageModel.setFileData(modelFileData);

        if (messageModel.getMessageId() == null) {
            messageModel.setMessageId(MessageId.random());
        }
        if (!saveLocalModel(messageModel)) {
            // F1Whisper (seventh fork review, F7-01): see createAndSendTextMessage. This is the deterministic case the
            // review reproduces: the blob is already uploaded, so the archived task would carry a live blob id and key.
            //
            // F1Whisper (eighth fork review, H8-01): and the row deleted for everyone mid-upload refuses the same way.
            // See ContactMessageReceiver#createAndSendFileMessage.
            logger.info("Not scheduling a group file send for {}: its row is gone or was deleted", messageModel.getId());
            return false;
        }

        // Note that lastUpdate lastUpdate was bumped when the file message was created

        // Schedule outgoing text message task
        taskManager.schedule(new OutgoingFileMessageTask(
            messageModel.getId(),
            Type_GROUP,
            getRecipientIdentities(recipientIdentities),
            thumbnailBlobId
        ));
        return true;
    }

    @Override
    public void createAndSendBallotSetupMessage(
        @NonNull final BallotData ballotData,
        @NonNull final BallotModel ballotModel,
        @NonNull GroupMessageModel messageModel,
        @Nullable MessageId messageId,
        @Nullable Collection<String> recipientIdentities,
        @NonNull TriggerSource triggerSource
    ) throws ThreemaException {
        final BallotId ballotId = new BallotId(Utils.hexStringToByteArray(ballotModel.getApiBallotId()));

        // Create a new message id if the given message id is null
        messageModel.setMessageId(messageId != null ? messageId : MessageId.random());
        if (!saveLocalModel(messageModel)) {
            // F1Whisper (seventh fork review, F7-01): see createAndSendTextMessage.
            logger.info("Not scheduling a group poll setup send for {}: its row is gone", messageModel.getId());
            return;
        }

        bumpLastUpdate();

        // Schedule outgoing text message task if this is triggered from local
        if (triggerSource == TriggerSource.LOCAL) {
            taskManager.schedule(new OutgoingPollSetupMessageTask(
                messageModel.getId(),
                Type_GROUP,
                getRecipientIdentities(recipientIdentities),
                ballotId,
                ballotData
            ));
        }
    }

    @Override
    public void createAndSendBallotVoteMessage(
        final BallotVote[] votes,
        final BallotModel ballotModel,
        @NonNull TriggerSource triggerSource
    ) throws ThreemaException {
        // Create message id
        MessageId messageId = MessageId.random();

        final BallotId ballotId = new BallotId(Utils.hexStringToByteArray(ballotModel.getApiBallotId()));

        // F1Whisper CHECKLIST: a checklist always shows everyone's checks live, so its votes must
        // reach ALL members, never just the creator. Pass INTERMEDIATE for checklists so the task's
        // RESULT_ON_CLOSE "send only to creator" branch is bypassed (covers any pre-fix checklist
        // still stored as RESULT_ON_CLOSE). Real polls keep their stored type unchanged.
        final BallotModel.Type effectiveBallotType = BallotUtil.isChecklist(ballotModel)
            ? BallotModel.Type.INTERMEDIATE
            : ballotModel.getType();

        // Schedule outgoing text message task
        taskManager.schedule(new OutgoingPollVoteGroupMessageTask(
            messageId,
            Set.of(groupService.getGroupMemberIdentities(group)),
            ballotId,
            ballotModel.getCreatorIdentity(),
            votes,
            effectiveBallotType,
            group.getApiGroupId(),
            group.getCreatorIdentity()
        ));
    }

    /**
     * Send an incoming message update to mark the message as read. This method only schedules the
     * outgoing group message update if multi device is activated.
     */
    public void sendIncomingMessageUpdateRead(@NonNull Set<MessageId> messageIds, long timestamp) {
        if (multiDeviceManager.isMultiDeviceActive()) {
            taskManager.schedule(
                new OutboundIncomingGroupMessageUpdateReadTask(
                    messageIds,
                    timestamp,
                    group.getApiGroupId(),
                    group.getCreatorIdentity()
                )
            );
        }
    }

    /**
     * F1Whisper (seventh fork review, F7-03): the edit text is no longer passed. See
     * {@link ContactMessageReceiver#sendEditMessage(int, Date)}.
     */
    public void sendEditMessage(int messageModelId, @NonNull Date editedAt) {
        taskManager.schedule(
            new OutgoingGroupEditMessageTask(
                messageModelId,
                MessageId.random(),
                editedAt,
                GroupUtil.getRecipientIdentitiesByFeatureSupport(
                    getFeatureSupport(ThreemaFeature.EDIT_MESSAGES)
                )
            )
        );
    }

    public void sendDeleteMessage(int messageModelId, @NonNull Date deletedAt) {
        taskManager.schedule(
            new OutgoingGroupDeleteMessageTask(
                messageModelId,
                MessageId.random(),
                deletedAt,
                GroupUtil.getRecipientIdentitiesByFeatureSupport(
                    getFeatureSupport(ThreemaFeature.DELETE_MESSAGES)
                )
            )
        );
    }

    /**
     * Send a reaction message to the group. Members who do not support reactions will receive an ack/dec instead
     *
     * @param messageModel  MessageModel the reaction reacts to
     * @param actionCase    The action case of the reaction (WITHDRAW is not backwards compatible and wil not cause an ack/dec to be sent)
     * @param emojiSequence The emoji sequence of the reaction
     * @param reactedAt     The timestamp of the reaction
     */
    public void sendReaction(AbstractMessageModel messageModel, Reaction.ActionCase actionCase, @NonNull String emojiSequence, @NonNull Date reactedAt) {
        // identities that support receiving emoji reactions
        Set<String> emojiReactionsIdentities = GroupUtil.getRecipientIdentitiesByFeatureSupport(getFeatureSupport(ThreemaFeature.EMOJI_REACTIONS));
        // all group identities except sender
        Set<String> identitiesWithoutReactionSupport = groupService.getMembersWithoutUser(group);
        identitiesWithoutReactionSupport.removeAll(emojiReactionsIdentities);

        // Note that there might be no recipients that support emoji reactions but even then we need
        // to schedule this task as it is needed for reflection. It may just not send out any csp
        // messages.
        taskManager.schedule(
            new OutgoingGroupReactionMessageTask(
                messageModel.getId(),
                MessageId.random(),
                actionCase,
                emojiSequence,
                reactedAt,
                emojiReactionsIdentities
            )
        );

        // Fall back to acks for users who do not yet support receiving emoji reactions
        if (actionCase == Reaction.ActionCase.APPLY && !identitiesWithoutReactionSupport.isEmpty() && canSendUserAcknowledge(messageModel)) {
            if (EmojiUtil.isThumbsUpEmoji(emojiSequence)) {
                // send ack to these receivers
                sendGroupDeliveryReceiptWithoutReflection(
                    identitiesWithoutReactionSupport,
                    (GroupMessageModel) messageModel,
                    DELIVERYRECEIPT_MSGUSERACK
                );
            } else if (EmojiUtil.isThumbsDownEmoji(emojiSequence)) {
                // send dec to these receivers
                sendGroupDeliveryReceiptWithoutReflection(
                    identitiesWithoutReactionSupport,
                    (GroupMessageModel) messageModel,
                    DELIVERYRECEIPT_MSGUSERDEC
                );
            }
        }
    }

    /**
     * Send a delivery receipt to the identities specified
     *
     * @param identities   Identities to send the receipt to
     * @param messageModel GroupMessageModel for which a receipt should be sent
     * @param receiptType  Type of receipt (currently only ACK and DEC are supported for groups)
     */
    private void sendGroupDeliveryReceiptWithoutReflection(
        @NonNull Set<String> identities,
        GroupMessageModel messageModel,
        int receiptType
    ) {
        serviceManager.getTaskManager().schedule(
            new OutgoingGroupDeliveryReceiptMessageTask(
                messageModel.getId(),
                receiptType,
                identities
            )
        );
    }

    /**
     * F1Whisper: send a group delivery/read receipt for {@code messageModel} to the given identities
     * (used to report "delivered"/"read" back to the message sender for the group message-details
     * screen). Gating by the "send read receipts" preference happens at the call site.
     */
    public void sendGroupDeliveryReceipt(
        int receiptType,
        @NonNull GroupMessageModel messageModel,
        @NonNull Set<String> toIdentities
    ) {
        if (toIdentities.isEmpty()) {
            return;
        }
        sendGroupDeliveryReceiptWithoutReflection(toIdentities, messageModel, receiptType);
    }

    @Override
    @NonNull
    public List<GroupMessageModel> loadMessages(MessageService.MessageFilter filter) {
        return databaseService.getGroupMessageModelFactory().find(
            group.getId(),
            filter);
    }

    @Override
    public long getMessagesCount() {
        return databaseService.getGroupMessageModelFactory().countMessages(
            group.getId());
    }

    @Override
    public long getUnreadMessagesCount() {
        return databaseService.getGroupMessageModelFactory().countUnreadMessages(
            group.getId());
    }

    @NonNull
    @Override
    public List<GroupMessageModel> getUnreadMessages() {
        return databaseService.getGroupMessageModelFactory().getUnreadMessages(
            group.getId());
    }

    public GroupModelOld getGroup() {
        return group;
    }

    @Nullable
    public GroupModel getGroupModel() {
        return groupModel;
    }

    @Override
    public boolean isEqual(MessageReceiver o) {
        return o instanceof GroupMessageReceiver && ((GroupMessageReceiver) o).getGroup().getId() == getGroup().getId();
    }

    @Override
    public String getDisplayName(@NonNull ContactNameFormat contactNameFormat) {
        // Get new group model to ensure the display name is fresh
        GroupModel groupModel = groupModelRepository.getByCreatorIdentityAndId(
            group.getCreatorIdentity(),
            group.getApiGroupId()
        );
        if (groupModel != null) {
            final @Nullable GroupModelData groupModelData = groupModel.getData();
            if (groupModelData != null) {
                return NameUtil.getGroupDisplayName(
                    groupModelData,
                    contactModelRepository,
                    userService,
                    contactNameFormat
                );
            }
        }

        // In case the new group model cannot be found, we fall back to the old group model
        return NameUtil.getGroupDisplayName(group, groupService, contactNameFormat);
    }

    @Override
    public String getShortName(@NonNull ContactNameFormat contactNameFormat) {
        return getDisplayName(contactNameFormat);
    }

    @Override
    public void prepareIntent(@NonNull Intent intent) {
        intent.putExtra(AppConstants.INTENT_DATA_GROUP_DATABASE_ID, (long) group.getId());
    }

    @Override
    public Bitmap getNotificationAvatar() {
        return groupService.getAvatar(group, false);
    }

    @Override
    public Bitmap getHighResAvatar() {
        return groupService.getAvatar(group, true);
    }

    @Override
    public Bitmap getAvatar() {
        return groupService.getAvatar(group, true, true);
    }

    @Override
    @Deprecated
    public int getUniqueId() {
        return GroupUtil.getUniqueId(group);
    }

    @NonNull
    @Override
    public String getUniqueIdString() {
        return GroupUtil.getUniqueIdString(group);
    }

    @Override
    public boolean isMessageBelongsToMe(AbstractMessageModel message) {
        return message instanceof GroupMessageModel
            && ((GroupMessageModel) message).getGroupId() == group.getId();
    }

    @Override
    public boolean shouldSendMediaData() {
        if (multiDeviceManager.isMultiDeviceActive()) {
            // We need to upload the media in any case (also for notes groups) if multi device is
            // active. In this case the upload is needed as the message is reflected.
            return true;
        }

        // don't really send off group media if user is the only group member left - keep it local
        String[] groupIdentities = groupService.getGroupMemberIdentities(group);
        return groupIdentities.length != 1 || !groupService.isGroupMember(group);
    }

    @Override
    public boolean offerRetry() {
        return false;
    }

    /**
     * F1Whisper (fifth fork review, F5-02): a group message's terminal state comes from the CSP task, unless this is a
     * local-only notes group.
     *
     * <p>Deliberately the same condition as {@link #shouldSendMediaData()}, and not a re-derivation of it: that method
     * already answers "is there a remote recipient, or a multi-device reflection, that this send has to reach?", which is
     * exactly the question of whether some later acknowledgement is the authoritative completion. When it is,
     * {@code OutgoingCspMessageTask.sendGroupMessage} writes the terminal state after the server ack (or, for a
     * multi-device notes group, after the reflect ack). When it is not, there is nothing to wait for and the pipeline's
     * own local completion is the boundary.</p>
     */
    @Override
    public boolean hasPendingRemoteCompletion() {
        return shouldSendMediaData();
    }

    @NonNull
    @Override
    public SendingPermissionValidationResult validateSendingPermission() {
        GroupAccessModel access = groupService.getAccess(getGroup(), true);

        if (access == null) {
            //what?
            return new SendingPermissionValidationResult.Denied();
        }

        if (!access.getCanSendMessageAccess().isAllowed()) {
            return new SendingPermissionValidationResult.Denied(
                access.getCanSendMessageAccess().getNotAllowedTestResourceId()
            );
        }
        return SendingPermissionValidationResult.Valid.INSTANCE;
    }

    @Override
    @MessageReceiverType
    public int getType() {
        return Type_GROUP;
    }

    @Override
    public String[] getIdentities() {
        return groupService.getGroupMemberIdentities(group);
    }

    @Override
    public void bumpLastUpdate() {
        if (group != null) {
            groupService.bumpLastUpdate(group);
        }
    }

    @Override
    @EmojiReactionsSupport
    public int getEmojiReactionSupport() {
        if (groupModel == null) {
            logger.error("Group model in group message receiver is null");
            return Reactions_NONE;
        }

        GroupModelData groupModelData = groupModel.getData();

        if (groupModelData == null) {
            logger.warn("Group model data is null");
            return Reactions_NONE;
        }
        if (!groupModelData.isMember()) {
            return Reactions_NONE;
        }
        if (Boolean.TRUE.equals(groupModel.isNotesGroup())) {
            return Reactions_FULL;
        }

        switch (groupService.getFeatureSupport(groupModelData, ThreemaFeature.EMOJI_REACTIONS).getAdoptionRate()) {
            case PARTIAL:
                return Reactions_PARTIAL;
            case ALL:
                return Reactions_FULL;
            case NONE:
                // Fallthrough
            default:
                return Reactions_NONE; // Handle unknown adoption rates
        }
    }

    @Override
    public @NonNull String toString() {
        return "GroupMessageReceiver (GroupId = " + group.getId() + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GroupMessageReceiver)) return false;
        GroupMessageReceiver that = (GroupMessageReceiver) o;
        return Objects.equals(group, that.group);
    }

    @Override
    public int hashCode() {
        return Objects.hash(group);
    }

    @NonNull
    private Set<String> getRecipientIdentities(@Nullable Collection<String> recipients) {
        if (recipients != null) {
            return new HashSet<>(recipients);
        } else {
            return Set.of(groupService.getGroupMemberIdentities(group));
        }
    }

    private GroupFeatureSupport getFeatureSupport(long feature) {
        if (groupModel == null) {
            logger.error("Cannot get feature support: Group model is null");
            return new GroupFeatureSupport(feature, List.of());
        }
        GroupModelData groupModelData = groupModel.getData();
        if (groupModelData == null) {
            logger.error("Cannot get feature support: Group model data is null");
            return new GroupFeatureSupport(feature, List.of());
        }
        return groupService.getFeatureSupport(groupModelData, feature);
    }
}
