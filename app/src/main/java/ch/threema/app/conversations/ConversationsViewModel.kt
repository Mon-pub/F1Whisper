package ch.threema.app.conversations

import android.content.Context
import androidx.lifecycle.viewModelScope
import ch.threema.app.BuildFlavor
import ch.threema.app.asynctasks.AddOrUpdateSupportContactBackgroundTask
import ch.threema.app.asynctasks.ContactAvailable
import ch.threema.app.asynctasks.ContactResult
import ch.threema.app.asynctasks.Failed
import ch.threema.app.backuprestore.ExportConversationService
import ch.threema.app.compose.conversation.models.ConversationListItemUiModel
import ch.threema.app.compose.conversation.models.ConversationUiModel
import ch.threema.app.eventbus.GlobalEventBuses
import ch.threema.app.eventbus.GlobalEventFlows
import ch.threema.app.eventbus.events.ActionEvent
import ch.threema.app.eventbus.events.ContactEvent
import ch.threema.app.eventbus.events.DistributionListEvent
import ch.threema.app.eventbus.events.GroupEvent
import ch.threema.app.files.deleteSecurely
import ch.threema.app.framework.BaseViewModel
import ch.threema.app.groupflows.GroupDisbandIntent
import ch.threema.app.groupflows.GroupFlowResult
import ch.threema.app.groupflows.GroupLeaveIntent
import ch.threema.app.messagereceiver.ContactMessageReceiver
import ch.threema.app.messagereceiver.DistributionListMessageReceiver
import ch.threema.app.messagereceiver.GroupMessageReceiver
import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.protocolsteps.ValidContactsLookupSteps
import ch.threema.app.restrictions.AppRestrictions
import ch.threema.app.services.ChatFolderService
import ch.threema.app.services.ConversationCategoryService
import ch.threema.app.services.ConversationService
import ch.threema.app.services.DistributionListService
import ch.threema.app.services.GroupFlowDispatcher
import ch.threema.app.services.MessageService
import ch.threema.app.services.notification.NotificationService
import ch.threema.app.usecases.avatar.GetAndPrepareAvatarUseCase
import ch.threema.app.usecases.contacts.WatchContactNameFormatSettingUseCase
import ch.threema.app.usecases.conversation.ExportConversationToFileUseCase
import ch.threema.app.usecases.conversations.EmptyOrDeleteConversationsUseCase
import ch.threema.app.usecases.conversations.WatchUnarchivedConversationListItemsUseCase
import ch.threema.base.utils.getThreemaLogger
import ch.threema.base.utils.onCompleted
import ch.threema.common.DispatcherProvider
import ch.threema.common.stateFlowOf
import ch.threema.common.takeUnlessEmpty
import ch.threema.data.IdentityProvider
import ch.threema.data.datatypes.AvailabilityStatus
import ch.threema.data.datatypes.ContactConversationId
import ch.threema.data.datatypes.ContactNameFormat
import ch.threema.data.datatypes.ConversationId
import ch.threema.data.datatypes.ConversationVisibility
import ch.threema.data.datatypes.GroupIdentity
import ch.threema.data.models.ContactModel
import ch.threema.data.models.GroupModel
import ch.threema.data.models.GroupModelData
import ch.threema.data.repositories.ContactModelRepository
import ch.threema.data.repositories.GroupModelRepository
import ch.threema.domain.taskmanager.TriggerSource
import ch.threema.domain.types.GroupDatabaseId
import ch.threema.domain.types.Identity
import ch.threema.storage.models.ConversationModel
import ch.threema.storage.models.ConversationTag
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger

private val logger: Logger = getThreemaLogger("ConversationsViewModel")

/**
 *  @param initiallyOpenedConversation If the screen that uses this viewmodel is used in a multi-pane-mode, this value can be passed. The
 *  currently opened conversation is shown as a highlighted list item. It will only be used if [isMultiPaneEnabled] is set to `true`.
 */
class ConversationsViewModel(
    private val applicationContext: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val conversationService: ConversationService,
    private val conversationCategoryService: ConversationCategoryService,
    private val chatFolderService: ChatFolderService,
    private val preferenceService: PreferenceService,
    private val messageService: MessageService,
    private val notificationService: NotificationService,
    private val exportConversationService: ExportConversationService,
    private val distributionListService: DistributionListService,
    private val validContactsLookupSteps: ValidContactsLookupSteps,
    private val appRestrictions: AppRestrictions,
    private val contactModelRepository: ContactModelRepository,
    private val groupModelRepository: GroupModelRepository,
    private val groupFlowDispatcher: GroupFlowDispatcher,
    private val identityProvider: IdentityProvider,
    globalEventFlows: GlobalEventFlows,
    private val globalEventBuses: GlobalEventBuses,
    private val exportConversationToFileUseCase: ExportConversationToFileUseCase,
    private val getAndPrepareAvatarUseCase: GetAndPrepareAvatarUseCase,
    private val watchConversationListItemsUseCase: WatchUnarchivedConversationListItemsUseCase,
    private val watchContactNameFormatSettingUseCase: WatchContactNameFormatSettingUseCase,
    private val emptyOrDeleteConversationsUseCase: EmptyOrDeleteConversationsUseCase,
    isMultiPaneEnabled: Boolean,
    initiallyOpenedConversation: ConversationId?,
) : BaseViewModel<ConversationsViewState, ConversationsViewEvent>() {

    private lateinit var watchConversationListItemsFlow: Flow<Result<List<ConversationUiModel>>>

    private val highlightedConversationFlow: StateFlow<ConversationId?> =
        if (isMultiPaneEnabled) {
            globalEventFlows.actions
                .filterIsInstance<ActionEvent.ConversationOpened>()
                .map { it.conversationId }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = initiallyOpenedConversation,
                )
        } else {
            stateFlowOf(null)
        }

    // We have to remember the unfiltered models
    @Volatile
    private var latestConversationUiModelsResult: Result<List<ConversationUiModel>>? = null

    // The cached set of conversation uids that are members of the currently selected folder. Empty
    // when no folder is selected (the "All" filter). Always refreshed for the active folder before
    // it is used for filtering.
    @Volatile
    private var selectedFolderMemberUids: Set<String> = emptySet()

    private var addOrUpdateSupportContactJob: Job? = null

    override suspend fun initialize(): ConversationsViewState {
        watchConversationListItemsFlow = watchConversationListItemsUseCase
            .call()
            .map { conversationUiModels ->
                Result.success(conversationUiModels)
            }
            .catch { throwable ->
                logger.error("Failed to load conversation ui models", throwable)
                emit(Result.failure(throwable))
            }
        produceOngoingState()
        return produceInitialState()
    }

    private suspend fun produceInitialState(): ConversationsViewState = withContext(dispatcherProvider.io) {
        val hidePrivateConversations: Boolean = preferenceService.arePrivateChatsHidden()
        val folders: List<ChatFolderUiModel> = buildFolderUiModels()
        val initialItemsState: ItemsState = produceInitialItemsState(hidePrivateConversations)
        val archivedConversationsCount: Long = conversationService.countArchived(
            /* searchQuery = */
            null,
            /* excludePrivateConversations = */
            hidePrivateConversations,
        )
        val contactNameFormatOrDefault =
            runCatching {
                preferenceService.getContactNameFormat()
            }.getOrElse { throwable ->
                logger.error("Failed to read contact name format setting from preferences", throwable)
                ContactNameFormat.DEFAULT
            }

        ConversationsViewState(
            itemsState = initialItemsState,
            searchQuery = null,
            hidePrivateConversations = hidePrivateConversations,
            hasPrivateConversations = conversationCategoryService.hasAnyPrivateMarks(),
            archivedConversationsCount = archivedConversationsCount,
            contactNameFormat = contactNameFormatOrDefault,
            availabilityStatus = getAvailabilityStatusOrNone(),
            pendingAction = null,
            folders = folders,
            selectedFolderId = null,
        )
    }

    private suspend fun produceInitialItemsState(hidePrivateConversations: Boolean): ItemsState {
        val conversationUiModelsResult: Result<List<ConversationUiModel>> = watchConversationListItemsFlow.first()
        latestConversationUiModelsResult = conversationUiModelsResult
        return conversationUiModelsResult
            .fold(
                onSuccess = { conversationUiModels ->
                    ItemsState.Loaded(
                        items = filterConversationUiModels(
                            conversationUiModels = conversationUiModels,
                            hidePrivateConversations = hidePrivateConversations,
                            searchQuery = null,
                            selectedFolderId = null,
                            selectedFolderMemberUids = emptySet(),
                        ).map(::ConversationListItemUiModel),
                    )
                },
                onFailure = {
                    ItemsState.Failed
                },
            )
    }

    private fun produceOngoingState() = runAction {
        combine(
            flow = watchConversationListItemsFlow,
            flow2 = preferenceService.watchArePrivateChatsHidden(),
            flow3 = highlightedConversationFlow,
            flow4 = watchContactNameFormatSettingUseCase.call(),
            flow5 = watchAvailabilityStatusOrNone(),
        ) { conversationUiModelsResult, hidePrivateConversations, openedConversationId, contactNameFormat, availabilityStatus ->
            latestConversationUiModelsResult = conversationUiModelsResult
            // Recompute the folders so that newly created/renamed/deleted folders are reflected. The
            // chat folder service fires `onModifiedAll()` on every mutation which re-triggers this combine.
            val folders: List<ChatFolderUiModel> = buildFolderUiModels()
            // If the currently selected folder no longer exists, fall back to the "All" filter.
            val selectedFolderId: Long? = currentViewState.selectedFolderId
                ?.takeIf { selectedId -> folders.any { it.id == selectedId } }
            selectedFolderMemberUids = loadFolderMemberUids(selectedFolderId)
            val updatedItemsState = conversationUiModelsResult
                .fold(
                    onSuccess = { conversationUiModels ->
                        ItemsState.Loaded(
                            items = filterConversationUiModels(
                                conversationUiModels = conversationUiModels,
                                hidePrivateConversations = hidePrivateConversations,
                                searchQuery = currentViewState.searchQuery,
                                selectedFolderId = selectedFolderId,
                                selectedFolderMemberUids = selectedFolderMemberUids,
                            ).map { conversationUiModel ->
                                ConversationListItemUiModel(
                                    model = conversationUiModel,
                                    isHighlighted = conversationUiModel.conversationId == openedConversationId,
                                )
                            },
                        )
                    },
                    onFailure = {
                        ItemsState.Failed
                    },
                )
            val archivedConversationsCount: Long = conversationService.countArchived(
                /* searchQuery = */
                currentViewState.searchQuery,
                /* excludePrivateConversations = */
                hidePrivateConversations,
            )
            updateViewState {
                copy(
                    itemsState = updatedItemsState,
                    hidePrivateConversations = hidePrivateConversations,
                    hasPrivateConversations = conversationCategoryService.hasAnyPrivateMarks(),
                    archivedConversationsCount = archivedConversationsCount,
                    contactNameFormat = contactNameFormat,
                    availabilityStatus = availabilityStatus,
                    folders = folders,
                    selectedFolderId = selectedFolderId,
                )
            }
        }
            .flowOn(dispatcherProvider.io)
            .launchIn(viewModelScope)
    }

    private fun getAvailabilityStatusOrNone(): AvailabilityStatus =
        runCatching {
            preferenceService.getAvailabilityStatus()
                ?: AvailabilityStatus.None
        }.getOrElse { throwable ->
            logger.error("Failed to users availability status from preferences", throwable)
            AvailabilityStatus.None
        }

    private fun watchAvailabilityStatusOrNone(): StateFlow<AvailabilityStatus> =
        preferenceService.watchAvailabilityStatus()
            .map { availabilityStatus ->
                availabilityStatus ?: AvailabilityStatus.None
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Lazily,
                initialValue = getAvailabilityStatusOrNone(),
            )

    fun onLongClickConversationItem(conversationId: ConversationId) = runAction {
        val conversationModel: ConversationModel? = conversationService.get(conversationId)
        if (conversationModel != null) {
            emitEvent(ConversationsViewEvent.OpenConversationActionDialog(conversationModel))
        } else {
            logger.warn("Could not show conversation action dialog because conversation model is missing for receiver {}", conversationId)
            emitEvent(ConversationsViewEvent.InternalError)
        }
    }

    fun onSearchQueryChange(searchQuery: String?) = runAction {
        val latestConversationUiModels: List<ConversationUiModel> = latestConversationUiModelsResult?.getOrNull()
            ?: return@runAction
        val didSearchQueryChangeEffectively = didSearchQueryChangeEffectively(
            currentQuery = currentViewState.searchQuery,
            updatedQuery = searchQuery,
        )
        if (!didSearchQueryChangeEffectively) {
            // Skip unnecessary search filtering and view state update
            return@runAction
        }
        val updatedItems: List<ConversationListItemUiModel> =
            filterConversationUiModels(
                conversationUiModels = latestConversationUiModels,
                hidePrivateConversations = currentViewState.hidePrivateConversations,
                searchQuery = searchQuery,
                selectedFolderId = currentViewState.selectedFolderId,
                selectedFolderMemberUids = selectedFolderMemberUids,
            ).map { conversationUiModel ->
                ConversationListItemUiModel(
                    model = conversationUiModel,
                    isHighlighted = conversationUiModel.conversationId == highlightedConversationFlow.value,
                )
            }
        val archivedConversationsCount = withContext(dispatcherProvider.io) {
            conversationService.countArchived(
                /* searchQuery = */
                searchQuery,
                /* excludePrivateConversations = */
                currentViewState.hidePrivateConversations,
            )
        }
        updateViewState {
            copy(
                itemsState = ItemsState.Loaded(
                    items = updatedItems,
                ),
                searchQuery = searchQuery,
                archivedConversationsCount = archivedConversationsCount,
            )
        }
    }

    /**
     *  Selects a chat folder to filter the conversation list by. Pass `null` to clear the filter ("All").
     */
    fun onFolderSelected(folderId: Long?) = runAction {
        val effectiveFolderId: Long? = folderId
            ?.takeIf { selectedId -> currentViewState.folders.any { it.id == selectedId } }
        selectedFolderMemberUids = loadFolderMemberUids(effectiveFolderId)

        val latestConversationUiModels: List<ConversationUiModel> = latestConversationUiModelsResult?.getOrNull()
            ?: emptyList()
        val updatedItems: List<ConversationListItemUiModel> =
            filterConversationUiModels(
                conversationUiModels = latestConversationUiModels,
                hidePrivateConversations = currentViewState.hidePrivateConversations,
                searchQuery = currentViewState.searchQuery,
                selectedFolderId = effectiveFolderId,
                selectedFolderMemberUids = selectedFolderMemberUids,
            ).map { conversationUiModel ->
                ConversationListItemUiModel(
                    model = conversationUiModel,
                    isHighlighted = conversationUiModel.conversationId == highlightedConversationFlow.value,
                )
            }
        updateViewState {
            copy(
                itemsState = ItemsState.Loaded(
                    items = updatedItems,
                ),
                selectedFolderId = effectiveFolderId,
            )
        }
    }

    /**
     *  Sets the folder membership of the given conversation to exactly [folderIds]. The chat folder
     *  service fires `onModifiedAll()` so the list refreshes automatically.
     */
    fun onAddConversationToFolders(conversationId: ConversationId, folderIds: Set<Long>) = runAction {
        withContext(dispatcherProvider.io) {
            chatFolderService.setConversationFolders(conversationId.toDatabaseValue(), folderIds)
        }
    }

    /**
     *  Determine if the change to the query value **could** have an effective impact on the result list after search. A query change from `null` to
     *  an `empty string`, or vice versa, will have no effect on the searching logic.
     *
     *  @see ConversationUiModel.matchesSearchQuery
     */
    private fun didSearchQueryChangeEffectively(currentQuery: String?, updatedQuery: String?): Boolean {
        val current = currentQuery?.takeUnlessEmpty()
        val updated = updatedQuery?.takeUnlessEmpty()
        return current != updated
    }

    /**
     *  Takes a list of all conversation models and returns a list of conversation models that should be effectively presented to the user.
     *  Besides the search query and the private-chats setting, this applies the selected chat folder (if any).
     */
    private fun filterConversationUiModels(
        conversationUiModels: List<ConversationUiModel>,
        hidePrivateConversations: Boolean,
        searchQuery: String?,
        selectedFolderId: Long?,
        selectedFolderMemberUids: Set<String>,
    ): List<ConversationUiModel> =
        conversationUiModels
            .filter { conversationUiModel ->
                !conversationUiModel.isPrivate || !hidePrivateConversations
            }
            .filter { conversationUiModel ->
                conversationUiModel.matchesSearchQuery(searchQuery)
            }
            .filter { conversationUiModel ->
                selectedFolderId == null || conversationUiModel.conversationId.toDatabaseValue() in selectedFolderMemberUids
            }

    private suspend fun buildFolderUiModels(): List<ChatFolderUiModel> =
        withContext(dispatcherProvider.io) {
            chatFolderService.getFolders().map { folder ->
                ChatFolderUiModel(id = folder.id, name = folder.name)
            }
        }

    private suspend fun loadFolderMemberUids(folderId: Long?): Set<String> =
        if (folderId == null) {
            emptySet()
        } else {
            withContext(dispatcherProvider.io) {
                chatFolderService.getMemberUids(folderId)
            }
        }

    /**
     *  @param isAndroidSystemLockConfigured Whether the system has a screen lock defined (not Threema)
     */
    fun onViewResumed(isAndroidSystemLockConfigured: Boolean) = runAction {
        // Show private hidden chats if the lock machinism was SYSTEM, and it was removed by the user
        if (preferenceService.getLockMechanism() == PreferenceService.LockMechanism.SYSTEM && !isAndroidSystemLockConfigured) {
            emitEvent(ConversationsViewEvent.OnSystemLockWasRemoved)
            with(preferenceService) {
                setLockMechanism(PreferenceService.LockMechanism.NONE)
                setAppLockEnabled(false)
                setArePrivateChatsHidden(false)
            }
            emitEvent(ConversationsViewEvent.UpdateWidgets)
            firePrivateReceiverUpdate()
        }
    }

    fun onClickMarkConversationAsPrivate(conversationId: ConversationId) = runAction {
        val conversationModel = conversationService.getCached(conversationId)
        if (conversationModel == null) {
            logger.error("Could not mark conversation as private because the model is missing")
            emitEvent(ConversationsViewEvent.InternalError)
            return@runAction
        }
        if (!preferenceService.hasLockMechanism()) {
            updateViewState {
                copy(
                    pendingAction = ConversationsViewPendingAction.MarkConversationAsPrivate(conversationModel),
                )
            }
            emitEvent(
                event = ConversationsViewEvent.LockMechanismRequiredToUpdatePrivateConversationMark(
                    targetValueIsMarkedAsPrivate = true,
                ),
            )
            return@runAction
        }

        if (conversationCategoryService.isMarkedAsPrivate(conversationId)) {
            logger.warn("Could not mark the conversation as private because it is already private")
            emitEvent(ConversationsViewEvent.InternalError)
            return@runAction
        }

        emitEvent(
            event = ConversationsViewEvent.ConfirmationRequiredToMarkConversationAsPrivate(conversationId),
        )
    }

    fun onLockMechanismConfiguredToMarkConversationAsPrivate() = runAction {
        if (!preferenceService.hasLockMechanism()) {
            return@runAction
        }
        val conversationModel = (currentViewState.pendingAction as? ConversationsViewPendingAction.MarkConversationAsPrivate)?.conversation
        if (conversationModel == null) {
            logger.error("Could not effectively mark the conversation as private because there was no pending action for it")
            emitEvent(ConversationsViewEvent.InternalError)
            return@runAction
        }
        completePendingAction()
        markConversationAsPrivateEffectively(conversationModel)
    }

    fun onClickConfirmMarkConversationAsPrivate(conversationId: ConversationId) = runAction {
        val conversationModel: ConversationModel? = conversationService.getCached(conversationId)
        if (conversationModel != null) {
            markConversationAsPrivateEffectively(conversationModel)
        } else {
            logger.error("Could not effectively mark the conversation as private because the model is missing")
            emitEvent(ConversationsViewEvent.InternalError)
        }
    }

    private fun markConversationAsPrivateEffectively(conversationModel: ConversationModel) = runAction {
        if (!preferenceService.hasLockMechanism()) {
            logger.warn("Can not mark a conversation as private without a configured lock mechanism")
            emitEvent(ConversationsViewEvent.InternalError)
            return@runAction
        }
        val success = conversationCategoryService.setPrivateMark(conversationModel.id)
        if (success) {
            emitEvent(ConversationsViewEvent.ConversationMarkAsPrivateSuccess)
            fireReceiverUpdate(conversationModel.messageReceiver)
            if (preferenceService.arePrivateChatsHidden()) {
                firePrivateReceiverUpdate()
            }
        } else {
            logger.warn("Could not effectively mark the conversation as private because it is already private")
            emitEvent(ConversationsViewEvent.InternalError)
        }
    }

    fun onClickUnmarkConversationAsPrivate(conversationId: ConversationId) = runAction {
        val conversationModel = conversationService.getCached(conversationId)
        if (conversationModel == null) {
            logger.error("Could not unmark conversation as private because the model is missing")
            emitEvent(ConversationsViewEvent.InternalError)
            return@runAction
        }
        if (!preferenceService.hasLockMechanism()) {
            updateViewState {
                copy(
                    pendingAction = ConversationsViewPendingAction.UnmarkConversationAsPrivate(conversationModel),
                )
            }
            emitEvent(
                event = ConversationsViewEvent.LockMechanismRequiredToUpdatePrivateConversationMark(
                    targetValueIsMarkedAsPrivate = false,
                ),
            )
            return@runAction
        }

        if (!conversationCategoryService.isMarkedAsPrivate(conversationId)) {
            logger.warn("Could not unmark the conversation as private because it is not private")
            emitEvent(ConversationsViewEvent.InternalError)
            return@runAction
        }

        updateViewState {
            copy(
                pendingAction = ConversationsViewPendingAction.UnmarkConversationAsPrivate(conversationModel),
            )
        }
        emitEvent(ConversationsViewEvent.UnlockRequiredToUnmarkConversationAsPrivate)
    }

    fun onLockMechanismConfiguredToUnmarkConversationAsPrivate() {
        if (preferenceService.hasLockMechanism()) {
            unmarkConversationAsPrivateEffectively()
        }
    }

    fun onUnlockSuccessToUnmarkConversationAsPrivate() {
        unmarkConversationAsPrivateEffectively()
    }

    private fun unmarkConversationAsPrivateEffectively() = runAction {
        val conversationModel = (currentViewState.pendingAction as? ConversationsViewPendingAction.UnmarkConversationAsPrivate)?.conversation
        if (conversationModel == null) {
            logger.error("Could not effectively unmark the conversation as private because there was no pending action for it")
            emitEvent(ConversationsViewEvent.InternalError)
            return@runAction
        }
        completePendingAction()
        if (!preferenceService.hasLockMechanism()) {
            return@runAction
        }
        val success = conversationCategoryService.removePrivateMark(conversationModel.id)
        if (success) {
            emitEvent(ConversationsViewEvent.ConversationUnmarkAsPrivateSuccess)
            fireReceiverUpdate(conversationModel.messageReceiver)
        } else {
            logger.warn("Could not effectively unmark the conversation as private because it is not private")
            emitEvent(ConversationsViewEvent.InternalError)
        }
    }

    fun onClickHideOrShowPrivateConversations() = runAction {
        val arePrivateChatsCurrentlyHidden = preferenceService.arePrivateChatsHidden()

        // This state can only be reached by setting the SYSTEM lock mechanism in Threema and then removing it in system settings
        if (!arePrivateChatsCurrentlyHidden && !preferenceService.hasLockMechanism()) {
            emitEvent(ConversationsViewEvent.LockMechanismRequiredToHidePrivateConversations)
            return@runAction
        }

        if (arePrivateChatsCurrentlyHidden) {
            emitEvent(ConversationsViewEvent.UnlockRequiredToShowPrivateConversations)
            return@runAction
        }

        preferenceService.setArePrivateChatsHidden(true)
        emitEvent(ConversationsViewEvent.UpdateWidgets)
        firePrivateReceiverUpdate()
    }

    fun onLockMechanismConfiguredToHidePrivateConversations() = runAction {
        if (preferenceService.hasLockMechanism()) {
            preferenceService.setArePrivateChatsHidden(true)
            emitEvent(ConversationsViewEvent.UpdateWidgets)
            firePrivateReceiverUpdate()
        }
    }

    fun onUnlockSuccessToShowPrivateConversations() = runAction {
        if (!preferenceService.arePrivateChatsHidden()) {
            // The setting changed in the meantime, nothing to do
            return@runAction
        }
        preferenceService.setArePrivateChatsHidden(false)
        emitEvent(ConversationsViewEvent.UpdateWidgets)
        firePrivateReceiverUpdate()
    }

    fun onSwipedListItemPin(conversationId: ConversationId) {
        togglePinConversation(conversationId)
    }

    fun onSwipedListItemArchive(conversationId: ConversationId) {
        archiveConversation(conversationId)
    }

    fun onClickArchiveConversation(conversationId: ConversationId) {
        archiveConversation(conversationId)
    }

    private fun archiveConversation(conversationId: ConversationId) = runAction {
        val conversationModel: ConversationModel? = conversationService.getCached(conversationId)
        if (conversationModel != null) {
            conversationService.archive(conversationModel, TriggerSource.LOCAL)
            emitEvent(ConversationsViewEvent.ConversationArchived(conversationModel))
        } else {
            logger.warn("Could not archive conversation because conversation model is missing for conversation {}", conversationId)
            emitEvent(ConversationsViewEvent.InternalError)
        }
    }

    private fun togglePinConversation(conversationId: ConversationId) = runAction {
        val conversationModel = conversationService.getCached(conversationId) ?: run {
            logger.warn("Could not pin/unpin conversation because conversation model is missing for conversation {}", conversationId)
            emitEvent(ConversationsViewEvent.InternalError)
            return@runAction
        }
        when {
            conversationModel.isContactConversation -> {
                val contactModel = conversationModel.contactModel
                    ?: return@runAction
                val newConversationVisibility = contactModel.getToggledPinConversationVisibility()
                    ?: return@runAction

                contactModel.setConversationVisibilityFromLocalOrRemote(newConversationVisibility)
                conversationModel.conversationVisibility = newConversationVisibility
            }

            conversationModel.isGroupConversation -> {
                val groupModel = conversationModel.groupModel
                    ?: return@runAction

                val newConversationVisibility = groupModel.getToggledPinConversationVisibility()
                    ?: return@runAction

                groupModel.setConversationVisibilityFromLocalOrRemote(newConversationVisibility)
                conversationModel.conversationVisibility = newConversationVisibility
            }

            conversationModel.isDistributionListConversation -> {
                val distributionListModel = conversationModel.distributionList
                    ?: return@runAction

                val newConversationVisibility = distributionListModel.conversationVisibility.getToggledPinConversationVisibility()
                    ?: return@runAction

                distributionListService.setConversationVisibility(
                    distributionListModel,
                    newConversationVisibility,
                )
                conversationModel.conversationVisibility = newConversationVisibility
            }

            else -> {
                logger.error("Conversation is of unknown type")
            }
        }
    }

    private fun ContactModel.getToggledPinConversationVisibility(): ConversationVisibility? =
        data?.conversationVisibility?.getToggledPinConversationVisibility()

    private fun GroupModel.getToggledPinConversationVisibility(): ConversationVisibility? =
        data?.conversationVisibility?.getToggledPinConversationVisibility()

    private fun ConversationVisibility.getToggledPinConversationVisibility(): ConversationVisibility? = when (this) {
        ConversationVisibility.NORMAL -> ConversationVisibility.PINNED
        ConversationVisibility.ARCHIVED -> null
        ConversationVisibility.PINNED -> ConversationVisibility.NORMAL
    }

    fun onClickMarkConversationAsRead(conversationId: ConversationId) = runAction {
        val conversationModel = conversationService.getCached(conversationId)
        if (conversationModel == null) {
            logger.error("Could not mark conversation as read because the model is missing")
            emitEvent(ConversationsViewEvent.InternalError)
            return@runAction
        }
        conversationService.untag(conversationModel, ConversationTag.MARKED_AS_UNREAD, TriggerSource.LOCAL)
        withContext(dispatcherProvider.io) {
            messageService.markConversationAsRead(conversationModel.messageReceiver, notificationService)
        }
    }

    fun onClickMarkConversationAsUnread(conversationId: ConversationId) = runAction {
        val conversationModel = conversationService.getCached(conversationId)
        if (conversationModel == null) {
            logger.error("Could not mark conversation as unread because the model is missing")
            emitEvent(ConversationsViewEvent.InternalError)
            return@runAction
        }
        conversationService.tag(conversationModel, ConversationTag.MARKED_AS_UNREAD, TriggerSource.LOCAL)
    }

    fun onClickEmptyConversation(conversationId: ConversationId) = runAction {
        emitEvent(
            event = ConversationsViewEvent.ConfirmationRequiredToEmptyConversation(conversationId),
        )
    }

    fun onClickConfirmEmptyConversation(conversationId: ConversationId) {
        emptyConversation(conversationId)
    }

    fun onClickDeleteContactConversation(conversationId: ConversationId) = runAction {
        emitEvent(
            event = ConversationsViewEvent.ConfirmationRequiredToDeleteContactConversation(conversationId),
        )
    }

    fun onClickedConfirmDeleteContactConversation(conversationId: ConversationId) {
        deleteConversation(conversationId)
    }

    fun onClickDeleteDistributionListConversation(conversationId: ConversationId) = runAction {
        emitEvent(
            event = ConversationsViewEvent.ConfirmationRequiredToDeleteDistributionListConversation(
                conversationId = conversationId,
            ),
        )
    }

    fun onClickedConfirmDeleteDistributionListConversation(conversationId: ConversationId) {
        deleteConversation(conversationId)
    }

    suspend fun provideAvatarBitmap(conversationId: ConversationId) =
        getAndPrepareAvatarUseCase.call(conversationId)

    private fun emptyConversation(conversationId: ConversationId) = runAction {
        emitEvent(ConversationsViewEvent.OnEmptyingConversation)
        val result: EmptyOrDeleteConversationsUseCase.Result = emptyOrDeleteConversationsUseCase.call(
            conversationIds = listOf(conversationId),
            mode = EmptyOrDeleteConversationsUseCase.Mode.EMPTY,
        )
        emitEvent(ConversationsViewEvent.OnEmptyingConversationResult(result))
    }

    private fun deleteConversation(conversationId: ConversationId) = runAction {
        emitEvent(ConversationsViewEvent.OnDeletingConversation)
        val result: EmptyOrDeleteConversationsUseCase.Result = emptyOrDeleteConversationsUseCase.call(
            conversationIds = listOf(conversationId),
            mode = EmptyOrDeleteConversationsUseCase.Mode.DELETE,
        )
        emitEvent(ConversationsViewEvent.OnDeletingConversationResult(result))
    }

    private fun fireReceiverUpdate(receiver: MessageReceiver<*>) {
        when (receiver) {
            is GroupMessageReceiver -> {
                val groupIdentity = GroupIdentity(
                    creatorIdentity = receiver.group.creatorIdentity,
                    groupId = receiver.group.apiGroupId.toLong(),
                )
                globalEventBuses.groups.emit(GroupEvent.GroupUpdated(groupIdentity))
            }

            is ContactMessageReceiver -> {
                globalEventBuses.contacts.emit(ContactEvent.ContactUpdated(Identity(receiver.contact.identity)))
            }

            is DistributionListMessageReceiver -> {
                globalEventBuses.distributionLists.emit(
                    DistributionListEvent.DistributionListUpdated(receiver.distributionList),
                )
            }
        }
    }

    private fun firePrivateReceiverUpdate() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // fire an update for every secret receiver (to update webclient data)
                conversationService.getAll(false)
                    .filter { conversationModel ->
                        conversationCategoryService.isMarkedAsPrivate(conversationModel.id)
                    }
                    .forEach { conversationModel ->
                        fireReceiverUpdate(conversationModel.messageReceiver)
                    }
            }
        }
    }

    fun onMissingPermissionToShareConversation(conversationId: ConversationId) = runAction {
        updateViewState {
            copy(
                pendingAction = ConversationsViewPendingAction.ShareConversation(conversationId),
            )
        }
        emitEvent(
            event = ConversationsViewEvent.StoragePermissionRequiredToShareConversation,
        )
    }

    fun onStoragePermissionGrantedToShareConversation() = runAction {
        val conversationId = (currentViewState.pendingAction as? ConversationsViewPendingAction.ShareConversation)?.conversationId
        if (conversationId == null) {
            logger.error("Failed to share the conversation because there was no pending action for it")
            emitEvent(ConversationsViewEvent.InternalError)
            return@runAction
        }
        completePendingAction()
        emitEvent(
            event = ConversationsViewEvent.OnShareConversation(conversationId),
        )
    }

    fun shareConversation(conversationId: ConversationId, password: String, includeMedia: Boolean) = runAction {
        val conversationModel: ConversationModel? = conversationService.getCached(conversationId)
        if (conversationModel == null) {
            logger.error("Failed to share conversation because the model is missing")
            emitEvent(ConversationsViewEvent.InternalError)
            return@runAction
        }
        val exportResult: ExportConversationToFileUseCase.Result = exportConversationToFileUseCase.call(
            conversationModel = conversationModel,
            password = password,
            includeMedia = includeMedia,
        )
        val event: ConversationsViewEvent? = when (exportResult) {
            is ExportConversationToFileUseCase.Result.Success -> {
                updateViewState {
                    copy(
                        pendingAction = ConversationsViewPendingAction.DeleteSharedConversationFile(exportResult.file),
                    )
                }
                ConversationsViewEvent.OnConversationFileReadyForSharing(exportResult.file)
            }
            ExportConversationToFileUseCase.Result.Failure -> {
                logger.error("Failed to share conversation messages file")
                ConversationsViewEvent.OnFailedToCreateConversationFileForSharing
            }
            ExportConversationToFileUseCase.Result.Cancelled -> null
        }
        if (event != null) {
            emitEvent(event)
        }
    }

    fun onClickCancelSharing() {
        exportConversationService.cancel()
    }

    /**
     *  After the sharing intent is done (the user shared the file, or just canceled it), we have to delete the temporary file.
     *
     *  We cannot delete the file immediately as some apps (e.g. Dropbox) take some time until they read the file after the intent has been completed.
     *  As we can't know for sure when they're done, we simply wait two minutes before we delete the temporary file.
     *
     *  If this deletion fails, the file will still eventually be deleted by [ch.threema.app.files.TempFilesCleanupWorker].
     */
    fun onSharingIntentCompleted() = runAction {
        val conversationFile = (currentViewState.pendingAction as? ConversationsViewPendingAction.DeleteSharedConversationFile?)?.file
        if (conversationFile == null) {
            logger.warn("Could not delete temporary conversation file after sharing intent was done because the pending action is missing")
            return@runAction
        }
        completePendingAction()
        val conversationFilePath: String = conversationFile.absolutePath

        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch {
            try {
                delay(2.minutes)
            } finally {
                File(conversationFilePath).deleteSecurely(applicationContext)
            }
        }
    }

    fun leaveGroup(intent: GroupLeaveIntent, groupDatabaseId: GroupDatabaseId?) = runAction {
        if (groupDatabaseId == null) {
            logger.error("Cannot leave group: groupDatabaseId is null")
            emitEvent(ConversationsViewEvent.OnLeaveGroupFailedInternally)
            return@runAction
        }
        val groupModel = groupModelRepository.getByGroupDatabaseId(groupDatabaseId)
        if (groupModel == null) {
            logger.error("Cannot leave group: groupModel is missing")
            emitEvent(ConversationsViewEvent.OnLeaveGroupFailedInternally)
            return@runAction
        }
        emitEvent(
            event = ConversationsViewEvent.OnLeavingGroup,
        )
        groupFlowDispatcher
            .runLeaveGroupFlow(
                intent = intent,
                groupModel = groupModel,
            )
            .onCompleted(
                onCompletedExceptionally = { exception: Throwable? ->
                    logger.error("leave-group-flow was completed exceptionally", exception)
                    runAction {
                        emitEvent(
                            event = ConversationsViewEvent.OnLeaveGroupCompleted(
                                result = GroupFlowResult.Failure.Other,
                            ),
                        )
                    }
                },
                onCompletedNormally = { result: GroupFlowResult? ->
                    if (result == null) {
                        logger.error("leave-group-flow was completed without a result")
                    }
                    runAction {
                        emitEvent(
                            event = if (result != null) {
                                ConversationsViewEvent.OnLeaveGroupCompleted(result)
                            } else {
                                ConversationsViewEvent.OnLeaveGroupFailedInternally
                            },
                        )
                    }
                },
            )
    }

    fun disbandGroup(intent: GroupDisbandIntent, groupDatabaseId: GroupDatabaseId?) = runAction {
        if (groupDatabaseId == null) {
            logger.error("Cannot disband group: groupDatabaseId is null")
            emitEvent(ConversationsViewEvent.OnDisbandGroupFailedInternally)
            return@runAction
        }
        val groupModel = groupModelRepository.getByGroupDatabaseId(groupDatabaseId)
        if (groupModel == null) {
            logger.error("Cannot disband group: groupModel is missing")
            emitEvent(ConversationsViewEvent.OnDisbandGroupFailedInternally)
            return@runAction
        }
        emitEvent(
            event = ConversationsViewEvent.OnDisbandingGroup,
        )
        groupFlowDispatcher
            .runDisbandGroupFlow(
                intent = intent,
                groupModel = groupModel,
            ).onCompleted(
                onCompletedExceptionally = { exception: Throwable? ->
                    logger.error("disband-group-flow was completed exceptionally", exception)
                    runAction {
                        emitEvent(
                            event = ConversationsViewEvent.OnDisbandGroupCompleted(
                                result = GroupFlowResult.Failure.Other,
                            ),
                        )
                    }
                },
                onCompletedNormally = { result: GroupFlowResult? ->
                    if (result == null) {
                        logger.error("disband-group-flow was completed without a result")
                    }
                    runAction {
                        emitEvent(
                            event = if (result != null) {
                                ConversationsViewEvent.OnDisbandGroupCompleted(result)
                            } else {
                                ConversationsViewEvent.OnDisbandGroupFailedInternally
                            },
                        )
                    }
                },
            )
    }

    fun removeGroup(groupDatabaseId: GroupDatabaseId?) = runAction {
        if (groupDatabaseId == null) {
            logger.error("Cannot remove group: groupDatabaseId is null")
            emitEvent(ConversationsViewEvent.OnRemoveGroupFailedInternally)
            return@runAction
        }
        val groupModel = groupModelRepository.getByGroupDatabaseId(groupDatabaseId)
            ?: run {
                // Group already removed
                return@runAction
            }
        val myIdentity = identityProvider.getIdentity() ?: run {
            logger.error("Cannot remove group if the identity is null")
            emitEvent(ConversationsViewEvent.OnRemoveGroupFailedInternally)
            return@runAction
        }

        val groupModelData: GroupModelData = groupModel.data
            ?: return@runAction
        if (groupModelData.isMember) {
            // Disband or leave if the user is still part of the group.
            if (groupModelData.groupIdentity.creatorIdentity == myIdentity.value) {
                disbandGroup(
                    intent = GroupDisbandIntent.DISBAND_AND_REMOVE,
                    groupDatabaseId = groupDatabaseId,
                )
            } else {
                leaveGroup(
                    intent = GroupLeaveIntent.LEAVE_AND_REMOVE,
                    groupDatabaseId = groupDatabaseId,
                )
            }
        } else {
            // Just remove the group
            emitEvent(ConversationsViewEvent.OnRemovingGroup)
            groupFlowDispatcher
                .runRemoveGroupFlow(groupModel)
                .onCompleted(
                    onCompletedExceptionally = { exception: Throwable? ->
                        logger.error("remove-group-flow was completed exceptionally", exception)
                        runAction {
                            emitEvent(
                                event = ConversationsViewEvent.OnRemoveGroupFailedInternally,
                            )
                        }
                    },
                    onCompletedNormally = { result: GroupFlowResult? ->
                        if (result == null) {
                            logger.error("remove-group-flow was completed without a result")
                        }
                        runAction {
                            emitEvent(
                                event = if (result != null) {
                                    ConversationsViewEvent.OnRemoveGroupCompleted(result)
                                } else {
                                    ConversationsViewEvent.OnRemoveGroupFailedInternally
                                },
                            )
                        }
                    },
                )
        }
    }

    private suspend fun completePendingAction() {
        updateViewState {
            copy(
                pendingAction = null,
            )
        }
    }

    fun onClickContactSupport() {
        if (addOrUpdateSupportContactJob?.isActive == true || BuildFlavor.current.isOnPrem) {
            return
        }
        addOrUpdateSupportContactJob = launchAction {
            // Failed case needs to be checked first, as the result can be both Failed and ContactAvailable at the same time
            // See the definition of `LocalPublicKeyMismatch`
            val event = when (val contactResult: ContactResult = addOrUpdateSupportContact()) {
                is Failed -> ConversationsViewEvent.OnSupportContactUnavailable(contactResult.message)
                is ContactAvailable -> ConversationsViewEvent.OnSupportContactAvailable(
                    conversationId = ContactConversationId(
                        identity = contactResult.contactModel.identity,
                    ),
                )
            }
            emitEvent(event)
        }
    }

    private suspend fun addOrUpdateSupportContact(): ContactResult {
        return withContext(dispatcherProvider.worker) {
            AddOrUpdateSupportContactBackgroundTask(
                validContactsLookupSteps = validContactsLookupSteps,
                contactModelRepository = contactModelRepository,
                appRestrictions = appRestrictions,
            ).runSynchronously()
        }
    }
}
