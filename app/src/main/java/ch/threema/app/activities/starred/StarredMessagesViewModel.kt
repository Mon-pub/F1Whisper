package ch.threema.app.activities.starred

import ch.threema.android.ResolvableString
import ch.threema.android.ResourceIdString
import ch.threema.android.toResolvedString
import ch.threema.app.R
import ch.threema.app.activities.starred.models.ConversationParticipant
import ch.threema.app.activities.starred.models.StarredMessageListItemUiModel
import ch.threema.app.activities.starred.models.StarredMessageUiModel
import ch.threema.app.activities.starred.models.StarredMessagesViewState
import ch.threema.app.compose.common.immutables.ImmutableBitmap
import ch.threema.app.eventbus.GlobalEventBuses
import ch.threema.app.eventbus.events.MessageEvent
import ch.threema.app.framework.BaseViewModel
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.services.ContactService
import ch.threema.app.services.ConversationCategoryService
import ch.threema.app.services.MessageService
import ch.threema.app.services.poll.PollService
import ch.threema.app.usecases.GetStarredMessagesUseCase
import ch.threema.app.usecases.StarredMessageWithMentionInfo
import ch.threema.app.usecases.avatar.GetAndPrepareAvatarUseCase
import ch.threema.app.usecases.groups.GetGroupDisplayNameUseCase
import ch.threema.common.DispatcherProvider
import ch.threema.common.takeUnlessBlank
import ch.threema.data.IdentityProvider
import ch.threema.data.datatypes.ContactConversationId
import ch.threema.data.datatypes.ContactNameFormat
import ch.threema.data.datatypes.ConversationId
import ch.threema.data.datatypes.GroupConversationId
import ch.threema.data.repositories.ContactModelRepository
import ch.threema.data.repositories.GroupModelRepository
import ch.threema.domain.types.Identity
import ch.threema.domain.types.MessageUid
import ch.threema.domain.types.toIdentityOrNull
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.data.DisplayTag.DISPLAY_TAG_STARRED
import ch.threema.storage.models.group.GroupMessageModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

class StarredMessagesViewModel(
    private val getStarredMessagesUseCase: GetStarredMessagesUseCase,
    private val preferenceService: PreferenceService,
    private val contactService: ContactService,
    private val contactModelRepository: ContactModelRepository,
    private val groupModelRepository: GroupModelRepository,
    private val messageService: MessageService,
    private val pollService: PollService,
    private val conversationCategoryService: ConversationCategoryService,
    private val dispatcherProvider: DispatcherProvider,
    private val getAndPrepareAvatarUseCase: GetAndPrepareAvatarUseCase,
    private val identityProvider: IdentityProvider,
    private val getGroupDisplayNameUseCase: GetGroupDisplayNameUseCase,
    private val globalEventBuses: GlobalEventBuses,
) : BaseViewModel<StarredMessagesViewState, StarredMessagesScreenEvent>() {

    @Volatile
    private var loadStarredMessagesJob: Job? = null

    override suspend fun initialize(): StarredMessagesViewState {
        runWhenActive {
            loadStarredMessages()
        }

        return StarredMessagesViewState(
            isLoading = false,
            query = null,
            sortOrder = preferenceService.getStarredMessagesSortOrder(),
            contactNameFormat = preferenceService.getContactNameFormat(),
            emojiStyle = preferenceService.getEmojiStyle(),
            listItems = emptyList(),
        )
    }

    fun loadStarredMessages() {
        loadStarredMessagesJob?.cancel()
        loadStarredMessagesJob = launchAction {
            updateViewState {
                copy(isLoading = true)
            }
            val previouslySelectedMessageId: Set<Int> = currentViewState
                .listItems
                .filter(StarredMessageListItemUiModel::isSelected)
                .map { starredMessageListItemUiModel -> starredMessageListItemUiModel.starredMessageUiModel.messageModel.id }
                .toSet()
            val contactNameFormat = preferenceService.getContactNameFormat()

            val ownIdentity = identityProvider.getIdentity()
            if (ownIdentity == null) {
                updateViewState {
                    copy(isLoading = false)
                }
                return@launchAction
            }

            val starredMessages = getStarredMessagesUseCase.call(
                query = currentViewState.query,
                order = currentViewState.sortOrder,
            ).mapNotNull { starredMessageWithMentionInfo ->
                when (starredMessageWithMentionInfo.abstractMessageModel) {
                    is MessageModel -> mapToStarredContactMessageUiModel(
                        ownIdentity = ownIdentity,
                        starredMessageWithMentionInfo = starredMessageWithMentionInfo,
                    )
                    is GroupMessageModel -> mapToStarredGroupMessageUiModel(
                        ownIdentity = ownIdentity,
                        starredMessageWithMentionInfo = starredMessageWithMentionInfo,
                        contactNameFormat = contactNameFormat,
                    )
                    else -> null
                }
            }.map { starredMessageUiModel ->
                StarredMessageListItemUiModel(
                    starredMessageUiModel = starredMessageUiModel,
                    isSelected = starredMessageUiModel.messageModel.id in previouslySelectedMessageId,
                )
            }
            updateViewState {
                copy(
                    isLoading = false,
                    listItems = starredMessages,
                )
            }
        }
    }

    private fun mapToStarredContactMessageUiModel(
        ownIdentity: Identity,
        starredMessageWithMentionInfo: StarredMessageWithMentionInfo,
    ): StarredMessageUiModel.StarredContactMessage? {
        val abstractMessageModel = starredMessageWithMentionInfo.abstractMessageModel
        val messageUid: MessageUid = abstractMessageModel.uid
            ?: return null
        val messageIdentity = abstractMessageModel.identity
            ?.toIdentityOrNull()
            ?: return null
        val conversationParticipantSender = if (abstractMessageModel.isOutbox) {
            ConversationParticipant.Me(ownIdentity)
        } else {
            getConversationParticipantContact(identity = messageIdentity)
        }
        conversationParticipantSender ?: return null
        val conversationParticipantReceiver = if (abstractMessageModel.isOutbox) {
            getConversationParticipantContact(identity = messageIdentity)
        } else {
            ConversationParticipant.Me(ownIdentity)
        }
        conversationParticipantReceiver ?: return null
        val showWorkBadge = contactService.showIdentityTypeBadge(messageIdentity.value)
        val conversationId = ContactConversationId(messageIdentity.value)
        val isPrivate = conversationCategoryService.isMarkedAsPrivate(conversationId)

        return StarredMessageUiModel.StarredContactMessage(
            uid = messageUid,
            messageModel = abstractMessageModel,
            messageContent = getMessageContent(abstractMessageModel),
            mentionNames = starredMessageWithMentionInfo.mentionedNames,
            sender = conversationParticipantSender,
            receiver = conversationParticipantReceiver,
            conversationId = conversationId,
            showIdentityTypeBadge = showWorkBadge,
            isPrivate = isPrivate,
        )
    }

    private fun mapToStarredGroupMessageUiModel(
        ownIdentity: Identity,
        starredMessageWithMentionInfo: StarredMessageWithMentionInfo,
        contactNameFormat: ContactNameFormat,
    ): StarredMessageUiModel.StarredGroupMessage? {
        val groupMessageModel = starredMessageWithMentionInfo.abstractMessageModel as? GroupMessageModel
            ?: return null
        val groupModel = groupModelRepository.getByGroupDatabaseId(
            groupDatabaseId = groupMessageModel.groupId.toLong(),
        ) ?: return null
        val messageUid: MessageUid = groupMessageModel.uid
            ?: return null
        val conversationParticipantSender = if (groupMessageModel.isOutbox) {
            ConversationParticipant.Me(ownIdentity)
        } else {
            val messageSenderIdentity = groupMessageModel.identity
                ?.toIdentityOrNull()
                ?: return null
            getConversationParticipantContact(messageSenderIdentity)
        }
        conversationParticipantSender
            ?: return null
        val conversationId = GroupConversationId(groupDatabaseId = groupModel.getDatabaseId())

        return StarredMessageUiModel.StarredGroupMessage(
            uid = messageUid,
            conversationId = conversationId,
            messageModel = groupMessageModel,
            messageContent = getMessageContent(groupMessageModel),
            mentionNames = starredMessageWithMentionInfo.mentionedNames,
            sender = conversationParticipantSender,
            groupDisplayName = getGroupDisplayNameUseCase.call(
                groupModel = groupModel,
                contactNameFormat = contactNameFormat,
            ),
            isPrivate = conversationCategoryService.isMarkedAsPrivate(conversationId),
        )
    }

    private fun getConversationParticipantContact(identity: Identity): ConversationParticipant? {
        val contactModelData = contactModelRepository.getByIdentity(identity)?.data
            ?: return null
        return ConversationParticipant.Contact(
            identity = identity,
            firstname = contactModelData.firstName,
            lastname = contactModelData.lastName,
        )
    }

    /**
     *  Same implementation as [ch.threema.app.globalsearch.GlobalSearchAdapter.setSnippetToTextView]
     */
    private fun getMessageContent(abstractMessageModel: AbstractMessageModel): ResolvableString? =
        when (abstractMessageModel.type) {
            MessageType.TEXT -> abstractMessageModel.body?.takeUnlessBlank()?.toResolvedString()
            MessageType.FILE -> abstractMessageModel.caption?.takeUnlessBlank()?.toResolvedString()
            MessageType.POLL -> getPollMessageContent(abstractMessageModel)
            MessageType.LOCATION -> getLocationMessageContent(abstractMessageModel)
            else -> null
        }

    private fun getPollMessageContent(abstractMessageModel: AbstractMessageModel): ResolvableString =
        abstractMessageModel
            .body
            ?.takeUnlessBlank()
            ?.let { pollService.get(abstractMessageModel.pollData.pollId) }
            ?.name
            ?.toResolvedString()
            ?: ResourceIdString(R.string.attach_ballot)

    private fun getLocationMessageContent(abstractMessageModel: AbstractMessageModel): ResolvableString? =
        abstractMessageModel
            .locationData
            .poi
            ?.getSnippetForSearchOrNull()
            ?.toResolvedString()

    fun removeStarsFromSelectedMessages() {
        runAction {
            val selectedMessageModels: List<AbstractMessageModel> = currentViewState
                .listItems
                .filter(StarredMessageListItemUiModel::isSelected)
                .map { starredMessageListItemUiModel ->
                    starredMessageListItemUiModel.starredMessageUiModel.messageModel
                }

            withContext(dispatcherProvider.io) {
                selectedMessageModels.forEach { abstractMessageModel ->
                    // F1Whisper (sixth fork review, F6-01): clear the STARRED bit with a conditional column write.
                    // Two defects lived in the line this replaces. The full-row save wrote back a model loaded when the
                    // starred list was built, reverting any lifecycle transition since and recreating a message expiry
                    // had claimed in between; and assigning DISPLAY_TAG_NONE cleared every OTHER bit as well, including
                    // the fork's terminal-send-failure marker, which silently re-enrolled a permanently failed message
                    // in the auto-resend scan.
                    messageService.clearDisplayTag(abstractMessageModel, DISPLAY_TAG_STARRED)
                }
            }

            globalEventBuses.messages.emit(MessageEvent.MessagesUpdated(selectedMessageModels))

            loadStarredMessages()
            emitEvent(StarredMessagesScreenEvent.SelectedStarsRemoved)
        }
    }

    fun removeAllStars() {
        runAction {
            withContext(dispatcherProvider.io) {
                messageService.unstarAllMessages()
            }
            loadStarredMessages()
        }
    }

    fun onQueryChanged(query: String?) {
        runAction {
            updateViewState {
                copy(query = query)
            }
            loadStarredMessages()
        }
    }

    fun onSortOrderChanged(@PreferenceService.StarredMessagesSortOrder sortOrder: Int) {
        runAction {
            preferenceService.setStarredMessagesSortOrder(sortOrder)
            updateViewState {
                copy(sortOrder = sortOrder)
            }
            loadStarredMessages()
        }
    }

    fun toggleListItemSelected(messageId: Int, initiatedByLongClick: Boolean) {
        runAction {
            val updatedListItems = currentViewState.listItems.map { starredMessageListItemUiModel ->
                if (starredMessageListItemUiModel.starredMessageUiModel.messageModel.id == messageId) {
                    starredMessageListItemUiModel.copy(
                        isSelected = !starredMessageListItemUiModel.isSelected,
                    )
                } else {
                    starredMessageListItemUiModel
                }
            }
            updateViewState {
                copy(listItems = updatedListItems)
            }
            emitEvent(
                StarredMessagesScreenEvent.ListItemSelectedToggled(
                    updatedSelectedListItemsCount = updatedListItems.count(StarredMessageListItemUiModel::isSelected),
                    initiatedByLongClick = initiatedByLongClick,
                ),
            )
        }
    }

    fun unselectAllMessageItems() {
        runAction {
            val updatedListItems = currentViewState.listItems.map { starredMessageListItemUiModel ->
                if (starredMessageListItemUiModel.isSelected) {
                    starredMessageListItemUiModel.copy(
                        isSelected = false,
                    )
                } else {
                    starredMessageListItemUiModel
                }
            }
            updateViewState {
                copy(listItems = updatedListItems)
            }
        }
    }

    val selectedMessageItemsCount: Int
        get() = viewState.value?.listItems?.count(StarredMessageListItemUiModel::isSelected) ?: 0

    suspend fun provideAvatarBitmap(conversationId: ConversationId): ImmutableBitmap? =
        getAndPrepareAvatarUseCase.call(conversationId)
}
