package ch.threema.storage.models.data.status

import ch.threema.common.putIfNotNull
import ch.threema.domain.types.IdentityString
import ch.threema.storage.models.data.status.StatusDataModel.StatusType

/**
 * F1Whisper: status data model for DISAPPEARING_STATUS inline messages.
 *
 * These are inserted locally when a per-conversation disappearing-messages timer is changed via the
 * [DisappearingTimerMessage] or [GroupDisappearingTimerMessage] control message. They render as
 * "X set disappearing messages to 1 day" / "You set..." / "Disappearing messages turned off".
 *
 * Stored as JSON via [StatusDataModel.serialize].
 *
 * @property timerSeconds timer in seconds; 0 means "off".
 * @property changedByIdentity the identity that changed the timer, or null when it was changed by the local user.
 * Null → "You turned off / set disappearing messages to …".
 */
@ConsistentCopyVisibility
data class DisappearingStatusDataModel
private constructor(
    val timerSeconds: Int,
    val changedByIdentity: IdentityString?,
) : StatusDataModel {
    @StatusType
    override val type
        get() = TYPE

    override fun getParams() = buildMap {
        put(PARAM_TIMER_SECONDS, timerSeconds)
        putIfNotNull(PARAM_IDENTITY, changedByIdentity)
    }

    companion object {
        /** Unique type integer, must not clash with VoipStatusDataModel(1), GroupCallStatus(2), ForwardSecurity(3), GroupStatus(4). */
        const val TYPE = 5

        private const val PARAM_TIMER_SECONDS = "timerSeconds"
        private const val PARAM_IDENTITY = "identity"

        /**
         * Create a disappearing-messages status data model.
         *
         * @param timerSeconds timer in seconds; 0 means the timer was turned off.
         * @param changedByIdentity the identity that changed the timer, or null if changed by the
         *   local user (renders as "You set…").
         */
        @JvmStatic
        fun create(timerSeconds: Int, changedByIdentity: IdentityString?) = DisappearingStatusDataModel(
            timerSeconds = timerSeconds,
            changedByIdentity = changedByIdentity,
        )

        fun createFromParams(params: Map<String, Any?>) = DisappearingStatusDataModel(
            timerSeconds = (params[PARAM_TIMER_SECONDS] as? Number)?.toInt() ?: 0,
            changedByIdentity = params[PARAM_IDENTITY] as? String,
        )
    }
}
