package ch.threema.storage.databaseupdate

import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * F1Whisper message-ordering fix (v124):
 *
 * Adds an immutable per-row sort key `sortAtUtc` so the chat timeline no longer sorts outgoing
 * messages by their mutable send-completion time (which is not monotonic with compose order and
 * reordered same-minute messages). Value = outgoing -> createdAtUtc (immutable compose time);
 * incoming -> COALESCE(postedAtUtc, createdAtUtc) (sender time; keeps the reconnect-backlog fix).
 *
 * Idempotent: ALTER guarded by fieldExists; backfill is a plain UPDATE safe to re-run (the formula
 * is stable for a row's whole life). Fresh installs already have the column from CREATE TABLE -> ALTER
 * skipped, backfill is a no-op on empty tables. Purely local; no protocol/.so change.
 */
class DatabaseUpdateToVersion124(
    private val sqLiteDatabase: SQLiteDatabase,
) : DatabaseUpdate {
    override fun run() {
        for (table in TABLES) {
            addSortAtColumn(table)
            backfillSortAt(table)
        }
    }

    private fun addSortAtColumn(table: String) {
        if (!sqLiteDatabase.fieldExists(table, COLUMN_SORT_AT)) {
            sqLiteDatabase.execSQL(
                "ALTER TABLE `$table` ADD COLUMN `$COLUMN_SORT_AT` BIGINT DEFAULT NULL",
            )
        }
    }

    private fun backfillSortAt(table: String) {
        sqLiteDatabase.execSQL(
            "UPDATE `$table` SET `$COLUMN_SORT_AT` = " +
                "CASE WHEN `$COLUMN_OUTBOX` = 1 THEN `$COLUMN_CREATED_AT` " +
                "ELSE COALESCE(`$COLUMN_POSTED_AT`, `$COLUMN_CREATED_AT`) END",
        )
    }

    override fun getDescription() = "add immutable sortAtUtc message sort key"

    override val version = VERSION

    companion object {
        const val VERSION = 124
        private val TABLES = listOf("message", "m_group_message", "distribution_list_message")
        private const val COLUMN_SORT_AT = "sortAtUtc"
        private const val COLUMN_OUTBOX = "outbox"
        private const val COLUMN_CREATED_AT = "createdAtUtc"
        private const val COLUMN_POSTED_AT = "postedAtUtc"
    }
}
