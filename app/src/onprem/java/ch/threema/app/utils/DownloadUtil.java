package ch.threema.app.utils;

import android.Manifest;
import android.content.Context;
import android.os.Build;
import android.os.Environment;

import org.slf4j.Logger;

import java.io.File;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

/**
 * F1Whisper: legacy cleanup helper for the onprem self-updater. The actual APK download is performed
 * by {@link ch.threema.app.services.ApkUpdateDownloadService} (OkHttp, app-private dir) -- NOT the
 * system DownloadManager, which is silently broken on locked-down OEMs. This class only keeps
 * {@link #deleteOldAPKs}, invoked by the shared {@code UpdateReceiver} after an update is installed to
 * sweep up any APK left behind in the public Downloads dir by the old (pre-OkHttp) implementation on
 * devices below Android 11.
 */
public class DownloadUtil {
    private static final Logger logger = getThreemaLogger("DownloadUtil");

    /**
     * Deletes old APKs in the public downloads directory. For Android 11 and newer, scoped storage
     * makes those files unreachable (and the app no longer writes there anyway -- the self-updater now
     * downloads into the app-private external files dir), so this is a no-op there.
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
