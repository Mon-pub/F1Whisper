package ch.threema.storage.factories;

import android.content.ContentValues;

import android.database.Cursor;

import net.zetetic.database.DatabaseUtils;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import ch.threema.storage.CursorHelper;
import ch.threema.storage.DatabaseCreationProvider;
import ch.threema.storage.DatabaseProvider;
import ch.threema.storage.DatabaseUtil;
import ch.threema.storage.QueryBuilder;
import ch.threema.storage.models.poll.PollVoteModel;

public class PollVoteModelFactory extends ModelFactory {
    public PollVoteModelFactory(DatabaseProvider databaseProvider) {
        super(databaseProvider, PollVoteModel.TABLE);
    }

    public List<PollVoteModel> getAll() {
        return convertList(getReadableDatabase().query(this.getTableName(),
            null,
            null,
            null,
            null,
            null,
            null));
    }

    public List<PollVoteModel> getByPollId(int pollId) {
        return convertList(getReadableDatabase().query(this.getTableName(),
            null,
            PollVoteModel.COLUMN_POLL_ID + "=?",
            new String[]{
                String.valueOf(pollId)
            },
            null,
            null,
            null));
    }

    public List<PollVoteModel> getByPollIdAndVotingIdentity(Integer pollId, String fromIdentity) {
        return convertList(getReadableDatabase().query(this.getTableName(),
            null,
            PollVoteModel.COLUMN_POLL_ID + "=? "
                + " AND " + PollVoteModel.COLUMN_VOTING_IDENTITY + "=?",
            new String[]{
                String.valueOf(pollId),
                fromIdentity
            },
            null,
            null,
            null));
    }

    public long countByPollIdAndVotingIdentity(Integer pollId, String fromIdentity) {
        return DatabaseUtils.longForQuery(getReadableDatabase(),
            "SELECT COUNT(*) FROM " + this.getTableName() + " "
                + "WHERE " + PollVoteModel.COLUMN_POLL_ID + "=?"
                + " AND " + PollVoteModel.COLUMN_VOTING_IDENTITY + "=?",
            new String[]{
                String.valueOf(pollId),
                String.valueOf(fromIdentity)
            });
    }

    public List<PollVoteModel> getByPollChoiceId(int pollChoiceId) {
        return convertList(getReadableDatabase().query(
            this.getTableName(),
            null,
            PollVoteModel.COLUMN_POLL_CHOICE_ID + "=?",
            new String[]{
                String.valueOf(pollChoiceId)
            },
            null, null, null
        ));
    }

    public PollVoteModel getById(int id) {
        return getFirst(
            PollVoteModel.COLUMN_ID + "=?",
            new String[]{
                String.valueOf(id)
            });
    }

    public List<PollVoteModel> convert(QueryBuilder queryBuilder,
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

    private List<PollVoteModel> convertList(Cursor c) {

        List<PollVoteModel> result = new ArrayList<>();
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

    private PollVoteModel convert(Cursor cursor) {
        if (cursor != null && cursor.getPosition() >= 0) {
            final PollVoteModel c = new PollVoteModel();

            //convert default
            new CursorHelper(cursor, getColumnIndexCache()).current(new CursorHelper.Callback() {
                @Override

                public boolean next(CursorHelper cursorHelper) {
                    c
                        .setId(cursorHelper.getInt(PollVoteModel.COLUMN_ID))
                        .setPollId(cursorHelper.getInt(PollVoteModel.COLUMN_POLL_ID))
                        .setPollChoiceId(cursorHelper.getInt(PollVoteModel.COLUMN_POLL_CHOICE_ID))
                        .setVotingIdentity(cursorHelper.getString(PollVoteModel.COLUMN_VOTING_IDENTITY))
                        .setChoice(cursorHelper.getInt(PollVoteModel.COLUMN_CHOICE))
                        .setCreatedAt(cursorHelper.getInstant(PollVoteModel.COLUMN_CREATED_AT))
                        .setModifiedAt(cursorHelper.getInstant(PollVoteModel.COLUMN_MODIFIED_AT));
                    return false;
                }
            });

            return c;
        }

        return null;
    }

    public boolean createOrUpdate(PollVoteModel pollVoteModel) {

        boolean insert = true;
        if (pollVoteModel.getId() > 0) {
            Cursor cursor = getReadableDatabase().query(
                this.getTableName(),
                null,
                PollVoteModel.COLUMN_ID + "=?",
                new String[]{
                    String.valueOf(pollVoteModel.getId())
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
            return create(pollVoteModel);
        } else {
            return update(pollVoteModel);
        }
    }

    private ContentValues buildContentValues(PollVoteModel pollVoteModel) {
        ContentValues contentValues = new ContentValues();

        contentValues.put(PollVoteModel.COLUMN_POLL_ID, pollVoteModel.getPollId());
        contentValues.put(PollVoteModel.COLUMN_POLL_CHOICE_ID, pollVoteModel.getPollChoiceId());
        contentValues.put(PollVoteModel.COLUMN_VOTING_IDENTITY, pollVoteModel.getVotingIdentity());
        contentValues.put(PollVoteModel.COLUMN_CHOICE, pollVoteModel.getChoice());
        contentValues.put(PollVoteModel.COLUMN_CREATED_AT, pollVoteModel.getCreatedAt() != null ? pollVoteModel.getCreatedAt().toEpochMilli() : null);
        contentValues.put(PollVoteModel.COLUMN_MODIFIED_AT, pollVoteModel.getModifiedAt() != null ? pollVoteModel.getModifiedAt().toEpochMilli() : null);

        return contentValues;
    }

    public boolean create(PollVoteModel pollVoteModel) {
        ContentValues contentValues = buildContentValues(pollVoteModel);
        long newId = getWritableDatabase().insertOrThrow(this.getTableName(), null, contentValues);
        if (newId > 0) {
            pollVoteModel.setId((int) newId);
            return true;
        }
        return false;
    }

    private boolean update(PollVoteModel pollVoteModel) {
        ContentValues contentValues = buildContentValues(pollVoteModel);
        getWritableDatabase().update(this.getTableName(),
            contentValues,
            PollVoteModel.COLUMN_ID + "=?",
            new String[]{
                String.valueOf(pollVoteModel.getId())
            });
        return true;
    }

    public int delete(PollVoteModel pollVoteModel) {
        return getWritableDatabase().delete(this.getTableName(),
            PollVoteModel.COLUMN_ID + "=?",
            new String[]{
                String.valueOf(pollVoteModel.getId())
            });
    }

    public int deleteByIds(int[] ids) {
        String[] params = new String[ids.length];
        for (int n = 0; n < ids.length; n++) {
            params[n] = String.valueOf(ids[n]);
        }
        return getWritableDatabase().delete(this.getTableName(),
            PollVoteModel.COLUMN_ID + " IN(" + DatabaseUtil.makePlaceholders(params.length) + ")",
            params);
    }

    public int deleteByPollId(int pollId) {
        return getWritableDatabase().delete(
            this.getTableName(),
            PollVoteModel.COLUMN_POLL_ID + "=?",
            new String[]{
                String.valueOf(pollId)
            }
        );
    }

    /**
     * Delete all votes that referenced a now-removed poll choice (used by the F1Whisper checklist
     * editor so deleting an item never leaves orphan votes pointing at a missing choice).
     */
    public int deleteByPollChoiceId(int pollChoiceId) {
        return getWritableDatabase().delete(
            this.getTableName(),
            PollVoteModel.COLUMN_POLL_CHOICE_ID + "=?",
            new String[]{
                String.valueOf(pollChoiceId)
            }
        );
    }

    public int deleteByPollIdAndVotingIdentity(int pollId, String identity) {
        return getWritableDatabase().delete(
            this.getTableName(),
            PollVoteModel.COLUMN_POLL_ID + "=? "
                + "AND " + PollVoteModel.COLUMN_VOTING_IDENTITY + "=?",
            new String[]{
                String.valueOf(pollId),
                identity
            }
        );
    }

    private PollVoteModel getFirst(String selection, String[] selectionArgs) {
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

    public long countByPollChoiceIdAndChoice(int pollChoiceId, int choice) {
        return DatabaseUtils.longForQuery(getReadableDatabase(),
            "SELECT COUNT(*) FROM " + this.getTableName() + " "
                + "WHERE " + PollVoteModel.COLUMN_POLL_CHOICE_ID + "=? "
                + "AND " + PollVoteModel.COLUMN_CHOICE + "=?",
            new String[]{
                String.valueOf(pollChoiceId),
                String.valueOf(choice)
            });
    }

    public static class Creator implements DatabaseCreationProvider {
        @Override
        @NonNull
        public String [] getCreationStatements() {
            return new String[]{
                "CREATE TABLE `ballot_vote` (`id` INTEGER PRIMARY KEY AUTOINCREMENT , `ballotId` INTEGER NOT NULL , `ballotChoiceId` INTEGER NOT NULL , `votingIdentity` VARCHAR NOT NULL , `choice` INTEGER , `createdAt` BIGINT NOT NULL , `modifiedAt` BIGINT NOT NULL );",
                // indices
                "CREATE INDEX `ballotVotingCount` ON `ballot_vote` ( `ballotChoiceId`, `choice` )",
                "CREATE UNIQUE INDEX `ballotVoteIdentity` ON `ballot_vote` ( `ballotId`, `ballotChoiceId`, `votingIdentity` );"
            };
        }
    }
}
