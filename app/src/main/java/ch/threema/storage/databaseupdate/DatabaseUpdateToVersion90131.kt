package ch.threema.storage.databaseupdate

import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Adds the two chat-folder tables (folders + membership) for the local chat-folder organization
 * feature. See [ch.threema.storage.factories.ChatFolderFactory].
 *
 * Guarded replay: pure `IF NOT EXISTS` DDL, so a database where this update already applied re-runs
 * it as a no-op and one missing any of the objects gets exactly the missing ones. No data is written.
 */
class DatabaseUpdateToVersion90131(
    private val sqLiteDatabase: SQLiteDatabase,
) : DatabaseUpdate {
    override fun run() {
        for (statement in STATEMENTS) {
            sqLiteDatabase.execSQL(statement)
        }
    }

    override fun getDescription() = "add chat folders"

    override val version = 90131

    companion object {
        /** The exact statements [run] executes, `internal` so the JVM replay tests drive the same strings. */
        internal val STATEMENTS = listOf(
            """
            CREATE TABLE IF NOT EXISTS `chat_folder` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `name` VARCHAR NOT NULL,
                `position` INTEGER NOT NULL,
                `createdAt` BIGINT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS `chat_folder_member` (
                `folderId` INTEGER NOT NULL,
                `conversationUid` VARCHAR NOT NULL,
                `createdAt` BIGINT,
                PRIMARY KEY (`folderId`, `conversationUid`),
                FOREIGN KEY(`folderId`) REFERENCES `chat_folder`(`id`) ON UPDATE CASCADE ON DELETE CASCADE
            )
            """,
            "CREATE INDEX IF NOT EXISTS `chatFolderMemberConversation` ON `chat_folder_member` (`conversationUid`)",
            "CREATE INDEX IF NOT EXISTS `chatFolderMemberFolder` ON `chat_folder_member` (`folderId`)",
        )
    }
}
