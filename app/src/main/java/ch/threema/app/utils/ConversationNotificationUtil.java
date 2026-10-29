package ch.threema.app.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import org.koin.java.KoinJavaComponent;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.HashMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;
import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.services.ContactService;
import ch.threema.app.services.ConversationCategoryService;
import ch.threema.app.services.FileService;
import ch.threema.app.services.GroupService;
import ch.threema.app.services.MessageService;
import ch.threema.app.services.notification.ConversationNotification;
import ch.threema.app.services.notification.ConversationNotificationGroup;
import ch.threema.app.services.DisappearingMessageService;
import ch.threema.app.services.notification.NotificationService;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

import ch.threema.data.datatypes.ContactConversationId;
import ch.threema.data.datatypes.ContactNameFormat;
import ch.threema.data.datatypes.GroupConversationId;
import ch.threema.domain.protocol.csp.messages.file.FileData;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.group.GroupMessageModel;
import ch.threema.storage.models.group.GroupModelOld;
import ch.threema.storage.models.MessageModel;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.data.MessageContentsType;

// TODO(ANDR-4691): This class needs refactoring
public class ConversationNotificationUtil {
    private static final Logger logger = getThreemaLogger("ConversationNotificationUtil");

    protected static final HashMap<String, ConversationNotificationGroup> notificationGroupHashMap = new HashMap<>();
    private static final int MAX_NOTIFICATION_THUMBNAIL_SIZE_BYTES = 1024 * 1024;

    @Nullable
    public static ConversationNotification convert(
        @NonNull Context context,
        @NonNull AbstractMessageModel messageModel,
        @NonNull ContactService contactService,
        @NonNull GroupService groupService,
        @NonNull ConversationCategoryService conversationCategoryService,
        @NonNull ContactNameFormat contactNameFormat
    ) {
        // F1Whisper: suppress the notification if the message has already expired.
        //
        // F1Whisper (tenth fork review, F10-06): the thread contract, stated because this is called from notification
        // construction and used to be a route into synchronous database and filesystem work. enforceIfExpired now
        // answers from the model in memory and schedules the removal on a worker, so this call performs no I/O on
        // whatever thread builds the notification. It returns whether the message must be treated as gone, which is
        // the only thing this caller needs.
        if (DisappearingMessageService.enforceIfExpired(messageModel)) {
            logger.debug("Suppressing notification for expired disappearing message uid={}", messageModel.getUid());
            return null;
        }

        ConversationNotification conversationNotification = null;
        if (messageModel instanceof MessageModel) {
            conversationNotification = create(context, (MessageModel) messageModel, contactService, conversationCategoryService, contactNameFormat);
        } else if (messageModel instanceof GroupMessageModel) {
            conversationNotification = create(context, (GroupMessageModel) messageModel, groupService, conversationCategoryService, contactNameFormat);
        }

        return conversationNotification;
    }

    private static MessageService.MessageString getMessage(AbstractMessageModel messageModel) {
        try {
            return ServiceManager.require().getMessageService()
                .getMessageString(messageModel, -1, false);
        } catch (Exception e) {
            logger.error("Exception", e);
            return new MessageService.MessageString(null);
        }
    }

    private static @Nullable Person getSenderPerson(@NonNull AbstractMessageModel messageModel) {
        final PreferenceService preferenceService = ServiceManager.get().getPreferenceService();
        final ContactService contactService = ServiceManager.get().getContactService();
        final ContactModel contactModel = contactService.getByIdentity(messageModel.getIdentity());
        return getPerson(
            contactService,
            contactModel,
            NameUtil.getShortName(
                ThreemaApplication.getAppContext(),
                messageModel,
                contactService,
                preferenceService.getContactNameFormat()
            )
        );
    }

    public static @Nullable Person getPerson(@Nullable ContactService contactService, ContactModel contactModel, String name) {
        if (contactService == null) {
            return null;
        }
        final @Nullable String identity = contactModel != null
            ? contactModel.getIdentity()
            : null;
        Person.Builder builder = new Person.Builder()
            .setKey(
                identity != null
                    ? new ContactConversationId(identity).getObfuscated().value
                    : ""
            )
            .setName(name);
        Bitmap avatar = contactService.getAvatar(identity, false);
        if (avatar != null) {
            IconCompat iconCompat = IconCompat.createWithBitmap(avatar);
            builder.setIcon(iconCompat);
        }
        if (contactModel != null && contactModel.isLinkedToAndroidContact()) {
            builder.setUri(contactService.getAndroidContactLookupUriString(contactModel));
        }
        return builder.build();
    }

    private static MessageType getMessageType(@NonNull AbstractMessageModel messageModel) {
        return messageModel.getType();
    }

    @Nullable
    private static Instant getCreatedAt(AbstractMessageModel messageModel) {
        return messageModel.getCreatedAt();
    }

    @Nullable
    private static ConversationNotification create(
        @NonNull final Context context,
        @NonNull final MessageModel messageModel,
        @NonNull final ContactService contactService,
        @NonNull final ConversationCategoryService conversationCategoryService,
        @NonNull final ContactNameFormat contactNameFormat
    ) {
        final @Nullable ContactModel contactModel = contactService.getByIdentity(messageModel.getIdentity());
        if (contactModel == null) {
            logger.warn("Contact for notification not found");
            return null;
        }
        final String groupUid = "i" + messageModel.getIdentity();
        synchronized (notificationGroupHashMap) {
            ConversationNotificationGroup group = notificationGroupHashMap.get(groupUid);
            final ContactConversationId contactConversationId = new ContactConversationId(contactModel.getIdentity());
            final boolean isPrivateConversation = conversationCategoryService.isMarkedAsPrivate(contactConversationId);
            String longName, shortName;
            if (isPrivateConversation) {
                longName = shortName = context.getString(R.string.private_chat_subject);
            } else {
                longName = NameUtil.getContactDisplayNameOrNickname(contactModel, true, contactNameFormat);
                shortName = NameUtil.getShortName(contactModel, contactNameFormat);
            }

            if (group == null) {
                group = new ConversationNotificationGroup(
                    groupUid,
                    longName,
                    shortName,
                    contactService.createReceiver(contactModel),
                    () -> {
                        if (contactModel != null) {
                            String identity = isPrivateConversation
                                ? null
                                : contactModel.getIdentity();
                            return contactService.getAvatar(identity, false);
                        }
                        return null;
                    }
                );
                notificationGroupHashMap.put(groupUid, group);
            } else {
                // contact name may change between notifications - set it again
                group.name = longName;
                group.shortName = shortName;
            }

            var conversationNotification = new ConversationNotification(
                getMessage(messageModel),
                getCreatedAt(messageModel),
                getId(messageModel),
                getUid(messageModel),
                group,
                getFetchThumbnail(messageModel),
                getThumbnailMimeType(messageModel),
                getSenderPerson(messageModel),
                getMessageType(messageModel),
                messageModel.isEdited(),
                messageModel.isDeleted()
            );
            group.conversations.add(conversationNotification);
            return conversationNotification;
        }
    }

    @Nullable
    private static String getThumbnailMimeType(AbstractMessageModel messageModel) {
        if (MessageType.FILE.equals(messageModel.getType()) &&
            (messageModel.getMessageContentsType() == MessageContentsType.IMAGE ||
                messageModel.getMessageContentsType() == MessageContentsType.VIDEO
            )) {
            return MimeUtil.MIME_TYPE_IMAGE_PNG.equals(messageModel.getFileData().getThumbnailMimeType()) ? MimeUtil.MIME_TYPE_IMAGE_PNG : MimeUtil.MIME_TYPE_IMAGE_JPEG;
        }
        return null;
    }

    @Nullable
    private static ConversationNotification create(
        final Context context,
        @NonNull final GroupMessageModel messageModel,
        @NonNull final GroupService groupService,
        @NonNull final ConversationCategoryService conversationCategoryService,
        @NonNull final ContactNameFormat contactNameFormat
    ) {
        final GroupModelOld groupModel = groupService.getById(messageModel.getGroupId());
        if (groupModel == null) {
            logger.warn("Group for notification not found");
            return null;
        }
        final String groupUid = "g" + messageModel.getGroupId();
        synchronized (notificationGroupHashMap) {
            @Nullable ConversationNotificationGroup group = notificationGroupHashMap.get(groupUid);
            final GroupConversationId groupConversationId = new GroupConversationId(groupModel.getId());
            final boolean isPrivateConversation = conversationCategoryService.isMarkedAsPrivate(groupConversationId);
            final String name = isPrivateConversation
                ? context.getString(R.string.private_chat_subject)
                : NameUtil.getGroupDisplayName(
                    groupModel,
                    groupService,
                    contactNameFormat
                );

            if (group == null) {
                group = new ConversationNotificationGroup(
                    groupUid,
                    name,
                    name,
                    groupService.createReceiver(groupModel),
                    () ->
                        groupService.getAvatar(
                            conversationCategoryService.isMarkedAsPrivate(new GroupConversationId(groupModel.getId()))
                                ? null
                                : groupModel,
                            false
                        )
                );
                notificationGroupHashMap.put(groupUid, group);
            } else {
                // group name may change between notifications - set it again
                group.name = name;
                group.shortName = name;
            }

            var conversationNotification = new ConversationNotification(
                getMessage(messageModel),
                getCreatedAt(messageModel),
                getId(messageModel),
                getUid(messageModel),
                group,
                getFetchThumbnail(messageModel),
                getThumbnailMimeType(messageModel),
                getSenderPerson(messageModel),
                getMessageType(messageModel),
                messageModel.isEdited(),
                messageModel.isDeleted()
            );
            group.conversations.add(conversationNotification);
            return conversationNotification;
        }
    }

    public static String getUid(AbstractMessageModel messageModel) {
        return messageModel.getUid();
    }

    public static int getId(AbstractMessageModel messageModel) {
        return messageModel.getId();
    }

    public static NotificationService.FetchCacheUri getFetchThumbnail(final AbstractMessageModel messageModel) {
        if (messageModel.getMessageContentsType() == MessageContentsType.IMAGE ||
            messageModel.getMessageContentsType() == MessageContentsType.VIDEO
        ) {
            if (messageModel.getType() == MessageType.FILE && messageModel.getFileData().getRenderingType() != FileData.RENDERING_MEDIA) {
                return null;
            }

            // F1-PATCH: the notification is an output boundary like any other, and it is the one the
            // recipient does not choose to open. A spoiler's whole point is that the image is not
            // shown until it is asked for, and there is nothing to ask here - so the thumbnail is
            // simply withheld. The text half of this was already obscured in ConversationNotification.
            final boolean isFileMessage = messageModel.getType() == MessageType.FILE;
            if (!OutputRestrictionPolicy.mayReleaseThumbnailToNotification(
                isFileMessage,
                isFileMessage && messageModel.getFileData().isSpoiler(),
                isFileMessage && messageModel.getFileData().isListenOnce()
            )) {
                return null;
            }

            return new NotificationService.FetchCacheUri() {
                @Override
                @Nullable
                @WorkerThread
                public Uri fetch() {
                    final FileService fileService = KoinJavaComponent.get(FileService.class);
                    return fileService.decryptThumbnailToShareableTempFile(messageModel, MAX_NOTIFICATION_THUMBNAIL_SIZE_BYTES);
                }
            };
        }
        return null;
    }
}
