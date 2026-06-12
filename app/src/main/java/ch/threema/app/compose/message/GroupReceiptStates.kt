package ch.threema.app.compose.message

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.threema.app.R
import ch.threema.app.compose.common.ThemedText
import ch.threema.app.compose.common.colorReferenceResource
import ch.threema.app.compose.theme.AppTypography
import ch.threema.app.messagedetails.GroupMemberReceiptUiModel
import ch.threema.storage.models.MessageState

/**
 * F1Whisper: shows, for an outgoing group message, which members have read / received / only been
 * sent the message ("Read by / Delivered to / Sent to"), Signal-style.
 */
@Composable
fun GroupReceiptStatesBox(
    modifier: Modifier = Modifier,
    groupReceipts: List<GroupMemberReceiptUiModel>,
) {
    val readBy = groupReceipts.filter { it.state == MessageState.READ }
    val deliveredTo = groupReceipts.filter { it.state == MessageState.DELIVERED }
    val sentTo = groupReceipts.filter { it.state == null }

    val borderColor = colorReferenceResource(R.attr.colorMessageBubbleSendContainer)
    Column(
        modifier = modifier
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReceiptSection(R.string.message_details_read_by, R.drawable.ic_mark_read, readBy)
        ReceiptSection(R.string.message_details_delivered_to, R.drawable.ic_inbox_filled, deliveredTo)
        ReceiptSection(R.string.message_details_sent_to, R.drawable.ic_mail_filled, sentTo)
    }
}

@Composable
private fun ReceiptSection(
    @StringRes titleRes: Int,
    @DrawableRes iconRes: Int,
    members: List<GroupMemberReceiptUiModel>,
) {
    if (members.isEmpty()) {
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(20.dp),
                painter = painterResource(id = iconRes),
                tint = MaterialTheme.colorScheme.onSurface,
                contentDescription = null,
            )
            ThemedText(
                text = stringResource(titleRes),
                style = AppTypography.labelLarge,
            )
        }
        members.forEach { member ->
            ThemedText(
                modifier = Modifier.padding(start = 28.dp),
                text = member.displayName,
                style = AppTypography.bodyMedium,
            )
        }
    }
}
