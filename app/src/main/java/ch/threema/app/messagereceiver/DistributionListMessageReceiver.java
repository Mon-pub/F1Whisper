package ch.threema.app.messagereceiver;

import android.graphics.Bitmap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.app.services.ContactService;
import ch.threema.app.services.DistributionListService;
import ch.threema.app.services.MessageService;
import ch.threema.app.utils.NameUtil;
import ch.threema.base.crypto.SymmetricEncryptionResult;
import ch.threema.data.datatypes.ContactNameFormat;
import ch.threema.data.datatypes.ConversationId;
import ch.threema.data.datatypes.DistributionListConversationId;
import ch.threema.data.datatypes.NotificationTriggerPolicyOverride;
import ch.threema.domain.models.MessageId;
import ch.threema.domain.protocol.csp.messages.poll.PollData;
import ch.threema.domain.protocol.csp.messages.poll.PollVote;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.storage.DatabaseService;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.DistributionListMessageModel;
import ch.threema.storage.models.DistributionListModel;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.poll.PollModel;
import ch.threema.storage.models.data.MessageContentsType;

public class DistributionListMessageReceiver implements MessageReceiver<DistributionListMessageModel> {
    private final List<ContactMessageReceiver> affectedMessageReceivers = new ArrayList<>();

    private final DatabaseService databaseService;
    private final DistributionListModel distributionListModel;
    private final DistributionListService distributionListService;

    public DistributionListMessageReceiver(
        DatabaseService databaseService,
        ContactService contactService,
        DistributionListModel distributionListModel,
        DistributionListService distributionListService
    ) {
        this.databaseService = databaseService;
        this.distributionListModel = distributionListModel;
        this.distributionListService = distributionListService;

        for (ContactModel c : this.distributionListService.getMembers(this.distributionListModel)) {
            ContactMessageReceiver contactMessageReceiver = contactService.createReceiver(c);
            this.affectedMessageReceivers.add(new DistributionListContactMessageReceiver(contactMessageReceiver));
        }
    }


    public DistributionListModel getDistributionList() {
        return this.distributionListModel;
    }

    /**
     * Return the {@link ContactMessageReceiver} instances that receive messages sent to this distribution list.
     */
    @Override
    public @Nullable List<ContactMessageReceiver> getAffectedMessageReceivers() {
        return this.affectedMessageReceivers;
    }

    @NonNull
    @Override
    public DistributionListMessageModel createLocalModel(
        @Nullable final MessageId messageId,
        final MessageType type,
        @MessageContentsType int messageContentsType,
        final Instant postedAt
    ) {
        DistributionListMessageModel m = new DistributionListMessageModel();
        if (type != null && type.getRequiresMessageId()) {
            m.setMessageId(messageId != null ? messageId : MessageId.random());
        }
        m.setDistributionListId(this.getDistributionList().getId());
        m.setType(type);
        m.setMessageContentsType(messageContentsType);
        m.setPostedAt(postedAt);
        // F1Whisper: stamp outgoing createdAt from the server-corrected, monotonic TrustedClock so
        // timestamps are cross-device-comparable even when the phone's own clock is wrong.
        m.setCreatedAt(Instant.ofEpochMilli(ch.threema.app.services.TrustedClock.stampNowMillis()));
        m.setSaved(false);
        m.setUid(UUID.randomUUID().toString());

        return m;
    }

    @Override
    @Deprecated
    public DistributionListMessageModel createAndSaveStatusModel(final String statusBody, final Instant postedAt) {
        DistributionListMessageModel m = new DistributionListMessageModel(true);
        m.setDistributionListId(this.getDistributionList().getId());
        m.setType(MessageType.TEXT);
        m.setPostedAt(postedAt);
        m.setCreatedAt(Instant.now());
        m.setSaved(true);
        m.setUid(UUID.randomUUID().toString());
        m.setBody(statusBody);

        this.saveLocalModel(m);

        return m;
    }

    @Override
    public boolean saveLocalModel(final DistributionListMessageModel save) {
        return this.databaseService.getDistributionListMessageModelFactory().createOrUpdate(save);
    }

    private void unarchiveDistributionListModel() {
        distributionListService.unarchive(distributionListModel);
    }

    @Override
    public void createAndSendTextMessage(@NonNull DistributionListMessageModel messageModel) {
        unarchiveDistributionListModel();
        bumpLastUpdate();
    }

    @Override
    public void createAndSendLocationMessage(
        final @NonNull DistributionListMessageModel messageModel
    ) {
        unarchiveDistributionListModel();
        bumpLastUpdate();
    }

    @Override
    public boolean createAndSendFileMessage(
        @Nullable byte[] thumbnailBlobId,
        @Nullable byte[] fileBlobId,
        @Nullable SymmetricEncryptionResult encryptionResult,
        @NonNull DistributionListMessageModel messageModel,
        @Nullable Collection<String> recipientIdentities
    ) {
        for (ContactMessageReceiver receiver : affectedMessageReceivers) {
            if (receiver instanceof DistributionListContactMessageReceiver) {
                ((DistributionListContactMessageReceiver) receiver).setFileMessageParameters(
                    thumbnailBlobId, fileBlobId, encryptionResult
                );
            }
        }
        unarchiveDistributionListModel();

        // Note that lastUpdate must not be bumped, as it is bumped by message service when the
        // file message is created

        // F1Whisper (eighth fork review, H8-01): nothing to refuse. This handoff hands the blob parameters to the
        // per-contact receivers, each of which persists and refuses on its own row; the distribution-list row itself is
        // neither enriched here nor scheduled as a task.
        return true;
    }

    @Override
    public void createAndSendPollSetupMessage(
        @NonNull PollData pollData,
        @NonNull PollModel pollModel,
        @NonNull DistributionListMessageModel abstractMessageModel,
        @Nullable Collection<String> recipientIdentities,
        @NonNull TriggerSource triggerSource
    ) {
        // Not supported in distribution lists
    }

    @Override
    public void createAndSendPollVoteMessage(
        PollVote[] votes,
        PollModel pollModel,
        @NonNull TriggerSource triggerSource
    ) {
        // Not supported in distribution lists
    }

    @Override
    @NonNull
    public List<DistributionListMessageModel> loadMessages(MessageService.MessageFilter filter) {
        return this.databaseService.getDistributionListMessageModelFactory().find(
            this.distributionListModel.getId(),
            filter
        );
    }

    @Override
    public long getMessagesCount() {
        return this.databaseService.getDistributionListMessageModelFactory().countMessages(
            this.distributionListModel.getId());
    }

    @Override
    public long getUnreadMessagesCount() {
        return 0;
    }

    @NonNull
    @Override
    public List<DistributionListMessageModel> getUnreadMessages() {
        return Collections.emptyList();
    }

    @Override
    public boolean isEqual(MessageReceiver o) {
        return o instanceof DistributionListMessageReceiver && ((DistributionListMessageReceiver) o).getDistributionList().getId() == this.getDistributionList().getId();
    }

    @Override
    public String getDisplayName(@NonNull ContactNameFormat contactNameFormat) {
        return NameUtil.getDistributionListDisplayName(
            this.getDistributionList(),
            this.distributionListService,
            contactNameFormat
        );
    }

    @Override
    public String getShortName(@NonNull ContactNameFormat contactNameFormat) {
        return getDisplayName(contactNameFormat);
    }

    @Override
    public Bitmap getNotificationAvatar() {
        return distributionListService.getAvatar(distributionListModel.getId(), false);
    }

    @Override
    public Bitmap getHighResAvatar() {
        return distributionListService.getAvatar(distributionListModel.getId(), true);
    }

    @Override
    public Bitmap getAvatar() {
        return distributionListService.getAvatar(distributionListModel.getId(), true, true);
    }

    @Override
    public boolean isMessageBelongsToMe(AbstractMessageModel message) {
        return
            message instanceof DistributionListMessageModel
                && ((DistributionListMessageModel) message).getDistributionListId() == this.getDistributionList().getId();
    }

    @Override
    public boolean shouldSendMediaData() {
        return true;
    }

    @Override
    public boolean offerRetry() {
        return false;
    }

    /**
     * F1Whisper (fifth fork review, F5-02): a distribution-list row is a local record of a fan-out, not a message with a
     * recipient of its own. No task reports a terminal state for it, so the pipeline's own completion is the boundary -
     * which is what it already was.
     */
    @Override
    public boolean hasPendingRemoteCompletion() {
        return false;
    }

    @NonNull
    @Override
    public SendingPermissionValidationResult validateSendingPermission() {
        return this.distributionListModel != null
            ? SendingPermissionValidationResult.Valid.INSTANCE
            : new SendingPermissionValidationResult.Denied();
    }

    @Override
    @MessageReceiverType
    public int getType() {
        return Type_DISTRIBUTION_LIST;
    }

    @Override
    public String[] getIdentities() {
        return this.distributionListService.getDistributionListIdentities(this.distributionListModel);
    }

    @Override
    public void bumpLastUpdate() {
        if (distributionListModel != null) {
            distributionListService.bumpLastUpdate(distributionListModel);
        }
    }

    @Override
    @EmojiReactionsSupport
    public int getEmojiReactionSupport() {
        return Reactions_NONE;
    }

    @Nullable
    @Override
    public NotificationTriggerPolicyOverride getNotificationTriggerPolicyOverrideOrNull() {
        return null;
    }

    @NonNull
    @Override
    public ConversationId getConversationId() {
        return new DistributionListConversationId(distributionListModel.getId());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DistributionListMessageReceiver)) return false;
        DistributionListMessageReceiver that = (DistributionListMessageReceiver) o;
        return Objects.equals(affectedMessageReceivers, that.affectedMessageReceivers) && Objects.equals(distributionListModel, that.distributionListModel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(affectedMessageReceivers, distributionListModel);
    }
}
