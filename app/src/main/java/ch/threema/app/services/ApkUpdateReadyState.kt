package ch.threema.app.services

import android.content.Context
import android.preference.PreferenceManager
import androidx.core.content.pm.PackageInfoCompat
import ch.threema.base.utils.getThreemaLogger
import java.io.File

private val logger = getThreemaLogger("ApkUpdateReadyState")

/**
 * F1Whisper (fork review H-08): persisted "a validated self-update APK is downloaded and ready"
 * state, so a completed download always has an IN-APP install path (the home-screen banner) and is
 * never stranded behind a denied/missed notification.
 *
 * Lives in the MAIN source set so [ch.threema.app.home.HomeActivity] can read it in every flavor;
 * only the onprem `ApkUpdateDownloadService` ever WRITES it (other flavors simply never see a
 * ready state). Self-healing: the state is only reported while the file still exists AND its
 * version is still strictly newer than the installed app — after a successful install (or a stale
 * file) it clears itself.
 */
object ApkUpdateReadyState {

    private const val PREF_READY_PATH = "self_update_ready_path"
    private const val PREF_READY_VERSION_CODE = "self_update_ready_version_code"

    /** Called by the onprem download service after the APK passed validation. */
    @JvmStatic
    fun record(context: Context, apkPath: String, apkVersionCode: Long) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_READY_PATH, apkPath)
            .putLong(PREF_READY_VERSION_CODE, apkVersionCode)
            .apply()
    }

    /**
     * @return the absolute path of a downloaded, validated update APK that is still installable
     * (file exists, version still strictly newer than the installed app), or null. Stale state is
     * cleared as a side effect.
     */
    @JvmStatic
    fun getReadyApkPath(context: Context): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val path = prefs.getString(PREF_READY_PATH, null) ?: return null
        val readyVersionCode = prefs.getLong(PREF_READY_VERSION_CODE, 0L)

        if (!File(path).exists()) {
            clear(context)
            return null
        }
        val installedVersionCode = try {
            PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0),
            )
        } catch (e: Exception) {
            logger.error("Could not determine installed version code", e)
            return null
        }
        if (readyVersionCode <= installedVersionCode) {
            // Already installed (or stale) — self-heal.
            clear(context)
            return null
        }
        return path
    }

    @JvmStatic
    fun clear(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(PREF_READY_PATH)
            .remove(PREF_READY_VERSION_CODE)
            .apply()
    }
}
