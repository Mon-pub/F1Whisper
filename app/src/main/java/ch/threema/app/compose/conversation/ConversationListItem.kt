package ch.threema.app.compose.conversation

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.FloatRange
import androidx.annotation.Px
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.annotation.FrequentlyChangingValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.threema.android.ResolvableString
import ch.threema.android.ResolvedString
import ch.threema.app.R
import ch.threema.app.compose.common.IconInfo
import ch.threema.app.compose.common.LocalDayOfYear
import ch.threema.app.compose.common.buttons.primary.ButtonPrimaryDense
import ch.threema.app.compose.common.immutables.ImmutableBitmap
import ch.threema.app.compose.common.list.swipe.ListItemSwipeContainer
import ch.threema.app.compose.common.list.swipe.ListItemSwipeFeature
import ch.threema.app.compose.common.list.swipe.ListItemSwipeFeatureState
import ch.threema.app.compose.common.spacer.SpacerHorizontal
import ch.threema.app.compose.common.text.ThemedText
import ch.threema.app.compose.common.text.conversation.ConversationText
import ch.threema.app.compose.common.text.conversation.ConversationTextDefaults
import ch.threema.app.compose.common.text.conversation.ConversationTextUtil
import ch.threema.app.compose.common.text.conversation.HighlightFeature
import ch.threema.app.compose.common.text.conversation.MentionFeature
import ch.threema.app.compose.conversation.models.ConversationListItemUiModel
import ch.threema.app.compose.conversation.models.ConversationNameStyle
import ch.threema.app.compose.conversation.models.ConversationUiModel
import ch.threema.app.compose.conversation.models.GroupCallUiModel
import ch.threema.app.compose.conversation.models.INACTIVE_CONTACT_ALPHA
import ch.threema.app.compose.conversation.models.UnreadState
import ch.threema.app.compose.preview.PreviewData
import ch.threema.app.compose.theme.ThreemaPreviewWrapper
import ch.threema.app.compose.theme.color.CustomColors
import ch.threema.app.compose.theme.dimens.GridUnit
import ch.threema.app.conversation.MessageViewElementFactory
import ch.threema.app.preference.service.PreferenceService.Companion.EMOJI_STYLE_ANDROID
import ch.threema.app.preference.service.PreferenceService.EmojiStyle
import ch.threema.app.ui.models.MessageViewElement
import ch.threema.app.usecases.conversations.AvatarIteration
import ch.threema.app.utils.MessageUtil
import ch.threema.app.utils.TextUtil
import ch.threema.app.voip.groupcall.sfu.CallId
import ch.threema.common.emptyByteArray
import ch.threema.common.toHMMSS
import ch.threema.common.withoutLineBreaks
import ch.threema.data.datatypes.AvailabilityStatus
import ch.threema.data.datatypes.ContactConversationId
import ch.threema.data.datatypes.ContactNameFormat
import ch.threema.data.datatypes.ConversationId
import ch.threema.data.datatypes.DistributionListConversationId
import ch.threema.data.datatypes.GroupConversationId
import ch.threema.data.datatypes.LocalGroupId
import ch.threema.domain.types.Identity
import java.time.Instant
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val MESSAGE_BODY_PREVIEW_MAX_LENGTH = 100

private val listItemShape: Shape = RoundedCornerShape(size = GridUnit.x1)

@Composable
fun ConversationListItem(
    modifier: Modifier = Modifier,
    conversationListItemUiModel: ConversationListItemUiModel,
    avatarIteration: AvatarIteration,
    localDayOfYear: LocalDayOfYear,
    avatarBitmapProvider: suspend (ConversationId) -> ImmutableBitmap?,
    ownIdentity: Identity,
    @EmojiStyle emojiStyle: Int,
    contactNameFormat: ContactNameFormat,
    onClick: (ConversationId) -> Unit,
    onLongClick: (ConversationId) -> Unit,
    onClickAvatar: (ConversationId) -> Unit,
    onClickJoinOrOpenGroupCall: (GroupConversationId) -> Unit,
    swipeFeatureStartToEnd: ListItemSwipeFeature.StartToEnd<ConversationId>? = null,
    swipeFeatureEndToStart: ListItemSwipeFeature.EndToStart<ConversationId>? = null,
) {
    val conversationUiModel: ConversationUiModel = conversationListItemUiModel.model

    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback: HapticFeedback = LocalHapticFeedback.current

    val swipeToDismissBoxState: SwipeToDismissBoxState = rememberSwipeToDismissBoxState(
        positionalThreshold = ListItemSwipeFeature.defaultPositionalThreshold,
    )

    val listItemContainerColor: Color =
        if (conversationListItemUiModel.isHighlighted) {
            CustomColors.listItemHighlightedContainer
        } else {
            MaterialTheme.colorScheme.background
        }

    val conversationIcon: IconInfo? = when (conversationUiModel) {
        is ConversationUiModel.ContactConversation -> {
            conversationUiModel.icon?.takeIf {
                conversationUiModel.latestMessageData?.isDeleted == false
            }
        }
        else -> conversationUiModel.icon
    }

    @Px
    var listItemWidth: Int by remember { mutableIntStateOf(0) }

    SwipeToDismissBox(
        modifier = modifier.onGloballyPositioned(
            onGloballyPositioned = { layoutCoordinates ->
                listItemWidth = layoutCoordinates.size.width
            },
        ),
        state = swipeToDismissBoxState,
        gesturesEnabled = swipeFeatureStartToEnd?.state?.enabled == true || swipeFeatureEndToStart?.state?.enabled == true,
        enableDismissFromStartToEnd = swipeFeatureStartToEnd?.state?.enabled == true,
        enableDismissFromEndToStart = swipeFeatureEndToStart?.state?.enabled == true,
        backgroundContent = {
            SwipeToDismissBoxBackgroundContent(
                swipeFeatureStartToEnd = swipeFeatureStartToEnd,
                swipeFeatureEndToStart = swipeFeatureEndToStart,
                swipeToDismissBoxState = swipeToDismissBoxState,
                listItemWidth = listItemWidth,
                containerColorSettled = listItemContainerColor,
            )
        },
        onDismiss = { swipeToDismissBoxValue ->
            when (swipeToDismissBoxValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    coroutineScope.launch {
                        swipeToDismissBoxState.snapTo(SwipeToDismissBoxValue.Settled)
                        swipeFeatureStartToEnd?.hapticFeedback?.let(hapticFeedback::performHapticFeedback)
                        swipeFeatureStartToEnd?.onSwipe(conversationUiModel.conversationId)
                    }
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    coroutineScope.launch {
                        swipeToDismissBoxState.snapTo(SwipeToDismissBoxValue.Settled)
                        swipeFeatureEndToStart?.hapticFeedback?.let(hapticFeedback::performHapticFeedback)
                        swipeFeatureEndToStart?.onSwipe(conversationUiModel.conversationId)
                    }
                }
                SwipeToDismissBoxValue.Settled -> {}
            }
        },
    ) {
        ConversationListItemContent(
            ownIdentity = ownIdentity,
            containerColor = listItemContainerColor,
            avatarContent = {
                AvatarContent(
                    conversationListItemUiModel = conversationListItemUiModel,
                    avatarIteration = avatarIteration,
                    bitmapProvider = avatarBitmapProvider,
                    onClick = {
                        onClickAvatar(conversationUiModel.conversationId)
                    },
                )
            },
            conversationName = conversationUiModel.conversationName,
            conversationNameStyle = conversationUiModel.conversationNameStyle,
            latestMessageData = conversationUiModel.latestMessageData,
            groupMessageSenderName = when (conversationUiModel) {
                is ConversationUiModel.GroupConversation -> conversationUiModel.latestMessageSenderName
                else -> null
            },
            localDayOfYear = localDayOfYear,
            unreadState = conversationUiModel.unreadState,
            isPinned = conversationUiModel.isPinned,
            isPrivate = conversationUiModel.isPrivate,
            groupCall = when (conversationUiModel) {
                is ConversationUiModel.GroupConversation -> conversationUiModel.groupCall
                else -> null
            },
            draftData = conversationUiModel.draftData,
            conversationIcon = conversationIcon,
            muteStatusIcon = conversationUiModel.muteStatusIcon,
            emojiStyle = emojiStyle,
            contactNameFormat = contactNameFormat,
            isTyping = when (conversationUiModel) {
                is ConversationUiModel.ContactConversation -> conversationUiModel.isTyping
                else -> false
            },
            onClick = {
                onClick(conversationUiModel.conversationId)
            },
            onLongClick = {
                onLongClick(conversationUiModel.conversationId)
            },
            onClickJoinOrOpenGroupCall = {
                val conversationId = conversationUiModel.conversationId
                if (conversationId is GroupConversationId) {
                    onClickJoinOrOpenGroupCall(conversationId)
                }
            },
        )
    }
}

/**
 * @param listItemWidth width in pixels
 */
@Composable
private fun SwipeToDismissBoxBackgroundContent(
    swipeFeatureStartToEnd: ListItemSwipeFeature.StartToEnd<ConversationId>?,
    swipeFeatureEndToStart: ListItemSwipeFeature.EndToStart<ConversationId>?,
    swipeToDismissBoxState: SwipeToDismissBoxState,
    listItemWidth: Int,
    containerColorSettled: Color,
) {
    val swipeProgress: Float by remember(listItemWidth) {
        derivedStateOf {
            calculateSwipeProgressRounded(
                swipeToDismissBoxState = swipeToDismissBoxState,
                listItemWidth = listItemWidth,
            )
        }
    }
    when (swipeToDismissBoxState.dismissDirection) {
        SwipeToDismissBoxValue.StartToEnd -> swipeFeatureStartToEnd?.let {
            ListItemSwipeContainer(
                swipeFeature = swipeFeatureStartToEnd,
                containerColorSettled = containerColorSettled,
                shape = listItemShape,
                swipeProgress = swipeProgress,
            )
        }

        SwipeToDismissBoxValue.EndToStart -> swipeFeatureEndToStart?.let {
            ListItemSwipeContainer(
                swipeFeature = swipeFeatureEndToStart,
                containerColorSettled = containerColorSettled,
                shape = listItemShape,
                swipeProgress = swipeProgress,
            )
        }

        SwipeToDismissBoxValue.Settled -> {}
    }
}

/**
 *  Calculates the scroll progress from `0.0` (inclusive) to `1.0` (inclusive) depending on the anchor draggable offset pixels and the given
 *  [listItemWidth]. Use the result of this calculation inside a [derivedStateOf] block to benefit from the rounding optimization.
 *
 *  @param listItemWidth width in pixels
 *
 *  @throws IllegalArgumentException if [listItemWidth] is negative
 *
 *  @see FrequentlyChangingValue
 *  @see androidx.compose.foundation.gestures.AnchoredDraggableState.offset
 */
@FloatRange(from = 0.0, to = 1.0)
private fun calculateSwipeProgressRounded(
    swipeToDismissBoxState: SwipeToDismissBoxState,
    listItemWidth: Int,
): Float {
    require(listItemWidth >= 0) {
        "Parameter listItemWidth must never be negative"
    }
    if (listItemWidth == 0) {
        return 0f
    }
    @Px val swipeOffset: Float = runCatching { swipeToDismissBoxState.requireOffset().absoluteValue }.getOrNull()
        ?: return 0f
    val swipeProgress: Float = if (swipeOffset > 0f) swipeOffset / listItemWidth else 0f
    return (swipeProgress * 100f).roundToInt() / 100f
}

@Composable
private fun AvatarContent(
    conversationListItemUiModel: ConversationListItemUiModel,
    avatarIteration: AvatarIteration,
    bitmapProvider: suspend (ConversationId) -> ImmutableBitmap?,
    onClick: () -> Unit,
) {
    val conversationUiModel = conversationListItemUiModel.model
    AvatarAsyncCheckable(
        conversationId = conversationUiModel.conversationId,
        avatarIteration = avatarIteration,
        bitmapProvider = bitmapProvider,
        contentDescription = conversationUiModel.receiverDisplayName?.let {
            getAvatarContentDescription(
                context = LocalContext.current,
                conversationId = conversationUiModel.conversationId,
                receiverDisplayName = conversationUiModel.receiverDisplayName ?: "",
            )
        },
        fallbackIcon = when (conversationUiModel) {
            is ConversationUiModel.ContactConversation -> R.drawable.ic_contact
            is ConversationUiModel.GroupConversation -> R.drawable.ic_group
            is ConversationUiModel.DistributionListConversation -> R.drawable.ic_distribution_list_avatar
        },
        showWorkBadge = when (conversationUiModel) {
            is ConversationUiModel.ContactConversation -> conversationUiModel.showIdentityTypeBadge
            else -> false
        },
        availabilityStatus = when (conversationUiModel) {
            is ConversationUiModel.ContactConversation -> conversationUiModel.availabilityStatus
            else -> null
        },
        isChecked = conversationListItemUiModel.isChecked,
        onClick = onClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationListItemContent(
    modifier: Modifier = Modifier,
    containerColor: Color,
    ownIdentity: Identity,
    avatarContent: @Composable () -> Unit,
    conversationName: String,
    conversationNameStyle: ConversationNameStyle,
    latestMessageData: ConversationUiModel.LatestMessageData?,
    groupMessageSenderName: ResolvableString?,
    localDayOfYear: LocalDayOfYear,
    unreadState: UnreadState?,
    isPinned: Boolean,
    isPrivate: Boolean,
    groupCall: GroupCallUiModel?,
    draftData: ConversationUiModel.DraftData?,
    conversationIcon: IconInfo?,
    muteStatusIcon: Int?,
    @EmojiStyle emojiStyle: Int,
    contactNameFormat: ContactNameFormat,
    isTyping: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickJoinOrOpenGroupCall: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(
                shape = listItemShape,
            )
            .background(
                color = containerColor,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(vertical = GridUnit.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpacerHorizontal(GridUnit.x2)
        avatarContent()
        SpacerHorizontal(GridUnit.x1_5)
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                FirstLine(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(
                            min = dimensionResource(R.dimen.listitem_min_height_first_line),
                        ),
                    conversationName = conversationName,
                    conversationNameStyle = conversationNameStyle,
                    isPinned = isPinned,
                    isPrivate = isPrivate,
                    unreadState = unreadState,
                    groupCall = groupCall,
                    muteStatusIcon = muteStatusIcon,
                    emojiStyle = emojiStyle,
                )
                SecondLine(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(
                            min = 24.dp,
                        ),
                    ownIdentity = ownIdentity,
                    latestMessageData = latestMessageData,
                    icon = conversationIcon,
                    groupMessageSenderName = groupMessageSenderName,
                    localDayOfYear = localDayOfYear,
                    isPrivate = isPrivate,
                    groupCall = groupCall,
                    unreadState = unreadState,
                    draftData = draftData,
                    emojiStyle = emojiStyle,
                    contactNameFormat = contactNameFormat,
                    isTyping = isTyping,
                )
            }

            if (groupCall != null) {
                ButtonPrimaryDense(
                    modifier = Modifier.padding(horizontal = GridUnit.x1_5),
                    onClick = onClickJoinOrOpenGroupCall,
                    text = stringResource(
                        if (groupCall.isJoined) {
                            R.string.voip_gc_open_call
                        } else {
                            R.string.voip_gc_join_call
                        },
                    ),
                )
            }
        }
        SpacerHorizontal(GridUnit.x2)
    }
}

@Composable
private fun FirstLine(
    modifier: Modifier,
    conversationName: String,
    conversationNameStyle: ConversationNameStyle,
    isPinned: Boolean,
    isPrivate: Boolean,
    unreadState: UnreadState?,
    groupCall: GroupCallUiModel?,
    @DrawableRes muteStatusIcon: Int?,
    @EmojiStyle emojiStyle: Int,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (muteStatusIcon != null && groupCall == null) {
            Icon(
                modifier = Modifier.size(
                    with(LocalDensity.current) {
                        20.sp.toDp()
                    },
                ),
                tint = LocalContentColor.current,
                painter = painterResource(muteStatusIcon),
                contentDescription = null,
            )
            SpacerHorizontal(GridUnit.x0_5)
        }
        ConversationText(
            modifier = Modifier
                .heightIn(
                    min = dimensionResource(R.dimen.listitem_min_height_first_line),
                )
                .weight(1f)
                .alpha(
                    if (conversationNameStyle.dimAlpha) INACTIVE_CONTACT_ALPHA else 1f,
                ),
            rawInput = conversationName,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (unreadState != null) FontWeight.SemiBold else FontWeight.Normal,
                textDecoration = if (conversationNameStyle.strikethrough) TextDecoration.LineThrough else TextDecoration.None,
            ),
            color = LocalContentColor.current,
            maxLines = 1,
            emojiSettings = ConversationTextDefaults.EmojiSettings.copy(
                style = emojiStyle,
            ),
            mentionFeature = MentionFeature.Off,
            markupEnabled = false,
            highlightFeature = HighlightFeature.Off,
        )
        if (isPinned && groupCall == null) {
            SpacerHorizontal(GridUnit.x0_5)
            Icon(
                modifier = Modifier.size(GridUnit.x2_5),
                painter = painterResource(R.drawable.ic_pin),
                contentDescription = null,
            )
        }
        if (isPrivate && groupCall == null) {
            SpacerHorizontal(GridUnit.x0_5)
            Icon(
                modifier = Modifier.size(GridUnit.x2_5),
                painter = painterResource(R.drawable.ic_lock_filled),
                contentDescription = null,
            )
        }
        if (unreadState != null && groupCall == null) {
            SpacerHorizontal(GridUnit.x0_5)
            UnreadCounter(
                unreadState = unreadState,
            )
        }
    }
}

@Composable
private fun SecondLine(
    modifier: Modifier,
    ownIdentity: Identity,
    latestMessageData: ConversationUiModel.LatestMessageData?,
    icon: IconInfo?,
    groupMessageSenderName: ResolvableString?,
    localDayOfYear: LocalDayOfYear,
    isPrivate: Boolean,
    groupCall: GroupCallUiModel?,
    unreadState: UnreadState?,
    draftData: ConversationUiModel.DraftData?,
    @EmojiStyle emojiStyle: Int,
    contactNameFormat: ContactNameFormat,
    isTyping: Boolean,
) {
    if (isPrivate) {
        SecondLinePrivate(
            modifier = modifier,
            unreadState = unreadState,
        )
    } else if (groupCall != null) {
        SecondLineOngoingGroupCall(
            modifier = modifier,
            groupCall = groupCall,
        )
    } else if (isTyping) {
        SecondLineTyping(
            modifier = modifier,
        )
    } else if (draftData != null) {
        SecondLineDraft(
            modifier = modifier,
            draftData = draftData,
            emojiStyle = emojiStyle,
            ownIdentity = ownIdentity,
        )
    } else if (latestMessageData != null) {
        SecondLineMessage(
            modifier = modifier,
            latestMessageData = latestMessageData,
            icon = icon,
            groupMessageSenderName = groupMessageSenderName,
            localDayOfYear = localDayOfYear,
            unreadState = unreadState,
            ownIdentity = ownIdentity,
            emojiStyle = emojiStyle,
            contactNameFormat = contactNameFormat,
        )
    } else {
        SecondLineEmpty(
            modifier = modifier,
            icon = icon,
        )
    }
}

@Composable
private fun SecondLinePrivate(
    modifier: Modifier,
    unreadState: UnreadState?,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemedText(
            text = stringResource(R.string.private_chat_subject),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (unreadState != null) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = LocalContentColor.current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SecondLineOngoingGroupCall(
    modifier: Modifier,
    groupCall: GroupCallUiModel,
) {
    var callDuration: Duration by remember(
        key1 = groupCall.id,
        key2 = groupCall.startedAt,
        key3 = groupCall.processedAt,
    ) {
        mutableStateOf(groupCall.getCallDurationNow())
    }
    LaunchedEffect(
        key1 = groupCall.id,
        key2 = groupCall.startedAt,
        key3 = groupCall.processedAt,
    ) {
        launch {
            while (isActive) {
                delay(1.seconds)
                callDuration += 1.seconds
            }
        }
    }

    ThemedText(
        modifier = modifier,
        text = buildString {
            append(callDuration.toHMMSS())
            append(" | ")
            append(
                stringResource(
                    id = if (groupCall.isJoined) R.string.voip_gc_in_call else R.string.voip_gc_ongoing_call,
                ),
            )
        },
        color = LocalContentColor.current,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SecondLineDraft(
    modifier: Modifier,
    draftData: ConversationUiModel.DraftData,
    @EmojiStyle emojiStyle: Int,
    ownIdentity: Identity,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemedText(
            text = stringResource(R.string.draft_pattern, stringResource(R.string.draft)),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ConversationText(
            modifier = Modifier.weight(1f),
            rawInput = draftData.draft,
            textStyle = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current,
            maxLines = 1,
            emojiSettings = ConversationTextDefaults.EmojiSettings.copy(
                style = emojiStyle,
            ),
            mentionFeature = MentionFeature.On(
                ownIdentity = ownIdentity,
                identityDisplayNames = draftData.mentionNames,
            ),
            markupEnabled = true,
            highlightFeature = HighlightFeature.Off,
        )
    }
}

@Composable
private fun SecondLineTyping(
    modifier: Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypingIndicator()
    }
}

@Composable
private fun SecondLineMessage(
    modifier: Modifier,
    latestMessageData: ConversationUiModel.LatestMessageData,
    icon: IconInfo?,
    groupMessageSenderName: ResolvableString?,
    localDayOfYear: LocalDayOfYear,
    unreadState: UnreadState?,
    ownIdentity: Identity,
    @EmojiStyle emojiStyle: Int,
    contactNameFormat: ContactNameFormat,
) {
    val context = LocalContext.current
    val messageViewElementFactory = koinInject<MessageViewElementFactory>()
    val latestMessageViewElement: MessageViewElement? = remember(
        latestMessageData.type,
        latestMessageData.body,
        latestMessageData.caption,
        latestMessageData.isOutbox,
        latestMessageData.isDeleted,
        contactNameFormat,
    ) {
        latestMessageData.toMessageViewElement(messageViewElementFactory, contactNameFormat)
    }
    val latestMessagePreview: String = remember(
        key1 = latestMessageViewElement?.text,
        key2 = latestMessageViewElement?.placeholder,
        key3 = latestMessageData.isDeleted,
    ) {
        if (latestMessageData.isDeleted) {
            context.getString(R.string.message_was_deleted)
        } else {
            latestMessageViewElement?.text
                ?.let(::getBodyPreview)
                ?: latestMessageViewElement?.placeholder
                ?: ""
        }
    }

    val latestMessageDisplayDate: String = remember(
        latestMessageData.postedAt,
        latestMessageData.isOutbox,
        latestMessageData.createdAt,
        localDayOfYear,
    ) {
        latestMessageData.getDisplayDate(context)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (groupMessageSenderName != null) {
            val fontWeightGroupSenderName: FontWeight = when {
                unreadState != null -> FontWeight.SemiBold
                else -> FontWeight.Normal
            }
            ConversationText(
                rawInput = groupMessageSenderName.get(context) + ":",
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = fontWeightGroupSenderName,
                ),
                maxLines = 1,
                emojiSettings = ConversationTextDefaults.EmojiSettings.copy(
                    style = emojiStyle,
                ),
                mentionFeature = MentionFeature.Off,
                markupEnabled = false,
                highlightFeature = HighlightFeature.Off,
            )
            SpacerHorizontal(GridUnit.x0_5)
        }

        if (latestMessageViewElement?.icon != null) {
            Icon(
                modifier = Modifier
                    .padding(end = GridUnit.x0_5)
                    .size(
                        with(LocalDensity.current) {
                            16.sp.toDp()
                        },
                    ),
                painter = painterResource(latestMessageViewElement.icon),
                contentDescription = latestMessageViewElement.placeholder,
                tint = latestMessageViewElement.iconTint?.let { colorRes ->
                    colorResource(colorRes)
                } ?: LocalContentColor.current,
            )
        }

        val fontWeightMessagePreview: FontWeight =
            when {
                latestMessageData.isDeleted -> FontWeight.Normal
                unreadState != null -> FontWeight.SemiBold
                else -> FontWeight.Normal
            }
        val fontStyleMessagePreview: FontStyle =
            if (latestMessageData.isDeleted) {
                FontStyle.Italic
            } else {
                FontStyle.Normal
            }
        val textAlphaMessagePreview: Float =
            if (latestMessageData.isDeleted) {
                0.6f
            } else {
                1.0f
            }
        ConversationText(
            modifier = Modifier.weight(1f),
            rawInput = latestMessagePreview,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = fontWeightMessagePreview,
                fontStyle = fontStyleMessagePreview,
            ),
            color = LocalContentColor.current.copy(
                alpha = textAlphaMessagePreview,
            ),
            maxLines = 1,
            emojiSettings = ConversationTextDefaults.EmojiSettings.copy(
                style = emojiStyle,
            ),
            mentionFeature = MentionFeature.On(
                ownIdentity = ownIdentity,
                identityDisplayNames = latestMessageData.mentionNames,
            ),
            highlightFeature = HighlightFeature.Off,
        )

        SpacerHorizontal(GridUnit.x1)

        ThemedText(
            text = latestMessageDisplayDate,
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (icon != null) {
            SpacerHorizontal(GridUnit.x0_5)
            ConversationIcon(
                icon = icon,
            )
        }
    }
}

@Composable
private fun SecondLineEmpty(
    modifier: Modifier,
    icon: IconInfo?,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
    ) {
        if (icon != null) {
            ConversationIcon(
                icon = icon,
            )
        }
    }
}

@Composable
private fun ConversationIcon(
    modifier: Modifier = Modifier,
    icon: IconInfo,
) {
    Icon(
        modifier = modifier.size(GridUnit.x2_5),
        tint = icon.tintOverride?.let(::Color)
            ?: LocalContentColor.current,
        painter = painterResource(icon.iconRes),
        contentDescription = icon.contentDescription?.let { contentDescriptionRes ->
            stringResource(contentDescriptionRes)
        },
    )
}

private fun getAvatarContentDescription(
    context: Context,
    conversationId: ConversationId,
    receiverDisplayName: String,
): String =
    when (conversationId) {
        is ContactConversationId -> context.getString(
            R.string.edit_type_content_description,
            context.getString(R.string.mime_contact),
            receiverDisplayName,
        )

        is GroupConversationId -> context.getString(
            R.string.edit_type_content_description,
            context.getString(R.string.group),
            receiverDisplayName,
        )

        is DistributionListConversationId -> context.getString(
            R.string.edit_type_content_description,
            context.getString(R.string.distribution_list),
            receiverDisplayName,
        )
    }

@Stable
private fun ConversationUiModel.LatestMessageData.toMessageViewElement(
    messageViewElementFactory: MessageViewElementFactory,
    contactNameFormat: ContactNameFormat,
): MessageViewElement? {
    if (isDeleted) {
        return null
    }
    return messageViewElementFactory.getViewElement(
        /* messageType = */
        type,
        /* messageBody = */
        body,
        /* messageCaption = */
        caption,
        /* isOutbox = */
        isOutbox,
        /* contactNameFormat = */
        contactNameFormat,
    )
}

private fun ConversationUiModel.LatestMessageData.getDisplayDate(context: Context): String =
    MessageUtil.getDisplayDate(
        /* context = */
        context,
        /* postedAt = */
        postedAt,
        /* isOutbox = */
        isOutbox,
        /* createdAt = */
        createdAt,
        /* full = */
        false,
    )

/**
 *  To avoid loading unnecessary emoji assets for message content that does not even fit on the screen horizontally, we only use a part of the full
 *  message body characters.
 *
 *  @param body The complete [ConversationUiModel.LatestMessageData.body]
 *
 *  @return A part of the message body without any line breaks
 *
 *  @see MESSAGE_BODY_PREVIEW_MAX_LENGTH
 */
private fun getBodyPreview(body: String): String {
    val messageBodyWithoutLineBreaks = body.withoutLineBreaks(replaceWith = " ")
    // Truncate to a reasonable preview length
    val truncated: String = ConversationTextUtil.truncateRespectingEmojis(
        text = messageBodyWithoutLineBreaks,
        maxLength = MESSAGE_BODY_PREVIEW_MAX_LENGTH,
    )
    return if (truncated.length < body.length) {
        truncated + TextUtil.ELLIPSIS
    } else {
        truncated
    }
}

private class PreviewProviderContactConversationListItemItemUiModel : PreviewParameterProvider<ConversationListItemUiModel> {

    private companion object {

        fun createContactConversationListItemUiModel(
            receiverDisplayName: String = "Contact Name",
            conversationName: String = "Contact Name",
            conversationNameStyle: ConversationNameStyle = ConversationNameStyle(),
            draftData: ConversationUiModel.DraftData? = null,
            latestMessageData: ConversationUiModel.LatestMessageData? = PreviewData.LatestMessageData.incomingTextMessage(
                body = "Contact message body",
            ),
            unreadState: UnreadState? = null,
            isPinned: Boolean = false,
            isPrivate: Boolean = false,
            icon: IconInfo? = IconInfo(
                iconRes = R.drawable.ic_reply,
                contentDescription = null,
            ),
            muteStatusIcon: Int? = null,
            isChecked: Boolean = false,
            showWorkBadge: Boolean = false,
            isTyping: Boolean = false,
            isHighlighted: Boolean = false,
        ) = ConversationListItemUiModel(
            model = ConversationUiModel.ContactConversation(
                conversationId = ContactConversationId(
                    identity = PreviewData.IDENTITY_OTHER_1.value,
                ),
                receiverDisplayName = receiverDisplayName,
                conversationName = conversationName,
                conversationNameStyle = conversationNameStyle,
                draftData = draftData,
                latestMessageData = latestMessageData,
                unreadState = unreadState,
                isPinned = isPinned,
                isPrivate = isPrivate,
                icon = icon,
                muteStatusIcon = muteStatusIcon,
                showIdentityTypeBadge = showWorkBadge,
                isTyping = isTyping,
                avatarIteration = AvatarIteration.initial,
                availabilityStatus = AvailabilityStatus.Unavailable(),
            ),
            isChecked = isChecked,
            isHighlighted = isHighlighted,
        )
    }

    override val values: Sequence<ConversationListItemUiModel> = sequenceOf(
        createContactConversationListItemUiModel(
            latestMessageData = null,
            icon = null,
        ),
        createContactConversationListItemUiModel(
            showWorkBadge = true,
            isHighlighted = true,
        ),
        createContactConversationListItemUiModel(
            isTyping = true,
        ),
        createContactConversationListItemUiModel(
            unreadState = UnreadState.Messages(
                count = 5,
            ),
        ),
        createContactConversationListItemUiModel(
            isPinned = true,
        ),
        createContactConversationListItemUiModel(
            isPrivate = true,
            isTyping = true,
        ),
        createContactConversationListItemUiModel(
            unreadState = UnreadState.Messages(
                count = 5,
            ),
            isPinned = true,
            isPrivate = true,
        ),
        createContactConversationListItemUiModel(
            conversationNameStyle = ConversationNameStyle.inactiveContact(),
        ),
        createContactConversationListItemUiModel(
            conversationNameStyle = ConversationNameStyle.invalidContact(),
        ),
        createContactConversationListItemUiModel(
            draftData = ConversationUiModel.DraftData(
                draft = "This message is just a draft and is maybe sent in the future",
                mentionNames = emptyMap(),
            ),
        ),
        createContactConversationListItemUiModel(
            muteStatusIcon = R.drawable.ic_dnd_filled,
        ),
        createContactConversationListItemUiModel(
            latestMessageData = PreviewData.LatestMessageData.incomingDeletedTextMessage(),
        ),
        createContactConversationListItemUiModel(
            latestMessageData = PreviewData.LatestMessageData.incomingTextMessage(
                body = "Contact message body",
            ),
        ),
        createContactConversationListItemUiModel(
            latestMessageData = PreviewData.LatestMessageData.incomingFileMessage(
                caption = "File message caption",
            ),
        ),
        createContactConversationListItemUiModel(
            latestMessageData = PreviewData.LatestMessageData.incomingFileMessage(
                caption = "This is your file @[${PreviewData.IDENTITY_ME}]",
            ),
        ),
        createContactConversationListItemUiModel(
            isChecked = true,
        ),
    )
}

private class PreviewProviderGroupConversationListItemUiModel : PreviewParameterProvider<ConversationListItemUiModel> {

    private companion object {
        fun createGroupConversationListItemUiModel(
            receiverDisplayName: String = "Group Name",
            conversationName: String = "Group Name",
            conversationNameStyle: ConversationNameStyle = ConversationNameStyle(),
            draftData: ConversationUiModel.DraftData? = null,
            latestMessageData: ConversationUiModel.LatestMessageData? = PreviewData.LatestMessageData.incomingTextMessage(
                body = "Group message body",
            ),
            latestMessageSenderName: ResolvableString? = ResolvedString("Alice"),
            unreadState: UnreadState? = null,
            isPinned: Boolean = false,
            isPrivate: Boolean = false,
            icon: IconInfo? = IconInfo(
                iconRes = R.drawable.ic_group_filled,
                contentDescription = null,
            ),
            muteStatusIcon: Int? = null,
            groupCall: GroupCallUiModel? = null,
            isChecked: Boolean = false,
            isHighlighted: Boolean = false,
        ) = ConversationListItemUiModel(
            model = ConversationUiModel.GroupConversation(
                conversationId = GroupConversationId(
                    groupDatabaseId = 1L,
                ),
                receiverDisplayName = receiverDisplayName,
                conversationName = conversationName,
                conversationNameStyle = conversationNameStyle,
                draftData = draftData,
                latestMessageData = latestMessageData,
                latestMessageSenderName = latestMessageSenderName,
                unreadState = unreadState,
                isPinned = isPinned,
                isPrivate = isPrivate,
                icon = icon,
                muteStatusIcon = muteStatusIcon,
                groupCall = groupCall,
                avatarIteration = AvatarIteration.initial,
            ),
            isChecked = isChecked,
            isHighlighted = isHighlighted,
        )
    }

    override val values: Sequence<ConversationListItemUiModel> = sequenceOf(
        createGroupConversationListItemUiModel(
            latestMessageData = null,
            latestMessageSenderName = null,
        ),
        createGroupConversationListItemUiModel(),
        createGroupConversationListItemUiModel(
            latestMessageData = PreviewData.LatestMessageData.incomingTextMessage(
                body = "Hey @[${PreviewData.IDENTITY_OTHER_2}] @[${PreviewData.IDENTITY_OTHER_3}] ",
            ),
        ),
        createGroupConversationListItemUiModel(
            draftData = ConversationUiModel.DraftData(
                draft = "Draft mentioning @[${PreviewData.IDENTITY_OTHER_2}]",
                mentionNames = PreviewData.mentionNames,
            ),
        ),
        createGroupConversationListItemUiModel(
            conversationNameStyle = ConversationNameStyle.groupNotAMemberOf(),
        ),
        createGroupConversationListItemUiModel(
            isPinned = true,
        ),
        createGroupConversationListItemUiModel(
            unreadState = UnreadState.Messages(
                count = 5,
            ),
        ),
        createGroupConversationListItemUiModel(
            isPinned = true,
            unreadState = UnreadState.Messages(
                count = 5,
            ),
            isPrivate = true,
        ),
        createGroupConversationListItemUiModel(
            groupCall = GroupCallUiModel(
                id = CallId(emptyByteArray()),
                groupId = LocalGroupId(0),
                startedAt = Instant.now().toEpochMilli() - (1000L * 30L),
                processedAt = Instant.now().toEpochMilli() - (1000L * 30L),
                isJoined = false,
            ),
        ),
        createGroupConversationListItemUiModel(
            groupCall = GroupCallUiModel(
                id = CallId(emptyByteArray()),
                groupId = LocalGroupId(0),
                startedAt = Instant.now().toEpochMilli() - (1000L * 30L),
                processedAt = Instant.now().toEpochMilli() - (1000L * 30L),
                isJoined = true,
            ),
        ),
        createGroupConversationListItemUiModel(
            groupCall = GroupCallUiModel(
                id = CallId(emptyByteArray()),
                groupId = LocalGroupId(0),
                startedAt = Instant.now().toEpochMilli() - (1000L * 30L),
                processedAt = Instant.now().toEpochMilli() - (1000L * 30L),
                isJoined = false,
            ),
            isPinned = true,
        ),
        createGroupConversationListItemUiModel(
            groupCall = GroupCallUiModel(
                id = CallId(emptyByteArray()),
                groupId = LocalGroupId(0),
                startedAt = Instant.now().toEpochMilli() - (1000L * 30L),
                processedAt = Instant.now().toEpochMilli() - (1000L * 30L),
                isJoined = false,
            ),
            unreadState = UnreadState.Messages(
                count = 5,
            ),
        ),
        createGroupConversationListItemUiModel(
            groupCall = GroupCallUiModel(
                id = CallId(emptyByteArray()),
                groupId = LocalGroupId(0),
                startedAt = Instant.now().toEpochMilli() - (1000L * 30L),
                processedAt = Instant.now().toEpochMilli() - (1000L * 30L),
                isJoined = false,
            ),
            isPrivate = true,
        ),
        createGroupConversationListItemUiModel(
            groupCall = GroupCallUiModel(
                id = CallId(emptyByteArray()),
                groupId = LocalGroupId(0),
                startedAt = Instant.now().toEpochMilli() - (1000L * 30L),
                processedAt = Instant.now().toEpochMilli() - (1000L * 30L),
                isJoined = false,
            ),
            isPinned = true,
            muteStatusIcon = R.drawable.ic_dnd_mention_grey600_24dp,
            unreadState = UnreadState.Messages(
                count = 5,
            ),
        ),
        createGroupConversationListItemUiModel(
            isChecked = true,
        ),
    )
}

private class PreviewProviderDistributionListConversationListItemUiModel : PreviewParameterProvider<ConversationListItemUiModel> {

    private companion object {
        fun createDistributionListConversationListItemUiModel(
            receiverDisplayName: String = "Distribution List",
            conversationName: String = "Distribution List",
            conversationNameStyle: ConversationNameStyle = ConversationNameStyle(),
            draftData: ConversationUiModel.DraftData? = null,
            latestMessageData: ConversationUiModel.LatestMessageData? = PreviewData.LatestMessageData.incomingTextMessage(
                body = "Distribution list message",
            ),
            unreadState: UnreadState? = null,
            isPinned: Boolean = false,
            isPrivate: Boolean = false,
            icon: IconInfo? = IconInfo(
                iconRes = R.drawable.ic_distribution_list,
                contentDescription = null,
            ),
            muteStatusIcon: Int? = null,
            isChecked: Boolean = false,
            isHighlighted: Boolean = false,
        ) = ConversationListItemUiModel(
            model = ConversationUiModel.DistributionListConversation(
                conversationId = DistributionListConversationId(
                    distributionListId = 1L,
                ),
                receiverDisplayName = receiverDisplayName,
                conversationName = conversationName,
                conversationNameStyle = conversationNameStyle,
                draftData = draftData,
                latestMessageData = latestMessageData,
                unreadState = unreadState,
                isPinned = isPinned,
                isPrivate = isPrivate,
                icon = icon,
                muteStatusIcon = muteStatusIcon,
                avatarIteration = AvatarIteration.initial,
            ),
            isChecked = isChecked,
            isHighlighted = isHighlighted,
        )
    }

    override val values: Sequence<ConversationListItemUiModel> = sequenceOf(
        createDistributionListConversationListItemUiModel(
            latestMessageData = null,
        ),
        createDistributionListConversationListItemUiModel(),
        createDistributionListConversationListItemUiModel(
            isPinned = true,
        ),
        createDistributionListConversationListItemUiModel(
            isPrivate = true,
        ),
        createDistributionListConversationListItemUiModel(
            unreadState = UnreadState.Messages(
                count = 16,
            ),
        ),
        createDistributionListConversationListItemUiModel(
            unreadState = UnreadState.Messages(
                count = 16,
            ),
            isPinned = true,
            isPrivate = true,
        ),
        createDistributionListConversationListItemUiModel(
            isChecked = true,
        ),
    )
}

@Composable
@PreviewLightDark
@PreviewWrapper(wrapper = ThreemaPreviewWrapper::class)
private fun Preview_ContactConversation(
    @PreviewParameter(PreviewProviderContactConversationListItemItemUiModel::class)
    conversationListItemUiModel: ConversationListItemUiModel,
) = Preview(
    conversationListItemUiModel = conversationListItemUiModel,
)

@Composable
@PreviewLightDark
@PreviewWrapper(wrapper = ThreemaPreviewWrapper::class)
private fun Preview_GroupConversation(
    @PreviewParameter(PreviewProviderGroupConversationListItemUiModel::class)
    conversationListItemUiModel: ConversationListItemUiModel,
) = Preview(
    conversationListItemUiModel = conversationListItemUiModel,
)

@Composable
@PreviewLightDark
@PreviewWrapper(wrapper = ThreemaPreviewWrapper::class)
private fun Preview_DistributionListConversation(
    @PreviewParameter(PreviewProviderDistributionListConversationListItemUiModel::class)
    conversationListItemUiModel: ConversationListItemUiModel,
) = Preview(
    conversationListItemUiModel = conversationListItemUiModel,
)

@Composable
private fun Preview(conversationListItemUiModel: ConversationListItemUiModel) {
    ConversationListItem(
        modifier = Modifier,
        conversationListItemUiModel = conversationListItemUiModel,
        avatarIteration = AvatarIteration.initial,
        localDayOfYear = 1,
        avatarBitmapProvider = { null },
        ownIdentity = PreviewData.IDENTITY_ME,
        emojiStyle = EMOJI_STYLE_ANDROID,
        contactNameFormat = ContactNameFormat.DEFAULT,
        onClick = {},
        onLongClick = {},
        onClickAvatar = {},
        onClickJoinOrOpenGroupCall = {},
        swipeFeatureStartToEnd = ListItemSwipeFeature.StartToEnd(
            onSwipe = {},
            hapticFeedback = null,
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
            state = ListItemSwipeFeatureState(
                icon = R.drawable.ic_new_feature,
                text = "Swipe start to end",
            ),
        ),
        swipeFeatureEndToStart = ListItemSwipeFeature.EndToStart(
            onSwipe = {},
            hapticFeedback = null,
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
            state = ListItemSwipeFeatureState(
                icon = R.drawable.ic_new_feature,
                text = "Swipe end to start",
            ),
        ),
    )
}
