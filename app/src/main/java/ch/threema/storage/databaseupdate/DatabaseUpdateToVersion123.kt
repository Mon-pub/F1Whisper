package ch.threema.storage.databaseupdate

import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * F1Whisper disappearing messages, per-direction timer fix (v123):
 *
 * v122 added ONE per-conversation timer ([ContactModel.COLUMN_DISAPPEARING_MESSAGES_TIMER_SECONDS] /
 * the same column on [GroupModelOld]) that conflated "the timer I advertise to the peer" with "the
 * timer the peer advertises to me". This adds a SECOND column, `peerDisappearingTimerSeconds`, holding
 * the PEER's advertised value so the two directions are tracked independently:
 *  - `disappearingMessagesTimerSeconds` = my own setting (what I freeze onto outgoing messages)
 *  - `peerDisappearingTimerSeconds`     = the peer's last-advertised setting (what freezes onto incoming)
 *
 * One-time SEED (transition only): existing conversations that already have a local timer set get that
 * value copied into the new peer column, so an already-configured convo isn't suddenly treated as
 * "peer has no timer" on first run after the upgrade.
 *
 * Idempotent: every ALTER is guarded by [fieldExists]; the seed is a plain UPDATE that is safe to
 * re-run (it only overwrites the peer column from the local column where the local column is set,
 * which is stable as long as the local column hasn't changed). A fresh install whose CREATE TABLE
 * already includes the column re-runs safely (ALTER is skipped, seed is a no-op on empty tables).
 *
 * NOTE: purely local feature (no protocol/.so change). The disappearing-timer control message carries
 * only the timer value; both columns are populated client-side.
 */
class DatabaseUpdateToVersion123(
    private val sqLiteDatabase: SQLiteDatabase,
) : DatabaseUpdate {
    override fun run() {
        addPeerTimerColumn(CONTACT_TABLE)
        addPeerTimerColumn(GROUP_TABLE)

        seedPeerTimerFromLocal(CONTACT_TABLE)
        seedPeerTimerFromLocal(GROUP_TABLE)
    }

    private fun addPeerTimerColumn(table: String) {
        if (!sqLiteDatabase.fieldExists(table, COLUMN_PEER_TIMER_SECONDS)) {
            sqLiteDatabase.execSQL(
                "ALTER TABLE `$table` ADD COLUMN `$COLUMN_PEER_TIMER_SECONDS` INTEGER DEFAULT NULL",
            )
        }
    }

    private fun seedPeerTimerFromLocal(table: String) {
        sqLiteDatabase.execSQL(
            "UPDATE `$table` SET `$COLUMN_PEER_TIMER_SECONDS` = `$COLUMN_LOCAL_TIMER_SECONDS`" +
                " WHERE `$COLUMN_LOCAL_TIMER_SECONDS` IS NOT NULL",
        )
    }

    override fun getDescription() = "add per-direction (peer) disappearing-messages timer column"

    override val version = VERSION

    companion object {
        const val VERSION = 123

        // Table names (real names verified in the tree).
        private const val CONTACT_TABLE = "contacts"
        private const val GROUP_TABLE = "m_group"

        // Mirror the ContactModel/GroupModelOld constants added in this batch.
        private const val COLUMN_PEER_TIMER_SECONDS = "peerDisappearingTimerSeconds"

        // The v122 local timer column we seed from.
        private const val COLUMN_LOCAL_TIMER_SECONDS = "disappearingMessagesTimerSeconds"
    }
}
