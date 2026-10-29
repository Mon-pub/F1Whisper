package ch.threema.app.conversation

import android.content.Context
import androidx.compose.runtime.Stable
import ch.threema.app.R
import ch.threema.app.adapters.decorators.GroupStatusAdapterDecorator
import ch.threema.app.di.injectNullableNonBinding
import ch.threema.app.services.poll.PollService
import ch.threema.app.ui.models.MessageViewElement
import ch.threema.app.utils.IconUtil
import ch.threema.app.utils.MimeUtil
import ch.threema.app.utils.PollUtil
import ch.threema.app.utils.QuoteUtil
import ch.threema.common.takeUnlessEmpty
import ch.threema.common.toHMMSS
import ch.threema.data.IdentityProvider
import ch.threema.data.datatypes.ContactNameFormat
import ch.threema.data.repositories.ContactModelRepository
import ch.threema.domain.protocol.csp.messages.file.FileData
import ch.threema.domain.protocol.csp.messages.voip.VoipCallAnswerData
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.data.media.FileDataModel
import ch.threema.storage.models.data.media.PollDataModel
import ch.threema.storage.models.data.status.ForwardSecurityStatusDataModel
import ch.threema.storage.models.data.status.GroupCallStatusDataModel
import ch.threema.storage.models.data.status.GroupStatusDataModel
import ch.threema.storage.models.data.status.StatusDataModel
import ch.threema.storage.models.data.status.VoipStatusDataModel
import kotlin.time.Duration.Companion.seconds
import org.koin.core.component.KoinComponent

class MessageViewElementFactory(
    private val appContext: Context,
    private val identityProvider: IdentityProvider,
) : KoinComponent {
    private val contactModelRepository: ContactModelRepository? by injectNullableNonBinding()
    private val pollService: PollService? by injectNullableNonBinding()

    @Stable
    fun getViewElement(
        messageType: MessageType,
        messageBody: String?,
        messageCaption: String?,
        isOutbox: Boolean,
        contactNameFormat: ContactNameFormat,
    ): MessageViewElement? = when (messageType) {
        MessageType.TEXT -> getViewElementForTextMessage(
            messageBody = messageBody,
            caption = messageCaption?.takeUnlessEmpty(),
            isOutbox = isOutbox,
            contactNameFormat = contactNameFormat,
        )
        MessageType.LOCATION -> getViewElementForLocationMessage()
        MessageType.FILE -> getViewElementForFileMessage(
            messageBody = messageBody,
            caption = messageCaption?.takeUnlessEmpty(),
        )
        MessageType.POLL -> getViewElementForPollMessage(messageBody)
        MessageType.GROUP_STATUS -> getViewElementForGroupStatusMessage(
            messageBody = messageBody ?: return null,
            contactNameFormat = contactNameFormat,
        )
        MessageType.VOIP_STATUS -> getViewElementForVoipStatusMessage(
            messageBody = messageBody ?: return null,
            isOutbox = isOutbox,
        )
        MessageType.GROUP_CALL_STATUS -> getViewElementForGroupCallStatusMessage(
            messageBody = messageBody ?: return null,
            isOutbox = isOutbox,
        )
        MessageType.FORWARD_SECURITY_STATUS -> getViewElementForForwardSecurityMessage(
            messageBody = messageBody ?: return null,
        )
        // F1Whisper: the disappearing-timer status row has no conversation list preview
        MessageType.DISAPPEARING_STATUS,
        MessageType.STATUS,
        MessageType.DATE_SEPARATOR,
        -> null
    }

    private fun getViewElementForTextMessage(
        messageBody: String?,
        caption: String?,
        isOutbox: Boolean,
        contactNameFormat: ContactNameFormat,
    ): MessageViewElement =
        MessageViewElement(
            text = QuoteUtil.getMessageBody(
                /* messageType = */
                MessageType.TEXT,
                /* messageBody = */
                messageBody,
                /* messageCaption = */
                caption,
                /* isOutbox = */
                isOutbox,
                /* substituteAndTruncate = */
                false,
                /* contactNameFormat = */
                contactNameFormat,
            ),
        )

    private fun getViewElementForLocationMessage(): MessageViewElement {
        val placeholder = appContext.getString(R.string.location_placeholder)
        return MessageViewElement(
            icon = R.drawable.ic_location_pin_filled,
            placeholder = placeholder,
            text = placeholder,
        )
    }

    private fun getViewElementForFileMessage(
        messageBody: String?,
        caption: String?,
    ): MessageViewElement {
        val fileDataModel = messageBody
            ?.takeUnlessEmpty()
            ?.let {
                FileDataModel.create(messageBody)
            }

        if (fileDataModel == null) {
            return MessageViewElement(
                icon = R.drawable.ic_doc_file,
                placeholder = appContext.getString(R.string.file_placeholder),
            )
        }
        val mimeType = fileDataModel.getMimeType()
        return when {
            MimeUtil.isImageFile(mimeType) -> {
                MessageViewElement(
                    icon = R.drawable.ic_photo_filled,
                    placeholder = appContext.getString(R.string.image_placeholder),
                    text = caption,
                )
            }
            MimeUtil.isVideoFile(mimeType) -> {
                MessageViewElement(
                    icon = R.drawable.ic_movie_filled,
                    placeholder = appContext.getString(R.string.video_placeholder),
                    text = caption,
                )
            }
            MimeUtil.isAudioFile(mimeType) -> {
                if (fileDataModel.renderingType == FileData.RENDERING_MEDIA) {
                    val placeholder = appContext.getString(R.string.voice_message)
                    val formattedDuration = fileDataModel.getDurationSeconds()
                        .takeIf { it > 0L }
                        ?.seconds
                        ?.toHMMSS()
                    val text = if (formattedDuration != null) {
                        appContext.getString(R.string.voice_message_with_duration_pattern, placeholder, formattedDuration)
                    } else {
                        placeholder
                    }
                    MessageViewElement(
                        icon = R.drawable.ic_microphone,
                        placeholder = placeholder,
                        text = text,
                    )
                } else {
                    MessageViewElement(
                        icon = R.drawable.ic_doc_audio,
                        placeholder = appContext.getString(R.string.audio_placeholder),
                        text = caption,
                    )
                }
            }
            else -> {
                MessageViewElement(
                    icon = IconUtil.getMimeIcon(mimeType),
                    placeholder = appContext.getString(R.string.file_placeholder),
                    text = caption
                        ?: (fileDataModel.fileName?.takeUnlessEmpty()),
                )
            }
        }
    }

    private fun getViewElementForPollMessage(messageBody: String?): MessageViewElement {
        val text = messageBody
            ?.takeUnlessEmpty()
            ?.let {
                PollDataModel.deserialize(messageBody)
            }
            ?.let { pollModel ->
                pollService?.let { pollService ->
                    PollUtil.getNotificationString(appContext, pollService, pollModel.pollId)
                }
            }
            ?.takeUnlessEmpty()
        return MessageViewElement(
            icon = R.drawable.ic_baseline_rule,
            placeholder = appContext.getString(R.string.ballot_placeholder),
            text = text,
        )
    }

    private fun getViewElementForGroupStatusMessage(
        messageBody: String,
        contactNameFormat: ContactNameFormat,
    ): MessageViewElement? {
        val groupStatusDataModel = (StatusDataModel.deserialize(messageBody) as? GroupStatusDataModel)
            ?: return null
        val statusText = GroupStatusAdapterDecorator.getStatusText(
            groupStatusDataModel,
            identityProvider,
            contactModelRepository,
            contactNameFormat,
            appContext,
        )
        return MessageViewElement(
            placeholder = statusText,
            text = statusText,
        )
    }

    private fun getViewElementForVoipStatusMessage(messageBody: String, isOutbox: Boolean): MessageViewElement? {
        val voipStatusDataModel = (StatusDataModel.deserialize(messageBody) as? VoipStatusDataModel)
            ?: return null
        return when (voipStatusDataModel.status) {
            VoipStatusDataModel.REJECTED -> {
                val rejectReason = voipStatusDataModel.reason ?: VoipCallAnswerData.RejectReason.UNKNOWN

                val rejectColor = when (rejectReason) {
                    VoipCallAnswerData.RejectReason.REJECTED,
                    VoipCallAnswerData.RejectReason.DISABLED,
                    VoipCallAnswerData.RejectReason.OFF_HOURS,
                    -> if (isOutbox) {
                        R.color.material_red
                    } else {
                        R.color.material_orange
                    }
                    else -> R.color.material_red
                }

                val rejectPlaceholder = when (rejectReason) {
                    VoipCallAnswerData.RejectReason.BUSY -> if (isOutbox) {
                        appContext.getString(R.string.voip_call_status_busy)
                    } else {
                        appContext.getString(
                            R.string.missed_call_with_detail_pattern,
                            appContext.getString(R.string.voip_call_status_missed),
                            appContext.getString(R.string.voip_call_status_busy_short),
                        )
                    }
                    VoipCallAnswerData.RejectReason.TIMEOUT -> if (isOutbox) {
                        appContext.getString(R.string.voip_call_status_unavailable)
                    } else {
                        appContext.getString(R.string.voip_call_status_missed)
                    }
                    VoipCallAnswerData.RejectReason.REJECTED -> {
                        appContext.getString(R.string.voip_call_status_rejected)
                    }
                    VoipCallAnswerData.RejectReason.DISABLED -> {
                        if (isOutbox) {
                            appContext.getString(R.string.voip_call_status_disabled)
                        } else {
                            appContext.getString(R.string.voip_call_status_rejected)
                        }
                    }
                    VoipCallAnswerData.RejectReason.OFF_HOURS -> {
                        appContext.getString(R.string.voip_call_status_off_hours)
                    }
                    else -> if (isOutbox) {
                        appContext.getString(R.string.voip_call_status_rejected)
                    } else {
                        appContext.getString(R.string.voip_call_status_missed)
                    }
                }
                MessageViewElement(
                    icon = if (isOutbox) {
                        R.drawable.ic_call_missed_outgoing_black_24dp
                    } else {
                        R.drawable.ic_call_missed_black_24dp
                    },
                    placeholder = rejectPlaceholder,
                    text = rejectPlaceholder,
                    iconTint = rejectColor,
                )
            }
            VoipStatusDataModel.ABORTED -> MessageViewElement(
                icon = R.drawable.ic_call_missed_outgoing_black_24dp,
                placeholder = appContext.getString(R.string.voip_call_status_aborted),
                text = appContext.getString(R.string.voip_call_status_aborted),
                iconTint = R.color.material_orange,
            )
            VoipStatusDataModel.MISSED -> MessageViewElement(
                icon = if (isOutbox) {
                    R.drawable.ic_call_missed_outgoing_black_24dp
                } else {
                    R.drawable.ic_call_missed_black_24dp
                },
                placeholder = appContext.getString(R.string.voip_call_status_missed),
                text = appContext.getString(R.string.voip_call_status_missed),
                iconTint = R.color.material_red,
            )
            VoipStatusDataModel.FINISHED -> {
                val placeholder = appContext.getString(
                    if (isOutbox) {
                        R.string.voip_call_finished_outbox
                    } else {
                        R.string.voip_call_finished_inbox
                    },
                )
                MessageViewElement(
                    icon = if (isOutbox) {
                        R.drawable.ic_call_made_black_24dp
                    } else {
                        R.drawable.ic_call_received_black_24dp
                    },
                    placeholder = placeholder,
                    text = placeholder,
                    iconTint = R.color.material_green,
                )
            }
            else -> null
        }
    }

    private fun getViewElementForGroupCallStatusMessage(
        messageBody: String,
        isOutbox: Boolean,
    ): MessageViewElement? {
        val groupCallStatusDataModel = (StatusDataModel.deserialize(messageBody) as? GroupCallStatusDataModel)
            ?: return null

        return when (groupCallStatusDataModel.status) {
            GroupCallStatusDataModel.STATUS_STARTED -> {
                val body = groupCallStatusDataModel.callerIdentity
                    ?.let { callerIdentity ->
                        contactModelRepository?.getByIdentity(callerIdentity)
                    }
                    ?.data
                    ?.getShortName()
                    ?.let { shortName ->
                        appContext.getString(
                            if (isOutbox) {
                                R.string.voip_gc_notification_call_started_generic_outbox
                            } else {
                                R.string.voip_gc_notification_call_started_generic
                            },
                            shortName,
                        )
                    }
                    ?: appContext.getString(R.string.voip_gc_call_started)
                MessageViewElement(
                    icon = R.drawable.ic_phone_locked_outline,
                    placeholder = appContext.getString(R.string.voip_gc_call_started),
                    text = body,
                )
            }
            GroupCallStatusDataModel.STATUS_ENDED -> MessageViewElement(
                icon = R.drawable.ic_phone_locked_outline,
                placeholder = appContext.getString(R.string.voip_gc_call_ended),
                text = appContext.getString(R.string.voip_gc_call_ended),
            )
            else -> null
        }
    }

    private fun getViewElementForForwardSecurityMessage(messageBody: String): MessageViewElement? {
        val forwardSecurityStatusDataModel = (StatusDataModel.deserialize(messageBody) as ForwardSecurityStatusDataModel?)
            ?: return null

        return when (forwardSecurityStatusDataModel.statusType) {
            ForwardSecurityStatusDataModel.ForwardSecurityStatusType.FORWARD_SECURITY_RESET -> {
                val body = appContext.getString(R.string.forward_security_reset_simple)
                MessageViewElement(
                    icon = R.drawable.ic_key_off,
                    placeholder = body,
                    text = body,
                    iconTint = R.color.material_red,
                )
            }
            ForwardSecurityStatusDataModel.ForwardSecurityStatusType.FORWARD_SECURITY_ESTABLISHED -> {
                val body = appContext.getString(R.string.forward_security_established)
                MessageViewElement(
                    icon = R.drawable.ic_baseline_key_24,
                    placeholder = body,
                    text = body,
                    iconTint = R.color.material_green,
                )
            }
            ForwardSecurityStatusDataModel.ForwardSecurityStatusType.FORWARD_SECURITY_ESTABLISHED_RX -> {
                val body = appContext.getString(R.string.forward_security_established_rx)
                MessageViewElement(
                    icon = R.drawable.ic_baseline_key_24,
                    placeholder = body,
                    text = body,
                    iconTint = R.color.material_green,
                )
            }
            ForwardSecurityStatusDataModel.ForwardSecurityStatusType.FORWARD_SECURITY_MESSAGES_SKIPPED -> {
                val body = appContext.resources.getQuantityString(
                    R.plurals.forward_security_messages_skipped,
                    forwardSecurityStatusDataModel.quantity,
                    forwardSecurityStatusDataModel.quantity,
                )
                MessageViewElement(
                    icon = R.drawable.ic_baseline_key_24,
                    placeholder = body,
                    text = body,
                )
            }
            ForwardSecurityStatusDataModel.ForwardSecurityStatusType.FORWARD_SECURITY_MESSAGE_OUT_OF_ORDER -> {
                val body = appContext.getString(R.string.forward_security_message_out_of_order)
                MessageViewElement(
                    icon = R.drawable.ic_baseline_key_24,
                    placeholder = body,
                    text = body,
                )
            }
            ForwardSecurityStatusDataModel.ForwardSecurityStatusType.MESSAGE_WITHOUT_FORWARD_SECURITY -> {
                val body = appContext.getString(R.string.message_without_forward_security)
                MessageViewElement(
                    icon = R.drawable.ic_key_off,
                    placeholder = body,
                    text = body,
                )
            }
            ForwardSecurityStatusDataModel.ForwardSecurityStatusType.FORWARD_SECURITY_UNAVAILABLE_DOWNGRADE -> {
                val body = appContext.getString(R.string.forward_security_downgraded_status_message)
                MessageViewElement(
                    icon = R.drawable.ic_baseline_key_24,
                    placeholder = body,
                    text = body,
                )
            }
            ForwardSecurityStatusDataModel.ForwardSecurityStatusType.FORWARD_SECURITY_ILLEGAL_SESSION_STATE -> {
                val body = appContext.getString(R.string.forward_security_illegal_session_status_message)
                MessageViewElement(
                    icon = R.drawable.ic_key_off,
                    placeholder = body,
                    text = body,
                )
            }
            ForwardSecurityStatusDataModel.ForwardSecurityStatusType.FORWARD_SECURITY_DISABLED -> {
                val body = appContext.getString(R.string.forward_security_disabled)
                MessageViewElement(
                    icon = R.drawable.ic_key_off,
                    placeholder = body,
                    text = body,
                )
            }
            else -> {
                MessageViewElement(
                    icon = R.drawable.ic_baseline_key_24,
                    placeholder = forwardSecurityStatusDataModel.staticText,
                    text = forwardSecurityStatusDataModel.staticText,
                )
            }
        }
    }
}
