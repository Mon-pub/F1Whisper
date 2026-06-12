package ch.threema.storage.factories;

import android.content.ContentValues;
import android.database.Cursor;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

import ch.threema.storage.CursorHelper;
import ch.threema.storage.DatabaseCreationProvider;
import ch.threema.storage.DatabaseProvider;
import ch.threema.storage.DatabaseUtil;
import ch.threema.storage.models.ScheduledMessageModel;

public class ScheduledMessageModelFactory extends ModelFactory {
    private static final Logger logger = getThreemaLogger("ScheduledMessageModelFactory");

    public ScheduledMessageModelFactory(DatabaseProvider databaseProvider) {
        super(databaseProvider, ScheduledMessageModel.TABLE);
    }

    /**
     * Insert the given model and return the new row id. The id is also written back onto the model.
     */
    public long create(@NonNull ScheduledMessageModel model) {
        ContentValues contentValues = buildContentValues(model);
        long id = getWritableDatabase().insertOrThrow(this.getTableName(), null, contentValues);
        model.setId((int) id);
        return id;
    }

    @NonNull
    public List<ScheduledMessageModel> getAllDueBefore(long nowMillis) {
        try (Cursor cursor = getReadableDatabase().query(
            this.getTableName(),
            null,
            ScheduledMessageModel.COLUMN_SCHEDULED_AT + "<=?",
            new String[]{String.valueOf(nowMillis)},
            null,
            null,
            ScheduledMessageModel.COLUMN_SCHEDULED_AT + " ASC"
        )) {
            return convertList(cursor);
        }
    }

    @Nullable
    public Long getEarliestScheduledAt() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
            "SELECT MIN(" + ScheduledMessageModel.COLUMN_SCHEDULED_AT + ") FROM " + this.getTableName(),
            null
        )) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        }
        return null;
    }

    @NonNull
    public List<ScheduledMessageModel> getByReceiver(int receiverType, @NonNull String receiverKey) {
        try (Cursor cursor = getReadableDatabase().query(
            this.getTableName(),
            null,
            ScheduledMessageModel.COLUMN_RECEIVER_TYPE + "=? AND "
                + ScheduledMessageModel.COLUMN_RECEIVER_KEY + "=?",
            new String[]{String.valueOf(receiverType), receiverKey},
            null,
            null,
            ScheduledMessageModel.COLUMN_SCHEDULED_AT + " ASC"
        )) {
            return convertList(cursor);
        }
    }

    public int countByReceiver(int receiverType, @NonNull String receiverKey) {
        return (int) DatabaseUtil.count(getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM " + this.getTableName()
                + " WHERE " + ScheduledMessageModel.COLUMN_RECEIVER_TYPE + "=? AND "
                + ScheduledMessageModel.COLUMN_RECEIVER_KEY + "=?",
            new String[]{String.valueOf(receiverType), receiverKey}
        ));
    }

    public void deleteById(int id) {
        getWritableDatabase().delete(this.getTableName(),
            ScheduledMessageModel.COLUMN_ID + "=?",
            new String[]{String.valueOf(id)});
    }

    public void deleteByReceiver(int receiverType, @NonNull String receiverKey) {
        getWritableDatabase().delete(this.getTableName(),
            ScheduledMessageModel.COLUMN_RECEIVER_TYPE + "=? AND "
                + ScheduledMessageModel.COLUMN_RECEIVER_KEY + "=?",
            new String[]{String.valueOf(receiverType), receiverKey});
    }

    private ContentValues buildContentValues(@NonNull ScheduledMessageModel model) {
        ContentValues contentValues = new ContentValues();
        // the id is auto-generated, so it is intentionally not put here for new rows
        contentValues.put(ScheduledMessageModel.COLUMN_RECEIVER_TYPE, model.getReceiverType());
        contentValues.put(ScheduledMessageModel.COLUMN_RECEIVER_KEY, model.getReceiverKey());
        contentValues.put(ScheduledMessageModel.COLUMN_BODY, model.getBody());
        contentValues.put(ScheduledMessageModel.COLUMN_SCHEDULED_AT, model.getScheduledAt());
        contentValues.put(ScheduledMessageModel.COLUMN_CREATED_AT, model.getCreatedAt());
        return contentValues;
    }

    private List<ScheduledMessageModel> convertList(Cursor cursor) {
        List<ScheduledMessageModel> result = new ArrayList<>();
        if (cursor != null) {
            while (cursor.moveToNext()) {
                result.add(convert(cursor));
            }
        }
        return result;
    }

    private ScheduledMessageModel convert(Cursor cursor) {
        if (cursor != null && cursor.getPosition() >= 0) {
            final ScheduledMessageModel c = new ScheduledMessageModel();

            new CursorHelper(cursor, getColumnIndexCache()).current(new CursorHelper.Callback() {
                @Override
                public boolean next(CursorHelper cursorHelper) {
                    Integer id = cursorHelper.getInt(ScheduledMessageModel.COLUMN_ID);
                    Integer receiverType = cursorHelper.getInt(ScheduledMessageModel.COLUMN_RECEIVER_TYPE);
                    Long scheduledAt = cursorHelper.getLong(ScheduledMessageModel.COLUMN_SCHEDULED_AT);
                    Long createdAt = cursorHelper.getLong(ScheduledMessageModel.COLUMN_CREATED_AT);
                    c
                        .setId(id != null ? id : 0)
                        .setReceiverType(receiverType != null ? receiverType : 0)
                        .setReceiverKey(cursorHelper.getString(ScheduledMessageModel.COLUMN_RECEIVER_KEY))
                        .setBody(cursorHelper.getString(ScheduledMessageModel.COLUMN_BODY))
                        .setScheduledAt(scheduledAt != null ? scheduledAt : 0L)
                        .setCreatedAt(createdAt != null ? createdAt : 0L);

                    return false;
                }
            });

            return c;
        }

        return null;
    }

    public static class Creator implements DatabaseCreationProvider {
        @Override
        @NonNull
        public String[] getCreationStatements() {
            return new String[]{
                "CREATE TABLE IF NOT EXISTS `" + ScheduledMessageModel.TABLE + "` (" +
                    "`" + ScheduledMessageModel.COLUMN_ID + "` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "`" + ScheduledMessageModel.COLUMN_RECEIVER_TYPE + "` INTEGER NOT NULL, " +
                    "`" + ScheduledMessageModel.COLUMN_RECEIVER_KEY + "` VARCHAR NOT NULL, " +
                    "`" + ScheduledMessageModel.COLUMN_BODY + "` VARCHAR NOT NULL, " +
                    "`" + ScheduledMessageModel.COLUMN_SCHEDULED_AT + "` BIGINT NOT NULL, " +
                    "`" + ScheduledMessageModel.COLUMN_CREATED_AT + "` BIGINT NOT NULL " +
                    ");",

                "CREATE INDEX IF NOT EXISTS `scheduledMessagesScheduledAt` ON `" + ScheduledMessageModel.TABLE
                    + "` ( `" + ScheduledMessageModel.COLUMN_SCHEDULED_AT + "` );",
                "CREATE INDEX IF NOT EXISTS `scheduledMessagesReceiver` ON `" + ScheduledMessageModel.TABLE
                    + "` ( `" + ScheduledMessageModel.COLUMN_RECEIVER_TYPE + "`, `" + ScheduledMessageModel.COLUMN_RECEIVER_KEY + "` );"
            };
        }
    }
}
