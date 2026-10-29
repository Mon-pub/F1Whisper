package ch.threema.app.utils;

import android.content.Context;

import org.koin.java.KoinJavaComponent;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ch.threema.app.conversation.MessageViewElementFactory;
import ch.threema.app.messagereceiver.GroupMessageReceiver;
import ch.threema.app.messagereceiver.MessageReceiver;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

import ch.threema.app.ui.models.MessageViewElement;
import ch.threema.data.datatypes.ContactNameFormat;
import ch.threema.data.models.GroupModel;
import ch.threema.domain.protocol.csp.ProtocolDefines;
import ch.threema.domain.protocol.csp.messages.DeleteMessage;
import ch.threema.domain.protocol.csp.messages.file.FileData;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.DistributionListMessageModel;
import ch.threema.storage.models.group.GroupMessageModel;
import ch.threema.storage.models.MessageModel;
import ch.threema.storage.models.MessageState;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.data.MessageContentsType;
import kotlin.Lazy;

public class MessageUtil {
    private static final Logger logger = getThreemaLogger("MessageUtil");

    private static final Lazy<MessageViewElementFactory> messageViewElementFactoryLazy = KoinJavaComponent.inject(MessageViewElementFactory.class);

    @NonNull
    public static MessageViewElementFactory getMessageViewElementFactory() {
        return messageViewElementFactoryLazy.getValue();
    }

    @NonNull
    public static String getDisplayDate(
        @NonNull Context context,
        @Nullable Instant postedAt,
        boolean isOutbox,
        @Nullable Instant modifiedAt,
        boolean full
    ) {
        if (postedAt == null && modifiedAt == null) {
            return "";
        }
        final @Nullable Instant date = getDisplayDate(postedAt, isOutbox, modifiedAt);
        if (date != null) {
            return LocaleUtil.formatTimeStampString(context, date, full);
        } else {
            return "";
        }
    }

    @Nullable
    public static Instant getDisplayDate(
        @Nullable Instant postedAt,
        boolean isOutbox,
        @Nullable Instant modifiedAt
    ) {
        if (isOutbox && modifiedAt != null) {
            return modifiedAt;
        } else {
            return postedAt;
        }
    }

    @Nullable
    public static Instant getDisplayInstant(
        @Nullable Instant postedAt,
        boolean isOutbox,
        @Nullable Instant modifiedAt
    ) {
        return getDisplayDate(postedAt, isOutbox, modifiedAt);
    }

    public static boolean hasDataFile(AbstractMessageModel messageModel) {
        return messageModel != null && messageModel.getType() == MessageType.FILE;
    }

    /**
     * Checks whether the message holds a file which should be rendered as a file attachment, i.e., not as media
     */
    public static boolean hasFileWithDefaultRendering(@NonNull AbstractMessageModel message) {
        return message.getType() == MessageType.FILE && message.getFileData().getRenderingType() == FileData.RENDERING_DEFAULT;
    }

    /**
     * This method indicates whether the message is a type that can have a thumbnail.
     * Note that it's still possible that a message does not (yet) have a thumbnail stored,
     * even though this method returns true.
     */
    public static boolean canHaveThumbnailFile(AbstractMessageModel messageModel) {
        return messageModel != null && messageModel.getType() == MessageType.FILE;
    }

    public static boolean canSendDeliveryReceipt(AbstractMessageModel message, int receiptType) {
        if (ConfigUtils.isGroupAckEnabled() && (receiptType == ProtocolDefines.DELIVERYRECEIPT_MSGUSERACK || receiptType == ProtocolDefines.DELIVERYRECEIPT_MSGUSERDEC)) {
            return (message instanceof MessageModel || message instanceof GroupMessageModel)
                && !message.isOutbox()
                && !message.isRead()
                && !message.isStatusMessage()
                && message.getType() != MessageType.VOIP_STATUS
                && !((message.getMessageFlags() & ProtocolDefines.MESSAGE_FLAG_NO_DELIVERY_RECEIPTS) == ProtocolDefines.MESSAGE_FLAG_NO_DELIVERY_RECEIPTS);
        } else {
            // F1Whisper: allow group messages to send read/received receipts (gated, like 1:1, by the
            // "send read receipts" preference at the call site)
            return (message instanceof MessageModel || message instanceof GroupMessageModel)
                && !message.isOutbox()
                && !message.isRead()
                && !message.isStatusMessage()
                && message.getType() != MessageType.VOIP_STATUS
                && !((message.getMessageFlags() & ProtocolDefines.MESSAGE_FLAG_NO_DELIVERY_RECEIPTS) == ProtocolDefines.MESSAGE_FLAG_NO_DELIVERY_RECEIPTS);
        }
    }

    /**
     * @return true if the message model can mark as read
     *
     * <p>F1Whisper (device report 2026-08-06, U-01): a message deleted for everyone cannot. The write that records a
     * read refuses a deleted row structurally, so asking for it produced three failed attempts and a warning on every
     * chat open, and the answer never changed. The refusal belongs here, where the callers ask before they act: no
     * attempt, no retry, and no read receipt for a message whose sender withdrew it - the sender deleted it, so a
     * "read" notice tells them only that the chat was open.</p>
     */
    public static boolean canMarkAsRead(AbstractMessageModel message) {
        return message != null
            && !message.isOutbox()
            && !message.isRead()
            && message.getDeletedAt() == null;
    }

    /**
     * @return true if the message model can mark as consumed
     */
    public static boolean canMarkAsConsumed(@Nullable AbstractMessageModel message) {
        return
            (message instanceof MessageModel || message instanceof GroupMessageModel)
                && !message.isStatusMessage()
                && !message.isOutbox()
                && message.getState() != MessageState.CONSUMED
                && (message.getMessageContentsType() == MessageContentsType.VOICE_MESSAGE ||
                message.getMessageContentsType() == MessageContentsType.AUDIO)
                && (message.getState() == null || canChangeToState(message.getState(), MessageState.CONSUMED, message instanceof GroupMessageModel));
    }

    /**
     * @return true, if the user-acknowledge flag can be set
     */
    public static boolean canSendUserAcknowledge(AbstractMessageModel messageModel) {
        if (ConfigUtils.isGroupAckEnabled()) {
            return
                messageModel != null
                    && (!messageModel.isOutbox() || messageModel instanceof GroupMessageModel)
                    && messageModel.getState() != MessageState.USERACK
                    && messageModel.getType() != MessageType.VOIP_STATUS
                    && messageModel.getType() != MessageType.GROUP_CALL_STATUS
                    && !messageModel.isStatusMessage()
                    && !(messageModel instanceof DistributionListMessageModel)
                    && !messageModel.isDeleted();
        } else {
            return
                messageModel != null
                    && !messageModel.isOutbox()
                    && messageModel.getState() != MessageState.USERACK
                    && messageModel.getType() != MessageType.VOIP_STATUS
                    && messageModel.getType() != MessageType.GROUP_CALL_STATUS
                    && !messageModel.isStatusMessage()
                    && !(messageModel instanceof DistributionListMessageModel)
                    && !(messageModel instanceof GroupMessageModel)
                    && !messageModel.isDeleted();
        }
    }

    /**
     * @return true, if the user-decline flag can be set
     */
    public static boolean canSendUserDecline(AbstractMessageModel messageModel) {
        if (ConfigUtils.isGroupAckEnabled()) {
            return
                messageModel != null
                    && (!messageModel.isOutbox() || messageModel instanceof GroupMessageModel)
                    && messageModel.getState() != MessageState.USERDEC
                    && messageModel.getType() != MessageType.VOIP_STATUS
                    && messageModel.getType() != MessageType.GROUP_CALL_STATUS
                    && !messageModel.isStatusMessage()
                    && !(messageModel instanceof DistributionListMessageModel)
                    && !messageModel.isDeleted();
        } else {
            return
                messageModel != null
                    && !messageModel.isOutbox()
                    && messageModel.getState() != MessageState.USERDEC
                    && messageModel.getType() != MessageType.VOIP_STATUS
                    && messageModel.getType() != MessageType.GROUP_CALL_STATUS
                    && !messageModel.isStatusMessage()
                    && !(messageModel instanceof DistributionListMessageModel)
                    && !(messageModel instanceof GroupMessageModel)
                    && !messageModel.isDeleted();
        }
    }

    public static boolean canSendImageReply(@Nullable AbstractMessageModel messageModel) {
        if (messageModel == null ||
            messageModel.getMessageContentsType() != MessageContentsType.IMAGE ||
            messageModel.isDeleted()) {
            return false;
        }
        try {
            return messageModel.getFileData().isDownloaded();
        } catch (ClassCastException exception) {
            // No file data
            logger.warn("No file data available");
            return false;
        }
    }

    /**
     * @return true if the user-acknowledge flag visible
     */
    public static boolean showStatusIcon(AbstractMessageModel messageModel) {
        boolean showState = false;
        if (messageModel != null) {
            if (messageModel.getType() == MessageType.VOIP_STATUS) {
                return false;
            }

            MessageState messageState = messageModel.getState();

            //group message/distribution list message icons only on pending or failing states
            if (messageModel instanceof GroupMessageModel) {
                if (messageState != null) {
                    if (messageModel.isOutbox()) {
                        showState = messageState == MessageState.SENDFAILED
                            || messageState == MessageState.FS_KEY_MISMATCH
                            || messageState == MessageState.SENDING
                            || (messageState == MessageState.PENDING && messageModel.getType() != MessageType.POLL);
                    } else {
                        showState = messageModel.getState() == MessageState.CONSUMED;
                    }
                }
            } else if (messageModel instanceof MessageModel) {
                if (!messageModel.isOutbox()) {
                    // inbox show icon only on acknowledged/declined or consumed
                    showState = messageState != null
                        && messageModel.getState() == MessageState.CONSUMED;
                } else {
                    // on outgoing message
                    if (ContactUtil.isGatewayContact(messageModel.getIdentity())) {
                        showState = messageState == MessageState.SENDFAILED
                            || messageState == MessageState.FS_KEY_MISMATCH
                            || messageState == MessageState.PENDING
                            || messageState == MessageState.SENDING;
                    } else {
                        showState = true;
                    }
                }
            }
        }
        return showState;
    }

    public static boolean isUnread(@Nullable AbstractMessageModel messageModel) {
        return messageModel != null
            && !messageModel.isStatusMessage()
            && !messageModel.isOutbox()
            && !messageModel.isRead();
    }

    /**
     * Returns all affected receivers of a distribution list (including itself)
     *
     * @return ArrayList of all MessageReceivers
     */
    public static ArrayList<MessageReceiver> getAllReceivers(final MessageReceiver messageReceiver) {
        ArrayList<MessageReceiver> allReceivers = new ArrayList<>();
        allReceivers.add(messageReceiver);

        List<MessageReceiver> affectedReceivers = messageReceiver.getAffectedMessageReceivers();
        if (affectedReceivers != null && !affectedReceivers.isEmpty()) {
            allReceivers.addAll(
                affectedReceivers.stream()
                    .filter(receiver -> receiver != null && !receiver.isEqual(messageReceiver))
                    .collect(Collectors.toList())
            );
        }
        return allReceivers;
    }

    /**
     * Expand list of MessageReceivers to contain distribution list receivers as single recipients
     *
     * @param allReceivers - list of MessageReceivers including distribution lists
     * @return - expanded list of receivers with duplicates removed
     */
    public static MessageReceiver[] addDistributionListReceivers(MessageReceiver[] allReceivers) {
        // Use LinkedHashSet in order to preserve insertion order.
        // If the order is not preserved sending of files to distribution lists is likely to fail
        Set<MessageReceiver> resolvedReceivers = new LinkedHashSet<>();
        for (MessageReceiver receiver : allReceivers) {
            if (receiver.getType() == MessageReceiver.Type_DISTRIBUTION_LIST) {
                resolvedReceivers.addAll(MessageUtil.getAllReceivers(receiver));
            } else {
                resolvedReceivers.add(receiver);
            }
        }
        return resolvedReceivers.toArray(new MessageReceiver[0]);
    }

    /**
     * Check if a MessageState change from fromState to toState is allowed
     *
     * @param fromState      State from which a state change is requested
     * @param toState        State to which a state change is requested
     * @param isGroupMessage true, if it's a group message
     * @return true if a state change is allowed, false otherwise
     */
    public static boolean canChangeToState(@Nullable MessageState fromState, @Nullable MessageState toState, boolean isGroupMessage) {
        if (fromState == null || toState == null) {
            //invalid data
            return false;
        }

        if (fromState == toState) {
            return false;
        }

        switch (toState) {
            case DELIVERED:
                return fromState == MessageState.SENDING
                    || fromState == MessageState.SENDFAILED
                    || fromState == MessageState.FS_KEY_MISMATCH
                    || fromState == MessageState.PENDING
                    || fromState == MessageState.SENT;
            case READ:
                return fromState == MessageState.SENDING
                    || fromState == MessageState.SENDFAILED
                    || fromState == MessageState.FS_KEY_MISMATCH
                    || fromState == MessageState.PENDING
                    || fromState == MessageState.SENT
                    || fromState == MessageState.DELIVERED;
            case SENDFAILED:
                return fromState == MessageState.SENDING
                    || fromState == MessageState.PENDING
                    || fromState == MessageState.TRANSCODING
                    || fromState == MessageState.UPLOADING;
            case FS_KEY_MISMATCH:
                return fromState == MessageState.SENDING
                    || fromState == MessageState.PENDING
                    || fromState == MessageState.TRANSCODING
                    || fromState == MessageState.SENT;
            case SENT:
                return fromState == MessageState.SENDING
                    || fromState == MessageState.SENDFAILED
                    || fromState == MessageState.FS_KEY_MISMATCH
                    || fromState == MessageState.PENDING
                    || fromState == MessageState.TRANSCODING
                    || fromState == MessageState.UPLOADING;
            case USERACK:
                return true;
            case USERDEC:
                return true;
            case CONSUMED:
                return fromState != MessageState.USERACK
                    && fromState != MessageState.USERDEC;
            case PENDING:
                return fromState == MessageState.SENDFAILED
                    || (fromState == MessageState.FS_KEY_MISMATCH && !isGroupMessage);
            case SENDING:
                return fromState == MessageState.SENDFAILED
                    || (fromState == MessageState.FS_KEY_MISMATCH && !isGroupMessage)
                    || fromState == MessageState.PENDING
                    || fromState == MessageState.TRANSCODING
                    || fromState == MessageState.UPLOADING;
            case UPLOADING:
                return fromState == MessageState.SENDFAILED
                    || fromState == MessageState.FS_KEY_MISMATCH
                    || fromState == MessageState.PENDING
                    || fromState == MessageState.TRANSCODING;
            default:
                logger.debug("message state {} not handled", toState);
                return false;
        }
    }

    public static String getCaptionText(AbstractMessageModel messageModel) {
        if (messageModel != null && messageModel.getType() == MessageType.FILE) {
            return messageModel.getFileData().getCaption();
        }
        return null;
    }

    /**
     * Check if the provided MessageState is a user reaction.
     *
     * @param state the message state
     * @return true if it is a user reaction, false otherwise
     */
    public static boolean isReaction(MessageState state) {
        return state == MessageState.USERACK || state == MessageState.USERDEC;
    }

    @NonNull
    public static MessageViewElement getViewElement(
        @Nullable AbstractMessageModel messageModel,
        @NonNull final ContactNameFormat contactNameFormat
    ) {
        if (messageModel == null || messageModel.getType() == null) {
            return new MessageViewElement();
        }
        var messageViewElement = getMessageViewElementFactory().getViewElement(
            messageModel.getType(),
            messageModel.getBody(),
            messageModel.getCaption(),
            messageModel.isOutbox(),
            contactNameFormat
        );
        if (messageViewElement == null) {
            return new MessageViewElement();
        }
        return messageViewElement;
    }

    /**
     * Check whether a file message is being sent. This includes the states PENDING, UPLOADING, and
     * SENDING. This method only returns true for outgoing messages. Note that for file messages
     * that are in state TRANSCODING this method returns false.
     */
    public static boolean isFileMessageBeingSent(@NonNull AbstractMessageModel model) {
        MessageState messageState = model.getState();
        return model.isOutbox() && (
            messageState == MessageState.PENDING ||
                messageState == MessageState.UPLOADING ||
                messageState == MessageState.SENDING
        );
    }

    /**
     * Check whether the given message type allows remote deletion of messages. Note that only the
     * message type is considered. To check whether the user should be able to delete a message for
     * everyone, {@link #canDeleteRemotely(AbstractMessageModel, MessageReceiver)} should be used.
     */
    public static boolean doesMessageTypeAllowRemoteDeletion(@Nullable MessageType messageType) {
        if (messageType == null) {
            return false;
        }

        switch (messageType) {
            case TEXT:
            case LOCATION:
            case FILE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Check whether the user should be able to delete the given message remotely at this point in time.
     */
    // TODO(ANDR-4222): Refactor this method
    public static boolean canDeleteRemotely(
        @NonNull AbstractMessageModel message,
        @NonNull MessageReceiver receiver
    ) {
        return doesMessageTypeAllowRemoteDeletion(message.getType())
            && !message.isStatusMessage()
            && message.isOutbox()
            && isStillInValidTimeFrameToDeleteRemotely(message, receiver)
            && (message instanceof MessageModel || message instanceof GroupMessageModel)
            && (message.getPostedAt() != null && message.getState() != MessageState.SENDFAILED)
            && !message.isDeleted();
    }

    /**
     * @return true if the message is not older then a defined age. Messages in notes groups can be deleted indefinitely.
     */
    private static boolean isStillInValidTimeFrameToDeleteRemotely(
        @NonNull AbstractMessageModel message,
        @NonNull MessageReceiver receiver
    ) {
        final @Nullable GroupModel groupModel;
        if (receiver instanceof GroupMessageReceiver) {
            groupModel = ((GroupMessageReceiver) receiver).getGroupModel();
        } else {
            groupModel = null;
        }
        final boolean isNotesGroup = groupModel != null && Boolean.TRUE.equals(groupModel.isNotesGroup());
        if (isNotesGroup) {
            return true;
        }
        final @Nullable Instant createdAt = message.getCreatedAt();
        if (createdAt == null) {
            return false;
        }
        final long deltaTime = Instant.now().toEpochMilli() - createdAt.toEpochMilli();
        return deltaTime <= DeleteMessage.DELETE_MESSAGES_MAX_AGE;
    }

    @Nullable
    public static MessageState receiptTypeToMessageState(int receiptType) {
        switch (receiptType) {
            case ProtocolDefines.DELIVERYRECEIPT_MSGRECEIVED:
                return MessageState.DELIVERED;
            case ProtocolDefines.DELIVERYRECEIPT_MSGREAD:
                return MessageState.READ;
            case ProtocolDefines.DELIVERYRECEIPT_MSGUSERACK:
                return MessageState.USERACK;
            case ProtocolDefines.DELIVERYRECEIPT_MSGUSERDEC:
                return MessageState.USERDEC;
            default:
                return null;
        }
    }

    /**
     * Check whether the user should be able to star the given message.
     */
    public static boolean canStarMessage(AbstractMessageModel message) {
        return (message instanceof MessageModel || message instanceof GroupMessageModel)
            && message.getType() != null
            && (message.getType().equals(MessageType.TEXT) ||
            message.getType().equals(MessageType.FILE) ||
            message.getType().equals(MessageType.LOCATION) ||
            message.getType().equals(MessageType.POLL));
    }

    /**
     * Check whether the user should be able to react to the given message with emojis
     */
    public static boolean canEmojiReact(@Nullable AbstractMessageModel messageModel) {
        if (messageModel == null) {
            return false;
        }

        if (messageModel.isDeleted()) {
            return false;
        }

        if (messageModel.isStatusMessage()) {
            return false;
        }

        return (messageModel instanceof MessageModel || messageModel instanceof GroupMessageModel)
            && messageModel.getType() != null
            && (messageModel.getType().equals(MessageType.TEXT) ||
            messageModel.getType().equals(MessageType.FILE) ||
            messageModel.getType().equals(MessageType.LOCATION) ||
            messageModel.getType().equals(MessageType.POLL));
    }
}
