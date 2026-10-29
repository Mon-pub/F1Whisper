package ch.threema.storage.factories;

import android.content.ContentValues;

import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.app.messagereceiver.ContactMessageReceiver;
import ch.threema.app.messagereceiver.GroupMessageReceiver;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.services.poll.PollService;
import ch.threema.storage.CursorHelper;
import ch.threema.storage.DatabaseCreationProvider;
import ch.threema.storage.DatabaseProvider;
import ch.threema.storage.DatabaseUtil;
import ch.threema.storage.QueryBuilder;
import ch.threema.storage.models.poll.PollModel;
import ch.threema.storage.models.poll.PollVoteModel;
import ch.threema.storage.models.poll.GroupPollModel;
import ch.threema.storage.models.poll.IdentityPollModel;

import static ch.threema.common.JavaCompat.isNullOrEmpty;

public class PollModelFactory extends ModelFactory {
    public PollModelFactory(DatabaseProvider databaseProvider) {
        super(databaseProvider, PollModel.TABLE);
    }

    public List<PollModel> getAll() {
        return convertList(getReadableDatabase().query(this.getTableName(),
            null,
            null,
            null,
            null,
            null,
            null));
    }

    @Nullable
    public PollModel getById(int id) {
        return getFirst(
            PollModel.COLUMN_ID + "=?",
            new String[]{
                String.valueOf(id)
            });
    }

    public List<PollModel> convert(
        QueryBuilder queryBuilder,
        String[] args,
        String orderBy) {
        queryBuilder.setTables(this.getTableName());
        return convertList(queryBuilder.query(
            getReadableDatabase(),
            null,
            null,
            args,
            null,
            null,
            orderBy));
    }

    protected List<PollModel> convertList(Cursor c) {

        List<PollModel> result = new ArrayList<>();
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    result.add(convert(c));
                }
            } finally {
                c.close();
            }
        }
        return result;
    }

    protected PollModel convert(Cursor cursor) {
        if (cursor != null && cursor.getPosition() >= 0) {
            final PollModel c = new PollModel();

            //convert default
            new CursorHelper(cursor, this.getColumnIndexCache()).current(new CursorHelper.Callback() {
                @Override
                public boolean next(CursorHelper cursorHelper) {
                    c
                        .setId(cursorHelper.getInt(PollModel.COLUMN_ID))
                        .setApiPollId(cursorHelper.getString(PollModel.COLUMN_API_POLL_ID))
                        .setCreatorIdentity(cursorHelper.getString(PollModel.COLUMN_CREATOR_IDENTITY))
                        .setName(cursorHelper.getString(PollModel.COLUMN_NAME))
                        .setCreatedAt(cursorHelper.getInstant(PollModel.COLUMN_CREATED_AT))
                        .setModifiedAt(cursorHelper.getInstant(PollModel.COLUMN_MODIFIED_AT))
                        .setLastViewedAt(cursorHelper.getInstant(PollModel.COLUMN_LAST_VIEWED_AT));

                    String stateString = cursorHelper.getString(PollModel.COLUMN_STATE);
                    if (!isNullOrEmpty(stateString)) {
                        c.setState(PollModel.State.valueOf(stateString));
                    }
                    String assessment = cursorHelper.getString(PollModel.COLUMN_ASSESSMENT);
                    if (!isNullOrEmpty(assessment)) {
                        c.setAssessment(PollModel.Assessment.valueOf(assessment));
                    }

                    String type = cursorHelper.getString(PollModel.COLUMN_TYPE);
                    if (!isNullOrEmpty(type)) {
                        c.setType(PollModel.Type.valueOf(type));
                    }

                    String choiceType = cursorHelper.getString(PollModel.COLUMN_CHOICE_TYPE);
                    if (!isNullOrEmpty(choiceType)) {
                        c.setChoiceType(PollModel.ChoiceType.valueOf(choiceType));
                    }

                    String displayType = cursorHelper.getString(PollModel.COLUMN_DISPLAY_TYPE);
                    if (!isNullOrEmpty(displayType)) {
                        try {
                            c.setDisplayType(PollModel.DisplayType.valueOf(displayType));
                        } catch (IllegalArgumentException e) {
                            // Tolerate an unknown persisted display type name (forward-compat) instead
                            // of failing the whole poll load; fall back to the list-mode default.
                            c.setDisplayType(PollModel.DisplayType.LIST_MODE);
                        }
                    }

                    return false;
                }
            });

            return c;
        }

        return null;
    }

    public boolean createOrUpdate(PollModel pollModel) {

        boolean insert = true;
        if (pollModel.getId() > 0) {
            Cursor cursor = getReadableDatabase().query(
                this.getTableName(),
                null,
                PollModel.COLUMN_ID + "=?",
                new String[]{
                    String.valueOf(pollModel.getId())
                },
                null,
                null,
                null
            );

            if (cursor != null) {
                try {
                    insert = !cursor.moveToNext();
                } finally {
                    cursor.close();
                }
            }
        }


        if (insert) {
            return create(pollModel);
        } else {
            return update(pollModel);
        }
    }

    protected ContentValues buildContentValues(PollModel pollModel) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(PollModel.COLUMN_API_POLL_ID, pollModel.getApiPollId());
        contentValues.put(PollModel.COLUMN_CREATOR_IDENTITY, pollModel.getCreatorIdentity());
        contentValues.put(PollModel.COLUMN_NAME, pollModel.getName());
        contentValues.put(PollModel.COLUMN_STATE, pollModel.getState() != null ? pollModel.getState().toString() : null);
        contentValues.put(PollModel.COLUMN_ASSESSMENT, pollModel.getAssessment() != null ? pollModel.getAssessment().toString() : null);
        contentValues.put(PollModel.COLUMN_TYPE, pollModel.getType() != null ? pollModel.getType().toString() : null);
        contentValues.put(PollModel.COLUMN_CHOICE_TYPE, pollModel.getChoiceType() != null ? pollModel.getChoiceType().toString() : null);
        contentValues.put(PollModel.COLUMN_DISPLAY_TYPE, pollModel.getDisplayType() != null ? pollModel.getDisplayType().toString() : null);
        contentValues.put(PollModel.COLUMN_CREATED_AT, pollModel.getCreatedAt() != null ? pollModel.getCreatedAt().toEpochMilli() : null);
        contentValues.put(PollModel.COLUMN_MODIFIED_AT, pollModel.getModifiedAt() != null ? pollModel.getModifiedAt().toEpochMilli() : null);
        contentValues.put(PollModel.COLUMN_LAST_VIEWED_AT, pollModel.getLastViewedAt() != null ? pollModel.getLastViewedAt().toEpochMilli() : null);
        return contentValues;
    }

    public boolean create(PollModel pollModel) {
        ContentValues contentValues = buildContentValues(pollModel);
        long newId = getWritableDatabase().insertOrThrow(this.getTableName(), null, contentValues);
        if (newId > 0) {
            pollModel.setId((int) newId);
            return true;
        }
        return false;
    }

    public boolean update(PollModel pollModel) {
        ContentValues contentValues = buildContentValues(pollModel);
        getWritableDatabase().update(this.getTableName(),
            contentValues,
            PollModel.COLUMN_ID + "=?",
            new String[]{
                String.valueOf(pollModel.getId())
            });
        return true;
    }

    public int delete(PollModel pollModel) {
        return getWritableDatabase().delete(this.getTableName(),
            PollModel.COLUMN_ID + "=?",
            new String[]{
                String.valueOf(pollModel.getId())
            });
    }

    @Nullable
    protected PollModel getFirst(String selection, String[] selectionArgs) {
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
            try {
                if (cursor.moveToFirst()) {
                    return convert(cursor);
                }
            } finally {
                cursor.close();
            }
        }

        return null;
    }

    public long count(PollService.PollFilter filter) {
        Cursor resultCursor = this.runPollFilterQuery(filter, "SELECT COUNT(*)");

        if (resultCursor != null) {
            return DatabaseUtil.count(resultCursor);
        }

        return 0L;
    }


    public List<PollModel> filter(PollService.PollFilter filter) {
        Cursor resultCursor = this.runPollFilterQuery(filter, "SELECT DISTINCT b.*");

        if (resultCursor != null) {
            return this.convertList(resultCursor);
        }

        return new ArrayList<>();
    }

    public PollModel getByApiPollIdAndIdentity(String apiPollId, String groupCreator) {
        return getFirst(
            PollModel.COLUMN_API_POLL_ID + "=? "
                + "AND " + PollModel.COLUMN_CREATOR_IDENTITY + "=?",
            new String[]{
                apiPollId,
                groupCreator
            });
    }

    protected Cursor runPollFilterQuery(PollService.PollFilter filter, String select) {
        String query = select + " FROM " + this.getTableName() + " b";
        List<String> args = new ArrayList<>();

        if (filter != null) {

            MessageReceiver receiver = filter.getReceiver();
            if (receiver != null) {
                String linkTable;
                String linkField;
                String linkFieldReceiver;
                String linkValue;
                switch (receiver.getType()) {
                    case MessageReceiver.Type_GROUP:
                        linkTable = GroupPollModel.TABLE;
                        linkField = GroupPollModel.COLUMN_POLL_ID;
                        linkFieldReceiver = GroupPollModel.COLUMN_GROUP_ID;
                        linkValue = String.valueOf(((GroupMessageReceiver) receiver).getGroup().getId());

                        break;
                    case MessageReceiver.Type_CONTACT:
                        linkTable = IdentityPollModel.TABLE;
                        linkField = IdentityPollModel.COLUMN_POLL_ID;
                        linkFieldReceiver = IdentityPollModel.COLUMN_IDENTITY;
                        linkValue = ((ContactMessageReceiver) receiver).getContact().getIdentity();

                        break;
                    default:
                        //do not run a poll query
                        return null;
                }

                if (linkTable != null) {
                    query += " INNER JOIN " + linkTable + " l"
                        + " ON l." + linkField + " = b." + PollModel.COLUMN_ID
                        + " AND l." + linkFieldReceiver + " = ?";

                    args.add(linkValue);
                }
            }

            // Build where statement
            List<String> where = new ArrayList<>();

            if (filter.getStates() != null && filter.getStates().length > 0) {
                where.add("b." + PollModel.COLUMN_STATE + " IN (" + DatabaseUtil.makePlaceholders(filter.getStates().length) + ")");
                for (PollModel.State f : filter.getStates()) {
                    args.add(f.toString());
                }
            }

            if (filter.createdOrNotVotedByIdentity() != null) {

                // Created by the identity OR no votes from the identity
                where.add("b." + PollModel.COLUMN_CREATOR_IDENTITY + " = ? OR NOT EXISTS ("
                    + "SELECT sv." + PollVoteModel.COLUMN_POLL_ID
                    + " FROM " + PollVoteModel.TABLE + " sv"
                    + " WHERE sv." + PollVoteModel.COLUMN_VOTING_IDENTITY + " = ? AND sv." + PollVoteModel.COLUMN_POLL_ID + " = b." + PollModel.COLUMN_ID
                    + ")");
                args.add(filter.createdOrNotVotedByIdentity());
                args.add(filter.createdOrNotVotedByIdentity());
            }

            if (!where.isEmpty()) {
                String whereStatement = "";
                for (String s : where) {
                    whereStatement += (!whereStatement.isEmpty() ? ") AND (" : "");
                    whereStatement += s;
                }
                query += " WHERE (" + whereStatement + ")";
            }

            query += " ORDER BY b." + PollModel.COLUMN_CREATED_AT + " DESC";
        }

        return getReadableDatabase().rawQuery(query,
            DatabaseUtil.convertArguments(args));
    }

    public static class Creator implements DatabaseCreationProvider {
        @Override
        @NonNull
        public String [] getCreationStatements() {
            return new String[]{
                "CREATE TABLE `ballot` (`id` INTEGER PRIMARY KEY AUTOINCREMENT , `apiBallotId` VARCHAR NOT NULL , `creatorIdentity` VARCHAR NOT NULL , `name` VARCHAR , `state` VARCHAR NOT NULL , `assessment` VARCHAR NOT NULL , `type` VARCHAR NOT NULL , `choiceType` VARCHAR NOT NULL , `displayType` VARCHAR , `createdAt` BIGINT NOT NULL , `modifiedAt` BIGINT NOT NULL , `lastViewedAt` BIGINT )",

                // indices
                "CREATE UNIQUE INDEX `apiBallotIdAndCreator` ON `ballot` ( `apiBallotId`, `creatorIdentity` )"
            };
        }
    }
}
