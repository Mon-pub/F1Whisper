package ch.threema.app.managers

import ch.threema.app.emojis.EmojiService
import ch.threema.app.multidevice.MultiDeviceManager
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.preference.service.SynchronizedSettingsService
import ch.threema.app.profilepicture.GroupProfilePictureUploader
import ch.threema.app.protocolsteps.IdentityBlockedSteps
import ch.threema.app.services.ApiService
import ch.threema.app.services.AutoResendService
import ch.threema.app.services.BlockedIdentitiesService
import ch.threema.app.services.ChatFolderService
import ch.threema.app.services.ContactService
import ch.threema.app.services.ConversationCategoryService
import ch.threema.app.services.ConversationService
import ch.threema.app.services.ConversationTagService
import ch.threema.app.services.DeviceService
import ch.threema.app.services.DistributionListService
import ch.threema.app.services.DownloadService
import ch.threema.app.services.ExcludedSyncIdentitiesService
import ch.threema.app.services.FileService
import ch.threema.app.services.GroupFlowDispatcher
import ch.threema.app.services.GroupService
import ch.threema.app.services.LifetimeService
import ch.threema.app.services.LocaleService
import ch.threema.app.services.LockAppService
import ch.threema.app.services.MessageService
import ch.threema.app.services.ProfilePictureRecipientsService
import ch.threema.app.services.RingtoneService
import ch.threema.app.services.SensorService
import ch.threema.app.services.ServerAddressProviderService
import ch.threema.app.services.SynchronizeContactsService
import ch.threema.app.services.UserService
import ch.threema.app.services.WallpaperService
import ch.threema.app.services.avatarcache.AvatarCacheService
import ch.threema.app.services.license.LicenseService
import ch.threema.app.services.messageplayer.MessagePlayerService
import ch.threema.app.services.notification.NotificationService
import ch.threema.app.services.poll.PollService
import ch.threema.app.stores.EncryptedPreferenceStore
import ch.threema.app.tasks.TaskCreator
import ch.threema.app.threemasafe.ThreemaSafeService
import ch.threema.app.voip.groupcall.GroupCallManager
import ch.threema.app.voip.groupcall.sfu.SfuConnection
import ch.threema.app.voip.services.VoipStateService
import ch.threema.app.webclient.manager.WebClientServiceManager
import ch.threema.base.SessionScoped
import ch.threema.base.crypto.NonceFactory
import ch.threema.base.utils.getThreemaLogger
import ch.threema.data.IdentityProvider
import ch.threema.data.repositories.ModelRepositories
import ch.threema.domain.protocol.api.APIConnector
import ch.threema.domain.protocol.connection.ServerConnection
import ch.threema.domain.protocol.connection.data.leBytes
import ch.threema.domain.protocol.csp.fs.ForwardSecurityMessageProcessor
import ch.threema.domain.stores.ContactStore
import ch.threema.domain.stores.DHSessionStore
import ch.threema.domain.stores.IdentityStore
import ch.threema.domain.taskmanager.TaskManager
import ch.threema.storage.DatabaseService
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.mp.KoinPlatform

private val logger = getThreemaLogger("ServiceManager")

@Deprecated("Use Koin instead")
@SessionScoped
class ServiceManager : KoinComponent {
    @Deprecated("Use Koin instead")
    val identityStore: IdentityStore
        get() = get()

    @Deprecated("Use Koin instead")
    val taskManager: TaskManager
        get() = get()

    @Deprecated("Use Koin instead")
    val multiDeviceManager: MultiDeviceManager
        get() = get()

    @Deprecated("Use Koin instead")
    val nonceFactory: NonceFactory
        get() = get()

    @Deprecated("Use Koin instead")
    val modelRepositories: ModelRepositories
        get() = get()

    @Deprecated("Use Koin instead")
    val connection: ServerConnection
        get() = get()

    @Deprecated("Use Koin instead")
    val contactStore: ContactStore
        get() = get()

    @Deprecated("Use Koin instead")
    val contactService: ContactService
        get() = get()

    @Deprecated("Use Koin instead")
    val userService: UserService
        get() = get()

    @Deprecated("Use Koin instead")
    val messageService: MessageService
        get() = get()

    /**
     * F1Whisper auto-resend: the orchestrator that silently re-sends unsent outgoing messages once
     * connectivity returns. Trigger it via [AutoResendService.scheduleScan] on every CSP
     * `LOGGED_IN` transition and once at app start.
     */
    @Deprecated("Use Koin instead")
    val autoResendService: AutoResendService
        get() = get()

    @Deprecated("Use Koin instead")
    val chatFolderService: ChatFolderService
        get() = get()

    @Deprecated("Use Koin instead")
    val localeService: LocaleService
        get() = get()

    @Deprecated("Use Koin instead")
    val deviceService: DeviceService
        get() = get()

    @Deprecated("Use Koin instead")
    val lifetimeService: LifetimeService
        get() = get()

    @Deprecated("Use Koin instead")
    val licenseService: LicenseService<*>
        get() = get()

    @Deprecated("Use Koin instead")
    val groupService: GroupService
        get() = get()

    @Deprecated("Use Koin instead")
    val groupProfilePictureUploader: GroupProfilePictureUploader
        get() = get()

    @Deprecated("Use Koin instead")
    val apiService: ApiService
        get() = get()

    @Deprecated("Use Koin instead")
    val conversationService: ConversationService
        get() = get()

    @Deprecated("Use Koin instead")
    val notificationService: NotificationService
        get() = get()

    @Deprecated("Use Koin instead")
    val synchronizeContactsService: SynchronizeContactsService
        get() = get()

    @Deprecated("Use Koin instead")
    val blockedIdentitiesService: BlockedIdentitiesService
        get() = get()

    @Deprecated("Use Koin instead")
    val excludedSyncIdentitiesService: ExcludedSyncIdentitiesService
        get() = get()

    @Deprecated("Use Koin instead")
    val conversationCategoryService: ConversationCategoryService
        get() = get()

    @Deprecated("Use Koin instead")
    val distributionListService: DistributionListService
        get() = get()

    @Deprecated("Use Koin instead")
    val messagePlayerService: MessagePlayerService
        get() = get()

    @Deprecated("Use Koin instead")
    val downloadService: DownloadService
        get() = get()

    @Deprecated("Use Koin instead")
    val pollService: PollService
        get() = get()

    @Deprecated("Use Koin instead")
    val wallpaperService: WallpaperService
        get() = get()

    @Deprecated("Use Koin instead")
    val threemaSafeService: ThreemaSafeService
        get() = get()

    @Deprecated("Use Koin instead")
    val sensorService: SensorService
        get() = get()

    @Deprecated("Use Koin instead")
    val voipStateService: VoipStateService
        get() = get()

    @Deprecated("Use Koin instead")
    val groupCallManager: GroupCallManager
        get() = get()

    @Deprecated("Use Koin instead")
    val sfuConnection: SfuConnection
        get() = get()

    @Deprecated("Use Koin instead")
    val conversationTagService: ConversationTagService
        get() = get()

    @Deprecated("Use Koin instead")
    val serverAddressProviderService: ServerAddressProviderService
        get() = get()

    @Deprecated("Use Koin instead")
    val webClientServiceManager: WebClientServiceManager
        get() = get()

    @Deprecated("Use Koin instead")
    val emojiService: EmojiService
        get() = get()

    @Deprecated("Use Koin instead")
    val taskCreator: TaskCreator
        get() = get()

    @Deprecated("Use Koin instead")
    val groupFlowDispatcher: GroupFlowDispatcher
        get() = get()

    @Deprecated("Use Koin instead")
    val apiConnector: APIConnector
        get() = get()

    @Deprecated("Use Koin instead")
    val synchronizedSettingsService: SynchronizedSettingsService
        get() = get()

    @Deprecated("Use Koin instead")
    val profilePicRecipientsService: ProfilePictureRecipientsService
        get() = get()

    @Deprecated("Use Koin instead")
    val forwardSecurityMessageProcessor: ForwardSecurityMessageProcessor
        get() = get()

    @Deprecated("Use Koin instead")
    val dhSessionStore: DHSessionStore
        get() = get()

    @Deprecated("Use Koin instead")
    val fileService: FileService
        get() = get()

    @Deprecated("Use Koin instead")
    val avatarCacheService: AvatarCacheService
        get() = get()

    @Deprecated("Use Koin instead")
    val lockAppService: LockAppService
        get() = get()

    @Deprecated("Use Koin instead")
    val identityBlockedSteps: IdentityBlockedSteps
        get() = get()

    @Deprecated("Use Koin instead")
    val okHttpClient: OkHttpClient
        get() = get()

    @Deprecated("Use Koin instead")
    val ringtoneService: RingtoneService
        get() = get()

    @Deprecated("Use Koin instead")
    val databaseService: DatabaseService
        get() = get()

    @Deprecated("Use Koin instead")
    val encryptedPreferenceStore: EncryptedPreferenceStore
        get() = get()

    @Deprecated("Use Koin instead")
    val preferenceService: PreferenceService
        get() = get()

    /**
     * Start the server connection. Do not call this directly; use the LifetimeService!
     */
    // TODO(ANDR-4680): This method should be moved out into a dedicated connection manager class
    @Throws(NoIdentityException::class)
    fun startConnection() {
        if (!get<IdentityProvider>().hasIdentity()) {
            throw NoIdentityException()
        }
        if (multiDeviceManager.isMultiDeviceActive) {
            val properties = multiDeviceManager.propertiesProvider.get()
            logger.info("Starting connection (mediatorDeviceId = {})", properties.mediatorDeviceId.leBytes().toHexString())
        } else {
            logger.info("Starting connection")
        }
        connection.start()
    }

    class NoIdentityException : Exception()

    /**
     * Stop the connection. Do not call this directly; use the LifetimeService!
     */
    // TODO(ANDR-4680): This method should be moved out into a dedicated connection manager class
    @Throws(InterruptedException::class)
    fun stopConnection() {
        logger.info("Stopping connection")
        try {
            connection.stop()
        } catch (e: InterruptedException) {
            logger.error("Interrupted while stopping connection")
            Thread.currentThread().interrupt()
            throw e
        }
    }

    companion object {
        @Deprecated("Do not access service manager directly, use DI instead")
        @JvmStatic
        fun get(): ServiceManager? = KoinPlatform.getKoinOrNull()?.getOrNull<ServiceManager>()

        @Deprecated("Do not access service manager directly, use DI instead")
        @JvmStatic
        fun require(): ServiceManager = get() ?: throw NullPointerException("ServiceManager was null")
    }
}
