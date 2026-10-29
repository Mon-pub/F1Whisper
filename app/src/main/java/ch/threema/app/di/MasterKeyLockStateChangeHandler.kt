package ch.threema.app.di

import android.content.Context
import ch.threema.app.GlobalAppState
import ch.threema.app.GlobalListeners
import ch.threema.app.androidcontactsync.AndroidContactChangeMonitor
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.push.PushService
import ch.threema.app.services.avatarcache.AvatarCacheService
import ch.threema.app.startup.AppStartupMonitorImpl
import ch.threema.app.systemupdates.SystemUpdateState
import ch.threema.app.utils.ConfigUtils
import ch.threema.app.utils.PushUtil
import ch.threema.app.utils.ShortcutUtil
import ch.threema.app.voip.util.VoipUtil
import ch.threema.app.webclient.services.instance.DisconnectContext
import ch.threema.app.workers.ShareTargetUpdateWorker
import ch.threema.base.utils.getThreemaLogger
import ch.threema.common.DispatcherProvider
import ch.threema.domain.protocol.connection.ConnectionState
import ch.threema.storage.DatabaseProviderImpl
import ch.threema.storage.DatabaseState
import java.time.Instant
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private val logger = getThreemaLogger("MasterKeyLockStateChangeHandler")

// TODO(ANDR-4729): The logic in this class should be split up into monitors or similar constructs
class MasterKeyLockStateChangeHandler(
    private val appContext: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val appStartupMonitor: AppStartupMonitorImpl,
    private val sessionScopeContainerHolder: SessionScopeContainerHolder,
    private val preferenceService: PreferenceService,
    private val databaseProvider: DatabaseProviderImpl,
    private val shareTargetUpdateWorkerScheduler: ShareTargetUpdateWorker.Scheduler,
    private val avatarCacheService: AvatarCacheService,
    private val globalAppState: GlobalAppState,
    private val androidContactChangeMonitor: AndroidContactChangeMonitor,
) {

    private val coroutineScope = CoroutineScope(dispatcherProvider.worker)

    @Volatile
    private var globalListeners: GlobalListeners? = null

    @Volatile
    private var observeConnectionStateJob: Job? = null
    private var observeLoginsJob: Job? = null

    fun onMasterKeyUnlocked(
        sessionScopeContainer: SessionScopeContainer,
        databaseState: StateFlow<DatabaseState>,
        systemUpdateState: StateFlow<SystemUpdateState>,
    ) {
        sessionScopeContainerHolder.sessionScopeContainer = sessionScopeContainer

        appStartupMonitor.onMasterKeyUnlocked(databaseState, systemUpdateState)
        sessionScopeContainer.multiDeviceManagerImpl.setReconnectHandle(sessionScopeContainer.convertibleServerConnection)

        globalListeners = GlobalListeners(appContext, androidContactChangeMonitor, sessionScopeContainer.serviceManager).apply {
            setUp()
        }

        // TODO(ANDR-4683): This needs to be turned into a proper monitor
        observeConnectionStateJob = coroutineScope.launch {
            sessionScopeContainer.serviceManager.connection.watchConnectionState().collect { connectionState ->
                logger.info("ServerConnection state changed: {}", connectionState)
                if (connectionState == ConnectionState.LOGGED_IN) {
                    globalAppState.lastLoggedIn = Instant.now()

                    if (PushService.servicesInstalled(appContext) && PushUtil.isPushEnabled(appContext)) {
                        if (PushUtil.pushTokenNeedsRefresh(appContext)) {
                            PushUtil.enqueuePushTokenUpdate(appContext, false, false)
                        } else {
                            logger.debug("Push token is still fresh. No update needed")
                        }
                    }
                }
            }
        }

        // F1Whisper auto-resend: connectivity is back, so silently re-send any unsent outgoing messages (media/file/poll
        // that failed on a transient network error, or anything left in flight by a process death). Keyed on the login
        // count rather than on the LOGGED_IN state: a state collector can miss a quick disconnect and re-login entirely
        // (LOGGED_IN followed by LOGGED_IN is no change), the count changes on every login. Debounced and
        // single-in-flight inside the service, so one scan per login is cheap.
        observeLoginsJob = coroutineScope.launch {
            sessionScopeContainer.serviceManager.connection.watchLoginCount().collect { loginCount ->
                if (loginCount > 0) {
                    try {
                        sessionScopeContainer.serviceManager.autoResendService.scheduleScan("reconnect")
                    } catch (e: Exception) {
                        logger.debug("Could not schedule auto-resend on reconnect", e)
                    }
                }
            }
        }
    }

    suspend fun onMasterKeyLocked() = coroutineScope {
        logger.info("Master key locked")
        observeConnectionStateJob?.cancel()
        observeConnectionStateJob = null
        observeLoginsJob?.cancel()
        observeLoginsJob = null

        appStartupMonitor.onMasterKeyLocked()

        sessionScopeContainerHolder.sessionScopeContainer
            ?.let { sessionScopeContainer ->
                cleanUpSession(sessionScopeContainer)
            }
        sessionScopeContainerHolder.sessionScopeContainer = null

        globalListeners?.tearDown()
        globalListeners = null

        ConfigUtils.scheduleAppRestart(appContext)
    }

    private suspend fun cleanUpSession(sessionScopeContainer: SessionScopeContainer) {
        try {
            withTimeout(SESSION_SCOPE_CONTAINER_CLEANUP_TIMEOUT) {
                stopOngoingCalls(sessionScopeContainer)
                dismissNotifications(sessionScopeContainer)
                stopConnection(sessionScopeContainer)
                deleteShareTargetShortcuts()
                stopWebClientSession(sessionScopeContainer)
                clearAvatarCache()
                destroyModelRepositories(sessionScopeContainer)
                closeDatabases(sessionScopeContainer)
            }
        } catch (e: TimeoutCancellationException) {
            logger.error("Failed to clean up the session within {}", SESSION_SCOPE_CONTAINER_CLEANUP_TIMEOUT, e)
            exitProcess(1)
        }

        delay(SESSION_SCOPE_CONTAINER_CLEANUP_GRACE_PERIOD)
        sessionScopeContainer.close()
    }

    private fun stopOngoingCalls(sessionScopeContainer: SessionScopeContainer) {
        if (!sessionScopeContainer.voipStateService.callState.isIdle) {
            VoipUtil.sendOneToOneCallHangupCommand(appContext)
        }
        sessionScopeContainer.groupCallManager.abortCurrentCall()
    }

    private fun dismissNotifications(sessionScopeContainer: SessionScopeContainer) {
        sessionScopeContainer.notificationService.cancelConversationNotificationsOnLockApp()
    }

    private suspend fun stopConnection(sessionScopeContainer: SessionScopeContainer) = withContext(dispatcherProvider.io) {
        sessionScopeContainer.connection.takeIf { it.isRunning }?.let { connection ->
            try {
                connection.stop()
            } catch (e: InterruptedException) {
                logger.error("Interrupted while stopping connection", e)
            }
        }
    }

    private fun deleteShareTargetShortcuts() {
        if (preferenceService.isDirectShare()) {
            shareTargetUpdateWorkerScheduler.cancelPeriodicUpdate()
            ShortcutUtil.deleteAllShareTargetShortcuts(preferenceService)
        }
    }

    private fun stopWebClientSession(sessionScopeContainer: SessionScopeContainer) {
        sessionScopeContainer.webClientServiceManager.sessionService.stopAll(
            DisconnectContext.byUs(DisconnectContext.REASON_SESSION_STOPPED),
        )
    }

    private fun clearAvatarCache() {
        avatarCacheService.clear()
    }

    private fun destroyModelRepositories(sessionScopeContainer: SessionScopeContainer) {
        sessionScopeContainer.modelRepositories.contacts.destroy()
        sessionScopeContainer.modelRepositories.groups.destroy()
    }

    private suspend fun closeDatabases(sessionScopeContainer: SessionScopeContainer) = withContext(dispatcherProvider.io) {
        databaseProvider.close()
        sessionScopeContainer.dhSessionStore.close()
    }

    companion object {
        /**
         * When the master key is locked, we need to clean up various services, caches, etc. This must complete quickly. If it doesn't,
         * we'd rather stop the app entirely than to linger here and wait for the full cleanup, to ensure that potentially sensitive user data
         * is cleared from memory.
         */
        private val SESSION_SCOPE_CONTAINER_CLEANUP_TIMEOUT = 10.seconds

        /**
         * We grant a short grace period for services to shut down properly before the session scope is closed,
         * to reduce the risk of crashes.
         */
        private val SESSION_SCOPE_CONTAINER_CLEANUP_GRACE_PERIOD = 400.milliseconds
    }
}
