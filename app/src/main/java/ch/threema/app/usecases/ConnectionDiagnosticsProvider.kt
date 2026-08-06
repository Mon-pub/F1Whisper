package ch.threema.app.usecases

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.core.content.getSystemService
import ch.threema.app.ThreemaApplication
import ch.threema.domain.protocol.connection.ConnectionState

/**
 * F1Whisper: one consistent read of everything the `connection` diagnostics section needs.
 *
 * Every field is captured at the same instant. Reading them one at a time as the section rendered
 * would let the connection change underneath and produce a self-contradictory report, which is the
 * kind of artifact that sends the next investigation down the wrong path.
 *
 * Clocks are fields rather than calls so the section is JVM-testable: `SystemClock.uptimeMillis()` is
 * Android-only, and the liveness verdict deliberately takes every clock as a parameter.
 */
data class ConnectionDiagnosticsSnapshot(
    val connectionState: ConnectionState,
    /** The connection's own `isRunning`: a start is in flight or a job is alive. */
    val restartInFlight: Boolean,
    /** `LifetimeServiceImpl.active`: a start was issued and not yet cleaned up. */
    val lifetimeLatchActive: Boolean,
    val connectionSlotsHeld: Int,
    val lastInboundAtMillis: Long,
    val lastInboundAtAwakeMillis: Long,
    val nowMillis: Long,
    val nowAwakeMillis: Long,
    val hasPendingTasks: Boolean,
    val networkTransport: String,
    val hasIdentity: Boolean,
    val usesMultiDevice: Boolean,
)

/**
 * Supplies a [ConnectionDiagnosticsSnapshot]. Exists so the diagnostics section is testable: the
 * production implementation reaches through `ThreemaApplication` statics and the Android
 * `ConnectivityManager`, neither of which is available in a JVM unit test.
 */
fun interface ConnectionDiagnosticsProvider {
    /** @throws Exception if the connection services are not available (e.g. pre-unlock). */
    fun snapshot(): ConnectionDiagnosticsSnapshot
}

/**
 * The production provider: exactly the reach-through the section used to do inline, now behind an
 * interface so the rendering can be tested without it. Behaviour is unchanged for both existing call
 * sites of [ExportConnectionDiagnosticsUseCase].
 *
 * `nowAwakeMillis` uses [SystemClock.uptimeMillis], never `elapsedRealtime()`. Both the inbound stamp
 * and `uptimeMillis` exclude deep sleep, so they are on the same clock; `elapsedRealtime()` includes
 * deep sleep and would reintroduce the wall-clock error that made the shipped v6.4.3-35 foreground
 * check fire on healthy sockets.
 */
class DefaultConnectionDiagnosticsProvider(
    private val appContext: Context,
) : ConnectionDiagnosticsProvider {

    override fun snapshot(): ConnectionDiagnosticsSnapshot {
        val serviceManager = ThreemaApplication.requireServiceManager()
        val connection = serviceManager.connection
        val lifetimeService = serviceManager.lifetimeService
        return ConnectionDiagnosticsSnapshot(
            connectionState = connection.connectionState,
            restartInFlight = connection.isRunning,
            lifetimeLatchActive = lifetimeService.isActive,
            connectionSlotsHeld = lifetimeService.connectionSlotCount,
            lastInboundAtMillis = connection.getLastInboundActivityAtMillis(),
            lastInboundAtAwakeMillis = connection.getLastInboundActivityAtAwakeMillis(),
            nowMillis = System.currentTimeMillis(),
            nowAwakeMillis = SystemClock.uptimeMillis(),
            hasPendingTasks = serviceManager.taskManager.hasPendingTasks(),
            networkTransport = describeTransport(),
            hasIdentity = serviceManager.userService.hasIdentity(),
            usesMultiDevice = serviceManager.multiDeviceManager.isMultiDeviceActive,
        )
    }

    /**
     * Transport of the current default network. Deliberately coarse: the transport type and whether
     * the OS considers the link validated. No SSID, no operator, no addresses, keeping the report's
     * no-personal-data guarantee.
     */
    private fun describeTransport(): String {
        val cm = appContext.getSystemService<ConnectivityManager>()
            ?: return "n/a (no ConnectivityManager)"
        val network = cm.activeNetwork ?: return "none (no active network)"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown (no capabilities)"
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        return buildString {
            append(transport)
            append(if (validated) " (validated" else " (not validated")
            if (vpn && transport != "vpn") {
                append(", vpn")
            }
            append(")")
        }
    }
}
