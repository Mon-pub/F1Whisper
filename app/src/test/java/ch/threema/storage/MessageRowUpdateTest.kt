package ch.threema.storage

import ch.threema.storage.models.AbstractMessageModel
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (fifth fork review, F5-04 / F5-06): executable tests for the conditional non-inserting row write, run against a
 * real SQLite database through sqlite-jdbc against the real `message` table from the schema snapshot.
 *
 * The defect this primitive removes: every volatile-content transition (freeze, first read, listen-once claim/consume/burn,
 * download completion, outgoing terminal state) mutated a detached model and called `MessageService#save`, which builds the
 * WHOLE row and routes it through `createOrUpdate` - an upsert that INSERTS when the original id is gone. A message
 * hard-deleted or deleted-for-everyone while one of those transitions was deciding therefore came back, or lost its newer
 * deletion, or had a concurrently-written field silently reverted.
 *
 * These tests execute [MessageRowUpdate.toSql] with [MessageRowUpdate.bindArgs] - the exact statement and bind order the
 * factory ships - rather than a re-derivation of it. [legacyFullRowUpsertResurrectsADeletedMessage] is the control: it
 * performs the old full-row upsert inline, calls no production code, and shows the deleted message returning.
 *
 * What this cannot cover, recorded rather than glossed: production runs on SQLCipher through `android.database`, and the
 * real interleaving is two threads in a live service graph. The statement, its predicates and its bind order are identical;
 * the engine and the scheduler are not.
 */
class MessageRowUpdateTest {
    private lateinit var db: Connection

    private val messageId = 1
    private val originalBody = """{"lo":true,"loc":false}"""
    private val burnedBody = """{"lo":true,"loc":true}"""

    @BeforeTest
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.execute(loadCreateStatement("message")) }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // It can never insert.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a row hard-deleted before the write is not recreated`() {
        insertRow()
        hardDelete()

        val applied = apply(
            MessageRowUpdate.builder()
                .set(AbstractMessageModel.COLUMN_BODY, burnedBody)
                .build(),
        )

        assertFalse(applied, "a write against a row that has gone must report that it did nothing")
        assertEquals(0, rowCount(), "and above all it must not insert the stale value as a new row")
    }

    @Test
    fun `a row deleted for everyone keeps its deletion`() {
        insertRow()
        deleteForEveryone(atUtc = 1_700_000_005_000L)

        val applied = apply(
            MessageRowUpdate.builder()
                .set(AbstractMessageModel.COLUMN_BODY, burnedBody)
                .build(),
        )

        assertFalse(applied, "deletion for everyone always wins over lifecycle bookkeeping")
        assertEquals(1_700_000_005_000L, deletedAt(), "the newer deletion must survive")
        assertNull(body(), "and the body it removed must not come back")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Compare-and-set.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `an expected value that still holds lets the write through`() {
        insertRow()

        val applied = apply(
            MessageRowUpdate.builder()
                .set(AbstractMessageModel.COLUMN_BODY, burnedBody)
                .expect(AbstractMessageModel.COLUMN_BODY, originalBody)
                .build(),
        )

        assertTrue(applied)
        assertEquals(burnedBody, body())
    }

    @Test
    fun `an expected value that has been superseded refuses the write`() {
        insertRow()
        // A concurrent transition wrote its own flags into the same serialised metadata.
        val concurrent = """{"lo":true,"loc":false,"j":1}"""
        update(AbstractMessageModel.COLUMN_BODY, concurrent)

        val applied = apply(
            MessageRowUpdate.builder()
                .set(AbstractMessageModel.COLUMN_BODY, burnedBody)
                .expect(AbstractMessageModel.COLUMN_BODY, originalBody)
                .build(),
        )

        assertFalse(applied, "the decision was made against a body that no longer exists")
        assertEquals(concurrent, body(), "so the concurrent write must survive untouched")
    }

    @Test
    fun `an expected NULL is expressed as IS NULL and matches`() {
        insertRow(expireStartedAt = null)

        val update = MessageRowUpdate.builder()
            .set(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT, 1_700_000_000_000L)
            .expect(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT, null)
            .build()

        assertTrue(
            update.toSql("message").contains("`expireStartedAtUtc` IS NULL"),
            "a null expectation must become IS NULL; `= NULL` is never true and would silently reject every row",
        )
        assertTrue(apply(update))
        assertEquals(1_700_000_000_000L, expireStartedAt())
    }

    @Test
    fun `an expected NULL refuses a row whose column has since been set`() {
        insertRow(expireStartedAt = 1_699_999_000_000L)

        val applied = apply(
            MessageRowUpdate.builder()
                .set(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT, 1_700_000_000_000L)
                .expect(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT, null)
                .build(),
        )

        assertFalse(applied, "a countdown started by a concurrent first read must not be restamped")
        assertEquals(1_699_999_000_000L, expireStartedAt())
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // It writes only what it names.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `every column the update does not name is left exactly as it was`() {
        insertRow()
        val before = wholeRowExcept(setOf(AbstractMessageModel.COLUMN_EXPIRES_AT))

        assertTrue(
            apply(
                MessageRowUpdate.builder()
                    .set(AbstractMessageModel.COLUMN_EXPIRES_AT, 1_700_000_030_000L)
                    .build(),
            ),
        )

        assertEquals(before, wholeRowExcept(setOf(AbstractMessageModel.COLUMN_EXPIRES_AT)))
        assertEquals(1_700_000_030_000L, expiresAt())
    }

    @Test
    fun `booleans dates and boxed integers all reach the column in the stored encoding`() {
        insertRow()

        assertTrue(
            apply(
                MessageRowUpdate.builder()
                    .set(AbstractMessageModel.COLUMN_IS_READ, true)
                    .set(AbstractMessageModel.COLUMN_READ_AT, java.util.Date(1_700_000_012_000L))
                    .set(AbstractMessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS, 30)
                    .build(),
            ),
        )

        assertEquals(1L, longOf(AbstractMessageModel.COLUMN_IS_READ), "booleans are stored as 1/0")
        assertEquals(1_700_000_012_000L, longOf(AbstractMessageModel.COLUMN_READ_AT), "dates are stored as epoch millis")
        assertEquals(30L, longOf(AbstractMessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS))
    }

    @Test
    fun `an enum is refused rather than guessed at`() {
        // `state` is stored as a string and `forwardSecurityMode` as an int, so there is no single correct conversion.
        // Guessing one would put a wrong value in a column no test reads back through the same path.
        assertFailsWith<IllegalArgumentException> {
            MessageRowUpdate.builder().set(AbstractMessageModel.COLUMN_STATE, ExampleEnum.SENT)
        }
    }

    @Test
    fun `an update that assigns nothing is a caller bug`() {
        assertFailsWith<IllegalStateException> {
            MessageRowUpdate.builder().expect(AbstractMessageModel.COLUMN_BODY, originalBody).build().toSql("message")
        }
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Legacy control: the full-row upsert.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun legacyFullRowUpsertResurrectsADeletedMessage() {
        insertRow()
        hardDelete()

        // The old shape: the transition holds a detached model and hands the WHOLE row to createOrUpdate, which finds no
        // row with that id and therefore inserts.
        db.prepareStatement(
            "INSERT INTO `message` (`id`, `uid`, `identity`, `outbox`, `type`, `body`, `isRead`, `isSaved`," +
                " `isStatusMessage`, `isQueued`, `createdAtUtc`) VALUES (?, ?, ?, 0, 1, ?, 0, 1, 0, 0, ?)",
        ).use { statement ->
            statement.setInt(1, messageId)
            statement.setString(2, "uid-1")
            statement.setString(3, "ECHOECHO")
            statement.setString(4, burnedBody)
            statement.setLong(5, 1_700_000_000_000L)
            statement.executeUpdate()
        }

        assertEquals(1, rowCount(), "this is the defect: a lifecycle write recreated a message the user had deleted")
    }

    private enum class ExampleEnum { SENT }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------------------------------------------

    /** Runs the production statement and bind order, exactly as {@code AbstractMessageModelFactory.applyRowUpdate} does. */
    private fun apply(update: MessageRowUpdate): Boolean =
        db.prepareStatement(update.toSql("message")).use { statement ->
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

    private fun insertRow(expireStartedAt: Long? = 1_700_000_000_000L) {
        db.prepareStatement(
            "INSERT INTO `message` (`id`, `uid`, `identity`, `outbox`, `type`, `body`, `isRead`, `isSaved`," +
                " `isStatusMessage`, `isQueued`, `createdAtUtc`, `disappearingTimerSeconds`, `expireStartedAtUtc`)" +
                " VALUES (?, ?, ?, 0, 1, ?, 0, 1, 0, 0, ?, 30, ?)",
        ).use { statement ->
            statement.setInt(1, messageId)
            statement.setString(2, "uid-1")
            statement.setString(3, "ECHOECHO")
            statement.setString(4, originalBody)
            statement.setLong(5, 1_700_000_000_000L)
            if (expireStartedAt == null) {
                statement.setNull(6, java.sql.Types.BIGINT)
            } else {
                statement.setLong(6, expireStartedAt)
            }
            statement.executeUpdate()
        }
    }

    private fun hardDelete() {
        db.createStatement().use { it.executeUpdate("DELETE FROM `message` WHERE `id` = $messageId") }
    }

    private fun deleteForEveryone(atUtc: Long) {
        db.createStatement().use {
            it.executeUpdate("UPDATE `message` SET `deletedAtUtc` = $atUtc, `body` = NULL WHERE `id` = $messageId")
        }
    }

    private fun update(column: String, value: String) {
        db.prepareStatement("UPDATE `message` SET `$column` = ? WHERE `id` = ?").use { statement ->
            statement.setString(1, value)
            statement.setInt(2, messageId)
            statement.executeUpdate()
        }
    }

    private fun rowCount(): Int =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM `message` WHERE `id` = $messageId").use { cursor ->
                cursor.next()
                cursor.getInt(1)
            }
        }

    private fun body(): String? = stringOf(AbstractMessageModel.COLUMN_BODY)

    private fun deletedAt(): Long? = longOf(AbstractMessageModel.COLUMN_DELETED_AT)

    private fun expiresAt(): Long? = longOf(AbstractMessageModel.COLUMN_EXPIRES_AT)

    private fun expireStartedAt(): Long? = longOf(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT)

    private fun stringOf(column: String): String? =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT `$column` FROM `message` WHERE `id` = $messageId").use { cursor ->
                assertTrue(cursor.next(), "row must exist")
                cursor.getString(1)
            }
        }

    private fun longOf(column: String): Long? =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT `$column` FROM `message` WHERE `id` = $messageId").use { cursor ->
                assertTrue(cursor.next(), "row must exist")
                val value = cursor.getLong(1)
                if (cursor.wasNull()) null else value
            }
        }

    /** Every column of the row except [excluded], so "touched nothing else" can be asserted rather than assumed. */
    private fun wholeRowExcept(excluded: Set<String>): Map<String, String?> =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM `message` WHERE `id` = $messageId").use { cursor ->
                assertTrue(cursor.next(), "row must exist")
                val meta = cursor.metaData
                (1..meta.columnCount)
                    .map { index -> meta.getColumnName(index) to cursor.getString(index) }
                    .filterNot { (name, _) -> name in excluded }
                    .toMap()
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
}
