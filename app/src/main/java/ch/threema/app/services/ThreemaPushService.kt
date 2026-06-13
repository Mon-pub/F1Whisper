package ch.threema.app.services

import android.annotation.TargetApi
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import ch.threema.app.R
import ch.threema.app.ThreemaApplication
import ch.threema.app.activities.DummyActivity
import ch.threema.app.activities.ThreemaPushNotificationInfoActivity
import ch.threema.app.notifications.NotificationChannels
import ch.threema.app.receivers.ThreemaPushReviveReceiver
import ch.threema.app.utils.ConfigUtils
import ch.threema.app.webclient.services.SessionAndroidService
import ch.threema.base.utils.getThreemaLogger
import org.slf4j.Logger

private val logger = getThreemaLogger("ThreemaPushService")

class ThreemaPushService : Service() {
    // Threema services
    private var lifetimeService: LifetimeService? = null

    @Synchronized
    override fun onCreate() {
        logger.debug("onCreate")
        super.onCreate()

        // Create intent triggered by notification
        val intent = ThreemaPushNotificationInfoActivity.createIntent(this)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        createNotificationChannel()

        // Create notification
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(
            this,
            NotificationChannels.NOTIFICATION_CHANNEL_THREEMA_PUSH,
        )
            .setContentTitle(getString(R.string.threema_push))
            .setContentText(getString(R.string.threema_push_notification_text))
            .setSmallIcon(R.drawable.ic_notification_push)
            .setLocalOnly(true)
            .setContentIntent(contentIntent)
        ServiceCompat.startForeground(
            this,
            THREEMA_PUSH_ACTIVE_NOTIFICATION_ID,
            builder.build(),
            FG_SERVICE_TYPE,
        )
        logger.info("startForeground called")

        // Get lifetime service
        //
        // Initialization may lock the app for a while, so we display the above notification
        // *before* getting the service to avoid a "Context.startForegroundService() did not
        // then call Service.startForeground()" exception.
        val serviceManager = ThreemaApplication.getServiceManager()
        if (serviceManager == null) {
            logger.error("Service Manager not available (passphrase locked?). Can't start Threema Push.")
            stopSelf()
            return
        }
        val lifetimeService = serviceManager.lifetimeService
        this.lifetimeService = lifetimeService

        // Acquire unpauseable connection while the service is running
        lifetimeService.acquireUnpauseableConnection(LIFETIME_SERVICE_TAG)
        isRunning = true

        // F1Whisper: arm the periodic self-heal alarm so that if this foreground service / its
        // process is later reaped (common in managed work profiles e.g. Shelter, where there is no
        // FCM to wake us), an AlarmManager wake restarts it instead of staying dead until the user
        // reopens the app. Gated on useThreemaPush inside scheduleRevive.
        scheduleRevive(applicationContext, HEARTBEAT_INTERVAL_MS)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    @Synchronized
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger.trace("onStartCommand")
        // F1Whisper: a null/no-action intent means the OS recreated us after a kill (START_STICKY).
        // Keep it sticky and let onCreate re-acquire the connection.
        if (intent == null || intent.action == null) {
            return START_STICKY
        }
        if (isStopping) {
            // Already stopping
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> logger.info("ACTION_START")
            ACTION_STOP -> {
                logger.info("ACTION_STOP")
                isRunning = false
                isStopping = true
                // F1Whisper: an explicit stop is the user/app turning push off; cancel the self-heal
                // alarm so we don't resurrect ourselves.
                cancelRevive(applicationContext)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
            }
        }
        // F1Whisper: START_STICKY so the OS recreates the push service after an out-of-memory /
        // work-profile process kill (see scheduleRevive for the alarm-based backstop too).
        return START_STICKY
    }

    override fun onDestroy() {
        logger.trace("onDestroy")

        // F1Whisper: capture whether this is an explicit stop before the flag is reset below.
        val explicitStop = isStopping

        // Release connection
        lifetimeService?.releaseConnection(LIFETIME_SERVICE_TAG)

        // Remove notificatoin
        removeNotification()

        // Stop foreground service
        stopForeground(true)
        logger.info("stopForeground")

        // F1Whisper: if we were killed (not an explicit ACTION_STOP), schedule a near-term alarm to
        // bring the push connection back, so background message delivery resumes without the user
        // reopening the app (matters most on managed work profiles with no FCM).
        if (!explicitStop) {
            scheduleRevive(applicationContext, REVIVE_DELAY_MS)
        }

        // Done
        isRunning = false
        super.onDestroy()
        isStopping = false
        logger.info("Service destroyed")
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        logger.info("onLowMemory")
        super.onLowMemory()
    }

    override fun onTimeout(startId: Int) {
        logger.warn("onTimeout called, this is going to kill the foreground service :(")
        // F1Whisper: Android 14+ enforces a runtime limit on REMOTE_MESSAGING foreground services.
        // Re-arm the self-heal alarm so the connection comes back shortly after the OS kills us.
        scheduleRevive(applicationContext, REVIVE_DELAY_MS)
        super.onTimeout(startId)
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        logger.info("onTaskRemoved")
        // F1Whisper: also re-arm on swipe-away so push survives the user clearing the task.
        scheduleRevive(applicationContext, REVIVE_DELAY_MS)
        val intent = Intent(this, DummyActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * Remove the persistent notification.
     */
    private fun removeNotification() {
        val notificationManagerCompat = NotificationManagerCompat.from(this)
        notificationManagerCompat.cancel(THREEMA_PUSH_ACTIVE_NOTIFICATION_ID)
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        if (!ConfigUtils.supportsNotificationChannels()) {
            return
        }

        val notificationManager = NotificationManagerCompat.from(this)
        val notificationChannel = NotificationChannel(
            NotificationChannels.NOTIFICATION_CHANNEL_THREEMA_PUSH,
            getString(R.string.threema_push),
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationChannel.description = getString(R.string.threema_push_service_description)
        notificationChannel.enableLights(false)
        notificationChannel.enableVibration(false)
        notificationChannel.setShowBadge(false)
        notificationChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        notificationChannel.setSound(null, null)

        notificationManager.createNotificationChannel(notificationChannel)
    }

    companion object {
        private const val THREEMA_PUSH_ACTIVE_NOTIFICATION_ID = 27392
        private const val LIFETIME_SERVICE_TAG = "threemaPushService"
        private val FG_SERVICE_TYPE =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0

        // Intent actions
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"

        // F1Whisper: self-heal alarm. Period between heartbeat checks that the push service/socket is
        // still alive, and the short delay used to revive after a kill/timeout. Public interval so the
        // revive receiver can re-arm itself.
        const val ACTION_REVIVE = "ch.threema.app.THREEMA_PUSH_REVIVE"
        const val HEARTBEAT_INTERVAL_MS = 15L * 60 * 1000
        private const val REVIVE_DELAY_MS = 60L * 1000
        private const val REVIVE_REQUEST_CODE = 27393

        // State variables
        var isRunning = false
            private set
        private var isStopping = false

        private fun revivePendingIntent(appContext: Context): PendingIntent {
            val intent = Intent(appContext, ThreemaPushReviveReceiver::class.java).setAction(ACTION_REVIVE)
            return PendingIntent.getBroadcast(
                appContext,
                REVIVE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * F1Whisper: schedule a single AlarmManager wake (after [delayMs]) that revives the push
         * service via [ThreemaPushReviveReceiver]. No-op unless Threema/F1 push is enabled. Uses an
         * exact-while-idle alarm when allowed (it also grants the brief background foreground-service
         * start exemption on Android 12+), falling back to an inexact while-idle alarm otherwise.
         */
        @JvmStatic
        fun scheduleRevive(appContext: Context, delayMs: Long) {
            val rawSharedPreferences = PreferenceManager.getDefaultSharedPreferences(appContext)
            if (!ConfigUtils.useThreemaPush(rawSharedPreferences, appContext)) {
                return
            }
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pendingIntent = revivePendingIntent(appContext)
            val triggerAt = System.currentTimeMillis() + delayMs
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            try {
                if (canExact) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            } catch (e: SecurityException) {
                logger.warn("Exact revive alarm denied, falling back to inexact", e)
                try {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } catch (e2: Exception) {
                    logger.error("Could not schedule push revive alarm", e2)
                }
            }
        }

        /**
         * F1Whisper: cancel the self-heal alarm (used when push is explicitly turned off).
         */
        @JvmStatic
        fun cancelRevive(appContext: Context) {
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            alarmManager.cancel(revivePendingIntent(appContext))
        }

        /**
         * Try to start this service. Will start if:
         *
         * - Service is enabled in shared preferences
         * - ServiceManager is available (-> MasterKey must be unlocked or disabled)
         */
        @JvmStatic
        fun tryStart(callerLogger: Logger, appContext: Context): Boolean {
            // Open shared preferences directly. This can be used in situations where we don't know
            // whether the MasterKey is unlocked already.
            val rawSharedPreferences = PreferenceManager.getDefaultSharedPreferences(appContext)

            // Start the Threema Push Service
            // Note: Not using preferenceService here, because MasterKey
            //       may not be unlocked.
            if (ConfigUtils.useThreemaPush(rawSharedPreferences, appContext)) {
                if (!isRunning) {
                    val intent = Intent(appContext, ThreemaPushService::class.java)
                    intent.action = SessionAndroidService.ACTION_START
                    callerLogger.info("Starting ThreemaPushService")
                    try {
                        ContextCompat.startForegroundService(appContext, intent)
                        return true
                    } catch (e: Exception) {
                        logger.error("Unable to start foreground service", e)
                    }
                }
            }
            return false
        }
    }
}
