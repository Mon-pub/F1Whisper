package ch.threema.storage.factories;

import static ch.threema.storage.models.data.DisplayTag.DISPLAY_TAG_STARRED;

import android.content.ContentValues;
import android.database.Cursor;

import org.jetbrains.annotations.NotNull;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import ch.threema.app.services.MessageService;
import ch.threema.domain.models.MessageId;
import ch.threema.storage.ChunkedSequence;
import ch.threema.storage.CursorHelper;
import ch.threema.storage.DatabaseCreationProvider;
import ch.threema.storage.DatabaseProvider;
import ch.threema.storage.DatabaseUtil;
import ch.threema.storage.QueryBuilder;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.MessageModel;
import ch.threema.storage.models.MessageType;
import kotlin.ranges.LongProgression;

public class MessageModelFactory extends AbstractMessageModelFactory {
    public MessageModelFactory(DatabaseProvider databaseProvider) {
        super(databaseProvider, MessageModel.TABLE);
    }

    public List<MessageModel> getAll() {
        return convertList(getReadableDatabase().query(this.getTableName(),
            null,
            null,
            null,
            null,
            null,
            null));
    }

    public MessageModel getByApiMessageIdAndIdentity(MessageId apiMessageId, String identity) {
        return getFirst(
            MessageModel.COLUMN_API_MESSAGE_ID + "=?" +
                " AND " + MessageModel.COLUMN_IDENTITY + "=?",
            new String[]{
                apiMessageId.toString(),
                identity
            });
    }

    public MessageModel getByApiMessageIdAndIdentityAndIsOutbox(MessageId apiMessageId, @NonNull String recipientIdentity, boolean isOutbox) {
        return getFirst(
            MessageModel.COLUMN_API_MESSAGE_ID + "=?"
                + " AND " + MessageModel.COLUMN_IDENTITY + "=?"
                + " AND " + MessageModel.COLUMN_OUTBOX + "=?",
            new String[]{
                apiMessageId.toString(),
                recipientIdentity,
                isOutbox ? "1" : "0"
            });
    }

    public MessageModel getById(int id) {
        return getFirst(
            MessageModel.COLUMN_ID + "=?",
            new String[]{
                String.valueOf(id)
            });
    }

    public MessageModel getByUid(String uid) {
        return getFirst(
            MessageModel.COLUMN_UID + "=?",
            new String[]{
                uid
            });
    }

    public List<AbstractMessageModel> getMessagesByText(@Nullable String text, boolean includeArchived, boolean starredOnly, boolean sortAscending) {
        String displayClause, sortClause;
        if (starredOnly) {
            displayClause = " AND (displayTags & " + DISPLAY_TAG_STARRED + ") > 0 ";
        } else {
            displayClause = "";
        }

        if (sortAscending) {
            sortClause = " ASC ";
        } else {
            sortClause = " DESC ";
        }

        if (includeArchived) {
            if (text == null) {
                return convertAbstractList(getReadableDatabase().rawQuery(
                    "SELECT * FROM " + MessageModel.TABLE +
                        " WHERE isStatusMessage = 0" +
                        displayClause +
                        " ORDER BY createdAtUtc" + sortClause +
                        "LIMIT 200",
                    new String[]{}));
            }

            return convertAbstractList(getReadableDatabase().rawQuery(
                "SELECT * FROM " + MessageModel.TABLE +
                    " WHERE ( ( body LIKE ? " +
                    " AND type IN (" +
                    MessageType.TEXT.ordinal() + "," +
                    MessageType.LOCATION.ordinal() + "," +
                    MessageType.BALLOT.ordinal() + ") )" +
                    " OR ( caption LIKE ? " +
                    " AND type IN (" +
                    MessageType.IMAGE.ordinal() + "," +
                    MessageType.FILE.ordinal() + ") ) )" +
                    " AND isStatusMessage = 0" +
                    displayClause +
                    " ORDER BY createdAtUtc" + sortClause +
                    "LIMIT 200",
                new String[]{
                    "%" + text + "%",
                    "%" + text + "%"
                }));
        } else {
            if (text == null) {
                return convertAbstractList(getReadableDatabase().rawQuery(
                    "SELECT * FROM " + MessageModel.TABLE + " m" +
                        " INNER JOIN " + ContactModel.TABLE + " c ON c.identity = m.identity" +
                        " WHERE c.isArchived = 0" +
                        " AND m.isStatusMessage = 0" +
                        displayClause +
                        " ORDER BY m.createdAtUtc" + sortClause +
                        "LIMIT 200",
                    new String[]{}));
            }
            return convertAbstractList(getReadableDatabase().rawQuery(
                "SELECT * FROM " + MessageModel.TABLE + " m" +
                    " INNER JOIN " + ContactModel.TABLE + " c ON c.identity = m.identity" +
                    " WHERE c.isArchived = 0" +
                    " AND ( ( m.body LIKE ? " +
                    " AND m.type IN (" +
                    MessageType.TEXT.ordinal() + "," +
                    MessageType.LOCATION.ordinal() + "," +
                    MessageType.BALLOT.ordinal() + ") )" +
                    " OR ( m.caption LIKE ? " +
                    " AND m.type IN (" +
                    MessageType.IMAGE.ordinal() + "," +
                    MessageType.FILE.ordinal() + ") ) )" +
                    " AND m.isStatusMessage = 0" +
                    displayClause +
                    " ORDER BY m.createdAtUtc" + sortClause +
                    "LIMIT 200",
                new String[]{
                    "%" + text + "%",
                    "%" + text + "%"
                }));
        }
    }

    /**
     * Convert a cursor's rows to a list of {@link AbstractMessageModel}s.
     * Note that the cursor will be closed after conversion.
     */
    private List<AbstractMessageModel> convertAbstractList(Cursor cursor) {
        List<AbstractMessageModel> result = new ArrayList<>();
        if (cursor != null) {
            try (cursor) {
                while (cursor.moveToNext()) {
                    result.add(convert(cursor));
                }
            }
        }
        return result;
    }

    /**
     * Convert a cursor's rows to a list of {@link MessageModel}s.
     * Note that the cursor will be closed after conversion.
     */
    private List<MessageModel> convertList(Cursor cursor) {
        List<MessageModel> result = new ArrayList<>();
        if (cursor != null) {
            try (cursor) {
                while (cursor.moveToNext()) {
                    result.add(convert(cursor));
                }
            }
        }
        return result;
    }

    private MessageModel convert(Cursor cursor) {
        if (cursor != null && cursor.getPosition() >= 0) {

            MessageModel c = new MessageModel();

            //convert default
            super.convert(c, new CursorHelper(cursor, getColumnIndexCache()));

            return c;
        }

        return null;
    }

    public long countMessages(String identity) {
        return DatabaseUtil.count(getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM " + this.getTableName()
                + " WHERE " + MessageModel.COLUMN_IDENTITY + "=?",
            new String[]{
                identity
            }
        ));
    }

    public long countUnreadMessages(String identity) {
        return DatabaseUtil.count(getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM " + this.getTableName()
                + " WHERE " + MessageModel.COLUMN_IDENTITY + "=?"
                + " AND " + MessageModel.COLUMN_OUTBOX + "=0"
                + " AND " + MessageModel.COLUMN_IS_SAVED + "=1"
                + " AND " + MessageModel.COLUMN_IS_READ + "=0"
                + " AND " + MessageModel.COLUMN_IS_STATUS_MESSAGE + "=0",
            new String[]{
                identity
            }
        ));
    }


    public List<MessageModel> getUnreadMessages(String identity) {
        return convertList(getReadableDatabase().query(this.getTableName(),
            null,
            MessageModel.COLUMN_IDENTITY + "=?"
                + " AND " + MessageModel.COLUMN_OUTBOX + "=0"
                + " AND " + MessageModel.COLUMN_IS_SAVED + "=1"
                + " AND " + MessageModel.COLUMN_IS_READ + "=0"
                + " AND " + MessageModel.COLUMN_IS_STATUS_MESSAGE + "=0",
            new String[]{
                identity
            },
            null,
            null,
            null));
    }

    public MessageModel getLastMessage(String identity) {
        Cursor cursor = getReadableDatabase().query(
            this.getTableName(),
            null,
            MessageModel.COLUMN_IDENTITY + "=?",
            new String[]{identity},
            null,
            null,
            MessageModel.COLUMN_ID + " DESC",
            "1");

        if (cursor != null) {
            try (cursor) {
                if (cursor.moveToFirst()) {
                    return convert(cursor);
                }
            }
        }

        return null;
    }

    public long countByTypes(MessageType[] messageTypes) {
        String[] args = new String[messageTypes.length];
        for (int n = 0; n < messageTypes.length; n++) {
            args[n] = String.valueOf(messageTypes[n].ordinal());
        }
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM " + this.getTableName()
                + " WHERE " + MessageModel.COLUMN_TYPE + " IN (" + DatabaseUtil.makePlaceholders(args.length) + ")",
            args
        );

        return DatabaseUtil.count(c);
    }

    public boolean createOrUpdate(@NonNull MessageModel messageModel) {
        boolean insert = true;
        if (messageModel.getId() > 0) {
            Cursor cursor = getReadableDatabase().query(
                this.getTableName(),
                null,
                MessageModel.COLUMN_ID + "=?",
                new String[]{
                    String.valueOf(messageModel.getId())
                },
                null,
                null,
                null
            );

            if (cursor != null) {
                try (cursor) {
                    insert = !cursor.moveToNext();
                }
            }
        }

        if (insert) {
            return create(messageModel);
        } else {
            return update(messageModel);
        }
    }

    public boolean create(MessageModel messageModel) {
        ContentValues contentValues = this.buildContentValues(messageModel);
        long newId = getWritableDatabase().insertOrThrow(this.getTableName(), null, contentValues);
        if (newId > 0) {
            messageModel.setId((int) newId);
            return true;
        }
        return false;
    }

    public boolean update(MessageModel messageModel) {
        ContentValues contentValues = this.buildContentValues(messageModel);
        getWritableDatabase().update(this.getTableName(),
            contentValues,
            MessageModel.COLUMN_ID + "=?",
            new String[]{
                String.valueOf(messageModel.getId())
            });
        return true;
    }

    public int delete(MessageModel messageModel) {
        return getWritableDatabase().delete(this.getTableName(),
            MessageModel.COLUMN_ID + "=?",
            new String[]{
                String.valueOf(messageModel.getId())
            });
    }

    public List<MessageModel> find(String identity, MessageService.MessageFilter filter) {
        QueryBuilder queryBuilder = new QueryBuilder();

        // F1Whisper: sort by the immutable per-row sort key (sortAtUtc). It is outgoing ->
        // createdAtUtc (local compose time, immutable) / incoming -> COALESCE(postedAtUtc,
        // createdAtUtc) (sender time). The earlier postedAtUtc-only sort reordered same-minute
        // OUTGOING messages, because postedAtUtc is overwritten with the send-completion time
        // (sentAt), which is not monotonic with compose order (FS/blob/retry finish out of order).
        // sortAtUtc is never overwritten, so compose order is preserved while incoming rows still
        // keep sender time (so a reconnect-backlog blob stays below its own replies). The inner CASE
        // is a defensive fallback for any row written before the v124 backfill. Then id for stability.
        String orderBy =
            "COALESCE(" + AbstractMessageModel.COLUMN_SORT_AT + ", "
                + "CASE WHEN " + AbstractMessageModel.COLUMN_OUTBOX + " = 1 "
                + "THEN " + AbstractMessageModel.COLUMN_CREATED_AT + " "
                + "ELSE COALESCE(" + AbstractMessageModel.COLUMN_POSTED_AT + ", "
                + AbstractMessageModel.COLUMN_CREATED_AT + ") END) DESC, "
                + MessageModel.COLUMN_ID + " DESC";
        List<String> placeholders = new ArrayList<>();

        queryBuilder.appendWhere(MessageModel.COLUMN_IDENTITY + "=?");
        placeholders.add(identity);

        //default filters
        this.appendFilter(queryBuilder, filter, placeholders);
        queryBuilder.setTables(this.getTableName());
        List<MessageModel> messageModels = convertList(queryBuilder.query(
            getReadableDatabase(),
            null,
            null,
            placeholders.toArray(new String[0]),
            null,
            null,
            orderBy,
            this.limitFilter(filter)));

        this.postFilter(messageModels, filter);

        return messageModels;
    }

    /**
     * Check if there is a call with the given identity and call id within the latest calls.
     *
     * @param identity the identity of the call partner
     * @param callId   the call id
     * @param limit    the maximum number of latest calls
     * @return {@code true} if this call exists in the latest calls, {@code false} otherwise
     */
    public boolean hasVoipStatusForCallId(@NonNull String identity, long callId, int limit) {
        QueryBuilder queryBuilder = new QueryBuilder();

        String orderBy = AbstractMessageModel.COLUMN_CREATED_AT + " DESC";

        queryBuilder.appendWhere(AbstractMessageModel.COLUMN_IDENTITY + "=?");
        queryBuilder.appendWhere(AbstractMessageModel.COLUMN_TYPE + "=?");

        queryBuilder.setTables(this.getTableName());

        Cursor cursor = queryBuilder.query(getReadableDatabase(),
            null,
            null,
            new String[]{identity, String.valueOf(MessageType.VOIP_STATUS.ordinal())},
            null,
            null,
            orderBy,
            String.valueOf(limit));

        if (cursor != null) {
            try (cursor) {
                List<MessageModel> messageModels = convertList(cursor);
                for (MessageModel messageModel : messageModels) {
                    if (messageModel.getVoipStatusData() != null && callId == messageModel.getVoipStatusData().getCallId()) {
                        return true;
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return false;
    }

    public ChunkedSequence<MessageModel> getByIdentity(String identity) {
        long count;
        try (Cursor cursor = getReadableDatabase().query(
            "SELECT COUNT(*) FROM `" + getTableName() + "` WHERE `" + MessageModel.COLUMN_IDENTITY + "` = ?",
            new String[]{identity}
        )) {
            if (cursor.moveToNext()) {
                count = cursor.getLong(0);
            } else {
                count = 0;
            }
        }

        return new ChunkedSequence<>(
            LongProgression.Companion.fromClosedRange(0, count, 10000),
            (from, size) ->
                getReadableDatabase().query(
                    "SELECT * FROM `" + getTableName() + "` WHERE `" + MessageModel.COLUMN_IDENTITY + "` = ? LIMIT ? OFFSET ?",
                    new String[]{identity, String.valueOf(size), String.valueOf(from)}
                ),
            this::convert
        );
    }

    /**
     * F1Whisper disappearing messages: all 1:1 messages whose disappear deadline has already passed
     * ({@code expiresAtUtc <= now} and {@code expiresAtUtc} is set). Used by the deletion engine's
     * sweep-all-due fire and the startup purge.
     */
    public List<MessageModel> getMessagesExpiredBefore(long now) {
        return convertList(getReadableDatabase().query(
            this.getTableName(),
            null,
            AbstractMessageModel.COLUMN_EXPIRES_AT + " IS NOT NULL"
                + " AND " + AbstractMessageModel.COLUMN_EXPIRES_AT + "<=?",
            new String[]{String.valueOf(now)},
            null,
            null,
            AbstractMessageModel.COLUMN_EXPIRES_AT + " ASC"));
    }

    /**
     * F1Whisper disappearing messages: the soonest pending expiry across all 1:1 messages
     * ({@code MIN(expiresAtUtc)} where set), or {@code null} if nothing is scheduled. Used to arm
     * the next alarm.
     */
    @Nullable
    public Long getEarliestExpiry() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
            "SELECT MIN(`" + AbstractMessageModel.COLUMN_EXPIRES_AT + "`) FROM " + this.getTableName()
                + " WHERE `" + AbstractMessageModel.COLUMN_EXPIRES_AT + "` IS NOT NULL",
            null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        }
        return null;
    }

    private MessageModel getFirst(String selection, String[] selectionArgs) {
        Cursor cursor = getReadableDatabase().query(
            this.getTableName(),
            null,
            selection,
            selectionArgs,
            null,
            null,
            null
        );

        try (cursor) {
            if (cursor != null && cursor.moveToFirst()) {
                return convert(cursor);
            }
        }
        return null;
    }

    public static class Creator implements DatabaseCreationProvider {
        @Override
        @NonNull
        public String [] getCreationStatements() {
            return new String[]{
                //create table
                "CREATE TABLE `" + MessageModel.TABLE + "`(" +
                    "`" + MessageModel.COLUMN_ID + "` INTEGER PRIMARY KEY AUTOINCREMENT , " +
                    "`" + MessageModel.COLUMN_UID + "` VARCHAR , " +
                    "`" + MessageModel.COLUMN_API_MESSAGE_ID + "` VARCHAR , " +
                    "`" + MessageModel.COLUMN_IDENTITY + "` VARCHAR , " +
                    //TODO(ANDR-XXXX): change to TINYINT
                    "`" + MessageModel.COLUMN_OUTBOX + "` SMALLINT , " +
                    "`" + MessageModel.COLUMN_TYPE + "` INTEGER , " +
                    "`" + MessageModel.COLUMN_BODY + "` VARCHAR , " +
                    "`" + MessageModel.COLUMN_CORRELATION_ID + "` VARCHAR , " +
                    "`" + MessageModel.COLUMN_CAPTION + "` VARCHAR , " +
                    //TODO(ANDR-XXXX): change to TINYINT
                    "`" + MessageModel.COLUMN_IS_READ + "` SMALLINT , " +
                    //TODO(ANDR-XXXX): change to TINYINT
                    "`" + MessageModel.COLUMN_IS_SAVED + "` SMALLINT , " +
                    "`" + MessageModel.COLUMN_IS_QUEUED + "` TINYINT , " +
                    "`" + MessageModel.COLUMN_STATE + "` VARCHAR , " +
                    "`" + MessageModel.COLUMN_POSTED_AT + "` BIGINT , " +
                    "`" + MessageModel.COLUMN_CREATED_AT + "` BIGINT , " +
                    "`" + MessageModel.COLUMN_MODIFIED_AT + "` BIGINT , " +
                    //TODO(ANDR-XXXX): change to TINYINT
                    "`" + MessageModel.COLUMN_IS_STATUS_MESSAGE + "` SMALLINT ," +
                    "`" + MessageModel.COLUMN_QUOTED_MESSAGE_API_MESSAGE_ID + "` VARCHAR ," +
                    "`" + MessageModel.COLUMN_MESSAGE_CONTENTS_TYPE + "` TINYINT ," +
                    "`" + MessageModel.COLUMN_MESSAGE_FLAGS + "` INT ," +
                    "`" + MessageModel.COLUMN_DELIVERED_AT + "` DATETIME ," +
                    "`" + MessageModel.COLUMN_READ_AT + "` DATETIME ," +
                    "`" + MessageModel.COLUMN_FORWARD_SECURITY_MODE + "` TINYINT DEFAULT 0 ," +
                    "`" + MessageModel.COLUMN_DISPLAY_TAGS + "` TINYINT DEFAULT 0 ," +
                    "`" + MessageModel.COLUMN_EDITED_AT + "` DATETIME ," +
                    "`" + MessageModel.COLUMN_DELETED_AT + "` DATETIME ," +
                    // F1Whisper disappearing messages (mirror DatabaseUpdateToVersion122)
                    "`" + MessageModel.COLUMN_EXPIRES_AT + "` BIGINT DEFAULT NULL ," +
                    "`" + MessageModel.COLUMN_EXPIRE_STARTED_AT + "` BIGINT DEFAULT NULL ," +
                    "`" + MessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS + "` INTEGER DEFAULT NULL ," +
                    // F1Whisper message-ordering fix (mirror DatabaseUpdateToVersion124)
                    "`" + MessageModel.COLUMN_SORT_AT + "` BIGINT DEFAULT NULL );",

                // indices
                "CREATE INDEX `contact_message_uid_idx` ON `" + MessageModel.TABLE + "` ( `" + MessageModel.COLUMN_UID + "` )",
                "CREATE INDEX `message_identity_idx` ON `" + MessageModel.TABLE + "` ( `" + MessageModel.COLUMN_IDENTITY + "` )",
                "CREATE INDEX `messageApiMessageIdIdx` ON `" + MessageModel.TABLE + "` ( `" + MessageModel.COLUMN_API_MESSAGE_ID + "` )",
                "CREATE INDEX `message_outbox_idx` ON `" + MessageModel.TABLE + "` ( `" + MessageModel.COLUMN_OUTBOX + "` )",
                "CREATE INDEX `messageCorrelationIdIx` ON `" + MessageModel.TABLE + "` ( `" + MessageModel.COLUMN_CORRELATION_ID + "` )",
                "CREATE INDEX `message_count_idx` ON `" + MessageModel.TABLE
                    + "`(`" + MessageModel.COLUMN_IDENTITY
                    + "`, `" + MessageModel.COLUMN_OUTBOX
                    + "`, `" + MessageModel.COLUMN_IS_SAVED
                    + "`, `" + MessageModel.COLUMN_IS_READ
                    + "`, `" + MessageModel.COLUMN_IS_STATUS_MESSAGE
                    + "`)",
                "CREATE INDEX `message_state_idx` ON `" + MessageModel.TABLE
                    + "`(`" + AbstractMessageModel.COLUMN_TYPE
                    + "`, `" + AbstractMessageModel.COLUMN_STATE
                    + "`, `" + AbstractMessageModel.COLUMN_OUTBOX
                    + "`)",
                // F1Whisper disappearing messages: the alarm engine arms/sweeps on expiresAtUtc.
                "CREATE INDEX `" + MessageModel.TABLE + "_expiresAt_idx` ON `" + MessageModel.TABLE
                    + "` ( `" + AbstractMessageModel.COLUMN_EXPIRES_AT + "` )"
            };
        }
    }
}
