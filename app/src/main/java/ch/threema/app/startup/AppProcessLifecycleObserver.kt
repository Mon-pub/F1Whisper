package ch.threema.app.startup

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import ch.threema.app.AppConstants
import ch.threema.app.GlobalAppState
import ch.threema.app.GlobalBroadcastReceivers
import ch.threema.app.connection.CachingDnsResolver
import ch.threema.app.di.awaitSessionScopeReady
import ch.threema.app.di.getOrNull
import ch.threema.app.services.LifetimeService
import ch.threema.app.services.ServiceManagerProvider
import ch.threema.app.utils.DispatcherProvider
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.protocol.connection.ConnectionLivenessVerdict
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
        // F1Whisper: the foreground reconnect check now asks ConnectionLivenessVerdict instead of
        // comparing a wall-clock age against a guessed threshold.
        //
        // The invariant the deleted FOREGROUND_STALE_THRESHOLD_MS = 90_000L rested on was FALSE. It
        // claimed "90s > the 60s echo interval, so a healthy idle-but-alive link is NEVER falsely
        // reconnected". Measured on the reporting device, wall-clock echo intervals were: median 61s,
        // p90 141s, MAX 403s. 62 of 220 intervals exceeded 90s and 34 exceeded 120s, and the socket
        // was alive at the end of ALL 34. So the old check tore down provably healthy connections,
        // and no wall-clock threshold can separate the two cases: any value high enough to suppress
        // the false alarms is also high enough to miss every true detection.
        //
        // The reason is that the echo heartbeat rides kotlinx.coroutines.delay, which is driven by a
        // monotonic clock that HALTS while the device is suspended to RAM, whereas wall-clock time
        // keeps advancing. A Doze window therefore inflates the wall-clock age without consuming any
        // heartbeat budget. Staleness is now judged in AWAKE time, which is the same clock that drives
        // the heartbeat, so an age measured against it is meaningful. See ConnectionLivenessVerdict
        // for the derivation of its threshold.
        //
        // Do not reintroduce a wall-clock staleness threshold here.

        // Rate-limit the foreground reconnect so rapid resume/pause churn can't fire repeatedly.
        private const val FOREGROUND_STALE_COOLDOWN_MS = 10_000L

        // Monotonic (process-uptime) timestamp of the last foreground reconnect, for the cooldown
        // above. SystemClock.elapsedRealtime() is correct for a within-process rate limit. It is NOT
        // used for any staleness age; the verdict owns that and judges in awake time.
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

        // F1Whisper: foreground recovery for a connection that is not carrying traffic. Two distinct
        // failures are covered, and the OLD CHECK COULD ONLY EVER SEE ONE OF THEM:
        //
        //  1. LOGGEDIN on a socket that died silently during Doze (the case the old check targeted).
        //  2. DISCONNECTED with nothing retrying: the reported wedge. The old check gated on
        //     `connectionState == LOGGEDIN`, so it was STRUCTURALLY BLIND to it. The user's own
        //     diagnostics export, taken during the incident, read DISCONNECTED with every network
        //     probe OK, which is exactly the state that gate skipped.
        //
        // Both are now decided by ConnectionLivenessVerdict, which judges staleness in awake time and
        // names "down and not retrying" as its own outcome. We reconnect when the verdict is anything
        // other than VERIFIED_LIVE.
        //
        // The mapping from verdict to "reconnect?" is ForegroundReconnectDecision, which deliberately
        // does NOT reconnect on NOT_APPLICABLE or UNVERIFIED: both mean something is already in
        // progress or unconfirmed, and tearing those down on every resume would be the v6.4.3-35
        // defect again. See that class for the full reasoning.
        //
        // Still routed through the serialized/debounced GlobalBroadcastReceivers path, never a direct
        // reconnect(), so there is no reconnect-storm risk, and the cooldown below still applies.
        //
        // Resolved null-safely: ThreemaApplication.getServiceManager() does a HARD Koin
        // get<ServiceManagerProvider>() and throws when the definition is absent (e.g. unit tests with
        // a minimal Koin graph). getOrNull keeps production behavior identical and degrades to
        // "skip the check" when the provider is unavailable.
        val connection = getOrNull<ServiceManagerProvider>()?.getServiceManagerOrNull()?.connection
        if (connection != null) {
            val verdict = ConnectionLivenessVerdict.evaluate(
                connectionState = connection.connectionState,
                restartInFlight = connection.isRunning,
                lastInboundAtMillis = connection.getLastInboundActivityAtMillis(),
                lastInboundAtAwakeMillis = connection.getLastInboundActivityAtAwakeMillis(),
                nowMillis = System.currentTimeMillis(),
                // uptimeMillis, never elapsedRealtime: it excludes deep sleep, matching the clock the
                // inbound stamp is taken on. elapsedRealtime would reintroduce the wall-clock error.
                nowAwakeMillis = SystemClock.uptimeMillis(),
            )
            val nowElapsed = SystemClock.elapsedRealtime()
            if (ForegroundReconnectDecision.shouldRequestReconnect(verdict.liveness) &&
                nowElapsed - lastForegroundStaleReconnectElapsed > FOREGROUND_STALE_COOLDOWN_MS
            ) {
                lastForegroundStaleReconnectElapsed = nowElapsed
                logger.info(
                    "Foreground: connection not verified live ({}: {}); requesting a reconnect",
                    verdict.liveness,
                    verdict.reason,
                )
                GlobalBroadcastReceivers.requestConnectionReconnect("foreground-not-verified-live")
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
