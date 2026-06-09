package ch.threema.app.availabilitystatus

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.core.os.bundleOf
import ch.threema.app.BuildConfig
import ch.threema.app.R
import ch.threema.app.compose.common.SpacerVertical
import ch.threema.app.compose.common.ThemedText
import ch.threema.app.compose.common.extensions.get
import ch.threema.app.compose.theme.ThreemaTheme
import ch.threema.app.compose.theme.ThreemaThemePreview
import ch.threema.app.compose.theme.dimens.GridUnit
import ch.threema.data.datatypes.AvailabilityStatus
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ViewFullAvailabilityStatusBottomSheetDialog : BottomSheetDialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.AVAILABILITY_STATUS_ENABLED) {
            "Can not show this bottom sheet as the current build does not support this feature in general."
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.behavior.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
            isDraggable = true
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val availabilityStatus: AvailabilityStatus? = arguments
            ?.getString(ARGUMENT_AVAILABILITY_STATUS)
            ?.let(AvailabilityStatus::fromJson)

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ThreemaTheme {
                    ViewFullStatusBottomSheetContent(availabilityStatus)
                }
            }
        }
    }

    companion object {

        private const val ARGUMENT_AVAILABILITY_STATUS = "availability-status"

        @JvmStatic
        fun newInstance(availabilityStatus: AvailabilityStatus): ViewFullAvailabilityStatusBottomSheetDialog =
            ViewFullAvailabilityStatusBottomSheetDialog().apply {
                arguments = bundleOf(
                    ARGUMENT_AVAILABILITY_STATUS to availabilityStatus.toJson(),
                )
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewFullStatusBottomSheetContent(
    availabilityStatus: AvailabilityStatus?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = GridUnit.x2,
            )
            .verticalScroll(
                state = rememberScrollState(),
            ),
    ) {
        BottomSheetDefaults.DragHandle(
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        SpacerVertical(GridUnit.x1)

        ThemedText(
            text = stringResource(R.string.view_full_availability_status_modal_title),
            style = MaterialTheme.typography.titleLarge,
        )

        SpacerVertical(GridUnit.x2)

        ThemedText(
            text = availabilityStatus?.displayText()?.get() ?: "-",
            style = MaterialTheme.typography.bodyMedium,
        )

        SpacerVertical(GridUnit.x2)
    }
}

private class PreviewProviderViewFullStatusBottomSheetContent : PreviewParameterProvider<AvailabilityStatus?> {

    override val values: Sequence<AvailabilityStatus?> = sequenceOf(
        AvailabilityStatus.None,
        AvailabilityStatus.Busy(),
        AvailabilityStatus.Busy(
            description = "In a short coffee break",
        ),
        AvailabilityStatus.Busy(
            description = "I can't keep my status description short because I am a person that likes to talk a lot.",
        ),
        AvailabilityStatus.Unavailable(),
        AvailabilityStatus.Unavailable(
            description = "Free day today",
        ),
        AvailabilityStatus.Unavailable(
            description = "I am on vacation and want to base jump mount everest. Hope to see you all when I make it back.",
        ),
        null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@PreviewLightDark
@Composable
private fun Preview_ViewFullStatusBottomSheetContent(
    @PreviewParameter(PreviewProviderViewFullStatusBottomSheetContent::class)
    availabilityStatus: AvailabilityStatus?,
) {
    ThreemaThemePreview {
        Surface {
            ViewFullStatusBottomSheetContent(availabilityStatus)
        }
    }
}
