package ch.threema.app.home

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PorterDuff
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TextAppearanceSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import androidx.annotation.AnyThread
import androidx.annotation.ColorInt
import androidx.annotation.IdRes
import androidx.annotation.UiThread
import androidx.appcompat.app.ActionBar
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ch.threema.android.Destroyer.Companion.createDestroyer
import ch.threema.android.ToastDuration
import ch.threema.android.broadcastReceiver
import ch.threema.android.buildActivityIntent
import ch.threema.android.context
import ch.threema.android.getLocation
import ch.threema.android.registerPermissionResultContract
import ch.threema.android.registerSimpleActivityResultContract
import ch.threema.android.runTransaction
import ch.threema.android.showToast
import ch.threema.app.AppConstants.THREEMA_CHANNEL_IDENTITY
import ch.threema.app.BuildConfig
import ch.threema.app.BuildFlavor
import ch.threema.app.R
import ch.threema.app.activities.BackupAdminActivity
import ch.threema.app.activities.ChatFoldersActivity
import ch.threema.app.activities.BackupRestoreProgressActivity
import ch.threema.app.activities.ComposeMessageActivity
import ch.threema.app.activities.DistributionListAddActivity
import ch.threema.app.activities.DownloadApkActivity
import ch.threema.app.activities.EnterSerialActivity
import ch.threema.app.activities.GroupAddActivity
import ch.threema.app.activities.ServerMessageActivity
import ch.threema.app.activities.ThreemaAppCompatActivity
import ch.threema.app.activities.WhatsNewActivity
import ch.threema.app.activities.WorkIntroActivity
import ch.threema.app.activities.directory.DirectoryActivity
import ch.threema.app.activities.starred.StarredMessagesActivity
import ch.threema.app.activities.wizard.WizardBaseActivity
import ch.threema.app.activities.wizard.WizardStartActivity
import ch.threema.app.apptaskexecutor.AppTaskExecutor
import ch.threema.app.archive.ArchiveActivity
import ch.threema.app.asynctasks.AddContactRestrictionPolicy
import ch.threema.app.asynctasks.BasicAddOrUpdateContactBackgroundTask
import ch.threema.app.asynctasks.ContactAvailable
import ch.threema.app.asynctasks.ContactCreated
import ch.threema.app.asynctasks.ContactResult
import ch.threema.app.backuprestore.csv.BackupService
import ch.threema.app.backuprestore.csv.RestoreService
import ch.threema.app.dialogs.GenericAlertDialog
import ch.threema.app.dialogs.GenericAlertDialog.DialogClickListener
import ch.threema.app.dialogs.GenericProgressDialog
import ch.threema.app.dialogs.SMSVerificationDialog
import ch.threema.app.dialogs.SMSVerificationDialog.SMSVerificationDialogCallback
import ch.threema.app.dialogs.ShowOnceDialog
import ch.threema.app.dialogs.SimpleStringAlertDialog
import ch.threema.app.errorreporting.ErrorReportingHelper
import ch.threema.app.files.TempFilesCleanupWorker
import ch.threema.app.fragments.ContactsSectionFragment
import ch.threema.app.fragments.MyIDFragment
import ch.threema.app.fragments.conversations.ConversationsFragment
import ch.threema.app.glide.AvatarOptions
import ch.threema.app.globalsearch.GlobalSearchActivity
import ch.threema.app.home.usecases.CheckBackupsFeatureEnabledUseCase
import ch.threema.app.home.usecases.CheckServerMessagesUseCase
import ch.threema.app.home.usecases.GetStarredMessagesCountUseCase
import ch.threema.app.home.usecases.GetUnreadConversationCountUseCase
import ch.threema.app.home.usecases.SetUpThreemaChannelUseCase
import ch.threema.app.home.usecases.ShouldShowWorkIntroScreenUseCase
import ch.threema.app.identitylinks.VerifyMobileNumberUseCase
import ch.threema.app.listeners.AppIconListener
import ch.threema.app.listeners.ContactCountListener
import ch.threema.app.listeners.ConversationListener
import ch.threema.app.listeners.MessageListener
import ch.threema.app.listeners.ProfileListener
import ch.threema.app.listeners.SMSVerificationListener
import ch.threema.app.listeners.VoipCallListener
import ch.threema.app.logging.DebugLogHelper
import ch.threema.app.managers.ListenerManager
import ch.threema.app.managers.ListenerManager.TypedListenerManager
import ch.threema.app.multidevice.LinkedDevicesActivity
import ch.threema.app.multidevice.MultiDeviceManager
import ch.threema.app.preference.SettingsActivity
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.preference.service.SynchronizedSettingsService
import ch.threema.app.problemsolving.GetProblemsUseCase
import ch.threema.app.problemsolving.ProblemSolverActivity
import ch.threema.app.push.PushService
import ch.threema.app.restrictions.AppRestrictions
import ch.threema.app.routines.CheckLicenseRoutine
import ch.threema.app.services.ActivityService
import ch.threema.app.services.ContactService
import ch.threema.app.services.ContactServiceImpl
import ch.threema.app.services.ConversationService
import ch.threema.app.services.DeviceService
import ch.threema.app.services.FileService
import ch.threema.app.services.LockAppService
import ch.threema.app.services.NotificationPreferenceService
import ch.threema.app.services.PassphraseService
import ch.threema.app.services.ThreemaPushService
import ch.threema.app.services.UserService
import ch.threema.app.services.license.LicenseService
import ch.threema.app.services.notification.NotificationService
import ch.threema.app.startup.RemoteSecretProtectionUpdateActivity
import ch.threema.app.startup.finishAndRestartLaterIfNotReady
import ch.threema.app.tasks.ApplicationUpdateStepsTask
import ch.threema.app.threemasafe.ThreemaSafeMDMConfig
import ch.threema.app.threemasafe.ThreemaSafeService
import ch.threema.app.ui.IdentityPopup
import ch.threema.app.ui.InsetSides.Companion.ltr
import ch.threema.app.ui.OngoingCallNoticeMode
import ch.threema.app.ui.OngoingCallNoticeView
import ch.threema.app.ui.applyDeviceInsetsAsPadding
import ch.threema.app.utils.AnimationUtil
import ch.threema.app.utils.ConfigUtils
import ch.threema.app.utils.ConnectionIndicatorUtil
import ch.threema.app.utils.DialogUtil
import ch.threema.app.utils.DispatcherProvider
import ch.threema.app.utils.IntentDataUtil
import ch.threema.app.utils.RuntimeUtil
import ch.threema.app.utils.TestUtil
import ch.threema.app.utils.executor.BackgroundExecutor
import ch.threema.app.utils.logScreenVisibility
import ch.threema.app.voip.groupcall.GroupCallDescription
import ch.threema.app.voip.groupcall.GroupCallManager
import ch.threema.app.voip.groupcall.GroupCallObserver
import ch.threema.app.voip.services.VoipCallService
import ch.threema.app.webclient.activities.SessionsActivity
import ch.threema.app.webviews.SupportActivity
import ch.threema.base.utils.getThreemaLogger
import ch.threema.common.consume
import ch.threema.common.minus
import ch.threema.common.secureContentEquals
import ch.threema.data.repositories.ContactModelRepository
import ch.threema.domain.models.ContactReceiverIdentifier
import ch.threema.domain.protocol.api.APIConnector
import ch.threema.domain.protocol.api.LinkMobileNoException
import ch.threema.domain.protocol.connection.ConnectionState
import ch.threema.domain.protocol.connection.ConnectionStateListener
import ch.threema.domain.protocol.connection.ServerConnection
import ch.threema.domain.stores.IdentityStore
import ch.threema.domain.taskmanager.TaskManager
import ch.threema.domain.taskmanager.TriggerSource
import ch.threema.localcrypto.MasterKeyManager
import ch.threema.localcrypto.models.RemoteSecretCheckType
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.ContactModel
import ch.threema.storage.models.ConversationModel
import ch.threema.storage.models.MessageState
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.badge.ExperimentalBadgeUtils
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import java.time.Instant
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

private val logger = getThreemaLogger("HomeActivity")

class HomeActivity : ThreemaAppCompatActivity(), SMSVerificationDialogCallback, DialogClickListener {
    init {
        logScreenVisibility(logger)
    }

    private val apiConnector: APIConnector by inject()
    private val appTaskExecutor: AppTaskExecutor by inject()
    private val contactModelRepository: ContactModelRepository by inject()
    private val contactService: ContactService by inject()
    private val conversationService: ConversationService by inject()
    private val errorReportingHelper: ErrorReportingHelper by inject()
    private val errorReportingDialog: ErrorReportingDialog by inject()
    private val deviceService: DeviceService by inject()
    private val dispatcherProvider: DispatcherProvider by inject()
    private val fileService: FileService by inject()
    private val groupCallManager: GroupCallManager by inject()
    private val identityStore: IdentityStore by inject()
    private val licenseService: LicenseService<*> by inject()
    private val lockAppService: LockAppService by inject()
    private val masterKeyManager: MasterKeyManager by inject()
    private val multiDeviceManager: MultiDeviceManager by inject()
    private val notificationPreferenceService: NotificationPreferenceService by inject()
    private val notificationService: NotificationService by inject()
    private val preferenceService: PreferenceService by inject()
    private val serverConnection: ServerConnection by inject()
    private val synchronizedSettingsService: SynchronizedSettingsService by inject()
    private val taskManager: TaskManager by inject()
    private val threemaSafeService: ThreemaSafeService by inject()
    private val userService: UserService by inject()
    private val appRestrictions: AppRestrictions by inject()
    private val getProblemsUseCase: GetProblemsUseCase by inject()
    private val checkBackupsFeatureEnabledUseCase: CheckBackupsFeatureEnabledUseCase by inject()
    private val shouldShowWorkIntroScreenUseCase: ShouldShowWorkIntroScreenUseCase by inject()
    private val setUpThreemaChannelUseCase: SetUpThreemaChannelUseCase by inject()
    private val getUnreadConversationCountUseCase: GetUnreadConversationCountUseCase by inject()
    private val getStarredMessagesCountUseCase: GetStarredMessagesCountUseCase by inject()
    private val checkServerMessagesUseCase: CheckServerMessagesUseCase by inject()
    private val verifyMobileNumberUseCase: VerifyMobileNumberUseCase by inject()
    private val debugLogHelper: DebugLogHelper by inject()
    private val viewModel: HomeViewModel by viewModel()
    private val destroyer = createDestroyer()
    private val backgroundExecutor by lazy {
        BackgroundExecutor()
    }

    private val notificationPermissionLauncher = registerPermissionResultContract { isGranted ->
        if (!isGranted) {
            logger.warn("Notification permission was not granted")
        }
    }
    private val problemSolverLauncher = registerSimpleActivityResultContract { updateWarningButton() }
    private val settingsLauncher = registerSimpleActivityResultContract { invalidateOptionsMenu() }
    private val enterSerialLauncher = registerSimpleActivityResultContract {
        if (!licenseService.isLicensed()) {
            GenericAlertDialog.newInstance(
                R.string.enter_serial_title,
                R.string.serial_required_want_exit,
                R.string.try_again,
                R.string.cancel,
            )
                .show(supportFragmentManager, DIALOG_TAG_SERIAL_LOCKED)
        } else {
            startHomeActivity(null)
        }
    }
    private val whatsNewLauncher = registerSimpleActivityResultContract { showMainContent() }

    private var checkLicenseBroadcastReceiver: BroadcastReceiver? = null

    private val currentCheckAppReceiver = broadcastReceiver { _, intent ->
        lifecycleScope.launch {
            when (intent.action) {
                IntentDataUtil.ACTION_LICENSE_NOT_ALLOWED -> {
                    val currentLicenseType = BuildFlavor.current.licenseType
                    if (currentLicenseType == BuildFlavor.LicenseType.SERIAL || currentLicenseType.isWork()) {
                        enterSerialLauncher.launch(EnterSerialActivity.createIntent(context))
                    } else {
                        showErrorTextAndExit(IntentDataUtil.getMessage(intent))
                    }
                }
                IntentDataUtil.ACTION_UPDATE_AVAILABLE -> {
                    if (BuildFlavor.current.maySelfUpdate && userService.hasIdentity()) {
                        logger.info("App update available. Opening DownloadApkActivity.")
                        // F1Whisper: cache the intent so onResume can re-prompt hourly.
                        cachedSelfUpdateIntent = Intent(intent)
                        delay(5.seconds)
                        val dialogIntent = Intent(intent)
                        dialogIntent.setClass(context, DownloadApkActivity::class.java)
                        // Record shown time so the 1-hour cooldown starts now.
                        android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                            .edit()
                            .putLong(PREF_SELF_UPDATE_LAST_SHOWN_MS, System.currentTimeMillis())
                            .apply()
                        startActivity(dialogIntent)
                    }
                }
                IntentDataUtil.ACTION_MANDATORY_UPDATE -> {
                    if (BuildFlavor.current.maySelfUpdate && userService.hasIdentity()) {
                        // F1Whisper: server has signalled that this client is below the mandatory
                        // floor. Cache url + deadline and refresh the banner on the next UI tick.
                        // NOTE: this gate is SOFT — not a security mechanism. See plan honesty-ceiling.
                        cachedMandatoryUpdateUrl = IntentDataUtil.getUrl(intent)
                        val deadlineSecs = IntentDataUtil.getMandatoryDeadlineSecs(intent)
                        cachedMandatoryDeadlineSecs = if (deadlineSecs >= 0L) deadlineSecs else null
                        logger.info("Mandatory update gate received: url={} deadlineSecs={}", cachedMandatoryUpdateUrl, cachedMandatoryDeadlineSecs)
                        updateMandatoryUpdateBanner()
                    }
                }
            }
        }
    }

    private val connectionStateListener = ConnectionStateListener(::updateConnectionIndicator)

    private val smsVerificationListener = object : SMSVerificationListener {
        override fun onVerified() {
            lifecycleScope.launch {
                if (noticeSMSLayout != null) {
                    AnimationUtil.collapse(noticeSMSLayout, null, true)
                }
            }
        }

        override fun onVerificationStarted() {
            lifecycleScope.launch {
                if (noticeSMSLayout != null) {
                    AnimationUtil.expand(noticeSMSLayout, null, true)
                }
            }
        }
    }

    private val conversationListener = object : ConversationListener {
        override fun onNew(conversationModel: ConversationModel) {
            updateUnreadBadge()
        }

        override fun onModified(modifiedConversationModel: ConversationModel) {
            updateUnreadBadge()
        }

        override fun onRemoved(conversationModel: ConversationModel) {
            updateUnreadBadge()
        }

        override fun onModifiedAll() {
            updateUnreadBadge()
        }
    }

    private val messageListener = object : MessageListener {
        override fun onModified(modifiedMessageModels: MutableList<AbstractMessageModel>) {
            for (modifiedMessageModel in modifiedMessageModels) {
                if (!modifiedMessageModel.isStatusMessage &&
                    modifiedMessageModel.isOutbox
                ) {
                    // F1Whisper auto-resend: the unsent-message nag (notification 732) stays gated to
                    // TERMINAL failures without any extra check here, because the auto-resend design
                    // never lets a transient connectivity failure reach SENDFAILED - such a message
                    // is kept in flight (PENDING/UPLOADING/SENDING) and re-sent on reconnect, so it
                    // simply never enters the SENDFAILED branch. Only terminal failures (and messages
                    // that have exhausted the 24h auto-resend window, marked terminally failed by the
                    // scan) arrive here as SENDFAILED and nag. When such a message is later manually
                    // or auto-resent it leaves SENDFAILED and the else-branch removes it from the nag.
                    if (modifiedMessageModel.state == MessageState.SENDFAILED) {
                        updateUnsentMessagesList(modifiedMessageModel, UnsentMessageAction.ADD)
                    } else {
                        updateUnsentMessagesList(modifiedMessageModel, UnsentMessageAction.REMOVE)
                    }
                }
            }
        }

        override fun onRemoved(removedMessageModel: AbstractMessageModel) {
            updateUnsentMessagesList(removedMessageModel, UnsentMessageAction.REMOVE)
        }

        override fun onRemoved(removedMessageModels: MutableList<AbstractMessageModel>) {
            for (removedMessageModel in removedMessageModels) {
                updateUnsentMessagesList(removedMessageModel, UnsentMessageAction.REMOVE)
            }
        }

        override fun onResendDismissed(messageModel: AbstractMessageModel) {
            updateUnsentMessagesList(messageModel, UnsentMessageAction.REMOVE)
        }
    }

    private val appIconListener = AppIconListener {
        updateAppLogo()
    }

    private val profileListener: ProfileListener = ProfileListener {
        lifecycleScope.launch {
            updateDrawerImage()
        }
    }

    private val voipCallListener: VoipCallListener = object : VoipCallListener {
        override fun onStart(contact: String?, elpasedTimeMs: Long) {
            updateOngoingCallNotice()
        }

        override fun onEnd() {
            ongoingCallNotice?.hideVoip()
        }
    }

    private val groupCallObserver = GroupCallObserver { updateOngoingCallNotice() }

    private val contactCountListener: ContactCountListener = ContactCountListener { last24hoursCount ->
        if (preferenceService.getShowUnreadBadge()) {
            lifecycleScope.launch {
                if (!isFinishing && !isDestroyed && !isChangingConfigurations) {
                    val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
                    if (bottomNavigationView != null) {
                        val badgeDrawable = bottomNavigationView.getOrCreateBadge(R.id.contacts)
                        // the contacts tab item badge uses custom colors (normally badges are red)
                        badgeDrawable.backgroundColor = getContactsTabBadgeColor(bottomNavigationView.selectedItemId)
                        if (badgeDrawable.verticalOffset == 0) {
                            badgeDrawable.verticalOffset = resources.getDimensionPixelSize(R.dimen.bottom_nav_badge_offset_vertical)
                        }
                        badgeDrawable.isVisible = last24hoursCount > 0
                    }
                }
            }
        }
    }

    // State
    private var currentFragmentTag: String? = null
    private var starredMessagesCount = 0
    private var isLicenseCheckStarted = false
    private var isInitialized = false
    private var isWhatsNewShown = false
    private val unsentMessages = mutableListOf<AbstractMessageModel>()
    // F1Whisper: cached self-update intent — replayed on resume so the user is re-prompted after
    // tapping "Remind me later". Null if no update is known; cleared only when the update is
    // superseded (a fresh check arrives).
    private var cachedSelfUpdateIntent: Intent? = null
    // F1Whisper: mandatory-update gate state. Set when ACTION_MANDATORY_UPDATE arrives.
    // Null means no gate active; non-null means the server placed this client below the floor.
    private var cachedMandatoryUpdateUrl: String? = null
    private var cachedMandatoryDeadlineSecs: Long? = null   // null => immediate forced update

    // Views
    private var actionBar: ActionBar? = null
    private var toolbar: MaterialToolbar? = null
    private var connectionIndicator: View? = null
    private var noticeSMSLayout: View? = null
    private var noticeMandatoryUpdateLayout: View? = null
    private var ongoingCallNotice: OngoingCallNoticeView? = null
    private var identityPopup: IdentityPopup? = null
    private var bottomNavigationView: BottomNavigationView? = null
    private var mainContent: View? = null
    private var toolbarWarningButton: View? = null

    @ExperimentalBadgeUtils
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (finishAndRestartLaterIfNotReady()) {
            return
        }

        if (BackupService.isRunning() || RestoreService.isRunning()) {
            startActivity(BackupRestoreProgressActivity.createIntent(this))
            finish()
            return
        }

        if (ConfigUtils.isSerialLicensed() && !ConfigUtils.isSerialLicenseValid()) {
            if (shouldShowWorkIntroScreenUseCase.call()) {
                startActivity(WorkIntroActivity.createIntent(this))
            } else {
                enterSerialLauncher.launch(EnterSerialActivity.createIntent(this))
            }
            finish()
        } else {
            startHomeActivity(savedInstanceState)
            if (savedInstanceState == null && userService.hasIdentity() && ConfigUtils.isWorkRestricted()) {
                updateWorkConfiguration()
            }
        }
    }

    private fun updateWorkConfiguration() {
        val newConfig = ThreemaSafeMDMConfig.getInstance()
        if (newConfig.hasChanged(preferenceService)) {
            val oldMasterKey = threemaSafeService.getThreemaSafeMasterKey()
            if (newConfig.isBackupForced) {
                if (newConfig.isSkipBackupPasswordEntry) {
                    // enable with given password
                    val newMasterKey = threemaSafeService.deriveMasterKey(newConfig.password, newConfig.identity)

                    // show warning dialog only when password was changed
                    if (oldMasterKey != null && (newMasterKey == null || oldMasterKey.secureContentEquals(newMasterKey))) {
                        reconfigureThreemaSafe(newConfig)
                        enableThreemaSafe(newConfig)
                    } else {
                        val dialog = GenericAlertDialog.newInstance(
                            R.string.threema_safe,
                            R.string.safe_managed_new_password_confirm,
                            R.string.accept,
                            R.string.real_not_now,
                        )
                        dialog.setData(newConfig)
                        dialog.show(supportFragmentManager, DIALOG_TAG_PASSWORD_PRESET_CONFIRM)
                    }
                } else if (oldMasterKey != null && oldMasterKey.isNotEmpty()) {
                    // no password has been given by admin but a master key from a previous backup exists
                    // -> create a new backup with existing password
                    reconfigureThreemaSafe(newConfig)
                    enableThreemaSafe(newConfig, masterKeyPreset = threemaSafeService.getThreemaSafeMasterKey())
                } else {
                    reconfigureThreemaSafe(newConfig)
                    threemaSafeService.launchForcedPasswordDialog(this, true)
                    finish()
                    return
                }
            } else {
                reconfigureThreemaSafe(newConfig)
            }
        } else if (newConfig.isBackupForced && !preferenceService.getThreemaSafeEnabled()) {
            // config has not changed but safe is still not enabled. fix it.
            if (newConfig.isSkipBackupPasswordEntry) {
                // enable with given password
                enableThreemaSafe(newConfig)
            } else {
                // ask user for a new password
                threemaSafeService.launchForcedPasswordDialog(this, true)
                finish()
                return
            }
        }
        newConfig.saveConfig(preferenceService)
    }

    private fun reconfigureThreemaSafe(newConfig: ThreemaSafeMDMConfig) {
        try {
            // dispose of old backup, if any
            threemaSafeService.deleteBackup()
            threemaSafeService.setEnabled(false)
        } catch (_: Exception) {
            // ignore
        }
        preferenceService.setThreemaSafeServerInfo(newConfig.getServerInfo())
    }

    override fun onStart() {
        super.onStart()
        logger.info("HomeActivity started")

        lifecycleScope.launch {
            if (checkServerMessagesUseCase.call()) {
                startActivity(ServerMessageActivity.createIntent(context))
            }
        }
        viewModel.checkMultiDeviceGroup()
    }

    @UiThread
    private fun showMainContent() {
        mainContent?.let { mainContent ->
            if (mainContent.visibility != View.VISIBLE) {
                mainContent.visibility = View.VISIBLE
            }
        }
    }

    private fun updateWarningButton() {
        lifecycleScope.launch {
            toolbarWarningButton?.isVisible = getProblemsUseCase.call().isNotEmpty()
        }
    }

    /**
     * F1Whisper: show or refresh the mandatory-update banner / forced-update overlay.
     *
     * Gate logic (units: epoch seconds):
     *  - [cachedMandatoryUpdateUrl] null => no gate active, hide banner.
     *  - deadline present AND now < deadline => GRACE: non-dismissable countdown banner with "Update now".
     *  - deadline absent OR now >= deadline => FORCED: forced-update overlay (same banner, but the
     *    button text changes and there is no way to proceed without updating, since the banner
     *    covers the AppBar and is non-dismissable). The actual download reuses [ApkUpdateDownloadService]
     *    via [DownloadApkActivity] with the EXTRA_FORCE_UPDATE_DIALOG flag so the dialog always shows.
     *
     * NOTE: This gate is SOFT — it is a convenience/accident gate, NOT a security mechanism.
     * An AGPL self-installed client can bypass it. The user-facing copy is intentionally framed
     * as a server compatibility requirement, not a security enforcement.
     *
     * Must be called on the UI thread (it manipulates views).
     */
    @UiThread
    private fun updateMandatoryUpdateBanner() {
        val bannerLayout = noticeMandatoryUpdateLayout ?: return
        val updateUrl = cachedMandatoryUpdateUrl

        if (updateUrl == null) {
            // No gate active.
            bannerLayout.isVisible = false
            return
        }

        val bannerTitle = bannerLayout.findViewById<android.widget.TextView>(R.id.notice_mandatory_update_title)
        val bannerText = bannerLayout.findViewById<android.widget.TextView>(R.id.notice_mandatory_update_text)
        val bannerButton = bannerLayout.findViewById<android.widget.Button>(R.id.notice_mandatory_update_button)

        val deadlineSecs = cachedMandatoryDeadlineSecs
        val nowSecs = System.currentTimeMillis() / 1000L
        val isGrace = deadlineSecs != null && nowSecs < deadlineSecs

        // Same bold warning title in both states; only the supporting body differs.
        bannerTitle?.setText(R.string.mandatory_update_forced_title)
        bannerButton?.setText(R.string.mandatory_update_action)

        if (isGrace && deadlineSecs != null) {
            // Grace period: show countdown body with a LOCALIZED remaining duration.
            // Round UP so the user is never told they have less time than they do.
            // remainingSecs is > 0 here (isGrace requires nowSecs < deadlineSecs), but clamp
            // to >= 0 defensively so a clock skew can never produce a negative duration label.
            val remainingSecs = (deadlineSecs - nowSecs).coerceAtLeast(0L)
            val remainingHours = ((remainingSecs + 3599L) / 3600L).coerceAtLeast(1L)
            val durationLabel = if (remainingHours >= 48L) {
                val days = ((remainingHours + 23L) / 24L).toInt()
                resources.getQuantityString(R.plurals.mandatory_update_days, days, days)
            } else {
                val hours = remainingHours.toInt()
                resources.getQuantityString(R.plurals.mandatory_update_hours, hours, hours)
            }
            bannerText?.text = getString(R.string.mandatory_update_countdown, durationLabel)
        } else {
            // Past deadline (or no deadline) => forced update.
            bannerText?.setText(R.string.mandatory_update_forced_body)
        }

        // Wire the "Update now" / "Download" button to the existing download path.
        // EXTRA_FORCE_UPDATE_DIALOG bypasses DownloadApkActivity's snooze guard so the
        // dialog always appears regardless of when the user last dismissed it.
        bannerButton?.setOnClickListener {
            logger.info("Mandatory update: launching DownloadApkActivity for url={}", updateUrl)
            val downloadIntent = IntentDataUtil.createActionIntentUpdateAvailable(
                getString(R.string.mandatory_update_forced_body),
                updateUrl
            ).apply {
                setClass(this@HomeActivity, DownloadApkActivity::class.java)
                putExtra(DownloadApkActivity.EXTRA_FORCE_UPDATE_DIALOG, true)
            }
            startActivity(downloadIntent)
        }

        bannerLayout.isVisible = true
    }

    // TODO(ANDR-4480): This needs refactoring
    private fun showWhatsNew() {
        val skipWhatsNew = true // set this to false if you want to show a What's New screen

        if (!preferenceService.isLatestVersion(this)) {
            // so the app has just been updated
            ConfigUtils.requestNotificationPermission(this, notificationPermissionLauncher, preferenceService)

            if (preferenceService.getPrivacyPolicyAccepted() == null) {
                preferenceService.setPrivacyPolicyAccepted(Instant.now(), PreferenceService.PRIVACY_POLICY_ACCEPT_UPDATE)
            }

            if (!ConfigUtils.isWorkBuild() && !TestUtil.isInDeviceTest() && !isFinishing) {
                if (skipWhatsNew) {
                    // make sure isWhatsNewShown is set to false here if whatsnew is skipped - otherwise pin unlock will not be shown once
                    isWhatsNewShown = false
                } else {
                    val previous = preferenceService.getLatestVersion() % 10000

                    // To not show the same dialog twice, it is only shown if the previous version
                    // is prior to the first version that used this dialog.
                    // Use the version code of the first version where this dialog should be shown.
                    if (previous < 1069) {
                        isWhatsNewShown = true

                        whatsNewLauncher.launch(WhatsNewActivity.createIntent(this))
                        overridePendingTransition(R.anim.abc_fade_in, R.anim.abc_fade_out)
                    }
                }
            }
            preferenceService.setLatestVersion(this)
        }
    }

    private fun enableThreemaSafe(mdmConfig: ThreemaSafeMDMConfig, masterKeyPreset: ByteArray? = null) {
        lifecycleScope.launch {
            val masterKey = masterKeyPreset
                ?: withContext(dispatcherProvider.worker) {
                    threemaSafeService.deriveMasterKey(mdmConfig.password, userService.getIdentity())
                }
            if (masterKey != null) {
                threemaSafeService.storeMasterKey(masterKey)
                preferenceService.setThreemaSafeServerInfo(mdmConfig.getServerInfo())
                threemaSafeService.setEnabled(true)
                threemaSafeService.uploadNow(true)
            } else {
                showToast(R.string.safe_error_preparing, ToastDuration.LONG)
            }
        }
    }

    private fun showQRPopup() {
        val toolbar = toolbar ?: return
        identityPopup?.dismiss()
        identityPopup = IdentityPopup(this)
        identityPopup?.show(
            /* activity = */
            this,
            /* toolbarView = */
            toolbar,
            /* location = */
            getMiniAvatarLocation(toolbar),
            /* profileButtonListener = */
            {
                bottomNavigationView?.post { bottomNavigationView?.findViewById<View>(R.id.my_profile)?.performClick() }
            },
            /* onDismissListener = */
            { identityPopup = null },
        )
    }

    private fun getMiniAvatarLocation(toolbar: MaterialToolbar): IntArray =
        toolbar.getLocation(
            xOffset = toolbar.contentInsetLeft + with(resources) {
                getDimensionPixelSize(R.dimen.navigation_icon_padding) + getDimensionPixelSize(R.dimen.navigation_icon_size)
            } / 2,
            yOffset = toolbar.height / 2,
        )

    private fun checkApp() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(currentCheckAppReceiver)

            if (checkLicenseBroadcastReceiver != null) {
                unregisterReceiver(checkLicenseBroadcastReceiver)
            }
        } catch (_: IllegalArgumentException) {
            // not registered... ignore exceptions
        }

        // Register not licensed, update available, and mandatory-update broadcasts.
        val filter = IntentFilter().apply {
            addAction(IntentDataUtil.ACTION_LICENSE_NOT_ALLOWED)
            addAction(IntentDataUtil.ACTION_UPDATE_AVAILABLE)
            addAction(IntentDataUtil.ACTION_MANDATORY_UPDATE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(currentCheckAppReceiver, filter)

        checkLicense()
    }

    private fun checkLicense() {
        if (isLicenseCheckStarted) {
            return
        }

        if (deviceService.isOnline()) {
            RuntimeUtil.runOnWorkerThread(
                CheckLicenseRoutine(
                    this,
                    apiConnector,
                    userService,
                    deviceService,
                    licenseService,
                    identityStore,
                    appRestrictions,
                ),
            )
            isLicenseCheckStarted = true

            if (checkLicenseBroadcastReceiver != null) {
                try {
                    unregisterReceiver(checkLicenseBroadcastReceiver)
                } catch (e: IllegalArgumentException) {
                    logger.error("Failed to unregister checkLicenseBroadcastReceiver", e)
                }
            }
        } else {
            if (checkLicenseBroadcastReceiver == null) {
                checkLicenseBroadcastReceiver = broadcastReceiver { _, _ ->
                    logger.debug("receive connectivity change in main activity to check license")
                    checkLicense()
                }
                registerReceiver(
                    checkLicenseBroadcastReceiver,
                    IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION),
                )
            }
        }
    }

    private fun updateUnsentMessagesList(modifiedMessageModel: AbstractMessageModel, action: UnsentMessageAction) {
        synchronized(unsentMessages) {
            val numCurrentUnsent = unsentMessages.size

            val uid = modifiedMessageModel.uid
            // Check whether the message model with the same uid is already in the list or not
            var containedMessageModel: AbstractMessageModel? = null
            for (unsentMessage in unsentMessages) {
                if (unsentMessage.uid == uid) {
                    containedMessageModel = unsentMessage
                    break
                }
            }

            when (action) {
                UnsentMessageAction.ADD ->
                    // Only add the message model if it is not yet in the list
                    if (containedMessageModel == null) {
                        unsentMessages.add(modifiedMessageModel)
                    }
                UnsentMessageAction.REMOVE ->
                    // Remove message model if it is in the list
                    if (containedMessageModel != null) {
                        unsentMessages.remove(containedMessageModel)
                    }
            }

            if (numCurrentUnsent != unsentMessages.size) {
                notificationService.showUnsentMessageNotification(unsentMessages)
            }
        }
    }

    private fun startHomeActivity(savedInstanceState: Bundle?) {
        // at this point the app should be unlocked, licensed and updated
        if (isInitialized) {
            return
        }
        val isAppStart = savedInstanceState == null

        // TODO(ANDR-2816): Remove
        preferenceService.removeLastNotificationRationaleShown()

        preferenceService.setLastOnlineStatus(deviceService.isOnline())

        notificationService.cancelRestartNotification()

        bindListener(ListenerManager.messageListeners, messageListener)
        bindListener(ListenerManager.smsVerificationListeners, smsVerificationListener)
        bindListener(ListenerManager.appIconListeners, appIconListener)
        bindListener(ListenerManager.profileListeners, profileListener)
        bindListener(ListenerManager.voipCallListeners, voipCallListener)
        bindListener(ListenerManager.conversationListeners, conversationListener)
        bindListener(ListenerManager.contactCountListener, contactCountListener)

        groupCallManager.addGeneralGroupCallObserver(groupCallObserver)
        destroyer.own { groupCallManager.removeGeneralGroupCallObserver(groupCallObserver) }

        initHomeActivity(savedInstanceState)
        if (isAppStart && preferenceService.checkForAppUpdate(this)) {
            taskManager.schedule(ApplicationUpdateStepsTask())
        }
    }

    @UiThread
    private fun <T> bindListener(listeners: TypedListenerManager<T>, listener: T) {
        listeners.add(listener)
        destroyer.own { listeners.remove(listener) }
    }

    @UiThread
    private fun initHomeActivity(savedInstanceState: Bundle?) {
        val isAppStart = savedInstanceState == null

        // licensing
        checkApp()

        // start wizard if necessary
        val wizardIsRunning = notificationPreferenceService.getWizardRunning()
        if (wizardIsRunning || !userService.hasIdentity()) {
            if (userService.hasIdentity()) {
                logger.info("User identity exists. Wizard is running: {}", wizardIsRunning)
                startActivity(WizardBaseActivity.createIntent(this))
            } else {
                logger.info("No user identity exists. Wizard is running: {}", wizardIsRunning)
                startActivity(WizardStartActivity.createIntent(this))
            }
            finish()
            return
        }

        setContentView(R.layout.activity_home)

        // At this point, we know that the Wizard was completed, or skipped due to MDM, or a Threema Safe backup has been restored.
        // We now need to apply the Remote Secret protection if required, or persist the master key directly as so far it might have
        // been kept only in memory.
        if (masterKeyManager.shouldUpdateRemoteSecretProtectionState(RemoteSecretCheckType.APP_STARTUP)) {
            startActivity(RemoteSecretProtectionUpdateActivity.createIntent(this))
            finish()
            return
        } else {
            lifecycleScope.launch {
                try {
                    withContext(dispatcherProvider.io) {
                        masterKeyManager.persistKeyDataIfNeeded()
                    }
                } catch (e: Exception) {
                    logger.error("Failed to persist master key", e)
                    showToast(R.string.an_error_occurred)
                    finish()
                }
            }
            lifecycleScope.launch(dispatcherProvider.io) {
                debugLogHelper.disableDebugLogFileLoggingIfNeeded()
            }
        }

        initActionBar()
        updateAppLogo()
        actionBar?.setDisplayShowTitleEnabled(false)
        actionBar?.setDisplayUseLogoEnabled(false)
        ConfigUtils.applyScreenshotPolicy(this, synchronizedSettingsService, lockAppService)
        initConnectionIndicator()
        invalidateOptionsMenu()
        if (isAppStart) {
            enableThreemaPushIfNeeded()
        }

        mainContent = findViewById(R.id.main_content)
        toolbarWarningButton = findViewById(R.id.toolbar_warning)
        toolbarWarningButton?.setOnClickListener {
            problemSolverLauncher.launch(ProblemSolverActivity.createIntent(this))
        }
        findViewById<View>(R.id.notice_sms_button_enter_code)?.setOnClickListener {
            SMSVerificationDialog.newInstance(userService.getLinkedMobile(true))
                .show(supportFragmentManager, DIALOG_TAG_VERIFY_CODE)
        }
        findViewById<View>(R.id.notice_sms_button_cancel)?.setOnClickListener {
            GenericAlertDialog.newInstance(R.string.verify_title, R.string.really_cancel_verify, R.string.yes, R.string.no)
                .show(supportFragmentManager, DIALOG_TAG_CANCEL_VERIFY)
        }
        noticeSMSLayout = findViewById<View>(R.id.notice_sms_layout)
        noticeSMSLayout?.isVisible = userService.getMobileLinkingState() == UserService.LinkingState_PENDING

        // F1Whisper: mandatory-update banner wiring. The banner view is hidden by default in the
        // layout; updateMandatoryUpdateBanner() makes it visible when the gate is active.
        noticeMandatoryUpdateLayout = findViewById(R.id.notice_mandatory_update_layout)

        initOngoingCallNotice()

        var initialFragmentTag = FRAGMENT_TAG_MESSAGES
        val initialItemId: Int

        val contactsFragment: Fragment
        val messagesFragment: Fragment
        val profileFragment: Fragment

        if (intent != null && intent.getBooleanExtra(EXTRA_SHOW_CONTACTS, false)) {
            initialFragmentTag = FRAGMENT_TAG_CONTACTS
            intent.removeExtra(EXTRA_SHOW_CONTACTS)
        }

        if (!isAppStart && savedInstanceState.containsKey(BUNDLE_CURRENT_FRAGMENT_TAG)) {
            initialFragmentTag = savedInstanceState.getString(BUNDLE_CURRENT_FRAGMENT_TAG, initialFragmentTag)
            contactsFragment = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG_CONTACTS)!!
            messagesFragment = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG_MESSAGES)!!
            profileFragment = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG_PROFILE)!!
            currentFragmentTag = initialFragmentTag
            initialItemId = getFragmentIdForTag(initialFragmentTag)
            supportFragmentManager.runTransaction {
                when (initialFragmentTag) {
                    FRAGMENT_TAG_CONTACTS -> {
                        hide(messagesFragment)
                        hide(profileFragment)
                        show(contactsFragment)
                    }
                    FRAGMENT_TAG_MESSAGES -> {
                        hide(contactsFragment)
                        hide(profileFragment)
                        show(messagesFragment)
                    }
                    FRAGMENT_TAG_PROFILE -> {
                        hide(messagesFragment)
                        hide(contactsFragment)
                        show(profileFragment)
                    }
                }
            }
        } else {
            if (!conversationService.hasConversations()) {
                initialFragmentTag = FRAGMENT_TAG_CONTACTS
            }
            contactsFragment = ContactsSectionFragment()
            messagesFragment = ConversationsFragment()
            profileFragment = MyIDFragment()
            currentFragmentTag = initialFragmentTag
            initialItemId = getFragmentIdForTag(initialFragmentTag)
            try {
                supportFragmentManager.runTransaction(allowStateLoss = true) {
                    add(R.id.home_container, contactsFragment, FRAGMENT_TAG_CONTACTS)
                    add(R.id.home_container, messagesFragment, FRAGMENT_TAG_MESSAGES)
                    add(R.id.home_container, profileFragment, FRAGMENT_TAG_PROFILE)
                    when (initialFragmentTag) {
                        FRAGMENT_TAG_CONTACTS -> {
                            hide(messagesFragment)
                            hide(profileFragment)
                        }
                        FRAGMENT_TAG_MESSAGES -> {
                            hide(contactsFragment)
                            hide(profileFragment)
                        }
                        FRAGMENT_TAG_PROFILE -> {
                            hide(contactsFragment)
                            hide(messagesFragment)
                        }
                    }
                }
            } catch (e: IllegalStateException) {
                logger.error("Failed to set up fragments", e)
            }
        }

        bottomNavigationView = findViewById(R.id.bottom_navigation)
        bottomNavigationView?.setOnItemSelectedListener(
            NavigationBarView.OnItemSelectedListener { item ->
                showMainContent()

                // the contacts tab item badge uses custom colors (normally badges are red)
                bottomNavigationView?.getBadge(R.id.contacts)?.backgroundColor = getContactsTabBadgeColor(item.getItemId())

                val currentFragment = supportFragmentManager.findFragmentByTag(currentFragmentTag)
                    ?: return@OnItemSelectedListener false

                when (item.itemId) {
                    R.id.contacts -> consume {
                        logger.info("Contacts tab clicked")
                        if (currentFragmentTag != FRAGMENT_TAG_CONTACTS) {
                            logger.info("Switching to Contacts tab")
                            supportFragmentManager.runTransaction {
                                setCustomAnimations(R.anim.fast_fade_in, R.anim.fast_fade_out, R.anim.fast_fade_in, R.anim.fast_fade_out)
                                hide(currentFragment)
                                show(contactsFragment)
                            }
                            currentFragmentTag = FRAGMENT_TAG_CONTACTS
                        }
                    }
                    R.id.messages -> consume {
                        logger.info("Messages tab clicked")
                        if (currentFragmentTag != FRAGMENT_TAG_MESSAGES) {
                            logger.info("Switching to Messages tab")
                            supportFragmentManager.runTransaction {
                                setCustomAnimations(R.anim.fast_fade_in, R.anim.fast_fade_out, R.anim.fast_fade_in, R.anim.fast_fade_out)
                                hide(currentFragment)
                                show(messagesFragment)
                            }
                            currentFragmentTag = FRAGMENT_TAG_MESSAGES
                        }
                    }
                    R.id.my_profile -> consume {
                        logger.info("Profile tab clicked")
                        if (currentFragmentTag != FRAGMENT_TAG_PROFILE) {
                            logger.info("Switching to My Profile tab")
                            supportFragmentManager.runTransaction {
                                setCustomAnimations(R.anim.fast_fade_in, R.anim.fast_fade_out, R.anim.fast_fade_in, R.anim.fast_fade_out)
                                hide(currentFragment)
                                show(profileFragment)
                            }
                            currentFragmentTag = FRAGMENT_TAG_PROFILE
                        }
                    }
                    else -> false
                }
            },
        )
        bottomNavigationView?.post { bottomNavigationView?.setSelectedItemId(initialItemId) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window?.setNavigationBarContrastEnforced(false)
        }

        updateUnreadBadge()

        // restore sync adapter account if necessary
        if (synchronizedSettingsService.isSyncContacts()) {
            if (!userService.checkAccount()) {
                // create account
                userService.getAccount(true)
            }
            userService.enableAccountAutoSync(true)
        }

        isInitialized = true

        showStartupInfoIfNeeded(isAppStart)

        notificationService.cancelRestoreNotification()

        if (preferenceService.getLastNotificationPermissionRequestTimestamp() == null) {
            ConfigUtils.requestNotificationPermission(this, notificationPermissionLauncher, preferenceService)
        }
    }

    private fun initConnectionIndicator() {
        lifecycleScope.launch {
            withContext(dispatcherProvider.worker) {
                serverConnection.addConnectionStateListener(connectionStateListener)
                updateConnectionIndicator(serverConnection.connectionState)
            }
            destroyer.own {
                serverConnection.removeConnectionStateListener(connectionStateListener)
            }
        }
    }

    private fun getFragmentIdForTag(fragmentTag: String) =
        when (fragmentTag) {
            FRAGMENT_TAG_CONTACTS -> R.id.contacts
            FRAGMENT_TAG_MESSAGES -> R.id.messages
            FRAGMENT_TAG_PROFILE -> R.id.my_profile
            else -> R.id.messages
        }

    private fun enableThreemaPushIfNeeded() {
        if (BuildFlavor.current.forceThreemaPush) {
            preferenceService.setUseThreemaPush(true)
        } else if (!preferenceService.useThreemaPush() && !PushService.servicesInstalled(this)) {
            // If a non-libre build of Threema cannot find push services, fall back to Threema Push
            enableThreemaPush()
            if (!ConfigUtils.isAmazonDevice() && !ConfigUtils.isWorkBuild()) {
                val title = R.string.push_not_available_title
                val message = getString(R.string.push_not_available_text1) +
                    "\n\n" +
                    getString(R.string.push_not_available_text2, getString(R.string.app_name))
                ShowOnceDialog.newInstance(title, message).show(supportFragmentManager, "nopush")
            }
        }
    }

    private fun showStartupInfoIfNeeded(isAppStart: Boolean) {
        // TODO(ANDR-4480): Refactor the logic for displaying "Whats New", and ensure that "Whats New" and crash dialogs don't conflict
        showWhatsNew()
        showCrashDialogIfNeeded(isAppStart)
    }

    private fun showCrashDialogIfNeeded(isAppStart: Boolean) {
        @Suppress("KotlinConstantConditions")
        if (!BuildConfig.ERROR_REPORTING_SUPPORTED) {
            return
        }
        lifecycleScope.launch {
            when (errorReportingHelper.processPendingErrorRecords()) {
                ErrorReportingHelper.CheckResult.SHOW_DIALOG -> {
                    // TODO(ANDR-4480): Reconsider this delay
                    if (isAppStart) {
                        // Delay the dialog by a bit so the UI looks less hectic during startup
                        delay(1.seconds)
                    }
                    errorReportingDialog.showDialog(this@HomeActivity)
                }
                ErrorReportingHelper.CheckResult.DO_NOTHING -> Unit
            }
        }
    }

    private fun initOngoingCallNotice() {
        ongoingCallNotice = findViewById(R.id.ongoing_call_notice)
        updateOngoingCallNotice()
    }

    private fun updateOngoingCallNotice() {
        val groupCallController = groupCallManager.getCurrentGroupCallController()
        val hasRunningOneOnOneCall = VoipCallService.isRunning()
        val hasRunningGroupCall = groupCallController != null
        when {
            hasRunningOneOnOneCall && hasRunningGroupCall -> {
                logger.warn("Invalid state: joined 1:1 AND group call, not showing call notice")
                ongoingCallNotice?.hide()
            }
            hasRunningOneOnOneCall -> ongoingCallNotice?.showVoip()
            hasRunningGroupCall -> showOngoingGroupCallNotice(groupCallController.description)
            else -> ongoingCallNotice?.hide()
        }
    }

    @AnyThread
    private fun showOngoingGroupCallNotice(call: GroupCallDescription) {
        if (ConfigUtils.isGroupCallsEnabled() && groupCallManager.isJoinedCall(call)) {
            ongoingCallNotice?.showGroupCall(call, OngoingCallNoticeMode.MODE_GROUP_CALL_JOINED)
        }
    }

    private fun enableThreemaPush() {
        if (!preferenceService.useThreemaPush()) {
            preferenceService.setUseThreemaPush(true)
            ThreemaPushService.tryStart(logger, applicationContext)
        }
    }

    private fun updateUnreadBadge() {
        if (!preferenceService.getShowUnreadBadge()) {
            return
        }
        lifecycleScope.launch {
            val unreadCount = getUnreadConversationCountUseCase.call()
            val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation) ?: return@launch
            val badgeDrawable = bottomNavigationView.getOrCreateBadge(R.id.messages)
            if (badgeDrawable.verticalOffset == 0) {
                badgeDrawable.verticalOffset = resources.getDimensionPixelSize(R.dimen.bottom_nav_badge_offset_vertical)
            }
            badgeDrawable.number = unreadCount
            badgeDrawable.isVisible = unreadCount > 0
        }
    }

    private fun updateStarredMessages() {
        lifecycleScope.launch {
            starredMessagesCount = getStarredMessagesCountUseCase.call()
        }
    }

    private fun updateDrawerImage() {
        if (!userService.hasIdentity()) {
            return
        }
        val toolbar = toolbar ?: return
        lifecycleScope.launch {
            withContext(dispatcherProvider.worker) {
                contactService.getAvatar(
                    userService.getIdentity(),
                    AvatarOptions.Builder()
                        .setReturnPolicy(AvatarOptions.DefaultAvatarPolicy.DEFAULT_FALLBACK)
                        .toOptions(),
                )
                    ?.let { bitmap ->
                        val size = resources.getDimensionPixelSize(R.dimen.navigation_icon_size)
                        bitmap.scale(size, size).toDrawable(resources)
                    }
            }
                ?.let { drawable ->
                    toolbar.navigationIcon = drawable
                    toolbar.setNavigationContentDescription(R.string.open_myid_popup)
                }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu) = consume {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.activity_home, menu)
        ConfigUtils.addIconsToOverflowMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> consume {
                logger.info("Own avatar clicked")
                showQRPopup()
            }
            R.id.menu_lock -> consume {
                logger.info("Lock button clicked")
                lockAppService.lock()
            }
            R.id.menu_new_group -> consume {
                logger.info("New group button clicked")
                startActivity(GroupAddActivity.createIntent(this))
            }
            R.id.menu_new_distribution_list -> consume {
                logger.info("New distribution list button clicked")
                startActivity(DistributionListAddActivity.createIntent(this))
            }
            R.id.menu_manage_folders -> consume {
                logger.info("Manage folders button clicked")
                startActivity(ChatFoldersActivity.createIntent(this))
            }
            R.id.my_backups -> consume {
                logger.info("Backups button clicked")
                startActivity(BackupAdminActivity.createIntent(this))
            }
            R.id.webclient -> consume {
                logger.info("Web button clicked")
                startActivity(SessionsActivity.createIntent(this))
            }
            R.id.multi_device -> consume {
                logger.info("MD button clicked")
                startActivity(LinkedDevicesActivity.createIntent(this))
            }
            R.id.help -> consume {
                logger.info("Help button clicked")
                startActivity(SupportActivity.createIntent(this))
            }
            R.id.settings -> consume {
                logger.info("Settings button clicked")
                settingsLauncher.launch(SettingsActivity.createIntent(this))
            }
            R.id.directory -> consume {
                logger.info("Directory button clicked")
                startActivity(DirectoryActivity.createIntent(this))
            }
            R.id.threema_channel -> consume {
                logger.info("Threema channel button clicked")
                confirmThreemaChannel()
            }
            R.id.archived -> consume {
                logger.info("Archive button clicked")
                startActivity(ArchiveActivity.createIntent(this))
            }
            R.id.globalsearch -> consume {
                logger.info("Global search button clicked")
                startActivity(GlobalSearchActivity.createIntent(this))
            }
            R.id.starred_messages -> consume {
                logger.info("Starred messages button clicked")
                startActivity(StarredMessagesActivity.createIntent(this))
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun initActionBar() {
        toolbar = findViewById(R.id.main_toolbar)

        setSupportActionBar(toolbar)
        actionBar = supportActionBar

        findViewById<View>(R.id.appbar).applyDeviceInsetsAsPadding(ltr())

        val toolbarLogoMain = toolbar!!.findViewById<AppCompatImageView>(R.id.toolbar_logo_main)
        val layoutParams = toolbarLogoMain.layoutParams
        layoutParams.height = (ConfigUtils.getActionBarSize(this) / 3.5).toInt()
        toolbarLogoMain.setLayoutParams(layoutParams)
        toolbarLogoMain.setImageResource(R.drawable.logo_main)
        toolbarLogoMain.setColorFilter(ConfigUtils.getColorFromAttribute(this, R.attr.colorOnSurface), PorterDuff.Mode.SRC_IN)
        toolbarLogoMain.setContentDescription(getString(R.string.logo))
        toolbarLogoMain.setOnClickListener { onLogoClicked() }

        updateDrawerImage()
    }

    private fun onLogoClicked() {
        logger.info("Logo clicked")
        if (currentFragmentTag != null) {
            val currentFragment = supportFragmentManager.findFragmentByTag(currentFragmentTag)
            if (currentFragment != null && currentFragment.isAdded && !currentFragment.isHidden) {
                when (currentFragment) {
                    is ContactsSectionFragment -> currentFragment.onLogoClicked()
                    is MyIDFragment -> currentFragment.onLogoClicked()
                }
            }
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) = consume {
        super.onPrepareOptionsMenu(menu)

        menu.findItem(R.id.menu_lock)?.isVisible = lockAppService.isLockingEnabled()

        menu.findItem(R.id.menu_toggle_private_chats)?.let { privateChatToggleMenuItem ->
            if (preferenceService.arePrivateChatsHidden()) {
                privateChatToggleMenuItem.setIcon(R.drawable.ic_outline_visibility)
                privateChatToggleMenuItem.setTitle(R.string.title_show_private_chats)
            } else {
                privateChatToggleMenuItem.setIcon(R.drawable.ic_outline_visibility_off)
                privateChatToggleMenuItem.setTitle(R.string.title_hide_private_chats)
            }
            ConfigUtils.tintMenuIcon(this, privateChatToggleMenuItem, R.attr.colorOnSurface)
        }

        menu.findItem(R.id.my_backups)?.isVisible = checkBackupsFeatureEnabledUseCase.call()

        menu.findItem(R.id.directory)?.isVisible = ConfigUtils.isWorkBuild() && ConfigUtils.isWorkDirectoryEnabled()

        menu.findItem(R.id.threema_channel)?.isVisible = !ConfigUtils.isWorkBuild() && contactService.getByIdentity(THREEMA_CHANNEL_IDENTITY) == null

        menu.findItem(R.id.webclient)?.isVisible = !ConfigUtils.isWorkRestricted() || !appRestrictions.isWebDisabled()

        // If MD is currently locked, but was activated before, we still have to give access to the menu item
        menu.findItem(R.id.multi_device)?.isVisible = multiDeviceManager.isMultiDeviceActive || ConfigUtils.isMultiDeviceEnabled()

        menu.findItem(R.id.starred_messages)?.let { starredMessagesItem ->
            if (starredMessagesCount > 0) {
                starredMessagesItem.setTitle(getFormattedStarredMessagesLabel())
            } else {
                starredMessagesItem.setTitle(R.string.starred_messages)
            }
        }
    }

    private fun getFormattedStarredMessagesLabel(): SpannableString {
        val textAppearanceSpan = TextAppearanceSpan(applicationContext, R.style.Threema_TextAppearance_StarredMessages_Count)
        val starredMessagesCountString = if (starredMessagesCount > 99) "99+" else starredMessagesCount.toString()
        val spannableString = SpannableString(getString(R.string.starred_messages) + "   " + starredMessagesCountString)
        spannableString.setSpan(
            textAppearanceSpan,
            spannableString.length - starredMessagesCountString.length,
            spannableString.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return spannableString
    }

    override fun onCallRequested(tag: String?) {
        val mobileLinkingTime = userService.getMobileLinkingTime()
        if (mobileLinkingTime != null && (Instant.now() - mobileLinkingTime) < 10.minutes) {
            SimpleStringAlertDialog.newInstance(R.string.verify_phonecall_text, getString(R.string.wait_one_minute))
                .show(supportFragmentManager)
        } else {
            GenericAlertDialog.newInstance(R.string.verify_phonecall_text, R.string.prepare_call_message, R.string.ok, R.string.cancel)
                .show(supportFragmentManager, DIALOG_TAG_VERIFY_CODE_CONFIRM)
        }
    }

    override fun onYes(tag: String, code: String?) {
        when (tag) {
            DIALOG_TAG_VERIFY_CODE -> verifyPhoneCode(code)
        }
    }

    private fun verifyPhoneCode(code: String?) {
        logger.info("Verifying phone code")
        lifecycleScope.launch {
            val verificationResult = verifyMobileNumberUseCase.call(
                verificationCode = code,
            )

            when (verificationResult) {
                is VerifyMobileNumberUseCase.VerificationResult.Success -> {
                    showToast(R.string.verify_success_text, ToastDuration.LONG)
                    DialogUtil.dismissDialog(supportFragmentManager, DIALOG_TAG_VERIFY_CODE, true)
                }

                is VerifyMobileNumberUseCase.VerificationResult.Failure -> {
                    supportFragmentManager.runTransaction(allowStateLoss = true) {
                        add(
                            SimpleStringAlertDialog.newInstance(
                                R.string.error,
                                verificationResult.errorMessage.get(this@HomeActivity),
                            ),
                            null,
                        )
                    }
                }
            }
        }
    }

    override fun onYes(tag: String?, data: Any?) {
        when (tag) {
            DIALOG_TAG_VERIFY_CODE_CONFIRM -> {
                requestVerifyCall()
            }
            DIALOG_TAG_CANCEL_VERIFY -> cancelLinkVerify()
            DIALOG_TAG_SERIAL_LOCKED -> {
                logger.info("Retrying entering valid license confirmed")
                enterSerialLauncher.launch(EnterSerialActivity.createIntent(this))
                finish()
            }
            DIALOG_TAG_FINISH_UP -> exitProcess(0)
            DIALOG_TAG_THREEMA_CHANNEL_VERIFY -> addThreemaChannel()
            DIALOG_TAG_PASSWORD_PRESET_CONFIRM -> {
                if (data != null) {
                    reconfigureThreemaSafe(data as ThreemaSafeMDMConfig)
                    enableThreemaSafe(data)
                }
            }
        }
    }

    private fun requestVerifyCall() {
        logger.info("Requesting verify call")
        lifecycleScope.launch {
            val errorMessage = withContext(dispatcherProvider.worker) {
                try {
                    userService.makeMobileLinkCall()
                    null
                } catch (e: Exception) {
                    logger.error("Failed to make mobile link call", e)
                    if (e is LinkMobileNoException) {
                        e.message
                    } else {
                        getString(R.string.verify_failed_summary)
                    }
                }
            }
            if (errorMessage != null) {
                SimpleStringAlertDialog.newInstance(R.string.an_error_occurred, errorMessage)
                    .show(supportFragmentManager, "le")
            }
        }
    }

    private fun cancelLinkVerify() {
        AnimationUtil.collapse(noticeSMSLayout, null, true)
        lifecycleScope.launch(dispatcherProvider.worker) {
            try {
                userService.unlinkMobileNumber(TriggerSource.LOCAL)
            } catch (e: Exception) {
                logger.error("Failed to unlink mobile number", e)
            }
        }
    }

    override fun onNo(tag: String?, data: Any?) {
        when (tag) {
            DIALOG_TAG_SERIAL_LOCKED -> finish()
        }
    }

    public override fun onResume() {
        if (!isWhatsNewShown) {
            ActivityService.activityResumed(this)
        } else {
            isWhatsNewShown = false
        }

        if (masterKeyManager.isProtectedWithPassphrase() && !PassphraseService.isRunning()) {
            PassphraseService.start(this)
        }

        // TODO(ANDR-4565): This worker should run periodically, instead of being explicitly started here
        TempFilesCleanupWorker.enqueue(this, fileAgeThreshold = 2.hours)

        updateStarredMessages()
        super.onResume()

        showMainContent()
        updateWarningButton()

        // F1Whisper: refresh the mandatory-update banner on every resume so the countdown text
        // stays current (a grace period may have expired since we last showed the banner).
        if (BuildFlavor.current.maySelfUpdate) {
            updateMandatoryUpdateBanner()
        }

        // F1Whisper: re-prompt for a pending self-update on every resume, but at most once per hour.
        // The initial show (from the broadcast) already wrote PREF_SELF_UPDATE_LAST_SHOWN_MS; here we
        // honour that cooldown so the user is not spammed across rapid foreground/background cycles.
        // Scoped strictly to the self-updater path: the cachedSelfUpdateIntent is set only when
        // ACTION_UPDATE_AVAILABLE fires for a maySelfUpdate flavor (onprem), and is never cleared by
        // unrelated license-check results.
        val cachedUpdate = cachedSelfUpdateIntent
        if (BuildFlavor.current.maySelfUpdate && userService.hasIdentity() && cachedUpdate != null) {
            val lastShown = android.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
                .getLong(PREF_SELF_UPDATE_LAST_SHOWN_MS, 0L)
            if (System.currentTimeMillis() - lastShown >= android.text.format.DateUtils.HOUR_IN_MILLIS) {
                logger.info("Re-prompting for cached self-update (>=1 h since last shown).")
                val dialogIntent = Intent(cachedUpdate)
                dialogIntent.setClass(this, DownloadApkActivity::class.java)
                android.preference.PreferenceManager.getDefaultSharedPreferences(this)
                    .edit()
                    .putLong(PREF_SELF_UPDATE_LAST_SHOWN_MS, System.currentTimeMillis())
                    .apply()
                startActivity(dialogIntent)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        ActivityService.activityPaused(this)
    }

    override fun onUserInteraction() {
        ActivityService.activityUserInteract(this)
    }

    private fun updateAppLogo() {
        if (!ConfigUtils.isWorkBuild()) {
            return
        }
        lifecycleScope.launch {
            val headerImageView = toolbar?.findViewById<ImageView>(R.id.toolbar_logo_main)
                ?: return@launch
            val customAppLogo = withContext(dispatcherProvider.worker) {
                fileService.getAppLogo(
                    ConfigUtils.getAppThemeSettingFromDayNightMode(ConfigUtils.getCurrentDayNightMode(context)),
                )
            }
                ?: return@launch
            headerImageView.clearColorFilter()
            Glide.with(this@HomeActivity)
                .load(customAppLogo)
                .into(headerImageView)
        }
    }

    // TODO(ANDR-4481): This needs refactoring
    @SuppressLint("StaticFieldLeak")
    private fun addThreemaChannel() {
        logger.info("Adding Threema channel")
        backgroundExecutor.execute(
            object : BasicAddOrUpdateContactBackgroundTask(
                identity = THREEMA_CHANNEL_IDENTITY,
                acquaintanceLevel = ContactModel.AcquaintanceLevel.DIRECT,
                myIdentity = userService.getIdentity()!!,
                apiConnector = apiConnector,
                contactModelRepository = contactModelRepository,
                addContactRestrictionPolicy = AddContactRestrictionPolicy.IGNORE,
                appRestrictions = appRestrictions,
                expectedPublicKey = ContactServiceImpl.THREEMA_PUBLIC_KEY,
            ) {
                override fun onBefore() {
                    GenericProgressDialog.newInstance(R.string.threema_channel, R.string.please_wait)
                        .show(supportFragmentManager, DIALOG_TAG_THREEMA_CHANNEL_PROGRESS)
                }

                override fun onFinished(result: ContactResult) {
                    DialogUtil.dismissDialog(supportFragmentManager, DIALOG_TAG_THREEMA_CHANNEL_PROGRESS, true)

                    if (result is ContactAvailable) {
                        // In case the contact has been successfully created or it has been  modified, already verified, or already exists,
                        // the threema channel conversation is launched.
                        launchThreemaChannelConversation()

                        // Send initial messages to threema channel only if the threema channel has been newly created as a contact and
                        // did not exist before.
                        if (result is ContactCreated) {
                            appTaskExecutor.scheduleTask {
                                setUpThreemaChannelUseCase.call()
                            }
                        }
                    } else {
                        showToast(R.string.internet_connection_required, ToastDuration.LONG)
                    }
                }
            },
        )
    }

    private fun launchThreemaChannelConversation() {
        startActivity(ComposeMessageActivity.createIntent(this, ContactReceiverIdentifier(THREEMA_CHANNEL_IDENTITY)))
    }

    @AnyThread
    private fun updateConnectionIndicator(connectionState: ConnectionState?) {
        logger.debug("connectionState = {}", connectionState)
        lifecycleScope.launch {
            connectionIndicator = findViewById<View>(R.id.connection_indicator)
            if (connectionIndicator != null) {
                ConnectionIndicatorUtil.getInstance().updateConnectionIndicator(connectionIndicator, connectionState)
            }
        }
    }

    private fun confirmThreemaChannel() {
        if (contactService.getByIdentity(THREEMA_CHANNEL_IDENTITY) == null) {
            GenericAlertDialog.newInstance(R.string.threema_channel, R.string.threema_channel_intro, R.string.ok, R.string.cancel, 0)
                .show(supportFragmentManager, DIALOG_TAG_THREEMA_CHANNEL_VERIFY)
        } else {
            launchThreemaChannelConversation()
        }
    }

    private fun showErrorTextAndExit(text: String?) {
        GenericAlertDialog.newInstance(R.string.error, text, R.string.finish, 0)
            .show(supportFragmentManager, DIALOG_TAG_FINISH_UP)
    }

    /**
     * @return The correct color to ensure contrast. If the navigation bar item is **not** selected, we use the `onSurfaceVariant`
     * because the material library paints icons in `onSurfaceVariant`. So we match. If the item is selected, we have to use
     * `onPrimaryContainer` since we customized our navigation bar active indicator to have the color of `primaryContainer`.
     * @see "@style/Threema.BottomNavigationView"
     */
    @ColorInt
    private fun getContactsTabBadgeColor(@IdRes currentlySelectedMenuItemId: Int): Int =
        ConfigUtils.getColorFromAttribute(
            this,
            if (currentlySelectedMenuItemId == R.id.contacts) R.attr.colorOnPrimaryContainer else R.attr.colorOnSurfaceVariant,
        )

    override fun onSaveInstanceState(outState: Bundle) {
        try {
            super.onSaveInstanceState(outState)
        } catch (e: Exception) {
            logger.error("Failed to save sate state", e)
        }
        if (currentFragmentTag != null) {
            outState.putString(BUNDLE_CURRENT_FRAGMENT_TAG, currentFragmentTag)
        }
    }

    override fun onDestroy() {
        ActivityService.activityDestroyed(this)

        identityPopup?.dismiss()
        identityPopup = null

        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(currentCheckAppReceiver)
            checkLicenseBroadcastReceiver?.let(::unregisterReceiver)
        } catch (_: IllegalArgumentException) {
            // not registered, ignore exceptions
        }

        super.onDestroy()
    }

    private enum class UnsentMessageAction {
        ADD,
        REMOVE,
    }

    companion object {
        // F1Whisper: shared-pref key for the last time the self-update prompt was shown (ms epoch).
        // Shared with DownloadApkActivity's PREF_STRING via the same default SharedPreferences
        // instance, but stored under a distinct key to avoid clobbering the snooze dismissal time.
        private const val PREF_SELF_UPDATE_LAST_SHOWN_MS = "self_update_last_shown_ms"

        private const val DIALOG_TAG_VERIFY_CODE = "vc"
        private const val DIALOG_TAG_VERIFY_CODE_CONFIRM = "vcc"
        private const val DIALOG_TAG_CANCEL_VERIFY = "cv"
        private const val DIALOG_TAG_SERIAL_LOCKED = "sll"
        private const val DIALOG_TAG_FINISH_UP = "fup"
        private const val DIALOG_TAG_THREEMA_CHANNEL_VERIFY = "tcv"
        private const val DIALOG_TAG_THREEMA_CHANNEL_PROGRESS = "tcp"
        private const val DIALOG_TAG_PASSWORD_PRESET_CONFIRM = "pwconf"

        private const val FRAGMENT_TAG_MESSAGES = "0"
        private const val FRAGMENT_TAG_CONTACTS = "1"
        private const val FRAGMENT_TAG_PROFILE = "2"

        private const val BUNDLE_CURRENT_FRAGMENT_TAG = "currentFragmentTag"

        private const val EXTRA_SHOW_CONTACTS = "show_contacts"

        @JvmStatic
        @JvmOverloads
        fun createIntent(context: Context, showContacts: Boolean = false) = buildActivityIntent<HomeActivity>(context) {
            if (showContacts) {
                putExtra(EXTRA_SHOW_CONTACTS, true)
            }
        }
    }
}
