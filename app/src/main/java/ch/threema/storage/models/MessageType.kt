package ch.threema.storage.models

/**
 * Former types:
 * - IMAGE (1)
 * - VIDEO (2)
 * - VOICEMESSAGE (3)
 * - CONTACT (5)
 *
 * @param serializedValue A numerical value that represents the enum value. It can be used for persisting a message type, and therefore must
 * be unique, and never be changed for existing values.
 * @param canBeEdited Whether the given message type allows editing in general.
 * To check whether the user should be able to edit a particular message, [ch.threema.app.utils.canBeEdited] should be used.
 * @param requiresMessageId Whether messages of this type are required to have a non-null [AbstractMessageModel.messageId] when being stored
 * into the database
 */
enum class MessageType(
    @JvmField
    val serializedValue: Int,
    val canBeEdited: Boolean = false,
    val requiresMessageId: Boolean = false,
) {
    TEXT(0, canBeEdited = true, requiresMessageId = true),
    LOCATION(4, requiresMessageId = true),
    STATUS(6),
    POLL(7, requiresMessageId = true),
    FILE(8, canBeEdited = true, requiresMessageId = true),
    VOIP_STATUS(9),
    DATE_SEPARATOR(10),
    GROUP_CALL_STATUS(11),
    FORWARD_SECURITY_STATUS(12),
    GROUP_STATUS(13),

    // F1Whisper disappearing messages: inline status message announcing a per-conversation timer
    // change ("X set disappearing messages to 1 day" / "...turned off"). F1-PATCH: stored value 14 is
    // what every existing F1Whisper database already holds for these rows (it was the next ordinal),
    // so it must never change; a colliding upstream value would need a data migration first.
    DISAPPEARING_STATUS(14),
    ;

    companion object {
        @JvmStatic
        fun deserialize(serializedValue: Int): MessageType? =
            entries.find { it.serializedValue == serializedValue }
    }
}
