package ch.threema.app.problemsolving

import android.content.Context
import ch.threema.app.logging.DebugLogHelper
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.utils.ConfigUtils
import ch.threema.app.utils.DispatcherProvider
import ch.threema.app.utils.OemAutostartUtil
import ch.threema.app.utils.PowermanagerUtil
import ch.threema.app.webclient.services.SessionService
import ch.threema.base.SessionScoped
import ch.threema.common.TimeProvider
import ch.threema.common.minus
import ch.threema.localcrypto.MasterKeyManager
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.withContext

/** How long a dismissable problem stays hidden before it re-surfaces. */
private val PROBLEM_RESURFACE_INTERVAL = 30.days

@SessionScoped
class GetProblemsUseCase(
    private val appContext: Context,
    private val sessionService: SessionService,
    private val preferenceService: PreferenceService,
    private val debugLogHelper: DebugLogHelper,
    private val timeProvider: TimeProvider,
    private val masterKeyManager: MasterKeyManager,
    private val dispatcherProvider: DispatcherProvider,
) {
    suspend fun call(): List<Problem> = withContext(dispatcherProvider.worker) {
        buildList {
            if (ConfigUtils.isBackgroundRestricted(appContext)) {
                add(Problem.BACKGROUND_USAGE_RESTRICTED)
            }
            if (ConfigUtils.isBackgroundDataRestricted(appContext)) {
                add(Problem.BACKGROUND_DATA_RESTRICTED)
            }
            if (ConfigUtils.isNotificationsDisabled(appContext)) {
                add(Problem.NOTIFICATIONS_DISABLED)
            }
            if (ConfigUtils.isFullScreenNotificationsDisabled(appContext)) {
                add(Problem.FULLSCREEN_NOTIFICATIONS_DISABLED)
            }
            if (!PowermanagerUtil.isIgnoringBatteryOptimizations(appContext)) {
                if (preferenceService.useThreemaPush()) {
                    add(Problem.THREEMA_PUSH_BATTERY_OPTIMIZATION)
                }
                if (sessionService.hasRunningSessions()) {
                    add(Problem.WEBCLIENT_BATTERY_OPTIMIZATION)
                }
                if (masterKeyManager.awaitIsProtectedWithRemoteSecret()) {
                    add(Problem.REMOTE_SECRET_BATTERY_OPTIMIZATION)
                }
            }
            preferenceService.getDebugLogEnabledTimestamp()?.let { enabledSince ->
                if (timeProvider.get() - enabledSince > 30.days) {
                    add(Problem.DEBUG_LOG_STILL_ENABLED)
                }
            }
            if (debugLogHelper.isDebugLogFileLoggingForceEnabled()) {
                add(Problem.DEBUG_LOG_FORCE_ENABLED)
            }
            // F1Whisper: on Doze-hostile OEMs, the manufacturer's separate "App launch" / auto-start
            // whitelist freezes the F1Push background socket even when AOSP battery optimization is
            // already disabled. Surface OEM-layer guidance whenever F1Push is the delivery path,
            // regardless of the battery-optimization state (it is orthogonal). Not auto-detectable,
            // so it is dismissable with a timed re-surface (see the filter below).
            if (preferenceService.useThreemaPush() && OemAutostartUtil.isKnownAggressiveOem()) {
                add(Problem.OEM_AUTOSTART_RESTRICTED)
            }
        }
            .filter { problem ->
                val dismissKey = problem.dismissKey ?: return@filter true
                val dismissedAt = preferenceService.getProblemDismissed(dismissKey) ?: return@filter true
                // Dismissable problem: re-surface once the dismissal is older than the interval, so a
                // user who dismissed without fixing (or whose OEM update reset the setting) is nudged
                // again, while a genuine fix means only a harmless periodic one-tap dismissal.
                timeProvider.get() - dismissedAt > PROBLEM_RESURFACE_INTERVAL
            }
            .distinctBy { problem ->
                // Some problems have the same cause, so it's enough to only show one of them
                when (problem) {
                    Problem.WEBCLIENT_BATTERY_OPTIMIZATION -> Problem.THREEMA_PUSH_BATTERY_OPTIMIZATION
                    else -> problem
                }
            }
    }
}
