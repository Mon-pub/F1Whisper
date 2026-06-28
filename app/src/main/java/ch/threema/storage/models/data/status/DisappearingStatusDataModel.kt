package ch.threema.storage.models.data.status

import android.util.JsonWriter
import ch.threema.domain.types.IdentityString
import ch.threema.storage.models.data.status.StatusDataModel.StatusType

/**
 * F1Whisper: status data model for DISAPPEARING_STATUS inline messages.
 *
 * These are inserted locally when a per-conversation disappearing-messages timer is changed via the
 * [DisappearingTimerMessage] or [GroupDisappearingTimerMessage] control message. They render as
 * "X set disappearing messages to 1 day" / "You set..." / "Disappearing messages turned off".
 *
 * Stored as JSON via [StatusDataModel.convert].
 */
class DisappearingStatusDataModel : StatusDataModel.StatusDataModelInterface {
    private val timerSecondsKey = "timerSeconds"
    private val identityKey = "identity"

    /** Timer in seconds; 0 means "off". */
    var timerSeconds: Int = 0
        private set

    /**
     * The identity that changed the timer, or null when it was changed by the local user.
     * Null → "You turned off / set disappearing messages to …".
     */
    var changedByIdentity: IdentityString? = null
        private set

    @StatusType
    override fun getType(): Int = TYPE

    override fun readData(key: String, value: String) {
        if (key == identityKey) changedByIdentity = value
    }

    override fun readData(key: String?, value: Long) {
        if (key == timerSecondsKey) timerSeconds = value.toInt()
    }

    override fun readData(key: String?, value: Boolean) {
        // no boolean fields
    }

    override fun readDataNull(key: String?) {
        if (key == identityKey) changedByIdentity = null
    }

    override fun writeData(j: JsonWriter) {
        j.name(timerSecondsKey).value(timerSeconds.toLong())
        if (changedByIdentity != null) {
            j.name(identityKey).value(changedByIdentity)
        }
    }

    companion object {
        /** Unique type integer — must not clash with VoipStatusDataModel(1), GroupCallStatus(2), ForwardSecurity(3), GroupStatus(4). */
        const val TYPE = 5

        /**
         * Create a disappearing-messages status data model.
         *
         * @param timerSeconds timer in seconds; 0 means the timer was turned off.
         * @param changedByIdentity the identity that changed the timer, or null if changed by the
         *   local user (renders as "You set…").
         */
        @JvmStatic
        fun create(timerSeconds: Int, changedByIdentity: IdentityString?): DisappearingStatusDataModel =
            DisappearingStatusDataModel().apply {
                this.timerSeconds = timerSeconds
                this.changedByIdentity = changedByIdentity
            }
    }
}
