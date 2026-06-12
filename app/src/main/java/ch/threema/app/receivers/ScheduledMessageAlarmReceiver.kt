package ch.threema.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.threema.app.services.ScheduledMessageService
import ch.threema.base.utils.getThreemaLogger

private val logger = getThreemaLogger("ScheduledMessageAlarmReceiver")

/**
 * Fired by [android.app.AlarmManager] when the earliest scheduled message is due. Offloads the
 * actual send to a worker thread via [goAsync] so it does not run network on the main thread.
 */
class ScheduledMessageAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        logger.info("Scheduled message alarm fired")
        val pendingResult = goAsync()
        Thread {
            try {
                ScheduledMessageService.getInstance().fireDue()
            } catch (e: Exception) {
                logger.error("Could not process due scheduled messages", e)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
