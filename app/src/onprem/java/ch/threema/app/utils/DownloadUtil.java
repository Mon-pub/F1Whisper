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
    public static void downloadUpdate(@NonNull Context context, @NonNull String downloadUrl) {
        logger.info("Downloading update");
        // Derive the filename from the original URL (before the ?download query is appended).
        String fileName = fileNameFromUrl(downloadUrl);
        Uri uri = Uri.parse(downloadUrl).buildUpon()
            .appendQueryParameter("download", "true")
            .build();

        download(context, uri, fileName);
    }

    /**
     * Starts the download with the download manager, saving it under {@code fileName} in the public
     * Downloads directory so it is named after the release (not a generic name).
     *
     * @param context  the application context
     * @param url      the url of the apk file
     * @param fileName the local filename to save as (the GitHub asset name)
     */
    private static void download(
        @NonNull Context context,
        @NonNull Uri url,
        @NonNull String fileName
    ) {
        DownloadManager.Request request = new DownloadManager.Request(url);
        request.setTitle(fileName);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        // enqueue file for download
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        final long id = manager.enqueue(request);
        logger.info("Enqueued update download {} with id {}", fileName, id);
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
