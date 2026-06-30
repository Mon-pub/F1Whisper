package ch.threema.app.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import ch.threema.base.utils.getThreemaLogger
import java.util.Locale

private val logger = getThreemaLogger("OemAutostartUtil")

/**
 * F1Whisper: guidance for Doze-hostile OEMs that aggressively kill background apps.
 *
 * F1Whisper is GMS-free and delivers background notifications via a persistent CSP socket (F1Push).
 * Several manufacturers (HONOR/Huawei, Xiaomi, Oppo, Vivo, Samsung, OnePlus, ...) maintain a
 * separate "App launch" / "Auto-start" / "Performance mode" whitelist that is ORTHOGONAL to
 * Android's battery-optimization (Doze) exemption. Granting the AOSP battery exemption does NOT
 * whitelist the app in that OEM layer, so the background socket is still frozen and notifications
 * are missed (confirmed in a HONOR MTN-NX1 report where battery optimization was already disabled).
 *
 * There is intentionally NO hardcoded vendor ComponentName intent here. The gold-standard FOSS apps
 * (FairEmail, Signal, Conversations, K-9) all converge on NOT hardcoding OEM intents: they are
 * renamed across firmware versions and, on the exact OEMs that matter most, are blocked at the
 * system level — HONOR's `com.hihonor.systemmanager` requires the signature-level `START_MODULE_UI`
 * permission (guaranteed SecurityException for a third-party app on MagicOS), and Huawei/Oppo throw
 * SecurityException without vendor permissions. Instead we open the authoritative, maintained
 * per-OEM instructions on dontkillmyapp.com in a browser (crash-proof), exactly like FairEmail.
 */
object OemAutostartUtil {
    /**
     * Manufacturers known to aggressively kill background apps (lowercased [android.os.Build.MANUFACTURER]).
     * Stock-clean OEMs (google/pixel, motorola, sony, nokia/hmd, fairphone) are excluded so the
     * warning never shows a false positive on devices that handle background processes correctly.
     */
    private val AGGRESSIVE_OEMS: Set<String> = setOf(
        "xiaomi", "redmi", "poco",
        "huawei", "honor",
        "oppo", "realme",
        "vivo", "iqoo",
        "samsung", "oneplus", "meizu", "asus",
        "lenovo", "tecno", "infinix",
    )

    /**
     * Maps a manufacturer to a VERIFIED dontkillmyapp.com slug (each fetched live). HONOR has no
     * dedicated /honor page (404) and forked Huawei's EMUI, so its "App launch" steps are identical
     * to Huawei's -> map honor to huawei. Manufacturers without a confirmed dedicated page fall back
     * to the dontkillmyapp.com homepage (a device picker), which never 404s.
     */
    private val DKMA_SLUG: Map<String, String> = mapOf(
        "xiaomi" to "xiaomi",
        "redmi" to "xiaomi",
        "poco" to "xiaomi",
        "huawei" to "huawei",
        "honor" to "huawei",
        "oppo" to "oppo",
        "realme" to "realme",
        "vivo" to "vivo",
        "iqoo" to "vivo",
        "samsung" to "samsung",
        "oneplus" to "oneplus",
    )

    private const val DKMA_BASE = "https://dontkillmyapp.com/"

    private fun manufacturer(): String =
        android.os.Build.MANUFACTURER.lowercase(Locale.ROOT).trim()

    @JvmStatic
    fun isKnownAggressiveOem(): Boolean = manufacturer() in AGGRESSIVE_OEMS

    /**
     * The display manufacturer name for the warning text (e.g. "HONOR", "Xiaomi"), as reported by
     * the device. Falls back to the raw value; never empty.
     */
    @JvmStatic
    fun manufacturerDisplayName(): String =
        android.os.Build.MANUFACTURER?.takeIf { it.isNotBlank() } ?: "This device"

    /**
     * Opens the per-OEM background-restriction guide on dontkillmyapp.com in a browser. Crash-proof:
     * falls back to this app's details settings, then to the global Settings, if no browser exists.
     */
    @JvmStatic
    fun openOemGuide(context: Context) {
        val slug = DKMA_SLUG[manufacturer()] ?: ""
        val url = DKMA_BASE + slug
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (e: ActivityNotFoundException) {
            logger.warn("No browser to open OEM guide ({}); falling back to app details", url, e)
            openAppDetails(context)
        }
    }

    private fun openAppDetails(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (e: ActivityNotFoundException) {
            logger.warn("App details settings unavailable; falling back to global settings", e)
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                )
            } catch (e2: ActivityNotFoundException) {
                logger.error("No settings activity available", e2)
            }
        }
    }
}
