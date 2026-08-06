package ch.threema.storage.factories;

import android.content.ContentValues;
import android.database.Cursor;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.app.services.MessageService;
import ch.threema.app.utils.JsonUtil;
import ch.threema.domain.models.MessageId;
import ch.threema.storage.ChunkedSequence;
import ch.threema.storage.CursorHelper;
import ch.threema.storage.DatabaseCreationProvider;
import ch.threema.storage.DatabaseProvider;
import ch.threema.storage.DatabaseUtil;
import ch.threema.storage.QueryBuilder;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.group.GroupMessageModel;
import ch.threema.data.models.GroupModel;
import ch.threema.storage.models.MessageState;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.group.GroupModelOld;
import kotlin.ranges.LongProgression;

import static ch.threema.storage.models.data.DisplayTag.DISPLAY_TAG_STARRED;

public class GroupMessageModelFactory extends AbstractMessageModelFactory {
    public GroupMessageModelFactory(DatabaseProvider databaseProvider) {
        super(databaseProvider, GroupMessageModel.TABLE);
    }

    public List<GroupMessageModel> getAll() {
        return convertList(getReadableDatabase().query(this.getTableName(),
            null,
            null,
            null,
            null,
            null,
            null));
    }

    public GroupMessageModel getByApiMessageIdAndIdentity(MessageId apiMessageId, String identity) {
        return getFirst(
            GroupMessageModel.COLUMN_API_MESSAGE_ID + "=?" +
                " AND " + GroupMessageModel.COLUMN_IDENTITY + "=?",
            new String[]{
                apiMessageId.toString(),
                identity
            });
    }

    public GroupMessageModel getByApiMessageIdAndGroupId(@NonNull MessageId apiMessageId, int groupId) {
        return getFirst(
            GroupMessageModel.COLUMN_API_MESSAGE_ID + "=?" +
                " AND " + GroupMessageModel.COLUMN_GROUP_ID + "=?",
            new String[]{
                apiMessageId.toString(),
                String.valueOf(groupId),
            });
    }

    public GroupMessageModel getById(int id) {
        return getFirst(
            GroupMessageModel.COLUMN_ID + "=?",
            new String[]{
                String.valueOf(id)
            });
    }

    public GroupMessageModel getByUid(String uid) {
        return getFirst(
            GroupMessageModel.COLUMN_UID + "=?",
            new String[]{
                uid
            });
    }

    public List<GroupMessageModel> getAllRejectedMessagesInGroup(@NonNull GroupModel group) {
        return convertList(
            getReadableDatabase().query(
                getTableName(),
                null,
                GroupMessageModel.COLUMN_GROUP_ID + "=? AND " + AbstractMessageModel.COLUMN_STATE + "=?",
                new String[]{String.valueOf(group.getDatabaseId()), MessageState.FS_KEY_MISMATCH.toString()},
                null,
                null,
                null
            )
        );
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
                    "SELECT * FROM " + GroupMessageModel.TABLE +
                        " WHERE isStatusMessage = 0" +
                        displayClause +
                        " ORDER BY createdAtUtc" + sortClause +
                        "LIMIT 200",
                    new String[]{}));
            }

            return convertAbstractList(getReadableDatabase().rawQuery(
                "SELECT * FROM " + GroupMessageModel.TABLE +
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
                    "SELECT * FROM " + GroupMessageModel.TABLE + " m" +
                        " INNER JOIN " + GroupModelOld.TABLE + " g ON g.id = m.groupId" +
                        " WHERE g.isArchived = 0" +
                        " AND m.isStatusMessage = 0" +
                        displayClause +
                        " ORDER BY m.createdAtUtc" + sortClause +
                        "LIMIT 200",
                    new String[]{}));
            }

            return convertAbstractList(getReadableDatabase().rawQuery(
                "SELECT * FROM " + GroupMessageModel.TABLE + " m" +
                    " INNER JOIN " + GroupModelOld.TABLE + " g ON g.id = m.groupId" +
                    " WHERE g.isArchived = 0" +
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
     * Convert a cursor's rows to a list of {@link GroupMessageModel}s.
     * Note that the cursor will be closed after conversion.
     */
    private List<GroupMessageModel> convertList(Cursor cursor) {
        List<GroupMessageModel> result = new ArrayList<>();
        if (cursor != null) {
            try (cursor) {
                while (cursor.moveToNext()) {
                    result.add(convert(cursor));
                }
            }
        }
        return result;
    }

    private GroupMessageModel convert(Cursor cursor) {
        if (cursor != null && cursor.getPosition() >= 0) {
            final GroupMessageModel groupMessageModel = new GroupMessageModel();

            //convert default
            super.convert(groupMessageModel, new CursorHelper(cursor, getColumnIndexCache()).current((CursorHelper.Callback) cursorHelper -> {
                int groupId = Objects.requireNonNull(cursorHelper.getInt(GroupMessageModel.COLUMN_GROUP_ID));
                groupMessageModel.setGroupId(groupId);
                String messageStates = cursorHelper.getString(GroupMessageModel.COLUMN_GROUP_MESSAGE_STATES);
                if (messageStates != null) {
                    try {
                        Map<String, Object> messageStatesMap = JsonUtil.convertObject(messageStates);
                        groupMessageModel.setGroupMessageStates(messageStatesMap);
                    } catch (JSONException ignored) {
                        // map may not be available or empty
                        groupMessageModel.setGroupMessageStates(null);
                    }
                }
                return false;
            }));

            return groupMessageModel;
        }

        return null;
    }

    /**
     * F1Whisper auto-resend: outgoing group messages eligible for silent auto-resend (see
     * {@code AbstractMessageModelFactory#queryAutoResendCandidates}), oldest compose-time first.
     *
     * @param minCreatedAtMillis exclusive lower bound on createdAtUtc (24h lifespan cutoff).
     */
    @NonNull
    public List<GroupMessageModel> getAutoResendCandidates(long minCreatedAtMillis) {
        return convertList(queryAutoResendCandidates(minCreatedAtMillis));
    }

    /**
     * F1Whisper auto-resend: outgoing group messages that are still unsent but have exhausted the
     * 24h auto-resend window (see {@code AbstractMessageModelFactory#queryAgedOutUnsentMessages}).
     *
     * @param maxCreatedAtMillis inclusive upper bound on createdAtUtc (past the 24h window).
     */
    @NonNull
    public List<GroupMessageModel> getAgedOutUnsentMessages(long maxCreatedAtMillis) {
        return convertList(queryAgedOutUnsentMessages(maxCreatedAtMillis));
    }

    public long countMessages(int groupId) {
        return DatabaseUtil.count(getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM " + this.getTableName()
                + " WHERE " + GroupMessageModel.COLUMN_GROUP_ID + "=?",
            new String[]{
                String.valueOf(groupId)
            }
        ));
    }

    public long countUnreadMessages(int groupId) {
        return DatabaseUtil.count(getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM " + this.getTableName()
                + " WHERE " + GroupMessageModel.COLUMN_GROUP_ID + "=?"
                + " AND " + UNREAD_ROW_WHERE,
            new String[]{
                String.valueOf(groupId)
            }
        ));
    }

    public List<GroupMessageModel> getUnreadMessages(int groupId) {
        return convertList(getReadableDatabase().query(this.getTableName(),
            null,
            GroupMessageModel.COLUMN_GROUP_ID + "=?"
                + " AND " + UNREAD_ROW_WHERE,
            new String[]{
                String.valueOf(groupId)
            },
            null,
            null,
            null));
    }

    public long countByTypes(MessageType[] messageTypes) {
        String[] args = new String[messageTypes.length];
        for (int n = 0; n < messageTypes.length; n++) {
            args[n] = String.valueOf(messageTypes[n].ordinal());
        }

        Cursor c = getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM " + this.getTableName()
                + " WHERE " + GroupMessageModel.COLUMN_TYPE + " IN (" + DatabaseUtil.makePlaceholders(args.length) + ")",
            args
        );
        return DatabaseUtil.count(c);
    }

    public boolean createOrUpdate(GroupMessageModel groupMessageModel) {
        // F1Whisper (sixth fork review F6-01, seventh F7-01): see AbstractMessageModelFactory#refusesReinsertion.
        if (groupMessageModel.getId() > 0) {
            if (update(groupMessageModel)) {
                return true;
            }
            refusesReinsertion(groupMessageModel.getId());
            return false;
        }
        return create(groupMessageModel);
    }

    public boolean create(GroupMessageModel groupMessageModel) {
        ContentValues contentValues = this.buildContentValues(groupMessageModel);
        contentValues.put(GroupMessageModel.COLUMN_GROUP_ID, groupMessageModel.getGroupId());
        addGroupMessageStates(contentValues, groupMessageModel);
        long newId = getWritableDatabase().insertOrThrow(this.getTableName(), null, contentValues);
        if (newId > 0) {
            groupMessageModel.setId((int) newId);
            return true;
        }
        return false;
    }

    /**
     * F1Whisper (seventh fork review, F7-01): reports whether the row was actually written. See
     * {@link MessageModelFactory#update(ch.threema.storage.models.MessageModel)}.
     */
    public boolean update(GroupMessageModel groupMessageModel) {
        ContentValues contentValues = this.buildContentValues(groupMessageModel);
        addGroupMessageStates(contentValues, groupMessageModel);
        return getWritableDatabase().update(this.getTableName(),
            contentValues,
            CONTENT_ROW_WHERE,
            new String[]{
                String.valueOf(groupMessageModel.getId()),
            }) > 0;
    }

    public List<GroupMessageModel> find(int groupId, MessageService.MessageFilter filter) {
        QueryBuilder queryBuilder = new QueryBuilder();

        // F1Whisper: sort by the immutable per-row sort key (sortAtUtc), then id for stability. See
        // MessageModelFactory.find for the full rationale. Shared TIMELINE_ORDER_BY so the
        // pagination keyset in appendFilter stays in lockstep with the ordering (fork review H-06).
        String orderBy = TIMELINE_ORDER_BY;
        List<String> placeholders = new ArrayList<>();

        queryBuilder.appendWhere(GroupMessageModel.COLUMN_GROUP_ID + "=?");
        placeholders.add(String.valueOf(groupId));

        //default filters
        this.appendFilter(queryBuilder, filter, placeholders);

        queryBuilder.setTables(this.getTableName());
        List<GroupMessageModel> messageModels = convertList(queryBuilder.query(
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

    public ChunkedSequence<GroupMessageModel> getByGroupId(long groupId) {
        long count;
        try (Cursor cursor = getReadableDatabase().query(
            "SELECT COUNT(*) FROM `" + getTableName() + "` WHERE `" + GroupMessageModel.COLUMN_GROUP_ID + "` = ?",
            new String[]{String.valueOf(groupId)}
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
                    "SELECT * FROM `" + getTableName() + "` WHERE `" + GroupMessageModel.COLUMN_GROUP_ID + "` = ? LIMIT ? OFFSET ?",
                    new String[]{String.valueOf(groupId), String.valueOf(size), String.valueOf(from)}
                ),
            this::convert
        );
    }

    public int delete(GroupMessageModel groupMessageModel) {
        return getWritableDatabase().delete(this.getTableName(),
            GroupMessageModel.COLUMN_ID + "=?",
            new String[]{
                String.valueOf(groupMessageModel.getId())
            });
    }

    public int deleteByGroupId(long groupId) {
        return getWritableDatabase().delete(this.getTableName(),
            GroupMessageModel.COLUMN_GROUP_ID + "=?",
            new String[]{
                String.valueOf(groupId)
            });
    }

    /**
     * F1Whisper disappearing messages: all group messages whose disappear deadline has already passed
     * ({@code expiresAtUtc <= now} and {@code expiresAtUtc} is set). Mirror of
     * {@link MessageModelFactory#getMessagesExpiredBefore(long)} for the group table.
     */
    public List<GroupMessageModel> getMessagesExpiredBefore(long now) {
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
     * F1Whisper disappearing messages: group rows whose countdown can never reach a deadline. Mirror
     * of {@link MessageModelFactory#getRepairableExpiryCandidates(int)} for the group table; see
     * there for why a row with no {@code expiresAtUtc} is invisible to the rest of the engine and why
     * this scan is confined to the boot/app-update path.
     */
    @NonNull
    public List<GroupMessageModel> getRepairableExpiryCandidates(int limit) {
        return convertList(getReadableDatabase().query(
            this.getTableName(),
            null,
            AbstractMessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS + " > 0"
                + " AND ("
                + "(" + AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT + " IS NOT NULL"
                + " AND " + AbstractMessageModel.COLUMN_EXPIRES_AT + " IS NULL)"
                + " OR (" + AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT + " IS NULL"
                + " AND " + AbstractMessageModel.COLUMN_IS_READ + " = 1"
                + " AND " + AbstractMessageModel.COLUMN_OUTBOX + " = 0)"
                + ")",
            null,
            null,
            null,
            AbstractMessageModel.COLUMN_ID + " ASC",
            String.valueOf(limit)));
    }

    /**
     * F1Whisper disappearing messages: the soonest pending expiry across all group messages
     * ({@code MIN(expiresAtUtc)} where set), or {@code null} if nothing is scheduled. Mirror of
     * {@link MessageModelFactory#getEarliestExpiry()} for the group table.
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

    private GroupMessageModel getFirst(String selection, String[] selectionArgs) {
        Cursor cursor = getReadableDatabase().query(
            this.getTableName(),
            null,
            selection,
            selectionArgs,
            null,
            null,
            null
        );

        if (cursor != null) {
            try (cursor) {
                if (cursor.moveToFirst()) {
                    return convert(cursor);
                }
            }
        }

        return null;
    }

    private void addGroupMessageStates(@NonNull ContentValues contentValues, @NonNull GroupMessageModel groupMessageModel) {
        String groupMessageStates = null;
        if (groupMessageModel.getGroupMessageStates() != null) {
            groupMessageStates = new JSONObject(groupMessageModel.getGroupMessageStates()).toString();
        }

        contentValues.put(GroupMessageModel.COLUMN_GROUP_MESSAGE_STATES, groupMessageStates);
    }

    public static class Creator implements DatabaseCreationProvider {
        @Override
        @NonNull
        public String [] getCreationStatements() {
            return new String[]{
                "CREATE TABLE `" + GroupMessageModel.TABLE + "`" +
                    "(" +
                    "`" + GroupMessageModel.COLUMN_ID + "` INTEGER PRIMARY KEY AUTOINCREMENT , " +
                    "`" + GroupMessageModel.COLUMN_UID + "` VARCHAR , " +
                    "`" + GroupMessageModel.COLUMN_API_MESSAGE_ID + "` VARCHAR , " +
                    "`" + GroupMessageModel.COLUMN_GROUP_ID + "` INTEGER NOT NULL , " +
                    "`" + GroupMessageModel.COLUMN_IDENTITY + "` VARCHAR , " +
                    "`" + GroupMessageModel.COLUMN_OUTBOX + "` SMALLINT , " +
                    "`" + GroupMessageModel.COLUMN_TYPE + "` INTEGER ," +
                    "`" + GroupMessageModel.COLUMN_CORRELATION_ID + "` VARCHAR ," +
                    "`" + GroupMessageModel.COLUMN_BODY + "` VARCHAR ," +
                    "`" + GroupMessageModel.COLUMN_CAPTION + "` VARCHAR ," +
                    "`" + GroupMessageModel.COLUMN_IS_READ + "` SMALLINT ," +
                    "`" + GroupMessageModel.COLUMN_IS_SAVED + "` SMALLINT ," +
                    "`" + GroupMessageModel.COLUMN_IS_QUEUED + "` TINYINT ," +
                    "`" + GroupMessageModel.COLUMN_STATE + "` VARCHAR , " +
                    "`" + GroupMessageModel.COLUMN_POSTED_AT + "` BIGINT , " +
                    "`" + GroupMessageModel.COLUMN_CREATED_AT + "` BIGINT , " +
                    "`" + GroupMessageModel.COLUMN_MODIFIED_AT + "` BIGINT , " +
                    "`" + GroupMessageModel.COLUMN_IS_STATUS_MESSAGE + "` SMALLINT ," +
                    "`" + GroupMessageModel.COLUMN_QUOTED_MESSAGE_API_MESSAGE_ID + "` VARCHAR ," +
                    "`" + GroupMessageModel.COLUMN_MESSAGE_CONTENTS_TYPE + "` TINYINT ," +
                    "`" + GroupMessageModel.COLUMN_MESSAGE_FLAGS + "` INT ," +
                    "`" + GroupMessageModel.COLUMN_DELIVERED_AT + "` DATETIME ," +
                    "`" + GroupMessageModel.COLUMN_READ_AT + "` DATETIME ," +
                    "`" + GroupMessageModel.COLUMN_FORWARD_SECURITY_MODE + "` TINYINT DEFAULT 0 ," +
                    "`" + GroupMessageModel.COLUMN_GROUP_MESSAGE_STATES + "` VARCHAR ," +
                    "`" + GroupMessageModel.COLUMN_DISPLAY_TAGS + "` TINYINT DEFAULT 0 ," +
                    "`" + GroupMessageModel.COLUMN_EDITED_AT + "` DATETIME ," +
                    "`" + GroupMessageModel.COLUMN_DELETED_AT + "` DATETIME ," +
                    // F1Whisper disappearing messages (per-message frozen timer + expiry timestamps).
                    "`" + AbstractMessageModel.COLUMN_EXPIRES_AT + "` BIGINT DEFAULT NULL ," +
                    "`" + AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT + "` BIGINT DEFAULT NULL ," +
                    "`" + AbstractMessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS + "` INTEGER DEFAULT NULL ," +
                    // F1Whisper message-ordering fix (mirror DatabaseUpdateToVersion124)
                    "`" + AbstractMessageModel.COLUMN_SORT_AT + "` BIGINT DEFAULT NULL );",

                // indices
                "CREATE INDEX `group_message_uid_idx` ON `" + GroupMessageModel.TABLE + "` ( `" + GroupMessageModel.COLUMN_UID + "` )",
                "CREATE INDEX `m_group_message_outbox_idx` ON `" + GroupMessageModel.TABLE + "` ( `" + GroupMessageModel.COLUMN_OUTBOX + "` );",
                "CREATE INDEX `m_group_message_identity_idx` ON `" + GroupMessageModel.TABLE + "` ( `" + GroupMessageModel.COLUMN_IDENTITY + "` );",
                "CREATE INDEX `m_group_message_groupId_idx` ON `" + GroupMessageModel.TABLE + "` ( `" + GroupMessageModel.COLUMN_GROUP_ID + "` );",
                "CREATE INDEX `groupMessageApiMessageIdIdx` ON `" + GroupMessageModel.TABLE + "` ( `" + GroupMessageModel.COLUMN_API_MESSAGE_ID + "` );",
                "CREATE INDEX `groupMessageCorrelationIdIdx` ON `" + GroupMessageModel.TABLE + "` ( `" + GroupMessageModel.COLUMN_CORRELATION_ID + "` );",
                "CREATE INDEX `group_message_state_idx` ON `" + GroupMessageModel.TABLE
                    + "`(`" + AbstractMessageModel.COLUMN_TYPE
                    + "`, `" + AbstractMessageModel.COLUMN_STATE
                    + "`, `" + AbstractMessageModel.COLUMN_OUTBOX
                    + "`)",
                // F1Whisper disappearing messages: hot-path index for the alarm engine's
                // MIN(expiresAtUtc) / expiresAtUtc<=now sweeps.
                "CREATE INDEX `" + GroupMessageModel.TABLE + "_expiresAt_idx` ON `" + GroupMessageModel.TABLE
                    + "` ( `" + AbstractMessageModel.COLUMN_EXPIRES_AT + "` )",
            };
        }
    }
}
