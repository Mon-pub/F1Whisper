package ch.threema.app.conversations

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.annotation.StringRes
import androidx.annotation.UiThread
import androidx.appcompat.widget.SearchView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResultListener
import ch.threema.android.buildActivityIntent
import ch.threema.android.getParcelableCompat
import ch.threema.android.registerPermissionResultContract
import ch.threema.app.AppConstants
import ch.threema.app.AppConstants.MAX_PW_LENGTH_BACKUP
import ch.threema.app.AppConstants.MIN_PW_LENGTH_BACKUP
import ch.threema.app.BuildConfig
import ch.threema.app.BuildFlavor
import ch.threema.app.R
import ch.threema.app.activities.ChatFoldersActivity
import ch.threema.app.activities.ComposeMessageActivity
import ch.threema.app.activities.DistributionListAddActivity
import ch.threema.app.activities.GroupDetailActivity
import ch.threema.app.activities.RecipientListBaseActivity
import ch.threema.app.activities.ThreemaActivity
import ch.threema.app.applock.AppLockUtil
import ch.threema.app.applock.CheckAppLockContract
import ch.threema.app.archive.ArchiveActivity
import ch.threema.app.availabilitystatus.AvailabilityStatusOwnBanner
import ch.threema.app.availabilitystatus.edit.EditAvailabilityStatusBottomSheetDialog
import ch.threema.app.compose.common.LocalDayOfYear
import ch.threema.app.compose.common.buttons.ButtonIconInfo
import ch.threema.app.compose.common.buttons.ButtonOutlined
import ch.threema.app.compose.common.buttons.primary.ExtendedFloatingActionButtonPrimary
import ch.threema.app.compose.common.list.swipe.ListItemSwipeFeature
import ch.threema.app.compose.common.list.swipe.ListItemSwipeFeatureState
import ch.threema.app.compose.common.rememberRefreshingLocalDayOfYear
import ch.threema.app.compose.common.spacer.SpacerVertical
import ch.threema.app.compose.common.text.ThemedText
import ch.threema.app.compose.conversation.ConversationListItem
import ch.threema.app.compose.conversation.models.ConversationListItemUiModel
import ch.threema.app.compose.theme.ThreemaTheme
import ch.threema.app.compose.theme.dimens.GridUnit
import ch.threema.app.contactdetails.ContactDetailActivity
import ch.threema.app.conversation.ConversationActivity
import ch.threema.app.dialogs.CancelableGenericProgressDialog
import ch.threema.app.dialogs.GenericAlertDialog
import ch.threema.app.dialogs.GenericProgressDialog
import ch.threema.app.dialogs.PasswordEntryDialog
import ch.threema.app.dialogs.SelectorDialog
import ch.threema.app.dialogs.SimpleStringAlertDialog
import ch.threema.app.dialogs.ThreemaDialogFragment.onClickPositiveButton
import ch.threema.app.dialogs.loadingtimeout.LoadingWithTimeoutDialogXml
import ch.threema.app.fragments.MainFragment
import ch.threema.app.framework.EventHandler
import ch.threema.app.framework.WithViewState
import ch.threema.app.groupflows.GROUP_FLOWS_LOADING_DIALOG_TIMEOUT_SECONDS
import ch.threema.app.groupflows.GroupDisbandIntent
import ch.threema.app.groupflows.GroupFlowResult
import ch.threema.app.groupflows.GroupLeaveIntent
import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.preference.SettingsActivity
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.preference.service.PreferenceService.EmojiStyle
import ch.threema.app.restrictions.AppRestrictions
import ch.threema.app.services.ChatFolderService
import ch.threema.app.services.ConversationCategoryService
import ch.threema.app.services.ConversationService
import ch.threema.app.services.ConversationTagService
import ch.threema.app.services.FileService
import ch.threema.app.services.LockAppService
import ch.threema.app.ui.SelectorDialogItem
import ch.threema.app.usecases.conversations.EmptyOrDeleteConversationsUseCase
import ch.threema.app.utils.ConfigUtils
import ch.threema.app.utils.DialogUtil
import ch.threema.app.utils.EditTextUtil
import ch.threema.app.utils.FileProviderUtil
import ch.threema.app.utils.MimeUtil
import ch.threema.app.voip.activities.GroupCallActivity
import ch.threema.app.widget.WidgetUpdater
import ch.threema.base.utils.getThreemaLogger
import ch.threema.common.consume
import ch.threema.common.toIntCapped
import ch.threema.data.IdentityProvider
import ch.threema.data.datatypes.AvailabilityStatus
import ch.threema.data.datatypes.ContactConversationId
import ch.threema.data.datatypes.ContactNameFormat
import ch.threema.data.datatypes.ConversationId
import ch.threema.data.datatypes.DistributionListConversationId
import ch.threema.data.datatypes.GroupConversationId
import ch.threema.domain.taskmanager.TriggerSource
import ch.threema.domain.types.Identity
import ch.threema.storage.models.ConversationModel
import ch.threema.storage.models.ConversationTag
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.lang.ref.WeakReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import org.slf4j.Logger

private val logger: Logger = getThreemaLogger("ConversationsFragment")

/**
 * This is one of the tabs in the home screen.
 *
 * It shows the current conversations.
 */
class ConversationsFragment : MainFragment() {

    private val identityProvider: IdentityProvider by inject()
    private val conversationService: ConversationService by inject()
    private val conversationCategoryService: ConversationCategoryService by inject()
    private val conversationTagService: ConversationTagService by inject()
    private val chatFolderService: ChatFolderService by inject()
    private val fileService: FileService by inject()
    private val preferenceService: PreferenceService by inject()
    private val lockAppService: LockAppService by inject()
    private val appLockUtil: AppLockUtil by inject()
    private val appRestrictions: AppRestrictions by inject()
    private val widgetUpdater: WidgetUpdater by inject()
    private val myIdentity: Identity by lazy { identityProvider.getIdentity()!! }

    private var searchView: SearchView? = null
    private var searchMenuItemRef: WeakReference<MenuItem>? = null
    private var toggleHiddenMenuItemRef: WeakReference<MenuItem>? = null

    private var restoredSearchQuery: String? = null
    private var archiveSnackbar: ArchiveSnackbar? = null

    private val viewModel: ConversationsViewModel by viewModel {
        val initiallyOpenedConversationId: ConversationId? = arguments?.getParcelableCompat(AppConstants.INTENT_DATA_CONVERSATION_ID)
        parametersOf(isMultiPaneEnabled(), initiallyOpenedConversationId)
    }

    private val queryTextListener = object : SearchView.OnQueryTextListener {
        override fun onQueryTextChange(searchQuery: String?) = consume {
            viewModel.onSearchQueryChange(searchQuery)
        }

        override fun onQueryTextSubmit(query: String?) = true
    }

    private val checkLockToShowPrivateConversationLauncher = registerForActivityResult(CheckAppLockContract()) { unlocked ->
        logger.info("Check lock to show private conversations returned: unlocked={}", unlocked)
        if (unlocked) {
            viewModel.onUnlockSuccessToShowPrivateConversations()
        }
    }
    private val checkLockToUnmarkConversationAsPrivateLauncher = registerForActivityResult(CheckAppLockContract()) { unlocked ->
        logger.info("Check lock to unmark private conversations returned: unlocked={}", unlocked)
        if (unlocked) {
            viewModel.onUnlockSuccessToUnmarkConversationAsPrivate()
        }
    }

    private val requestWriteStoragePermissionLauncher = registerPermissionResultContract { isGranted ->
        if (isGranted) {
            viewModel.onStoragePermissionGrantedToShareConversation()
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            ConfigUtils.showPermissionRationale(
                /* context = */
                context,
                /* parentLayout = */
                view,
                /* stringResource = */
                R.string.permission_storage_required,
            )
        }
    }

    private val shareConversationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onSharingIntentCompleted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logger.info("onCreate")

        setHasOptionsMenu(true)

        setFragmentResultListener(DIALOG_LOCK_MECHANISM_REQUIRED_TO_TOGGLE_PRIVATE_MARK) { _, bundle ->
            onClickPositiveButton(bundle) {
                onClickSetUpLockMechanismToTogglePrivateMark(bundle)
            }
        }
        setFragmentResultListener(DIALOG_LOCK_MECHANISM_REQUIRED_TO_HIDE) { _, bundle ->
            onClickPositiveButton(bundle) {
                onClickSetUpLockMechanismToHide()
            }
        }
        setFragmentResultListener(DIALOG_CONFIRM_TO_MARK_AS_PRIVATE) { _, bundle ->
            onClickPositiveButton(bundle) {
                onClickConfirmMarkConversationAsPrivate(bundle)
            }
        }
        setFragmentResultListener(DIALOG_CONFIRM_TO_EMPTY_CONVERSATION) { _, bundle ->
            onClickPositiveButton(bundle) {
                onClickConfirmEmptyConversation(bundle)
            }
        }
        setFragmentResultListener(DIALOG_CONFIRM_DELETE_CONTACT_CONVERSATION) { _, bundle ->
            onClickPositiveButton(bundle) {
                onClickConfirmDeleteContactConversation(bundle)
            }
        }
        setFragmentResultListener(DIALOG_CONFIRM_DELETE_DISTRIBUTION_LIST_CONVERSATION) { _, bundle ->
            onClickPositiveButton(bundle) {
                onClickConfirmDeleteDistributionListConversation(bundle)
            }
        }
        setFragmentResultListener(DIALOG_SET_PASSWORD_FOR_SHARING) { _, bundle ->
            onClickPositiveButton(bundle) {
                onClickShareConversationWithPassword(bundle)
            }
        }
        setFragmentResultListener(DIALOG_PREPARING_MESSAGES_FOR_SHARING) { _, bundle ->
            onClickPositiveButton(bundle) {
                viewModel.onClickCancelSharing()
            }
        }
        setFragmentResultListener(DIALOG_CONFIRM_LEAVE_GROUP) { _, bundle ->
            onClickPositiveButton(bundle) {
                onClickConfirmLeaveGroup(bundle)
            }
        }
        setFragmentResultListener(DIALOG_CONFIRM_DISSOLVE_GROUP) { _, bundle ->
            onClickPositiveButton(bundle) {
                onClickConfirmDissolveGroup(bundle)
            }
        }
        setFragmentResultListener(DIALOG_CONFIRM_DELETE_MY_GROUP) { _, bundle ->
            onClickPositiveButton(bundle) {
                onClickConfirmRemoveGroup(bundle)
            }
        }
        setFragmentResultListener(DIALOG_CONFIRM_DELETE_GROUP) { _, bundle ->
            onClickPositiveButton(bundle) {
                onClickConfirmRemoveGroup(bundle)
            }
        }
        setFragmentResultListener(DIALOG_CONVERSATION_ITEM_ACTIONS) { _, bundle ->
            if (bundle.containsKey(SelectorDialog.BUNDLE_KEY_CLICKED_ITEM)) {
                onClickConversationAction(bundle)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        logger.info("onViewCreated")
        if (savedInstanceState != null) {
            restoredSearchQuery = savedInstanceState.getString(BUNDLE_SEARCH_QUERY)
        }
    }

    override fun onDestroyView() {
        logger.info("onDestroyView")

        searchView = null
        searchMenuItemRef?.clear()

        super.onDestroyView()
    }

    @Deprecated("Deprecated in Java")
    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        // move search item to popup if the lock item is visible
        searchMenuItemRef?.get()?.let { searchMenuItem ->
            if (lockAppService.isLockingEnabled) {
                searchMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
            } else {
                searchMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
            }
        }

        // Wrong state direction required here right now. As long as the options menu is still xml and created by the fragment
        viewModel.viewState.value?.hasPrivateConversations?.let { hasPrivateConversations ->
            setShowOrHidePrivateConversationsMenuItemVisible(
                isVisible = hasPrivateConversations,
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        logger.debug("onCreateOptionsMenu")

        if (activity == null || isMultiPaneEnabled()) {
            return
        }

        var searchMenuItem: MenuItem? = menu.findItem(R.id.menu_search_messages)
        if (searchMenuItem == null) {
            inflater.inflate(R.menu.fragment_conversations, menu)
            if (isAdded) {
                searchMenuItem = menu.findItem(R.id.menu_search_messages)
                searchView = searchMenuItem.actionView as SearchView?
                searchView?.let { searchView ->
                    searchView.setQueryHint(getString(R.string.hint_search_list))
                    searchView.setOnQueryTextListener(queryTextListener)
                    if (!restoredSearchQuery.isNullOrEmpty()) {
                        // restore search
                        searchMenuItem.expandActionView()
                        searchView.setQuery(restoredSearchQuery, false)
                        searchView.clearFocus()
                    }
                }
            }
        }

        if (searchMenuItem != null) {
            searchMenuItemRef = WeakReference<MenuItem>(searchMenuItem)
        } else {
            searchMenuItemRef?.clear()
        }
        val togglePrivateConversationsMenuItem: MenuItem? = menu.findItem(R.id.menu_toggle_private_chats)
        if (togglePrivateConversationsMenuItem != null) {
            toggleHiddenMenuItemRef = WeakReference<MenuItem>(togglePrivateConversationsMenuItem)
        } else {
            toggleHiddenMenuItemRef?.clear()
        }
        toggleHiddenMenuItemRef?.get()?.let { menuItem: MenuItem ->
            if (!isAdded) {
                return@let
            }
            menuItem.setOnMenuItemClickListener { _ ->
                consume {
                    logger.info("Clicked on menu item to toggle hide/show private conversations")
                    viewModel.onClickHideOrShowPrivateConversations()
                }
            }
        }
    }

    private fun openConversation(conversationId: ConversationId) {
        if (!isAdded) {
            return
        }

        // Close keyboard if search view is expanded
        if (searchView?.isIconified == false) {
            EditTextUtil.hideSoftKeyboard(searchView)
        }

        val openConversationScreenIntent = ComposeMessageActivity.createIntent(
            context = requireActivity(),
            conversationId = conversationId,
        )
        if (isMultiPaneEnabled()) {
            openConversationScreenIntent.setFlags(
                Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            startActivity(openConversationScreenIntent)
            requireActivity().overridePendingTransition(0, 0)
        } else {
            requireActivity().startActivity(openConversationScreenIntent)
        }
    }

    private fun onOpenConversationActionDialog(conversationModel: ConversationModel) {
        val labels: MutableList<SelectorDialogItem> = mutableListOf()
        val tags: MutableList<Int> = mutableListOf()

        val receiver: MessageReceiver<*> = try {
            conversationModel.messageReceiver
        } catch (e: Exception) {
            logger.error("Could not get receiver of conversation model", e)
            return
        }

        val isPrivate: Boolean = conversationCategoryService.isMarkedAsPrivate(
            conversationId = conversationModel.id,
        )
        val isMarkedAsUnread: Boolean = conversationTagService.isTaggedWith(
            conversationId = conversationModel.id,
            tag = ConversationTag.MARKED_AS_UNREAD,
        )

        if (conversationModel.hasUnreadMessage() || isMarkedAsUnread) {
            labels.add(SelectorDialogItem(getString(R.string.mark_read), R.drawable.ic_visibility))
            tags.add(TAG_MARK_READ)
        } else {
            labels.add(SelectorDialogItem(getString(R.string.mark_unread), R.drawable.ic_visibility_off))
            tags.add(TAG_MARK_UNREAD)
        }

        if (isPrivate) {
            labels.add(SelectorDialogItem(getString(R.string.unset_private), R.drawable.ic_lock_strikethrough))
            tags.add(TAG_UNMARK_AS_PRIVATE)
        } else {
            labels.add(SelectorDialogItem(getString(R.string.set_private), R.drawable.ic_lock))
            tags.add(TAG_MARK_AS_PRIVATE)
        }

        if (!isPrivate && !appRestrictions.isExportDisabled()) {
            labels.add(SelectorDialogItem(getString(R.string.share_chat), R.drawable.ic_share_outline))
            tags.add(TAG_SHARE)
        }

        labels.add(SelectorDialogItem(getString(R.string.add_to_folder), R.drawable.ic_folder_outline))
        tags.add(TAG_ADD_TO_FOLDER)

        labels.add(SelectorDialogItem(getString(R.string.archive_chat), R.drawable.ic_archive))
        tags.add(TAG_ARCHIVE_CONVERSATION)

        if (conversationModel.messageCount > 0) {
            labels.add(SelectorDialogItem(getString(R.string.empty_chat_title), R.drawable.ic_outline_delete_sweep))
            tags.add(TAG_EMPTY_CONVERSATION)
        }
        if (conversationModel.isContactConversation) {
            labels.add(SelectorDialogItem(getString(R.string.delete_chat_title), R.drawable.ic_delete_outline))
            tags.add(TAG_DELETE_CONTACT_CONVERSATION)
        }

        if (conversationModel.isDistributionListConversation) {
            // distribution lists
            labels.add(SelectorDialogItem(getString(R.string.really_delete_distribution_list), R.drawable.ic_delete_outline))
            tags.add(TAG_DELETE_DISTRIBUTION_LIST)
        } else if (conversationModel.isGroupConversation) {
            // group conversations
            val groupModel = conversationModel.groupModel ?: run {
                logger.error("Cannot access the group from the conversation model")
                return
            }
            val isCreator: Boolean = groupModel.isCreator()
            val isMember: Boolean = groupModel.isMember()
            // Check also if the user is a group member, because left groups should not be editable.
            if (groupModel.isCreator() && groupModel.isMember()) {
                labels.add(SelectorDialogItem(getString(R.string.group_edit_title), R.drawable.ic_pencil))
                tags.add(TAG_EDIT_GROUP)
            }
            // Members (except the creator) can leave the group
            if (groupModel.isLeavable()) {
                labels.add(SelectorDialogItem(getString(R.string.action_leave_group), R.drawable.ic_outline_directions_run))
                tags.add(TAG_LEAVE_GROUP)
            }
            if (groupModel.isDisbandable()) {
                labels.add(SelectorDialogItem(getString(R.string.action_dissolve_group), R.drawable.ic_outline_directions_run))
                tags.add(TAG_DISSOLVE_GROUP)
            }
            labels.add(SelectorDialogItem(getString(R.string.action_delete_group), R.drawable.ic_delete_outline))
            if (isMember) {
                if (isCreator) {
                    tags.add(TAG_DELETE_MY_GROUP)
                } else {
                    tags.add(TAG_DELETE_GROUP)
                }
            } else {
                tags.add(TAG_DELETE_LEFT_GROUP)
            }
        }

        // TODO(ANDR-4613): Remove this option
        @Suppress("KotlinConstantConditions")
        if (BuildConfig.NEW_CONVERSATION_SCREEN_ENABLED) {
            labels.add(SelectorDialogItem("Open in legacy screen", R.drawable.ic_outline_login_24))
            tags.add(TAG_OPEN_IN_LEGACY_SCREEN)
        }

        val selectorDialog: SelectorDialog = SelectorDialog.newInstance(
            /* title = */
            receiver.getDisplayName(preferenceService.getContactNameFormat()),
            /* items = */
            ArrayList(labels),
            /* tags = */
            ArrayList(tags),
            /* negative = */
            getString(R.string.cancel),
            /* requestKey = */
            DIALOG_CONVERSATION_ITEM_ACTIONS,
        )
        val requestData = bundleOf(
            DIALOG_BUNDLE_KEY_CONVERSATION_ID to conversationModel.id,
        )
        selectorDialog.setRequestData(requestData)
        selectorDialog.show(parentFragmentManager)
    }

    private fun onClickConversationAction(bundle: Bundle) {
        val clickedItem: Int? = bundle
            .getInt(SelectorDialog.BUNDLE_KEY_CLICKED_ITEM, -1)
            .takeIf { it != -1 }
        if (clickedItem == null) {
            logger.error("Missing required clicked-item value in dialog result extras")
            return
        }

        fun getConversationId(): ConversationId? {
            val conversationId: ConversationId? = bundle.getParcelableCompat(DIALOG_BUNDLE_KEY_CONVERSATION_ID)
            if (conversationId == null) {
                logger.error("Missing required conversation-id in dialog result extras")
            }
            return conversationId
        }

        fun getGroupConversationId(): GroupConversationId? {
            val groupConversationId: GroupConversationId? = getConversationId() as? GroupConversationId?
            if (groupConversationId == null) {
                logger.error("Missing required group-conversation-id in dialog result extras")
            }
            return groupConversationId
        }

        when (clickedItem) {
            TAG_ARCHIVE_CONVERSATION -> {
                logger.info("Clicked on button to archive conversation")
                getConversationId()?.let(viewModel::onClickArchiveConversation)
            }

            TAG_ADD_TO_FOLDER -> {
                logger.info("Clicked on button to add conversation to folder")
                getConversationId()?.let(::showChatFolderSelectDialog)
            }

            TAG_EMPTY_CONVERSATION -> {
                logger.info("Clicked on button to empty conversation")
                getConversationId()?.let(viewModel::onClickEmptyConversation)
            }

            TAG_DELETE_CONTACT_CONVERSATION -> {
                logger.info("Clicked on button to delete contact conversation")
                getConversationId()?.let(viewModel::onClickDeleteContactConversation)
            }

            TAG_DELETE_DISTRIBUTION_LIST -> {
                logger.info("Clicked on button to delete distribution list conversation")
                getConversationId()?.let(viewModel::onClickDeleteDistributionListConversation)
            }

            TAG_EDIT_GROUP -> {
                logger.info("Clicked on button to edit group")
                getGroupConversationId()?.let { groupConversationId ->
                    val intentGroupDetail = GroupDetailActivity.createIntent(
                        /* context = */
                        requireActivity(),
                        /* groupDatabaseId = */
                        groupConversationId.groupDatabaseId,
                    )
                    requireActivity().startActivity(intentGroupDetail)
                }
            }

            TAG_LEAVE_GROUP -> {
                logger.info("Clicked on button to leave group")
                getGroupConversationId()?.let { groupConversationId ->
                    GenericAlertDialog.newInstance(
                        /* title = */
                        R.string.action_leave_group,
                        /* message = */
                        R.string.really_leave_group_message,
                        /* positive = */
                        R.string.ok,
                        /* negative = */
                        R.string.cancel,
                        /* requestKey = */
                        DIALOG_CONFIRM_LEAVE_GROUP,
                    ).setRequestData(
                        bundleOf(
                            DIALOG_BUNDLE_KEY_CONVERSATION_ID to groupConversationId,
                        ),
                    ).show(parentFragmentManager)
                }
            }

            TAG_DISSOLVE_GROUP -> {
                logger.info("Clicked on button to dissolve group")
                getGroupConversationId()?.let { groupConversationId ->
                    GenericAlertDialog.newInstance(
                        /* title = */
                        R.string.action_dissolve_group,
                        /* message = */
                        R.string.really_dissolve_group,
                        /* positive = */
                        R.string.ok,
                        /* negative = */
                        R.string.cancel,
                        /* requestKey = */
                        DIALOG_CONFIRM_DISSOLVE_GROUP,
                    ).setRequestData(
                        bundleOf(
                            DIALOG_BUNDLE_KEY_CONVERSATION_ID to groupConversationId,
                        ),
                    ).show(parentFragmentManager)
                }
            }

            TAG_DELETE_MY_GROUP -> {
                logger.info("Clicked on button to delete own group")
                getGroupConversationId()?.let { groupConversationId ->
                    GenericAlertDialog.newInstance(
                        /* title = */
                        R.string.action_dissolve_and_delete_group,
                        /* message = */
                        R.string.delete_my_group_message,
                        /* positive = */
                        R.string.ok,
                        /* negative = */
                        R.string.cancel,
                        /* requestKey = */
                        DIALOG_CONFIRM_DELETE_MY_GROUP,
                    ).setRequestData(
                        bundleOf(
                            DIALOG_BUNDLE_KEY_CONVERSATION_ID to groupConversationId,
                        ),
                    ).show(parentFragmentManager)
                }
            }

            TAG_DELETE_GROUP -> {
                logger.info("Clicked on button to delete group")
                getGroupConversationId()?.let { groupConversationId ->
                    GenericAlertDialog.newInstance(
                        /* title = */
                        R.string.action_delete_group,
                        /* message = */
                        R.string.delete_group_message,
                        /* positive = */
                        R.string.ok,
                        /* negative = */
                        R.string.cancel,
                        /* requestKey = */
                        DIALOG_CONFIRM_DELETE_GROUP,
                    ).setRequestData(
                        bundleOf(
                            DIALOG_BUNDLE_KEY_CONVERSATION_ID to groupConversationId,
                        ),
                    ).show(parentFragmentManager)
                }
            }

            TAG_DELETE_LEFT_GROUP -> {
                logger.info("Clicked on button to delete already left group")
                getGroupConversationId()?.let { groupConversationId ->
                    GenericAlertDialog.newInstance(
                        /* title = */
                        R.string.action_delete_group,
                        /* message = */
                        R.string.delete_left_group_message,
                        /* positive = */
                        R.string.ok,
                        /* negative = */
                        R.string.cancel,
                        /* requestKey = */
                        DIALOG_CONFIRM_DELETE_GROUP,
                    ).setRequestData(
                        bundleOf(
                            DIALOG_BUNDLE_KEY_CONVERSATION_ID to groupConversationId,
                        ),
                    ).show(parentFragmentManager)
                }
            }

            TAG_MARK_AS_PRIVATE -> {
                logger.info("Clicked on button mark conversation as private")
                getConversationId()?.let(viewModel::onClickMarkConversationAsPrivate)
            }

            TAG_UNMARK_AS_PRIVATE -> {
                logger.info("Clicked on button to remove private mark from conversation")
                getConversationId()?.let(viewModel::onClickUnmarkConversationAsPrivate)
            }

            TAG_SHARE -> {
                logger.info("Clicked on button to share conversation")
                getConversationId()?.let(::onClickShareConversation)
            }

            TAG_MARK_READ -> {
                logger.info("Clicked on button to mark conversation as read")
                getConversationId()?.let(viewModel::onClickMarkConversationAsRead)
            }

            TAG_MARK_UNREAD -> {
                logger.info("Clicked button to mark conversation as unread")
                getConversationId()?.let(viewModel::onClickMarkConversationAsUnread)
            }

            // TODO(ANDR-4613): Remove this option
            TAG_OPEN_IN_LEGACY_SCREEN -> {
                getConversationId()?.let { conversationId ->
                    val legacyIntent = buildActivityIntent<ComposeMessageActivity>(requireContext()) {
                        putExtra(AppConstants.INTENT_DATA_CONVERSATION_ID, conversationId)
                    }
                    requireActivity().startActivity(legacyIntent)
                }
            }
        }
    }

    private fun onConversationArchived(conversationModel: ConversationModel) {
        archiveSnackbar = ArchiveSnackbar(archiveSnackbar, conversationModel)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_CODE_SET_UP_LOCK_MECHANISM_TO_MARK_CONVERSATION_PRIVATE -> {
                viewModel.onLockMechanismConfiguredToMarkConversationAsPrivate()
            }

            REQUEST_CODE_SET_UP_LOCK_MECHANISM_TO_UN_MARK_CONVERSATION_PRIVATE -> {
                viewModel.onLockMechanismConfiguredToUnmarkConversationAsPrivate()
            }

            REQUEST_CODE_SET_UP_LOCK_MECHANISM_TO_HIDE_PRIVATE_CONVERSATIONS -> viewModel.onLockMechanismConfiguredToHidePrivateConversations()

            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    @UiThread
    private fun onConversationFileReadyForSharing(file: File) {
        DialogUtil.dismissDialog(
            /* fragmentManager = */
            parentFragmentManager,
            /* tag = */
            DIALOG_TAG_PREPARING_MESSAGES,
            /* allowStateLoss = */
            true,
        )
        val intent: Intent = Intent(Intent.ACTION_SEND).apply {
            setType(MimeUtil.MIME_TYPE_ZIP)
            putExtra(
                Intent.EXTRA_SUBJECT,
                resources.getString(R.string.share_subject, getString(R.string.app_name)),
            )
            putExtra(
                Intent.EXTRA_TEXT,
                getString(R.string.chat_history_attached) + "\n\n" + getString(R.string.share_conversation_body),
            )
            putExtra(
                Intent.EXTRA_STREAM,
                FileProviderUtil.getUriForFile(requireContext(), file),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        shareConversationLauncher.launch(
            input = Intent.createChooser(
                /* target = */
                intent,
                /* title = */
                getString(R.string.share_via),
            ),
        )
    }

    @UiThread
    private fun onFailedToCreateConversationFileForSharing() {
        DialogUtil.dismissDialog(
            /* fragmentManager = */
            parentFragmentManager,
            /* tag = */
            DIALOG_TAG_PREPARING_MESSAGES,
            /* allowStateLoss = */
            true,
        )
        showSharingConversationFailedDialog()
    }

    @UiThread
    private fun showLockMechanismRequiredToUpdateConversationPrivateMarkDialog(targetValueIsMarkedAsPrivate: Boolean) {
        logger.info("Showing dialog to explain private conversations")
        GenericAlertDialog
            .newInstance(
                /* title = */
                R.string.hide_chat,
                /* message = */
                R.string.hide_chat_message_explain,
                /* positive = */
                R.string.set_lock,
                /* negative = */
                R.string.cancel,
                /* requestKey = */
                DIALOG_LOCK_MECHANISM_REQUIRED_TO_TOGGLE_PRIVATE_MARK,
            ).setRequestData(
                bundleOf(
                    DIALOG_BUNDLE_KEY_TARGET_VALUE to targetValueIsMarkedAsPrivate,
                ),
            ).show(parentFragmentManager)
    }

    @UiThread
    private fun showConfirmationDialogToMarkConversationAsPrivate(conversationId: ConversationId) {
        logger.info("Showing dialog to confirm mark conversation as private")
        GenericAlertDialog
            .newInstance(
                /* title = */
                R.string.hide_chat,
                /* message = */
                R.string.really_hide_chat_message,
                /* positive = */
                R.string.ok,
                /* negative = */
                R.string.cancel,
                /* requestKey = */
                DIALOG_CONFIRM_TO_MARK_AS_PRIVATE,
            ).setRequestData(
                bundleOf(
                    DIALOG_BUNDLE_KEY_CONVERSATION_ID to conversationId,
                ),
            ).show(parentFragmentManager)
    }

    private fun onUnlockRequiredToUnmarkConversationAsPrivate() {
        checkLockToUnmarkConversationAsPrivateLauncher.launch()
    }

    @UiThread
    private fun showShareConversationSetPasswordDialog(conversationId: ConversationId) {
        PasswordEntryDialog
            .newInstance(
                /* title = */
                R.string.share_chat,
                /* message = */
                R.string.enter_zip_password_body,
                /* hint = */
                R.string.password_hint,
                /* positive = */
                R.string.ok,
                /* negative = */
                R.string.cancel,
                /* minLength = */
                MIN_PW_LENGTH_BACKUP,
                /* maxLength = */
                MAX_PW_LENGTH_BACKUP,
                /* confirmHint = */
                R.string.backup_password_again_summary,
                /* inputType = */
                0,
                /* checkboxText = */
                R.string.backup_data_media,
                /* showForgotPwHint = */
                PasswordEntryDialog.ForgotHintType.NONE,
                /* requestKey = */
                DIALOG_SET_PASSWORD_FOR_SHARING,
            ).setRequestData(
                bundleOf(
                    DIALOG_BUNDLE_KEY_CONVERSATION_ID to conversationId,
                ),
            ).show(parentFragmentManager)
    }

    @UiThread
    private fun showConfirmationDialogBeforeEmpty(conversationId: ConversationId) {
        GenericAlertDialog
            .newInstance(
                /* title = */
                R.string.empty_chat_title,
                /* message = */
                R.string.empty_chat_confirm,
                /* positive = */
                R.string.ok,
                /* negative = */
                R.string.cancel,
                /* requestKey = */
                DIALOG_CONFIRM_TO_EMPTY_CONVERSATION,
            ).setRequestData(
                bundleOf(
                    DIALOG_BUNDLE_KEY_CONVERSATION_ID to conversationId,
                ),
            ).show(parentFragmentManager)
    }

    @UiThread
    private fun showConfirmDeleteContactConversationDialog(conversationId: ConversationId) {
        GenericAlertDialog
            .newInstance(
                /* title = */
                R.string.delete_chat_title,
                /* message = */
                R.string.delete_chat_confirm,
                /* positive = */
                R.string.ok,
                /* negative = */
                R.string.cancel,
                /* requestKey = */
                DIALOG_CONFIRM_DELETE_CONTACT_CONVERSATION,
            ).setRequestData(
                bundleOf(
                    DIALOG_BUNDLE_KEY_CONVERSATION_ID to conversationId,
                ),
            ).show(parentFragmentManager)
    }

    @UiThread
    private fun showConfirmDeleteDistributionListConversationDialog(conversationId: ConversationId) {
        GenericAlertDialog
            .newInstance(
                /* title = */
                R.string.really_delete_distribution_list,
                /* message = */
                R.string.really_delete_distribution_list_message,
                /* positive = */
                R.string.ok,
                /* negative = */
                R.string.cancel,
                /* requestKey = */
                DIALOG_CONFIRM_DELETE_DISTRIBUTION_LIST_CONVERSATION,
            ).setRequestData(
                bundleOf(
                    DIALOG_BUNDLE_KEY_CONVERSATION_ID to conversationId,
                ),
            ).show(parentFragmentManager)
    }

    @UiThread
    private fun showSharingConversationFailedDialog() {
        SimpleStringAlertDialog
            .newInstance(
                /* title = */
                R.string.share_via,
                /* message = */
                getString(R.string.an_error_occurred),
            )
            .show(parentFragmentManager)
    }

    private fun requestStoragePermissionToShareConversation() {
        val permissionWasGrantedInMeantime = ConfigUtils.hasWriteStoragePermission(requireContext())
        if (permissionWasGrantedInMeantime) {
            viewModel.onStoragePermissionGrantedToShareConversation()
            return
        }
        requestWriteStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun onLockMechanismRequiredToHidePrivateConversationsEvent() {
        GenericAlertDialog.newInstance(
            /* title = */
            R.string.hide_chat,
            /* message = */
            R.string.hide_chat_message_explain,
            /* positive = */
            R.string.set_lock,
            /* negative = */
            R.string.cancel,
            /* requestKey = */
            DIALOG_LOCK_MECHANISM_REQUIRED_TO_HIDE,
        ).show(parentFragmentManager)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()

                EventHandler(viewModel) { event ->
                    handleEvent(
                        event = event,
                        requestShowSnackbar = { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                    )
                }

                val contentWindowInsets: WindowInsets =
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(
                            if (isMultiPaneEnabled()) {
                                WindowInsetsSides.Left + WindowInsetsSides.Bottom
                            } else {
                                WindowInsetsSides.Horizontal
                            },
                        )

                ThreemaTheme {
                    val lazyListState = rememberLazyListState()

                    Scaffold(
                        snackbarHost = {
                            SnackbarHost(snackbarHostState)
                        },
                        contentWindowInsets = contentWindowInsets,
                        floatingActionButton = {
                            ExtendedFloatingActionButtonPrimary(
                                expanded = lazyListState.isScrollingUp(),
                                icon = ButtonIconInfo(
                                    iconRes = R.drawable.ic_chat_bubble,
                                    contentDescription = R.string.title_compose_message,
                                ),
                                text = stringResource(R.string.title_compose_message),
                                onClick = ::onFABClicked,
                            )
                        },
                    ) { insetsPadding ->
                        WithViewState(viewModel) { conversationsViewState ->
                            if (conversationsViewState != null) {
                                LaunchedEffect(conversationsViewState.hasPrivateConversations) {
                                    setShowOrHidePrivateConversationsMenuItemVisible(
                                        isVisible = conversationsViewState.hasPrivateConversations,
                                    )
                                }

                                val emojiStyle: Int = remember {
                                    preferenceService.getEmojiStyle()
                                }

                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (conversationsViewState.availabilityStatus is AvailabilityStatus.Set) {
                                        val layoutDirection = LocalLayoutDirection.current
                                        AvailabilityStatusOwnBanner(
                                            modifier = Modifier
                                                .padding(
                                                    start = insetsPadding.calculateStartPadding(layoutDirection),
                                                    end = insetsPadding.calculateEndPadding(layoutDirection),
                                                )
                                                .padding(
                                                    all = GridUnit.x1,
                                                )
                                                .widthIn(
                                                    max = 550.dp,
                                                )
                                                .fillMaxWidth(),
                                            status = conversationsViewState.availabilityStatus,
                                            onClickEdit = {
                                                EditAvailabilityStatusBottomSheetDialog
                                                    .newInstance()
                                                    .show(
                                                        /* manager = */
                                                        parentFragmentManager,
                                                        /* tag = */
                                                        "edit-availability-status-from-conversations-list",
                                                    )
                                            },
                                            emojiStyle = emojiStyle,
                                        )
                                    }

                                    FolderChipRow(
                                        folders = conversationsViewState.folders,
                                        selectedFolderId = conversationsViewState.selectedFolderId,
                                        onClickFolder = { folderId ->
                                            viewModel.onFolderSelected(folderId)
                                        },
                                    )

                                    when (val itemsState = conversationsViewState.itemsState) {
                                        is ItemsState.Loaded -> {
                                            if (itemsState.items.isNotEmpty()) {
                                                ConversationList(
                                                    insetsPadding = insetsPadding,
                                                    myIdentity = myIdentity,
                                                    lazyListState = lazyListState,
                                                    emojiStyle = emojiStyle,
                                                    items = itemsState.items,
                                                    contactNameFormat = conversationsViewState.contactNameFormat,
                                                    archivedCount = conversationsViewState.archivedConversationsCount,
                                                    searchQuery = conversationsViewState.searchQuery,
                                                )
                                            }

                                            AnimatedVisibility(
                                                visible = itemsState.items.isEmpty(),
                                                enter = fadeIn(
                                                    animationSpec = spring(
                                                        stiffness = Spring.StiffnessVeryLow,
                                                    ),
                                                ),
                                                exit = fadeOut(
                                                    animationSpec = spring(
                                                        stiffness = Spring.StiffnessMedium,
                                                    ),
                                                ),
                                            ) {
                                                EmptyContent(
                                                    insetsPadding = insetsPadding,
                                                    conversationsViewState = conversationsViewState,
                                                )
                                            }
                                        }
                                        ItemsState.Failed -> {
                                            FailureContent(
                                                insetsPadding = insetsPadding,
                                                onClickContactSupport = viewModel::onClickContactSupport,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    /**
     *  @param requestShowSnackbar Block receiving [StringRes]
     */
    private fun handleEvent(
        event: ConversationsViewEvent,
        requestShowSnackbar: (String) -> Unit,
    ) {
        when (event) {
            is ConversationsViewEvent.OpenConversationActionDialog -> onOpenConversationActionDialog(event.conversationModel)

            is ConversationsViewEvent.ConversationArchived -> onConversationArchived(event.conversationModel)

            is ConversationsViewEvent.LockMechanismRequiredToUpdatePrivateConversationMark ->
                showLockMechanismRequiredToUpdateConversationPrivateMarkDialog(
                    targetValueIsMarkedAsPrivate = event.targetValueIsMarkedAsPrivate,
                )

            is ConversationsViewEvent.ConfirmationRequiredToMarkConversationAsPrivate ->
                showConfirmationDialogToMarkConversationAsPrivate(event.conversationId)

            ConversationsViewEvent.ConversationMarkAsPrivateSuccess -> requestShowSnackbar(getString(R.string.chat_hidden))

            ConversationsViewEvent.UnlockRequiredToUnmarkConversationAsPrivate -> onUnlockRequiredToUnmarkConversationAsPrivate()

            ConversationsViewEvent.ConversationUnmarkAsPrivateSuccess -> requestShowSnackbar(getString(R.string.chat_visible))

            ConversationsViewEvent.UnlockRequiredToShowPrivateConversations -> checkLockToShowPrivateConversationLauncher.launch()

            ConversationsViewEvent.LockMechanismRequiredToHidePrivateConversations -> onLockMechanismRequiredToHidePrivateConversationsEvent()

            is ConversationsViewEvent.ConfirmationRequiredToEmptyConversation -> showConfirmationDialogBeforeEmpty(event.conversationId)

            is ConversationsViewEvent.ConfirmationRequiredToDeleteContactConversation ->
                showConfirmDeleteContactConversationDialog(event.conversationId)

            is ConversationsViewEvent.ConfirmationRequiredToDeleteDistributionListConversation ->
                showConfirmDeleteDistributionListConversationDialog(event.conversationId)

            ConversationsViewEvent.StoragePermissionRequiredToShareConversation -> requestStoragePermissionToShareConversation()

            is ConversationsViewEvent.OnShareConversation -> showShareConversationSetPasswordDialog(event.conversationId)

            is ConversationsViewEvent.OnConversationFileReadyForSharing -> onConversationFileReadyForSharing(event.file)

            ConversationsViewEvent.OnFailedToCreateConversationFileForSharing -> onFailedToCreateConversationFileForSharing()

            ConversationsViewEvent.OnSystemLockWasRemoved -> requestShowSnackbar(getString(R.string.no_lockscreen_set))

            is ConversationsViewEvent.OnSupportContactAvailable -> openConversation(event.conversationId)

            is ConversationsViewEvent.OnSupportContactUnavailable -> requestShowSnackbar(getString(event.message))

            ConversationsViewEvent.UpdateWidgets -> widgetUpdater.updateWidgets()

            ConversationsViewEvent.InternalError -> requestShowSnackbar(getString(R.string.an_error_occurred))

            ConversationsViewEvent.OnLeaveGroupFailedInternally -> showLeaveGroupFailedDialog(
                message = R.string.error_leaving_group_internal,
            )
            ConversationsViewEvent.OnLeavingGroup -> showLeavingGroupProgressDialog()
            is ConversationsViewEvent.OnLeaveGroupCompleted -> onLeaveGroupCompleted(event.result)

            ConversationsViewEvent.OnDisbandGroupFailedInternally -> showDisbandGroupFailedDialog(
                message = R.string.error_disbanding_group_internal,
            )
            ConversationsViewEvent.OnDisbandingGroup -> showDisbandingGroupProgressDialog()

            is ConversationsViewEvent.OnDisbandGroupCompleted -> onDisbandGroupCompleted(event.result)

            ConversationsViewEvent.OnRemoveGroupFailedInternally -> showRemovingGroupFailedDialog(
                message = R.string.error_removing_group_internal,
            )
            ConversationsViewEvent.OnRemovingGroup -> showRemovingGroupProgressDialog()

            is ConversationsViewEvent.OnRemoveGroupCompleted -> onRemovingGroupComplete(event.result)

            ConversationsViewEvent.OnEmptyingConversation -> showEmptyingConversationProgressDialog()
            is ConversationsViewEvent.OnEmptyingConversationResult -> onEmptyingConversationResult(
                result = event.result,
                requestShowSnackbar = requestShowSnackbar,
            )

            ConversationsViewEvent.OnDeletingConversation -> showDeletingConversationProgressDialog()
            is ConversationsViewEvent.OnDeletingConversationResult -> onDeletingConversationResult(
                result = event.result,
                requestShowSnackbar = requestShowSnackbar,
            )
        }
    }

    @Composable
    private fun ConversationList(
        insetsPadding: PaddingValues,
        myIdentity: Identity,
        lazyListState: LazyListState,
        @EmojiStyle emojiStyle: Int,
        items: List<ConversationListItemUiModel>,
        contactNameFormat: ContactNameFormat,
        archivedCount: Long,
        searchQuery: String?,
    ) {
        val localDayOfYear: LocalDayOfYear by rememberRefreshingLocalDayOfYear()

        var wasAtTop by remember { mutableStateOf(true) }

        LaunchedEffect(lazyListState) {
            snapshotFlow {
                lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0
            }
                .distinctUntilChanged()
                .collect { isAtTop -> wasAtTop = isAtTop }
        }

        LaunchedEffect(items.firstOrNull()) {
            if (wasAtTop && items.isNotEmpty()) {
                lazyListState.scrollToItem(0)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insetsPadding)
                .testTag("lazy_column_conversations"),
            contentPadding = PaddingValues(
                bottom = GridUnit.x15,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            state = lazyListState,
        ) {
            items(
                items = items,
                key = { conversationListItemUiModel ->
                    conversationListItemUiModel.model.conversationId
                },
                contentType = { "conversation" },
            ) { conversationListItemUiModel ->
                ConversationListItem(
                    modifier = Modifier.animateItem(),
                    conversationListItemUiModel = conversationListItemUiModel,
                    avatarIteration = conversationListItemUiModel.model.avatarIteration,
                    localDayOfYear = localDayOfYear,
                    avatarBitmapProvider = viewModel::provideAvatarBitmap,
                    ownIdentity = myIdentity,
                    emojiStyle = emojiStyle,
                    contactNameFormat = contactNameFormat,
                    onClick = { conversationId ->
                        if (!isConversationOpenedInMultiPaneMode(conversationListItemUiModel)) {
                            openConversation(conversationId)
                        }
                    },
                    onLongClick = { conversationId ->
                        if (!isMultiPaneEnabled()) {
                            viewModel.onLongClickConversationItem(conversationId)
                        }
                    },
                    onClickAvatar = ::onAvatarClicked,
                    onClickJoinOrOpenGroupCall = { groupConversationId ->
                        logger.info("Join group call button clicked")
                        startActivity(
                            GroupCallActivity.createJoinCallIntent(
                                requireActivity(),
                                groupConversationId.groupDatabaseId.toInt(),
                            ),
                        )
                    },
                    swipeFeatureStartToEnd = ListItemSwipeFeature.StartToEnd(
                        onSwipe = viewModel::onSwipedListItemPin,
                        containerColor = colorResource(R.color.message_list_pin_color),
                        contentColor = Color.White,
                        state = ListItemSwipeFeatureState(
                            icon = if (conversationListItemUiModel.model.isPinned) {
                                R.drawable.ic_unpin
                            } else {
                                R.drawable.ic_pin
                            },
                            text = stringResource(
                                if (conversationListItemUiModel.model.isPinned) {
                                    R.string.unpin
                                } else {
                                    R.string.pin
                                },
                            ),
                            enabled = !isMultiPaneEnabled(),
                        ),
                    ),
                    swipeFeatureEndToStart = ListItemSwipeFeature.EndToStart(
                        onSwipe = viewModel::onSwipedListItemArchive,
                        containerColor = colorResource(R.color.message_list_archive_color),
                        contentColor = Color.White,
                        state = ListItemSwipeFeatureState(
                            icon = R.drawable.ic_archive,
                            text = stringResource(R.string.to_archive),
                            enabled = !isMultiPaneEnabled(),
                        ),
                    ),
                )
            }
            if (archivedCount > 0) {
                item(
                    key = archivedCount,
                    contentType = "archive_button",
                ) {
                    ArchivedButton(
                        modifier = Modifier.padding(top = GridUnit.x3),
                        archivedCount = archivedCount,
                        searchQuery = searchQuery,
                    )
                }
            }
        }
    }

    @Composable
    private fun EmptyContent(
        insetsPadding: PaddingValues,
        conversationsViewState: ConversationsViewState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(
                    state = rememberScrollState(),
                )
                .padding(insetsPadding)
                .padding(horizontal = GridUnit.x2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SpacerVertical(GridUnit.x4)
            Icon(
                modifier = Modifier.size(GridUnit.x7),
                painter = painterResource(R.drawable.ic_no_conversations),
                contentDescription = null,
                tint = LocalContentColor.current,
            )
            SpacerVertical(GridUnit.x2)
            ThemedText(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.no_recent_conversations),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )

            AnimatedVisibility(
                visible = conversationsViewState.archivedConversationsCount > 0L,
            ) {
                ArchivedButton(
                    modifier = Modifier
                        .padding(horizontal = GridUnit.x3)
                        .padding(top = GridUnit.x6),
                    archivedCount = conversationsViewState.archivedConversationsCount,
                    searchQuery = conversationsViewState.searchQuery,
                )
            }
            SpacerVertical(GridUnit.x15)
        }
    }

    @Composable
    private fun FailureContent(
        modifier: Modifier = Modifier,
        insetsPadding: PaddingValues,
        onClickContactSupport: () -> Unit,
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(
                    state = rememberScrollState(),
                )
                .padding(insetsPadding)
                .padding(horizontal = GridUnit.x2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SpacerVertical(GridUnit.x4)
            Icon(
                modifier = Modifier.size(GridUnit.x7),
                painter = painterResource(R.drawable.ic_error_chats),
                contentDescription = null,
                tint = LocalContentColor.current.copy(
                    alpha = 0.6f,
                ),
            )
            SpacerVertical(GridUnit.x4)
            ThemedText(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.failed_to_load_conversations),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            if (!BuildFlavor.current.isOnPrem) {
                SpacerVertical(GridUnit.x6)
                ButtonOutlined(
                    onClick = onClickContactSupport,
                    text = stringResource(R.string.contact_support),
                    maxLines = 2,
                )
            }
            SpacerVertical(GridUnit.x15)
        }
    }

    @Composable
    private fun ArchivedButton(
        modifier: Modifier = Modifier,
        archivedCount: Long,
        searchQuery: String?,
    ) {
        val archivedCountInt: Int = remember(archivedCount) {
            archivedCount.toIntCapped()
        }

        ButtonOutlined(
            modifier = modifier,
            onClick = {
                startActivity(
                    ArchiveActivity.createIntent(
                        context = requireActivity(),
                        searchQuery = searchQuery,
                    ),
                )
            },
            text = pluralStringResource(
                id = R.plurals.num_archived_chats,
                count = archivedCountInt,
                archivedCountInt,
            ),
            leadingIcon = ButtonIconInfo(
                iconRes = R.drawable.ic_archive,
            ),
        )
    }

    /**
     * Horizontally scrolling row of filter chips above the conversation list. Renders a leading "All"
     * chip, one chip per folder and a trailing "manage folders" chip. The row is hidden when no
     * folders exist so users that don't use folders see no change.
     */
    @Composable
    private fun FolderChipRow(
        folders: List<ChatFolderUiModel>,
        selectedFolderId: Long?,
        onClickFolder: (Long?) -> Unit,
    ) {
        if (folders.isEmpty()) {
            return
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 800.dp),
            contentPadding = PaddingValues(
                horizontal = GridUnit.x2,
                vertical = GridUnit.x0_5,
            ),
            horizontalArrangement = Arrangement.spacedBy(GridUnit.x1),
        ) {
            item(key = "folder-all") {
                FilterChip(
                    selected = selectedFolderId == null,
                    onClick = { onClickFolder(null) },
                    label = {
                        ThemedText(
                            text = stringResource(R.string.folder_all),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
            }
            items(
                items = folders,
                key = { folder -> folder.id },
            ) { folder ->
                FilterChip(
                    selected = selectedFolderId == folder.id,
                    onClick = { onClickFolder(folder.id) },
                    label = {
                        ThemedText(
                            text = folder.name,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
            }
        }
    }

    private fun showChatFolderSelectDialog(conversationId: ConversationId) {
        val folders = chatFolderService.getFolders()
        if (folders.isEmpty()) {
            // No folders yet, send the user straight to the manage screen to create one.
            startActivity(ChatFoldersActivity.createIntent(requireActivity()))
            return
        }
        val conversationUid = conversationId.toDatabaseValue()
        val currentFolderIds = chatFolderService.getFolderIdsForConversation(conversationUid)
        val folderNames = folders.map { it.name }.toTypedArray()
        val checkedStates = BooleanArray(folders.size) { index -> folders[index].id in currentFolderIds }

        MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.add_to_folder_title)
            .setMultiChoiceItems(folderNames, checkedStates) { _, which, isChecked ->
                checkedStates[which] = isChecked
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                val selectedFolderIds: Set<Long> = folders
                    .filterIndexed { index, _ -> checkedStates[index] }
                    .map { it.id }
                    .toSet()
                viewModel.onAddConversationToFolders(conversationId, selectedFolderIds)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun isConversationOpenedInMultiPaneMode(
        conversationListItemUiModel: ConversationListItemUiModel,
    ): Boolean {
        return isMultiPaneEnabled() && conversationListItemUiModel.isHighlighted
    }

    private fun onFABClicked() {
        logger.info("Clicked on button to create a new conversation")
        val intent: Intent = Intent(context, RecipientListBaseActivity::class.java).apply {
            putExtra(AppConstants.INTENT_DATA_HIDE_RECENTS, true)
            putExtra(RecipientListBaseActivity.INTENT_DATA_MULTISELECT, false)
            putExtra(RecipientListBaseActivity.INTENT_DATA_MULTISELECT_FOR_COMPOSE, true)
        }
        requireActivity().startActivityForResult(intent, ThreemaActivity.ACTIVITY_ID_COMPOSE_MESSAGE)
    }

    private fun onAvatarClicked(conversationId: ConversationId) {
        val intent = when (conversationId) {
            is ContactConversationId -> {
                ContactDetailActivity.createIntent(requireContext(), conversationId.identity)
            }

            is GroupConversationId -> {
                GroupDetailActivity.createIntent(requireActivity(), conversationId.groupDatabaseId)
            }

            is DistributionListConversationId -> {
                DistributionListAddActivity.createIntent(requireContext(), conversationId.distributionListId)
            }
        }
        requireActivity().startActivity(intent)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        logger.debug("*** onHiddenChanged: {}", hidden)
        if (hidden) {
            if (searchView?.isShown() == true) {
                searchMenuItemRef?.get()?.collapseActionView()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        logger.info("*** onPause")
    }

    override fun onResume() {
        logger.info("*** onResume")
        viewModel.onViewResumed(
            isAndroidSystemLockConfigured = appLockUtil.hasDeviceLock(),
        )
        super.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        logger.info("saveInstance")
        restoredSearchQuery = null
        searchView?.query?.takeIf(CharSequence::isNotBlank)?.let { existingFilterQuery ->
            outState.putString(BUNDLE_SEARCH_QUERY, existingFilterQuery.toString())
        }
        super.onSaveInstanceState(outState)
    }

    private fun onClickShareConversation(conversationId: ConversationId) {
        val hasWriteStoragePermission = ConfigUtils.hasWriteStoragePermission(requireContext())
        if (hasWriteStoragePermission) {
            showShareConversationSetPasswordDialog(conversationId)
        } else {
            viewModel.onMissingPermissionToShareConversation(conversationId)
        }
    }

    private fun setShowOrHidePrivateConversationsMenuItemVisible(isVisible: Boolean) {
        val toggleHiddenMenuItem: MenuItem? = toggleHiddenMenuItemRef?.get()
        if (isAdded && toggleHiddenMenuItem != null) {
            toggleHiddenMenuItem.isVisible = isVisible
        }
    }

    private fun isMultiPaneEnabled(): Boolean = ConfigUtils.isTabletLayout() &&
        (activity is ComposeMessageActivity || activity is ConversationActivity)

    private fun onClickSetUpLockMechanismToTogglePrivateMark(bundle: Bundle) {
        logger.info("Clicked on button to open settings screen to set up a lock mechanism (goal: toggle private mark for a conversation)")
        val intent = SettingsActivity.createIntent(requireContext(), SettingsActivity.InitialScreen.SECURITY)
        if (!bundle.containsKey(DIALOG_BUNDLE_KEY_TARGET_VALUE)) {
            return
        }
        val targetValue: Boolean = bundle.getBoolean(DIALOG_BUNDLE_KEY_TARGET_VALUE)
        val requestCode = if (targetValue) {
            REQUEST_CODE_SET_UP_LOCK_MECHANISM_TO_MARK_CONVERSATION_PRIVATE
        } else {
            REQUEST_CODE_SET_UP_LOCK_MECHANISM_TO_UN_MARK_CONVERSATION_PRIVATE
        }
        startActivityForResult(intent, requestCode)
    }

    private fun onClickConfirmMarkConversationAsPrivate(bundle: Bundle) {
        logger.info("Clicked on button to confirm marking conversation as private")
        val conversationId: ConversationId? = bundle.getParcelableCompat(DIALOG_BUNDLE_KEY_CONVERSATION_ID)
        if (conversationId != null) {
            viewModel.onClickConfirmMarkConversationAsPrivate(conversationId)
        }
    }

    private fun onClickConfirmEmptyConversation(bundle: Bundle) {
        logger.info("Clicked on confirm button to empty conversation")
        val conversationId: ConversationId? = bundle.getParcelableCompat(DIALOG_BUNDLE_KEY_CONVERSATION_ID)
        if (conversationId != null) {
            viewModel.onClickConfirmEmptyConversation(conversationId)
        }
    }

    private fun onClickShareConversationWithPassword(bundle: Bundle) {
        logger.info("Clicked on confirm button to share conversation")
        val conversationId: ConversationId? = bundle.getParcelableCompat(DIALOG_BUNDLE_KEY_CONVERSATION_ID)
        val password: String? = bundle.getString(PasswordEntryDialog.KEY_PASSWORD)
        val includeMedia: Boolean = bundle.getBoolean(PasswordEntryDialog.KEY_INCLUDE_MEDIA)
        if (conversationId != null && password != null) {
            CancelableGenericProgressDialog
                .newInstance(
                    /* title = */
                    R.string.preparing_messages,
                    /* message = */
                    0,
                    /* button = */
                    R.string.cancel,
                    /* requestKey = */
                    DIALOG_PREPARING_MESSAGES_FOR_SHARING,
                ).show(
                    /* manager = */
                    parentFragmentManager,
                    /* tag = */
                    DIALOG_TAG_PREPARING_MESSAGES,
                )
            viewModel.shareConversation(
                conversationId = conversationId,
                password = password,
                includeMedia = includeMedia,
            )
        } else {
            showSharingConversationFailedDialog()
        }
    }

    private fun onClickConfirmDeleteContactConversation(bundle: Bundle) {
        logger.info("Clicked on confirm button to delete contact conversation")
        val conversationId: ConversationId? = bundle.getParcelableCompat(DIALOG_BUNDLE_KEY_CONVERSATION_ID)
        if (conversationId != null) {
            viewModel.onClickedConfirmDeleteContactConversation(conversationId)
        }
    }

    private fun onClickConfirmDeleteDistributionListConversation(bundle: Bundle) {
        logger.info("Clicked on confirm button to delete distribution list conversation")
        val conversationId: ConversationId? = bundle.getParcelableCompat(DIALOG_BUNDLE_KEY_CONVERSATION_ID)
        if (conversationId != null) {
            viewModel.onClickedConfirmDeleteDistributionListConversation(conversationId)
        }
    }

    private fun onClickSetUpLockMechanismToHide() {
        logger.info("Clicked on button to open settings screen to set up a lock mechanism (goal: hide private conversations)")
        val intent = SettingsActivity.createIntent(requireContext(), SettingsActivity.InitialScreen.SECURITY)
        startActivityForResult(intent, REQUEST_CODE_SET_UP_LOCK_MECHANISM_TO_HIDE_PRIVATE_CONVERSATIONS)
    }

    private fun onClickConfirmLeaveGroup(bundle: Bundle) {
        logger.info("Clicked on confirm button to leave group")
        val groupConversationId: GroupConversationId? = bundle.getParcelableCompat(DIALOG_BUNDLE_KEY_CONVERSATION_ID)
        if (groupConversationId != null) {
            viewModel.leaveGroup(
                intent = GroupLeaveIntent.LEAVE,
                groupDatabaseId = groupConversationId.groupDatabaseId,
            )
        }
    }

    @UiThread
    private fun showLeaveGroupFailedDialog(@StringRes message: Int) {
        SimpleStringAlertDialog
            .newInstance(
                /* title = */
                R.string.error,
                /* message = */
                message,
            ).show(parentFragmentManager)
    }

    @UiThread
    private fun showLeavingGroupProgressDialog() {
        LoadingWithTimeoutDialogXml
            .newInstance(
                timeoutSeconds = GROUP_FLOWS_LOADING_DIALOG_TIMEOUT_SECONDS,
                titleText = R.string.leaving_group,
            ).show(
                /* manager = */
                parentFragmentManager,
                /* tag = */
                DIALOG_TAG_LEAVING_GROUP,
            )
    }

    @UiThread
    private fun dismissLeavingGroupProgressDialog() {
        DialogUtil.dismissDialog(
            /* fragmentManager = */
            parentFragmentManager,
            /* tag = */
            DIALOG_TAG_LEAVING_GROUP,
            /* allowStateLoss = */
            true,
        )
    }

    @UiThread
    private fun onLeaveGroupCompleted(result: GroupFlowResult) {
        dismissLeavingGroupProgressDialog()
        if (result is GroupFlowResult.Failure) {
            showLeaveGroupFailedDialog(
                message = when (result) {
                    is GroupFlowResult.Failure.Network -> R.string.error_leaving_group_network
                    else -> R.string.error_leaving_group_internal
                },
            )
        }
    }

    private fun onClickConfirmDissolveGroup(bundle: Bundle) {
        logger.info("Clicked on confirm button to dissolve group")
        val groupConversationId: GroupConversationId? = bundle.getParcelableCompat(DIALOG_BUNDLE_KEY_CONVERSATION_ID)
        if (groupConversationId != null) {
            viewModel.disbandGroup(
                intent = GroupDisbandIntent.DISBAND,
                groupDatabaseId = groupConversationId.groupDatabaseId,
            )
        }
    }

    @UiThread
    private fun showDisbandGroupFailedDialog(@StringRes message: Int) {
        SimpleStringAlertDialog
            .newInstance(
                /* title = */
                R.string.error,
                /* message = */
                message,
            ).show(parentFragmentManager)
    }

    @UiThread
    private fun showDisbandingGroupProgressDialog() {
        LoadingWithTimeoutDialogXml
            .newInstance(
                timeoutSeconds = GROUP_FLOWS_LOADING_DIALOG_TIMEOUT_SECONDS,
                titleText = R.string.disbanding_group,
            ).show(
                /* manager = */
                parentFragmentManager,
                /* tag = */
                DIALOG_TAG_DISSOLVING_GROUP,
            )
    }

    @UiThread
    private fun dismissDisbandingGroupProgressDialog() {
        DialogUtil.dismissDialog(
            /* fragmentManager = */
            parentFragmentManager,
            /* tag = */
            DIALOG_TAG_DISSOLVING_GROUP,
            /* allowStateLoss = */
            true,
        )
    }

    @UiThread
    private fun onDisbandGroupCompleted(result: GroupFlowResult) {
        dismissDisbandingGroupProgressDialog()
        if (result is GroupFlowResult.Failure) {
            showDisbandGroupFailedDialog(
                message = when (result) {
                    is GroupFlowResult.Failure.Network -> R.string.error_disbanding_group_network
                    else -> R.string.error_disbanding_group_internal
                },
            )
        }
    }

    private fun onClickConfirmRemoveGroup(bundle: Bundle) {
        logger.info("Clicked on confirm button to delete (own) group")
        val groupConversationId: GroupConversationId? = bundle.getParcelableCompat(DIALOG_BUNDLE_KEY_CONVERSATION_ID)
        if (groupConversationId != null) {
            viewModel.removeGroup(
                groupDatabaseId = groupConversationId.groupDatabaseId,
            )
        }
    }

    @UiThread
    private fun showRemovingGroupFailedDialog(@StringRes message: Int) {
        SimpleStringAlertDialog
            .newInstance(
                /* title = */
                R.string.error,
                /* message = */
                message,
            ).show(parentFragmentManager)
    }

    @UiThread
    private fun showRemovingGroupProgressDialog() {
        LoadingWithTimeoutDialogXml
            .newInstance(
                timeoutSeconds = GROUP_FLOWS_LOADING_DIALOG_TIMEOUT_SECONDS,
                titleText = R.string.removing_group,
            ).show(
                /* manager = */
                parentFragmentManager,
                /* tag = */
                DIALOG_TAG_REMOVING_GROUP,
            )
    }

    @UiThread
    private fun dismissRemovingGroupProgressDialog() {
        DialogUtil.dismissDialog(
            /* fragmentManager = */
            parentFragmentManager,
            /* tag = */
            DIALOG_TAG_REMOVING_GROUP,
            /* allowStateLoss = */
            true,
        )
    }

    @UiThread
    private fun onRemovingGroupComplete(result: GroupFlowResult) {
        dismissRemovingGroupProgressDialog()
        if (result is GroupFlowResult.Failure) {
            showRemovingGroupFailedDialog(
                message = when (result) {
                    is GroupFlowResult.Failure.Network -> R.string.error_removing_group_network
                    else -> R.string.error_removing_group_internal
                },
            )
        }
    }

    @UiThread
    private fun showEmptyingConversationProgressDialog() {
        GenericProgressDialog
            .newInstance(
                /* titleRes = */
                R.string.emptying_chat,
                /* messageRes = */
                R.string.emptying_chat_deleting_messages,
            ).show(
                /* manager = */
                parentFragmentManager,
                /* tag = */
                DIALOG_TAG_EMPTYING_CONVERSATION,
            )
    }

    @UiThread
    private fun dismissEmptyingConversationProgressDialog() {
        DialogUtil.dismissDialog(
            /* fragmentManager = */
            parentFragmentManager,
            /* tag = */
            DIALOG_TAG_EMPTYING_CONVERSATION,
            /* allowStateLoss = */
            true,
        )
    }

    @UiThread
    private fun onEmptyingConversationResult(
        result: EmptyOrDeleteConversationsUseCase.Result,
        requestShowSnackbar: (String) -> Unit,
    ) {
        dismissEmptyingConversationProgressDialog()
        val resultText = when (result) {
            is EmptyOrDeleteConversationsUseCase.Result.Completed ->
                if (result.successCount == 0) {
                    getString(R.string.an_error_occurred)
                } else {
                    null
                }
            is EmptyOrDeleteConversationsUseCase.Result.UnknownConversation -> getString(R.string.an_error_occurred)
        }
        resultText?.let {
            requestShowSnackbar(resultText)
        }
    }

    @UiThread
    private fun showDeletingConversationProgressDialog() {
        GenericProgressDialog
            .newInstance(
                /* titleRes = */
                R.string.deleting_chat,
                /* messageRes = */
                R.string.emptying_chat_deleting_messages,
            ).show(
                /* manager = */
                parentFragmentManager,
                /* tag = */
                DIALOG_TAG_DELETING_CONVERSATION,
            )
    }

    @UiThread
    private fun dismissDeletingConversationProgressDialog() {
        DialogUtil.dismissDialog(
            /* fragmentManager = */
            parentFragmentManager,
            /* tag = */
            DIALOG_TAG_DELETING_CONVERSATION,
            /* allowStateLoss = */
            true,
        )
    }

    @UiThread
    private fun onDeletingConversationResult(
        result: EmptyOrDeleteConversationsUseCase.Result,
        requestShowSnackbar: (String) -> Unit,
    ) {
        dismissDeletingConversationProgressDialog()
        val resultText = when (result) {
            is EmptyOrDeleteConversationsUseCase.Result.Completed ->
                if (result.successCount > 0) {
                    resources.getQuantityString(
                        R.plurals.chat_deleted,
                        result.successCount,
                        result.successCount,
                    )
                } else {
                    getString(R.string.an_error_occurred)
                }
            is EmptyOrDeleteConversationsUseCase.Result.UnknownConversation -> getString(R.string.an_error_occurred)
        }
        requestShowSnackbar(resultText)
    }

    /**
     * Keeps track of the last archive conversations. This class is used for the undo action.
     */
    private inner class ArchiveSnackbar(archiveSnackbar: ArchiveSnackbar?, archivedConversation: ConversationModel) {
        private val snackbar: Snackbar?
        private val conversationModels = ArrayList<ConversationModel>()

        /**
         * Creates an updated archive snackbar, dismisses the old snackbar (if available), and shows
         * the updated snackbar.
         *
         * @param archiveSnackbar      the currently shown archive snackbar (if available)
         * @param archivedConversation the conversation that just has been archived
         */
        init {
            this.conversationModels.add(archivedConversation)

            if (archiveSnackbar != null) {
                this.conversationModels.addAll(archiveSnackbar.conversationModels)
                archiveSnackbar.dismiss()
            }

            if (view != null) {
                val amountArchived: Int = this.conversationModels.size
                val snackText: String = resources.getQuantityString(
                    R.plurals.message_archived,
                    amountArchived,
                    amountArchived,
                )
                this.snackbar = Snackbar.make(
                    requireView(),
                    snackText,
                    DURATION_ARCHIVE_UNDO_SNACKBAR.inWholeMilliseconds.toInt(),
                )
                this.snackbar.setAction(R.string.undo) { _ ->
                    logger.info("Clicked on button to undo archiving conversation")
                    conversationService.unarchive(conversationModels, TriggerSource.LOCAL)
                }
                this.snackbar.addCallback(
                    object : Snackbar.Callback() {
                        override fun onDismissed(snackbar: Snackbar?, event: Int) {
                            super.onDismissed(snackbar, event)
                            if (this@ConversationsFragment.archiveSnackbar === this@ArchiveSnackbar) {
                                this@ConversationsFragment.archiveSnackbar = null
                            }
                        }
                    },
                )
                this.snackbar.show()
            } else {
                this.snackbar = null
            }
        }

        fun dismiss() {
            this.snackbar?.dismiss()
        }
    }

    companion object {

        private const val REQUEST_CODE_SET_UP_LOCK_MECHANISM_TO_MARK_CONVERSATION_PRIVATE = 33211
        private const val REQUEST_CODE_SET_UP_LOCK_MECHANISM_TO_UN_MARK_CONVERSATION_PRIVATE = 33212
        private const val REQUEST_CODE_SET_UP_LOCK_MECHANISM_TO_HIDE_PRIVATE_CONVERSATIONS = 33213

        private const val DIALOG_TAG_PREPARING_MESSAGES = "tag-dialog-preparing-messages"
        private const val DIALOG_TAG_LEAVING_GROUP = "tag-dialog-leaving-group"
        private const val DIALOG_TAG_DISSOLVING_GROUP = "tag-dialog-dissolving-group"
        private const val DIALOG_TAG_REMOVING_GROUP = "tag-dialog-removing-group"
        private const val DIALOG_TAG_EMPTYING_CONVERSATION = "tag-dialog-emptying-conversation"
        private const val DIALOG_TAG_DELETING_CONVERSATION = "tag-dialog-deleting-conversation"

        // Dialog request keys
        private const val DIALOG_LOCK_MECHANISM_REQUIRED_TO_TOGGLE_PRIVATE_MARK = "dialog-lock-mechanism-required-to-toggle-private-mark"
        private const val DIALOG_LOCK_MECHANISM_REQUIRED_TO_HIDE = "dialog-lock-mechanism-required-to-hide"
        private const val DIALOG_CONFIRM_TO_MARK_AS_PRIVATE = "dialog-confirm-to-mark-as-private"
        private const val DIALOG_CONFIRM_TO_EMPTY_CONVERSATION = "dialog-confirm-to-empty-conversation"
        private const val DIALOG_CONFIRM_DELETE_CONTACT_CONVERSATION = "dialog-confirm-delete-contact-conversation"
        private const val DIALOG_CONFIRM_DELETE_DISTRIBUTION_LIST_CONVERSATION = "dialog-confirm-delete-distribution-list-conversation"
        private const val DIALOG_SET_PASSWORD_FOR_SHARING = "dialog-set-password-for-sharing"
        private const val DIALOG_PREPARING_MESSAGES_FOR_SHARING = "dialog-preparing-messages-for-sharing"
        private const val DIALOG_CONFIRM_LEAVE_GROUP = "dialog-confirm-leave-group"
        private const val DIALOG_CONFIRM_DISSOLVE_GROUP = "dialog-confirm-dissolve-group"
        private const val DIALOG_CONFIRM_DELETE_MY_GROUP = "dialog-confirm-delete-my-group"
        private const val DIALOG_CONFIRM_DELETE_GROUP = "dialog-confirm-delete-group"
        private const val DIALOG_CONVERSATION_ITEM_ACTIONS = "dialog-conversation-item-actions"

        // Dialog request data
        private const val DIALOG_BUNDLE_KEY_TARGET_VALUE = "target-value"
        private const val DIALOG_BUNDLE_KEY_CONVERSATION_ID = "conversation-id"

        private const val TAG_EMPTY_CONVERSATION = 1
        private const val TAG_DELETE_DISTRIBUTION_LIST = 2
        private const val TAG_LEAVE_GROUP = 3
        private const val TAG_DISSOLVE_GROUP = 4
        private const val TAG_DELETE_MY_GROUP = 5
        private const val TAG_DELETE_GROUP = 6
        private const val TAG_MARK_AS_PRIVATE = 7
        private const val TAG_UNMARK_AS_PRIVATE = 8
        private const val TAG_SHARE = 9
        private const val TAG_DELETE_LEFT_GROUP = 10
        private const val TAG_EDIT_GROUP = 11
        private const val TAG_MARK_READ = 12
        private const val TAG_MARK_UNREAD = 13
        private const val TAG_DELETE_CONTACT_CONVERSATION = 14
        private const val TAG_ARCHIVE_CONVERSATION = 15

        // TODO(ANDR-4613): Remove this tag
        private const val TAG_OPEN_IN_LEGACY_SCREEN = 16

        // F1Whisper: must stay distinct from the tags above (the dialog result is dispatched by value)
        private const val TAG_ADD_TO_FOLDER = 17

        private val DURATION_ARCHIVE_UNDO_SNACKBAR: Duration = 7.seconds

        private const val BUNDLE_SEARCH_QUERY = "search-query"
    }
}

/**
 * Returns whether the lazy list is currently scrolling up.
 */
@Composable
private fun LazyListState.isScrollingUp(): Boolean {
    var previousIndex by remember(this) { mutableIntStateOf(firstVisibleItemIndex) }
    var previousScrollOffset by remember(this) { mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            if (previousIndex != firstVisibleItemIndex) {
                previousIndex > firstVisibleItemIndex
            } else {
                previousScrollOffset >= firstVisibleItemScrollOffset
            }.also {
                previousIndex = firstVisibleItemIndex
                previousScrollOffset = firstVisibleItemScrollOffset
            }
        }
    }.value
}
