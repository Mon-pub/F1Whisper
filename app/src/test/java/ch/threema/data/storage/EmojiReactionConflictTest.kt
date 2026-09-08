package ch.threema.data.storage

import ch.threema.storage.factories.ContactEmojiReactionModelFactory
import ch.threema.storage.factories.GroupEmojiReactionModelFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper: applying the same emoji reaction twice must be a no-op, not an error.
 *
 * The failure this reproduces: six `EmojiReactionEntryCreateException`s in 13 days of device logs, each wrapping a
 * `SQLiteConstraintException: UNIQUE constraint failed: group_reactions.messageId, senderIdentity, emojiSequence`,
 * logged at ERROR and costing the reaction. The table declares its unique key `ON CONFLICT REPLACE`, but the insert
 * passed an explicit `CONFLICT_ROLLBACK`, and a per-statement conflict algorithm overrides the table-level clause.
 *
 * What is executed here is the SHIPPED `CREATE TABLE` from
 * [GroupEmojiReactionModelFactory.Creator]/[ContactEmojiReactionModelFactory.Creator] against a real SQLite engine,
 * with each of the three candidate conflict algorithms, so the choice between them rests on what the database
 * actually does rather than on a reading of the documentation.
 */
class EmojiReactionConflictTest {

    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    init {
        db.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("CREATE TABLE `m_group_message` (`id` INTEGER PRIMARY KEY AUTOINCREMENT)")
            statement.execute("CREATE TABLE `message` (`id` INTEGER PRIMARY KEY AUTOINCREMENT)")
            statement.execute("INSERT INTO `m_group_message` (`id`) VALUES ($MESSAGE_ID)")
            statement.execute("INSERT INTO `message` (`id`) VALUES ($MESSAGE_ID)")
            GroupEmojiReactionModelFactory.Creator.getCreationStatements().forEach(statement::execute)
            ContactEmojiReactionModelFactory.Creator.getCreationStatements().forEach(statement::execute)
        }
    }

    @AfterTest
    fun tearDown() = db.close()

    // -----------------------------------------------------------------------------------------------------------------------------
    // The three candidate algorithms, on the shipped schema.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the shipped ROLLBACK insert raises on the second apply and stores nothing new`() {
        assertTrue(insert("OR ROLLBACK", reactedAt = FIRST_REACTED_AT))

        assertFailsWith<SQLException> { insert("OR ROLLBACK", reactedAt = SECOND_REACTED_AT) }

        assertEquals(1, rowCount())
        assertEquals(FIRST_REACTED_AT, storedReactedAt())
    }

    @Test
    fun `IGNORE reports the row was not inserted, and leaves the original untouched`() {
        assertTrue(insert("OR IGNORE", reactedAt = FIRST_REACTED_AT))

        assertFalse(insert("OR IGNORE", reactedAt = SECOND_REACTED_AT), "no row was written, so none is reported")

        assertEquals(1, rowCount())
        assertEquals(FIRST_REACTED_AT, storedReactedAt(), "the reaction happened when it first happened")
    }

    /**
     * Why the one-line fix is not REPLACE. `reactedAt` orders the reaction UI, the web reaction buckets and the backup
     * export, so rewriting it on every redelivery would move a reaction around under the user without any user action.
     */
    @Test
    fun `REPLACE would silently rewrite the timestamp of a reaction that already happened`() {
        assertTrue(insert("OR REPLACE", reactedAt = FIRST_REACTED_AT))

        assertTrue(insert("OR REPLACE", reactedAt = SECOND_REACTED_AT))

        assertEquals(1, rowCount())
        assertEquals(SECOND_REACTED_AT, storedReactedAt(), "which is exactly the drift IGNORE avoids")
    }

    /**
     * The table's own `ON CONFLICT REPLACE` was never in force: the per-statement algorithm overrides it. This is the
     * mechanism behind the whole finding, so it is asserted rather than assumed.
     */
    @Test
    fun `a bare insert falls back to the table clause, which is REPLACE`() {
        assertTrue(insert(conflictClause = "", reactedAt = FIRST_REACTED_AT))

        assertTrue(insert(conflictClause = "", reactedAt = SECOND_REACTED_AT))

        assertEquals(1, rowCount())
        assertEquals(SECOND_REACTED_AT, storedReactedAt())
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Why ROLLBACK is the wrong policy even where it is currently survivable.
    // -----------------------------------------------------------------------------------------------------------------------------

    /**
     * No caller traced today holds an open SQLite transaction around this insert - the repository's lock is a monitor,
     * and the restore path uses the separate bulk insert - so the six field errors were six lost reactions, not six
     * silent losses of neighbouring writes. This test pins down what ROLLBACK would cost the first caller that does
     * open one, which is why the policy changes rather than the callers.
     */
    @Test
    fun `ROLLBACK discards an unrelated write made earlier in the same transaction`() {
        insert("OR IGNORE", reactedAt = FIRST_REACTED_AT)

        inTransaction {
            db.createStatement().use { it.execute("INSERT INTO `m_group_message` (`id`) VALUES ($SENTINEL_ID)") }
            assertFailsWith<SQLException> { insert("OR ROLLBACK", reactedAt = SECOND_REACTED_AT) }
        }

        assertEquals(0, countMessages(SENTINEL_ID), "the sentinel was rolled back with the duplicate")
    }

    @Test
    fun `IGNORE leaves an unrelated write in the same transaction alone`() {
        insert("OR IGNORE", reactedAt = FIRST_REACTED_AT)

        inTransaction {
            db.createStatement().use { it.execute("INSERT INTO `m_group_message` (`id`) VALUES ($SENTINEL_ID)") }
            assertFalse(insert("OR IGNORE", reactedAt = SECOND_REACTED_AT))
        }

        assertEquals(1, countMessages(SENTINEL_ID), "and is committed with it")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // IGNORE loosens the duplicate case only.
    // -----------------------------------------------------------------------------------------------------------------------------

    /**
     * SQLite specifies that IGNORE behaves as ABORT for foreign key errors, so a reaction for a message this device
     * does not have still raises and still reaches
     * [ch.threema.data.repositories.EmojiReactionEntryCreateException]. Two tests in
     * `EmojiReactionsRepositoryTest` depend on that.
     */
    @Test
    fun `IGNORE still raises for a reaction whose message does not exist`() {
        assertFailsWith<SQLException> {
            insert("OR IGNORE", reactedAt = FIRST_REACTED_AT, messageId = MISSING_MESSAGE_ID)
        }

        assertEquals(0, rowCount())
    }

    @Test
    fun `a different emoji from the same sender is a separate reaction, not a conflict`() {
        assertTrue(insert("OR IGNORE", reactedAt = FIRST_REACTED_AT))

        assertTrue(insert("OR IGNORE", reactedAt = SECOND_REACTED_AT, emojiSequence = "⚾"))

        assertEquals(2, rowCount())
    }

    @Test
    fun `the same emoji from a different sender is a separate reaction, not a conflict`() {
        assertTrue(insert("OR IGNORE", reactedAt = FIRST_REACTED_AT))

        assertTrue(insert("OR IGNORE", reactedAt = SECOND_REACTED_AT, senderIdentity = "OTHER123"))

        assertEquals(2, rowCount())
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The shipped code runs the algorithm this test measured. Source assertions: the DAO talks to SQLCipher through
    // `android.database`, which is not available in a JVM unit test.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the shipped insert uses IGNORE, not the one that raises nor the one that rewrites`() {
        assertTrue(shippedCreate().contains("conflictAlgorithm = SQLiteDatabase.CONFLICT_IGNORE"))
        assertFalse(shippedCreate().contains("CONFLICT_ROLLBACK"), "the algorithm shown above to raise on a redelivery")
        assertFalse(shippedCreate().contains("CONFLICT_REPLACE"), "the algorithm shown above to rewrite reactedAt")
    }

    @Test
    fun `an ignored insert reports the row that is actually stored`() {
        assertTrue(shippedCreate().contains("return findByKey(table, entry)"))
    }

    @Test
    fun `the cache publishes the persisted row, not the timestamp the attempt invented`() {
        assertTrue(
            shippedCreateEntry().contains("cache.get(reactionMessageIdentifier)?.addEntry(persistedEntry.toDataType())"),
        )
    }

    @Test
    fun `neither inserted nor already present is still a failure`() {
        assertTrue(shippedCreateEntry().contains("throw EmojiReactionEntryCreateException("))
    }

    private fun shippedCreate(): String =
        File("src/main/java/ch/threema/data/storage/EmojiReactionsDaoImpl.kt").readText()
            .substringAfter("override fun create(")
            .substringBefore("private fun findByKey")

    private fun shippedCreateEntry(): String =
        File("src/main/java/ch/threema/data/repositories/EmojiReactionsRepository.kt").readText()
            .substringAfter("fun createEntry(")
            .substringBefore("@Throws(Exception::class)")

    // -----------------------------------------------------------------------------------------------------------------------------

    private fun insert(
        conflictClause: String,
        reactedAt: Long,
        messageId: Int = MESSAGE_ID,
        senderIdentity: String = SENDER,
        emojiSequence: String = EMOJI,
        table: String = GroupEmojiReactionModelFactory.TABLE,
    ): Boolean =
        db.prepareStatement(
            "INSERT $conflictClause INTO `$table` " +
                "(`messageId`, `senderIdentity`, `emojiSequence`, `reactedAt`) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setInt(1, messageId)
            statement.setString(2, senderIdentity)
            statement.setString(3, emojiSequence)
            statement.setLong(4, reactedAt)
            statement.executeUpdate() > 0
        }

    /**
     * A transaction driven with the engine's own statements, the way the production `runTransaction` does, rather than
     * through JDBC's transaction management: `OR ROLLBACK` ends the transaction ITSELF, and a driver that then insists
     * on committing one that is no longer open would report its own bookkeeping error instead of the behaviour under
     * test. Committing a transaction the engine already discarded simply fails, which is the signal, not a problem.
     */
    private fun inTransaction(block: () -> Unit) {
        db.createStatement().use { it.execute("BEGIN") }
        try {
            block()
        } finally {
            runCatching { db.createStatement().use { it.execute("COMMIT") } }
        }
    }

    private fun rowCount(table: String = GroupEmojiReactionModelFactory.TABLE): Int =
        queryFirst("SELECT COUNT(*) FROM `$table`") { it.getInt(1) }

    private fun countMessages(id: Int): Int =
        queryFirst("SELECT COUNT(*) FROM `m_group_message` WHERE `id` = $id") { it.getInt(1) }

    private fun storedReactedAt(table: String = GroupEmojiReactionModelFactory.TABLE): Long =
        queryFirst("SELECT `reactedAt` FROM `$table`") { it.getLong(1) }

    private fun <T> queryFirst(sql: String, read: (ResultSet) -> T): T =
        db.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                assertTrue(resultSet.next(), "no row for: $sql")
                read(resultSet)
            }
        }

    private companion object {
        const val MESSAGE_ID = 7
        const val MISSING_MESSAGE_ID = 999
        const val SENTINEL_ID = 4242
        const val SENDER = "ABCDEFGH"
        const val EMOJI = "⚽"
        const val FIRST_REACTED_AT = 1_700_000_000_000L
        const val SECOND_REACTED_AT = 1_700_000_060_000L
    }
}
