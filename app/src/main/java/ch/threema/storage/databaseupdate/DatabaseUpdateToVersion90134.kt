package ch.threema.storage.databaseupdate

import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * F1Whisper message-ordering fix (originally v124):
 *
 * Adds an immutable per-row sort key `sortAtUtc` so the chat timeline no longer sorts outgoing
 * messages by their mutable send-completion time (which is not monotonic with compose order and
 * reordered same-minute messages). Value = outgoing -> createdAtUtc (immutable compose time);
 * incoming -> COALESCE(postedAtUtc, createdAtUtc) (sender time; keeps the reconnect-backlog fix).
 *
 * Guarded replay: this update may run on a database where it already applied (the recorded version
 * does not have to describe the schema). Per table, the ALTER and its backfill run together and ONLY
 * when the column is missing; where the column exists, the table is left untouched. Fresh installs
 * already have the column from CREATE TABLE, so nothing runs there. Purely local; no protocol/.so change.
 */
class DatabaseUpdateToVersion90134(
    private val sqLiteDatabase: SQLiteDatabase,
) : DatabaseUpdate {
    override fun run() {
        for (statement in planMigration(sqLiteDatabase::fieldExists)) {
            sqLiteDatabase.execSQL(statement)
        }
    }

    override fun getDescription() = "add immutable sortAtUtc message sort key"

    override val version = VERSION

    companion object {
        const val VERSION = 90134
        private val TABLES = listOf("message", "m_group_message", "distribution_list_message")
        private const val COLUMN_SORT_AT = "sortAtUtc"
        private const val COLUMN_OUTBOX = "outbox"
        private const val COLUMN_CREATED_AT = "createdAtUtc"
        private const val COLUMN_POSTED_AT = "postedAtUtc"

        /**
         * Pure decision: the ordered statements this update runs, given which columns already exist.
         * [run] executes exactly this against the production database; the JVM replay tests drive the
         * same function against real SQLite fixtures.
         */
        internal fun planMigration(columnExists: (table: String, column: String) -> Boolean): List<String> =
            TABLES
                .filterNot { table -> columnExists(table, COLUMN_SORT_AT) }
                .flatMap { table ->
                    listOf(
                        "ALTER TABLE `$table` ADD COLUMN `$COLUMN_SORT_AT` BIGINT DEFAULT NULL",
                        "UPDATE `$table` SET `$COLUMN_SORT_AT` = " +
                            "CASE WHEN `$COLUMN_OUTBOX` = 1 THEN `$COLUMN_CREATED_AT` " +
                            "ELSE COALESCE(`$COLUMN_POSTED_AT`, `$COLUMN_CREATED_AT`) END",
                    )
                }
    }
}
