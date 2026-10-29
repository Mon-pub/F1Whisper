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
 * Extracted so that [DisappearingMessageService] (and future services like a calendar alarm) get
 * the exact-vs-inexact-fallback + cancel-when-empty logic without duplicating it.
 *
 * Usage:
 * ```kotlin
 * private val scheduler = AlarmScheduler(
 *     actionString = MyAlarmReceiver.ACTION,
 *     requestCode  = 42,
 *     buildIntent  = { context -> Intent(context, MyAlarmReceiver::class.java).setAction(ACTION) },
 * )
 * // To re-arm:
 * scheduler.rescheduleNextAlarm(context) { earliestAlarmTargetFromDb() }
 * // To cancel:
 * scheduler.cancel(context)
 * ```
 *
 * The query returns an [AlarmTarget], not a nullable timestamp, so that "the queue is empty" and
 * "the queue could not be read" stay distinguishable all the way to the cancel decision. See
 * [AlarmPlanDecision] for what the third case cost before it existed.
 */
class AlarmScheduler(
    private val requestCode: Int,
    private val buildIntent: (Context) -> android.content.Intent,
) {
    /**
     * Serialises read-decide-act for this scheduler's one [PendingIntent]. See [AlarmRecomputationGate] for the interleaving it
     * removes; one instance per scheduler, because two schedulers own different alarms and must not block each other.
     */
    private val gate = AlarmRecomputationGate()

    /**
     * Apply [queryEarliestTarget] to the pending alarm: arm it, cancel it, or - when the queue could
     * not be read - arm a retry [retryDelayMillis] from now so the engine recovers on its own.
     *
     * A throwing [queryEarliestTarget] is treated exactly as [AlarmTarget.Unavailable]: an exception
     * is one more way of not knowing, and the one thing it must never do is look like an empty queue.
     */
    fun rescheduleNextAlarm(
        context: Context,
        retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
        queryEarliestTarget: () -> AlarmTarget,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        // Built outside the gate on purpose: it reads nothing about the queue, and every caller resolves it to the same
        // requestCode-keyed PendingIntent, which is precisely the shared resource the gate is protecting.
        val pendingIntent = buildPendingIntent(context)

        gate.applyLatest(
            retryDelayMillis = retryDelayMillis,
            queryEarliestTarget = queryEarliestTarget,
        ) { action ->
            when (action) {
                AlarmAction.Cancel -> alarmManager.cancel(pendingIntent)
                is AlarmAction.ArmAt -> arm(context, alarmManager, pendingIntent, action.epochMillis)
            }
        }
    }

    private fun arm(
        context: Context,
        alarmManager: AlarmManager,
        pendingIntent: PendingIntent,
        atMillis: Long,
    ) {
        try {
            if (canScheduleExactAlarms(context, alarmManager)) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
            } else {
                logger.warn(
                    "Exact alarms not permitted (requestCode={}), scheduling inexact alarm",
                    requestCode,
                )
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
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

    companion object {
        /**
         * How far ahead to arm the recovery alarm when the queue could not be read. Five minutes is
         * short enough that a locked-master-key window resolves quickly and long enough that
         * repeatedly retrying against a still-locked device costs nothing measurable.
         */
        const val DEFAULT_RETRY_DELAY_MILLIS = 5 * 60 * 1000L
    }
}
