package ch.threema.app.di.modules

import ch.threema.app.backuprestore.ExportConversationService
import ch.threema.app.di.DependencyContainer
import ch.threema.app.di.SessionScopeContainer
import ch.threema.app.di.SessionScopeContainerHolder
import ch.threema.app.di.isSessionScopeReady
import ch.threema.app.emojis.EmojiService
import ch.threema.app.managers.ServiceManager
import ch.threema.app.multidevice.MultiDeviceManager
import ch.threema.app.preference.service.SynchronizedSettingsService
import ch.threema.app.profilepicture.GroupProfilePictureUploader
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
import ch.threema.app.services.GroupFlowDispatcher
import ch.threema.app.services.GroupService
import ch.threema.app.services.LifetimeService
import ch.threema.app.services.LocaleService
import ch.threema.app.services.MessageService
import ch.threema.app.services.ProfilePictureRecipientsService
import ch.threema.app.services.SensorService
import ch.threema.app.services.ServerAddressProviderService
import ch.threema.app.services.SynchronizeContactsService
import ch.threema.app.services.UserService
import ch.threema.app.services.WallpaperService
import ch.threema.app.services.license.LicenseService
import ch.threema.app.services.messageplayer.MessagePlayerService
import ch.threema.app.services.notification.NotificationService
import ch.threema.app.services.poll.PollService
import ch.threema.app.tasks.TaskCreator
import ch.threema.app.threemasafe.ThreemaSafeService
import ch.threema.app.utils.OutgoingCspMessageServices
import ch.threema.app.voip.groupcall.GroupCallManager
import ch.threema.app.voip.groupcall.sfu.SfuConnection
import ch.threema.app.voip.services.VoipStateService
import ch.threema.app.webclient.manager.WebClientServiceManager
import ch.threema.app.webclient.services.SessionService
import ch.threema.base.crypto.NonceFactory
import ch.threema.data.repositories.ContactModelRepository
import ch.threema.data.repositories.EditHistoryRepository
import ch.threema.data.repositories.EmojiReactionsRepository
import ch.threema.data.repositories.GroupModelRepository
import ch.threema.data.repositories.ModelRepositories
import ch.threema.domain.models.LicenseCredentials
import ch.threema.domain.protocol.ServerAddressProvider
import ch.threema.domain.protocol.api.APIConnector
import ch.threema.domain.protocol.connection.ServerConnection
import ch.threema.domain.protocol.csp.fs.ForwardSecurityMessageProcessor
import ch.threema.domain.stores.ContactStore
import ch.threema.domain.stores.DHSessionStore
import ch.threema.domain.stores.IdentityStore
import ch.threema.domain.taskmanager.TaskManager
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

/**
 * Provides the common components which depend (directly or indirectly) on the master key being unlocked.
 *
 * To check whether a component from this module is available, use [isSessionScopeReady] or use Koin's `getOrNull`.
 * All components in this module should be annotated with [ch.threema.base.SessionScoped], if possible.
 */
val sessionScopedModule = module {
    factory<ServerAddressProvider?> { get<ServerAddressProviderService>().serverAddressProvider }
    factory<SessionService> { get<WebClientServiceManager>().sessionService }
    factoryOf(::OutgoingCspMessageServices)

    sessionScoped<ServiceManager> { serviceManager }

    sessionScoped<APIConnector> { apiConnector }
    sessionScoped<ApiService> { apiService }
    sessionScoped<AutoResendService> { autoResendService }
    sessionScoped<BlockedIdentitiesService> { blockedIdentitiesService }
    sessionScoped<ChatFolderService> { chatFolderService }
    sessionScoped<ContactModelRepository> { modelRepositories.contacts }
    sessionScoped<ContactService> { contactService }
    sessionScoped<ContactStore> { contactStore }
    sessionScoped<ConversationCategoryService> { conversationCategoryService }
    sessionScoped<ConversationService> { conversationService }
    sessionScoped<ConversationTagService> { conversationTagService }
    sessionScoped<DHSessionStore> { dhSessionStore }
    sessionScoped<DeviceService> { deviceService }
    sessionScoped<DistributionListService> { distributionListService }
    sessionScoped<DownloadService> { downloadService }
    sessionScoped<EditHistoryRepository> { modelRepositories.editHistory }
    sessionScoped<EmojiReactionsRepository> { modelRepositories.emojiReaction }
    sessionScoped<EmojiService> { emojiService }
    sessionScoped<ExcludedSyncIdentitiesService> { excludedSyncIdentitiesService }
    sessionScoped<ExportConversationService> { exportConversationService }
    sessionScoped<ForwardSecurityMessageProcessor> { forwardSecurityMessageProcessor }
    sessionScoped<GroupCallManager> { groupCallManager }
    sessionScoped<GroupFlowDispatcher> { groupFlowDispatcher }
    sessionScoped<GroupModelRepository> { modelRepositories.groups }
    sessionScoped<GroupProfilePictureUploader> { groupProfilePictureUploader }
    sessionScoped<GroupService> { groupService }
    sessionScoped<IdentityStore> { identityStore }
    sessionScoped<LicenseService<out LicenseCredentials>> { licenseService }
    sessionScoped<LifetimeService> { lifetimeService }
    sessionScoped<LocaleService> { localeService }
    sessionScoped<MessagePlayerService> { messagePlayerService }
    sessionScoped<MessageService> { messageService }
    sessionScoped<ModelRepositories> { modelRepositories }
    sessionScoped<MultiDeviceManager> { multiDeviceManager }
    sessionScoped<NonceFactory> { nonceFactory }
    sessionScoped<NotificationService> { notificationService }
    sessionScoped<PollService> { pollService }
    sessionScoped<ProfilePictureRecipientsService> { profilePicRecipientsService }
    sessionScoped<SensorService> { sensorService }
    sessionScoped<ServerAddressProviderService> { serverAddressProviderService }
    sessionScoped<ServerConnection> { connection }
    sessionScoped<SfuConnection> { sfuConnection }
    sessionScoped<SynchronizeContactsService> { synchronizeContactsService }
    sessionScoped<SynchronizedSettingsService> { synchronizedSettingsService }
    sessionScoped<TaskCreator> { taskCreator }
    sessionScoped<TaskManager> { taskManager }
    sessionScoped<ThreemaSafeService> { threemaSafeService }
    sessionScoped<UserService> { userService }
    sessionScoped<VoipStateService> { voipStateService }
    sessionScoped<WallpaperService> { wallpaperService }
    sessionScoped<WebClientServiceManager> { webClientServiceManager }

    factoryOf(::DependencyContainer)
}

private inline fun <reified T : Any> Module.sessionScoped(noinline bind: SessionScopeContainer.() -> T) {
    factory<T?> {
        get<SessionScopeContainerHolder>().sessionScopeContainer?.bind()
    }
}
