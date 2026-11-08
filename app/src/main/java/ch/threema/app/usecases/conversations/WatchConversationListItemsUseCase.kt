package ch.threema.app.usecases.conversations

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import ch.threema.android.ResolvableString
import ch.threema.android.ResourceIdString
import ch.threema.android.toResolvedString
import ch.threema.app.R
import ch.threema.app.compose.common.IconInfo
import ch.threema.app.compose.common.text.conversation.ConversationTextAnalyzer
import ch.threema.app.compose.conversation.models.ConversationNameStyle
import ch.threema.app.compose.conversation.models.ConversationUiModel
import ch.threema.app.compose.conversation.models.GroupCallUiModel
import ch.threema.app.compose.conversation.models.UnreadState
import ch.threema.app.drafts.DraftManager
import ch.threema.app.drafts.MessageDraft
import ch.threema.app.messagereceiver.ContactMessageReceiver
import ch.threema.app.messagereceiver.GroupMessageReceiver
import ch.threema.app.services.ContactService
import ch.threema.app.services.ConversationCategoryService
import ch.threema.app.services.DistributionListService
import ch.threema.app.services.GroupService
import ch.threema.app.services.RingtoneService
import ch.threema.app.typingindicator.TypingIndicatorProvider
import ch.threema.app.ui.getIconResAt
import ch.threema.app.usecases.availabilitystatus.WatchAllContactAvailabilityStatusesUseCase
import ch.threema.app.usecases.contacts.WatchAllMentionNamesUseCase
import ch.threema.app.usecases.contacts.WatchContactNameFormatSettingUseCase
import ch.threema.app.usecases.groups.WatchGroupCallsUseCase
import ch.threema.app.utils.MessageUtil
import ch.threema.app.utils.NameUtil
import ch.threema.app.utils.QuoteUtil
import ch.threema.app.utils.StateBitmapUtil
import ch.threema.common.TimeProvider
import ch.threema.common.combine
import ch.threema.data.datatypes.AvailabilityStatus
import ch.threema.data.datatypes.ContactConversationId
import ch.threema.data.datatypes.ContactNameFormat
import ch.threema.data.datatypes.ConversationId
import ch.threema.data.datatypes.ConversationIdObfuscated
import ch.threema.data.datatypes.ConversationVisibility
import ch.threema.data.datatypes.DistributionListConversationId
import ch.threema.data.datatypes.GroupConversationId
import ch.threema.data.datatypes.MentionNameData
import ch.threema.data.datatypes.localGroupId
import ch.threema.domain.types.Identity
import ch.threema.domain.types.IdentityString
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.ContactModel
import ch.threema.storage.models.ConversationModel
import ch.threema.storage.models.DistributionListModel
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.ReceiverModel
import ch.threema.storage.models.group.GroupModelOld
import java.time.Instant
import kotlinx.coroutines.flow.Flow

// TODO(ANDR-4175): Watching the typing indicator separately may not be necessary anymore if the isTyping value of archived conversation models is updated correctly
// TODO(ANDR-4277): Group conversation names need to refresh when members added/removed
abstract class WatchConversationListItemsUseCase(
    private val watchConversationsUseCase: WatchConversationsUseCase,
    private val watchGroupCallsUseCase: WatchGroupCallsUseCase,
    private val watchAvatarIterationsUseCase: WatchAvatarIterationsUseCase,
    private val watchContactNameFormatSettingUseCase: WatchContactNameFormatSettingUseCase,
    private val watchAllMentionNamesUseCase: WatchAllMentionNamesUseCase,
    private val watchAllContactAvailabilityStatusesUseCase: WatchAllContactAvailabilityStatusesUseCase,
    private val draftManager: DraftManager,
    private val conversationCategoryService: ConversationCategoryService,
    private val contactService: ContactService,
    private val groupService: GroupService,
    private val distributionListService: DistributionListService,
    private val ringtoneService: RingtoneService,
    private val typingIndicatorProvider: TypingIndicatorProvider,
    private val timeProvider: TimeProvider,
) {

    /**
     *  Creates a flow holding the most recent [ConversationUiModel]s.
     *
     *  Things that influence the list of conversation models:
     *  - Any change to the underlying [ConversationModel]s that is published via the [ch.threema.app.eventbus.GlobalEventFlows], like:
     *      - the latest message
     *      - the category applied to a conversation (private-marked)
     *      - the tag(s) applied to a conversation (pinned, unread)
     *  - Some change to the [ch.threema.app.messagereceiver.MessageReceiver], like:
     *      - Name
     *      - Avatar
     *  - Naming changes to mentioned contacts in the latest message
     *  - Currently running group calls
     *  - Currently typing identities
     *  - User preferences
     *      - Show contact defined avatars
     *      - Show default avatar with colors
     *      - Display order of firstname and lastname
     *  - Message drafts
     *
     *  Note that this list will contain all private-marked conversations, ignoring the user setting to hide them.
     */
    fun call(): Flow<List<ConversationUiModel>> =
        combine(
            watchConversationsUseCase.call(),
            watchGroupCallsUseCase.call(),
            typingIndicatorProvider.watchTypingIdentities(),
            watchAvatarIterationsUseCase.call(),
            watchContactNameFormatSettingUseCase.call(),
            draftManager.drafts,
            watchAllMentionNamesUseCase.call(),
            watchAllContactAvailabilityStatusesUseCase.call(),
        ) {
                conversationModels,
                groupCalls,
                typingIdentities,
                avatarIterations,
                contactNameFormat,
                drafts,
                mentionNameData,
                contactAvailabilityStatuses,
            ->
            val privateConversationIds: Set<ConversationId> = conversationModels
                .mapNotNull { conversationModel ->
                    if (getIsPrivate(conversationModel)) conversationModel.id else null
                }
                .toSet()
            val now = timeProvider.get()
            conversationModels
                .mapNotNull { conversationModel ->
                    mapToConversationUiModel(
                        conversationModel = conversationModel,
                        groupCalls = groupCalls,
                        typingIdentities = typingIdentities,
                        privateConversationIds = privateConversationIds,
                        avatarIterations = avatarIterations,
                        contactNameFormat = contactNameFormat,
                        drafts = drafts,
                        mentionNameData = mentionNameData,
                        contactAvailabilityStatuses = contactAvailabilityStatuses,
                        now,
                    )
                }
        }

    private fun mapToConversationUiModel(
        conversationModel: ConversationModel,
        groupCalls: Set<GroupCallUiModel>,
        typingIdentities: Set<Identity>,
        privateConversationIds: Set<ConversationId>,
        avatarIterations: Map<ConversationId, AvatarIteration>,
        contactNameFormat: ContactNameFormat,
        drafts: Map<ConversationIdObfuscated, MessageDraft>,
        mentionNameData: List<MentionNameData>,
        contactAvailabilityStatuses: Map<IdentityString, AvailabilityStatus>,
        now: Instant,
    ): ConversationUiModel? =
        if (conversationModel.contact != null) {
            mapToContactConversationUiModel(
                conversationModel = conversationModel,
                typingIdentities = typingIdentities,
                privateConversationIds = privateConversationIds,
                avatarIterations = avatarIterations,
                contactNameFormat = contactNameFormat,
                drafts = drafts,
                mentionNameData = mentionNameData,
                contactAvailabilityStatuses = contactAvailabilityStatuses,
                now = now,
            )
        } else if (conversationModel.group != null) {
            mapToGroupConversationUiModel(
                conversationModel = conversationModel,
                groupCalls = groupCalls,
                privateConversationIds = privateConversationIds,
                avatarIterations = avatarIterations,
                contactNameFormat = contactNameFormat,
                drafts = drafts,
                mentionNameData = mentionNameData,
                now = now,
            )
        } else if (conversationModel.distributionList != null) {
            mapToDistributionListConversationUiModel(
                conversationModel = conversationModel,
                privateConversationIds = privateConversationIds,
                contactNameFormat = contactNameFormat,
                drafts = drafts,
                mentionNameData = mentionNameData,
                now = now,
            )
        } else {
            null
        }

    private fun mapToContactConversationUiModel(
        conversationModel: ConversationModel,
        typingIdentities: Set<Identity>,
        privateConversationIds: Set<ConversationId>,
        avatarIterations: Map<ConversationId, AvatarIteration>,
        contactNameFormat: ContactNameFormat,
        drafts: Map<ConversationIdObfuscated, MessageDraft>,
        mentionNameData: List<MentionNameData>,
        contactAvailabilityStatuses: Map<IdentityString, AvailabilityStatus>,
        now: Instant,
    ): ConversationUiModel.ContactConversation? {
        val contactModel: ContactModel = conversationModel.contact
            ?: return null
        val contactIdentity = Identity(contactModel.identity)
        val contactConversationId = ContactConversationId(
            identity = contactIdentity.value,
        )
        return ConversationUiModel.ContactConversation(
            conversationId = contactConversationId,
            latestMessageData = getLatestMessageData(conversationModel, mentionNameData, contactNameFormat),
            receiverDisplayName = getReceiverDisplayNameOrNull(
                receiverModel = conversationModel.receiverModel,
                contactNameFormat = contactNameFormat,
            ),
            conversationName = conversationModel.messageReceiver.getDisplayName(contactNameFormat),
            conversationNameStyle = ConversationNameStyle.forConversationModel(conversationModel),
            draftData = getDraftData(conversationModel, drafts, mentionNameData, contactNameFormat),
            unreadState = getUnreadStateOrNull(conversationModel),
            isPinned = conversationModel.conversationVisibility == ConversationVisibility.PINNED,
            isPrivate = privateConversationIds.contains(contactConversationId),
            icon = getConversationIconOrNull(conversationModel),
            muteStatusIcon = getMuteStatusIconOrNull(conversationModel, now),
            showIdentityTypeBadge = contactService.showIdentityTypeBadge(contactModel),
            isTyping = typingIdentities.contains(contactIdentity),
            avatarIteration = avatarIterations[contactConversationId] ?: AvatarIteration.initial,
            availabilityStatus = contactAvailabilityStatuses[contactModel.identity],
        )
    }

    private fun mapToGroupConversationUiModel(
        conversationModel: ConversationModel,
        groupCalls: Set<GroupCallUiModel>,
        privateConversationIds: Set<ConversationId>,
        avatarIterations: Map<ConversationId, AvatarIteration>,
        contactNameFormat: ContactNameFormat,
        drafts: Map<ConversationIdObfuscated, MessageDraft>,
        mentionNameData: List<MentionNameData>,
        now: Instant,
    ): ConversationUiModel.GroupConversation? {
        val groupModel: GroupModelOld = conversationModel.group
            ?: return null
        val groupContactConversationId = GroupConversationId(
            groupDatabaseId = groupModel.id.toLong(),
        )
        return ConversationUiModel.GroupConversation(
            conversationId = groupContactConversationId,
            latestMessageData = getLatestMessageData(conversationModel, mentionNameData, contactNameFormat),
            receiverDisplayName = getReceiverDisplayNameOrNull(
                receiverModel = conversationModel.receiverModel,
                contactNameFormat = contactNameFormat,
            ),
            conversationName = conversationModel.messageReceiver.getDisplayName(contactNameFormat),
            conversationNameStyle = ConversationNameStyle.forConversationModel(conversationModel),
            draftData = getDraftData(conversationModel, drafts, mentionNameData, contactNameFormat),
            unreadState = getUnreadStateOrNull(conversationModel),
            isPinned = conversationModel.conversationVisibility == ConversationVisibility.PINNED,
            isPrivate = privateConversationIds.contains(groupContactConversationId),
            groupCall = getGroupCallState(groupModel, groupCalls),
            icon = getConversationIconOrNull(conversationModel),
            muteStatusIcon = getMuteStatusIconOrNull(conversationModel, now),
            latestMessageSenderName = getGroupMessageSenderNameOrNull(
                conversationModel = conversationModel,
                contactNameFormat = contactNameFormat,
                drafts = drafts,
            ),
            avatarIteration = avatarIterations[groupContactConversationId] ?: AvatarIteration.initial,
        )
    }

    private fun mapToDistributionListConversationUiModel(
        conversationModel: ConversationModel,
        privateConversationIds: Set<ConversationId>,
        contactNameFormat: ContactNameFormat,
        drafts: Map<ConversationIdObfuscated, MessageDraft>,
        mentionNameData: List<MentionNameData>,
        now: Instant,
    ): ConversationUiModel.DistributionListConversation? {
        val distributionListModel: DistributionListModel = conversationModel.distributionList
            ?: return null
        val distributionListConversationId = DistributionListConversationId(
            distributionListId = distributionListModel.id,
        )
        return ConversationUiModel.DistributionListConversation(
            conversationId = distributionListConversationId,
            latestMessageData = getLatestMessageData(conversationModel, mentionNameData, contactNameFormat),
            receiverDisplayName = getReceiverDisplayNameOrNull(
                receiverModel = conversationModel.receiverModel,
                contactNameFormat = contactNameFormat,
            ),
            conversationName = conversationModel.messageReceiver.getDisplayName(contactNameFormat),
            conversationNameStyle = ConversationNameStyle.forConversationModel(conversationModel),
            draftData = getDraftData(conversationModel, drafts, mentionNameData, contactNameFormat),
            unreadState = getUnreadStateOrNull(conversationModel),
            isPinned = conversationModel.conversationVisibility == ConversationVisibility.PINNED,
            isPrivate = privateConversationIds.contains(distributionListConversationId),
            icon = getConversationIconOrNull(conversationModel),
            muteStatusIcon = getMuteStatusIconOrNull(conversationModel, now),
            avatarIteration = AvatarIteration.initial,
        )
    }

    private fun getLatestMessageData(
        conversationModel: ConversationModel,
        mentionNameData: List<MentionNameData>,
        contactNameFormat: ContactNameFormat,
    ): ConversationUiModel.LatestMessageData? {
        val messageModel = conversationModel.latestMessage ?: return null
        val messageTypeRequired = messageModel.type ?: return null

        @Suppress("DEPRECATION")
        val messageContentThatCouldContainMentions: String =
            when (messageModel.type) {
                MessageType.TEXT -> QuoteUtil.getMessageBody(
                    messageModel.type,
                    messageModel.body,
                    messageModel.caption,
                    messageModel.isOutbox,
                    false,
                    contactNameFormat,
                )
                MessageType.FILE -> messageModel.caption
                else -> null
            } ?: ""

        val mentionNames = ConversationTextAnalyzer.findResolvableMentionNames(
            input = messageContentThatCouldContainMentions,
            mentionNameData = mentionNameData,
            contactNameFormat = contactNameFormat,
        )
        return ConversationUiModel.LatestMessageData(
            type = messageTypeRequired,
            body = messageModel.body,
            caption = messageModel.caption,
            isOutbox = messageModel.isOutbox,
            isDeleted = messageModel.isDeleted,
            postedAt = messageModel.postedAt,
            createdAt = messageModel.createdAt,
            mentionNames = mentionNames,
        )
    }

    private fun getDraftData(
        conversationModel: ConversationModel,
        drafts: Map<ConversationIdObfuscated, MessageDraft>,
        mentionNameData: List<MentionNameData>,
        contactNameFormat: ContactNameFormat,
    ): ConversationUiModel.DraftData? {
        return drafts[conversationModel.id.obfuscated]
            ?.text
            ?.let { draft ->
                val mentionNames: Map<Identity, ResolvableString> = ConversationTextAnalyzer.findResolvableMentionNames(
                    input = draft,
                    mentionNameData = mentionNameData,
                    contactNameFormat = contactNameFormat,
                )
                ConversationUiModel.DraftData(
                    draft = draft,
                    mentionNames = mentionNames,
                )
            }
    }

    private fun getConversationIconOrNull(conversationModel: ConversationModel): IconInfo? =
        when {
            conversationModel.isContactConversation -> getContactConversationIconOrNull(conversationModel)
            conversationModel.isGroupConversation -> getGroupConversationIconOrNull(conversationModel)
            conversationModel.isDistributionListConversation -> IconInfo(
                iconRes = R.drawable.ic_distribution_list,
                contentDescription = R.string.distribution_list,
            )
            else -> null
        }

    /**
     * @throws IllegalArgumentException if the given [conversationModel] is not a contact conversation model
     */
    private fun getContactConversationIconOrNull(conversationModel: ConversationModel): IconInfo? {
        require(conversationModel.isContactConversation) {
            "Must be a contact conversation"
        }
        val latestMessageModel: AbstractMessageModel = conversationModel.latestMessage
            ?: return null
        if (latestMessageModel.type == MessageType.VOIP_STATUS) {
            // TODO(ANDR-4549): Correct the content description used for this icon
            return IconInfo(
                iconRes = R.drawable.ic_phone_locked,
                contentDescription = R.string.state_sent,
            )
        }
        if (!latestMessageModel.isOutbox) {
            // TODO(ANDR-4549): Correct the content description used for this icon
            return IconInfo(
                iconRes = R.drawable.ic_reply,
                contentDescription = R.string.state_sent,
            )
        }

        if (!MessageUtil.showStatusIcon(latestMessageModel)) {
            return null
        }
        val messageState: MessageState = latestMessageModel.state
            ?: return null

        val stateBitmapUtil = StateBitmapUtil.getInstance()
            ?: return null

        @DrawableRes
        val stateIconRes: Int = stateBitmapUtil.getStateDrawable(messageState)
            ?: return null

        val stateIconContentDescriptionRes: Int? = stateBitmapUtil.getStateDescription(messageState)

        @ColorInt
        val tintOverride: Int? =
            if (messageState == MessageState.SENDFAILED || messageState == MessageState.FS_KEY_MISMATCH) {
                stateBitmapUtil.warningColor
            } else {
                null
            }

        return IconInfo(
            iconRes = stateIconRes,
            contentDescription = stateIconContentDescriptionRes,
            tintOverride = tintOverride,
        )
    }

    /**
     * @throws IllegalArgumentException if the given [conversationModel] is not a group conversation model
     */
    private fun getGroupConversationIconOrNull(conversationModel: ConversationModel): IconInfo? {
        check(conversationModel.isGroupConversation) {
            "Must be a group conversation"
        }
        val groupModel = conversationModel.groupModel
            ?: return null
        return if (groupModel.isNotesGroup() == true) {
            IconInfo(
                iconRes = R.drawable.ic_spiral_bound_booklet_outline,
                contentDescription = R.string.notes,
            )
        } else {
            IconInfo(
                iconRes = R.drawable.ic_group_filled,
                contentDescription = R.string.prefs_group_notifications,
            )
        }
    }

    private fun getUnreadStateOrNull(conversationModel: ConversationModel): UnreadState? {
        return when {
            conversationModel.hasUnreadMessage() -> UnreadState.Messages(conversationModel.unreadCount)
            conversationModel.isUnreadTagged -> UnreadState.JustMarked
            else -> null
        }
    }

    private fun getReceiverDisplayNameOrNull(receiverModel: ReceiverModel, contactNameFormat: ContactNameFormat): String? {
        return when (receiverModel) {
            is ContactModel -> NameUtil.getContactDisplayNameOrNickname(
                /* contactModel = */
                receiverModel,
                /* nicknameWithPrefix = */
                true,
                /* contactNameFormat = */
                contactNameFormat,
            )

            is GroupModelOld -> NameUtil.getGroupDisplayName(
                /* groupModel = */
                receiverModel,
                /* groupService = */
                groupService,
                /* contactNameFormat = */
                contactNameFormat,
            )

            is DistributionListModel -> NameUtil.getDistributionListDisplayName(
                /* distributionListModel = */
                receiverModel,
                /* distributionListService = */
                distributionListService,
                /* contactNameFormat = */
                contactNameFormat,
            )

            else -> null
        }
    }

    private fun getIsPrivate(conversationModel: ConversationModel): Boolean {
        return conversationCategoryService.isMarkedAsPrivate(
            conversationId = conversationModel.id,
        )
    }

    private fun getGroupCallState(groupModel: GroupModelOld, groupCalls: Set<GroupCallUiModel>): GroupCallUiModel? {
        return groupCalls.firstOrNull { groupCallUiModel ->
            groupCallUiModel.groupId == groupModel.localGroupId
        }
    }

    @DrawableRes
    private fun getMuteStatusIconOrNull(
        conversationModel: ConversationModel,
        now: Instant,
    ): Int? {
        var iconRes: Int? = null
        val messageReceiver = conversationModel.messageReceiver
        if (messageReceiver is ContactMessageReceiver) {
            iconRes = messageReceiver.contact.notificationTriggerPolicyOverride?.getIconResAt(now)
        } else if (messageReceiver is GroupMessageReceiver) {
            iconRes = messageReceiver.group.notificationTriggerPolicyOverride?.getIconResAt(now)
        }
        val hasCustomRingtone = ringtoneService.hasCustomRingtone(conversationModel.id)
        val isSilent = ringtoneService.isSilent(conversationModel.id, conversationModel.isGroupConversation)
        if (iconRes == null && hasCustomRingtone && isSilent) {
            iconRes = R.drawable.ic_notifications_off_filled
        }
        return iconRes
    }

    private fun getGroupMessageSenderNameOrNull(
        conversationModel: ConversationModel,
        contactNameFormat: ContactNameFormat,
        drafts: Map<ConversationIdObfuscated, MessageDraft>,
    ): ResolvableString? {
        val hasOwnDraft: Boolean = drafts.contains(conversationModel.id.obfuscated)
        val latestMessage: AbstractMessageModel? = conversationModel.latestMessage

        if (
            !conversationModel.isGroupConversation ||
            latestMessage == null ||
            latestMessage.type == MessageType.GROUP_CALL_STATUS ||
            hasOwnDraft
        ) {
            return null
        }

        return if (latestMessage.isOutbox) {
            ResourceIdString(R.string.me_myself_and_i)
        } else {
            val contactModel: ContactModel? = contactService.getByIdentity(latestMessage.identity)
            NameUtil
                .getShortName(contactModel, contactNameFormat)
                ?.toResolvedString()
        }
    }
}
