package ch.threema.app.ui

import android.app.Application
import androidx.preference.PreferenceManager
import ch.threema.base.utils.getThreemaLogger
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

private val logger = getThreemaLogger("DynamicColorsHelper")

object DynamicColorsHelper {
    private const val PREF_KEY_DYNAMIC_COLOR = "pref_dynamic_color"

    @JvmStatic
    fun applyDynamicColorsIfEnabled(application: Application) {
        if (!DynamicColors.isDynamicColorAvailable()) {
            logger.info("Dynamic color not available, skipping")
            return
        }
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
            ?: return
        // F1Whisper: dynamic color is ON by default (fallback true) so a fresh install on
        // Android 12+ adopts the system palette before setDefaultValues persists the pref.
        if (sharedPreferences.getBoolean(PREF_KEY_DYNAMIC_COLOR, true)) {
            val dynamicColorsOptions = DynamicColorsOptions.Builder()
                .setPrecondition { _, _ ->
                    sharedPreferences.getBoolean(PREF_KEY_DYNAMIC_COLOR, true)
                }
                .build()
            DynamicColors.applyToActivitiesIfAvailable(application, dynamicColorsOptions)
        }
    }
}
