package ch.threema.storage.factories

import android.content.ContentValues
import android.database.Cursor
import ch.threema.base.utils.getThreemaLogger
import ch.threema.storage.DatabaseCreationProvider
import ch.threema.storage.DatabaseProvider
import ch.threema.storage.models.ChatFolderMemberModel
import ch.threema.storage.models.ChatFolderModel
import java.util.Date
import net.zetetic.database.sqlcipher.SQLiteDatabase

private val logger = getThreemaLogger("ChatFolderFactory")

/**
 * Data access object for the two chat-folder tables ([ChatFolderModel.TABLE] and
 * [ChatFolderMemberModel.TABLE]). Modeled after [ConversationTagFactory].
 */
class ChatFolderFactory(databaseProvider: DatabaseProvider) :
    ModelFactory(databaseProvider, ChatFolderModel.TABLE) {

    fun getAllFolders(): List<ChatFolderModel> {
        readableDatabase.rawQuery(
            "SELECT * FROM `${ChatFolderModel.TABLE}` " +
                "ORDER BY `${ChatFolderModel.COLUMN_POSITION}` ASC, `${ChatFolderModel.COLUMN_ID}` ASC",
            null,
        ).use { cursor ->
            val folders = ArrayList<ChatFolderModel>(cursor.count)
            while (cursor.moveToNext()) {
                folders.add(convertFolder(cursor))
            }
            return folders
        }
    }

    /**
     * Inserts a new folder and returns the generated row id.
     */
    fun createFolder(name: String, position: Int): Long {
        val contentValues = ContentValues().apply {
            put(ChatFolderModel.COLUMN_NAME, name)
            put(ChatFolderModel.COLUMN_POSITION, position)
            put(ChatFolderModel.COLUMN_CREATED_AT, Date().time)
        }
        return writableDatabase.insertOrThrow(ChatFolderModel.TABLE, null, contentValues)
    }

    fun renameFolder(folderId: Long, name: String) {
        val contentValues = ContentValues().apply {
            put(ChatFolderModel.COLUMN_NAME, name)
        }
        writableDatabase.update(
            ChatFolderModel.TABLE,
            contentValues,
            "`${ChatFolderModel.COLUMN_ID}`=?",
            arrayOf(folderId.toString()),
        )
    }

    fun updateFolderPosition(folderId: Long, position: Int) {
        val contentValues = ContentValues().apply {
            put(ChatFolderModel.COLUMN_POSITION, position)
        }
        writableDatabase.update(
            ChatFolderModel.TABLE,
            contentValues,
            "`${ChatFolderModel.COLUMN_ID}`=?",
            arrayOf(folderId.toString()),
        )
    }

    /**
     * Deletes the folder. The associated members are removed via the foreign key `ON DELETE CASCADE`.
     */
    fun deleteFolder(folderId: Long) {
        writableDatabase.delete(
            ChatFolderModel.TABLE,
            "`${ChatFolderModel.COLUMN_ID}`=?",
            arrayOf(folderId.toString()),
        )
    }

    fun countFolders(): Long = count()

    fun getMemberUids(folderId: Long): List<String> {
        readableDatabase.query(
            ChatFolderMemberModel.TABLE,
            arrayOf(ChatFolderMemberModel.COLUMN_CONVERSATION_UID),
            "`${ChatFolderMemberModel.COLUMN_FOLDER_ID}`=?",
            arrayOf(folderId.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            val uids = ArrayList<String>(cursor.count)
            val columnIndex = cursor.getColumnIndexOrThrow(ChatFolderMemberModel.COLUMN_CONVERSATION_UID)
            while (cursor.moveToNext()) {
                uids.add(cursor.getString(columnIndex))
            }
            return uids
        }
    }

    fun getFolderIdsForConversation(conversationUid: String): List<Long> {
        readableDatabase.query(
            ChatFolderMemberModel.TABLE,
            arrayOf(ChatFolderMemberModel.COLUMN_FOLDER_ID),
            "`${ChatFolderMemberModel.COLUMN_CONVERSATION_UID}`=?",
            arrayOf(conversationUid),
            null,
            null,
            null,
        ).use { cursor ->
            val folderIds = ArrayList<Long>(cursor.count)
            val columnIndex = cursor.getColumnIndexOrThrow(ChatFolderMemberModel.COLUMN_FOLDER_ID)
            while (cursor.moveToNext()) {
                folderIds.add(cursor.getLong(columnIndex))
            }
            return folderIds
        }
    }

    fun addMember(folderId: Long, conversationUid: String) {
        val contentValues = ContentValues().apply {
            put(ChatFolderMemberModel.COLUMN_FOLDER_ID, folderId)
            put(ChatFolderMemberModel.COLUMN_CONVERSATION_UID, conversationUid)
            put(ChatFolderMemberModel.COLUMN_CREATED_AT, Date().time)
        }
        writableDatabase.insertWithOnConflict(
            ChatFolderMemberModel.TABLE,
            null,
            contentValues,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun removeMember(folderId: Long, conversationUid: String) {
        writableDatabase.delete(
            ChatFolderMemberModel.TABLE,
            "`${ChatFolderMemberModel.COLUMN_FOLDER_ID}`=? AND `${ChatFolderMemberModel.COLUMN_CONVERSATION_UID}`=?",
            arrayOf(folderId.toString(), conversationUid),
        )
    }

    fun isMember(folderId: Long, conversationUid: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM `${ChatFolderMemberModel.TABLE}` " +
                "WHERE `${ChatFolderMemberModel.COLUMN_FOLDER_ID}`=? " +
                "AND `${ChatFolderMemberModel.COLUMN_CONVERSATION_UID}`=? LIMIT 1",
            arrayOf(folderId.toString(), conversationUid),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    /**
     * Removes all membership rows referencing the given conversation. Used when a conversation is
     * permanently deleted.
     */
    fun deleteMembersByConversationUid(conversationUid: String) {
        writableDatabase.delete(
            ChatFolderMemberModel.TABLE,
            "`${ChatFolderMemberModel.COLUMN_CONVERSATION_UID}`=?",
            arrayOf(conversationUid),
        )
    }

    private fun convertFolder(cursor: Cursor): ChatFolderModel = ChatFolderModel(
        id = cursor.getLong(cursor.getColumnIndexOrThrow(ChatFolderModel.COLUMN_ID)),
        name = cursor.getString(cursor.getColumnIndexOrThrow(ChatFolderModel.COLUMN_NAME)),
        position = cursor.getInt(cursor.getColumnIndexOrThrow(ChatFolderModel.COLUMN_POSITION)),
        createdAt = Date(cursor.getLong(cursor.getColumnIndexOrThrow(ChatFolderModel.COLUMN_CREATED_AT))),
    )

    object Creator : DatabaseCreationProvider {
        override fun getCreationStatements() = arrayOf(
            "CREATE TABLE IF NOT EXISTS `${ChatFolderModel.TABLE}` (" +
                "`${ChatFolderModel.COLUMN_ID}` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "`${ChatFolderModel.COLUMN_NAME}` VARCHAR NOT NULL, " +
                "`${ChatFolderModel.COLUMN_POSITION}` INTEGER NOT NULL, " +
                "`${ChatFolderModel.COLUMN_CREATED_AT}` BIGINT" +
                ");",
            "CREATE TABLE IF NOT EXISTS `${ChatFolderMemberModel.TABLE}` (" +
                "`${ChatFolderMemberModel.COLUMN_FOLDER_ID}` INTEGER NOT NULL, " +
                "`${ChatFolderMemberModel.COLUMN_CONVERSATION_UID}` VARCHAR NOT NULL, " +
                "`${ChatFolderMemberModel.COLUMN_CREATED_AT}` BIGINT, " +
                "PRIMARY KEY (`${ChatFolderMemberModel.COLUMN_FOLDER_ID}`, `${ChatFolderMemberModel.COLUMN_CONVERSATION_UID}`), " +
                "FOREIGN KEY(`${ChatFolderMemberModel.COLUMN_FOLDER_ID}`) REFERENCES " +
                "`${ChatFolderModel.TABLE}`(`${ChatFolderModel.COLUMN_ID}`) ON UPDATE CASCADE ON DELETE CASCADE" +
                ");",
            "CREATE INDEX IF NOT EXISTS `chatFolderMemberConversation` ON `${ChatFolderMemberModel.TABLE}` " +
                "(`${ChatFolderMemberModel.COLUMN_CONVERSATION_UID}`);",
            "CREATE INDEX IF NOT EXISTS `chatFolderMemberFolder` ON `${ChatFolderMemberModel.TABLE}` " +
                "(`${ChatFolderMemberModel.COLUMN_FOLDER_ID}`);",
        )
    }
}
