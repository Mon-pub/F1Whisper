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
 * F1Whisper (tenth fork review, F10-01): executable tests for the four expiry SELECTORS, run against a real SQLite
 * database through sqlite-jdbc against the real `message` and `m_group_message` tables from the schema snapshot.
 *
 * The defect: the readers and the claim disagreed about which rows the engine may act on. Since F5-04 the claim has
 * refused a row deleted for everyone, a row with no positive timer and a row with no countdown start; the readers went
 * on selecting on `expiresAtUtc` alone. A delete-for-everyone tombstone keeps its timer and its deadline - the tombstone
 * IS the row, only the body is gone - so it entered every sweep, was refused by the claim, and was then handed straight
 * back to the alarm as the earliest pending deadline, in the past. `AlarmManager` re-fired immediately, the sweep made
 * no progress, and the cycle repeated: the reporting device logged 13,256 firings over roughly twenty hours, about five
 * seconds apart, with no deletion between them.
 *
 * These tests drive the SHIPPED strings ([AbstractMessageModelFactory.expiredBeforeSelection],
 * [AbstractMessageModelFactory.earliestExpirySql], [AbstractMessageModelFactory.repairableExpirySelection]) rather than
 * a re-assembly of the same predicate, because a re-assembled copy drifting from the original is the entire defect.
 *
 * [legacyBroadSelectorReArmsTheAlarmOnATombstoneForever] is the control: it runs the OLD predicate inline, calls no
 * production code, and shows the no-progress loop happening.
 */
class ExpirySelectorEligibilityTest {
    private lateinit var db: Connection

    private val startedAt = 1_700_000_000_000L
    private val expiresAt = startedAt + 30_000L
    private val afterDeadline = expiresAt + 1_000L

    @BeforeTest
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.execute(loadCreateStatement("message")) }
        db.createStatement().use { it.execute(loadCreateStatement("m_group_message")) }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The tombstone, on both tables
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `an expired contact tombstone is invisible to the sweep and can never arm the alarm`() {
        insertRow(CONTACT, id = 1, expiresAtValue = expiresAt)
        deleteForEveryone(CONTACT, id = 1)

        assertEquals(emptyList(), dueIds(CONTACT, afterDeadline), "a row the claim refuses must not enter the sweep")
        assertNull(earliest(CONTACT), "and must never become the deadline the alarm is armed at")
    }

    @Test
    fun `an expired group tombstone is invisible to the sweep and can never arm the alarm`() {
        insertRow(GROUP, id = 1, expiresAtValue = expiresAt)
        deleteForEveryone(GROUP, id = 1)

        assertEquals(emptyList(), dueIds(GROUP, afterDeadline))
        assertNull(earliest(GROUP))
    }

    @Test
    fun `an existing tombstone is corrected by the query alone, with no migration and no row rewrite`() {
        // The shape a v6.4.3-38 database already contains: deleted for everyone, every timer field retained. Nothing
        // below writes to it; the correction has to work on the row exactly as it sits on disk, because clearing fields
        // when a NEW tombstone is written would leave every existing one looping forever.
        insertRow(CONTACT, id = 1, expiresAtValue = expiresAt)
        deleteForEveryone(CONTACT, id = 1)
        val before = rowSnapshot(CONTACT, 1)

        assertEquals(emptyList(), dueIds(CONTACT, afterDeadline))
        assertNull(earliest(CONTACT))
        assertEquals(before, rowSnapshot(CONTACT, 1), "the tombstone must be left exactly as it was found")
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The other no-progress shapes the broad predicate admitted
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a past deadline with no countdown start is not due`() {
        // deleteIfStillDue answers false for a null start without asking the database, so selecting this row could only
        // ever produce a refusal and another immediate re-arm.
        insertRow(CONTACT, id = 1, expiresAtValue = expiresAt, expireStartedAtValue = null)

        assertEquals(emptyList(), dueIds(CONTACT, afterDeadline))
        assertNull(earliest(CONTACT))
    }

    @Test
    fun `a past deadline left behind by a timer that was turned off is not due`() {
        insertRow(CONTACT, id = 1, expiresAtValue = expiresAt, timerSeconds = 0)

        assertEquals(emptyList(), dueIds(CONTACT, afterDeadline))
        assertNull(earliest(CONTACT))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Ordinary rows keep working
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a valid overdue row is still returned and still arms the alarm`() {
        insertRow(CONTACT, id = 1, expiresAtValue = expiresAt)

        assertEquals(listOf(1), dueIds(CONTACT, afterDeadline))
        assertEquals(expiresAt, earliest(CONTACT))
    }

    @Test
    fun `an invalid past tombstone next to a valid future row leaves the future row as the next alarm target`() {
        insertRow(CONTACT, id = 1, expiresAtValue = expiresAt)
        deleteForEveryone(CONTACT, id = 1)
        val future = afterDeadline + 600_000L
        insertRow(CONTACT, id = 2, expiresAtValue = future)

        assertEquals(emptyList(), dueIds(CONTACT, afterDeadline), "nothing is due yet")
        assertEquals(future, earliest(CONTACT), "so the alarm goes to the valid future deadline, not into the past")
    }

    @Test
    fun `the sweep keeps its earliest-deadline-first ordering`() {
        insertRow(CONTACT, id = 1, expiresAtValue = expiresAt)
        insertRow(CONTACT, id = 2, expiresAtValue = expiresAt - 10_000L)
        insertRow(CONTACT, id = 3, expiresAtValue = expiresAt - 5_000L)

        assertEquals(listOf(2, 3, 1), dueIds(CONTACT, afterDeadline))
    }

    @Test
    fun `a row that is not yet due is not swept`() {
        insertRow(CONTACT, id = 1, expiresAtValue = expiresAt)

        assertEquals(emptyList(), dueIds(CONTACT, nowMillis = expiresAt - 1))
        assertEquals(expiresAt, earliest(CONTACT))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The bounded repair scan
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a full batch of low-id tombstones no longer starves the valid repairable row behind them`() {
        // The scan is ordered by id and capped at REPAIR_SCAN_LIMIT. Tombstones were admitted and then refused by the
        // conditional writer, so a thousand of them filled every launch's batch and the valid row behind them never got
        // its deadline. Nothing was written wrongly; the entire budget was spent on rows already destined for refusal.
        val limit = REPAIR_SCAN_LIMIT
        repeat(limit) { index ->
            insertRow(CONTACT, id = index + 1, expiresAtValue = null)
            deleteForEveryone(CONTACT, id = index + 1)
        }
        val validId = limit + 1
        insertRow(CONTACT, id = validId, expiresAtValue = null)

        assertEquals(listOf(validId), repairableIds(CONTACT, limit))
    }

    @Test
    fun `the same starvation is closed on the group table`() {
        val limit = REPAIR_SCAN_LIMIT
        repeat(limit) { index ->
            insertRow(GROUP, id = index + 1, expiresAtValue = null)
            deleteForEveryone(GROUP, id = index + 1)
        }
        val validId = limit + 1
        insertRow(GROUP, id = validId, expiresAtValue = null)

        assertEquals(listOf(validId), repairableIds(GROUP, limit))
    }

    @Test
    fun `the repair scan still finds a read incoming message whose countdown never started`() {
        insertRow(CONTACT, id = 1, expiresAtValue = null, expireStartedAtValue = null, isRead = true, outbox = false)

        assertEquals(listOf(1), repairableIds(CONTACT, REPAIR_SCAN_LIMIT))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Control
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun legacyBroadSelectorReArmsTheAlarmOnATombstoneForever() {
        insertRow(CONTACT, id = 1, expiresAtValue = expiresAt)
        deleteForEveryone(CONTACT, id = 1)

        // The old predicate, inline: `expiresAtUtc IS NOT NULL` and nothing else.
        val legacyDue = idsOf("SELECT `id` FROM `message` WHERE `expiresAtUtc` IS NOT NULL AND `expiresAtUtc` <= $afterDeadline")
        val legacyEarliest = longOf("SELECT MIN(`expiresAtUtc`) FROM `message` WHERE `expiresAtUtc` IS NOT NULL")

        assertEquals(listOf(1), legacyDue, "the sweep picks the tombstone up")
        // ... the claim then refuses it (proven by ExpiryClaimTest), the row survives, and the alarm is handed the same
        // past deadline straight back. That is the loop, expressed in two queries.
        assertEquals(expiresAt, legacyEarliest)
        assertTrue(legacyEarliest!! <= afterDeadline, "this is the defect: the next alarm is armed in the past, forever")
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------------------------------------------------

    private fun dueIds(table: String, nowMillis: Long): List<Int> =
        db.prepareStatement(
            "SELECT `id` FROM `$table` WHERE ${AbstractMessageModelFactory.expiredBeforeSelection()}" +
                " ORDER BY `expiresAtUtc` ASC",
        ).use { statement ->
            statement.setLong(1, nowMillis)
            statement.executeQuery().use { cursor ->
                buildList { while (cursor.next()) add(cursor.getInt(1)) }
            }
        }

    private fun earliest(table: String): Long? = longOf(AbstractMessageModelFactory.earliestExpirySql(table))

    private fun repairableIds(table: String, limit: Int): List<Int> =
        idsOf(
            "SELECT `id` FROM `$table` WHERE ${AbstractMessageModelFactory.repairableExpirySelection()}" +
                " ORDER BY `id` ASC LIMIT $limit",
        )

    private fun idsOf(sql: String): List<Int> =
        db.createStatement().use { statement ->
            statement.executeQuery(sql).use { cursor ->
                buildList { while (cursor.next()) add(cursor.getInt(1)) }
            }
        }

    private fun longOf(sql: String): Long? =
        db.createStatement().use { statement ->
            statement.executeQuery(sql).use { cursor ->
                if (cursor.next()) {
                    val value = cursor.getLong(1)
                    if (cursor.wasNull()) null else value
                } else {
                    null
                }
            }
        }

    private fun rowSnapshot(table: String, id: Int): String =
        db.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT `deletedAtUtc`, `disappearingTimerSeconds`, `expireStartedAtUtc`, `expiresAtUtc`," +
                    " `body` FROM `$table` WHERE `id` = $id",
            ).use { cursor ->
                cursor.next()
                (1..5).joinToString("|") { cursor.getString(it) ?: "null" }
            }
        }

    private fun deleteForEveryone(table: String, id: Int) {
        // What a delete-for-everyone actually leaves behind: the body is gone, every timer field is retained.
        db.createStatement().use {
            it.executeUpdate("UPDATE `$table` SET `deletedAtUtc` = $startedAt, `body` = NULL WHERE `id` = $id")
        }
    }

    private fun insertRow(
        table: String,
        id: Int,
        expiresAtValue: Long?,
        expireStartedAtValue: Long? = startedAt,
        timerSeconds: Int = 30,
        isRead: Boolean = true,
        outbox: Boolean = false,
    ) {
        val groupColumn = if (table == GROUP) ", `groupId`" else ""
        val groupValue = if (table == GROUP) ", 1" else ""
        db.prepareStatement(
            "INSERT INTO `$table` (`id`, `uid`, `identity`, `outbox`, `type`, `body`, `isRead`, `isSaved`," +
                " `isStatusMessage`, `isQueued`, `createdAtUtc`, `disappearingTimerSeconds`," +
                " `expireStartedAtUtc`, `expiresAtUtc`$groupColumn)" +
                " VALUES (?, ?, ?, ?, 1, ?, ?, 1, 0, 0, ?, ?, ?, ?$groupValue)",
        ).use { statement ->
            statement.setInt(1, id)
            statement.setString(2, "uid-$table-$id")
            statement.setString(3, "ECHOECHO")
            statement.setInt(4, if (outbox) 1 else 0)
            statement.setString(5, "the message")
            statement.setInt(6, if (isRead) 1 else 0)
            statement.setLong(7, startedAt)
            statement.setInt(8, timerSeconds)
            if (expireStartedAtValue == null) statement.setNull(9, java.sql.Types.BIGINT) else statement.setLong(9, expireStartedAtValue)
            if (expiresAtValue == null) statement.setNull(10, java.sql.Types.BIGINT) else statement.setLong(10, expiresAtValue)
            statement.executeUpdate()
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

    private companion object {
        const val CONTACT = "message"
        const val GROUP = "m_group_message"

        /**
         * Mirrors `DisappearingMessageService.REPAIR_SCAN_LIMIT`. Kept as a literal rather than imported because the
         * service pulls in the Android runtime; the number is what the starvation test needs, not the symbol.
         */
        const val REPAIR_SCAN_LIMIT = 1000
    }
}
