package ch.threema.storage.factories;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;

import org.slf4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.app.services.GroupService;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

import ch.threema.data.datatypes.ConversationVisibility;
import ch.threema.data.datatypes.GroupIdentity;
import ch.threema.data.datatypes.GroupNotificationTriggerPolicyOverride;
import ch.threema.data.datatypes.GroupNotificationTriggerPolicyOverridePolicy;
import ch.threema.domain.models.GroupId;
import ch.threema.domain.models.UserState;
import ch.threema.storage.CursorHelper;
import ch.threema.storage.DatabaseCreationProvider;
import ch.threema.storage.DatabaseProvider;
import ch.threema.storage.DatabaseUtil;
import ch.threema.storage.QueryBuilder;
import ch.threema.storage.models.group.GroupModelOld;

public class GroupModelFactory extends ModelFactory {
    private static final Logger logger = getThreemaLogger("GroupModelFactory");

    public GroupModelFactory(DatabaseProvider databaseProvider) {
        super(databaseProvider, GroupModelOld.TABLE);
    }

    public List<GroupModelOld> getAll() {
        return convertList(
            getReadableDatabase().query(getTableName(), null, null, null, null, null, null)
        );
    }

    @Nullable
    public GroupModelOld getById(int id) {
        return getFirstOrNull(
            GroupModelOld.COLUMN_ID + "=?",
            String.valueOf(id)
        );
    }

    @NonNull
    public List<GroupModelOld> getByIds(@NonNull List<Integer> ids) {
        if (ids.isEmpty()) {
            return new ArrayList<>();
        }
        final @NonNull String placeholders = DatabaseUtil.makePlaceholders(ids.size());
        final @NonNull String[] selectionArgs = DatabaseUtil.convertArguments(ids);
        final @NonNull String selection = GroupModelOld.COLUMN_ID + " IN (" + placeholders + ")";
        return convertList(
            getReadableDatabase().query(getTableName(), null, selection, selectionArgs, null, null, null)
        );
    }

    @NonNull
    private List<GroupModelOld> convert(
        @NonNull QueryBuilder queryBuilder,
        @Nullable String[] args,
        @Nullable String orderBy
    ) {
        queryBuilder.setTables(getTableName());
        return convertList(
            queryBuilder.query(getReadableDatabase(), null, null, args, null, null, orderBy)
        );
    }

    @NonNull
    public List<GroupModelOld> convertList(@Nullable Cursor cursor) {
        final @NonNull List<GroupModelOld> results = new ArrayList<>();
        if (cursor == null) {
            return results;
        }
        try (cursor) {
            while (cursor.moveToNext()) {
                final @Nullable GroupModelOld groupModel = convert(cursor);
                if (groupModel != null) {
                    results.add(groupModel);
                }
            }
        }
        return results;
    }

    @Nullable
    private GroupModelOld convert(@Nullable Cursor cursor) {
        if (cursor == null || cursor.getPosition() < 0) {
            return null;
        }
        final @NonNull GroupModelOld groupModel = new GroupModelOld();
        //convert default
        new CursorHelper(cursor, getColumnIndexCache()).current(
            (CursorHelper.Callback) cursorHelper -> {
                Integer notificationTriggerPolicyOverridePolicy = cursorHelper.getInt(GroupModelOld.COLUMN_NOTIFICATION_TRIGGER_POLICY_OVERRIDE_POLICY);
                GroupNotificationTriggerPolicyOverride notificationTriggerPolicyOverride;
                if (notificationTriggerPolicyOverridePolicy == null) {
                    notificationTriggerPolicyOverride = null;
                } else {
                    GroupNotificationTriggerPolicyOverridePolicy policy = GroupNotificationTriggerPolicyOverridePolicy
                        .deserialize(notificationTriggerPolicyOverridePolicy);
                    if (policy == null) {
                        notificationTriggerPolicyOverride = null;
                    } else {
                        Long expiresAtLong = cursorHelper.getLong(GroupModelOld.COLUMN_NOTIFICATION_TRIGGER_POLICY_OVERRIDE_EXPIRES_AT);
                        Instant expiresAt = expiresAtLong != null ? Instant.ofEpochMilli(expiresAtLong) : null;

                        notificationTriggerPolicyOverride = new GroupNotificationTriggerPolicyOverride(
                            policy,
                            expiresAt
                        );
                    }
                }

                Integer conversationVisibilityValue = cursorHelper.getInt(GroupModelOld.COLUMN_CONVERSATION_VISIBILITY);
                if (conversationVisibilityValue == null) {
                    throw new IllegalStateException("Conversation visibility value is null");
                }
                ConversationVisibility conversationVisibility = ConversationVisibility.deserialize(conversationVisibilityValue);
                if (conversationVisibility == null) {
                    logger.error("Could not read conversation visibility with value {}", conversationVisibilityValue);
                    conversationVisibility = ConversationVisibility.NORMAL;
                }

                groupModel
                    .setId(cursorHelper.getInt(GroupModelOld.COLUMN_ID))
                    .setApiGroupId(new GroupId(cursorHelper.getString(GroupModelOld.COLUMN_API_GROUP_ID)))
                    .setName(cursorHelper.getString(GroupModelOld.COLUMN_NAME))
                    .setCreatorIdentity(cursorHelper.getString(GroupModelOld.COLUMN_CREATOR_IDENTITY))
                    .setSynchronizedAt(cursorHelper.getInstant(GroupModelOld.COLUMN_SYNCHRONIZED_AT))
                    .setCreatedAt(cursorHelper.getInstant(GroupModelOld.COLUMN_CREATED_AT))
                    .setLastUpdate(cursorHelper.getInstant(GroupModelOld.COLUMN_LAST_UPDATE))
                    .setConversationVisibility(conversationVisibility)
                    .setGroupDesc(cursorHelper.getString(GroupModelOld.COLUMN_GROUP_DESC))
                    .setGroupDescTimestamp(cursorHelper.getInstant(GroupModelOld.COLUMN_GROUP_DESC_CHANGED_TIMESTAMP))
                    .setColorIndex(cursorHelper.getInt(GroupModelOld.COLUMN_COLOR_INDEX))
                    .setUserState(UserState.getByValue(cursorHelper.getInt(GroupModelOld.COLUMN_USER_STATE)))
                    .setNotificationTriggerPolicyOverride(notificationTriggerPolicyOverride)
                    // F1Whisper: nullable per-conversation disappearing-messages timers (0/NULL = off).
                    .setDisappearingMessagesTimerSeconds(cursorHelper.getInt(GroupModelOld.COLUMN_DISAPPEARING_MESSAGES_TIMER_SECONDS))
                    .setPeerDisappearingTimerSeconds(cursorHelper.getInt(GroupModelOld.COLUMN_PEER_DISAPPEARING_TIMER_SECONDS));

                return false;
            }
        );
        return groupModel;
    }

    public boolean createOrUpdate(@NonNull GroupModelOld groupModel) {
        boolean insert = true;
        if (groupModel.getId() > 0) {
            Cursor cursor = getReadableDatabase().query(
                this.getTableName(),
                null,
                GroupModelOld.COLUMN_ID + "=?",
                new String[]{
                    String.valueOf(groupModel.getId())
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
            return create(groupModel);
        } else {
            return update(groupModel);
        }
    }

    @NonNull
    private ContentValues buildContentValues(@NonNull GroupModelOld groupModel) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(GroupModelOld.COLUMN_API_GROUP_ID, groupModel.getApiGroupId().toString());
        contentValues.put(GroupModelOld.COLUMN_CREATOR_IDENTITY, groupModel.getCreatorIdentity());
        contentValues.put(GroupModelOld.COLUMN_NAME, groupModel.getName());
        contentValues.put(GroupModelOld.COLUMN_CREATED_AT, groupModel.getCreatedAt() != null ? groupModel.getCreatedAt().toEpochMilli() : null);
        contentValues.put(GroupModelOld.COLUMN_LAST_UPDATE, groupModel.getLastUpdate() != null ? groupModel.getLastUpdate().toEpochMilli() : null);
        contentValues.put(GroupModelOld.COLUMN_SYNCHRONIZED_AT, groupModel.getSynchronizedAt() != null ? groupModel.getSynchronizedAt().toEpochMilli() : null);
        contentValues.put(GroupModelOld.COLUMN_CONVERSATION_VISIBILITY, groupModel.getConversationVisibility().getSerializedValue());
        contentValues.put(GroupModelOld.COLUMN_GROUP_DESC, groupModel.getGroupDesc());
        contentValues.put(GroupModelOld.COLUMN_GROUP_DESC_CHANGED_TIMESTAMP, groupModel.getGroupDescTimestamp() != null ? groupModel.getGroupDescTimestamp().toEpochMilli() : null);
        contentValues.put(GroupModelOld.COLUMN_COLOR_INDEX, groupModel.getIdColor().getColorIndex());
        // In case the user state is not set, we fall back to 'member'.
        contentValues.put(GroupModelOld.COLUMN_USER_STATE, groupModel.getUserState() != null ? groupModel.getUserState().getValue() : UserState.MEMBER.getValue());
        GroupNotificationTriggerPolicyOverride notificationTriggerPolicyOverride = groupModel.getNotificationTriggerPolicyOverride();
        Integer notificationTriggerPolicyOverridePolicy = notificationTriggerPolicyOverride != null
            ? notificationTriggerPolicyOverride.getPolicy().getSerializedValue()
            : null;
        Instant expiresAt = notificationTriggerPolicyOverride != null ? notificationTriggerPolicyOverride.getExpiresAt() : null;
        Long expiresAtLong = expiresAt != null ? expiresAt.toEpochMilli() : null;
        contentValues.put(GroupModelOld.COLUMN_NOTIFICATION_TRIGGER_POLICY_OVERRIDE_POLICY, notificationTriggerPolicyOverridePolicy);
        contentValues.put(GroupModelOld.COLUMN_NOTIFICATION_TRIGGER_POLICY_OVERRIDE_EXPIRES_AT, expiresAtLong);
        contentValues.put(GroupModelOld.COLUMN_DISAPPEARING_MESSAGES_TIMER_SECONDS, groupModel.getDisappearingMessagesTimerSeconds());
        contentValues.put(GroupModelOld.COLUMN_PEER_DISAPPEARING_TIMER_SECONDS, groupModel.getPeerDisappearingTimerSeconds());

        return contentValues;
    }

    public boolean create(@NonNull GroupModelOld groupModel) {
        logger.debug("create group {}", groupModel.getApiGroupId());
        ContentValues contentValues = buildContentValues(groupModel);
        try {
            long newId = getWritableDatabase().insertOrThrow(this.getTableName(), null, contentValues);
            if (newId > 0) {
                logger.debug("create group success with id {}", newId);
                groupModel.setId((int) newId);
                return true;
            }
        } catch (SQLException e) {
            logger.debug("unable to create group: {}", e.getMessage());
        }
        return false;
    }

    public boolean update(@NonNull GroupModelOld groupModel) {
        final @NonNull ContentValues contentValues = buildContentValues(groupModel);
        int rowAffected = getWritableDatabase().update(
            getTableName(),
            contentValues,
            GroupModelOld.COLUMN_ID + "=?",
            new String[]{
                String.valueOf(groupModel.getId())
            }
        );
        logger.debug("done, affected rows: {}", rowAffected);
        return true;
    }

    public void setLastUpdate(@NonNull GroupIdentity groupIdentity, @Nullable Instant lastUpdate) {
        final @Nullable Long lastUpdateTime = lastUpdate != null ? lastUpdate.toEpochMilli() : null;
        ContentValues contentValues = new ContentValues();
        contentValues.put(GroupModelOld.COLUMN_LAST_UPDATE, lastUpdateTime);

        getWritableDatabase().update(
            GroupModelOld.TABLE,
            contentValues,
            GroupModelOld.COLUMN_API_GROUP_ID + " = ? AND " + GroupModelOld.COLUMN_CREATOR_IDENTITY + " = ?",
            new String[]{
                groupIdentity.getGroupIdHexString(),
                groupIdentity.getCreatorIdentity()
            }
        );
    }

    public int delete(@NonNull GroupModelOld groupModel) {
        return getWritableDatabase().delete(
            getTableName(),
            GroupModelOld.COLUMN_ID + "=?",
            new String[]{
                String.valueOf(groupModel.getId())
            }
        );
    }

    @Nullable
    private GroupModelOld getFirstOrNull(@Nullable String selection, @Nullable String... selectionArgs) {
        final @Nullable Cursor cursor = getReadableDatabase().query(getTableName(), null, selection, selectionArgs, null, null, null);
        if (cursor == null) {
            return null;
        }
        try (cursor) {
            if (cursor.moveToFirst()) {
                return convert(cursor);
            } else {
                return null;
            }
        }
    }

    /**
     * @param filter Only {@code GroupFilter.sortAscending}, {@code GroupFilter.sortByDate} and {@code GroupFilter.sortByName} will have an effect.
     *               This means that {@code GroupFilter.includeLeftGroups} is ignored.
     */
    @NonNull
    public List<GroupModelOld> filter(@Nullable GroupService.GroupFilter filter) {
        final @NonNull QueryBuilder queryBuilder = new QueryBuilder();

        // sort by id!
        @Nullable String orderBy = null;

        if (filter != null) {
            final @NonNull String sortDirection = filter.sortAscending() ? "ASC" : "DESC";
            if (filter.sortByDate()) {
                orderBy = GroupModelOld.COLUMN_CREATED_AT + " " + sortDirection;
            } else if (filter.sortByName()) {
                orderBy = String.format("%s COLLATE NOCASE %s ", GroupModelOld.COLUMN_NAME, sortDirection);
            }
        }

        return convert(queryBuilder, new String[0], orderBy);
    }

    @Nullable
    public GroupModelOld getByApiGroupIdAndCreator(@NonNull String apiGroupId, @NonNull String groupCreator) {
        return getFirstOrNull(
            GroupModelOld.COLUMN_API_GROUP_ID + "=? AND " + GroupModelOld.COLUMN_CREATOR_IDENTITY + "=?",
            apiGroupId,
            groupCreator
        );
    }

    public static class Creator implements DatabaseCreationProvider {
        @Override
        @NonNull
        public String[] getCreationStatements() {
            return new String[]{
                "CREATE TABLE `" + GroupModelOld.TABLE + "` (" +
                    "`" + GroupModelOld.COLUMN_ID + "` INTEGER " + "PRIMARY KEY AUTOINCREMENT , " +
                    "`" + GroupModelOld.COLUMN_API_GROUP_ID + "` VARCHAR , " +
                    "`" + GroupModelOld.COLUMN_NAME + "` VARCHAR , " +
                    "`" + GroupModelOld.COLUMN_CREATOR_IDENTITY + "` VARCHAR , " +
                    "`" + GroupModelOld.COLUMN_CREATED_AT + "` BIGINT , " +
                    "`" + GroupModelOld.COLUMN_LAST_UPDATE + "` INTEGER, " +
                    "`" + GroupModelOld.COLUMN_SYNCHRONIZED_AT + "` BIGINT , " +
                    "`" + GroupModelOld.COLUMN_CONVERSATION_VISIBILITY + "` INTEGER DEFAULT 0 NOT NULL, " +
                    "`" + GroupModelOld.COLUMN_GROUP_DESC + "` VARCHAR DEFAULT NULL, " +
                    "`" + GroupModelOld.COLUMN_GROUP_DESC_CHANGED_TIMESTAMP + "` BIGINT DEFAULT NULL, " +
                    "`" + GroupModelOld.COLUMN_COLOR_INDEX + "` INTEGER DEFAULT 0 NOT NULL, " +
                    "`" + GroupModelOld.COLUMN_USER_STATE + "` INTEGER DEFAULT 0 NOT NULL, " +
                    "`" + GroupModelOld.COLUMN_NOTIFICATION_TRIGGER_POLICY_OVERRIDE_POLICY + "` INTEGER DEFAULT NULL, " +
                    "`" + GroupModelOld.COLUMN_NOTIFICATION_TRIGGER_POLICY_OVERRIDE_EXPIRES_AT + "` BIGINT DEFAULT NULL, " +
                    "`" + GroupModelOld.COLUMN_DISAPPEARING_MESSAGES_TIMER_SECONDS + "` INTEGER DEFAULT NULL, " +
                    "`" + GroupModelOld.COLUMN_PEER_DISAPPEARING_TIMER_SECONDS + "` INTEGER DEFAULT NULL " +
                    ");",
                "CREATE UNIQUE INDEX `apiGroupIdAndCreator` ON `" + GroupModelOld.TABLE + "` ( " +
                    "`" + GroupModelOld.COLUMN_API_GROUP_ID + "`, `" + GroupModelOld.COLUMN_CREATOR_IDENTITY + "` " +
                    ");"
            };
        }
    }
}
