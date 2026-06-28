package ch.threema.storage.databaseupdate

import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * F1Whisper feature 9 (disappearing messages, Signal-style short-timer per-conversation):
 *
 * Adds the per-message expiry bookkeeping columns to both message tables:
 *  - `expiresAtUtc`             (epoch-millis) when this message is due for hard-deletion, NULL until the clock starts
 *  - `expireStartedAtUtc`      (epoch-millis) when the countdown started (on-send outgoing / on-read incoming)
 *  - `disappearingTimerSeconds`(seconds)      the timer FROZEN per-message at send time (Signal EXPIRES_IN parity,
 *                                             so every enforcement site is a pure timestamp compare, no conversation join)
 * plus an index on `expiresAtUtc` for the alarm engine's MIN(expiresAtUtc)/expired-before(now) sweeps.
 *
 * Also adds the per-conversation default timer to the contact + group rows
 * (`disappearingMessagesTimerSeconds`, seconds, 0/NULL = off), mirroring the per-contact
 * `typingIndicators`/`readReceipts` and per-group `notificationTriggerPolicyOverride` precedents.
 *
 * Idempotent: every ALTER is guarded by [fieldExists] and every CREATE INDEX uses IF NOT EXISTS, so a
 * partially-applied upgrade (or a fresh install whose CREATE TABLE already includes these columns) re-runs safely.
 *
 * NOTE: this is a purely local feature (no protocol/.so change). The disappearing-timer control message
 * (0x85/0x95) carries only the timer value; the columns above are populated client-side.
 */
class DatabaseUpdateToVersion122(
    private val sqLiteDatabase: SQLiteDatabase,
) : DatabaseUpdate {
    override fun run() {
        addPerMessageColumns(MESSAGE_TABLE)
        addPerMessageColumns(GROUP_MESSAGE_TABLE)

        addPerConversationTimer(CONTACT_TABLE)
        addPerConversationTimer(GROUP_TABLE)
    }

    private fun addPerMessageColumns(table: String) {
        addColumnIfMissing(table, COLUMN_EXPIRES_AT, "BIGINT DEFAULT NULL")
        addColumnIfMissing(table, COLUMN_EXPIRE_STARTED_AT, "BIGINT DEFAULT NULL")
        addColumnIfMissing(table, COLUMN_DISAPPEARING_TIMER_SECONDS, "INTEGER DEFAULT NULL")

        // Index the expiry timestamp: the alarm engine arms on MIN(expiresAtUtc) and sweeps
        // expiresAtUtc <= now across both tables, so this is the hot path.
        sqLiteDatabase.execSQL(
            "CREATE INDEX IF NOT EXISTS `${table}_expiresAt_idx` ON `$table` (`$COLUMN_EXPIRES_AT`)",
        )
    }

    private fun addPerConversationTimer(table: String) {
        addColumnIfMissing(table, COLUMN_CONVERSATION_TIMER_SECONDS, "INTEGER DEFAULT NULL")
    }

    private fun addColumnIfMissing(
        table: String,
        field: String,
        definition: String,
    ) {
        if (!sqLiteDatabase.fieldExists(table, field)) {
            sqLiteDatabase.execSQL("ALTER TABLE `$table` ADD COLUMN `$field` $definition")
        }
    }

    override fun getDescription() = "add disappearing-messages columns"

    override val version = VERSION

    companion object {
        const val VERSION = 122

        // Table names (real names verified in the tree).
        private const val MESSAGE_TABLE = "message"
        private const val GROUP_MESSAGE_TABLE = "m_group_message"
        private const val CONTACT_TABLE = "contacts"
        private const val GROUP_TABLE = "m_group"

        // Per-message columns (mirror AbstractMessageModel.COLUMN_* constants added in this batch).
        private const val COLUMN_EXPIRES_AT = "expiresAtUtc"
        private const val COLUMN_EXPIRE_STARTED_AT = "expireStartedAtUtc"
        private const val COLUMN_DISAPPEARING_TIMER_SECONDS = "disappearingTimerSeconds"

        // Per-conversation default timer column (mirror ContactModel/GroupModelOld constant).
        private const val COLUMN_CONVERSATION_TIMER_SECONDS = "disappearingMessagesTimerSeconds"
    }
}
