package ch.threema.app.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import ch.threema.base.utils.getThreemaLogger

private val logger = getThreemaLogger("AlarmScheduler")

/**
 * F1Whisper: shared helper that arms or cancels a single [AlarmManager] alarm for the earliest
 * pending epoch-millis timestamp returned by [queryEarliestMillis].
 *
 * Extracted so that [ScheduledMessageService] and [DisappearingMessageService] (and future services
 * like a calendar alarm) share the exact-vs-inexact-fallback + cancel-when-empty logic without
 * duplicating it.
 *
 * Usage:
 * ```kotlin
 * private val scheduler = AlarmScheduler(
 *     actionString = MyAlarmReceiver.ACTION,
 *     requestCode  = 42,
 *     buildIntent  = { context -> Intent(context, MyAlarmReceiver::class.java).setAction(ACTION) },
 * )
 * // To re-arm:
 * scheduler.rescheduleNextAlarm(context) { earliestEpochMillisFromDb() }
 * // To cancel:
 * scheduler.cancel(context)
 * ```
 */
class AlarmScheduler(
    private val requestCode: Int,
    private val buildIntent: (Context) -> android.content.Intent,
) {
    /**
     * Re-arm the alarm for the earliest timestamp returned by [queryEarliestMillis], or cancel it
     * if [queryEarliestMillis] returns `null`.
     */
    fun rescheduleNextAlarm(context: Context, queryEarliestMillis: () -> Long?) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = buildPendingIntent(context)

        val earliest: Long? = try {
            queryEarliestMillis()
        } catch (e: Exception) {
            logger.error("Could not read earliest alarm time (requestCode={})", requestCode, e)
            return
        }

        if (earliest == null) {
            alarmManager.cancel(pendingIntent)
            return
        }

        try {
            if (canScheduleExactAlarms(context, alarmManager)) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliest, pendingIntent)
            } else {
                logger.warn(
                    "Exact alarms not permitted (requestCode={}), scheduling inexact alarm",
                    requestCode,
                )
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliest, pendingIntent)
            }
        } catch (e: SecurityException) {
            logger.error("Could not schedule alarm (requestCode={})", requestCode, e)
        }
    }

    /** Cancel any pending alarm. */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(buildPendingIntent(context))
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = buildIntent(context)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canScheduleExactAlarms(context: Context, alarmManager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
}
