package ch.threema.app.services

import ch.threema.app.managers.ListenerManager
import ch.threema.storage.factories.ChatFolderFactory
import ch.threema.storage.models.ChatFolderModel

/**
 * Default [ChatFolderService] implementation backed by [ChatFolderFactory]. Folders are local-only,
 * so there is no multi-device reflection (unlike pinned conversations).
 */
class ChatFolderServiceImpl(
    private val chatFolderFactory: ChatFolderFactory,
) : ChatFolderService {

    override fun getFolders(): List<ChatFolderModel> = chatFolderFactory.getAllFolders()

    override fun createFolder(name: String): Long {
        val nextPosition = (chatFolderFactory.getAllFolders().maxOfOrNull { it.position } ?: -1) + 1
        val folderId = chatFolderFactory.createFolder(name, nextPosition)
        notifyConversationsChanged()
        return folderId
    }

    override fun renameFolder(folderId: Long, name: String) {
        chatFolderFactory.renameFolder(folderId, name)
        notifyConversationsChanged()
    }

    override fun deleteFolder(folderId: Long) {
        chatFolderFactory.deleteFolder(folderId)
        notifyConversationsChanged()
    }

    override fun reorderFolders(orderedFolderIds: List<Long>) {
        orderedFolderIds.forEachIndexed { index, folderId ->
            chatFolderFactory.updateFolderPosition(folderId, index)
        }
        notifyConversationsChanged()
    }

    override fun getMemberUids(folderId: Long): Set<String> =
        chatFolderFactory.getMemberUids(folderId).toSet()

    override fun getFolderIdsForConversation(conversationUid: String): Set<Long> =
        chatFolderFactory.getFolderIdsForConversation(conversationUid).toSet()

    override fun setConversationFolders(conversationUid: String, folderIds: Set<Long>) {
        val currentFolderIds = getFolderIdsForConversation(conversationUid)
        (folderIds - currentFolderIds).forEach { folderId ->
            chatFolderFactory.addMember(folderId, conversationUid)
        }
        (currentFolderIds - folderIds).forEach { folderId ->
            chatFolderFactory.removeMember(folderId, conversationUid)
        }
        notifyConversationsChanged()
    }

    override fun addToFolder(folderId: Long, conversationUid: String) {
        chatFolderFactory.addMember(folderId, conversationUid)
        notifyConversationsChanged()
    }

    override fun removeFromFolder(folderId: Long, conversationUid: String) {
        chatFolderFactory.removeMember(folderId, conversationUid)
        notifyConversationsChanged()
    }

    override fun onConversationDeleted(conversationUid: String) {
        chatFolderFactory.deleteMembersByConversationUid(conversationUid)
    }

    private fun notifyConversationsChanged() {
        ListenerManager.conversationListeners.handle { it.onModifiedAll() }
    }
}
