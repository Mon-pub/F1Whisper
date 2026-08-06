package ch.threema.app.adapters.decorators

import android.content.Context
import ch.threema.app.R
import ch.threema.app.ui.listitemholder.ComposeMessageHolder
import ch.threema.app.utils.DisappearingMessageUtil
import ch.threema.app.utils.LinkifyUtil
import ch.threema.storage.models.AbstractMessageModel

/**
 * F1Whisper: renders a [ch.threema.storage.models.MessageType.DISAPPEARING_STATUS] row as a centered
 * system message announcing a per-conversation timer change. The text is localized at render time
 * (not pre-rendered into the body) so it follows the in-app language switch, mirroring
 * [GroupStatusAdapterDecorator].
 *
 * The actor is the [DisappearingStatusDataModel.changedByIdentity]; when it is null the local user
 * changed the timer, rendering as "You set…". The duration label comes from the shared
 * [DisappearingMessageUtil] so the wording always matches the picker.
 */
class DisappearingStatusChatAdapterDecorator(
    messageModel: AbstractMessageModel,
    chatAdapterDecoratorListener: ChatAdapterDecoratorListener,
    linkifyListener: LinkifyUtil.LinkifyListener,
    helper: Helper?,
) : ChatAdapterDecorator(messageModel, chatAdapterDecoratorListener, linkifyListener, helper) {

    override fun configureChatMessage(holder: ComposeMessageHolder, context: Context, position: Int) {
        val statusData = messageModel.disappearingStatusData ?: return
        val changedBy = statusData.changedByIdentity
        val isMe = changedBy == null
        val isOff = statusData.timerSeconds <= 0

        val statusText: String = when {
            isOff && isMe -> context.getString(R.string.status_disappearing_off_you)
            isOff -> context.getString(R.string.status_disappearing_off, resolveName(context, changedBy))
            isMe -> context.getString(
                R.string.status_disappearing_set_you,
                DisappearingMessageUtil.getDurationLabel(context, statusData.timerSeconds),
            )

            else -> context.getString(
                R.string.status_disappearing_set,
                resolveName(context, changedBy),
                DisappearingMessageUtil.getDurationLabel(context, statusData.timerSeconds),
            )
        }

        if (showHide(holder.bodyTextView, statusText.isNotEmpty())) {
            holder.bodyTextView.text = statusText
        }
        setOnClickListener({
            // no action on click
        }, holder.messageBlockView)
    }

    /**
     * Resolve a contact's display name for the given identity, falling back to the bare identity.
     * Mirrors [GroupStatusAdapterDecorator]'s name resolution (base-class service getters).
     */
    private fun resolveName(context: Context, identity: String?): String {
        if (identity == null || userService?.isMe(identity) == true) {
            return context.getString(R.string.me_myself_and_i)
        }
        val contactModel = contactService?.getByIdentity(identity) ?: return identity
        val contactMessageReceiver = contactService?.createReceiver(contactModel) ?: return identity
        return contactMessageReceiver.getDisplayName(
            helper?.preferenceService?.getContactNameFormat() ?: return identity,
        )
    }
}
