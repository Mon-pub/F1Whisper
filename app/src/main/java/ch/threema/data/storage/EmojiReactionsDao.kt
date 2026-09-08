package ch.threema.data.storage

import android.database.sqlite.SQLiteException
import ch.threema.app.utils.ThrowingConsumer
import ch.threema.domain.types.IdentityString
import ch.threema.storage.models.AbstractMessageModel

interface EmojiReactionsDao {
    /**
     * Insert a new emoji reaction, leaving an identical one that is already there untouched.
     *
     * F1Whisper: applying the same reaction twice used to raise. The table declares
     * `UNIQUE(messageId, senderIdentity, emojiSequence) ON CONFLICT REPLACE`, but the insert overrode that with an
     * explicit `CONFLICT_ROLLBACK`, and a per-statement conflict algorithm wins over the table-level clause. Reapplying
     * a reaction is not an error - a redelivered message, two linked devices reacting, or a repeated ACK all produce
     * it - so the duplicate is ignored rather than raised. It is ignored rather than REPLACEd because REPLACE deletes
     * and reinserts with a fresh [DbEmojiReaction.reactedAt], and that timestamp orders the reaction UI, the web
     * reaction buckets and the backup export.
     *
     * @param entry The entry to add for the reaction
     * @param messageModel The message referenced by the reaction entry
     *
     * @return the reaction as it is now STORED: [entry] when the row was inserted, the row that was already there when
     * the insert was ignored, and `null` when the message cannot hold reactions or the row could not be read back.
     *
     * @throws SQLiteException if the insert fails for any reason other than the unique constraint
     * @throws ch.threema.data.repositories.EmojiReactionEntryCreateException if inserting the [DbEmojiReaction] in the database failed
     */
    fun create(entry: DbEmojiReaction, messageModel: AbstractMessageModel): DbEmojiReaction?

    /**
     * Remove an emoji reaction from the database
     */
    fun remove(entry: DbEmojiReaction, messageModel: AbstractMessageModel)

    /**
     * Delete all reactions referred to by the specified message id
     */
    fun deleteAllByMessage(messageModel: AbstractMessageModel)

    /**
     * Find all reactions referred to by the specified message id
     */
    fun findAllByMessage(messageModel: AbstractMessageModel): List<DbEmojiReaction>

    /**
     * Delete all reactions from the database.
     */
    fun deleteAll()

    fun getContactReactionsCount(): Long

    fun getGroupReactionsCount(): Long

    /**
     * Iteration is ordered by the id of the referenced messages.
     */
    fun iterateAllContactBackupReactions(consumer: ThrowingConsumer<BackupContactReaction>)

    /**
     * Iteration is ordered by the id of the referenced messages.
     */
    fun iterateAllGroupBackupReactions(consumer: ThrowingConsumer<BackupGroupReaction>)

    fun insertContactReactionsInTransaction(block: TransactionalReactionInsertScope)

    fun insertGroupReactionsInTransaction(block: TransactionalReactionInsertScope)

    data class BackupContactReaction(
        val contactIdentity: IdentityString,
        val apiMessageId: String,
        val senderIdentity: IdentityString,
        val emojiSequence: String,
        val reactedAt: Long,
    )

    data class BackupGroupReaction(
        val apiGroupId: String,
        val groupCreatorIdentity: IdentityString,
        val apiMessageId: String,
        val senderIdentity: IdentityString,
        val emojiSequence: String,
        val reactedAt: Long,
    )

    fun interface ReactionInsertHandle {
        fun insert(entry: DbEmojiReaction)
    }

    fun interface TransactionalReactionInsertScope {
        @Throws(Exception::class)
        fun runInserts(handle: ReactionInsertHandle)
    }
}
