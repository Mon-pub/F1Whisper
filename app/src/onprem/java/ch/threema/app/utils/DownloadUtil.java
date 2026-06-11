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

    // F1Whisper: fixed filename for the downloaded update APK (not user-facing / not translatable).
    private static final String DOWNLOAD_FILENAME = "F1Whisper-update.apk";

    /**
     * Starts the download and provides the download state info.
     *
     * @param context     the application context
     * @param downloadUrl the download URL
     */
    public static void downloadUpdate(@NonNull Context context, @NonNull String downloadUrl) {
        logger.info("Downloading update");
        Uri uri = Uri.parse(downloadUrl).buildUpon()
            .appendQueryParameter("download", "true")
            .build();

        download(context, uri);
    }

    /**
     * Starts the download with the download manager.
     *
     * @param context the application context
     * @param url     the url of the apk file
     */
    private static void download(
        @NonNull Context context,
        @NonNull Uri url
    ) {
        DownloadManager.Request request = new DownloadManager.Request(url);
        request.setTitle(DOWNLOAD_FILENAME);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        // enqueue file for download
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        final long id = manager.enqueue(request);
        logger.info("Enqueued update download with id {}", id);
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
            File temporaryAPKFile = new File(downloadPath.getPath(), DOWNLOAD_FILENAME);
            if (temporaryAPKFile.exists()) {
                try {
                    FileUtil.deleteFileOrWarn(temporaryAPKFile, "download file", logger);
                } catch (SecurityException e) {
                    logger.error("could not delete old apk file", e);
                }
            }
        });
    }
}
