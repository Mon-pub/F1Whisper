package ch.threema.storage.databaseupdate

import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * F1Whisper: drop the `scheduled_messages` table left behind by the removed scheduled-messaging feature.
 *
 * Scheduled messaging shipped in earlier F1Whisper releases and was later removed from the product. Rows still waiting in the
 * table are discarded together with the table, deliberately: this update does not read, send, convert, export or report them.
 * The drop stays registered permanently, so an install that skipped releases and still carries the table loses it on upgrade too.
 *
 * Pure idempotent DDL. `IF EXISTS` makes it a no-op on every install that never created the table or already dropped it, and
 * dropping a table also drops its indexes, so the two `scheduledMessages*` indexes need no separate statement.
 *
 * Originally registered as version 127; renumbered into the fork's own version sequence (see [ch.threema.storage.DatabaseUpdater]).
 */
internal class DatabaseUpdateToVersion90136(
    private val sqLiteDatabase: SQLiteDatabase,
) : DatabaseUpdate {
    override fun run() {
        sqLiteDatabase.execSQL(DROP_STATEMENT)
    }

    override fun getDescription() = "drop obsolete scheduled_messages table"

    override val version = VERSION

    companion object {
        const val VERSION = 90136

        /**
         * The single statement this update runs. `internal` (not private) so the executable regression test drives the EXACT string
         * that ships against a real SQLite database, rather than a copy of it that could drift.
         */
        internal const val DROP_STATEMENT = "DROP TABLE IF EXISTS `scheduled_messages`"
    }
}
