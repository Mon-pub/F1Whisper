package ch.threema.app.activities

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.launch
import ch.threema.android.buildActivityIntent
import ch.threema.android.buildBundle
import ch.threema.android.disableExitTransition
import ch.threema.android.getLongOrNull
import ch.threema.android.getParcelableExtraCompat
import ch.threema.android.getStringOrNull
import ch.threema.android.runTransaction
import ch.threema.app.AppConstants
import ch.threema.app.BuildConfig
import ch.threema.app.R
import ch.threema.app.applock.CheckAppLockContract
import ch.threema.app.conversation.ConversationActivity
import ch.threema.app.conversations.ConversationsFragment
import ch.threema.app.dialogs.GenericAlertDialog
import ch.threema.app.dialogs.GenericAlertDialog.DialogClickListener
import ch.threema.app.fragments.composemessage.ComposeMessageFragment
import ch.threema.app.preference.SettingsActivity
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.services.ConversationCategoryService
import ch.threema.app.startup.AppStartupAware
import ch.threema.app.startup.waitUntilReady
import ch.threema.app.ui.VoicePlayerBannerView
import ch.threema.app.utils.ConfigUtils
import ch.threema.app.utils.IntentDataUtil
import ch.threema.app.utils.ShortcutUtil
import ch.threema.app.utils.logScreenVisibility
import ch.threema.base.utils.getThreemaLogger
import ch.threema.data.datatypes.ContactConversationId
import ch.threema.data.datatypes.ConversationId
import ch.threema.data.datatypes.DistributionListConversationId
import ch.threema.data.datatypes.GroupConversationId
import ch.threema.domain.types.GroupDatabaseId
import ch.threema.domain.types.IdentityString
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.DistributionListMessageModel
import ch.threema.storage.models.group.GroupMessageModel
import org.koin.android.ext.android.inject

private val logger = getThreemaLogger("ComposeMessageActivity")

class ComposeMessageActivity : ThreemaToolbarActivity(), DialogClickListener, AppStartupAware {
    init {
        logScreenVisibility(logger)
    }

    private val conversationCategoryService: ConversationCategoryService by inject()
    private val preferenceService: PreferenceService by inject()

    private var composeMessageFragment: ComposeMessageFragment? = null
    private var conversationsFragment: ConversationsFragment? = null

    private var currentIntent: Intent? = null
    private var savedSoftInputMode = 0

    private val checkLockOnCreateLauncher = registerForActivityResult(CheckAppLockContract()) { unlocked ->
        if (unlocked) {
            composeMessageFragment?.let { fragment ->
                supportFragmentManager.runTransaction {
                    show(fragment)
                }
                // mark conversation as read as soon as it's unhidden
                fragment.markAsRead()
            }
        } else {
            finish()
        }
    }
    private val checkLockOnNewIntentLauncher = registerForActivityResult(CheckAppLockContract()) { unlocked ->
        if (unlocked) {
            composeMessageFragment?.let { fragment ->
                supportFragmentManager.runTransaction {
                    show(fragment)
                }
                fragment.onNewIntent(this.currentIntent)
            }
        } else if (!ConfigUtils.isTabletLayout()) {
            finish()
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        logger.info("onCreate")
        window.setAllowEnterTransitionOverlap(true)
        window.setAllowReturnTransitionOverlap(true)
        currentIntent = intent

        super.onCreate(savedInstanceState)

        // TODO(ANDR-4389): Improve the waiting mechanism
        waitUntilReady {
            if (supportFragmentManager.isStateSaved) {
                logger.warn("Fragment state already saved")
            }
            initActivity(savedInstanceState)
            handleDeviceInsets()
        }
    }

    override fun initActivity(savedInstanceState: Bundle?): Boolean {
        if (!super.initActivity(savedInstanceState)) {
            return false
        }
        logger.info("initActivity")

        findExistingFragments()

        val conversationId: ConversationId = intent?.extractConversationId()
            ?: run {
                logger.warn("Can't open conversation, didn't receive conversation id in intent")
                return false
            }

        if (findViewById<View>(R.id.messages) != null && conversationsFragment == null) {
            // add conversations fragment in tablet layout
            conversationsFragment = ConversationsFragment()
            conversationsFragment!!.setArguments(
                buildBundle {
                    putParcelable(AppConstants.INTENT_DATA_CONVERSATION_ID, conversationId)
                },
            )
            supportFragmentManager.runTransaction(allowStateLoss = true) {
                add(R.id.messages, conversationsFragment!!, MESSAGES_FRAGMENT_TAG)
            }
        }

        val isHidden = checkHiddenConversationLock(conversationId, checkLockOnCreateLauncher)
        if (composeMessageFragment == null) {
            composeMessageFragment = ComposeMessageFragment()
            if (isHidden) {
                supportFragmentManager.runTransaction(allowStateLoss = true) {
                    add(R.id.compose, composeMessageFragment!!, COMPOSE_FRAGMENT_TAG)
                    hide(composeMessageFragment!!)
                }
            } else {
                supportFragmentManager.runTransaction(allowStateLoss = true) {
                    add(R.id.compose, composeMessageFragment!!, COMPOSE_FRAGMENT_TAG)
                }
            }
        } else if (!isHidden) {
            supportFragmentManager.runTransaction(allowStateLoss = true) {
                show(composeMessageFragment!!)
            }
        }
        updateVoiceBannerOpenChat(intent)
        return true
    }

    /**
     * F1Whisper: tell the background voice "now playing" banner which chat is open so it hides for a
     * voice message belonging to THIS chat (that chat renders the bubble itself) and shows only for a
     * voice playing from a different chat.
     */
    private fun updateVoiceBannerOpenChat(intent: Intent?) {
        val banner = findViewById<VoicePlayerBannerView?>(R.id.voice_player_banner) ?: return
        val uniqueId = try {
            IntentDataUtil.getMessageReceiverFromIntent(applicationContext, intent)?.conversationId?.toDatabaseValue()
        } catch (e: Exception) {
            logger.warn("Could not resolve open chat for voice banner", e)
            null
        }
        banner.setOpenChatUniqueId(uniqueId)
    }

    override fun getLayoutResource() = if (ConfigUtils.isTabletLayout(this)) {
        R.layout.activity_compose_message_tablet
    } else {
        R.layout.activity_compose_message
    }

    private fun findExistingFragments() {
        composeMessageFragment = supportFragmentManager.findFragmentByTag(COMPOSE_FRAGMENT_TAG) as ComposeMessageFragment?
        conversationsFragment = supportFragmentManager.findFragmentByTag(MESSAGES_FRAGMENT_TAG) as ConversationsFragment?
    }

    public override fun onNewIntent(intent: Intent) {
        logger.info("onNewIntent")

        super.onNewIntent(intent)

        this.currentIntent = intent

        findExistingFragments()

        val conversationId: ConversationId = intent.extractConversationId()
            ?: run {
                logger.warn("Can't open conversation, didn't receive conversation id in new intent")
                return
            }
        composeMessageFragment?.let { composeMessageFragment ->
            if (!checkHiddenConversationLock(conversationId, checkLockOnNewIntentLauncher)) {
                supportFragmentManager.runTransaction {
                    show(composeMessageFragment)
                }
                composeMessageFragment.onNewIntent(intent)
            }
        }
        updateVoiceBannerOpenChat(intent)
    }

    override fun enableOnBackPressedCallback() = true

    override fun handleOnBackPressed() {
        logger.info("handleOnBackPressed")
        if (ConfigUtils.isTabletLayout() && conversationsFragment?.onBackPressed() == true) {
            return
        }
        composeMessageFragment?.let { composeMessageFragment ->
            if (!composeMessageFragment.onBackPressed()) {
                finish()
                if (ConfigUtils.isTabletLayout()) {
                    disableExitTransition()
                }
            }
            return
        }
        finish()
    }

    public override fun onResume() {
        super.onResume()

        // Set the soft input mode to resize when activity resumes because it is set to adjust nothing while it is paused
        savedSoftInputMode = window.attributes.softInputMode
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    public override fun onPause() {
        super.onPause()

        // Set the soft input mode to adjust nothing while paused. This is needed when the keyboard is opened to edit the contact before sending.
        if (savedSoftInputMode > 0) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
    }

    private fun checkHiddenConversationLock(conversationId: ConversationId, launcher: ActivityResultLauncher<Unit>): Boolean {
        if (conversationCategoryService.isMarkedAsPrivate(conversationId)) {
            if (preferenceService.hasLockMechanism()) {
                launcher.launch()
            } else {
                GenericAlertDialog
                    .newInstance(
                        /* title = */
                        R.string.hide_chat,
                        /* message = */
                        R.string.hide_chat_enter_message_explain,
                        /* positive = */
                        R.string.set_lock,
                        /* negative = */
                        R.string.cancel,
                    )
                    .show(
                        /* manager = */
                        supportFragmentManager,
                        /* tag = */
                        DIALOG_TAG_HIDDEN_NOTICE,
                    )
            }
            return true
        }
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        ConfigUtils.adjustToolbar(this, toolbar)

        val messagesLayout = findViewById<FrameLayout?>(R.id.messages)

        if (messagesLayout != null) {
            // adjust width of messages fragment in tablet layout
            val layoutParams = messagesLayout.layoutParams as FrameLayout.LayoutParams
            layoutParams.width = resources.getDimensionPixelSize(R.dimen.message_fragment_width)
            messagesLayout.setLayoutParams(layoutParams)
        }
    }

    override fun onYes(tag: String?, data: Any?) {
        startActivity(SettingsActivity.createIntent(this, SettingsActivity.InitialScreen.SECURITY))
        finish()
    }

    override fun onNo(tag: String?, data: Any?) {
        finish()
    }

    companion object {
        private const val COMPOSE_FRAGMENT_TAG = "compose_message_fragment"
        private const val MESSAGES_FRAGMENT_TAG = "message_section_fragment"

        private const val DIALOG_TAG_HIDDEN_NOTICE = "hidden"

        @JvmStatic
        @JvmOverloads
        @Suppress("KotlinConstantConditions")
        fun createIntent(
            context: Context,
            conversationId: ConversationId,
            initialText: String? = null,
            hasInitialFocus: Boolean? = null,
        ) =
            if (BuildConfig.NEW_CONVERSATION_SCREEN_ENABLED) {
                ConversationActivity.createIntent(
                    context = context,
                    conversationId = conversationId,
                    initialText = initialText,
                    hasInitialFocus = hasInitialFocus,
                )
            } else {
                buildActivityIntent<ComposeMessageActivity>(context) {
                    putExtra(AppConstants.INTENT_DATA_CONVERSATION_ID, conversationId)
                    if (initialText != null) {
                        putExtra(AppConstants.INTENT_DATA_TEXT, initialText)
                    }
                    if (hasInitialFocus != null) {
                        putExtra(AppConstants.INTENT_DATA_EDITFOCUS, hasInitialFocus)
                    }
                }
            }

        @JvmStatic
        @JvmOverloads
        @Suppress("KotlinConstantConditions")
        fun createIntentJumpToMessage(
            context: Context,
            message: AbstractMessageModel,
            overrideBackToHomeBehavior: Boolean? = null,
        ) =
            if (BuildConfig.NEW_CONVERSATION_SCREEN_ENABLED) {
                ConversationActivity.createIntentJumpToMessage(
                    context = context,
                    message = message,
                    overrideBackToHomeBehavior = overrideBackToHomeBehavior,
                )
            } else {
                buildActivityIntent<ComposeMessageActivity>(context) {
                    val conversationId: ConversationId =
                        when (message) {
                            is GroupMessageModel -> {
                                GroupConversationId(
                                    groupDatabaseId = message.groupId.toLong(),
                                )
                            }
                            is DistributionListMessageModel -> {
                                DistributionListConversationId(
                                    distributionListId = message.distributionListId,
                                )
                            }
                            else -> {
                                val identity = message.identity
                                requireNotNull(identity) { "Message is missing identity" }
                                ContactConversationId(identity)
                            }
                        }
                    putExtra(AppConstants.INTENT_DATA_CONVERSATION_ID, conversationId)
                    putExtra(ComposeMessageFragment.EXTRA_API_MESSAGE_ID, message.apiMessageId)
                    putExtra(ComposeMessageFragment.EXTRA_SEARCH_QUERY, " ")
                    if (overrideBackToHomeBehavior != null) {
                        putExtra(ComposeMessageFragment.EXTRA_OVERRIDE_BACK_TO_HOME_BEHAVIOR, overrideBackToHomeBehavior)
                    }
                }
            }

        @JvmStatic
        fun Intent.extractConversationId(): ConversationId? =
            getParcelableExtraCompat<ConversationId>(AppConstants.INTENT_DATA_CONVERSATION_ID)
                ?: run {
                    // Persisted intents from shortcuts do not support parcelable extras, so conversation-id can not be used directly
                    val calledFromShortcut = getBooleanExtra(ShortcutUtil.EXTRA_CALLED_FROM_SHORTCUT, false)
                    if (!calledFromShortcut) {
                        return null
                    }
                    getStringOrNull(AppConstants.INTENT_DATA_CONTACT)?.let { identity: IdentityString ->
                        return ContactConversationId(identity)
                    }
                    getLongOrNull(AppConstants.INTENT_DATA_GROUP_DATABASE_ID)?.let { groupDatabaseId: GroupDatabaseId ->
                        return GroupConversationId(groupDatabaseId)
                    }
                    getLongOrNull(AppConstants.INTENT_DATA_DISTRIBUTION_LIST_ID)?.let { distributionListId ->
                        return DistributionListConversationId(distributionListId)
                    }
                    null
                }
    }
}
