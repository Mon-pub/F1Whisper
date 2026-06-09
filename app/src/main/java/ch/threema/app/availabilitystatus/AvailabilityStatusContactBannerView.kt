package ch.threema.app.availabilitystatus

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.threema.app.R
import ch.threema.app.compose.common.SpacerHorizontal
import ch.threema.app.compose.common.extensions.get
import ch.threema.app.compose.common.text.conversation.ConversationText
import ch.threema.app.compose.common.text.conversation.EmojiSettings
import ch.threema.app.compose.common.text.conversation.MentionFeature
import ch.threema.app.compose.theme.ThreemaTheme
import ch.threema.app.compose.theme.dimens.GridUnit
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.preference.service.PreferenceService.EmojiStyle
import ch.threema.data.datatypes.AvailabilityStatus
import kotlinx.coroutines.flow.MutableStateFlow

class AvailabilityStatusContactBannerView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {

    private val state = MutableStateFlow(
        AvailabilityStatusContactBannerState(
            availabilityStatusSet = null,
            emojiStyle = PreferenceService.EMOJI_STYLE_ANDROID,
            onClickOnOverflowListener = null,
        ),
    )

    fun setState(
        availabilityStatusSet: AvailabilityStatus.Set?,
        @EmojiStyle emojiStyle: Int,
        onClickOnOverflowListener: (() -> Unit)?,
    ) {
        state.value = AvailabilityStatusContactBannerState(
            availabilityStatusSet = availabilityStatusSet,
            emojiStyle = emojiStyle,
            onClickOnOverflowListener = onClickOnOverflowListener,
        )
    }

    @Composable
    override fun Content() {
        val state: AvailabilityStatusContactBannerState by state.collectAsStateWithLifecycle()
        state.availabilityStatusSet?.let {
            ThreemaTheme {
                AvailabilityStatusContactBanner(
                    state = state,
                )
            }
        }
    }
}

private data class AvailabilityStatusContactBannerState(
    val availabilityStatusSet: AvailabilityStatus.Set?,
    @EmojiStyle val emojiStyle: Int,
    val onClickOnOverflowListener: (() -> Unit)?,
)

@Composable
private fun AvailabilityStatusContactBanner(
    modifier: Modifier = Modifier,
    state: AvailabilityStatusContactBannerState,
) {
    state.availabilityStatusSet ?: return
    var hasVisualOverflow: Boolean by remember(state.availabilityStatusSet, state.emojiStyle) {
        mutableStateOf(false)
    }
    Row(
        modifier = modifier
            .padding(
                top = GridUnit.x1,
                bottom = dimensionResource(R.dimen.notice_views_vertical_margin),
                start = GridUnit.x1,
                end = GridUnit.x1,
            )
            .fillMaxWidth()
            .clip(
                shape = RoundedCornerShape(
                    size = dimensionResource(R.dimen.cardview_border_radius),
                ),
            )
            .background(
                color = state.availabilityStatusSet.containerColor(),
            )
            .clickable(
                enabled = hasVisualOverflow && state.onClickOnOverflowListener != null,
                onClick = {
                    state.onClickOnOverflowListener?.invoke()
                },
                onClickLabel = stringResource(R.string.accessibility_view_full_availability_status),
                role = Role.Button,
            )
            .padding(
                vertical = GridUnit.x1,
                horizontal = GridUnit.x2,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConversationText(
            modifier = Modifier.weight(1f),
            rawInput = state.availabilityStatusSet.displayText().get(),
            textStyle = MaterialTheme.typography.bodyMedium,
            color = state.availabilityStatusSet.onContainerColor(),
            maxLines = 2,
            textAlign = TextAlign.Center,
            onTextLaidOut = { updatedHasVisualOverflow ->
                hasVisualOverflow = updatedHasVisualOverflow
            },
            emojiSettings = EmojiSettings(
                style = state.emojiStyle,
            ),
            mentionFeature = MentionFeature.Off,
            markupEnabled = false,
        )

        if (hasVisualOverflow) {
            SpacerHorizontal(GridUnit.x2)
            Icon(
                modifier = Modifier
                    .size(24.dp)
                    .clearAndSetSemantics { },
                painter = painterResource(R.drawable.ic_info_rounded),
                contentDescription = null,
                tint = state.availabilityStatusSet.onContainerColor(),
            )
        }
    }
}
