package ch.threema.app.services.notification

import android.annotation.TargetApi
import android.net.Uri
import android.os.Build
import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.base.SessionScoped
import ch.threema.data.datatypes.ConversationId
import ch.threema.data.datatypes.LocalGroupId
import ch.threema.data.models.ContactModel
import ch.threema.data.models.ContactModelData
import ch.threema.domain.types.MessageUid
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.group.GroupModelOld

@SessionScoped
interface NotificationService {
    interface FetchCacheUri {
        fun fetch(): Uri?
    }

    @TargetApi(Build.VERSION_CODES.O)
    fun recreateNotificationChannels()

    /**
     * Set the currently visible conversation (there can only be one at any given time).
     * No notifications will be created for messages for this conversation.
     */
    fun setVisibleConversation(conversationId: ConversationId?)

    fun showGroupCallNotification(group: GroupModelOld, contactModelData: ContactModelData)

    fun showConversationNotification(conversationNotification: ConversationNotification, updateExisting: Boolean)

    fun showMasterKeyLockedNewMessageNotification()

    fun showServerMessageNotification()

    /**
     * Show a notification that a message could not be sent. Note that this is should not be used
     * for messages that were rejected because of forward security.
     *
     * @param failedMessages the failed message models
     */
    fun showUnsentMessageNotification(failedMessages: List<AbstractMessageModel>)

    /**
     * Show a forward security message rejected notification for the given receiver. Note that for
     * every receiver only one notification is shown. If a notification is already shown, this call
     * has no effect. The notification remains visible until the user cancels (or clicks) it.
     */
    fun showForwardSecurityMessageRejectedNotification(messageReceiver: MessageReceiver<*>)

    /**
     * F1Whisper: show a notification that someone reacted with an emoji to one of the user's own
     * messages. Tapping it opens the conversation scrolled to the reacted message. Respects the
     * reaction-notification preference, per-conversation mute and the currently visible chat.
     *
     * @param messageReceiver the conversation the reacted message belongs to
     * @param targetMessage the user's own message that was reacted to
     * @param reactorIdentity the identity of the contact that added the reaction
     * @param emojiSequence the reaction emoji
     */
    fun showReactionNotification(
        messageReceiver: MessageReceiver<*>,
        targetMessage: AbstractMessageModel,
        reactorIdentity: String,
        emojiSequence: String,
    )

    /**
     * Shows a notification that the safe backup has failed for the provided number of days. Note
     * that this method should only be called if safe backups are enabled and the number of days
     * that the backup has failed is at least one. Otherwise, the notification may not make sense.
     *
     * @param fullDaysSinceLastBackup the number of days on which a safe backup failed
     */
    fun showSafeBackupFailed(fullDaysSinceLastBackup: Int)

    fun cancel(conversationId: ConversationId)

    fun showNewSyncedContactsNotification(contactModels: List<ContactModel>)

    fun showWebclientResumeFailed(message: String)

    fun cancelGroupCallNotification(groupId: LocalGroupId)

    fun cancelConversationNotification(vararg messageUids: MessageUid)

    fun cancelConversationNotificationsOnLockApp()

    fun cancelServerMessageNotification()

    fun cancelAppLockedNewMessagesNotification()

    fun cancelSafeBackupFailed()

    fun cancelWorkSyncProgress()

    fun cancelRestartNotification()

    fun cancelRestoreCompletionNotification()

    fun cancelBackupCompletionNotification()
}
