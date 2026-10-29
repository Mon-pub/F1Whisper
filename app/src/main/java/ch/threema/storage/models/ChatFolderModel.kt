package ch.threema.storage.models

import java.util.Date

/**
 * A single user-defined chat folder. Folders are a purely local, client-side organization feature
 * (they are never synchronized to the server or to other devices).
 */
data class ChatFolderModel(
    val id: Long,
    val name: String,
    val position: Int,
    val createdAt: Date,
) {
    companion object {
        const val TABLE = "chat_folder"
        const val COLUMN_ID = "id"
        const val COLUMN_NAME = "name"
        const val COLUMN_POSITION = "position"
        const val COLUMN_CREATED_AT = "createdAt"

        /**
         * Sentinel id used for not-yet-persisted folders that are about to be inserted.
         */
        const val NO_ID: Long = 0L
    }
}
