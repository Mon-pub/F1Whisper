package ch.threema.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.threema.app.services.ThreemaPushService
import ch.threema.base.utils.getThreemaLogger

private val logger = getThreemaLogger("ThreemaPushReviveReceiver")

/**
 * F1Whisper: AlarmManager-driven self-heal for the persistent "F1/Threema Push" connection.
 *
 * In no-GMS / onprem builds there is no FCM to wake the app, so background message delivery relies
 * entirely on [ThreemaPushService] (a foreground service holding the chat socket). On managed work
 * profiles (e.g. Shelter) and aggressive OEMs that service/process can be reaped, leaving the user
 * with no messages until they reopen the app. This receiver — fired by the alarm armed in
 * [ThreemaPushService.scheduleRevive] — restarts the push service and re-arms the next heartbeat, so
 * the connection comes back on its own.
 */
class ThreemaPushReviveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        logger.info("Push revive alarm fired")
        val appContext = context.applicationContext
        // Re-arm the next heartbeat first, so the cycle continues even if starting the service fails
        // right now (e.g. master key still locked, or a transient background-start restriction).
        ThreemaPushService.scheduleRevive(appContext, ThreemaPushService.HEARTBEAT_INTERVAL_MS)
        try {
            ThreemaPushService.tryStart(logger, appContext)
        } catch (e: Exception) {
            logger.error("Could not revive ThreemaPushService", e)
        }
    }
}
