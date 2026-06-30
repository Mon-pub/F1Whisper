package ch.threema.app.problemsolving

import androidx.annotation.StringRes

sealed class SolutionType {
    /**
     * For problems that require the user to make changes on a separate setting screen.
     */
    data object ToSettings : SolutionType()

    /**
     * For problems that can be instantly resolved by a single button press.
     */
    data class InstantAction(@StringRes val label: Int) : SolutionType()

    /**
     * F1Whisper: for problems whose resolution lives behind an external, OEM-specific guide that the
     * app cannot open directly (e.g. manufacturer "App launch" / auto-start whitelists). The button
     * opens a maintained instructions page in a browser.
     */
    data class ToExternalGuide(@StringRes val label: Int) : SolutionType()
}
