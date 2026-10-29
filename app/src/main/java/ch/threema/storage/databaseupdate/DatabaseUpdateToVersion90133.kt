package ch.threema.storage.databaseupdate

import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * F1Whisper disappearing messages, per-direction timer fix (originally v123):
 *
 * [DatabaseUpdateToVersion90132] added ONE per-conversation timer ([ContactModel.COLUMN_DISAPPEARING_MESSAGES_TIMER_SECONDS] /
 * the same column on [GroupModelOld]) that conflated "the timer I advertise to the peer" with "the
 * timer the peer advertises to me". This adds a SECOND column, `peerDisappearingTimerSeconds`, holding
 * the PEER's advertised value so the two directions are tracked independently:
 *  - `disappearingMessagesTimerSeconds` = my own setting (what I freeze onto outgoing messages)
 *  - `peerDisappearingTimerSeconds`     = the peer's last-advertised setting
 *
 * The peer column has since become legacy: no runtime code reads it (see
 * [ch.threema.storage.models.ContactModel.COLUMN_PEER_DISAPPEARING_TIMER_SECONDS]). It is still created
 * here so every upgraded schema matches a fresh install.
 *
 * One-time SEED: when the column is created, existing conversations that already have a local timer
 * get that value copied into it.
 *
 * Guarded replay: this update may run on a database where it already applied (the recorded version
 * does not have to describe the schema). Per table, the ALTER and its seed run together and ONLY when
 * the column is missing; where the column exists, the table is left untouched, so values written
 * since the column was created are never overwritten.
 *
 * NOTE: purely local feature (no protocol/.so change).
 */
class DatabaseUpdateToVersion90133(
    private val sqLiteDatabase: SQLiteDatabase,
) : DatabaseUpdate {
    override fun run() {
        for (statement in planMigration(sqLiteDatabase::fieldExists)) {
            sqLiteDatabase.execSQL(statement)
        }
    }

    override fun getDescription() = "add per-direction (peer) disappearing-messages timer column"

    override val version = VERSION

    companion object {
        const val VERSION = 90133

        // Table names (real names verified in the tree).
        private val TABLES = listOf("contacts", "m_group")

        // Mirror the ContactModel/GroupModelOld constants.
        private const val COLUMN_PEER_TIMER_SECONDS = "peerDisappearingTimerSeconds"

        // The local timer column (added by DatabaseUpdateToVersion90132) we seed from.
        private const val COLUMN_LOCAL_TIMER_SECONDS = "disappearingMessagesTimerSeconds"

        /**
         * Pure decision: the ordered statements this update runs, given which columns already exist.
         * [run] executes exactly this against the production database; the JVM replay tests drive the
         * same function against real SQLite fixtures.
         */
        internal fun planMigration(columnExists: (table: String, column: String) -> Boolean): List<String> =
            TABLES
                .filterNot { table -> columnExists(table, COLUMN_PEER_TIMER_SECONDS) }
                .flatMap { table ->
                    listOf(
                        "ALTER TABLE `$table` ADD COLUMN `$COLUMN_PEER_TIMER_SECONDS` INTEGER DEFAULT NULL",
                        "UPDATE `$table` SET `$COLUMN_PEER_TIMER_SECONDS` = `$COLUMN_LOCAL_TIMER_SECONDS`" +
                            " WHERE `$COLUMN_LOCAL_TIMER_SECONDS` IS NOT NULL",
                    )
                }
    }
}
