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

    // Fork review H-08: FIXED local filename — the on-disk name is never derived from the
    // (server-supplied) URL, so a hostile/misconfigured update endpoint cannot choose it.
    private static final String DOWNLOAD_FILENAME = "F1Whisper-update.apk";
    private static final String FALLBACK_DOWNLOAD_URL = "https://github.com/Mon-pub/F1Whisper/releases/latest";

    private static final int PROGRESS_NOTIFICATION_ID = 27395;
    private static final int RESULT_NOTIFICATION_ID = 27394;

    private static final int FG_SERVICE_TYPE =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0;

    // A finished release apk is tens of MB; anything smaller is a truncated/error body, not an apk.
    private static final long MIN_PLAUSIBLE_APK_BYTES = 1024L * 1024L;
    // Fork review H-08: hard upper bound enforced WHILE streaming. A universal release APK is
    // ~55 MB; anything approaching this cap is not our update and must not exhaust storage.
    private static final long MAX_APK_BYTES = 200L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 64 * 1024;

    private ExecutorService executor;
    private NotificationManagerCompat notificationManager;

    // Follow-up review P0-7: per-operation start/stop lifecycle (replaces the racy
    // lastStartId + downloadActive pair — see ApkUpdateStartArbiter for the failure interleaving).
    // lifecycleLock spans the start decision INCLUDING its foreground transition (S2-01) and the
    // finish+stop sequence, so no new start can be accepted between "operation went idle" and its
    // stopSelfResult call, and no promotion can interleave with another operation's teardown.
    private final Object lifecycleLock = new Object();
    private final ApkUpdateStartArbiter arbiter = new ApkUpdateStartArbiter();

    // Last progress percent shown by the ACTIVE operation (written by its download thread). A
    // rejected duplicate/invalid start re-promotes with THIS value so the running download's
    // notification is never reset (S2-01).
    private volatile int lastProgressPercent = 0;

    // Second follow-up S2-06: the active operation's network call, retained so onDestroy can
    // cancel it — thread interruption alone does not unblock a synchronous OkHttp call, and a
    // destroyed-then-recreated service must never leave a zombie call writing files. Guarded by
    // lifecycleLock.
    @Nullable
    private okhttp3.Call activeCall;

    // Third follow-up S3-05 (T3-10): process-unique per-instance nonce so a destroyed-and-recreated
    // instance (which restarts the startId sequence) can never collide with — or clean up — a
    // foreign instance's partial download. See ApkUpdateOperationFiles.
    private final String instanceNonce = java.util.UUID.randomUUID().toString();

    // Third follow-up S3-05 (T3-10): lifecycle ownership. onDestroy() can run in the window between
    // the executor accepting runDownload and the download registering its call; a worker must reject
    // registration/publication after teardown rather than leave a zombie writing files.
    //
    // F1Whisper (fourth fork review, F4-12): owned by ApkUpdateLifecycleOwnership rather than by a bare flag under
    // lifecycleLock, because "check under the lock, act after it" is not enough for the two transitions that touch
    // PROCESS-WIDE state - the shared final APK filename and the ready-state record. Those go through runIfOwned, which
    // makes the check and the act indivisible against destroy().
    @NonNull
    private final ApkUpdateLifecycleOwnership ownership = new ApkUpdateLifecycleOwnership();

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
        final boolean requestValid = url != null && url.startsWith("https://");

        // Second follow-up S2-01: the foreground transition is PART of the arbitration decision
        // and runs under the same lock, so a start can never promote outside the lock and then be
        // demoted by a concurrently finishing operation without re-promotion. Every
        // startForegroundService() delivery is still answered by a startForeground() call well
        // within the 5s window (the lock is only ever held for short, non-blocking sections).
        synchronized (lifecycleLock) {
            final ApkUpdateStartArbiter.StartDecision decision = arbiter.onStart(startId, requestValid);
            switch (decision.notificationAction) {
                case PROMOTE_FRESH:
                    lastProgressPercent = 0;
                    ServiceCompat.startForeground(
                        this, PROGRESS_NOTIFICATION_ID, buildProgressNotification(0, true), FG_SERVICE_TYPE);
                    break;
                case PROMOTE_CURRENT:
                    // Re-promote with the running download's CURRENT progress: satisfies the
                    // start contract without resetting the active operation's notification.
                    final int percent = lastProgressPercent;
                    ServiceCompat.startForeground(
                        this, PROGRESS_NOTIFICATION_ID, buildProgressNotification(percent, percent == 0), FG_SERVICE_TYPE);
                    break;
                case PROMOTE_THEN_STOP:
                    ServiceCompat.startForeground(
                        this, PROGRESS_NOTIFICATION_ID, buildProgressNotification(0, true), FG_SERVICE_TYPE);
                    break;
            }
            switch (decision) {
                case ACCEPT:
                    executor.execute(() -> runDownload(url, startId));
                    break;
                case REJECT_DUPLICATE:
                    logger.info("Update download already in progress; ignoring duplicate start");
                    break;
                case REJECT_INVALID_KEEP_RUNNING:
                    // Follow-up review P0-7: an invalid start while a download runs must NEVER
                    // touch the running download's foreground state or stop the service (the old
                    // code stopped the service outright here, cancelling the download).
                    logger.warn("No https update url in start request; a download is running — ignoring");
                    break;
                case REJECT_INVALID_STOP_SELF:
                    logger.warn("No https update url; not starting download");
                    postResultNotification(null);
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
                    // The invalid start's OWN id: if a newer (possibly valid) start already
                    // arrived, stopSelfResult returns false and that start proceeds.
                    stopSelfResult(startId);
                    break;
            }
        }

        // Do not auto-restart with a null intent (we have no url to resume); the user can retry.
        return START_NOT_STICKY;
    }

    /** The accepted operation: download, complete ALL terminal handling, then go idle and stop. */
    private void runDownload(@NonNull String url, int myStartId) {
        File downloaded = null;
        try {
            downloaded = download(url, myStartId);
        } catch (Exception e) {
            logger.error("Self-update download failed", e);
        }
        // Terminal handling BEFORE the arbiter exposes idle: the ready-state was persisted inside
        // download(), and the result notification is posted while this operation still owns the
        // service — a duplicate tap arriving now is still rejected, and this operation's stop
        // decision correctly covers it.
        postResultNotification(downloaded);
        synchronized (lifecycleLock) {
            activeCall = null;
            final int stopId = arbiter.onFinished(myStartId);
            // Second follow-up S2-01: the finisher NEVER demotes the foreground state. If
            // stopSelfResult stops the service, the system removes the foreground notification
            // with it; if a newer start was already delivered it returns false and that start's
            // own locked decision (PROMOTE_FRESH / PROMOTE_CURRENT / PROMOTE_THEN_STOP) governs
            // the notification — an explicit stopForeground here is exactly the demotion race
            // the review identified.
            // Follow-up review P0-7: under lifecycleLock no new start can be ACCEPTED between the
            // idle transition and this call, so stopId can only be this operation's own id or a
            // rejected start's — never an accepted download's. A yet-newer start delivered after
            // this decision makes stopSelfResult return false (service lives; that start proceeds).
            stopSelfResult(stopId);
        }
    }

    @NonNull
    private File download(@NonNull String url, int myStartId) throws IOException {
        final File targetDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (targetDir == null) {
            throw new IOException("external files dir unavailable");
        }
        //noinspection ResultOfMethodCallIgnored
        targetDir.mkdirs();

        // S3-05 (T3-10): establish lifecycle ownership BEFORE any cleanup or I/O. A worker scheduled
        // just before onDestroy() must not run — reject it here rather than delete files and start a
        // network call on behalf of a destroyed service.
        //
        // F1Whisper (fourth fork review, F4-12): the check and the cleanup are ONE critical section, not a check followed
        // by an unguarded act. onDestroy() sets `destroyed` under this same lock, so it previously had a window between
        // the two in which to run: a predecessor could pass the check, be torn down, and then delete the final APK and
        // clear the ready-state record that its REPLACEMENT had already published. The app would show an update as ready
        // whose file was gone, or lose a newer instance's completed download. Both of those are shared, process-wide
        // state; the operation-unique partial filenames protect neither.
        //
        // Idempotent retries + cleanup: remove the stale final apk and THIS instance's own partial
        // downloads from a previous attempt before writing (never a foreign instance's live partial).
        final boolean cleaned = ownership.runIfOwned(() -> {
            cleanupOldApks(targetDir, instanceNonce);
            ApkUpdateReadyState.clear(this);
        });
        if (!cleaned) {
            throw new IOException("service destroyed before download started");
        }

        // Second follow-up S2-06: stream into an OPERATION-UNIQUE temp file and publish the final
        // path only after validation, via an atomic same-directory rename — the ready state can
        // never point at a partial file, and a stale operation from a destroyed service instance
        // cannot overwrite a newer operation's finished download.
        final File outFile = new File(targetDir, ApkUpdateOperationFiles.partFileName(DOWNLOAD_FILENAME, instanceNonce, myStartId));

        final Request request = new Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
            .build();

        // S2-06: retain the call under the operation's ownership so onDestroy can cancel it.
        final okhttp3.Call call = client().newCall(request);
        synchronized (lifecycleLock) {
            // S3-05 (T3-10): reject registration after teardown — do not start the network call for
            // a destroyed service (onDestroy observed a null activeCall and returned; this worker
            // must not resurrect I/O behind it).
            if (ownership.isDestroyed()) {
                throw new IOException("service destroyed before the network call was registered");
            }
            activeCall = call;
        }

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("http " + response.code());
            }
            final ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("empty response body");
            }
            final long contentLength = body.contentLength();
            if (contentLength > MAX_APK_BYTES) {
                throw new IOException("declared size too large (" + contentLength + " bytes)");
            }

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

                    // Fork review H-08: hard streaming cap — a lying/absent Content-Length must not
                    // let a hostile endpoint exhaust storage.
                    if (readTotal > MAX_APK_BYTES) {
                        throw new IOException("download exceeded the " + MAX_APK_BYTES + "-byte cap");
                    }

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
                throw new IOException("downloaded file too small (" + readTotal + " bytes)");
            }

            // Fork review H-08: validate BEFORE any installer handoff — this application's package,
            // strictly newer versionCode, and a signing certificate shared with the installed app.
            final long candidateVersionCode = validateDownloadedApk(outFile);

            // S2-06: atomic publish — only a fully validated file ever carries the final name, and
            // only the final name is ever recorded as ready.
            // S3-05 (T3-10): re-check ownership immediately before publication — a service destroyed
            // during the (slow) download must not publish a ready-state that a recreated instance
            // would then act on.
            //
            // F1Whisper (fourth fork review, F4-12): the check, the rename and the ready-state record are ONE critical
            // section. Split, onDestroy() could land between the check and the rename, and a predecessor would then
            // publish its own file under the shared final name and record it as ready AFTER its authority had ended -
            // overwriting or contradicting whatever its replacement had done. The network transfer and the APK
            // validation deliberately stay outside: they are slow, they touch only this operation's own temp file, and
            // holding the lifecycle lock across them would block onDestroy for the length of a download.
            final File finalFile = new File(targetDir, DOWNLOAD_FILENAME);
            final boolean published = ownership.runIfOwned(() -> {
                if (!outFile.renameTo(finalFile)) {
                    throw new IOException("could not move validated update into place");
                }
                ApkUpdateReadyState.record(this, finalFile.getAbsolutePath(), candidateVersionCode);
            });
            if (!published) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
                throw new IOException("service destroyed before publish");
            }

            logger.info("Self-update downloaded and validated {} ({} bytes)", DOWNLOAD_FILENAME, readTotal);
            return finalFile;
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
            throw e;
        }
    }

    /**
     * Fork review H-08: reject the downloaded archive unless it is THIS application
     * (package name), STRICTLY NEWER (versionCode), and signed with at least one certificate the
     * installed app also carries (otherwise Android would refuse the upgrade anyway — better to
     * fail here with a clear reason than to bounce the user into the installer). Decision logic
     * lives in the unit-tested {@link ApkUpdateValidator}.
     *
     * @return the validated candidate's versionCode (the caller records the ready state only
     *     AFTER the atomic rename to the final path — S2-06)
     */
    private long validateDownloadedApk(@NonNull File apk) throws IOException {
        final android.content.pm.PackageManager pm = getPackageManager();
        final int signingFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            : android.content.pm.PackageManager.GET_SIGNATURES;

        final android.content.pm.PackageInfo candidate =
            pm.getPackageArchiveInfo(apk.getAbsolutePath(), signingFlag);
        final android.content.pm.PackageInfo installed;
        try {
            installed = pm.getPackageInfo(getPackageName(), signingFlag);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new IOException("own package info unavailable", e);
        }

        final ApkUpdateValidator.Result result = ApkUpdateValidator.validate(
            candidate != null ? candidate.packageName : null,
            candidate != null ? androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(candidate) : -1L,
            signerSha256Set(candidate),
            getPackageName(),
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(installed),
            signerSha256Set(installed));

        if (result != ApkUpdateValidator.Result.OK) {
            throw new IOException("downloaded apk failed validation: " + result);
        }

        // The ready state (home-screen install banner, covering the notification-denied case) is
        // recorded by the caller after the atomic rename — never against the temp path.
        return androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(candidate);
    }

    /** Lowercase-hex SHA-256 digests of all signing certificates, or null if none are exposed. */
    @Nullable
    private static java.util.Set<String> signerSha256Set(@Nullable android.content.pm.PackageInfo packageInfo) {
        if (packageInfo == null) {
            return null;
        }
        final android.content.pm.Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signatures = packageInfo.signingInfo != null
                ? packageInfo.signingInfo.getApkContentsSigners()
                : null;
        } else {
            signatures = packageInfo.signatures;
        }
        if (signatures == null || signatures.length == 0) {
            return null;
        }
        try {
            final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            final java.util.Set<String> result = new java.util.HashSet<>();
            for (android.content.pm.Signature signature : signatures) {
                final byte[] hash = digest.digest(signature.toByteArray());
                final StringBuilder hex = new StringBuilder(hash.length * 2);
                for (byte b : hash) {
                    hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
                }
                result.add(hex.toString());
            }
            return result;
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every Android release; treat as unreadable.
            return null;
        }
    }

    /**
     * Build a fresh UNPINNED OkHttp client for the GitHub download. This must NOT reuse the OnPrem
     * cert-pinned client (that pins our server's cert and would reject GitHub / its CDN). Same-scheme
     * redirects are followed (the GitHub asset url 302-redirects to a download CDN) but — fork review
     * H-08 — cross-scheme redirects are disabled AND every hop is asserted HTTPS by a network
     * interceptor, so no part of the APK transfer can be downgraded to plaintext after the initial
     * URL check.
     */
    @NonNull
    private static OkHttpClient client() {
        return new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .addNetworkInterceptor(chain -> {
                if (!chain.request().url().isHttps()) {
                    throw new IOException("refusing non-https hop: " + chain.request().url().host());
                }
                return chain.proceed(chain.request());
            })
            .retryOnConnectionFailure(true)
            .cache(null)
            .build();
    }

    /**
     * S3-05 (T3-10): delete the stale FINAL apk plus THIS instance's own partials — never a foreign
     * instance's partial, which a winding-down predecessor may still be writing. The per-file delete
     * decision lives in the unit-tested {@link ApkUpdateOperationFiles#shouldDelete}.
     */
    private static void cleanupOldApks(@NonNull File dir, @NonNull String instanceNonce) {
        final File[] candidates = dir.listFiles((d, name) ->
            ApkUpdateOperationFiles.shouldDelete(name, DOWNLOAD_FILENAME, instanceNonce));
        if (candidates == null) {
            return;
        }
        for (File apk : candidates) {
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
        lastProgressPercent = percent;
        // Second follow-up S2-09 (lint MissingPermission): on Android 13+ posting a notification
        // requires the runtime POST_NOTIFICATIONS permission. The download itself never depends
        // on it (the foreground-service notification is exempt), so a denial only suppresses the
        // optional progress updates. The check is INLINE because lint's permission flow analysis
        // does not follow helper methods.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            notificationManager.notify(PROGRESS_NOTIFICATION_ID, buildProgressNotification(percent, false));
        } catch (Exception e) {
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
        // S2-09: inline POST_NOTIFICATIONS guard (see updateProgress). The home-screen
        // ready-state banner still offers the install path when notifications are denied.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            logger.info("Notifications not permitted; skipping update-result notification");
            return;
        }
        try {
            notificationManager.notify(RESULT_NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            logger.error("Could not post update-result notification", e);
        }
    }

    @Override
    public void onDestroy() {
        // Second follow-up S2-06: explicitly cancel the retained network call — shutdownNow only
        // interrupts the thread, which does NOT unblock a synchronous OkHttp call; a recreated
        // instance must never share I/O with a zombie predecessor.
        final okhttp3.Call call;
        // F1Whisper (fourth fork review, F4-12): end authority over the shared update state FIRST, and block until any
        // transition already inside runIfOwned has finished. That is what makes "destroyed" mean "will not touch shared
        // state again" rather than "was asked to stop".
        ownership.destroy();
        synchronized (lifecycleLock) {
            // S3-05 (T3-10): a worker that has not yet registered its call will observe the lost ownership and abort.
            call = activeCall;
            activeCall = null;
        }
        if (call != null) {
            call.cancel();
        }
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
