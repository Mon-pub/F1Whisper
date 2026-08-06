package ch.threema.data.repositories

import android.database.sqlite.SQLiteException
import ch.threema.app.managers.CoreServiceManager
import ch.threema.base.SessionScoped
import ch.threema.base.ThreemaException
import ch.threema.base.utils.getThreemaLogger
import ch.threema.data.ModelTypeCache
import ch.threema.data.models.EditHistoryListModel
import ch.threema.data.models.toDataType
import ch.threema.data.storage.DbEditHistoryEntry
import ch.threema.data.storage.EditHistoryDao
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageType
import java.util.Date

private val logger = getThreemaLogger("EditHistoryRepository")

@SessionScoped
class EditHistoryRepository(
    private val cache: ModelTypeCache<String, EditHistoryListModel>,
    private val editHistoryDao: EditHistoryDao,
    private val coreServiceManager: CoreServiceManager,
) {
    fun getByMessageUid(messageUid: String): EditHistoryListModel? {
        return cache.getOrCreate(messageUid) {
            logger.debug("Load edit history for message {} from database", messageUid)
            EditHistoryListModel(
                editHistoryDao.findAllByMessageUid(messageUid)
                    .map { it.toDataType() },
                coreServiceManager,
            )
        }
    }

    /**
     * Inserts a [DbEditHistoryEntry] into the db.
     * Call this before saving the edited message so the old message is saved in the history.
     *
     * @param message The message model to create an edit history entry from.
     *
     * @throws EditHistoryEntryCreateException if inserting the [DbEditHistoryEntry] in the database failed
     * @throws IllegalStateException if the [message] is not valid to create a [DbEditHistoryEntry] from
     *
     */
    fun createEntry(message: AbstractMessageModel) {
        publishEntry(createEntryDeferred(message))
    }

    /**
     * F1Whisper (seventh fork review, F7-03): insert the history entry WITHOUT touching the in-memory cache, and return
     * what the caller publishes once its transaction has committed.
     *
     * The insert and the edited row's own write are one transaction now, so the insert can still be rolled back after
     * this returns. Updating the cache here would leave the old plaintext visible in the edit-history sheet for a
     * history row that no longer exists on disk - which is exactly the content delete-for-everyone was asked to remove.
     */
    fun createEntryDeferred(message: AbstractMessageModel): PendingHistoryEntry {
        val oldText: String? = when (message.type) {
            MessageType.TEXT -> message.body
            MessageType.FILE -> message.caption
            else -> throw IllegalStateException("Unhandled messageType ${message.type}")
        }

        synchronized(this) {
            try {
                if (message.editedAt == null && message.createdAt == null) {
                    logger.error("Failed to get valid date to create the edit history entry. Fallback to current date.")
                }
                val historyEntry = DbEditHistoryEntry(
                    uid = 0,
                    messageUid = message.uid!!,
                    messageId = message.id,
                    text = oldText,
                    editedAt = message.editedAt ?: message.createdAt ?: Date(),
                )

                val uid = editHistoryDao.create(historyEntry, message)

                return PendingHistoryEntry(message.uid!!, historyEntry.copy(uid = uid.toInt()))
            } catch (exception: SQLiteException) {
                throw EditHistoryEntryCreateException(exception)
            }
        }
    }

    /**
     * Make a committed history entry visible to the in-memory cache. Call only after the transaction that inserted it
     * has committed; see [createEntryDeferred].
     */
    fun publishEntry(pending: PendingHistoryEntry) {
        synchronized(this) {
            cache.get(pending.messageUid)?.addEntry(pending.entry.toDataType())
        }
    }

    /** An inserted, not yet committed history entry. See [createEntryDeferred]. */
    class PendingHistoryEntry internal constructor(
        internal val messageUid: String,
        internal val entry: DbEditHistoryEntry,
    )

    fun deleteByMessageUid(messageUid: String) {
        logger.debug("Delete by message uid {}", messageUid)
        editHistoryDao.deleteAllByMessageUid(messageUid)
        cache.get(messageUid)?.clear()
        cache.remove(messageUid)
    }
}

class EditHistoryEntryCreateException(e: Exception) :
    ThreemaException("Failed to create the edit history entry in the db", e)
