package ch.threema.app.tasks

import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageType
import java.util.Date

/**
 * F1Whisper (seventh fork review, F7-01 / F7-03): whether a persistent task may still transmit user content from the
 * row it was scheduled for.
 *
 * **The defect this closes.** A persistent send task is archived to disk with nothing but a LOCAL message id, and it may
 * not run for hours - the device can be offline, or the process can die and the task be reloaded on the next launch.
 * When it finally does run it loaded its message through the service's id getters, which answer from the in-memory cache
 * BEFORE the database. So a message the user hard-deleted while the task was queued could still be handed back in full -
 * body, blob id, encryption key - and sent. Nothing in the database said so, and nothing in the UI could: the row was
 * gone throughout. That is post-deletion disclosure of user content, and it needs the row itself to be the authority,
 * not a cache the deletion may not have reached.
 *
 * **Two questions, because content tasks and edit tasks lose the row differently.** An ordinary content task asks only
 * whether the message still exists and has not been deleted for everyone. A queued EDIT additionally has to ask whether
 * the row still represents the edit it is about to announce: the edit is stored on the row rather than carried in the
 * task, precisely so that no plaintext sits at rest in the task archive while the device is offline, so the row IS the
 * message being sent. A row deleted for everyone, or moved on by a newer edit, is not that message.
 *
 * **Deletion-control tasks are deliberately excluded.** A delete or delete-for-everyone task exists to transmit a
 * deletion, and the soft-deleted tombstone it loads is exactly the row this gate would reject. They keep the ordinary
 * loader; they carry no content to disclose.
 *
 * **Both queued senders ask it, not just the persistent one** (ninth follow-up review, F9-01). A media send waits in a
 * single-worker queue behind every other attachment, so it can be minutes old when it starts, holding a model captured
 * when the message was created - the same staleness a task archived to disk has, arrived at by a different route. One
 * definition rather than two that agree today: the question "may this still transmit user content from that row" has
 * one answer, and a second copy of it is a copy that can drift.
 *
 * Android-free, so the decision that ships is the decision a JVM test executes.
 */
object PersistentTaskRowGate {

    /**
     * Whether a content task, or a queued media send process, may transmit from [current] - the row as the database has
     * it right now, never the instance the caller has been holding.
     *
     * @param current the reloaded row, or `null` if it has gone.
     */
    @JvmStatic
    fun transmits(current: AbstractMessageModel?): Boolean =
        current != null && current.deletedAt == null

    /**
     * The text a queued edit may announce, read from [current], or `null` if it may announce nothing.
     *
     * Refuses a row that has gone or been deleted for everyone, and a row whose edit marker is no longer the one this
     * task committed - which means either that the edit never committed, or that a later edit superseded it and will
     * announce the surviving text itself.
     */
    @JvmStatic
    fun committedEdit(current: AbstractMessageModel?, editedAt: Date): String? {
        if (!transmits(current) || current!!.editedAt?.time != editedAt.time) {
            return null
        }
        return when (current.type) {
            MessageType.TEXT -> current.body
            MessageType.FILE -> current.caption
            else -> null
        }
    }
}
