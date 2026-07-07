package ch.threema.app.startup

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import android.os.SystemClock
import ch.threema.app.AppConstants
import ch.threema.app.GlobalAppState
import ch.threema.app.GlobalBroadcastReceivers
import ch.threema.app.ThreemaApplication
import ch.threema.app.connection.CachingDnsResolver
import ch.threema.app.di.awaitSessionScopeReady
import ch.threema.app.di.getOrNull
import ch.threema.app.services.LifetimeService
import ch.threema.app.utils.DispatcherProvider
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.protocol.connection.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

private val logger = getThreemaLogger("AppProcessLifecycleObserver")

class AppProcessLifecycleObserver(
    private val reloadAppRestrictions: () -> Unit,
    dispatcherProvider: DispatcherProvider,
) : DefaultLifecycleObserver, KoinComponent {

    private companion object {
        // F1Whisper: on this GMS-free build the persistent CSP socket can die silently during Doze.
        // On foreground we only ever tear down an ALREADY-DEAD socket: state must still be LOGGEDIN
        // AND no inbound signal (echo reply refreshes the timestamp every ~60s) for longer than this
        // threshold. 90s > the 60s echo interval, so a healthy idle-but-alive link (refreshed every
        // 60s) is NEVER falsely reconnected. The staleness age uses wall-clock time, which advances
        // during Doze sleep.
        private const val FOREGROUND_STALE_THRESHOLD_MS = 90_000L

        // Rate-limit the foreground stale reconnect so rapid resume/pause churn can't fire repeatedly.
        private const val FOREGROUND_STALE_COOLDOWN_MS = 10_000L

        // Monotonic (process-uptime) timestamp of the last foreground stale reconnect, for the
        // cooldown above. SystemClock.elapsedRealtime() is correct here (a within-process rate limit);
        // it must NOT be used for the staleness age, which needs wall-clock to count Doze sleep.
        @Volatile
        private var lastForegroundStaleReconnectElapsed: Long = 0L
    }

    /**
     * Note that this object follows a last one wins approach regarding connection acquisition and release. Intermediate connection acquisitions and
     * releases may be skipped.
     */
    private val connectionHolder = object {
        private val coroutineScope = CoroutineScope(dispatcherProvider.worker)
        private var lifetimeServiceAcquisitionJob: Job? = null
        private var lifetimeServiceReleaseJob: Job? = null

        /**
         * Acquire a permanent connection.
         */
        fun acquire() {
            lifetimeServiceAcquisitionJob = coroutineScope.launch {
                // Wait until release job is complete (or cancelled) to ensure it is acquired afterwards
                lifetimeServiceReleaseJob?.cancelAndAwaitForCancellation()

                awaitSessionScopeReady()
                get<LifetimeService>().acquireConnection(AppConstants.ACTIVITY_CONNECTION_TAG)
                logger.info("Connection now acquired")
            }
        }

        /**
         * Release the permanent connection.
         */
        fun release() {
            lifetimeServiceReleaseJob = coroutineScope.launch {
                // Wait until acquisition is complete (or cancelled) to ensure it is released afterwards
                lifetimeServiceAcquisitionJob?.cancelAndAwaitForCancellation()

                val lifetimeService = getOrNull<LifetimeService>()
                if (lifetimeService != null) {
                    lifetimeService.releaseConnectionLinger(
                        AppConstants.ACTIVITY_CONNECTION_TAG,
                        AppConstants.ACTIVITY_CONNECTION_LIFETIME,
                    )
                    logger.info("Connection linger released")
                } else {
                    logger.warn("Could not release connection linger ")
                }
            }
        }

        private suspend fun Job.cancelAndAwaitForCancellation() {
            try {
                cancelAndJoin()
            } catch (_: CancellationException) {
                // Nothing to do
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        logger.info("*** Lifecycle: App now created")
    }

    override fun onStart(owner: LifecycleOwner) {
        logger.info("*** Lifecycle: App now visible")
    }

    override fun onResume(owner: LifecycleOwner) {
        logger.info("*** Lifecycle: App now resumed")
        GlobalAppState.isAppResumed = true

        connectionHolder.acquire()

        // F1Whisper: if the last DNS resolution fell back to a cached IP (a background Doze window
        // where name resolution was frozen), force one fresh-resolve reconnect now that the app is in
        // the foreground (no Doze restriction → DNS works), so we never stay pinned to a stale IP.
        // Routed through the network callback's serialized executor → no race with its own reconnect.
        if (CachingDnsResolver.wasLastResolveFromCache()) {
            logger.info("Foreground: last DNS resolve used a cached IP; requesting a fresh-resolve reconnect")
            GlobalBroadcastReceivers.requestConnectionReconnect("foreground-dns-refresh")
        }

        // F1Whisper: fast reconnect for a silently-dead socket. On this GMS-free build the persistent
        // CSP socket can die during Doze, and the app otherwise only notices via the ~70s echo cycle
        // (60s echo interval + 10s response timeout). Here we reconnect ONLY a socket that is still
        // LOGGEDIN but has had NO inbound activity for the staleness window -- i.e. we only ever tear
        // down an already-dead socket, never a live or merely-slow one. Two clocks are intentional:
        // the staleness AGE uses System.currentTimeMillis() (wall-clock, counts Doze sleep) while the
        // COOLDOWN uses SystemClock.elapsedRealtime() (process-monotonic rate limit). Routed through
        // the same serialized/debounced reconnect path as the DNS refresh above (never a direct
        // reconnect()) so there is no reconnect-storm risk.
        val connection = ThreemaApplication.getServiceManager()?.connection
        if (connection != null && connection.connectionState == ConnectionState.LOGGEDIN) {
            val idleMillis = System.currentTimeMillis() - connection.getLastInboundActivityAtMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            if (idleMillis > FOREGROUND_STALE_THRESHOLD_MS &&
                nowElapsed - lastForegroundStaleReconnectElapsed > FOREGROUND_STALE_COOLDOWN_MS
            ) {
                lastForegroundStaleReconnectElapsed = nowElapsed
                logger.info(
                    "Foreground: connection LOGGEDIN but no inbound for {} ms (> {} ms); forcing a reconnect",
                    idleMillis,
                    FOREGROUND_STALE_THRESHOLD_MS,
                )
                GlobalBroadcastReceivers.requestConnectionReconnect("foreground-stale-socket")
            }
        }

        reloadAppRestrictions()
    }

    override fun onPause(owner: LifecycleOwner) {
        logger.info("*** Lifecycle: App now paused")
        GlobalAppState.isAppResumed = false

        connectionHolder.release()
    }

    override fun onStop(owner: LifecycleOwner) {
        logger.info("*** Lifecycle: App now stopped")
    }
}
