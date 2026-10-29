package ch.threema.storage.models

import ch.threema.data.datatypes.ConversationId
import java.time.Instant

/**
 * A membership row linking a [ChatFolderModel] to a conversation. The conversation is referenced by
 * its [ConversationId] (stored as its `toDatabaseValue()`), the exact same key that [ConversationTagModel] uses.
 */
data class ChatFolderMemberModel(
    val folderId: Long,
    val conversationId: ConversationId,
    val createdAt: Instant = Instant.now(),
) {
    companion object {
        const val TABLE = "chat_folder_member"
        const val COLUMN_FOLDER_ID = "folderId"
        const val COLUMN_CONVERSATION_UID = "conversationUid"
        const val COLUMN_CREATED_AT = "createdAt"
    }
}
