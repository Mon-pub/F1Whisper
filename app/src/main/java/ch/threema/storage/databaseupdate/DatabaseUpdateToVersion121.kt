package ch.threema.storage.databaseupdate

import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Adds the two chat-folder tables (folders + membership) for the local chat-folder organization
 * feature. See [ch.threema.storage.factories.ChatFolderFactory].
 */
class DatabaseUpdateToVersion121(
    private val sqLiteDatabase: SQLiteDatabase,
) : DatabaseUpdate {
    override fun run() {
        sqLiteDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_folder` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `name` VARCHAR NOT NULL,
                `position` INTEGER NOT NULL,
                `createdAt` BIGINT
            )
            """,
        )
        sqLiteDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_folder_member` (
                `folderId` INTEGER NOT NULL,
                `conversationUid` VARCHAR NOT NULL,
                `createdAt` BIGINT,
                PRIMARY KEY (`folderId`, `conversationUid`),
                FOREIGN KEY(`folderId`) REFERENCES `chat_folder`(`id`) ON UPDATE CASCADE ON DELETE CASCADE
            )
            """,
        )
        sqLiteDatabase.execSQL(
            "CREATE INDEX IF NOT EXISTS `chatFolderMemberConversation` ON `chat_folder_member` (`conversationUid`)",
        )
        sqLiteDatabase.execSQL(
            "CREATE INDEX IF NOT EXISTS `chatFolderMemberFolder` ON `chat_folder_member` (`folderId`)",
        )
    }

    override fun getDescription() = "add chat folders"

    override val version = 121
}
