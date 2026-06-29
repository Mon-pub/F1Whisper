package ch.threema.app.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import androidx.core.content.getSystemService
import ch.threema.app.services.LifetimeService
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.protocol.connection.ReconnectableServerConnection
import ch.threema.domain.protocol.connection.ServerConnection
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private val logger = getThreemaLogger("ConnectionNetworkCallback")

/**
 * Listens for default-network changes via [ConnectivityManager] and triggers a reconnect of the
 * persistent server connection. This closes a gap the legacy [ConnectivityManager.CONNECTIVITY_ACTION]
 * broadcast (and the [ch.threema.app.workers.ConnectivityChangeWorker] it drives) never covered:
 * that worker only flushes pending tasks, it never reconnects a live-but-dead socket. On onprem the
 * persistent connection is the sole delivery path (Threema Push is forced), so a network change that
 * leaves the socket half-open would otherwise strand the app DISCONNECTED until a force-kill.
 *
 * This is additive to (not a replacement for) [ch.threema.app.receivers.ConnectivityChangeReceiver]
 * / [ch.threema.app.workers.ConnectivityChangeWorker], which handle the orthogonal "flush queued
 * tasks when network returns" behaviour used across all flavors.
 *
 * Thread-marshalling (the critical part): the [android.net.ConnectivityManager.NetworkCallback]
 * fires on a ConnectivityManager-owned thread. We must NOT call [ReconnectableServerConnection.reconnect]
 * from there: reconnect() -> stop() does a blocking `runBlocking { connectionJob?.join() }`, which
 * would block that framework thread and can deadlock/ANR. Instead every callback merely schedules
 * work onto a private single-thread scheduled executor owned by this class. That executor gives us
 * both the off-callback-thread hop AND the debounce timer in one primitive, and serializing all
 * reconnects on its single thread guarantees no overlapping stop()/start().
 *
 * We deliberately do NOT reuse the connection's internal worker dispatcher
 * ([ch.threema.domain.protocol.connection.SingleThreadedServerConnectionDispatcher]): that thread is
 * close()d on every stop(), so posting a reconnect to it across a reconnect would race a closed
 * executor.
 */
class ConnectionNetworkCallback(
    context: Context,
    // Both the lifetime and the connection are resolved lazily on the executor thread so a null
    // ServiceManager (pre-unlock, or before the async ServiceManager setup has run) is handled
    // gracefully by the gates rather than captured at register time. registerBroadcastReceivers()
    // runs synchronously in onCreate(), before the ServiceManager exists, so neither may be captured
    // eagerly or the feature would be silently disabled on every cold start.
    private val lifetimeServiceProvider: () -> LifetimeService?,
    // Returns the ConvertibleServerConnection, which implements ReconnectableServerConnection.
    private val connectionProvider: () -> ServerConnection?,
) {
    private val connectivityManager: ConnectivityManager? = context.getSystemService()

    // Single-thread scheduled executor: off-callback-thread hop + debounce timer in one primitive.
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ConnNetworkCb")
        }

    @Volatile
    private var pendingReconnect: ScheduledFuture<*>? = null

    // Hard floor between two *executed* reconnects, as a backstop against an OS network flap storm
    // (the pending-future cancel already coalesces the common case).
    @Volatile
    private var lastReconnectElapsed: Long = 0

    // Last meaningful capability state, used to filter the chatty onCapabilitiesChanged stream so
    // we only schedule when the transport/validated set actually changed (e.g. a VPN transition).
    // These fields are accessed exclusively from the single executor thread; no @Volatile needed.
    private var lastHasVpn: Boolean = false
    private var lastValidated: Boolean = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleReconnect("onAvailable")
        }

        override fun onLost(network: Network) {
            // The socket may now be bound to a network that is gone; always attempt a reconnect.
            scheduleReconnect("onLost")
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            // onCapabilitiesChanged fires very frequently. Only react when a meaningful property
            // changed (VPN transport coming/going, or validated-internet flipping), so we don't
            // schedule a reconnect on every signal-strength tick.
            //
            // Snapshot the capability values on the CM callback thread (safe: these are local reads
            // of the passed-in NetworkCapabilities object, not shared state). The comparison and
            // write of lastHasVpn/lastValidated are deferred into the executor so that concurrent
            // CM callbacks — which can fire from multiple framework threads — serialize the
            // read-compare-write sequence on our single executor thread, preventing two concurrent
            // callbacks from both passing the change-filter and both scheduling a reconnect.
            val hasVpn = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val validated =
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (lifetimeServiceProvider()?.isActive != true) {
                return
            }
            try {
                executor.submit {
                    if (hasVpn != lastHasVpn || validated != lastValidated) {
                        lastHasVpn = hasVpn
                        lastValidated = validated
                        pendingReconnect?.cancel(false)
                        pendingReconnect = executor.schedule(
                            { doReconnect("onCapabilitiesChanged") },
                            DEBOUNCE_MS,
                            TimeUnit.MILLISECONDS,
                        )
                    }
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                logger.debug("Reconnect not scheduled, executor is shut down")
            }
        }
    }

    /**
     * Register the network callback. Process-lifetime registration (mirrors the sibling receivers
     * in [ch.threema.app.GlobalBroadcastReceivers], which are likewise never unregistered); the
     * instance must be strongly held by the caller, because a GC'd NetworkCallback is silently
     * unregistered by the framework.
     */
    fun register() {
        val cm = connectivityManager
        if (cm == null) {
            logger.warn("ConnectivityManager unavailable; network-triggered reconnect disabled")
            return
        }
        try {
            // registerDefaultNetworkCallback exists since API 24 but only became reliable at API 26,
            // so on API 24-25 we register an explicit internet NetworkRequest instead.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                // Do NOT require NET_CAPABILITY_NOT_RESTRICTED here: NetworkRequest.Builder adds it
                // by default, and a restricted (RESTRICTED) network such as some VPN/enterprise
                // transports would then never deliver onAvailable on API 24-25, so a transition onto
                // such a network would not trigger a reconnect. Clearing it lets restricted-network
                // transitions still fire.
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .build()
                cm.registerNetworkCallback(request, callback)
            }
            logger.info("Network callback registered")
        } catch (e: Exception) {
            logger.error("Failed to register network callback", e)
        }
    }

    /**
     * Unregister and tear down. Provided for completeness/testability; not invoked in normal app
     * flow (process-lifetime, mirrors the sibling receivers). We must NOT unregister on connection
     * stop: the whole point is to keep detecting a returning network while we still want a
     * connection.
     */
    fun shutdown() {
        connectivityManager?.let {
            try {
                it.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                logger.warn("Failed to unregister network callback", e)
            }
        }
        executor.shutdownNow()
    }

    /**
     * F1Whisper: force a reconnect through the SAME serialized/debounced/floored path the network
     * callback uses, so callers (e.g. the foreground DNS-refresh hook) never race the network
     * callback's reconnect. Used to re-resolve DNS after a Doze background where the socket connected
     * via a cached IP ([ch.threema.app.connection.CachingDnsResolver]).
     */
    fun requestReconnect(reason: String) = scheduleReconnect(reason)

    private fun scheduleReconnect(reason: String) {
        // Gate 1: only act if a connection is actually desired (the forced ThreemaPushService holds
        // an unpauseable lifetime slot for the whole onprem session, so isActive is true even while
        // backgrounded/dozing). A null lifetimeService means the ServiceManager is not ready yet
        // (pre-unlock / early cold start), so nothing is desired. This is a cheap early-out on the
        // callback thread; doReconnect() re-checks on the executor thread (Gate 3).
        if (lifetimeServiceProvider()?.isActive != true) {
            return
        }
        // Gate 2: coalesce flaps. A VPN connect/disconnect emits onLost + onAvailable + several
        // onCapabilitiesChanged within ~1s; cancel any pending run and reschedule so they collapse
        // into a single reconnect.
        //
        // ConnectivityManager delivers callbacks from multiple framework threads, so the
        // cancel-then-reschedule of pendingReconnect must be atomic or two concurrent callbacks
        // could both schedule. We hop the whole cancel+reschedule onto our single-threaded executor:
        // because that executor runs one task at a time, the read-cancel-reschedule sequence is
        // serialized and therefore atomic, and pendingReconnect is only ever touched from this one
        // thread.
        try {
            executor.submit {
                pendingReconnect?.cancel(false)
                pendingReconnect = executor.schedule(
                    { doReconnect(reason) },
                    DEBOUNCE_MS,
                    TimeUnit.MILLISECONDS,
                )
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Executor was shut down (shutdown() called); nothing to do.
            logger.debug("Reconnect not scheduled, executor is shut down")
        }
    }

    // Runs on our single executor thread, so calling the blocking reconnect() here is safe.
    private fun doReconnect(reason: String) {
        // Gate 3 (re-check on the executor thread): bail if no connection is desired any more, or if
        // the ServiceManager is still not ready (null lifetimeService).
        if (lifetimeServiceProvider()?.isActive != true) {
            return
        }

        // Backstop hard floor between executed reconnects, in case the OS flaps the default network
        // repeatedly faster than the debounce window.
        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastReconnectElapsed
        if (sinceLast < MIN_RECONNECT_GAP_MS) {
            // Do NOT drop this reconnect: if the final settle event of a flap burst lands within the
            // min gap of the previous executed reconnect, dropping it could leave the socket bound to
            // the dead/old network (the stuck-disconnected bug). Reschedule for the remainder of the
            // gap instead, coalesced via pendingReconnect (we run on the executor thread, so this is
            // safe to assign directly). Any callback arriving in the meantime simply replaces it.
            val remaining = MIN_RECONNECT_GAP_MS - sinceLast
            logger.debug("Deferring reconnect ({}), within min gap; retry in {}ms", reason, remaining)
            try {
                pendingReconnect = executor.schedule(
                    { doReconnect(reason) },
                    remaining,
                    TimeUnit.MILLISECONDS,
                )
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                logger.debug("Reconnect retry not scheduled, executor is shut down")
            }
            return
        }

        val connection = connectionProvider()
        if (connection == null) {
            // ServiceManager not ready yet (e.g. pre-unlock); nothing to reconnect.
            logger.debug("Skipping reconnect ({}), no connection available", reason)
            return
        }
        if (connection !is ReconnectableServerConnection) {
            logger.warn("Connection is not reconnectable; skipping reconnect")
            return
        }

        lastReconnectElapsed = now
        logger.info("Network-triggered reconnect ({})", reason)
        try {
            connection.reconnect()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            logger.error("Network-triggered reconnect failed", e)
        }
    }

    companion object {
        // Coalesce network flaps; >= RECONNECT_BASE_INTERVAL (2s) so we don't fight the backoff loop
        // and well under the user-perceptible threshold.
        private const val DEBOUNCE_MS = 2_000L

        // Backstop minimum gap between two executed reconnects.
        private const val MIN_RECONNECT_GAP_MS = 3_000L
    }
}
