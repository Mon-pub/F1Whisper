package ch.threema.app.availabilitystatus

import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import ch.threema.android.getLocation
import ch.threema.android.postDelayed
import ch.threema.app.R
import ch.threema.app.ui.TooltipPopup
import kotlin.time.Duration.Companion.seconds

object AvailabilityStatusTooltipPopupManager {
    @JvmStatic
    fun showInConversation(activity: FragmentActivity, anchor: View): TooltipPopup? {
        val tooltipPopup = createTooltipPopup(activity)
            ?: return null
        anchor.postDelayed(1.seconds) {
            if (!anchor.isVisible || !anchor.isAttachedToWindow) {
                return@postDelayed
            }
            val tooltipWidth = activity.resources.getDimensionPixelSize(R.dimen.tooltip_max_width)
            tooltipPopup.show(
                activity = activity,
                anchor = anchor,
                title = activity.getString(R.string.tooltip_title_availability_status),
                text = activity.getString(R.string.tooltip_text_availability_status_in_chat),
                alignment = TooltipPopup.Alignment.BELOW_ANCHOR_ARROW_LEFT,
                originLocation = anchor.getLocation(
                    xOffset = (anchor.width - tooltipWidth) / 2,
                    yOffset = anchor.height,
                ),
            )
        }
        return tooltipPopup
    }

    @JvmStatic
    fun showInProfile(activity: FragmentActivity, anchor: View): TooltipPopup? {
        val tooltipPopup = createTooltipPopup(activity)
            ?: return null
        anchor.postDelayed(1.seconds) {
            if (!anchor.isVisible || !anchor.isAttachedToWindow) {
                return@postDelayed
            }
            tooltipPopup.show(
                activity = activity,
                anchor = anchor,
                title = activity.getString(R.string.tooltip_title_availability_status),
                text = activity.getString(R.string.tooltip_text_availability_status_in_profile),
                alignment = TooltipPopup.Alignment.BELOW_ANCHOR_ARROW_LEFT,
            )
        }
        return tooltipPopup
    }

    private fun createTooltipPopup(activity: FragmentActivity): TooltipPopup? {
        val tooltipPopup = TooltipPopup(
            context = activity,
            preferenceKey = R.string.preferences__tooltip_availability_status_shown,
            lifecycleOwner = activity,
            showArrow = false,
        )
        if (tooltipPopup.isForeverDismissed()) {
            return null
        }
        tooltipPopup.listener = object : TooltipPopup.TooltipPopupListener() {
            override fun onShown(tooltipPopup: TooltipPopup) {
                tooltipPopup.markAsShown()
            }
        }
        return tooltipPopup
    }
}
