package ch.threema.app.services.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_MUTABLE
import android.app.PendingIntent.FLAG_NO_CREATE
import android.app.PendingIntent.FLAG_ONE_SHOT
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.preference.PreferenceManager
import ch.threema.android.buildNotification
import ch.threema.android.buildNotificationAction
import ch.threema.android.buildPerson
import ch.threema.app.AppConstants
import ch.threema.app.R
import ch.threema.app.activities.BackupAdminActivity
import ch.threema.app.activities.ComposeMessageActivity
import ch.threema.app.di.injectNullableNonBinding
import ch.threema.app.home.HomeActivity
import ch.threema.app.messagereceiver.ContactMessageReceiver
import ch.threema.app.messagereceiver.GroupMessageReceiver
import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.notifications.ForwardSecurityNotificationManager
import ch.threema.app.notifications.NotificationChannels
import ch.threema.app.notifications.NotificationGroups
import ch.threema.app.notifications.NotificationIDs
import ch.threema.app.notifications.NotificationIDs.getNotificationIdForConversation
import ch.threema.app.notifications.NotificationRequestCodes
import ch.threema.app.notifications.NotificationRequestCodes.ConversationNotificationAction
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.receivers.CancelResendMessagesBroadcastReceiver
import ch.threema.app.receivers.ReSendMessagesBroadcastReceiver
import ch.threema.app.servermessage.ServerMessageActivity
import ch.threema.app.services.ContactService
import ch.threema.app.services.ConversationCategoryService
import ch.threema.app.services.GroupService
import ch.threema.app.services.LockAppService
import ch.threema.app.services.NotificationPreferenceService
import ch.threema.app.services.RingtoneService
import ch.threema.app.ui.muteAppliesAt
import ch.threema.app.utils.ConfigUtils
import ch.threema.app.utils.DoNotDisturbUtil
import ch.threema.app.utils.NameUtil
import ch.threema.app.voip.activities.CallActivity
import ch.threema.app.voip.activities.GroupCallActivity
import ch.threema.app.voip.services.VoipCallService
import ch.threema.app.widget.WidgetUpdater
import ch.threema.base.SessionScoped
import ch.threema.base.utils.getThreemaLogger
import ch.threema.common.TimeProvider
import ch.threema.common.minus
import ch.threema.common.truncate
import ch.threema.data.IdentityProvider
import ch.threema.data.datatypes.ContactConversationId
import ch.threema.data.datatypes.ConversationId
import ch.threema.data.datatypes.ConversationIdObfuscated
import ch.threema.data.datatypes.DistributionListConversationId
import ch.threema.data.datatypes.GroupConversationId
import ch.threema.data.datatypes.GroupNotificationTriggerPolicyOverridePolicy
import ch.threema.data.datatypes.LocalGroupId
import ch.threema.data.datatypes.localGroupId
import ch.threema.data.models.ContactModel
import ch.threema.data.models.ContactModelData
import ch.threema.domain.types.MessageUid
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.conversationId
import ch.threema.storage.models.group.GroupModelOld
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import org.koin.core.component.KoinComponent

private val logger = getThreemaLogger("NotificationServiceImpl")

@SessionScoped
class NotificationServiceImpl(
    private val appContext: Context,
    private val lockAppService: LockAppService,
    private val conversationCategoryService: ConversationCategoryService,
    private val notificationPreferenceService: NotificationPreferenceService,
    private val ringtoneService: RingtoneService,
    private val preferenceService: PreferenceService,
    private val identityProvider: IdentityProvider,
    private val badgeUpdater: BadgeUpdater,
    private val doNotDisturbUtil: DoNotDisturbUtil,
    private val timeProvider: TimeProvider,
    private val widgetUpdater: WidgetUpdater,
) : NotificationService, KoinComponent {

    private val notificationManagerCompat: NotificationManagerCompat = NotificationManagerCompat.from(appContext)

    private val conversationNotificationsCache = LinkedList<ConversationNotification>()
    private var visibleConversationId: ConversationId? = null

    private val fsNotificationManager by lazy {
        ForwardSecurityNotificationManager(
            appContext,
            conversationCategoryService,
            preferenceService,
        )
    }

    private val contactService: ContactService? by injectNullableNonBinding()
    private val groupService: GroupService? by injectNullableNonBinding()

    init {
        if (ConfigUtils.supportsNotificationChannels()) {
            NotificationChannels.createOrMigrateNotificationChannels(appContext)
        }
    }

    override fun recreateNotificationChannels() {
        if (ConfigUtils.supportsNotificationChannels()) {
            NotificationChannels.recreateNotificationChannels(appContext)
        }
    }

    override fun setVisibleConversation(conversationId: ConversationId?) {
        if (conversationId != null && conversationId != visibleConversationId) {
            cancel(conversationId)
        }
        visibleConversationId = conversationId
    }

    override fun showGroupCallNotification(group: GroupModelOld, contactModelData: ContactModelData) {
        val groupService = groupService
        if (groupService == null) {
            logger.error("Group service is null; cannot show notification")
            return
        }

        if (shouldBlockGroupCallNotification(group)) {
            return
        }

        val conversationId = group.conversationId

        val publicNotification = buildNotification(appContext, channelId = NotificationChannels.NOTIFICATION_CHANNEL_INCOMING_GROUP_CALLS) {
            setContentTitle(appContext.getString(R.string.group_call))
            setContentText(appContext.getString(R.string.voip_gc_notification_new_call_public))
            setSmallIcon(R.drawable.ic_phone_locked_outline)
            setGroup(NotificationGroups.CALLS)
            setGroupSummary(false)
            setCategory(NotificationCompat.CATEGORY_CALL)
            setChannelId(NotificationChannels.NOTIFICATION_CHANNEL_INCOMING_GROUP_CALLS)
        }

        val privateNotification = buildNotification(appContext, channelId = NotificationChannels.NOTIFICATION_CHANNEL_INCOMING_GROUP_CALLS) {
            val contentText = appContext.getString(
                R.string.voip_gc_notification_call_started,
                contactModelData.getShortName(),
                group.name,
            )
            setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            setContentTitle(appContext.getString(R.string.group_call))
            setContentText(contentText)
            setSmallIcon(R.drawable.ic_phone_locked_outline)
            setLargeIcon(groupService.getAvatar(group, false))
            setLocalOnly(true)
            setGroup(NotificationGroups.CALLS)
            setGroupSummary(false)
            setCategory(NotificationCompat.CATEGORY_CALL)
            setPriority(NotificationCompat.PRIORITY_HIGH)
            setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            setPublicVersion(publicNotification)
            setTimeoutAfter(TimeUnit.SECONDS.toMillis(30))
            setContentIntent(
                createPendingIntentForConversation(
                    conversationId = conversationId,
                    requestCode = NotificationRequestCodes.getRequestCodeForConversationNotification(
                        conversationId = conversationId,
                        action = ConversationNotificationAction.OPEN,
                    ),
                ),
            )

            addAction(
                buildNotificationAction(
                    icon = R.drawable.ic_phone_locked_outline,
                    title = appContext.getString(R.string.voip_gc_join_call),
                    intent = getGroupCallJoinPendingIntent(
                        groupConversationId = conversationId,
                        // update the current pending intent (if it exists), it can be immutable, and it is sufficient if it is one shot.
                        flags = FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE or FLAG_ONE_SHOT,
                    )!!,
                ),
            )

            if (!ConfigUtils.supportsNotificationChannels()) {
                setSound(
                    notificationPreferenceService.getLegacyGroupCallRingtone(),
                    AudioManager.STREAM_RING,
                )
                if (notificationPreferenceService.isLegacyGroupCallVibrate()) {
                    setVibrate(NotificationChannels.VIBRATE_PATTERN_GROUP_CALL)
                }
            }
        }

        notify(
            notificationId = NotificationIDs.INCOMING_GROUP_CALL_NOTIFICATION_ID,
            notification = privateNotification,
            channelId = NotificationChannels.NOTIFICATION_CHANNEL_INCOMING_GROUP_CALLS,
            tag = getNotificationTagForGroup(group.localGroupId),
        )
    }

    private fun shouldBlockGroupCallNotification(group: GroupModelOld): Boolean {
        // If the notificationTriggerPolicyOverride setting is set to mention only, we will show the call notification.
        // Otherwise, we check if the mute setting would apply at the current system time.
        val notificationTriggerPolicyOverride = group.notificationTriggerPolicyOverride
        val isMuted = notificationTriggerPolicyOverride != null &&
            notificationTriggerPolicyOverride.policy != GroupNotificationTriggerPolicyOverridePolicy.MENTIONED &&
            notificationTriggerPolicyOverride.muteAppliesAt(timeProvider.get())
        return isMuted || doNotDisturbUtil.isDoNotDisturbActive()
    }

    private fun getGroupCallJoinPendingIntent(
        groupConversationId: GroupConversationId,
        flags: Int,
    ): PendingIntent? =
        PendingIntent.getActivity(
            /* context = */
            appContext,
            /* requestCode = */
            NotificationRequestCodes.getRequestCodeForConversationNotification(
                conversationId = groupConversationId,
                action = ConversationNotificationAction.GROUP_CALL_JOIN,
            ),
            /* intent = */
            GroupCallActivity.createJoinCallIntent(
                context = appContext,
                groupId = groupConversationId.groupDatabaseId.toInt(),
            ),
            /* flags = */
            flags,
        )

    override fun showConversationNotification(
        conversationNotification: ConversationNotification,
        updateExisting: Boolean,
    ) {
        if (ConfigUtils.hasInvalidCredentials()) {
            logger.debug("Credentials are not (or no longer) valid. Suppressing notification.")
            return
        }

        if (notificationPreferenceService.getWizardRunning()) {
            logger.debug("Wizard in progress. Notification suppressed.")
            return
        }

        synchronized(conversationNotificationsCache) {
            val currentNotificationsGroup = conversationNotification.group
            val conversationId = currentNotificationsGroup.conversationId
            // Check if current visible conversation is the conversation of the notifications-group
            if (conversationId == visibleConversationId) {
                logger.info("No notification - conversation visible")
                return
            }

            val conversationIdObfuscated: ConversationIdObfuscated?

            // If the conversationNotification does not already exist in local cache,
            // and the receiver is not muted, we add it to the beginning of the cached list
            val notificationAlreadyExistsInCache = conversationNotificationsCache.any { cachedConversationNotification ->
                cachedConversationNotification.uid == conversationNotification.uid
            }
            if (!notificationAlreadyExistsInCache) {
                conversationIdObfuscated = conversationId.obfuscated
                if (!doNotDisturbUtil.isMessageMuted(
                        notificationTriggerPolicyOverride = currentNotificationsGroup.messageReceiver.getNotificationTriggerPolicyOverrideOrNull(),
                        rawMessageText = conversationNotification.rawMessage,
                    )
                ) {
                    conversationNotificationsCache.addFirst(conversationNotification)
                }
            } else if (updateExisting) {
                conversationIdObfuscated = conversationId.obfuscated
            } else {
                conversationIdObfuscated = null
            }

            val uniqueNotificationGroups = mutableMapOf<String, ConversationNotificationGroup>()
            var numberOfNotificationsForCurrentConversation = 0

            val cacheIterator = conversationNotificationsCache.listIterator()
            while (cacheIterator.hasNext()) {
                val cachedConversationNotification = cacheIterator.next()

                if (conversationNotification.uid == cachedConversationNotification.uid && updateExisting) {
                    if (conversationNotification.isMessageDeleted) {
                        cacheIterator.remove()
                        continue
                    }
                    cacheIterator.set(conversationNotification)
                }

                val cachedConversationNotificationsGroup = cachedConversationNotification.group
                uniqueNotificationGroups[cachedConversationNotificationsGroup.uid] = cachedConversationNotificationsGroup

                if (cachedConversationNotificationsGroup == currentNotificationsGroup) {
                    numberOfNotificationsForCurrentConversation++
                }
            }

            if (conversationNotificationsCache.isEmpty()) {
                cancelAppLockedNewMessagesNotification()
            }

            val cacheDoesNotContainCurrentNotificationsGroup = conversationNotificationsCache.none { cachedConversationNotification ->
                cachedConversationNotification.group.uid == currentNotificationsGroup.uid
            }
            if (cacheDoesNotContainCurrentNotificationsGroup) {
                cancelConversationNotification(conversationNotification)
                badgeUpdater.showIconBadge(conversationNotificationsCache.size)
                return
            }

            if (updateExisting && lockAppService.isLocked) {
                return
            }

            val latestFullName = currentNotificationsGroup.name
            val isGroupChat = currentNotificationsGroup.messageReceiver is GroupMessageReceiver
            val defaultChannelId = if (isGroupChat) {
                NotificationChannels.NOTIFICATION_CHANNEL_GROUP_CHATS_DEFAULT
            } else {
                NotificationChannels.NOTIFICATION_CHANNEL_CHATS_DEFAULT
            }
            val channelId = conversationIdObfuscated?.value
                ?.takeIf { NotificationChannels.doesPerConversationChannelExist(appContext, it) }
                ?: defaultChannelId

            if (
                doNotDisturbUtil.isMessageMuted(
                    notificationTriggerPolicyOverride = currentNotificationsGroup.messageReceiver.getNotificationTriggerPolicyOverrideOrNull(),
                    rawMessageText = conversationNotification.rawMessage,
                )
            ) {
                return
            }

            val notificationSchema = createNotificationSchema(currentNotificationsGroup)

            if (lockAppService.isLocked) {
                showAppLockedNewMessageNotification(
                    notificationSchema = notificationSchema,
                    uid = conversationNotification.uid,
                    channelId = defaultChannelId,
                )
                return
            }

            cancelAppLockedNewMessagesNotification()

            var tickerText: CharSequence?
            var singleMessageText: CharSequence?
            val newMessagesText = getUnreadMessagesCountText(
                unreadConversationsCount = uniqueNotificationGroups.size,
                unreadMessagesCount = conversationNotificationsCache.size,
            )

            val isPrivateChat = conversationIdObfuscated != null && conversationCategoryService.isMarkedAsPrivate(conversationId)

            if (isPrivateChat) {
                tickerText = newMessagesText
                singleMessageText = newMessagesText
            } else {
                val tickerMessage: CharSequence?
                if (notificationPreferenceService.isShowMessagePreview()) {
                    tickerMessage = conversationNotification.message.truncate(MAX_TICKER_TEXT_LENGTH)
                    singleMessageText = conversationNotification.message
                } else {
                    tickerMessage = newMessagesText
                    singleMessageText = newMessagesText
                }
                tickerText = appContext.getString(
                    R.string.notification_preview_pattern,
                    latestFullName,
                    tickerMessage,
                )
            }

            val now = timeProvider.get()
            val onlyAlertOnce = conversationNotification.isMessageEdited ||
                conversationNotification.isMessageDeleted ||
                (now - currentNotificationsGroup.lastNotificationDate) < NOTIFY_AGAIN_TIMEOUT
            currentNotificationsGroup.lastNotificationDate = now

            val summaryText = appContext.resources.getQuantityString(
                R.plurals.new_messages,
                numberOfNotificationsForCurrentConversation,
                numberOfNotificationsForCurrentConversation,
            )

            if (!notificationPreferenceService.isShowMessagePreview() || isPrivateChat) {
                singleMessageText = summaryText
                tickerText = summaryText
            }

            val publicNotification = buildNotification(appContext, channelId) {
                setContentTitle(summaryText)
                setContentText(appContext.getString(R.string.notification_hidden_text))
                setSmallIcon(R.drawable.ic_notification_small)
                setOnlyAlertOnce(onlyAlertOnce)
            }

            val privateNotification = buildNotification(appContext, channelId) {
                setContentTitle(latestFullName)
                setContentText(singleMessageText)
                setTicker(tickerText)
                setSmallIcon(R.drawable.ic_notification_small)
                setLargeIcon(currentNotificationsGroup.loadAvatar())
                setGroup(currentNotificationsGroup.uid)
                setGroupSummary(false)
                setOnlyAlertOnce(onlyAlertOnce)
                setPriority(notificationPreferenceService.getLegacyNotificationPriority())
                setCategory(NotificationCompat.CATEGORY_MESSAGE)
                setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                setPublicVersion(publicNotification)

                if (!ConfigUtils.supportsNotificationChannels()) {
                    if (notificationSchema.soundUri != null) {
                        setSound(notificationSchema.soundUri, AudioManager.STREAM_NOTIFICATION)
                    }
                    if (notificationSchema.shouldVibrate) {
                        setVibrate(NotificationChannels.VIBRATE_PATTERN_REGULAR)
                    }
                    if (notificationSchema.shouldUseLight) {
                        setLights(ContextCompat.getColor(appContext, R.color.md_theme_light_primary), 2500, 2500)
                    }
                }

                // Add identity to notification for system DND priority override
                addPerson(conversationNotification.senderPerson)

                if (notificationPreferenceService.isShowMessagePreview() && !isPrivateChat) {
                    setStyle(
                        getMessagingStyle(
                            group = currentNotificationsGroup,
                            notifications = getConversationNotificationsForGroup(currentNotificationsGroup),
                        ),
                    )
                    if (conversationIdObfuscated != null) {
                        setShortcutId(conversationIdObfuscated.value)
                        setLocusId(LocusIdCompat(conversationIdObfuscated.value))
                    }

                    val messageReceiver = currentNotificationsGroup.messageReceiver

                    val replyPendingIntent = PendingIntent.getService(
                        appContext,
                        currentNotificationsGroup.getRequestCode(ConversationNotificationAction.REPLY),
                        NotificationActionService.createReplyIntent(appContext, messageReceiver),
                        // Note that this pending intent needs to be mutable because the reply text needs to be added to it.
                        FLAG_UPDATE_CURRENT or FLAG_MUTABLE,
                    )

                    val markReadPendingIntent = PendingIntent.getService(
                        appContext,
                        currentNotificationsGroup.getRequestCode(ConversationNotificationAction.MARK_AS_READ),
                        NotificationActionService.createMarkAsReadIntent(appContext, messageReceiver),
                        FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE or FLAG_ONE_SHOT,
                    )

                    val ackPendingIntent = PendingIntent.getService(
                        appContext,
                        currentNotificationsGroup.getRequestCode(ConversationNotificationAction.ACK),
                        NotificationActionService.createAckIntent(appContext, messageReceiver, conversationNotification.id),
                        FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE or FLAG_ONE_SHOT,
                    )

                    val decPendingIntent = PendingIntent.getService(
                        appContext,
                        currentNotificationsGroup.getRequestCode(ConversationNotificationAction.DEC),
                        NotificationActionService.createDecIntent(appContext, messageReceiver, conversationNotification.id),
                        FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE or FLAG_ONE_SHOT,
                    )

                    addConversationNotificationActions(
                        replyPendingIntent = replyPendingIntent,
                        ackPendingIntent = ackPendingIntent,
                        markReadPendingIntent = markReadPendingIntent,
                        conversationNotification = conversationNotification,
                        unreadMessagesCount = numberOfNotificationsForCurrentConversation,
                        newestGroup = currentNotificationsGroup,
                    )
                    addWearableExtender(
                        newestGroup = currentNotificationsGroup,
                        ackPendingIntent = ackPendingIntent,
                        decPendingIntent = decPendingIntent,
                        replyPendingIntent = replyPendingIntent,
                        markReadPendingIntent = markReadPendingIntent,
                        numberOfUnreadMessagesForThisConversation = numberOfNotificationsForCurrentConversation,
                    )
                }

                setContentIntent(
                    createPendingIntentForConversation(
                        conversationId = conversationId,
                        requestCode = currentNotificationsGroup.getRequestCode(ConversationNotificationAction.OPEN),
                    ),
                )
            }

            if (!updateExisting || notificationExists(currentNotificationsGroup.notificationId)) {
                notify(
                    notificationId = currentNotificationsGroup.notificationId,
                    notification = privateNotification,
                    schema = notificationSchema,
                    channelId = defaultChannelId,
                )

                logger.info("Showing notification, uid={}", conversationNotification.uid)
                badgeUpdater.showIconBadge(conversationNotificationsCache.size)
            }
        }
    }

    private fun createNotificationSchema(notificationGroup: ConversationNotificationGroup): NotificationSchema =
        when (val messageReceiver = notificationGroup.messageReceiver) {
            is GroupMessageReceiver -> {
                NotificationSchema(
                    shouldVibrate = notificationPreferenceService.isLegacyGroupVibrate(),
                    soundUri = ringtoneService.getGroupRingtone(messageReceiver.conversationId),
                    shouldUseLight = notificationPreferenceService.isLegacyGroupNotificationLightEnabled(),
                )
            }
            is ContactMessageReceiver -> {
                NotificationSchema(
                    shouldVibrate = notificationPreferenceService.isLegacyNotificationVibrate(),
                    soundUri = ringtoneService.getContactRingtone(messageReceiver.conversationId),
                    shouldUseLight = notificationPreferenceService.isLegacyNotificationLightEnabled(),
                )
            }
            else -> throw IllegalStateException("Unsupported messageReceiver")
        }

    private fun getUnreadMessagesCountText(unreadConversationsCount: Int, unreadMessagesCount: Int): String =
        if (unreadConversationsCount > 1) {
            appContext.resources.getQuantityString(
                R.plurals.new_messages_in_chats,
                unreadMessagesCount,
                unreadMessagesCount,
                unreadConversationsCount,
            )
        } else {
            appContext.resources.getQuantityString(
                R.plurals.new_messages,
                unreadMessagesCount,
                unreadMessagesCount,
            )
        }

    private fun getMessagingStyle(
        group: ConversationNotificationGroup,
        notifications: List<ConversationNotification>,
    ): NotificationCompat.MessagingStyle? {
        val contactService = contactService
            ?: run {
                logger.warn("Contact service is null")
                return null
            }

        val myIdentity = identityProvider.getIdentityString()
            ?: return null

        val chatName = group.name
        val isGroupChat = group.messageReceiver is GroupMessageReceiver
        val me = buildPerson {
            setName(appContext.getString(R.string.me_myself_and_i))
            setKey(ContactConversationId(myIdentity).obfuscated.value)
            contactService.getAvatar(myIdentity, false)
                ?.let { avatar ->
                    setIcon(IconCompat.createWithBitmap(avatar))
                }
        }

        val messagingStyle = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(if (isGroupChat) chatName else null)
            .setGroupConversation(isGroupChat)

        notifications.take(NotificationCompat.MessagingStyle.MAXIMUM_RETAINED_MESSAGES)
            .map { notification ->
                val messageText = notification.message
                val created = notification.createdAt?.toEpochMilli() ?: 0
                val person = if (isGroupChat) {
                    notification.senderPerson
                } else {
                    (notification.senderPerson?.toBuilder() ?: Person.Builder())
                        .setName(chatName)
                        .build()
                }
                val message = NotificationCompat.MessagingStyle.Message(messageText, created, person)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && notification.thumbnailMimeType != null) {
                    notification.getOrCreateThumbnail()
                        ?.let { thumbnailUri ->
                            message.setData(notification.thumbnailMimeType, thumbnailUri)
                        }
                }
                message
            }
            .reversed()
            .forEach { message ->
                messagingStyle.addMessage(message)
            }
        return messagingStyle
    }

    private fun getConversationNotificationsForGroup(group: ConversationNotificationGroup): List<ConversationNotification> =
        conversationNotificationsCache.filter { notification ->
            notification.group.uid == group.uid
        }

    private fun NotificationCompat.Builder.addConversationNotificationActions(
        replyPendingIntent: PendingIntent,
        ackPendingIntent: PendingIntent,
        markReadPendingIntent: PendingIntent,
        conversationNotification: ConversationNotification,
        unreadMessagesCount: Int,
        newestGroup: ConversationNotificationGroup,
    ) {
        var showMarkAsReadAction = false
        if (notificationPreferenceService.isShowMessagePreview() &&
            !conversationCategoryService.isMarkedAsPrivate(newestGroup.conversationId)
        ) {
            val replyAction = buildNotificationAction(
                icon = R.drawable.ic_reply_black_18dp,
                title = appContext.getString(R.string.wearable_reply),
                intent = replyPendingIntent,
            ) {
                addRemoteInput(
                    RemoteInput.Builder(AppConstants.EXTRA_VOICE_REPLY)
                        .setLabel(appContext.getString(R.string.compose_message_and_enter))
                        .build(),
                )
                setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                setShowsUserInterface(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowGeneratedReplies(!notificationPreferenceService.getDisableSmartReplies())
                }
            }
            addAction(replyAction)

            if (newestGroup.messageReceiver is GroupMessageReceiver) {
                if (unreadMessagesCount == 1) {
                    addAction(getThumbsUpAction(ackPendingIntent))
                }
                showMarkAsReadAction = true
            } else if (newestGroup.messageReceiver is ContactMessageReceiver) {
                if (MessageType.VOIP_STATUS == conversationNotification.messageType) {
                    val identity = newestGroup.messageReceiver.contact.identity

                    val callActivityIntent = Intent(appContext, CallActivity::class.java)
                        .putExtra(
                            VoipCallService.EXTRA_ACTIVITY_MODE,
                            CallActivity.MODE_OUTGOING_CALL,
                        )
                        .putExtra(VoipCallService.EXTRA_CONTACT_IDENTITY, identity)
                        .putExtra(VoipCallService.EXTRA_IS_INITIATOR, true)
                        .putExtra(VoipCallService.EXTRA_CALL_ID, -1L)
                    val callPendingIntent = PendingIntent.getActivity(
                        appContext,
                        NotificationRequestCodes.getRequestCodeForConversationNotification(
                            conversationId = ContactConversationId(identity),
                            action = ConversationNotificationAction.CALL,
                        ),
                        callActivityIntent,
                        // A call pending intent can be immutable and one shot.
                        FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE or FLAG_ONE_SHOT,
                    )
                    addAction(
                        buildNotificationAction(
                            R.drawable.ic_call_white_24dp,
                            appContext.getString(R.string.voip_return_call),
                            callPendingIntent,
                        ) {
                            setShowsUserInterface(true)
                            setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_CALL)
                        },
                    )
                } else {
                    if (unreadMessagesCount == 1) {
                        addAction(getThumbsUpAction(ackPendingIntent))
                    }
                    showMarkAsReadAction = true
                }
            }
        }

        val markAsReadAction = getMarkAsReadAction(markReadPendingIntent)
        if (showMarkAsReadAction) {
            addAction(markAsReadAction)
        } else {
            addInvisibleAction(markAsReadAction)
        }
    }

    private fun getMarkAsReadAction(markReadPendingIntent: PendingIntent): NotificationCompat.Action =
        buildNotificationAction(
            icon = R.drawable.ic_mark_read_bitmap,
            title = appContext.getString(
                R.string.mark_read_short,
            ),
            intent = markReadPendingIntent,
        ) {
            setShowsUserInterface(false)
            setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
        }

    private fun getThumbsUpAction(ackPendingIntent: PendingIntent): NotificationCompat.Action =
        buildNotificationAction(
            icon = R.drawable.emoji_thumbs_up,
            title = appContext.getString(R.string.acknowledge),
            intent = ackPendingIntent,
        ) {
            setShowsUserInterface(false)
            setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_THUMBS_UP)
        }

    private fun NotificationCompat.Builder.addWearableExtender(
        newestGroup: ConversationNotificationGroup,
        ackPendingIntent: PendingIntent?,
        decPendingIntent: PendingIntent?,
        replyPendingIntent: PendingIntent?,
        markReadPendingIntent: PendingIntent?,
        numberOfUnreadMessagesForThisConversation: Int,
    ) {
        val replyAction = buildNotificationAction(
            icon = R.drawable.ic_wear_full_reply,
            title = appContext.getString(R.string.wearable_reply),
            intent = replyPendingIntent,
        ) {
            addRemoteInput(
                RemoteInput.Builder(AppConstants.EXTRA_VOICE_REPLY)
                    .setLabel(appContext.getString(R.string.wearable_reply_label, newestGroup.name))
                    .setChoices(appContext.resources.getStringArray(R.array.wearable_reply_choices))
                    .build(),
            )
            setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            setShowsUserInterface(false)
            extend(
                NotificationCompat.Action.WearableExtender()
                    .setHintDisplayActionInline(true),
            )
        }

        val wearableExtender = NotificationCompat.WearableExtender()
            .addAction(replyAction)

        if (notificationPreferenceService.isShowMessagePreview() && !conversationCategoryService.isMarkedAsPrivate(newestGroup.conversationId)) {
            if (numberOfUnreadMessagesForThisConversation == 1 && newestGroup.messageReceiver is ContactMessageReceiver) {
                wearableExtender.addAction(
                    buildNotificationAction(
                        icon = R.drawable.emoji_thumbs_up,
                        title = appContext.getString(R.string.acknowledge),
                        intent = ackPendingIntent,
                    ) {
                        setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_THUMBS_UP)
                    },
                )

                wearableExtender.addAction(
                    buildNotificationAction(
                        icon = R.drawable.emoji_thumbs_down,
                        title = appContext.getString(R.string.decline),
                        intent = decPendingIntent,
                    ) {
                        setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_THUMBS_DOWN)
                    },
                )
            }

            wearableExtender.addAction(
                buildNotificationAction(
                    icon = R.drawable.ic_mark_read,
                    title = appContext.getString(R.string.mark_read),
                    intent = markReadPendingIntent,
                ) {
                    setShowsUserInterface(false)
                    setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                },
            )
        }
        extend(wearableExtender)
        extend(
            NotificationCompat.CarExtender().setLargeIcon(newestGroup.loadAvatar()),
        )
    }
    private fun notificationExists(notificationId: Int): Boolean =
        try {
            notificationManagerCompat.activeNotifications.any { notification ->
                notification.id == notificationId
            }
        } catch (e: Exception) {
            logger.warn("Failed to check if notification exists", e)
            false
        }

    private fun cancelConversationNotification(conversationNotification: ConversationNotification) {
        synchronized(conversationNotificationsCache) {
            logger.info("Canceling notification {}", conversationNotification.uid)
            cancel(conversationNotification.group.notificationId)
            conversationNotification.group.conversations.remove(conversationNotification)
        }
    }

    private fun cancelAllCachedConversationNotifications() {
        synchronized(this.conversationNotificationsCache) {
            if (!conversationNotificationsCache.isEmpty()) {
                for (conversationNotification in conversationNotificationsCache) {
                    cancelConversationNotification(conversationNotification)
                }
                conversationNotificationsCache.clear()
            }
        }
    }

    /**
     * cancel all conversation notifications of category Notification.CATEGORY_MESSAGE
     * returns true if any have been canceled
     */
    private fun cancelAllMessageCategoryNotifications(): Boolean {
        var cancelledIDs = false
        try {
            notificationManagerCompat.activeNotifications.forEach { notification ->
                if (notification.notification != null && notification.notification.category == Notification.CATEGORY_MESSAGE) {
                    cancel(notificationId = notification.id)
                    cancelledIDs = true
                }
            }
        } catch (e: Exception) {
            logger.error("Could not cancel notifications of CATEGORY_MESSAGE ", e)
        }
        return cancelledIDs
    }

    private fun showAppLockedNewMessageNotification(
        notificationSchema: NotificationSchema? = null,
        uid: String? = null,
        channelId: String,
    ) {
        val notification = buildNotification(appContext, channelId) {
            setSmallIcon(R.drawable.ic_notification_small)
            setContentTitle(appContext.getString(R.string.new_messages_locked))
            setContentText(appContext.getString(R.string.new_messages_locked_description))
            setTicker(appContext.getString(R.string.new_messages_locked))
            setCategory(NotificationCompat.CATEGORY_MESSAGE)
            setPriority(notificationPreferenceService.getLegacyNotificationPriority())
            setOnlyAlertOnce(false)
            setContentIntent(createPendingIntentForHomeActivity())
            setSound(notificationSchema?.soundUri, AudioManager.STREAM_NOTIFICATION)

            if (notificationSchema?.shouldVibrate == true) {
                setVibrate(NotificationChannels.VIBRATE_PATTERN_REGULAR)
            }
        }

        notify(
            notificationId = NotificationIDs.NEW_MESSAGE_PIN_LOCKED_NOTIFICATION_ID,
            notification = notification,
            channelId = channelId,
        )

        badgeUpdater.showIconBadge(0)

        logger.info(
            "Showing generic notification (app locked), uid = {}, channelId = {} ",
            uid,
            channelId,
        )
    }

    private fun createPendingIntentForHomeActivity(): PendingIntent =
        createPendingIntentWithTaskStack(
            intent = HomeActivity.createIntent(appContext)
                .setFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                ),
            requestCode = NotificationRequestCodes.HOME_ACTIVITY,
        )

    private fun createPendingIntentForConversation(conversationId: ConversationId, requestCode: Int): PendingIntent {
        val intent = ComposeMessageActivity.createIntent(appContext, conversationId)
            .setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION,
            )
        return createPendingIntentWithTaskStack(intent, requestCode)
    }

    override fun showMasterKeyLockedNewMessageNotification() {
        val notification = buildNotification(appContext, NotificationChannels.NOTIFICATION_CHANNEL_CHATS_DEFAULT) {
            setSmallIcon(R.drawable.ic_notification_small)
            setContentTitle(appContext.getString(R.string.new_messages_locked))
            setContentText(appContext.getString(R.string.new_messages_locked_description))
            setTicker(appContext.getString(R.string.new_messages_locked))
            setCategory(NotificationCompat.CATEGORY_MESSAGE)
            setOnlyAlertOnce(false)
            setContentIntent(createPendingIntentForHomeActivity())
            setSound(notificationPreferenceService.getLegacyNotificationSound(), AudioManager.STREAM_NOTIFICATION)

            if (notificationPreferenceService.isLegacyNotificationVibrate()) {
                setVibrate(NotificationChannels.VIBRATE_PATTERN_REGULAR)
            }
        }

        notify(
            notificationId = NotificationIDs.NEW_MESSAGE_LOCKED_NOTIFICATION_ID,
            notification = notification,
            channelId = NotificationChannels.NOTIFICATION_CHANNEL_CHATS_DEFAULT,
        )

        logger.info("Showing generic notification (master key locked)")
    }

    override fun showServerMessageNotification() {
        if (doNotDisturbUtil.isDoNotDisturbActive()) {
            return
        }

        val notification = buildNotification(appContext, NotificationChannels.NOTIFICATION_CHANNEL_NOTICE) {
            setSmallIcon(R.drawable.ic_error_red_24dp)
            setTicker(appContext.getString(R.string.server_message_title))
            setContentTitle(appContext.getString(R.string.server_message_title))
            setContentText(appContext.getString(R.string.tap_here_for_more))
            setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    NotificationRequestCodes.SERVER_MESSAGE,
                    ServerMessageActivity.createIntent(appContext),
                    FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT,
                ),
            )
            setLocalOnly(true)
            setPriority(NotificationCompat.PRIORITY_MAX)
            setAutoCancel(true)
        }
        notify(
            notificationId = NotificationIDs.SERVER_MESSAGE_NOTIFICATION_ID,
            notification = notification,
            channelId = NotificationChannels.NOTIFICATION_CHANNEL_NOTICE,
        )
    }

    private fun createPendingIntentWithTaskStack(intent: Intent, requestCode: Int): PendingIntent =
        TaskStackBuilder.create(appContext)
            .addNextIntentWithParentStack(intent)
            .getPendingIntent(requestCode, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE or FLAG_ONE_SHOT)!!

    override fun showUnsentMessageNotification(failedMessages: List<AbstractMessageModel>) {
        val numberOfFailedMessages = failedMessages.size

        if (numberOfFailedMessages == 0) {
            cancel(NotificationIDs.UNSENT_MESSAGE_NOTIFICATION_ID)
            return
        }

        val sendPendingIntent = PendingIntent.getBroadcast(
            appContext,
            NotificationRequestCodes.UNSENT_NOTIFICATIONS_SEND,
            ReSendMessagesBroadcastReceiver.createIntent(appContext, failedMessages),
            FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE or FLAG_ONE_SHOT,
        )

        val tryAgainAction = buildNotificationAction(
            icon = R.drawable.ic_wear_full_retry,
            title = appContext.getString(R.string.try_again),
            intent = sendPendingIntent,
        )
        val wearableExtender = NotificationCompat.WearableExtender()
            .addAction(tryAgainAction)

        val cancelIntent = CancelResendMessagesBroadcastReceiver.createIntent(appContext, failedMessages)

        val content = appContext.getResources()
            .getQuantityString(R.plurals.sending_message_failed, numberOfFailedMessages, numberOfFailedMessages)
        val notification = buildNotification(appContext, NotificationChannels.NOTIFICATION_CHANNEL_ALERT) {
            setSmallIcon(R.drawable.ic_error_red_24dp)
            setTicker(content)
            setPriority(NotificationCompat.PRIORITY_HIGH)
            setCategory(NotificationCompat.CATEGORY_ERROR)
            setContentIntent(createPendingIntentForHomeActivity())
            extend(wearableExtender)
            setContentTitle(appContext.getString(R.string.app_name))
            setContentText(content)
            setStyle(NotificationCompat.BigTextStyle().bigText(content))
            setDeleteIntent(
                PendingIntent.getBroadcast(
                    appContext,
                    NotificationRequestCodes.UNSENT_NOTIFICATIONS_CANCEL,
                    cancelIntent,
                    FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE or FLAG_ONE_SHOT,
                ),
            )
            addAction(
                R.drawable.ic_refresh_white_24dp,
                appContext.getString(R.string.try_again),
                sendPendingIntent,
            )
        }

        notify(
            notificationId = NotificationIDs.UNSENT_MESSAGE_NOTIFICATION_ID,
            notification = notification,
            channelId = NotificationChannels.NOTIFICATION_CHANNEL_ALERT,
        )
    }

    override fun showForwardSecurityMessageRejectedNotification(messageReceiver: MessageReceiver<*>) {
        fsNotificationManager.showForwardSecurityNotification(messageReceiver)
    }

    override fun showReactionNotification(
        messageReceiver: MessageReceiver<*>,
        targetMessage: AbstractMessageModel,
        reactorIdentity: String,
        emojiSequence: String,
    ) {
        // global toggle (default on)
        val reactionNotificationsEnabled = PreferenceManager.getDefaultSharedPreferences(appContext)
            .getBoolean(appContext.getString(R.string.preferences__reaction_notifications), true)
        if (!reactionNotificationsEnabled) {
            return
        }

        // respect per-conversation mute (do-not-disturb)
        val notificationTriggerPolicyOverride = messageReceiver.getNotificationTriggerPolicyOverrideOrNull()
        if (notificationTriggerPolicyOverride != null && notificationTriggerPolicyOverride.muteAppliesAt(timeProvider.get())) {
            return
        }

        // do not notify for the conversation that is currently on screen
        val conversationId = messageReceiver.conversationId
        if (conversationId == visibleConversationId) {
            return
        }

        // do not reveal content while the app is locked
        if (lockAppService.isLocked) {
            return
        }

        // OrNickname: a contact with only a nickname (no first/last name) must show the nickname, not fall back to the raw Threema ID
        val reactorName = contactService?.getByIdentity(reactorIdentity)
            ?.let { reactor ->
                NameUtil.getContactDisplayNameOrNickname(reactor, true, preferenceService.getContactNameFormat())
            }
            ?: reactorIdentity
        val title = messageReceiver.getDisplayName(preferenceService.getContactNameFormat())
        val content = appContext.getString(R.string.reaction_notification_text, reactorName, emojiSequence)

        // Namespaces the notification per conversation, so that reactions in different chats don't overwrite each other
        val notificationTag = "reaction:$conversationId"

        try {
            // The data uri keeps the pending intents of different conversations apart, as only extras differ between them
            val intent = ComposeMessageActivity.createIntentJumpToMessage(appContext, targetMessage)
                .setData(Uri.parse("reaction://${Uri.encode(conversationId.toString())}"))
                .setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION,
                )
            val notification = buildNotification(appContext, NotificationChannels.NOTIFICATION_CHANNEL_EMOJI_REACTIONS) {
                setSmallIcon(R.drawable.ic_notification_small)
                setContentTitle(title)
                setContentText(content)
                setContentIntent(createPendingIntentWithTaskStack(intent, NotificationIDs.REACTION_NOTIFICATION_ID))
                setStyle(NotificationCompat.BigTextStyle().bigText(content))
                setPriority(NotificationCompat.PRIORITY_DEFAULT)
                setCategory(NotificationCompat.CATEGORY_SOCIAL)
                setAutoCancel(true)
            }
            notify(
                notificationId = NotificationIDs.REACTION_NOTIFICATION_ID,
                notification = notification,
                channelId = NotificationChannels.NOTIFICATION_CHANNEL_EMOJI_REACTIONS,
                tag = notificationTag,
            )
        } catch (e: Exception) {
            logger.error("Failed to show reaction notification", e)
        }
    }

    override fun showSafeBackupFailed(fullDaysSinceLastBackup: Int) {
        val notification = buildNotification(appContext, NotificationChannels.NOTIFICATION_CHANNEL_ALERT) {
            val content = appContext.getString(R.string.safe_failed_notification, fullDaysSinceLastBackup)

            setSmallIcon(R.drawable.ic_error_red_24dp)
            setTicker(content)
            setLocalOnly(true)
            setPriority(NotificationCompat.PRIORITY_HIGH)
            setCategory(NotificationCompat.CATEGORY_ERROR)
            setContentTitle(appContext.getString(R.string.app_name))
            setContentText(content)
            setStyle(NotificationCompat.BigTextStyle().bigText(content))

            setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    NotificationRequestCodes.SAFE_BACKUP_FAILED,
                    BackupAdminActivity.createIntent(appContext),
                    FLAG_IMMUTABLE,
                ),
            )
        }
        notify(
            notificationId = NotificationIDs.SAFE_FAILED_NOTIFICATION_ID,
            notification = notification,
            channelId = NotificationChannels.NOTIFICATION_CHANNEL_ALERT,
        )
    }

    override fun showNewSyncedContactsNotification(contactModels: List<ContactModel>) {
        if (contactModels.isEmpty()) {
            return
        }

        val message: String
        val openPendingIntent: PendingIntent

        val contactModel = contactModels.singleOrNull()
        val contactNameFormat = preferenceService.getContactNameFormat()
        if (contactModel == null) {
            val contactListString = contactModels.joinToString(separator = ", ") { contactModel ->
                NameUtil.getContactDisplayName(contactModel, contactNameFormat)
            }
            message = appContext.getString(
                R.string.notification_contact_has_joined_multiple,
                contactModels.size,
                contactListString,
            )
            openPendingIntent = createPendingIntentWithTaskStack(
                intent = HomeActivity.createIntent(appContext, showContacts = true)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                requestCode = NotificationRequestCodes.NEW_CONTACTS_SYNCED,
            )
        } else {
            val name = NameUtil.getContactDisplayName(contactModel, contactNameFormat)
            message = appContext.getString(R.string.notification_contact_has_joined, name)
            openPendingIntent = createPendingIntentForConversation(
                conversationId = ContactConversationId(contactModel.identity),
                requestCode = NotificationRequestCodes.NEW_CONTACTS_SYNCED,
            )
        }

        val notification = buildNotification(appContext, NotificationChannels.NOTIFICATION_CHANNEL_NEW_SYNCED_CONTACTS) {
            setSmallIcon(R.drawable.ic_notification_small)
            setContentTitle(appContext.getString(R.string.notification_channel_new_contact))
            setContentText(message)
            setContentIntent(openPendingIntent)
            setStyle(NotificationCompat.BigTextStyle().bigText(message))
            setPriority(NotificationCompat.PRIORITY_HIGH)
            setAutoCancel(true)

            if (notificationPreferenceService.isLegacyNotificationVibrate()) {
                setVibrate(NotificationChannels.VIBRATE_PATTERN_REGULAR)
            }
        }
        notify(
            notificationId = NotificationIDs.NEW_SYNCED_CONTACTS_NOTIFICATION_ID,
            notification = notification,
            channelId = NotificationChannels.NOTIFICATION_CHANNEL_NEW_SYNCED_CONTACTS,
        )
    }

    override fun showWebclientResumeFailed(message: String) {
        val notification = buildNotification(appContext, NotificationChannels.NOTIFICATION_CHANNEL_NOTICE) {
            setSmallIcon(R.drawable.ic_web_notification)
            setTicker(message)
            setLocalOnly(true)
            setPriority(NotificationCompat.PRIORITY_HIGH)
            setCategory(NotificationCompat.CATEGORY_ERROR)
            setContentTitle(appContext.getString(R.string.app_name))
            setContentText(message)
            setStyle(NotificationCompat.BigTextStyle().bigText(message))
        }
        notify(
            notificationId = NotificationIDs.WEB_RESUME_FAILED_NOTIFICATION_ID,
            notification = notification,
            channelId = NotificationChannels.NOTIFICATION_CHANNEL_NOTICE,
        )
    }

    private fun notify(
        notificationId: Int,
        notification: Notification,
        schema: NotificationSchema? = null,
        channelId: String,
        tag: String? = null,
    ) {
        try {
            notificationManagerCompat.notify(tag, notificationId, notification)
        } catch (e: SecurityException) {
            // some phones revoke access to selected sound files for notifications after an OS upgrade
            logger.error("Can't show notification. Falling back to default ringtone", e)

            if (channelId == NotificationChannels.NOTIFICATION_CHANNEL_CHATS_DEFAULT ||
                channelId == NotificationChannels.NOTIFICATION_CHANNEL_GROUP_CHATS_DEFAULT ||
                channelId == NotificationChannels.NOTIFICATION_CHANNEL_INCOMING_CALLS ||
                channelId == NotificationChannels.NOTIFICATION_CHANNEL_INCOMING_GROUP_CALLS
            ) {
                val soundUri = schema?.soundUri
                if (soundUri != null && soundUri != Settings.System.DEFAULT_NOTIFICATION_URI && soundUri != Settings.System.DEFAULT_RINGTONE_URI) {
                    val newNotificationBuilder = NotificationCompat.Builder(appContext, notification)

                    // post notification to a silent channel
                    newNotificationBuilder.setChannelId(NotificationChannels.NOTIFICATION_CHANNEL_CHAT_UPDATE)
                    try {
                        notificationManagerCompat.notify(tag, notificationId, newNotificationBuilder.build())
                    } catch (e2: Exception) {
                        logger.error("Failed to show fallback notification", e2)
                    }
                }
            }
        } catch (e: Exception) {
            // catch FileUriExposedException - see https://commonsware.com/blog/2016/09/07/notifications-sounds-android-7p0-aggravation.html
            logger.error("Failed to notify", e)
        }
    }

    override fun cancel(conversationId: ConversationId) {
        if (conversationId is DistributionListConversationId) {
            return
        }
        cancel(notificationId = getNotificationIdForConversation(conversationId))

        synchronized(conversationNotificationsCache) {
            val iterator = conversationNotificationsCache.iterator()
            while (iterator.hasNext()) {
                val conversationNotification = iterator.next()
                if (conversationNotification.group.conversationId == conversationId) {
                    iterator.remove()
                    cancelConversationNotification(conversationNotification)
                }
            }
            badgeUpdater.showIconBadge(conversationNotificationsCache.size)
        }

        // TODO(ANDR-4706): This should not be called from here, but from a monitor instead
        widgetUpdater.updateWidgets()
    }

    override fun cancelGroupCallNotification(groupId: LocalGroupId) {
        val conversationId = GroupConversationId(groupId.id.toLong())
        val joinIntent = getGroupCallJoinPendingIntent(
            groupConversationId = conversationId,
            flags = FLAG_NO_CREATE or FLAG_IMMUTABLE,
        )
        joinIntent?.cancel()
        cancel(
            notificationId = NotificationIDs.INCOMING_GROUP_CALL_NOTIFICATION_ID,
            tag = getNotificationTagForGroup(groupId),
        )
    }

    override fun cancelConversationNotificationsOnLockApp() {
        synchronized(conversationNotificationsCache) {
            if (!conversationNotificationsCache.isEmpty()) {
                val containedAnyNotificationToAnUnmutedReceiver = conversationNotificationsCache
                    .any { conversationNotification ->
                        val messageReceiver = conversationNotification.group.messageReceiver
                        !doNotDisturbUtil.isMessageMuted(
                            notificationTriggerPolicyOverride = messageReceiver.getNotificationTriggerPolicyOverrideOrNull(),
                            rawMessageText = conversationNotification.rawMessage,
                        )
                    }
                cancelCachedConversationNotifications()
                /*
                 * We do not want to show the app-locked-new-message notification if all the cached notifications
                 * originated from NOW muted receivers
                 */
                if (containedAnyNotificationToAnUnmutedReceiver) {
                    showDefaultAppLockedNewMessageNotification()
                }
            } else if (cancelAllMessageCategoryNotifications()) {
                /*
                 * In this case we can't really tell if all the canceled system notifications are from blocked
                 * receivers or not. That all the system notifications that were canceled here belonging to NOW
                 * muted receivers is an extreme edge case. So we display the app-locked-new-message notification.
                 *
                 * Note: One could determine the actual receiver of the canceled system notifications by its tag.
                 * But still than we would be missing the raw-message required for `DoNotDisturbUtil.isMessageMuted` method.
                 */
                showDefaultAppLockedNewMessageNotification()
            }
        }
    }

    private fun cancelCachedConversationNotifications() {
        synchronized(this.conversationNotificationsCache) {
            cancelAllCachedConversationNotifications()
            badgeUpdater.showIconBadge(conversationNotificationsCache.size)
        }
    }

    private fun showDefaultAppLockedNewMessageNotification() {
        showAppLockedNewMessageNotification(
            channelId = NotificationChannels.NOTIFICATION_CHANNEL_CHAT_UPDATE,
        )
    }

    override fun cancelConversationNotification(vararg messageUids: MessageUid) {
        synchronized(conversationNotificationsCache) {
            logger.info("Cancel {} conversation notifications", messageUids.size)
            for (messageUid in messageUids) {
                val conversationNotification = conversationNotificationsCache.firstOrNull {
                    it.uid == messageUid
                }

                if (conversationNotification != null) {
                    logger.info("Cancel notification for messageUid={}", messageUid)
                    cancelConversationNotification(conversationNotification)
                } else {
                    logger.info("Notification for messageUid={} not found", messageUid)
                }
            }

            badgeUpdater.showIconBadge(conversationNotificationsCache.size)

            if (conversationNotificationsCache.isEmpty()) {
                cancelAppLockedNewMessagesNotification()
            }
        }

        // TODO(ANDR-4706): This should not be called from here, but from a monitor instead
        widgetUpdater.updateWidgets()
    }

    override fun cancelSafeBackupFailed() {
        cancel(NotificationIDs.SAFE_FAILED_NOTIFICATION_ID)
    }

    override fun cancelWorkSyncProgress() {
        cancel(NotificationIDs.WORK_SYNC_NOTIFICATION_ID)
    }

    override fun cancelRestartNotification() {
        cancel(NotificationIDs.APP_RESTART_NOTIFICATION_ID)
    }

    override fun cancelRestoreCompletionNotification() {
        cancel(NotificationIDs.RESTORE_COMPLETION_NOTIFICATION_ID)
    }

    override fun cancelServerMessageNotification() {
        cancel(NotificationIDs.SERVER_MESSAGE_NOTIFICATION_ID)
    }

    override fun cancelBackupCompletionNotification() {
        cancel(NotificationIDs.BACKUP_COMPLETION_NOTIFICATION_ID)
    }

    override fun cancelAppLockedNewMessagesNotification() {
        cancel(NotificationIDs.NEW_MESSAGE_PIN_LOCKED_NOTIFICATION_ID)
    }

    private fun cancel(notificationId: Int, tag: String? = null) {
        try {
            notificationManagerCompat.cancel(tag, notificationId)
        } catch (e: Exception) {
            logger.warn("Failed to cancel notification {}", notificationId, e)
        }
    }

    companion object {
        private val NOTIFY_AGAIN_TIMEOUT = 30.seconds
        private const val MAX_TICKER_TEXT_LENGTH = 256

        private fun getNotificationTagForGroup(groupId: LocalGroupId) =
            groupId.id.toString()
    }
}
