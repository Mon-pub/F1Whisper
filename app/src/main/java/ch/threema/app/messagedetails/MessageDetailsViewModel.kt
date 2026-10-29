package ch.threema.app.messagedetails

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.threema.app.eventbus.GlobalEventFlows
import ch.threema.app.eventbus.events.MessageEvent
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.services.MessageService
import ch.threema.app.utils.QuoteUtil
import ch.threema.app.utils.StateBitmapUtil
import ch.threema.common.ByteSize
import ch.threema.common.bytes
import ch.threema.data.datatypes.ContactNameFormat
import ch.threema.data.repositories.EmojiReactionsRepository
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityMode
import ch.threema.domain.types.MessageUid
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.DistributionListMessageModel
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.group.GroupMessageModel
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MessageDetailsViewModel(
    private val messageService: MessageService,
    private val globalEventFlows: GlobalEventFlows,
    private val emojiReactionsRepository: EmojiReactionsRepository,
    private val preferenceService: PreferenceService,
    private val messageId: Int,
    private val messageType: String,
) : ViewModel() {

    private val _uiState: MutableStateFlow<ChatMessageDetailsUiState> = let {
        val message = messageService.getMessageModelFromId(messageId, messageType)
        MutableStateFlow(
            ChatMessageDetailsUiState(
                message = message.toUiModel(
                    contactNameFormat = preferenceService.getContactNameFormat(),
                    groupReceipts = message.buildGroupReceipts(),
                ),
                hasReactions = message.hasReactions(),
                shouldMarkupText = true,
            ),
        )
    }
    val uiState: StateFlow<ChatMessageDetailsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // TODO(ANDR-4681): Instead of collecting message events, it would be better if we could subscribe to changes
            //  directly from the MessageModel class (which does not exist yet at the time of this writing)
            globalEventFlows
                .messages
                .mapNotNull { event ->
                    val currentMessageUid: MessageUid = uiState.value.message.uid
                    val messageFromEvent = when (event) {
                        is MessageEvent.NewMessage -> null
                        is MessageEvent.MessagesUpdated -> event.messages.find { it.uid == currentMessageUid }
                        is MessageEvent.MessageRemovedLocally -> event.message
                        is MessageEvent.MessageEdited -> event.message
                        is MessageEvent.MessageDeletedForAll -> event.message
                    }
                    messageFromEvent?.takeIf { it.uid == currentMessageUid }
                }.collect { message ->
                    refreshMessage(message)
                }
        }
    }

    private fun AbstractMessageModel.hasReactions(): Boolean =
        emojiReactionsRepository.safeGetReactionsByMessage(this).isNotEmpty()

    // F1Whisper: per-member delivered/read states for an OUTGOING group message
    private fun AbstractMessageModel.buildGroupReceipts(): List<GroupMemberReceiptUiModel> =
        if (this is GroupMessageModel && this.isOutbox) {
            messageService.getGroupReceiptStates(this).map {
                GroupMemberReceiptUiModel(it.displayName, it.state)
            }
        } else {
            emptyList()
        }

    private fun refreshMessage(updatedMessage: AbstractMessageModel) {
        _uiState.update {
            it.copy(
                message = updatedMessage.toUiModel(
                    contactNameFormat = preferenceService.getContactNameFormat(),
                    groupReceipts = updatedMessage.buildGroupReceipts(),
                ),
            )
        }
    }

    fun markupText(value: Boolean) {
        _uiState.update { it.copy(shouldMarkupText = value) }
    }
}

data class ChatMessageDetailsUiState(
    val message: MessageUiModel,
    val hasReactions: Boolean,
    val shouldMarkupText: Boolean,
)

// TODO(ANDR-3195): Move MessageModel mappings from ChatMessageDetailsViewModel to data models
data class MessageUiModel(
    val id: Int,
    val uid: String,
    val text: String,
    val createdAt: Instant,
    val editedAt: Instant?,
    val isDeleted: Boolean,
    val isOutbox: Boolean,
    @DrawableRes val deliveryIconRes: Int?,
    @StringRes val deliveryIconContentDescriptionRes: Int?,
    val messageTimestampsUiModel: MessageTimestampsUiModel,
    val messageDetailsUiModel: MessageDetailsUiModel,
    val type: MessageType?,
    val groupReceipts: List<GroupMemberReceiptUiModel> = emptyList(),
)

/**
 * F1Whisper: one group member's delivered/read state for the message-details screen.
 * [state] is [MessageState.READ], [MessageState.DELIVERED], or {@code null} (sent only).
 */
data class GroupMemberReceiptUiModel(
    val displayName: String,
    val state: MessageState?,
)

data class MessageTimestampsUiModel(
    val createdAt: Instant? = null,
    val sentAt: Instant? = null,
    val receivedAt: Instant? = null,
    val deliveredAt: Instant? = null,
    val readAt: Instant? = null,
    val modifiedAt: Instant? = null,
    val editedAt: Instant? = null,
    val deletedAt: Instant? = null,
    // F1Whisper disappearing messages: the absolute deletion time once the countdown has started
    // (from the message's [AbstractMessageModel.expiresAt]), or null while it has not started.
    val expiresAt: Instant? = null,
    // F1Whisper disappearing messages: the frozen per-message timer in seconds, used to render the
    // "<duration> after read" hint for a disappearing message whose countdown has not started yet
    // (typically an incoming unread message where [expiresAt] is still null). Null if not disappearing.
    val disappearsAfterSeconds: Int? = null,
) {
    fun hasProperties(): Boolean {
        return createdAt != null ||
            sentAt != null ||
            receivedAt != null ||
            deliveredAt != null ||
            readAt != null ||
            modifiedAt != null ||
            editedAt != null ||
            deletedAt != null ||
            expiresAt != null ||
            disappearsAfterSeconds != null
    }
}

data class MessageDetailsUiModel(
    val messageId: String? = null,
    val mimeType: String? = null,
    val fileSize: ByteSize? = null,
    val pfsState: ForwardSecurityMode? = null,
) {
    fun hasProperties(): Boolean {
        return messageId != null ||
            mimeType != null ||
            fileSize != null ||
            pfsState != null
    }
}

fun AbstractMessageModel.toUiModel(
    contactNameFormat: ContactNameFormat,
    groupReceipts: List<GroupMemberReceiptUiModel> = emptyList(),
) = MessageUiModel(
    id = this.id,
    uid = this.uid!!,
    text = QuoteUtil.getMessageBody(
        this.type,
        this.body,
        this.caption,
        this.isOutbox,
        false,
        contactNameFormat,
    ) ?: "",
    createdAt = this.createdAt!!,
    editedAt = this.editedAt,
    isDeleted = this.isDeleted,
    isOutbox = this.isOutbox,
    deliveryIconRes = StateBitmapUtil.getInstance().getStateDrawable(this.state),
    deliveryIconContentDescriptionRes = StateBitmapUtil.getInstance().getStateDescription(this.state),
    messageTimestampsUiModel = this.toMessageTimestampsUiModel(),
    messageDetailsUiModel = this.toMessageDetailsUiModel(),
    type = this.type,
    groupReceipts = groupReceipts,
)

fun AbstractMessageModel?.toMessageTimestampsUiModel(): MessageTimestampsUiModel {
    if (this == null) {
        return MessageTimestampsUiModel()
    }
    if (this.isStatusMessage) {
        return MessageTimestampsUiModel(createdAt = this.createdAt)
    } else if (this.type == MessageType.GROUP_CALL_STATUS) {
        return MessageTimestampsUiModel(
            sentAt = this.createdAt,
            deliveredAt = if (!this.isOutbox) this.deliveredAt else null,
        )
    }

    // F1Whisper disappearing messages: surface the deletion time (once the countdown has started)
    // or, for a frozen-but-unstarted timer (typically an incoming unread message), the "after read"
    // duration. Suppressed for an already-deleted message where the countdown is moot.
    val expiresAtInstant: Instant? =
        if (!this.isDeleted && this.isDisappearing) this.expiresAt?.let(Instant::ofEpochMilli) else null
    val disappearsAfterSeconds: Int? =
        if (!this.isDeleted && this.isDisappearing && this.expiresAt == null) {
            this.disappearingTimerSeconds
        } else {
            null
        }

    return if (this.isOutbox) {
        val shouldShowAdditionalTimestamps =
            this.state != MessageState.SENT && !(this.type == MessageType.POLL && this is GroupMessageModel)

        val shouldShowPostedAt =
            (
                state != MessageState.SENDING &&
                    state != MessageState.SENDFAILED &&
                    state != MessageState.FS_KEY_MISMATCH &&
                    state != MessageState.PENDING
                ) ||
                type == MessageType.POLL

        val shouldShowModifiedAt =
            !(this.state == MessageState.READ && this.modifiedAt == this.readAt) &&
                !(this.state == MessageState.DELIVERED && this.modifiedAt == this.deliveredAt) &&
                !this.isDeleted

        MessageTimestampsUiModel(
            createdAt = this.createdAt,
            sentAt = if (shouldShowPostedAt) this.postedAt else null,
            deliveredAt = if (shouldShowAdditionalTimestamps) this.deliveredAt else null,
            readAt = if (shouldShowAdditionalTimestamps) this.readAt else null,
            modifiedAt = if (shouldShowAdditionalTimestamps && shouldShowModifiedAt) this.modifiedAt else null,
            editedAt = this.editedAt,
            deletedAt = this.deletedAt,
            expiresAt = expiresAtInstant,
            disappearsAfterSeconds = disappearsAfterSeconds,
        )
    } else {
        MessageTimestampsUiModel(
            createdAt = this.postedAt,
            receivedAt = this.createdAt,
            readAt = this.readAt,
            editedAt = this.editedAt,
            deletedAt = this.deletedAt,
            expiresAt = expiresAtInstant,
            disappearsAfterSeconds = disappearsAfterSeconds,
        )
    }
}

fun AbstractMessageModel?.toMessageDetailsUiModel(): MessageDetailsUiModel {
    if (this == null) {
        return MessageDetailsUiModel()
    }
    if (this.isStatusMessage || this.type == MessageType.GROUP_CALL_STATUS) {
        return MessageDetailsUiModel()
    }
    val fileSize: ByteSize? = if (this.type == MessageType.FILE) {
        this.fileData.fileSize.takeIf { fileSize -> fileSize > 0L }?.bytes
    } else {
        null
    }
    val mimeType: String? =
        if (this.type == MessageType.FILE) this.fileData.mimeType.takeIf(String::isNotBlank) else null
    val messageId: String? = this.apiMessageId?.takeIf(String::isNotBlank)
    val pfsState: ForwardSecurityMode? =
        if (this !is DistributionListMessageModel) this.forwardSecurityMode else null

    return MessageDetailsUiModel(
        mimeType = mimeType,
        fileSize = fileSize,
        messageId = messageId,
        pfsState = pfsState,
    )
}
