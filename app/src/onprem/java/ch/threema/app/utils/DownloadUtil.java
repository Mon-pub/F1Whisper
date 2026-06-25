package ch.threema.app.utils;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import org.slf4j.Logger;

import java.io.File;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

/**
 * F1Whisper: APK downloader for the sideloaded onprem self-updater (the other foss/libre flavors
 * ship an empty stub). This is the StoreThreema implementation with one fork change: the saved-file
 * name is a code constant instead of the store-only {@code R.string.shop_download_filename} string
 * resource (a download filename is not translatable UI text). The apk is fetched into the system
 * Downloads dir by the DownloadManager; {@link #deleteOldAPKs} best-effort cleans it up afterwards.
 */
public class DownloadUtil {
    private static final Logger logger = getThreemaLogger("DownloadUtil");

    // F1Whisper: fallback filename only when the update URL carries no usable .apk segment.
    private static final String FALLBACK_DOWNLOAD_FILENAME = "F1Whisper-update.apk";

    /**
     * Derive the local filename for the downloaded APK from its URL -- i.e. the GitHub release asset
     * name, e.g. "F1Whisper-v6.4.3-8-onprem-release.apk" -- so the download notification and the saved
     * file are self-describing (which version) instead of a generic name. Falls back only if the URL
     * has no `.apk` last segment.
     */
    @NonNull
    private static String fileNameFromUrl(@NonNull String downloadUrl) {
        String last = Uri.parse(downloadUrl).getLastPathSegment();
        if (last != null && last.endsWith(".apk")) {
            return last;
        }
        return FALLBACK_DOWNLOAD_FILENAME;
    }

    /**
     * Starts the download and provides the download state info.
     *
     * @param context     the application context
     * @param downloadUrl the download URL
     */
    public static long downloadUpdate(@NonNull Context context, @NonNull String downloadUrl) {
        logger.info("Downloading update");
        // Derive the filename from the original URL (before the ?download query is appended).
        String fileName = fileNameFromUrl(downloadUrl);
        Uri uri = Uri.parse(downloadUrl).buildUpon()
            .appendQueryParameter("download", "true")
            .build();

        return download(context, uri, fileName);
    }

    /**
     * Starts the download with the download manager, saving it under {@code fileName} in the app's
     * own external files directory so it is named after the release (not a generic name).
     *
     * <p>F1Whisper: the destination is the app-private external files dir
     * ({@link Context#getExternalFilesDir}), NOT the public Downloads dir. The earlier public-Downloads
     * destination was unreliable on locked-down OEMs (Xiaomi/MIUI etc.): scoped storage (Android 11+)
     * makes the write subject to MediaStore policy, and {@link #deleteOldAPKs} cannot clean a stale
     * same-named file there, so a second attempt at the same release fails with
     * {@code ERROR_FILE_ALREADY_EXISTS}. The app-private external dir needs no storage permission, can
     * always be cleaned by us (so we pre-delete the target below to make retries idempotent), has more
     * room than the internal download cache on low-storage devices, and is still installable via
     * {@link DownloadManager#getUriForDownloadedFile} (the same content:// path the install step uses).
     *
     * @param context  the application context
     * @param url      the url of the apk file
     * @param fileName the local filename to save as (the GitHub asset name)
     */
    private static long download(
        @NonNull Context context,
        @NonNull Uri url,
        @NonNull String fileName
    ) {
        // F1Whisper: make a retry of the same release idempotent -- delete any stale partial/complete
        // file from a previous attempt so the enqueue never fails with ERROR_FILE_ALREADY_EXISTS.
        final File targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (targetDir != null) {
            final File stale = new File(targetDir, fileName);
            if (stale.exists() && !stale.delete()) {
                logger.warn("Could not delete stale update file {}", stale.getName());
            }
        }

        DownloadManager.Request request = new DownloadManager.Request(url);
        request.setTitle(fileName);
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName);
        // F1Whisper: GitHub serves the asset as application/octet-stream; pin the apk MIME type so the
        // system "download complete" notification (and our own install intent) reliably resolve to the
        // package installer.
        request.setMimeType("application/vnd.android.package-archive");
        // F1Whisper: a self-update is worth fetching on any connection the user has chosen to be on.
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(true);
        // F1Whisper: show progress while downloading; the completion is surfaced by our own
        // "tap to install" notification (UpdateDownloadCompleteReceiver), so no need to also keep the
        // system completed-notification around.
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);

        // enqueue file for download
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        final long id = manager.enqueue(request);
        logger.info("Enqueued update download {} with id {}", fileName, id);
        return id;
    }

    /**
     * Deletes old APKs in the downloads directory. For Android 11 and newer, the files cannot be
     * deleted due to scoped storage; newer app updates are deleted automatically by the system and
     * therefore this method is no longer needed there.
     *
     * @param context needed to resolve the downloads directory
     */
    @AnyThread
    public static void deleteOldAPKs(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return;
        }

        if (!ConfigUtils.isPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return;
        }

        RuntimeUtil.runOnWorkerThread(() -> {
            File downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            // Downloaded update APKs are named after the release (e.g. F1Whisper-v6.4.3-8-onprem-
            // release.apk) plus the legacy fallback name; clean up any of them.
            File[] oldApks = downloadPath.listFiles(
                (dir, name) -> name.startsWith("F1Whisper-") && name.endsWith(".apk"));
            if (oldApks == null) {
                return;
            }
            for (File apk : oldApks) {
                try {
                    FileUtil.deleteFileOrWarn(apk, "download file", logger);
                } catch (SecurityException e) {
                    logger.error("could not delete old apk file", e);
                }
            }
        });
    }
}
