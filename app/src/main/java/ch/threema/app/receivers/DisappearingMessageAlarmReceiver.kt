package ch.threema.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.threema.app.services.DisappearingMessageService
import ch.threema.base.utils.getThreemaLogger

private val logger = getThreemaLogger("DisappearingMessageAlarmReceiver")

/**
 * F1Whisper: fired by [android.app.AlarmManager] when the earliest pending disappearing-message
 * expiry is due. Offloads deletion to a worker thread via [goAsync] to keep the main thread free.
 */
class DisappearingMessageAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        logger.info("Disappearing message alarm fired")
        val pendingResult = goAsync()
        Thread {
            try {
                DisappearingMessageService.getInstance().fireDue()
            } catch (e: Exception) {
                logger.error("Could not process due disappearing messages", e)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
