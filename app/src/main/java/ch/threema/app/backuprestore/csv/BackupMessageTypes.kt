package ch.threema.app.backuprestore.csv

import ch.threema.storage.models.MessageType

object BackupMessageTypes {
    const val TEXT = "TEXT"
    const val LOCATION = "LOCATION"
    const val STATUS = "STATUS"
    const val POLL = "BALLOT"
    const val FILE = "FILE"
    const val VOIP_STATUS = "VOIP_STATUS"
    const val GROUP_CALL_STATUS = "GROUP_CALL_STATUS"
    const val GROUP_STATUS = "GROUP_STATUS"

    // F1-PATCH: F1Whisper disappearing-timer status rows. Earlier F1Whisper backups already wrote this exact string (the
    // enum name), so it must not change.
    const val DISAPPEARING_STATUS = "DISAPPEARING_STATUS"

    const val DEPRECATED_CONTACT = "CONTACT"
    const val DEPRECATED_IMAGE = "IMAGE"
    const val DEPRECATED_VIDEO = "VIDEO"
    const val DEPRECATED_VOICEMESSAGE = "VOICEMESSAGE"

    @JvmStatic
    fun fromMessageType(messageType: MessageType): String? =
        when (messageType) {
            MessageType.TEXT -> TEXT
            MessageType.LOCATION -> LOCATION
            MessageType.STATUS -> STATUS
            MessageType.POLL -> POLL
            MessageType.FILE -> FILE
            MessageType.VOIP_STATUS -> VOIP_STATUS
            MessageType.GROUP_CALL_STATUS -> GROUP_CALL_STATUS
            MessageType.GROUP_STATUS -> GROUP_STATUS
            MessageType.DISAPPEARING_STATUS -> DISAPPEARING_STATUS
            MessageType.DATE_SEPARATOR,
            MessageType.FORWARD_SECURITY_STATUS,
            -> {
                // these types never get written into the backup
                null
            }
        }
}
