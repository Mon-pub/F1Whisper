package ch.threema.app.problemsolving

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import ch.threema.android.ResolvableString
import ch.threema.android.ResourceIdString
import ch.threema.app.R
import ch.threema.app.logging.DebugLogHelper.Companion.FORCE_ENABLE_FILE_NAME

/**
 * F1Whisper: ALL problems are NON-dismissable. F1Whisper delivers every background notification
 * solely via F1Push (a persistent foreground service), so any of these conditions (battery usage
 * optimized, full-screen notifications off, background usage/data restricted, debug log left on)
 * can silently break notification delivery while the app is in the background. Users who dismissed
 * a warning then forgot and complained notifications "don't work" -> we no longer set a
 * {@code dismissKey} on any problem, so the warning stays visible until the underlying issue is
 * actually fixed, at which point it auto-clears (the problem list is recomputed each time).
 *
 * The {@code dismissKey} parameter is kept (always null now) because {@code GetProblemsUseCase} and
 * {@code ProblemSolverActivity} still read it: a null key both keeps the problem from ever being
 * filtered out by a past dismissal and hides the per-problem dismiss button.
 *
 * @param dismissKey Always null in F1Whisper (no problem is dismissable). The key would otherwise
 * persist a user's dismissal decision.
 * @param solutionType The type of action the user can take to solve the problem, or null if it is not a solveable problem but a problem the user
 * needs to be made aware of.
 */
@Immutable
enum class Problem(
    @StringRes
    val titleRes: Int,
    val explanation: ResolvableString,
    val dismissKey: String? = null,
    val solutionType: SolutionType? = SolutionType.ToSettings,
) {
    BACKGROUND_USAGE_RESTRICTED(
        titleRes = R.string.problemsolver_title_background,
        explanation = ResourceIdString(R.string.problemsolver_explain_background),
    ),
    BACKGROUND_DATA_RESTRICTED(
        titleRes = R.string.problemsolver_title_background_data,
        explanation = ResourceIdString(R.string.problemsolver_explain_background_data),
    ),
    DEBUG_LOG_FORCE_ENABLED(
        titleRes = R.string.problemsolver_title_debug_log_enabled,
        explanation = ResolvableString { context ->
            context.getString(R.string.problemsolver_explain_debug_log_force_enabled, FORCE_ENABLE_FILE_NAME)
        },
        solutionType = null,
    ),
    DEBUG_LOG_STILL_ENABLED(
        titleRes = R.string.problemsolver_title_debug_log_enabled,
        explanation = ResourceIdString(R.string.problemsolver_explain_debug_log_enabled),
        solutionType = SolutionType.InstantAction(R.string.disable),
    ),
    NOTIFICATIONS_DISABLED(
        titleRes = R.string.problemsolver_title_notifications,
        explanation = ResourceIdString(R.string.problemsolver_explain_notifications),
    ),
    FULLSCREEN_NOTIFICATIONS_DISABLED(
        titleRes = R.string.problemsolver_title_fullscreen_notifications,
        explanation = ResourceIdString(R.string.problemsolver_explain_fullscreen_notifications),
    ),
    THREEMA_PUSH_BATTERY_OPTIMIZATION(
        titleRes = R.string.problemsolver_title_app_battery_usgae_optimized,
        explanation = ResourceIdString(R.string.problemsolver_explain_app_battery_usgae_optimized),
    ),
    WEBCLIENT_BATTERY_OPTIMIZATION(
        titleRes = R.string.problemsolver_title_app_battery_usgae_optimized,
        explanation = { context ->
            context.getString(
                R.string.battery_optimizations_explain,
                context.getString(R.string.webclient),
                context.getString(R.string.app_name),
            )
        },
    ),
    REMOTE_SECRET_BATTERY_OPTIMIZATION(
        titleRes = R.string.problemsolver_title_app_battery_usgae_optimized,
        explanation = { context ->
            context.getString(
                R.string.battery_optimizations_explain,
                context.getString(R.string.remote_secret),
                context.getString(R.string.app_name),
            )
        },
    ),
}
