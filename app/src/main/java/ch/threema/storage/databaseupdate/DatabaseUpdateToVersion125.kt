package ch.threema.storage.databaseupdate

import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * F1Whisper fix (fork review H-01): the shared message ContentValues
 * ([ch.threema.storage.factories.AbstractMessageModelFactory.buildContentValues]) writes the
 * disappearing-messages bookkeeping columns for EVERY message table, but
 * [DatabaseUpdateToVersion122] only added them to `message` and `m_group_message` — so every
 * `distribution_list_message` INSERT/UPDATE failed at runtime with "no column named expiresAtUtc".
 *
 * Adds the three per-message columns to `distribution_list_message` (same shapes as v122):
 *  - `expiresAtUtc`              (epoch-millis) hard-deletion due time, NULL until the clock starts
 *  - `expireStartedAtUtc`        (epoch-millis) when the countdown started
 *  - `disappearingTimerSeconds`  (seconds)      the timer frozen per-message at send time
 *
 * No expiry index here: the disappearing alarm engine only sweeps `message`/`m_group_message`;
 * distribution lists never arm a timer — the columns exist solely so the shared write path is valid.
 *
 * Idempotent: every ALTER is guarded by [fieldExists], so a fresh install (whose CREATE TABLE now
 * includes these columns) or a partially-applied upgrade re-runs safely.
 */
class DatabaseUpdateToVersion125(
    private val sqLiteDatabase: SQLiteDatabase,
) : DatabaseUpdate {
    override fun run() {
        val existingColumns = COLUMN_DEFINITIONS
            .map { (field, _) -> field }
            .filter { field -> sqLiteDatabase.fieldExists(DISTRIBUTION_LIST_MESSAGE_TABLE, field) }
            .toSet()
        for (statement in planMigration(existingColumns)) {
            sqLiteDatabase.execSQL(statement)
        }
    }

    override fun getDescription() = "add disappearing-messages columns to distribution_list_message"

    override val version = VERSION

    companion object {
        const val VERSION = 125

        // Self-contained per the DatabaseUpdate contract: no constants from other classes.
        // `internal` (not private) so the executable migration regression test
        // (DistributionListMessageSchemaRegressionTest) runs the EXACT same DDL strings against a
        // real database and proves v124 + this migration converges to the fresh schema.
        internal const val DISTRIBUTION_LIST_MESSAGE_TABLE = "distribution_list_message"

        internal val COLUMN_DEFINITIONS = listOf(
            "expiresAtUtc" to "BIGINT DEFAULT NULL",
            "expireStartedAtUtc" to "BIGINT DEFAULT NULL",
            "disappearingTimerSeconds" to "INTEGER DEFAULT NULL",
        )

        internal fun alterStatement(field: String, definition: String): String =
            "ALTER TABLE `$DISTRIBUTION_LIST_MESSAGE_TABLE` ADD COLUMN `$field` $definition"

        /**
         * Pure decision (third follow-up S3-07 / T3-14): given the columns that ALREADY exist on
         * `distribution_list_message`, the ordered list of ALTER statements this migration must run.
         * [run] executes exactly this against the production SQLCipher database; the executable
         * regression test drives this same function (and the resulting DDL) against a JDBC fixture,
         * covering the fresh (no-op), v124 (all three), and partially-applied (subset) upgrade paths.
         */
        internal fun planMigration(existingColumns: Set<String>): List<String> =
            COLUMN_DEFINITIONS
                .filterNot { (field, _) -> field in existingColumns }
                .map { (field, definition) -> alterStatement(field, definition) }
    }
}
