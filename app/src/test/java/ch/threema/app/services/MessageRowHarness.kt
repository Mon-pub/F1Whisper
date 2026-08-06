package ch.threema.app.services

import ch.threema.storage.MessageRowUpdate
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.group.GroupMessageModel
import java.sql.Connection
import java.sql.DriverManager
import java.util.Date
import kotlin.test.assertTrue

/**
 * F1Whisper (sixth fork review): a real SQLite database holding the real message tables, plus the two operations the
 * production code performs on them - the conditional column-scoped write, and the full-row upsert it replaced.
 *
 * Shared by the sixth review's tests so that each of them asserts against ONE definition of "what the database does",
 * and so that the legacy control every one of them needs is written once. The statements executed are the ones the
 * shipped code builds ([MessageRowUpdate.toSql] with [MessageRowUpdate.bindArgs], and
 * `AbstractMessageModelFactory.deleteIfStillDueSql`), never a re-derivation of them.
 *
 * **What it cannot cover, recorded rather than glossed.** Production runs on SQLCipher through `android.database`, and
 * the media bodies are serialised by `android.util.JsonWriter`, which is not available in a JVM unit test. Bodies here
 * are therefore opaque strings: what is asserted is which row a statement matches and which columns it changes, which is
 * exactly where the sixth review's defects live.
 */
class MessageRowHarness(vararg tables: String) {
    val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    init {
        tables.forEach { table ->
            db.createStatement().use { it.execute(loadCreateStatement(table)) }
        }
    }

    fun close() {
        db.close()
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The two shipped operations.
    // -----------------------------------------------------------------------------------------------------------------------------

    /** Runs the production statement and bind order, exactly as `AbstractMessageModelFactory.applyRowUpdate` does. */
    fun apply(table: String, messageId: Int, update: MessageRowUpdate): Boolean =
        db.prepareStatement(update.toSql(table)).use { statement ->
            update.bindArgs(messageId).forEachIndexed { index, value ->
                when (value) {
                    null -> statement.setObject(index + 1, null)
                    is Long -> statement.setLong(index + 1, value)
                    is ByteArray -> statement.setBytes(index + 1, value)
                    else -> statement.setString(index + 1, value.toString())
                }
            }
            statement.executeUpdate() > 0
        }

    /** Runs the production expiry claim, exactly as `AbstractMessageModelFactory.deleteIfStillDue` does. */
    fun claimIfStillDue(table: String, messageId: Int, expireStartedAt: Long?, expiresAt: Long?, nowMillis: Long): Boolean {
        if (expireStartedAt == null || expiresAt == null) {
            return false
        }
        return db.prepareStatement(deleteIfStillDueSql(table)).use { statement ->
            statement.setLong(1, messageId.toLong())
            statement.setLong(2, expireStartedAt)
            statement.setLong(3, expiresAt)
            statement.setLong(4, nowMillis)
            statement.executeUpdate() > 0
        }
    }

    /**
     * The full-row persistence that ships since the seventh review: for a positive id an UPDATE ONLY, reporting
     * whether it wrote a row.
     *
     * The existence decision IS the write, so nothing can insert and nothing can slip between "does it exist" and
     * "write it". Contrast [legacyFullRowUpsert], which asked first and then acted on the answer.
     *
     * Since the eighth review the WHERE clause is the shipped [contentRowWhere], which also refuses a row that has been
     * deleted for everyone; it is taken from the factory rather than restated here.
     */
    fun fullRowUpdate(table: String, model: AbstractMessageModel): Boolean {
        val columns = fullRowColumns(model)
        val sql = "UPDATE `$table` SET " + columns.keys.joinToString(", ") { "`$it` = ?" } + " WHERE " + contentRowWhere()
        return db.prepareStatement(sql).use { statement ->
            columns.values.forEachIndexed { index, value -> bind(statement, index + 1, value) }
            statement.setInt(columns.size + 1, model.id)
            statement.executeUpdate() > 0
        }
    }

    /**
     * The operation every conversion in these waves removes: build the WHOLE row from a detached model and upsert it.
     *
     * Faithful to what `createOrUpdate` did before the guard was added - update when the row is there, insert when it is
     * not - so a control can show both halves of the defect: the snapshot overwriting newer columns, and the vanished
     * row coming back.
     */
    fun legacyFullRowUpsert(table: String, model: AbstractMessageModel) {
        val present = rowCount(table, model.id) > 0
        val columns = fullRowColumns(model)

        val sql = if (present) {
            "UPDATE `$table` SET " + columns.keys.joinToString(", ") { "`$it` = ?" } + " WHERE `id` = ?"
        } else {
            "INSERT INTO `$table` (" + (columns.keys + "id").joinToString(", ") { "`$it`" } + ") VALUES (" +
                (columns.keys + "id").joinToString(", ") { "?" } + ")"
        }
        db.prepareStatement(sql).use { statement ->
            columns.values.forEachIndexed { index, value -> bind(statement, index + 1, value) }
            statement.setInt(columns.size + 1, model.id)
            statement.executeUpdate()
        }
    }

    /** Every column a full-row write builds from a detached model, in the order `buildContentValues` produces them. */
    private fun fullRowColumns(model: AbstractMessageModel): LinkedHashMap<String, Any?> =
        linkedMapOf<String, Any?>(
            "uid" to model.uid,
            "apiMessageId" to model.apiMessageId,
            "identity" to model.identity,
            "outbox" to if (model.isOutbox) 1L else 0L,
            "type" to model.type?.ordinal?.toLong(),
            "body" to model.body,
            "caption" to model.caption,
            "isRead" to if (model.isRead) 1L else 0L,
            "isSaved" to if (model.isSaved) 1L else 0L,
            "state" to model.state?.toString(),
            "postedAtUtc" to model.rawPostedAt?.time,
            "createdAtUtc" to model.createdAt?.time,
            "modifiedAtUtc" to model.modifiedAt?.time,
            "isStatusMessage" to 0L,
            "deliveredAtUtc" to model.deliveredAt?.time,
            "readAtUtc" to model.readAt?.time,
            "editedAtUtc" to model.editedAt?.time,
            "deletedAtUtc" to model.deletedAt?.time,
            "expiresAtUtc" to model.expiresAt,
            "expireStartedAtUtc" to model.expireStartedAt,
            "disappearingTimerSeconds" to model.disappearingTimerSeconds?.toLong(),
            "displayTags" to model.displayTags.toLong(),
        ).also { columns ->
            if (model is GroupMessageModel) {
                columns["groupId"] = model.groupId.toLong()
                columns["groupMessageStates"] = MessageLifecycleUpdates.serialiseGroupMessageStates(model.groupMessageStates)
            }
        }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Rows in, models out.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Suppress("LongParameterList")
    fun insertContactRow(
        messageId: Int,
        body: String? = "hello",
        caption: String? = null,
        isRead: Boolean = false,
        outbox: Boolean = false,
        state: MessageState? = null,
        createdAt: Long = BASE_TIME,
        timerSeconds: Int? = 30,
        expireStartedAt: Long? = null,
        expiresAt: Long? = null,
        displayTags: Int = 0,
    ) = insertRow(
        table = "message",
        messageId = messageId,
        body = body,
        caption = caption,
        isRead = isRead,
        outbox = outbox,
        state = state,
        createdAt = createdAt,
        timerSeconds = timerSeconds,
        expireStartedAt = expireStartedAt,
        expiresAt = expiresAt,
        displayTags = displayTags,
        groupId = null,
    )

    @Suppress("LongParameterList")
    fun insertGroupRow(
        messageId: Int,
        body: String? = "hello",
        caption: String? = null,
        isRead: Boolean = false,
        outbox: Boolean = true,
        state: MessageState? = null,
        createdAt: Long = BASE_TIME,
        timerSeconds: Int? = 30,
        expireStartedAt: Long? = null,
        expiresAt: Long? = null,
        displayTags: Int = 0,
    ) = insertRow(
        table = "m_group_message",
        messageId = messageId,
        body = body,
        caption = caption,
        isRead = isRead,
        outbox = outbox,
        state = state,
        createdAt = createdAt,
        timerSeconds = timerSeconds,
        expireStartedAt = expireStartedAt,
        expiresAt = expiresAt,
        displayTags = displayTags,
        groupId = 7,
    )

    @Suppress("LongParameterList")
    private fun insertRow(
        table: String,
        messageId: Int,
        body: String?,
        caption: String?,
        isRead: Boolean,
        outbox: Boolean,
        state: MessageState?,
        createdAt: Long,
        timerSeconds: Int?,
        expireStartedAt: Long?,
        expiresAt: Long?,
        displayTags: Int,
        groupId: Int?,
    ) {
        val columns = linkedMapOf<String, Any?>(
            "id" to messageId.toLong(),
            "uid" to "uid-$messageId",
            "apiMessageId" to "api-$messageId",
            "identity" to "ECHOECHO",
            "outbox" to if (outbox) 1L else 0L,
            "type" to MessageType.TEXT.ordinal.toLong(),
            "body" to body,
            "caption" to caption,
            "isRead" to if (isRead) 1L else 0L,
            "isSaved" to 1L,
            "isStatusMessage" to 0L,
            "isQueued" to 0L,
            "state" to state?.toString(),
            "createdAtUtc" to createdAt,
            "sortAtUtc" to createdAt,
            "disappearingTimerSeconds" to timerSeconds?.toLong(),
            "expireStartedAtUtc" to expireStartedAt,
            "expiresAtUtc" to expiresAt,
            "displayTags" to displayTags.toLong(),
        ).also { columns ->
            if (groupId != null) {
                columns["groupId"] = groupId.toLong()
            }
        }
        val sql = "INSERT INTO `$table` (" + columns.keys.joinToString(", ") { "`$it`" } + ") VALUES (" +
            columns.keys.joinToString(", ") { "?" } + ")"
        db.prepareStatement(sql).use { statement ->
            columns.values.forEachIndexed { index, value -> bind(statement, index + 1, value) }
            statement.executeUpdate()
        }
    }

    /**
     * Read row [messageId] back into a model, the way `AbstractMessageModelFactory.convert` does through a real cursor.
     *
     * Returns null when the row has gone, which is what `reloadPersistedModel` reports and what every caller in this
     * wave has to handle without falling back to the instance it holds.
     */
    fun readModel(table: String, messageId: Int): AbstractMessageModel? =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM `$table` WHERE `id` = $messageId").use { cursor ->
                if (!cursor.next()) {
                    return null
                }
                val model: AbstractMessageModel = if (table == "m_group_message") {
                    GroupMessageModel().also { it.groupId = cursor.getInt("groupId") }
                } else {
                    MessageModel()
                }
                model.id = messageId
                model.uid = cursor.getString("uid")
                model.apiMessageId = cursor.getString("apiMessageId")
                model.identity = cursor.getString("identity")
                model.isOutbox = cursor.getInt("outbox") == 1
                model.type = MessageType.entries[cursor.getInt("type")]
                model.body = cursor.getString("body")
                // As `convert` does: the caption is its own column, and a media message's is user content the eighth
                // review's finding restores into a tombstone. A reader that dropped it would let a writer wipe it.
                model.caption = cursor.getString("caption")
                model.isRead = cursor.getInt("isRead") == 1
                model.isSaved = cursor.getInt("isSaved") == 1
                model.state = cursor.getString("state")?.let(MessageState::valueOf)
                model.postedAt = nullableLong(cursor, "postedAtUtc")?.let(::Date)
                model.createdAt = nullableLong(cursor, "createdAtUtc")?.let(::Date)
                model.modifiedAt = nullableLong(cursor, "modifiedAtUtc")?.let(::Date)
                model.deliveredAt = nullableLong(cursor, "deliveredAtUtc")?.let(::Date)
                model.readAt = nullableLong(cursor, "readAtUtc")?.let(::Date)
                model.editedAt = nullableLong(cursor, "editedAtUtc")?.let(::Date)
                model.deletedAt = nullableLong(cursor, "deletedAtUtc")?.let(::Date)
                model.expiresAt = nullableLong(cursor, "expiresAtUtc")
                model.expireStartedAt = nullableLong(cursor, "expireStartedAtUtc")
                model.disappearingTimerSeconds = nullableLong(cursor, "disappearingTimerSeconds")?.toInt()
                model.displayTags = cursor.getInt("displayTags")
                if (model is GroupMessageModel) {
                    model.groupMessageStates = parseStates(cursor.getString("groupMessageStates"))
                }
                model
            }
        }

    /**
     * The ids the shipped unread query returns for one conversation, oldest column order irrelevant: what is asserted
     * is membership of the unread set.
     *
     * Built from [unreadRowWhere], the fragment BOTH factories' `countUnreadMessages` and `getUnreadMessages` run, so a
     * test cannot pass against a predicate the app does not use.
     */
    fun unreadIds(table: String, scopeColumn: String, scopeValue: Any): List<Int> =
        db.prepareStatement("SELECT `id` FROM `$table` WHERE `$scopeColumn` = ? AND " + unreadRowWhere()).use { statement ->
            bind(statement, 1, if (scopeValue is Int) scopeValue.toLong() else scopeValue)
            statement.executeQuery().use { cursor ->
                buildList {
                    while (cursor.next()) {
                        add(cursor.getInt(1))
                    }
                }
            }
        }

    /** The count the conversation list and the divider label read, through the same shipped fragment. */
    fun countUnread(table: String, scopeColumn: String, scopeValue: Any): Int =
        unreadIds(table, scopeColumn, scopeValue).size

    /**
     * The same count under an arbitrary [where], so a test can run the predicate as it SHIPPED BEFORE a change next to
     * the one that ships now. Only the legacy controls use this; everything else goes through [unreadIds].
     */
    fun countMatching(table: String, scopeColumn: String, scopeValue: Any, where: String): Int =
        db.prepareStatement("SELECT COUNT(*) FROM `$table` WHERE `$scopeColumn` = ? AND $where").use { statement ->
            bind(statement, 1, if (scopeValue is Int) scopeValue.toLong() else scopeValue)
            statement.executeQuery().use { cursor ->
                cursor.next()
                cursor.getInt(1)
            }
        }

    /** Fixture: the row is a status message (a call notice, a group event), which is never unread. */
    fun markStatusMessage(table: String, messageId: Int) {
        db.createStatement().use { it.executeUpdate("UPDATE `$table` SET `isStatusMessage` = 1 WHERE `id` = $messageId") }
    }

    /** Fixture: the row is not yet saved, the state an incoming message passes through before it is complete. */
    fun markUnsaved(table: String, messageId: Int) {
        db.createStatement().use { it.executeUpdate("UPDATE `$table` SET `isSaved` = 0 WHERE `id` = $messageId") }
    }

    fun requireModel(table: String, messageId: Int): AbstractMessageModel {
        val model = readModel(table, messageId)
        assertTrue(model != null, "row $messageId must still exist in $table")
        return model
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Assertions read the columns directly, never through the model, so a wrong write cannot be hidden by a right read.
    // -----------------------------------------------------------------------------------------------------------------------------

    fun rowCount(table: String, messageId: Int): Int =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM `$table` WHERE `id` = $messageId").use { cursor ->
                cursor.next()
                cursor.getInt(1)
            }
        }

    fun longOf(table: String, messageId: Int, column: String): Long? =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT `$column` FROM `$table` WHERE `id` = $messageId").use { cursor ->
                assertTrue(cursor.next(), "row $messageId must exist in $table")
                val value = cursor.getLong(1)
                if (cursor.wasNull()) null else value
            }
        }

    fun stringOf(table: String, messageId: Int, column: String): String? =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT `$column` FROM `$table` WHERE `id` = $messageId").use { cursor ->
                assertTrue(cursor.next(), "row $messageId must exist in $table")
                cursor.getString(1)
            }
        }

    fun hardDelete(table: String, messageId: Int) {
        claimHardDelete(table, messageId)
    }

    /**
     * The hard-deletion CLAIM, as `MessageServiceImpl.remove` now performs it: delete the row first and report whether
     * this caller is the one that owns everything the row governed.
     */
    fun claimHardDelete(table: String, messageId: Int): Boolean =
        db.createStatement().use { it.executeUpdate("DELETE FROM `$table` WHERE `id` = $messageId") > 0 }

    /**
     * Delete for everyone, through the statement `MessageServiceImpl.deleteMessageContentsAndRelatedData` runs.
     *
     * It used to be a hand-written UPDATE that cleared only the body. The caption is half of what the eighth review's
     * finding puts back into a tombstone, so the tombstone a test starts from has to be the tombstone the app makes.
     */
    fun deleteForEveryone(table: String, messageId: Int, atUtc: Long): Boolean =
        apply(
            table,
            messageId,
            MessageLifecycleUpdates.deletedForEveryone(Date(atUtc), table == GROUP_TABLE),
        )

    private fun parseStates(serialised: String?): MutableMap<String, Any>? {
        if (serialised == null) {
            return null
        }
        val json = org.json.JSONObject(serialised)
        return json.keys().asSequence().associateWithTo(mutableMapOf()) { key -> json.get(key) }
    }

    private fun nullableLong(cursor: java.sql.ResultSet, column: String): Long? {
        val value = cursor.getLong(column)
        return if (cursor.wasNull()) null else value
    }

    private fun bind(statement: java.sql.PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> statement.setObject(index, null)
            is Long -> statement.setLong(index, value)
            is Int -> statement.setInt(index, value)
            else -> statement.setString(index, value.toString())
        }
    }

    private fun loadCreateStatement(table: String): String {
        javaClass.getResourceAsStream("/database/schema.sql")!!.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith("CREATE TABLE `$table`(")) {
                    return line
                }
            }
        }
        error("no CREATE TABLE for `$table` in the schema snapshot")
    }

    companion object {
        const val CONTACT_TABLE = "message"
        const val GROUP_TABLE = "m_group_message"
        const val BASE_TIME = 1_700_000_000_000L

        /**
         * The claim statement the expiry paths run, taken from the factory that ships it rather than copied here.
         */
        fun deleteIfStillDueSql(table: String): String =
            ch.threema.storage.factories.MessageFactoryTestAccess.deleteIfStillDueSql(table)

        /**
         * The WHERE clause every full-row save runs, taken from the factory that ships it: the row id AND the deletion
         * boundary the eighth review's finding needed it to carry.
         */
        fun contentRowWhere(): String =
            ch.threema.storage.factories.MessageFactoryTestAccess.contentRowWhere()

        /**
         * The WHERE clause both factories' unread count and unread list run, taken from the factory that ships it.
         */
        fun unreadRowWhere(): String =
            ch.threema.storage.factories.MessageFactoryTestAccess.unreadRowWhere()
    }
}
