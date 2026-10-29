package ch.threema.app.services

import ch.threema.base.SessionScoped
import ch.threema.storage.models.ChatFolderModel

/**
 * Manages the local chat folders and their membership. Chat folders are a purely client-side
 * organization feature and are never synchronized to the server or to other devices.
 *
 * Every mutating operation notifies the conversation listeners so that the conversation list
 * re-filters automatically.
 */
@SessionScoped
interface ChatFolderService {

    /**
     * Returns all folders ordered by their position.
     */
    fun getFolders(): List<ChatFolderModel>

    /**
     * Creates a new folder with the given name (appended at the end) and returns its id.
     */
    fun createFolder(name: String): Long

    fun renameFolder(folderId: Long, name: String)

    fun deleteFolder(folderId: Long)

    /**
     * Persists a new ordering. [orderedFolderIds] must contain the folder ids in the desired order.
     */
    fun reorderFolders(orderedFolderIds: List<Long>)

    /**
     * Returns the conversation uids that are members of the given folder.
     */
    fun getMemberUids(folderId: Long): Set<String>

    /**
     * Returns the folder ids the given conversation is a member of.
     */
    fun getFolderIdsForConversation(conversationUid: String): Set<Long>

    /**
     * Sets the folder membership of the given conversation to exactly [folderIds] (diffing the
     * current membership and adding/removing as needed).
     */
    fun setConversationFolders(conversationUid: String, folderIds: Set<Long>)

    fun addToFolder(folderId: Long, conversationUid: String)

    fun removeFromFolder(folderId: Long, conversationUid: String)

    /**
     * Removes all membership rows referencing the given conversation. Should be called when a
     * conversation is permanently deleted.
     */
    fun onConversationDeleted(conversationUid: String)
}
