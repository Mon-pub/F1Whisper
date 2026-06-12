package ch.threema.app.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.WorkerThread
import ch.threema.app.ThreemaApplication
import ch.threema.app.messagereceiver.ContactMessageReceiver
import ch.threema.app.messagereceiver.DistributionListMessageReceiver
import ch.threema.app.messagereceiver.GroupMessageReceiver
import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.receivers.ScheduledMessageAlarmReceiver
import ch.threema.base.utils.getThreemaLogger
import ch.threema.storage.factories.ScheduledMessageModelFactory
import ch.threema.storage.models.ScheduledMessageModel

private val logger = getThreemaLogger("ScheduledMessageService")

/**
 * Coordinates locally scheduled (deferred) messages.
 *
 * The actual transmission uses the unchanged [MessageService.sendText]; this is purely a local
 * pre-send queue. A single [AlarmManager] alarm is armed for the earliest pending message. When it
 * fires, [ScheduledMessageAlarmReceiver] calls [fireDue].
 */
class ScheduledMessageService private constructor() {

    companion object {
        const val ACTION_FIRE = "ch.threema.app.SCHEDULED_MESSAGE_FIRE"
        private const val REQUEST_CODE_SCHEDULED = 200
        private const val CONNECTION_TAG = "scheduledSend"

        @Volatile
        private var instance: ScheduledMessageService? = null

        @JvmStatic
        fun getInstance(): ScheduledMessageService =
            instance ?: synchronized(this) {
                instance ?: ScheduledMessageService().also { instance = it }
            }

        /**
         * Build the [MessageReceiver]-key used to identify a receiver in the store. This MUST match
         * the keys consumed by [reconstructReceiver] and the values
         * `IntentDataUtil.addMessageReceiverToIntent` produces.
         */
        @JvmStatic
        fun receiverKeyOf(receiver: MessageReceiver<*>): String? =
            when (receiver.type) {
                MessageReceiver.Type_CONTACT ->
                    (receiver as ContactMessageReceiver).contact?.identity

                MessageReceiver.Type_GROUP ->
                    (receiver as GroupMessageReceiver).group?.id?.toString()

                MessageReceiver.Type_DISTRIBUTION_LIST ->
                    (receiver as DistributionListMessageReceiver).distributionList?.id?.toString()

                else -> null
            }
    }

    @Throws(Exception::class)
    private fun factory(): ScheduledMessageModelFactory {
        val serviceManager = ThreemaApplication.getServiceManager()
            ?: throw IllegalStateException("ServiceManager not available")
        return serviceManager.databaseService.scheduledMessageModelFactory
    }

    /**
     * Schedule [body] to be sent to [receiver] at [atMillis] (epoch ms). Re-arms the alarm.
     */
    fun schedule(receiver: MessageReceiver<*>, body: String, atMillis: Long) {
        val receiverKey = receiverKeyOf(receiver)
        if (receiverKey == null) {
            logger.warn("Cannot schedule message: unsupported receiver type {}", receiver.type)
            return
        }
        try {
            val model = ScheduledMessageModel()
                .setReceiverType(receiver.type)
                .setReceiverKey(receiverKey)
                .setBody(body)
                .setScheduledAt(atMillis)
                .setCreatedAt(System.currentTimeMillis())
            factory().create(model)
        } catch (e: Exception) {
            logger.error("Could not store scheduled message", e)
            return
        }
        rescheduleNextAlarm(ThreemaApplication.getAppContext())
    }

    /**
     * Cancel a scheduled message by id and re-arm the alarm.
     */
    fun cancel(id: Int) {
        try {
            factory().deleteById(id)
        } catch (e: Exception) {
            logger.error("Could not cancel scheduled message {}", id, e)
            return
        }
        rescheduleNextAlarm(ThreemaApplication.getAppContext())
    }

    fun getByReceiver(receiverType: Int, receiverKey: String): List<ScheduledMessageModel> =
        try {
            factory().getByReceiver(receiverType, receiverKey)
        } catch (e: Exception) {
            logger.error("Could not load scheduled messages", e)
            emptyList()
        }

    fun countByReceiver(receiverType: Int, receiverKey: String): Int =
        try {
            factory().countByReceiver(receiverType, receiverKey)
        } catch (e: Exception) {
            logger.error("Could not count scheduled messages", e)
            0
        }

    /**
     * Send all due messages. Called from the alarm receiver on a worker thread.
     */
    @WorkerThread
    fun fireDue() {
        val serviceManager = ThreemaApplication.getServiceManager()
        if (serviceManager == null) {
            logger.warn("ServiceManager not available, deferring scheduled send")
            return
        }

        val factory: ScheduledMessageModelFactory
        val messageService: MessageService
        try {
            factory = serviceManager.databaseService.scheduledMessageModelFactory
            messageService = serviceManager.messageService
        } catch (e: Exception) {
            // master key locked / no identity: leave the rows for the next unlock and re-arm a retry
            logger.warn("Cannot send scheduled messages right now, will retry", e)
            scheduleRetry(ThreemaApplication.getAppContext())
            return
        }

        val due = factory.getAllDueBefore(System.currentTimeMillis())
        if (due.isEmpty()) {
            rescheduleNextAlarm(ThreemaApplication.getAppContext())
            return
        }

        val lifetimeService = serviceManager.lifetimeService
        lifetimeService?.acquireConnection(CONNECTION_TAG)
        try {
            for (model in due) {
                try {
                    val receiver = reconstructReceiver(serviceManager, model)
                    if (receiver == null) {
                        logger.warn("Scheduled message receiver no longer exists, dropping row {}", model.id)
                        factory.deleteById(model.id)
                        continue
                    }
                    messageService.sendText(model.body, receiver)
                    factory.deleteById(model.id)
                } catch (e: Exception) {
                    // best-effort: leave the row so a later rearm retries it
                    logger.error("Could not send scheduled message {}", model.id, e)
                }
            }
        } finally {
            lifetimeService?.releaseConnection(CONNECTION_TAG)
        }

        rescheduleNextAlarm(ThreemaApplication.getAppContext())
    }

    @Throws(Exception::class)
    private fun reconstructReceiver(
        serviceManager: ch.threema.app.managers.ServiceManager,
        model: ScheduledMessageModel,
    ): MessageReceiver<*>? {
        return when (model.receiverType) {
            MessageReceiver.Type_CONTACT -> {
                val contactService = serviceManager.contactService
                val contact = contactService.getByIdentity(model.receiverKey) ?: return null
                contactService.createReceiver(contact)
            }

            MessageReceiver.Type_GROUP -> {
                val groupService = serviceManager.groupService
                val groupId = model.receiverKey.toIntOrNull() ?: return null
                val groupModel = groupService.getById(groupId) ?: return null
                groupService.createReceiver(groupModel)
            }

            MessageReceiver.Type_DISTRIBUTION_LIST -> {
                val distributionListService = serviceManager.distributionListService
                val distId = model.receiverKey.toLongOrNull() ?: return null
                val distModel = distributionListService.getById(distId) ?: return null
                distributionListService.createReceiver(distModel)
            }

            else -> null
        }
    }

    /**
     * Re-arm the single alarm for the earliest pending message, or cancel it if none remain.
     */
    fun rescheduleNextAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = buildPendingIntent(context)

        val earliest: Long? = try {
            ThreemaApplication.getServiceManager()
                ?.databaseService
                ?.scheduledMessageModelFactory
                ?.earliestScheduledAt
        } catch (e: Exception) {
            logger.error("Could not read earliest scheduled message", e)
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
                // exact alarms not permitted: fall back to an inexact alarm (may be delayed)
                logger.warn("Exact alarms not permitted, scheduling inexact alarm")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliest, pendingIntent)
            }
        } catch (e: SecurityException) {
            logger.error("Could not schedule alarm", e)
        }
    }

    private fun scheduleRetry(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = buildPendingIntent(context)
        val retryAt = System.currentTimeMillis() + RETRY_DELAY_MILLIS
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, retryAt, pendingIntent)
        } catch (e: SecurityException) {
            logger.error("Could not schedule retry alarm", e)
        }
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduledMessageAlarmReceiver::class.java).setAction(ACTION_FIRE)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_SCHEDULED,
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

private const val RETRY_DELAY_MILLIS = 5 * 60 * 1000L
