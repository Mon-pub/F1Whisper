package ch.threema.app.notifications

import ch.threema.data.datatypes.ContactConversationId
import ch.threema.data.datatypes.ConversationId
import ch.threema.data.datatypes.DistributionListConversationId
import ch.threema.data.datatypes.GroupConversationId
import ch.threema.domain.types.GroupDatabaseId
import ch.threema.domain.types.IdentityString

/**
 * Manages all the notification IDs of the app. The following rules apply:
 * - static notification IDs are always negative
 * - static notification IDs are guaranteed to be globally unique
 * - conversation notification IDs are always positive
 * - contact conversations and group conversations have mutually exclusive ranges
 * - a specific notification ID may belong to more than one conversation, but this is very unlikely
 * - conversation notification IDs are spaced apart, allowing for in-between values to be easily generated, such as for request codes
 *   for pending intents
 *
 * @see NotificationRequestCodes
 */
object NotificationIDs {
    const val APP_RESTART_NOTIFICATION_ID = -1
    const val BACKUP_COMPLETION_NOTIFICATION_ID = -2
    const val BACKUP_NOTIFICATION_ID = -3
    const val INCOMING_CALL_NOTIFICATION_ID = -4
    const val INCOMING_GROUP_CALL_NOTIFICATION_ID = -5
    const val IN_CALL_NOTIFICATION_ID = -6
    const val MASTER_KEY_LOCKED_NOTIFICATION_ID = -7
    const val NEW_MESSAGE_LOCKED_NOTIFICATION_ID = -8
    const val NEW_MESSAGE_PIN_LOCKED_NOTIFICATION_ID = -9
    const val NEW_SYNCED_CONTACTS_NOTIFICATION_ID = -10
    const val ONGOING_GROUP_CALL_NOTIFICATION_ID = -11
    const val PASSPHRASE_SERVICE_NOTIFICATION_ID = -12
    const val REMOTE_SECRET_ACTIVE_NOTIFICATION_ID = -13
    const val RESTORE_COMPLETION_NOTIFICATION_ID = -14
    const val RESTORE_NOTIFICATION_ID = -15
    const val SAFE_FAILED_NOTIFICATION_ID = -16
    const val SERVER_MESSAGE_NOTIFICATION_ID = -17
    const val THREEMA_PUSH_ACTIVE_NOTIFICATION_ID = -18
    const val UNSENT_MESSAGE_NOTIFICATION_ID = -19
    const val VOICE_ACTION_NOTIFICATION_ID = -20
    const val VOICE_MESSAGE_PLAYER_NOTIFICATION_ID = -21
    const val WEBCLIENT_ACTIVE_NOTIFICATION_ID = -22
    const val WEB_RESUME_FAILED_NOTIFICATION_ID = -23
    const val WORK_SYNC_NOTIFICATION_ID = -24

    /** F1Whisper: id for "someone reacted to your message" notifications (per-conversation via tag) */
    const val REACTION_NOTIFICATION_ID = -25

    private const val CONVERSATION_NOTIFICATION_BASE = 1000
    private const val CONVERSATION_NOTIFICATION_SPACING = NotificationRequestCodes.ConversationNotificationAction.RESERVED_RANGE
    private const val CONTACT_CONVERSATION_RANGE_SIZE = 100_000_000
    private const val GROUP_CONVERSATION_RANGE_SIZE = 100_000_000

    /**
     * @param offset May be set to get additional notification IDs for the same conversation, for secondary types of notifications
     * Must be at least 0 and less than [CONVERSATION_NOTIFICATION_SPACING]
     */
    @JvmStatic
    fun getNotificationIdForConversation(conversationId: ConversationId, offset: Int = 0): Int =
        when (conversationId) {
            is ContactConversationId -> {
                CONVERSATION_NOTIFICATION_BASE +
                    getNotificationIdForIdentity(conversationId.identity)
            }
            is GroupConversationId -> {
                CONVERSATION_NOTIFICATION_BASE +
                    CONTACT_CONVERSATION_RANGE_SIZE +
                    getNotificationIdForGroupDatabaseId(conversationId.groupDatabaseId)
            }
            is DistributionListConversationId -> throw IllegalArgumentException("Notification IDs for distribution lists are not supported")
        } * CONVERSATION_NOTIFICATION_SPACING + offset

    private fun getNotificationIdForIdentity(identity: IdentityString): Int =
        identity.fold(0L) { accumulatorValue, char ->
            (accumulatorValue * (10 + 26 + 1) + getIdentityCharValue(char)) % CONTACT_CONVERSATION_RANGE_SIZE
        }
            .toInt()

    private fun getIdentityCharValue(char: Char): Int =
        when {
            char.isDigit() -> char - '0'
            char.isLetter() -> (char - 'A') + 10
            else -> 10 + 26
        }

    private fun getNotificationIdForGroupDatabaseId(groupDatabaseId: GroupDatabaseId): Int =
        (groupDatabaseId % GROUP_CONVERSATION_RANGE_SIZE).toInt()
}
