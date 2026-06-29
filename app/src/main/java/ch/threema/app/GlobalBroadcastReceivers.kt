package ch.threema.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.getSystemService
import ch.threema.app.backuprestore.csv.BackupService
import ch.threema.app.connection.ConnectionNetworkCallback
import ch.threema.app.receivers.ConnectivityChangeReceiver
import ch.threema.app.restrictions.AppRestrictionService
import ch.threema.app.services.LifetimeService
import ch.threema.app.utils.ConfigUtils
import ch.threema.base.utils.getThreemaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val logger = getThreemaLogger("GlobalBroadcastReceivers")

object GlobalBroadcastReceivers {

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val lifetimeService: LifetimeService?
        get() = ThreemaApplication.getServiceManager()?.lifetimeService

    // Strongly hold the network callback for the process lifetime. A GC'd NetworkCallback is
    // silently unregistered by the framework, so it must not be a local.
    private var connectionNetworkCallback: ConnectionNetworkCallback? = null

    /**
     * F1Whisper: ask the network callback to force a reconnect (re-resolving DNS) through its
     * serialized/debounced executor. Called when the app returns to the foreground after a Doze
     * background in which the connection used a cached IP ([ch.threema.app.connection.CachingDnsResolver]).
     * No-op if the callback is not registered yet.
     */
    @JvmStatic
    fun requestConnectionReconnect(reason: String) {
        connectionNetworkCallback?.requestReconnect(reason)
    }

    @JvmStatic
    fun registerBroadcastReceivers(context: Context) {
        registerConnectivityChangeReceiver(context)
        registerConnectionNetworkCallback(context)
        registerDeviceIdleModeChangedReceiver(context)
        registerNotificationChannelGroupBlockStateChangedReceiver(context)
        registerAppRestrictionsChangeReceiver(context)
    }

    private fun registerConnectionNetworkCallback(context: Context) {
        // Trigger a reconnect of the persistent server connection when the default network changes.
        // The legacy CONNECTIVITY_ACTION receiver above never reconnects a live-but-dead socket;
        // this callback closes that gap (additive, complementary). Both lifetime and connection are
        // resolved lazily at reconnect time so a null ServiceManager is handled by the gates inside
        // the callback rather than failing here. This MUST NOT capture the ServiceManager eagerly:
        // registerBroadcastReceivers() runs synchronously in onCreate() before the async
        // ServiceManager setup has run, so getServiceManager() is still null at this point and an
        // eager null-guard here would silently disable the feature on every cold start.
        connectionNetworkCallback = ConnectionNetworkCallback(
            context = context.applicationContext,
            lifetimeServiceProvider = { lifetimeService },
            connectionProvider = { ThreemaApplication.getServiceManager()?.connection },
        ).also { it.register() }
    }

    private fun registerConnectivityChangeReceiver(context: Context) {
        // This is called when a change in network connectivity has occurred.
        // Note: This is deprecated on API 28+!
        context.registerReceiver(
            ConnectivityChangeReceiver(),
            IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION),
        )
    }

    private fun registerDeviceIdleModeChangedReceiver(context: Context) {
        // This is called when the state of isDeviceIdleMode() changes.
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val powerManager = context.getSystemService<PowerManager>() ?: return
                    if (powerManager.isDeviceIdleMode) {
                        logger.info("*** Device going to deep sleep")

                        GlobalAppState.isDeviceIdle = true

                        coroutineScope.launch {
                            try {
                                // Pause connection
                                lifetimeService?.pause()
                            } catch (e: Exception) {
                                logger.error("Exception while pausing connection", e)
                            }

                            if (BackupService.isRunning()) {
                                context.stopService(Intent(context, BackupService::class.java))
                            }
                        }
                    } else {
                        logger.info("*** Device waking up")
                        coroutineScope.launch {
                            try {
                                lifetimeService?.unpause()
                            } catch (e: Exception) {
                                logger.error("Exception while unpausing connection", e)
                            }
                        }
                        GlobalAppState.isDeviceIdle = false
                    }
                }
            },
            IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED),
        )
    }

    private fun registerNotificationChannelGroupBlockStateChangedReceiver(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }
        // This is called when a NotificationChannelGroup is blocked or unblocked.
        // This broadcast is only sent to the app that owns the channel group that has changed.
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try {
                        val blockedState = intent.getBooleanExtra(NotificationManager.EXTRA_BLOCKED_STATE, false)
                        val groupName = intent.getStringExtra(NotificationManager.EXTRA_NOTIFICATION_CHANNEL_GROUP_ID)
                        logger.info(
                            "*** Channel group {} blocked: {}",
                            groupName ?: "<not specified>",
                            blockedState,
                        )
                    } catch (e: Exception) {
                        logger.error("Could not get data from intent", e)
                    }
                }
            },
            IntentFilter(NotificationManager.ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED),
        )
    }

    private fun registerAppRestrictionsChangeReceiver(context: Context) {
        if (ConfigUtils.isWorkRestricted()) {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        logger.info("Restrictions have changed. Updating restrictions")
                        AppRestrictionService.getInstance().reload()
                    }
                },
                IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
            )
        }
    }
}
