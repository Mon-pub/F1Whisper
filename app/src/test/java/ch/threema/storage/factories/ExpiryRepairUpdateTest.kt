package ch.threema.storage.factories

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (fourth fork review, F4-06): executable tests for the expiry repair write, run against a real SQLite database
 * through sqlite-jdbc, in the [DistributionListMessageSchemaRegressionTest] style.
 *
 * The defect: the repair pass read whole detached message models, changed two timer fields, and handed the result to
 * `MessageService#save`, which is a full-row upsert built from `AbstractMessageModelFactory.buildContentValues` - body,
 * state, deletion timestamp and every other column. Between the read and that write the row could be hard-deleted, in which
 * case `createOrUpdate` INSERTED the stale snapshot back as a new message, or deleted for everyone, in which case the
 * full-row write restored the old body and nulled the deletion timestamp. A maintenance pass whose only job is to compute
 * deadlines was able to recreate content the user had deleted, and to lose a newer deletion.
 *
 * The claim under test is therefore a claim about SQL, and it is tested as SQL: every case executes
 * [AbstractMessageModelFactory.repairExpirySql] - the exact statement that ships, for the real `message` table taken from
 * the schema snapshot - with the production bind order. A test that re-derives the statement would prove nothing about the
 * one that runs on a device.
 *
 * [legacyFullRowUpsertResurrectsADeletedMessage] is the control. It performs the OLD write inline, calls no production
 * code, and shows the deleted message coming back.
 *
 * What this cannot cover, recorded rather than glossed: production runs on SQLCipher through `android.database`, and the
 * real interleaving is two threads in a live service graph. The statement and its predicates are identical; the engine and
 * the scheduler are not.
 */
class ExpiryRepairUpdateTest {
    private lateinit var db: Connection

    private val messageId = 1
    private val startedAt = 1_700_000_000_000L
    private val timerSeconds = 30L
    private val expiresAt = startedAt + timerSeconds * 1000L

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
    // The two races the review named.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a row hard-deleted between the read and the write is not recreated`() {
        insertRepairableRow()
        // The repair pass has read the model. Now the user deletes the message.
        hardDelete()

        val applied = repair()

        assertEquals(0, applied, "the repair must not match a row that has gone")
        assertEquals(0, rowCount(), "and above all it must not insert the stale snapshot back")
    }

    @Test
    fun `a row deleted for everyone between the read and the write keeps its deletion`() {
        insertRepairableRow(body = "the original message")
        // The repair pass has read the model. Now a delete-for-all arrives.
        deleteForEveryone(atUtc = startedAt + 5_000)

        val applied = repair()

        assertEquals(0, applied, "a message deleted for everyone is no longer a repair candidate")
        assertEquals(startedAt + 5_000, deletedAt(), "the newer deletion must survive")
        assertNull(body(), "and the body it removed must not come back")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The write itself.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a genuinely repairable row is stamped`() {
        insertRepairableRow()

        val applied = repair()

        assertEquals(1, applied)
        assertEquals(startedAt, expireStartedAt())
        assertEquals(expiresAt, expiresAtValue())
    }

    @Test
    fun `the repair touches nothing but the two expiry columns`() {
        insertRepairableRow(body = "the original message")
        val before = wholeRowExcept(setOf("expireStartedAtUtc", "expiresAtUtc"))

        repair()

        assertEquals(before, wholeRowExcept(setOf("expireStartedAtUtc", "expiresAtUtc")))
        assertEquals("the original message", body())
    }

    @Test
    fun `a row whose countdown was started by a concurrent mark-as-read is left alone`() {
        // The candidate shape is "read, incoming, no countdown". Mark-as-read gets there first and starts a proper one.
        insertRepairableRow()
        db.createStatement().use {
            it.executeUpdate(
                "UPDATE `message` SET `expireStartedAtUtc` = ${startedAt + 9_000}," +
                    " `expiresAtUtc` = ${startedAt + 9_000 + timerSeconds * 1000L} WHERE `id` = $messageId",
            )
        }

        val applied = repair()

        assertEquals(0, applied, "the row is no longer unreachable, so the repair has nothing to say about it")
        assertEquals(startedAt + 9_000, expireStartedAt(), "mark-as-read's countdown must not be overwritten")
    }

    @Test
    fun `a row whose timer was cleared since the read is left alone`() {
        insertRepairableRow()
        db.createStatement().use {
            it.executeUpdate("UPDATE `message` SET `disappearingTimerSeconds` = NULL WHERE `id` = $messageId")
        }

        assertEquals(0, repair(), "an untimed message is not a repair candidate")
        assertNull(expireStartedAt())
    }

    @Test
    fun `the started-but-unstamped shape is repairable`() {
        // The other half of the candidate query: the countdown began but no deadline was ever derived.
        insertRow(isRead = 0, outbox = 0, expireStartedAt = startedAt, expiresAt = null)

        assertEquals(1, repair())
        assertEquals(expiresAt, expiresAtValue())
    }

    @Test
    fun `an outgoing row is never repaired through the read-but-unstarted branch`() {
        insertRow(isRead = 1, outbox = 1, expireStartedAt = null, expiresAt = null)

        assertEquals(0, repair(), "the unstarted branch is for incoming messages the user has read")
    }

    @Test
    fun `the repair is idempotent`() {
        insertRepairableRow()

        assertEquals(1, repair())
        assertEquals(0, repair(), "a second pass finds nothing left to repair")
        assertEquals(expiresAt, expiresAtValue())
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Legacy control: the old full-row upsert, written out inline.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun legacyFullRowUpsertResurrectsADeletedMessage() {
        insertRepairableRow(body = "the original message")
        hardDelete()
        assertEquals(0, rowCount())

        // The old write: the whole snapshot the pass was holding, upserted by id. createOrUpdate falls back to an INSERT
        // when the id no longer exists, which is exactly what happens here.
        db.prepareStatement(
            "INSERT INTO `message` (`id`, `uid`, `apiMessageId`, `identity`, `outbox`, `body`, `isRead`," +
                " `disappearingTimerSeconds`, `expireStartedAtUtc`, `expiresAtUtc`)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setInt(1, messageId)
            statement.setString(2, "uid-1")
            statement.setString(3, "0011223344556677")
            statement.setString(4, "AAAAAAAA")
            statement.setInt(5, 0)
            statement.setString(6, "the original message")
            statement.setInt(7, 1)
            statement.setLong(8, timerSeconds)
            statement.setLong(9, startedAt)
            statement.setLong(10, expiresAt)
            statement.executeUpdate()
        }

        assertEquals(1, rowCount(), "this is the defect: a deleted message is back")
        assertEquals("the original message", body(), "with its content intact")
    }

    // -----------------------------------------------------------------------------------------------------------------------------

    /** Runs the EXACT statement that ships, with the production bind order. */
    private fun repair(
        newExpireStartedAt: Long? = startedAt,
        newExpiresAt: Long? = expiresAt,
    ): Int =
        db.prepareStatement(AbstractMessageModelFactory.repairExpirySql("message")).use { statement ->
            if (newExpireStartedAt == null) statement.setNull(1, java.sql.Types.BIGINT) else statement.setLong(1, newExpireStartedAt)
            if (newExpiresAt == null) statement.setNull(2, java.sql.Types.BIGINT) else statement.setLong(2, newExpiresAt)
            statement.setInt(3, messageId)
            statement.executeUpdate()
        }

    private fun insertRepairableRow(body: String = "hello") =
        insertRow(isRead = 1, outbox = 0, expireStartedAt = null, expiresAt = null, body = body)

    private fun insertRow(
        isRead: Int,
        outbox: Int,
        expireStartedAt: Long?,
        expiresAt: Long?,
        body: String = "hello",
    ) {
        db.prepareStatement(
            "INSERT INTO `message` (`id`, `uid`, `apiMessageId`, `identity`, `outbox`, `body`, `isRead`," +
                " `disappearingTimerSeconds`, `expireStartedAtUtc`, `expiresAtUtc`)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setInt(1, messageId)
            statement.setString(2, "uid-1")
            statement.setString(3, "0011223344556677")
            statement.setString(4, "AAAAAAAA")
            statement.setInt(5, outbox)
            statement.setString(6, body)
            statement.setInt(7, isRead)
            statement.setLong(8, timerSeconds)
            if (expireStartedAt == null) statement.setNull(9, java.sql.Types.BIGINT) else statement.setLong(9, expireStartedAt)
            if (expiresAt == null) statement.setNull(10, java.sql.Types.BIGINT) else statement.setLong(10, expiresAt)
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

    private fun rowCount(): Int =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM `message`").use {
                it.next()
                it.getInt(1)
            }
        }

    private fun body(): String? = readColumn("body") { it.getString(1) }

    private fun deletedAt(): Long? = readColumn("deletedAtUtc") { it.getLong(1) }

    private fun expireStartedAt(): Long? = readColumn("expireStartedAtUtc") { it.getLong(1) }

    private fun expiresAtValue(): Long? = readColumn("expiresAtUtc") { it.getLong(1) }

    private fun <T> readColumn(column: String, read: (java.sql.ResultSet) -> T): T? =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT `$column` FROM `message` WHERE `id` = $messageId").use { cursor ->
                if (!cursor.next()) return null
                val value = read(cursor)
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
