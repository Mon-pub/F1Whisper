package ch.threema.app.backuprestore;

import android.content.Context;
import android.text.format.DateUtils;

import org.slf4j.Logger;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.ListIterator;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import ch.threema.app.R;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.services.ContactService;
import ch.threema.app.services.FileService;
import ch.threema.app.services.MessageService;
import ch.threema.app.utils.FileHandlingZipOutputStream;
import ch.threema.app.utils.FileUtil;
import ch.threema.app.utils.GeoLocationUtil;
import ch.threema.app.utils.NameUtil;
import ch.threema.app.utils.ElapsedTimeFormatter;
import ch.threema.app.utils.RestrictedMediaOutput;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import static ch.threema.common.JavaCompat.isNullOrEmpty;
import static ch.threema.common.JavaCompat.stringToInputStream;

import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.ConversationModel;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.data.media.FileDataModel;

public class ExportConversationServiceImpl implements ExportConversationService {
    private static final Logger logger = getThreemaLogger("ExportConversationServiceImpl");

    private final @NonNull Context appContext;
    private final @NonNull FileService fileService;
    private final @NonNull MessageService messageService;
    private final @NonNull ContactService contactService;
    private final @NonNull PreferenceService preferenceService;
    private volatile boolean isCanceled;

    public ExportConversationServiceImpl(
        @NonNull Context appContext,
        @NonNull FileService fileService,
        @NonNull MessageService messageService,
        @NonNull ContactService contactService,
        @NonNull PreferenceService preferenceService
    ) {
        this.appContext = appContext;
        this.fileService = fileService;
        this.messageService = messageService;
        this.contactService = contactService;
        this.preferenceService = preferenceService;
    }

    @WorkerThread
    private boolean buildThread(
        @NonNull ConversationModel conversationModel,
        @NonNull FileHandlingZipOutputStream zipOutputStream,
        @NonNull StringBuilder messageBody,
        boolean includeMedia
    ) {
        @NonNull AbstractMessageModel messageModel;

        isCanceled = false;

        final @NonNull List<AbstractMessageModel> messages = messageService.getMessagesForReceiver(conversationModel.messageReceiver);
        final @NonNull ListIterator<AbstractMessageModel> listIterator = messages.listIterator(messages.size());
        while (listIterator.hasPrevious()) {
            messageModel = listIterator.previous();

            if (isCanceled) {
                break;
            }

            if (messageModel.isStatusMessage()) {
                continue;
            }

            if (messageModel.getType() == MessageType.GROUP_CALL_STATUS || messageModel.getType() == MessageType.FORWARD_SECURITY_STATUS) {
                continue;
            }

            String filename = "";
            String messageLine = "";

            if (!conversationModel.isGroupConversation()) {
                messageLine = messageModel.isOutbox()
                    ? appContext.getString(R.string.me_myself_and_i)
                    : NameUtil.getContactDisplayNameOrNickname(
                        contactService.getByIdentity(messageModel.getIdentity()),
                        true,
                        preferenceService.getContactNameFormat()
                    );
                messageLine += ": ";
            }

            messageLine += messageService.getMessageString(messageModel, 0).getRawMessage();

            // add media file to zip
            try {
                boolean saveMedia = false;
                String extension = "";

                switch (messageModel.getType()) {
                    case FILE:
                        FileDataModel fileDataModel = messageModel.getFileData();
                        saveMedia = fileDataModel.isDownloaded();
                        filename = isNullOrEmpty(fileDataModel.getFileName()) ?
                            FileUtil.getDefaultFilename(fileDataModel.getMimeType()) :
                            (messageModel.getApiMessageId() != null ? messageModel.getApiMessageId() : messageModel.getId()) +
                                "-" + fileDataModel.getFileName();
                        extension = "";
                        break;
                    case LOCATION:
                        messageLine += " <" + GeoLocationUtil.getLocationUri(messageModel) + ">";
                        break;
                    case VOIP_STATUS:
                        if (messageModel.getVoipStatusData() != null && messageModel.getVoipStatusData().getDurationInSeconds() != null) {
                            messageLine += " <" + ElapsedTimeFormatter.secondsToString(messageModel.getVoipStatusData().getDurationInSeconds()) + ">";
                        }
                        break;
                    default:
                }

                // F1Whisper (fourth fork review, F4-09): a chat export decrypts every downloaded attachment into a zip
                // the user keeps. That is a permanent clear copy, so an incoming listen-once voice message is written
                // out as its message line only, with no attachment.
                if (saveMedia && RestrictedMediaOutput.isRestricted(messageModel)) {
                    logger.info("Excluding restricted media {} from the chat export", messageModel.getUid());
                    saveMedia = false;
                    filename = null;
                }

                if (saveMedia) {
                    if (isNullOrEmpty(filename)) {
                        filename = messageModel.getUid() + extension;
                    }

                    var messageUid = messageModel.getUid();
                    if (includeMedia && messageUid != null) {
                        try (InputStream is = fileService.decryptMessageFileToStream(messageUid)) {
                            if (is != null) {
                                zipOutputStream.addFileFromInputStream(is, filename, false);
                            } else {
                                // if media is missing, try thumbnail
                                try (InputStream thumbnailInputStream = fileService.decryptedMessageThumbnailToStream(messageUid)) {
                                    if (thumbnailInputStream != null) {
                                        zipOutputStream.addFileFromInputStream(thumbnailInputStream, filename, false);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // do not abort, it's only a media :-)
                logger.error("Exception", e);
            }

            if (!isNullOrEmpty(filename)) {
                messageLine += " <" + filename + ">";
            }

            final @NonNull String messageDate = DateUtils.formatDateTime(
                appContext,
                messageModel.getPostedAt().toEpochMilli(),
                DateUtils.FORMAT_ABBREV_ALL |
                    DateUtils.FORMAT_SHOW_YEAR |
                    DateUtils.FORMAT_SHOW_DATE |
                    DateUtils.FORMAT_NUMERIC_DATE |
                    DateUtils.FORMAT_SHOW_TIME
            );
            if (!isNullOrEmpty(messageLine)) {
                messageBody.append("[");
                messageBody.append(messageDate);
                messageBody.append("] ");
                messageBody.append(messageLine);
                messageBody.append("\n");
            }
        }
        return !isCanceled;
    }

    @NonNull
    @Override
    @WorkerThread
    public final ExportResult exportToZip(
        final @NonNull ConversationModel conversationModel,
        final @NonNull File outputFile,
        final @NonNull String password,
        boolean includeMedia
    ) {
        final @NonNull StringBuilder messageBody = new StringBuilder();
        try (final @NonNull FileHandlingZipOutputStream zipOutputStream = FileHandlingZipOutputStream.initializeZipOutputStream(outputFile, password)) {
            final boolean threadWasBuiltCompletely = buildThread(conversationModel, zipOutputStream, messageBody, includeMedia);
            if (threadWasBuiltCompletely) {
                zipOutputStream.addFileFromInputStream(stringToInputStream(messageBody.toString()), "messages.txt", true);
                return ExportResult.SUCCESS;
            } else {
                return ExportResult.CANCELLED;
            }
        } catch (Exception e) {
            logger.error("Exception", e);
            return ExportResult.FAILURE;
        } finally {
            if (isCanceled) {
                FileUtil.deleteFileOrWarn(outputFile, "output file", logger);
            }
        }
    }

    @Override
    @AnyThread
    public void cancel() {
        isCanceled = true;
    }
}
