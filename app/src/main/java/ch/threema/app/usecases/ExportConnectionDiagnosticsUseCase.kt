package ch.threema.app.usecases

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import ch.threema.app.BuildConfig
import ch.threema.app.BuildFlavor
import ch.threema.app.R
import ch.threema.app.diagnostics.ConnectivityProbeReportWriter
import ch.threema.app.diagnostics.ProbeReport
import ch.threema.app.ThreemaApplication
import ch.threema.app.files.AppDirectoryProvider
import ch.threema.app.notifications.NotificationChannels
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.push.PushService
import ch.threema.app.utils.ConfigUtils
import ch.threema.app.utils.DispatcherProvider
import ch.threema.app.utils.PowermanagerUtil
import ch.threema.app.utils.PushUtil
import ch.threema.base.utils.getThreemaLogger
import ch.threema.common.TimeProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.withContext

private val logger = getThreemaLogger("ExportConnectionDiagnosticsUseCase")

/**
 * F1Whisper: gather a one-tap snapshot of the signals that explain "I get no notifications until I
 * open the app" reports (connection state, push mode, notification channel / permission / OS DND,
 * battery-optimization exemption, work-profile isolation), write it to a single
 * `connection_diagnostic.log`, and zip it exactly like the debug-log export so it can be shared.
 *
 * The raw debug log shows whether the socket connects / messages arrive; this report adds the
 * OS-side facts the log cannot see (notifications disabled / channel silenced / OS Do-Not-Disturb /
 * battery-killed / running under a managed work profile such as Shelter). Contains no message
 * contents, names, or other personal data.
 */
class ExportConnectionDiagnosticsUseCase(
    private val appContext: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val sharedPreferences: SharedPreferences,
    private val preferenceService: PreferenceService,
    private val timeProvider: TimeProvider,
) {
    @Throws(IOException::class, SecurityException::class)
    suspend fun call(probeReport: ProbeReport? = null): File = withContext(dispatcherProvider.io) {
        // The passive report is a fixed set of short key/value lines (no message contents, no loops),
        // so it is inherently tiny. When a [probeReport] is supplied (the connectivity troubleshooter),
        // append the ACTIVE network-probe section so the shared log carries BOTH the passive OS /
        // notification / battery snapshot AND the DNS / TLS-SNI / port censorship probes in one file —
        // exactly what a post-registration "no messages / can't connect" (censorship) report needs.
        // The overall cap is pure belt-and-suspenders against any pathological field.
        val report = buildString {
            append(buildReport())
            probeReport?.let {
                appendLine()
                appendLine()
                append(ConnectivityProbeReportWriter.render(it))
            }
        }.let {
            if (it.length > MAX_REPORT_CHARS) it.take(MAX_REPORT_CHARS) + "\n[report truncated]" else it
        }
        val zipFile = File(AppDirectoryProvider(appContext).cacheDirectory, ZIP_FILE_NAME)
        if (zipFile.exists() && !zipFile.delete()) {
            logger.error("Failed to delete existing diagnostics zip")
        }
        // Plain unencrypted zip (same openable result as the debug-log export). NOTE: the shared
        // FileHandlingZipOutputStream helpers force AES encryption and require a password, so they
        // cannot be used here for a recipient-openable file.
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            zos.putNextEntry(ZipEntry(LOG_FILE_NAME))
            zos.write(report.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        zipFile
    }

    private fun buildReport(): String = buildString {
        appendLine("# F1Whisper connection & notification diagnostics")
        kv("created") { timeProvider.get() }

        section("device") {
            kv("android version") { "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})" }
            kv("manufacturer") { Build.MANUFACTURER }
            kv("model") { Build.MODEL }
            kv("managed work profile (e.g. Shelter)") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    appContext.getSystemService<UserManager>()?.isManagedProfile ?: "n/a"
                } else {
                    "n/a (<API 30)"
                }
            }
        }

        section("app") {
            kv("app version") { BuildConfig.VERSION_NAME }
            kv("app version code") {
                appContext.packageManager
                    .getPackageInfo(appContext.packageName, 0)
                    .let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong() }
            }
            kv("build flavor") { BuildFlavor.current.fullDisplayName }
        }

        section("connection") {
            kv("csp state") { ThreemaApplication.requireServiceManager().connection.connectionState }
            kv("has identity") { ThreemaApplication.requireServiceManager().userService.hasIdentity() }
            kv("uses multi device") { ThreemaApplication.requireServiceManager().multiDeviceManager.isMultiDeviceActive }
        }

        section("push") {
            kv("uses threema/F1 push (polling)") { ConfigUtils.useThreemaPush(sharedPreferences, appContext) }
            kv("fcm push enabled") { PushUtil.isPushEnabled(appContext) }
            kv("push services installed") { PushService.servicesInstalled(appContext) }
            kv("stored push token present") { !preferenceService.getPushToken().isNullOrEmpty() }
            kv("push token last sent") {
                val ts = sharedPreferences.getLong(appContext.getString(R.string.preferences__token_sent_date), 0L)
                if (ts <= 0L) "never" else Date(ts)
            }
        }

        section("notifications") {
            kv("notifications enabled (system)") {
                NotificationManagerCompat.from(appContext).areNotificationsEnabled()
            }
            kv("POST_NOTIFICATIONS granted") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                } else {
                    "n/a (<API 33)"
                }
            }
            val nm = appContext.getSystemService<NotificationManager>()
            kv("chats channel importance") { channelImportance(nm, NotificationChannels.NOTIFICATION_CHANNEL_CHATS_DEFAULT) }
            kv("group chats channel importance") { channelImportance(nm, NotificationChannels.NOTIFICATION_CHANNEL_GROUP_CHATS_DEFAULT) }
            kv("os do-not-disturb filter") { interruptionFilter(nm) }
        }

        section("battery") {
            kv("ignoring battery optimizations") { PowermanagerUtil.isIgnoringBatteryOptimizations(appContext) }
        }
    }

    private fun channelImportance(nm: NotificationManager?, channelId: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return "n/a (<API 26)"
        }
        val channel = nm?.getNotificationChannel(channelId) ?: return "channel not found"
        return "${importanceName(channel.importance)} (${channel.importance})"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        NotificationManager.IMPORTANCE_NONE -> "NONE (blocked)"
        NotificationManager.IMPORTANCE_MIN -> "MIN"
        NotificationManager.IMPORTANCE_LOW -> "LOW (silent)"
        NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT"
        NotificationManager.IMPORTANCE_HIGH -> "HIGH"
        NotificationManager.IMPORTANCE_MAX -> "MAX"
        else -> "UNSPECIFIED"
    }

    private fun interruptionFilter(nm: NotificationManager?): String = when (nm?.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "ALL (off)"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "NONE (total silence)"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS only"
        else -> "unknown"
    }

    /**
     * Append a `key:\tvalue` line, capturing the value lazily so any single failing probe degrades
     * to `n/a (...)` instead of aborting the whole report.
     */
    private fun StringBuilder.kv(key: String, value: () -> Any?) {
        val rendered = try {
            value()?.toString() ?: "null"
        } catch (e: Throwable) {
            "n/a (${e.javaClass.simpleName})"
        }
        // Hard-cap any single value so one unexpectedly long probe result can never bloat the report.
        val capped = if (rendered.length > MAX_VALUE_CHARS) rendered.take(MAX_VALUE_CHARS) + "…" else rendered
        appendLine("$key:\t$capped")
    }

    private fun StringBuilder.section(title: String, block: StringBuilder.() -> Unit) {
        appendLine()
        appendLine("# $title")
        block()
    }

    companion object {
        private const val ZIP_FILE_NAME = "connection_diagnostics.zip"
        private const val LOG_FILE_NAME = "connection_diagnostic.log"

        // Generous caps; the real report is well under 2 KB. Purely defensive against overflow.
        private const val MAX_VALUE_CHARS = 512
        private const val MAX_REPORT_CHARS = 64 * 1024
    }
}
