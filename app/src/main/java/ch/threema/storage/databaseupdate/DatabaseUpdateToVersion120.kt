package ch.threema.storage.databaseupdate

import net.zetetic.database.sqlcipher.SQLiteDatabase

internal class DatabaseUpdateToVersion120(
    private val sqLiteDatabase: SQLiteDatabase,
) : DatabaseUpdate {
    override fun run() {
        sqLiteDatabase.rawExecSQL(
            """
                CREATE TABLE IF NOT EXISTS `scheduled_messages` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                    `receiverType` INTEGER NOT NULL,
                    `receiverKey` VARCHAR NOT NULL,
                    `body` VARCHAR NOT NULL,
                    `scheduledAt` BIGINT NOT NULL,
                    `createdAt` BIGINT NOT NULL
                );
            """.trimIndent(),
        )
        sqLiteDatabase.rawExecSQL(
            "CREATE INDEX IF NOT EXISTS `scheduledMessagesScheduledAt` ON `scheduled_messages` ( `scheduledAt` );",
        )
        sqLiteDatabase.rawExecSQL(
            "CREATE INDEX IF NOT EXISTS `scheduledMessagesReceiver` ON `scheduled_messages` ( `receiverType`, `receiverKey` );",
        )
    }

    override fun getDescription() = "add scheduled_messages table"

    override val version = 120
}
