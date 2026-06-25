package ch.threema.app.receivers;

import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;

import org.slf4j.Logger;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import ch.threema.app.R;
import ch.threema.app.activities.DownloadApkActivity;
import ch.threema.app.notifications.NotificationChannels;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

/**
 * F1Whisper: handles completion of the in-app self-update download (enqueued by
 * {@link DownloadApkActivity} via the system {@link DownloadManager}). Decoupled from the activity so
 * the download runs fully in the background; on success it posts an "update ready, tap to install"
 * notification whose tap relaunches {@link DownloadApkActivity} to perform the install (reusing the
 * unknown-sources grant flow). On failure it posts a notification linking to the GitHub releases page.
 */
public class UpdateDownloadCompleteReceiver extends BroadcastReceiver {
    private static final Logger logger = getThreemaLogger("UpdateDownloadCompleteReceiver");

    private static final int UPDATE_READY_NOTIFICATION_ID = 27394;
    private static final String FALLBACK_DOWNLOAD_URL = "https://github.com/Mon-pub/F1Whisper/releases/latest";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            return;
        }
        final long referenceId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (referenceId <= 0) {
            return;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final long ourId = prefs.getLong(DownloadApkActivity.PREF_DOWNLOAD_ID, -1);
        if (referenceId != ourId) {
            // Not our self-update download (some other DownloadManager job); ignore.
            return;
        }
        prefs.edit().remove(DownloadApkActivity.PREF_DOWNLOAD_ID).apply();

        int status = DownloadManager.STATUS_FAILED;
        int reason = -1;
        long bytesDownloaded = -1;
        long bytesTotal = -1;
        final DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager != null) {
            final DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(referenceId);
            try (Cursor cursor = downloadManager.query(query)) {
                if (cursor != null && cursor.moveToFirst()) {
                    final int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    if (statusIdx >= 0) {
                        status = cursor.getInt(statusIdx);
                    }
                    final int reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                    if (reasonIdx >= 0) {
                        reason = cursor.getInt(reasonIdx);
                    }
                    final int downloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    if (downloadedIdx >= 0) {
                        bytesDownloaded = cursor.getLong(downloadedIdx);
                    }
                    final int totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                    if (totalIdx >= 0) {
                        bytesTotal = cursor.getLong(totalIdx);
                    }
                }
            } catch (Exception e) {
                logger.error("Could not query download status", e);
            }
        }

        final boolean success = status == DownloadManager.STATUS_SUCCESSFUL;
        if (!success) {
            // F1Whisper: status alone (16 = STATUS_FAILED) is not actionable; log the COLUMN_REASON and
            // the byte progress so a failure report tells us exactly why (HTTP error, insufficient
            // space, file-already-exists, cannot-resume, etc.) and how far it got.
            logger.warn(
                "Self-update download failed, status {} reason {} ({}), {} / {} bytes",
                status, reason, describeReason(reason), bytesDownloaded, bytesTotal);
        }
        postNotification(context, referenceId, success);
    }

    /**
     * F1Whisper: map a {@link DownloadManager} {@code COLUMN_REASON} to a short human-readable string
     * for diagnostics. For failed downloads the reason is one of the {@code DownloadManager.ERROR_*}
     * constants; for paused downloads it is a {@code PAUSED_*} constant; an HTTP status code (400-599)
     * may also be reported directly.
     */
    @NonNull
    private static String describeReason(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "cannot resume";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "external storage missing";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "file already exists";
            case DownloadManager.ERROR_FILE_ERROR:
                return "file/storage error";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "http data error";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "insufficient space";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "too many redirects";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "unhandled http code";
            case DownloadManager.ERROR_UNKNOWN:
                return "unknown error";
            default:
                if (reason >= 400 && reason < 600) {
                    return "http " + reason;
                }
                return "reason " + reason;
        }
    }

    private void postNotification(Context context, long downloadId, boolean success) {
        final Intent target;
        final String title;
        final String text;
        if (success) {
            target = new Intent(context, DownloadApkActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(DownloadApkActivity.EXTRA_INSTALL_DOWNLOAD_ID, downloadId);
            title = context.getString(R.string.self_updater_update_ready_title);
            text = context.getString(R.string.self_updater_update_ready_text);
        } else {
            target = new Intent(Intent.ACTION_VIEW, Uri.parse(FALLBACK_DOWNLOAD_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            title = context.getString(R.string.error);
            text = context.getString(R.string.self_updater_download_failed);
        }

        final PendingIntent contentIntent = PendingIntent.getActivity(
            context,
            success ? 1 : 2,
            target,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationChannels.NOTIFICATION_CHANNEL_ALERT)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_push)
            .setAutoCancel(true)
            .setContentIntent(contentIntent);
        try {
            NotificationManagerCompat.from(context).notify(UPDATE_READY_NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            logger.error("Could not post update-ready notification", e);
        }
    }
}
