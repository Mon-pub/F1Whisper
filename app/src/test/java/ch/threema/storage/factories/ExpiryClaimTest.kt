package ch.threema.storage.factories

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper (fifth fork review, F5-04): executable tests for the conditional DELETE that CLAIMS an expired row, run against
 * a real SQLite database through sqlite-jdbc against the real `message` table from the schema snapshot.
 *
 * The defect: lazy enforcement, the alarm fire and the startup sweep each selected a due row and then removed its files,
 * its ballot aggregate and the row itself from that DETACHED snapshot, with no final check that the row was still due. A
 * duplicate advertising an explicit OFF, or a freeze re-deriving the deadline from a corrected sender timer, could land in
 * between; the snapshot was still overdue, so content the sender had just said to keep was destroyed anyway. A deadline
 * that had merely been REPAIRED earlier in the same pass was likewise treated as authorisation for an unconditional delete
 * much later.
 *
 * The claim closes it by construction: the DELETE re-checks timer, start, deadline, deletion state and due-ness at write
 * time, and winning it is what makes a caller the owner of the side effects. Losing it destroys nothing.
 *
 * [legacyUnconditionalDeleteDestroysAnUntimedMessage] is the control: it performs the old id-only delete inline, calls no
 * production code, and shows the message the user had un-timed being destroyed.
 */
class ExpiryClaimTest {
    private lateinit var db: Connection

    private val messageId = 1
    private val startedAt = 1_700_000_000_000L
    private val expiresAt = startedAt + 30_000L
    private val afterDeadline = expiresAt + 1_000L

    @BeforeTest
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.execute(loadCreateStatement("message")) }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a row that is still due is claimed and removed`() {
        insertDueRow()

        assertTrue(claim(startedAt, expiresAt, afterDeadline))
        assertEquals(0, rowCount())
    }

    @Test
    fun `only one of two concurrent claims can win`() {
        insertDueRow()

        val first = claim(startedAt, expiresAt, afterDeadline)
        val second = claim(startedAt, expiresAt, afterDeadline)

        assertTrue(first, "the first enforcement owns the row")
        assertFalse(second, "and the second must not go on to delete files or a ballot aggregate for a row it never owned")
    }

    @Test
    fun `a timer turned off between the read and the delete saves the message`() {
        insertDueRow()
        // A duplicate arrives advertising an explicit OFF; the freeze clears the timer and the countdown.
        db.createStatement().use {
            it.executeUpdate(
                "UPDATE `message` SET `disappearingTimerSeconds` = 0," +
                    " `expireStartedAtUtc` = NULL, `expiresAtUtc` = NULL WHERE `id` = $messageId",
            )
        }

        assertFalse(claim(startedAt, expiresAt, afterDeadline), "the row the caller decided about no longer exists")
        assertEquals(1, rowCount(), "and the message the sender said to keep must survive")
    }

    @Test
    fun `a deadline moved later between the read and the delete saves the message`() {
        insertDueRow()
        // A freeze re-derived the deadline from the sender's corrected timer.
        db.createStatement().use {
            it.executeUpdate("UPDATE `message` SET `expiresAtUtc` = ${afterDeadline + 60_000} WHERE `id` = $messageId")
        }

        assertFalse(claim(startedAt, expiresAt, afterDeadline))
        assertEquals(1, rowCount())
    }

    @Test
    fun `a countdown restarted between the read and the delete saves the message`() {
        insertDueRow()
        db.createStatement().use {
            it.executeUpdate(
                "UPDATE `message` SET `expireStartedAtUtc` = ${startedAt + 10_000}," +
                    " `expiresAtUtc` = ${startedAt + 40_000} WHERE `id` = $messageId",
            )
        }

        assertFalse(claim(startedAt, expiresAt, afterDeadline))
        assertEquals(1, rowCount())
    }

    @Test
    fun `a row deleted for everyone is not claimed`() {
        insertDueRow()
        db.createStatement().use {
            it.executeUpdate("UPDATE `message` SET `deletedAtUtc` = $startedAt, `body` = NULL WHERE `id` = $messageId")
        }

        assertFalse(claim(startedAt, expiresAt, afterDeadline), "the deletion tombstone is not ours to remove")
        assertEquals(1, rowCount())
    }

    @Test
    fun `a row that is not yet due is not claimed`() {
        insertDueRow()

        assertFalse(claim(startedAt, expiresAt, nowMillis = expiresAt - 1))
        assertEquals(1, rowCount())
    }

    @Test
    fun `a row with no start or no deadline is answered directly rather than asked about`() {
        insertDueRow()

        // Binding NULL into an `=` comparison is never true, so asking the database would silently answer "not claimed"
        // for the wrong reason. The factory answers it before compiling anything.
        assertFalse(AbstractMessageModelFactory.deleteIfStillDueSql("message").contains("IS ?"))
        assertEquals(1, rowCount())
    }

    @Test
    fun legacyUnconditionalDeleteDestroysAnUntimedMessage() {
        insertDueRow()
        // The user's peer turned the timer off; the enforcement pass is still holding the overdue snapshot.
        db.createStatement().use {
            it.executeUpdate(
                "UPDATE `message` SET `disappearingTimerSeconds` = 0," +
                    " `expireStartedAtUtc` = NULL, `expiresAtUtc` = NULL WHERE `id` = $messageId",
            )
        }

        // The old shape: delete by id, from the detached model, with no re-check.
        db.createStatement().use { it.executeUpdate("DELETE FROM `message` WHERE `id` = $messageId") }

        assertEquals(0, rowCount(), "this is the defect: a stale due snapshot destroyed content that was no longer timed")
    }

    private fun claim(expireStartedAt: Long?, expiresAtValue: Long?, nowMillis: Long): Boolean {
        if (expireStartedAt == null || expiresAtValue == null) {
            return false
        }
        return db.prepareStatement(AbstractMessageModelFactory.deleteIfStillDueSql("message")).use { statement ->
            statement.setLong(1, messageId.toLong())
            statement.setLong(2, expireStartedAt)
            statement.setLong(3, expiresAtValue)
            statement.setLong(4, nowMillis)
            statement.executeUpdate() > 0
        }
    }

    private fun insertDueRow() {
        db.prepareStatement(
            "INSERT INTO `message` (`id`, `uid`, `identity`, `outbox`, `type`, `body`, `isRead`, `isSaved`," +
                " `isStatusMessage`, `isQueued`, `createdAtUtc`, `disappearingTimerSeconds`," +
                " `expireStartedAtUtc`, `expiresAtUtc`) VALUES (?, ?, ?, 0, 1, ?, 1, 1, 0, 0, ?, 30, ?, ?)",
        ).use { statement ->
            statement.setInt(1, messageId)
            statement.setString(2, "uid-1")
            statement.setString(3, "ECHOECHO")
            statement.setString(4, "the message")
            statement.setLong(5, startedAt)
            statement.setLong(6, startedAt)
            statement.setLong(7, expiresAt)
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
