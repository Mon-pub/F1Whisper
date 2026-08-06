package ch.threema.storage.databaseupdate

import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * F1Whisper: drop the obsolete `scheduled_messages` table.
 *
 * Scheduled messaging was removed from the product. The feature never shipped to users, so there is nothing to migrate: any row still
 * sitting in the table is discarded with the table itself. This update is schema cleanup only and deliberately does not read, send,
 * convert, export or report the rows it removes.
 *
 * `IF EXISTS` makes it a no-op on every install that never created the table, which is every install that upgraded past the point
 * where the creating update (v120) was removed from the updater list. Dropping a table also drops its indexes, so the two
 * `scheduledMessages*` indexes need no separate statement.
 */
internal class DatabaseUpdateToVersion127(
    private val sqLiteDatabase: SQLiteDatabase,
) : DatabaseUpdate {
    override fun run() {
        sqLiteDatabase.execSQL(DROP_STATEMENT)
    }

    override fun getDescription() = "drop obsolete scheduled_messages table"

    override val version = VERSION

    companion object {
        const val VERSION = 127

        /**
         * The single statement this update runs. `internal` (not private) so the executable regression test drives the EXACT string
         * that ships against a real SQLite database, rather than a copy of it that could drift.
         */
        internal const val DROP_STATEMENT = "DROP TABLE IF EXISTS `scheduled_messages`"
    }
}
