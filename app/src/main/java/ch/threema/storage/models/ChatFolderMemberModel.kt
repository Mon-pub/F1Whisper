package ch.threema.storage.models

import ch.threema.domain.types.ConversationUID
import java.util.Date

/**
 * A membership row linking a [ChatFolderModel] to a conversation. The conversation is referenced by
 * its [ConversationUID], the exact same key that [ConversationTagModel] uses.
 */
data class ChatFolderMemberModel(
    val folderId: Long,
    val conversationUid: ConversationUID,
    val createdAt: Date = Date(),
) {
    companion object {
        const val TABLE = "chat_folder_member"
        const val COLUMN_FOLDER_ID = "folderId"
        const val COLUMN_CONVERSATION_UID = "conversationUid"
        const val COLUMN_CREATED_AT = "createdAt"
    }
}
