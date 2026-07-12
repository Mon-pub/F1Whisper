package ch.threema.storage.factories;

import static ch.threema.storage.models.data.DisplayTag.DISPLAY_TAG_STARRED;

import android.content.ContentValues;
import android.database.sqlite.SQLiteException;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import net.zetetic.database.sqlcipher.SQLiteStatement;

import org.slf4j.Logger;

import java.util.Iterator;
import java.util.List;

import ch.threema.app.services.MessageService;
import ch.threema.app.utils.TestUtil;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityMode;
import ch.threema.storage.CursorHelper;
import ch.threema.storage.DatabaseProvider;
import ch.threema.storage.DatabaseUtil;
import ch.threema.storage.QueryBuilder;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.MessageState;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.data.DisplayTag;
import ch.threema.storage.models.data.MessageContentsType;
import ch.threema.storage.models.data.media.AudioDataModel;
import ch.threema.storage.models.data.media.FileDataModel;
import ch.threema.storage.models.data.media.VideoDataModel;

abstract class AbstractMessageModelFactory extends ModelFactory {
    private static final Logger logger = getThreemaLogger("AbstractMessageModelFactory");

    AbstractMessageModelFactory(DatabaseProvider databaseProvider, String tableName) {
        super(databaseProvider, tableName);
    }

    void convert(final AbstractMessageModel messageModel, CursorHelper cursorFactory) {
        cursorFactory.current(new CursorHelper.Callback() {
            @Override
            public boolean next(CursorHelper cursorFactory) {
                ForwardSecurityMode forwardSecurityMode = ForwardSecurityMode.NONE;
                Integer fsmValue = cursorFactory.getInt(AbstractMessageModel.COLUMN_FORWARD_SECURITY_MODE);
                if (fsmValue != null) {
                    forwardSecurityMode = ForwardSecurityMode.getByValue(fsmValue);
                }

                messageModel.setId(cursorFactory.getInt(AbstractMessageModel.COLUMN_ID));
                messageModel.setUid(cursorFactory.getString(AbstractMessageModel.COLUMN_UID));
                messageModel.setApiMessageId(cursorFactory.getString(AbstractMessageModel.COLUMN_API_MESSAGE_ID));
                messageModel.setIdentity(cursorFactory.getString(AbstractMessageModel.COLUMN_IDENTITY));
                messageModel.setOutbox(cursorFactory.getBoolean(AbstractMessageModel.COLUMN_OUTBOX));
                messageModel.setCorrelationId(cursorFactory.getString(AbstractMessageModel.COLUMN_CORRELATION_ID));
                messageModel.setBody(cursorFactory.getString(AbstractMessageModel.COLUMN_BODY));
                messageModel.setRead(cursorFactory.getBoolean(AbstractMessageModel.COLUMN_IS_READ));
                messageModel.setSaved(cursorFactory.getBoolean(AbstractMessageModel.COLUMN_IS_SAVED));
                messageModel.setPostedAt(cursorFactory.getDate(AbstractMessageModel.COLUMN_POSTED_AT));
                messageModel.setCreatedAt(cursorFactory.getDate(AbstractMessageModel.COLUMN_CREATED_AT));
                messageModel.setModifiedAt(cursorFactory.getDate(AbstractMessageModel.COLUMN_MODIFIED_AT));
                messageModel.setStatusMessage(cursorFactory.getBoolean(AbstractMessageModel.COLUMN_IS_STATUS_MESSAGE));
                messageModel.setCaption(cursorFactory.getString(AbstractMessageModel.COLUMN_CAPTION));
                messageModel.setQuotedMessageId(cursorFactory.getString(AbstractMessageModel.COLUMN_QUOTED_MESSAGE_API_MESSAGE_ID));
                messageModel.setMessageContentsType(cursorFactory.getInt(AbstractMessageModel.COLUMN_MESSAGE_CONTENTS_TYPE));
                messageModel.setMessageFlags(cursorFactory.getInt(AbstractMessageModel.COLUMN_MESSAGE_FLAGS));
                messageModel.setDeliveredAt(cursorFactory.getDate(AbstractMessageModel.COLUMN_DELIVERED_AT));
                messageModel.setReadAt(cursorFactory.getDate(AbstractMessageModel.COLUMN_READ_AT));
                messageModel.setEditedAt(cursorFactory.getDate(AbstractMessageModel.COLUMN_EDITED_AT));
                messageModel.setDeletedAt(cursorFactory.getDate(AbstractMessageModel.COLUMN_DELETED_AT));
                // F1Whisper disappearing messages: raw epoch-millis timestamps + the frozen per-message timer.
                messageModel.setExpiresAt(cursorFactory.getLong(AbstractMessageModel.COLUMN_EXPIRES_AT));
                messageModel.setExpireStartedAt(cursorFactory.getLong(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT));
                Long disappearingTimerSeconds = cursorFactory.getLong(AbstractMessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS);
                messageModel.setDisappearingTimerSeconds(disappearingTimerSeconds != null ? disappearingTimerSeconds.intValue() : null);
                messageModel.setForwardSecurityMode(forwardSecurityMode);
                messageModel.setDisplayTags(cursorFactory.getInt(AbstractMessageModel.COLUMN_DISPLAY_TAGS));

                String stateString = cursorFactory.getString(AbstractMessageModel.COLUMN_STATE);
                if (!TestUtil.isEmptyOrNull(stateString)) {
                    try {
                        messageModel.setState(MessageState.valueOf(stateString));
                    } catch (IllegalArgumentException e) {
                        logger.error("Invalid message state {} - ignore", stateString, e);
                    }
                }

                int type = cursorFactory.getInt(AbstractMessageModel.COLUMN_TYPE);
                MessageType[] types = MessageType.values();
                if (type >= 0 && type < types.length) {
                    messageModel.setType(types[type]);
                }
                return false;
            }
        });
    }

    ContentValues buildContentValues(AbstractMessageModel messageModel) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(AbstractMessageModel.COLUMN_UID, messageModel.getUid());
        contentValues.put(AbstractMessageModel.COLUMN_API_MESSAGE_ID, messageModel.getApiMessageId());
        contentValues.put(AbstractMessageModel.COLUMN_IDENTITY, messageModel.getIdentity());
        contentValues.put(AbstractMessageModel.COLUMN_OUTBOX, messageModel.isOutbox());
        contentValues.put(AbstractMessageModel.COLUMN_TYPE, messageModel.getType() != null ? messageModel.getType().ordinal() : null);
        contentValues.put(AbstractMessageModel.COLUMN_CORRELATION_ID, messageModel.getCorrelationId());
        contentValues.put(AbstractMessageModel.COLUMN_BODY, messageModel.getBody());
        contentValues.put(AbstractMessageModel.COLUMN_IS_READ, messageModel.isRead());
        contentValues.put(AbstractMessageModel.COLUMN_IS_SAVED, messageModel.isSaved());
        contentValues.put(AbstractMessageModel.COLUMN_STATE, messageModel.getState() != null ? messageModel.getState().toString() : null);
        contentValues.put(AbstractMessageModel.COLUMN_POSTED_AT, DatabaseUtil.getDateTimeContentValue(messageModel.getPostedAt()));
        contentValues.put(AbstractMessageModel.COLUMN_CREATED_AT, DatabaseUtil.getDateTimeContentValue(messageModel.getCreatedAt()));
        // F1Whisper message-ordering fix: immutable per-row sort key. Outgoing rows sort by their
        // local compose time (createdAtUtc) instead of the mutable send-completion time (postedAtUtc
        // is overwritten with sentAt on SEND, which is not monotonic with compose order). Incoming
        // rows keep sender time (preserves the reconnect-backlog fix). The formula is stable for a
        // row's whole life, so recomputing it on every write yields the same value.
        final java.util.Date sortDate = messageModel.isOutbox()
            ? messageModel.getCreatedAt()
            : (messageModel.getPostedAt() != null ? messageModel.getPostedAt() : messageModel.getCreatedAt());
        contentValues.put(AbstractMessageModel.COLUMN_SORT_AT, DatabaseUtil.getDateTimeContentValue(sortDate));
        contentValues.put(AbstractMessageModel.COLUMN_MODIFIED_AT, DatabaseUtil.getDateTimeContentValue(messageModel.getModifiedAt()));
        contentValues.put(AbstractMessageModel.COLUMN_IS_STATUS_MESSAGE, messageModel.isStatusMessage());
        contentValues.put(AbstractMessageModel.COLUMN_CAPTION, messageModel.getCaption());
        contentValues.put(AbstractMessageModel.COLUMN_QUOTED_MESSAGE_API_MESSAGE_ID, messageModel.getQuotedMessageId());
        contentValues.put(AbstractMessageModel.COLUMN_MESSAGE_CONTENTS_TYPE, messageModel.getMessageContentsType());
        contentValues.put(AbstractMessageModel.COLUMN_MESSAGE_FLAGS, messageModel.getMessageFlags());
        contentValues.put(AbstractMessageModel.COLUMN_DELIVERED_AT, DatabaseUtil.getDateTimeContentValue(messageModel.getDeliveredAt()));
        contentValues.put(AbstractMessageModel.COLUMN_READ_AT, DatabaseUtil.getDateTimeContentValue(messageModel.getReadAt()));
        contentValues.put(AbstractMessageModel.COLUMN_EDITED_AT, DatabaseUtil.getDateTimeContentValue(messageModel.getEditedAt()));
        contentValues.put(AbstractMessageModel.COLUMN_DELETED_AT, DatabaseUtil.getDateTimeContentValue(messageModel.getDeletedAt()));
        // F1Whisper disappearing messages: epoch-millis timestamps stored raw + the frozen per-message timer (null = off).
        contentValues.put(AbstractMessageModel.COLUMN_EXPIRES_AT, messageModel.getExpiresAt());
        contentValues.put(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT, messageModel.getExpireStartedAt());
        contentValues.put(AbstractMessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS, messageModel.getDisappearingTimerSeconds());
        contentValues.put(AbstractMessageModel.COLUMN_FORWARD_SECURITY_MODE, messageModel.getForwardSecurityMode() != null ? messageModel.getForwardSecurityMode().getValue() : null);
        contentValues.put(AbstractMessageModel.COLUMN_DISPLAY_TAGS, messageModel.getDisplayTags());

        return contentValues;
    }

    void appendFilter(QueryBuilder queryBuilder, @Nullable MessageService.MessageFilter filter, List<String> placeholders) {
        if (filter != null) {
            if (!filter.withStatusMessages()) {
                queryBuilder.appendWhere(AbstractMessageModel.COLUMN_IS_STATUS_MESSAGE + "=0");
            }
            if (filter.onlyUnread()) {
                queryBuilder.appendWhere(AbstractMessageModel.COLUMN_OUTBOX + "=0");
                queryBuilder.appendWhere(AbstractMessageModel.COLUMN_IS_READ + "=0");
                queryBuilder.appendWhere(AbstractMessageModel.COLUMN_IS_STATUS_MESSAGE + "=0");
            }

            if (!filter.withUnsaved()) {
                queryBuilder.appendWhere(AbstractMessageModel.COLUMN_IS_SAVED + "=1");
            }

            if (filter.types() != null && filter.types().length > 0) {
                queryBuilder.appendWhere(AbstractMessageModel.COLUMN_TYPE + " IN (" + DatabaseUtil.makePlaceholders(filter.types().length) + ")");
                for (MessageType f : filter.types()) {
                    placeholders.add(String.valueOf(f.ordinal()));
                }
            }

            if (filter.contentTypes() != null && filter.contentTypes().length > 0) {
                queryBuilder.appendWhere(AbstractMessageModel.COLUMN_MESSAGE_CONTENTS_TYPE + " IN (" + DatabaseUtil.makePlaceholders(filter.contentTypes().length) + ")");
                for (@MessageContentsType int f : filter.contentTypes()) {
                    placeholders.add(String.valueOf(f));
                }
            }

            if (filter.getPageReferenceId() != null && filter.getPageReferenceId() > 0) {
                queryBuilder.appendWhere(AbstractMessageModel.COLUMN_ID + "<?");
                placeholders.add(String.valueOf(filter.getPageReferenceId()));
            }

            if (filter.displayTags() != null && filter.displayTags().length > 0) {
                for (@DisplayTag int f : filter.displayTags()) {
                    queryBuilder.appendWhere("(" + AbstractMessageModel.COLUMN_DISPLAY_TAGS + " & " + f + ") > 0");
                }
            }
        }
    }

    <T> void postFilter(List<T> input, @Nullable MessageService.MessageFilter filter) {
        if (filter != null && filter.onlyDownloaded()) {
            Iterator<T> i = input.iterator();
            while (i.hasNext()) {
                AbstractMessageModel m = (AbstractMessageModel) i.next();
                boolean remove = false;
                if (m.getType() == MessageType.VIDEO) {
                    VideoDataModel d = m.getVideoData();
                    remove = (d == null || !d.isDownloaded());
                } else if (m.getType() == MessageType.VOICEMESSAGE) {
                    AudioDataModel d = m.getAudioData();
                    remove = (d == null || !d.isDownloaded());
                } else if (m.getType() == MessageType.FILE) {
                    FileDataModel d = m.getFileData();
                    remove = (d == null || !d.isDownloaded());
                }

                if (remove) {
                    i.remove();
                }
            }
        }
    }

    String limitFilter(@Nullable MessageService.MessageFilter filter) {
        if (filter != null && filter.getPageSize() > 0) {
            return "" + filter.getPageSize();
        }
        return null;
    }

    /**
     * Mark file messages that have not been uploaded completely yet as failed so that users can
     * retry sending them. This affects file messages with state pending (upload did not start yet)
     * and uploading (upload may have been started already). File messages with state sending do not
     * need to be set to failed as they have been uploaded completely and a persistent task has been
     * scheduled. Therefore, these files will get sent as soon as there is a chat server connection.
     */
    public void markUnscheduledFileMessagesAsFailed() {
        ContentValues values = new ContentValues();
        values.put(AbstractMessageModel.COLUMN_STATE, MessageState.SENDFAILED.toString());

        try {
            int updated = getWritableDatabase().update(
                this.getTableName(),
                values,
                AbstractMessageModel.COLUMN_TYPE + " =?"
                    + " AND " + AbstractMessageModel.COLUMN_STATE + " IN (?, ?)"
                    + " AND " + AbstractMessageModel.COLUMN_OUTBOX + " = 1",
                new String[]{
                    String.valueOf(MessageType.FILE.ordinal()),
                    MessageState.PENDING.toString(),
                    MessageState.UPLOADING.toString(),
                }
            );

            if (updated > 0) {
                logger.info("{} messages in sending status were updated to sendfailed.", updated);
            }
        } catch (Exception e) {
            logger.error("Exception", e);
        }
    }

    /**
     * F1Whisper auto-resend: the states in which a FILE/media message's blob-upload phase has NOT
     * completed and therefore NO persistent send task has been scheduled yet - so the message is
     * genuinely unsent and safe to re-drive. Deliberately EXCLUDES {@link MessageState#SENDING}:
     * once a message is SENDING its persistent {@code OutgoingCspMessageTask} owns delivery (it
     * survives process death and auto-retries connectivity via the task queue), so auto-resending it
     * would double-send. Also EXCLUDES {@link MessageState#TRANSCODING}: a half-transcoded video has
     * no uploadable blob to resume (upstream's own {@code markUnscheduledFileMessagesAsFailed}
     * recovers only PENDING/UPLOADING for the same reason). Text/location/ballot never need this scan
     * - they go straight to the task queue at creation - which is why the candidate query is
     * additionally pinned to {@link MessageType#FILE}: FILE is the only type whose send has a
     * separate, process-death-fragile blob-upload phase driven by
     * {@link ch.threema.app.services.MessageSendingServiceExponentialBackOff}.
     */
    private static final String UNSENT_MEDIA_STATE_IN =
        AbstractMessageModel.COLUMN_STATE + " IN ("
            + "'" + MessageState.PENDING + "',"
            + "'" + MessageState.UPLOADING + "',"
            + "'" + MessageState.SENDFAILED + "'"
            + ")";

    /** Restrict auto-resend to FILE messages (the only type using the fragile blob-upload backoff). */
    private static final String AUTO_RESEND_TYPE_FILE =
        AbstractMessageModel.COLUMN_TYPE + " = " + MessageType.FILE.ordinal();

    /**
     * F1Whisper auto-resend: the SQL WHERE clause selecting outgoing FILE messages that are eligible
     * for silent auto-resend once connectivity returns. A candidate is:
     *
     *  - outgoing ({@code outbox = 1}), not a status message, not deleted;
     *  - a FILE message (see {@link #AUTO_RESEND_TYPE_FILE});
     *  - in an unsent blob-phase state (see {@link #UNSENT_MEDIA_STATE_IN}) - NEVER SENDING;
     *  - NOT marked terminal (the {@code DISPLAY_TAG_SEND_FAILED_TERMINAL} bit is clear), so
     *    FS / cancelled / MessageTooLong failures are excluded;
     *  - younger than the age cutoff (Signal-parity 24h lifespan), passed as a bind arg.
     *
     * The state list + type are literals (from trusted enums), the age cutoff is a bind arg.
     */
    private static final String AUTO_RESEND_WHERE =
        AbstractMessageModel.COLUMN_OUTBOX + " = 1"
            + " AND " + AbstractMessageModel.COLUMN_IS_STATUS_MESSAGE + " = 0"
            + " AND " + AbstractMessageModel.COLUMN_DELETED_AT + " IS NULL"
            + " AND " + AUTO_RESEND_TYPE_FILE
            + " AND (" + AbstractMessageModel.COLUMN_DISPLAY_TAGS + " & " + DisplayTag.DISPLAY_TAG_SEND_FAILED_TERMINAL + ") = 0"
            + " AND " + UNSENT_MEDIA_STATE_IN
            + " AND " + AbstractMessageModel.COLUMN_CREATED_AT + " > ?";

    /**
     * F1Whisper auto-resend: order candidates oldest-first by their immutable compose time so a
     * batch resend preserves the original send order.
     */
    private static final String AUTO_RESEND_ORDER_BY =
        AbstractMessageModel.COLUMN_CREATED_AT + " ASC, " + AbstractMessageModel.COLUMN_ID + " ASC";

    /**
     * F1Whisper auto-resend: run the auto-resend candidate query for this factory's table and
     * return the raw cursor (oldest first). Callers wrap it with their typed {@code convertList}.
     *
     * @param minCreatedAtMillis exclusive lower bound on {@code createdAtUtc} (epoch millis); rows
     *                           older than this are excluded (the 24h lifespan cutoff).
     */
    @WorkerThread
    protected android.database.Cursor queryAutoResendCandidates(long minCreatedAtMillis) {
        return getReadableDatabase().query(
            this.getTableName(),
            null,
            AUTO_RESEND_WHERE,
            new String[]{String.valueOf(minCreatedAtMillis)},
            null,
            null,
            AUTO_RESEND_ORDER_BY
        );
    }

    /**
     * F1Whisper auto-resend: the WHERE clause selecting outgoing FILE messages that were
     * auto-eligible but have now exhausted the 24h auto-resend window WITHOUT ever succeeding - i.e.
     * still in an unsent blob-phase state, not terminal, and OLDER than the age cutoff. These are
     * handed to the nag path so the user is finally told once (Signal/Telegram give up silently
     * after the window; we surface it). Mirrors {@link #AUTO_RESEND_WHERE} but flips the age
     * comparison.
     */
    private static final String AGED_OUT_UNSENT_WHERE =
        AbstractMessageModel.COLUMN_OUTBOX + " = 1"
            + " AND " + AbstractMessageModel.COLUMN_IS_STATUS_MESSAGE + " = 0"
            + " AND " + AbstractMessageModel.COLUMN_DELETED_AT + " IS NULL"
            + " AND " + AUTO_RESEND_TYPE_FILE
            + " AND (" + AbstractMessageModel.COLUMN_DISPLAY_TAGS + " & " + DisplayTag.DISPLAY_TAG_SEND_FAILED_TERMINAL + ") = 0"
            + " AND " + UNSENT_MEDIA_STATE_IN
            + " AND " + AbstractMessageModel.COLUMN_CREATED_AT + " <= ?";

    /**
     * F1Whisper auto-resend: query the aged-out unsent messages for this table (see
     * {@link #AGED_OUT_UNSENT_WHERE}). Callers wrap it with their typed {@code convertList}.
     *
     * @param maxCreatedAtMillis inclusive upper bound on {@code createdAtUtc}; rows at or before
     *                           this are past the 24h window.
     */
    @WorkerThread
    protected android.database.Cursor queryAgedOutUnsentMessages(long maxCreatedAtMillis) {
        return getReadableDatabase().query(
            this.getTableName(),
            null,
            AGED_OUT_UNSENT_WHERE,
            new String[]{String.valueOf(maxCreatedAtMillis)},
            null,
            null,
            AUTO_RESEND_ORDER_BY
        );
    }

    @WorkerThread
    public int unstarAllMessages() {
        String query =
            "UPDATE " + this.getTableName() +
                " SET " + AbstractMessageModel.COLUMN_DISPLAY_TAGS +
                " = (" + AbstractMessageModel.COLUMN_DISPLAY_TAGS +
                " & ~" + DISPLAY_TAG_STARRED +
                ") WHERE (" + AbstractMessageModel.COLUMN_DISPLAY_TAGS + " & " + DISPLAY_TAG_STARRED + ") > 0";

        SQLiteStatement statement = getWritableDatabase().compileStatement(query);
        return statement.executeUpdateDelete();
    }

    @WorkerThread
    public long countStarredMessages() throws SQLiteException {
        return DatabaseUtil.count(getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM " + this.getTableName()
                + " WHERE (" + AbstractMessageModel.COLUMN_DISPLAY_TAGS + " & " + DISPLAY_TAG_STARRED + ") > 0",
            null
        ));
    }
}
