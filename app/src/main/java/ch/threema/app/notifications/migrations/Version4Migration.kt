package ch.threema.app.notifications.migrations

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import ch.threema.android.setSound
import ch.threema.app.R
import ch.threema.app.notifications.NotificationChannels.NOTIFICATION_CHANNEL_EMOJI_REACTIONS
import ch.threema.app.notifications.createChannel

/**
 * F1Whisper: adds the "emoji reactions" notification channel (notifications shown when someone
 * reacts to one of your own messages) for installations that already had notification channels.
 */
class Version4Migration : NotificationChannelMigration {
    override fun migrate(
        context: Context,
        sharedPreferences: SharedPreferences,
        notificationManager: NotificationManagerCompat,
    ) = with(notificationManager) {
        createChannel(
            channelId = NOTIFICATION_CHANNEL_EMOJI_REACTIONS,
            channelName = context.getString(R.string.notification_channel_emoji_reactions),
            channelImportance = NotificationManagerCompat.IMPORTANCE_DEFAULT,
        ) {
            setLightsEnabled(true)
            setVibrationEnabled(true)
            setShowBadge(false)
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, AudioAttributes.USAGE_NOTIFICATION)
        }
    }
}
