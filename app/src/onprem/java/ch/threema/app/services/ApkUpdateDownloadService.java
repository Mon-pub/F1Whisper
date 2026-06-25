package ch.threema.app.services;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import org.slf4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import ch.threema.app.R;
import ch.threema.app.activities.DownloadApkActivity;
import ch.threema.app.notifications.NotificationChannels;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * F1Whisper: foreground service that downloads the in-app self-update APK from the GitHub release
 * URL (supplied by the check_license {@code updateUrl}) and hands the finished file to the system
 * package installer.
 *
 * <p><b>Why not {@link android.app.DownloadManager}?</b> The previous implementation enqueued the
 * APK with the system DownloadManager. On locked-down OEMs (notably Xiaomi/MIUI) the
 * {@code com.android.providers.downloads} provider is frequently frozen or background-restricted, so
 * {@code enqueue()} returns an id but the queue is never processed -- no progress notification, no
 * network traffic, no file, and no failure broadcast either. That is undiagnosable and unfixable from
 * the app side. This service removes the dependency entirely: it streams the APK itself over an
 * UNPINNED OkHttp client (the download target is GitHub, not our cert-pinned OnPrem server) into the
 * app-private external files dir, shows its own progress notification, and on completion posts a
 * "tap to install" notification that relaunches {@link DownloadApkActivity} to run the install
 * (reusing the unknown-sources grant flow). Running as a foreground service keeps the download alive
 * if the user backgrounds the app, which is exactly the case MIUI used to kill.
 */
public class ApkUpdateDownloadService extends Service {
    private static final Logger logger = getThreemaLogger("ApkUpdateDownloadService");

    private static final String ACTION_DOWNLOAD = "ch.threema.app.action.DOWNLOAD_UPDATE";
    private static final String EXTRA_URL = "url";

    private static final String FALLBACK_DOWNLOAD_FILENAME = "F1Whisper-update.apk";
    private static final String FALLBACK_DOWNLOAD_URL = "https://github.com/Mon-pub/F1Whisper/releases/latest";

    private static final int PROGRESS_NOTIFICATION_ID = 27395;
    private static final int RESULT_NOTIFICATION_ID = 27394;

    private static final int FG_SERVICE_TYPE =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0;

    // A finished release apk is tens of MB; anything smaller is a truncated/error body, not an apk.
    private static final long MIN_PLAUSIBLE_APK_BYTES = 1024L * 1024L;
    private static final int BUFFER_SIZE = 64 * 1024;

    private ExecutorService executor;
    private NotificationManagerCompat notificationManager;

    /**
     * F1Whisper: start the foreground download for {@code downloadUrl}. Must be called while the app
     * is in the foreground (it is invoked from the user's "Download" tap in {@link DownloadApkActivity}),
     * which satisfies the Android 12+ foreground-service-start restriction.
     */
    @AnyThread
    public static void enqueue(@NonNull Context context, @NonNull String downloadUrl) {
        final Intent intent = new Intent(context, ApkUpdateDownloadService.class)
            .setAction(ACTION_DOWNLOAD)
            .putExtra(EXTRA_URL, downloadUrl);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        notificationManager = NotificationManagerCompat.from(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        final String url = intent != null ? intent.getStringExtra(EXTRA_URL) : null;
        // Promote to foreground immediately (within the 5s window) regardless of outcome.
        ServiceCompat.startForeground(
            this,
            PROGRESS_NOTIFICATION_ID,
            buildProgressNotification(0, true),
            FG_SERVICE_TYPE);

        if (url == null || !url.startsWith("https://")) {
            logger.warn("No https update url; not starting download");
            postResultNotification(null);
            stopForegroundAndSelf();
            return START_NOT_STICKY;
        }

        executor.execute(() -> {
            File downloaded = null;
            try {
                downloaded = download(url);
            } catch (Exception e) {
                logger.error("Self-update download failed", e);
            }
            postResultNotification(downloaded);
            stopForegroundAndSelf();
        });

        // Do not auto-restart with a null intent (we have no url to resume); the user can retry.
        return START_NOT_STICKY;
    }

    @NonNull
    private File download(@NonNull String url) throws IOException {
        final String fileName = fileNameFromUrl(url);
        final File targetDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (targetDir == null) {
            throw new IOException("external files dir unavailable");
        }
        //noinspection ResultOfMethodCallIgnored
        targetDir.mkdirs();

        // Idempotent retries + cleanup: remove any stale apk(s) from a previous attempt before writing.
        cleanupOldApks(targetDir);

        final File outFile = new File(targetDir, fileName);

        final Request request = new Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
            .build();

        try (Response response = client().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("http " + response.code());
            }
            final ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("empty response body");
            }
            final long contentLength = body.contentLength();

            long readTotal = 0;
            int lastPercent = -1;
            long lastNotifyAt = 0;
            try (InputStream in = body.byteStream();
                 OutputStream out = new FileOutputStream(outFile)) {
                final byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    readTotal += read;

                    if (contentLength > 0) {
                        final int percent = (int) Math.min(100, (readTotal * 100) / contentLength);
                        // Throttle notification updates: only on a percent change, at most ~2/sec.
                        final long now = android.os.SystemClock.elapsedRealtime();
                        if (percent != lastPercent && now - lastNotifyAt >= 400) {
                            lastPercent = percent;
                            lastNotifyAt = now;
                            updateProgress(percent);
                        }
                    }
                }
                out.flush();
            }

            if (readTotal < MIN_PLAUSIBLE_APK_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
                throw new IOException("downloaded file too small (" + readTotal + " bytes)");
            }
            logger.info("Self-update downloaded {} ({} bytes)", fileName, readTotal);
            return outFile;
        }
    }

    /**
     * Build a fresh UNPINNED OkHttp client for the GitHub download. This must NOT reuse the OnPrem
     * cert-pinned client (that pins our server's cert and would reject GitHub / its CDN). Redirects are
     * followed (the GitHub asset url 302-redirects to a download CDN).
     */
    @NonNull
    private static OkHttpClient client() {
        return new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .cache(null)
            .build();
    }

    /**
     * Derive the local filename from the URL (the GitHub release asset name, e.g.
     * "F1Whisper-v6.4.3-24-onprem-release.apk") so the saved file is self-describing. Falls back only
     * if the URL has no {@code .apk} last segment.
     */
    @NonNull
    private static String fileNameFromUrl(@NonNull String downloadUrl) {
        final String last = Uri.parse(downloadUrl).getLastPathSegment();
        if (last != null && last.endsWith(".apk")) {
            return last;
        }
        return FALLBACK_DOWNLOAD_FILENAME;
    }

    private static void cleanupOldApks(@NonNull File dir) {
        final File[] old = dir.listFiles((d, name) -> name.startsWith("F1Whisper-") && name.endsWith(".apk"));
        if (old == null) {
            return;
        }
        for (File apk : old) {
            if (!apk.delete()) {
                logger.warn("Could not delete stale update file {}", apk.getName());
            }
        }
    }

    @NonNull
    private NotificationCompat.Builder progressBuilder() {
        return new NotificationCompat.Builder(this, NotificationChannels.NOTIFICATION_CHANNEL_BACKUP_RESTORE_IN_PROGRESS)
            .setContentTitle(getString(R.string.self_updater_downloading_background))
            .setSmallIcon(R.drawable.ic_notification_push)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setPriority(NotificationCompat.PRIORITY_LOW);
    }

    @NonNull
    private android.app.Notification buildProgressNotification(int percent, boolean indeterminate) {
        return progressBuilder().setProgress(100, percent, indeterminate).build();
    }

    private void updateProgress(int percent) {
        try {
            notificationManager.notify(PROGRESS_NOTIFICATION_ID, buildProgressNotification(percent, false));
        } catch (Exception e) {
            // POST_NOTIFICATIONS may be denied; the download proceeds regardless.
            logger.debug("Could not update progress notification", e);
        }
    }

    /**
     * Post the terminal notification: on success a "tap to install" that relaunches
     * {@link DownloadApkActivity} with the downloaded file path; on failure one linking to the GitHub
     * releases page for a manual download.
     */
    private void postResultNotification(@Nullable File downloaded) {
        final boolean success = downloaded != null;
        final Intent target;
        final String title;
        final String text;
        if (success) {
            target = new Intent(this, DownloadApkActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(DownloadApkActivity.EXTRA_INSTALL_FILE_PATH, downloaded.getAbsolutePath());
            title = getString(R.string.self_updater_update_ready_title);
            text = getString(R.string.self_updater_update_ready_text);
        } else {
            target = new Intent(Intent.ACTION_VIEW, Uri.parse(FALLBACK_DOWNLOAD_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            title = getString(R.string.error);
            text = getString(R.string.self_updater_download_failed);
        }

        final PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            success ? 1 : 2,
            target,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final NotificationCompat.Builder builder =
            new NotificationCompat.Builder(this, NotificationChannels.NOTIFICATION_CHANNEL_ALERT)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification_push)
                .setAutoCancel(true)
                .setContentIntent(contentIntent);
        try {
            notificationManager.notify(RESULT_NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            logger.error("Could not post update-result notification", e);
        }
    }

    private void stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
