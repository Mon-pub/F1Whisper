package ch.threema.storage.databaseupdate

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guarded replay of the fork's own database updates (90131..90136).
 *
 * The recorded schema version of a database does not have to describe its shape, so every fork update must be a no-op
 * where it already applied and a repair where it did not. That is a claim about SQL, so it is tested as SQL: the walk
 * below executes the exact statements each update plans, against real SQLite fixtures through sqlite-jdbc.
 *
 * Covered shapes: a database that never ran any fork update (repair), one that ran the early ones only (the per-direction
 * timer must be seeded from the local one), one where everything already applied with values that do NOT match the
 * backfill formulas (they must survive unchanged: the data steps are guarded, not merely idempotent), a partial shape,
 * and a double walk (identical schema and rows).
 *
 * Not covered here, recorded rather than glossed: production runs on SQLCipher through `android.database`. The statements
 * are identical; the engine is not.
 */
class ForkMigrationGuardedReplayTest {
    private lateinit var db: Connection

    @BeforeTest
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        createBaseTables()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a database without any fork object is repaired and initialised`() {
        insertMessage("message", id = 1, outbox = 1, createdAt = 100, postedAt = 50)
        insertMessage("message", id = 2, outbox = 0, createdAt = 200, postedAt = 150)
        insertMessage("m_group_message", id = 1, outbox = 0, createdAt = 300, postedAt = null)
        insertMessage("distribution_list_message", id = 1, outbox = 1, createdAt = 400, postedAt = 10)
        createScheduledMessagesTable()

        walk()

        assertTrue(tableExists("chat_folder"))
        assertTrue(tableExists("chat_folder_member"))
        assertFalse(tableExists("scheduled_messages"))
        for (table in MESSAGE_TABLES) {
            for (column in listOf("expiresAtUtc", "expireStartedAtUtc", "disappearingTimerSeconds", "sortAtUtc")) {
                assertTrue(columnExists(table, column), "$table.$column must exist after the walk")
            }
        }
        for (table in CONVERSATION_TABLES) {
            assertTrue(columnExists(table, "disappearingMessagesTimerSeconds"))
            assertTrue(columnExists(table, "peerDisappearingTimerSeconds"))
        }
        // The sort key is initialised by its formula: outgoing -> createdAtUtc, incoming -> COALESCE(postedAtUtc, createdAtUtc).
        assertEquals(100L, sortAt("message", 1))
        assertEquals(150L, sortAt("message", 2))
        assertEquals(300L, sortAt("m_group_message", 1))
        assertEquals(400L, sortAt("distribution_list_message", 1))
    }

    @Test
    fun `a missing peer timer is seeded from the local timer`() {
        addColumn("contacts", "disappearingMessagesTimerSeconds", "INTEGER DEFAULT NULL")
        addColumn("m_group", "disappearingMessagesTimerSeconds", "INTEGER DEFAULT NULL")
        insertConversation("contacts", id = 1, localTimer = 30)
        insertConversation("contacts", id = 2, localTimer = null)
        insertConversation("m_group", id = 1, localTimer = 3600)

        walk()

        assertEquals(30L, peerTimer("contacts", 1))
        assertNull(peerTimer("contacts", 2))
        assertEquals(3600L, peerTimer("m_group", 1))
    }

    @Test
    fun `values in an already-applied schema survive unchanged`() {
        applyEveryForkColumn()
        insertConversation("contacts", id = 1, localTimer = 30, peerTimer = 7)
        insertConversation("m_group", id = 1, localTimer = 60, peerTimer = null)
        insertMessage("message", id = 1, outbox = 1, createdAt = 100, postedAt = 50, sortAt = 999)
        insertMessage("m_group_message", id = 1, outbox = 0, createdAt = 300, postedAt = 250, sortAt = 1)
        insertMessage("distribution_list_message", id = 1, outbox = 1, createdAt = 400, postedAt = 10, sortAt = 2)

        walk()

        // None of these values matches the seed or backfill formula, so any unguarded data step would have changed it.
        assertEquals(7L, peerTimer("contacts", 1))
        assertNull(peerTimer("m_group", 1))
        assertEquals(999L, sortAt("message", 1))
        assertEquals(1L, sortAt("m_group_message", 1))
        assertEquals(2L, sortAt("distribution_list_message", 1))
    }

    @Test
    fun `a partial schema gets only its missing data steps`() {
        addColumn("message", "sortAtUtc", "BIGINT DEFAULT NULL")
        insertMessage("message", id = 1, outbox = 1, createdAt = 100, postedAt = 50, sortAt = 999)
        insertMessage("m_group_message", id = 1, outbox = 0, createdAt = 300, postedAt = 250)

        walk()

        assertEquals(999L, sortAt("message", 1), "the table that already had the column must not be backfilled")
        assertEquals(250L, sortAt("m_group_message", 1), "the table that lacked the column must be backfilled")
    }

    @Test
    fun `walking twice leaves identical schema and rows`() {
        insertMessage("message", id = 1, outbox = 0, createdAt = 100, postedAt = 50)
        createScheduledMessagesTable()
        walk()
        insertConversation("contacts", id = 1, localTimer = null)
        execute("UPDATE `contacts` SET `disappearingMessagesTimerSeconds` = 30, `peerDisappearingTimerSeconds` = 5")
        execute("UPDATE `message` SET `sortAtUtc` = 12345")
        val schemaAfterFirst = schemaSnapshot()
        val rowsAfterFirst = rowSnapshot()

        walk()

        assertEquals(schemaAfterFirst, schemaSnapshot())
        assertEquals(rowsAfterFirst, rowSnapshot())
    }

    @Test
    fun `an already-applied schema plans no data step at all`() {
        applyEveryForkColumn()
        val exists = ::columnExists

        val plans = DatabaseUpdateToVersion90131.STATEMENTS +
            DatabaseUpdateToVersion90132.planMigration(exists) +
            DatabaseUpdateToVersion90133.planMigration(exists) +
            DatabaseUpdateToVersion90134.planMigration(exists) +
            DatabaseUpdateToVersion90135.planMigration(existingDistributionListColumns()) +
            DatabaseUpdateToVersion90136.DROP_STATEMENT

        val dml = plans.filter { DML.containsMatchIn(it) }
        assertEquals(emptyList(), dml)
        assertTrue(plans.none { it.trimStart().startsWith("ALTER", ignoreCase = true) })
    }

    // -------------------------------------------------------------------------------------------------------------------------

    /** The fork updates in registration order, each planned against the schema as the previous one left it. */
    private fun walk() {
        DatabaseUpdateToVersion90131.STATEMENTS.forEach(::execute)
        DatabaseUpdateToVersion90132.planMigration(::columnExists).forEach(::execute)
        DatabaseUpdateToVersion90133.planMigration(::columnExists).forEach(::execute)
        DatabaseUpdateToVersion90134.planMigration(::columnExists).forEach(::execute)
        DatabaseUpdateToVersion90135.planMigration(existingDistributionListColumns()).forEach(::execute)
        execute(DatabaseUpdateToVersion90136.DROP_STATEMENT)
    }

    /** Minimal pre-fork tables: only the columns the fork updates read. */
    private fun createBaseTables() {
        for (table in MESSAGE_TABLES) {
            execute("CREATE TABLE `$table` (`id` INTEGER PRIMARY KEY, `outbox` TINYINT, `createdAtUtc` BIGINT, `postedAtUtc` BIGINT)")
        }
        for (table in CONVERSATION_TABLES) {
            execute("CREATE TABLE `$table` (`id` INTEGER PRIMARY KEY)")
        }
    }

    private fun applyEveryForkColumn() {
        walk()
    }

    private fun createScheduledMessagesTable() {
        execute("CREATE TABLE `scheduled_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `body` VARCHAR NOT NULL)")
        execute("CREATE INDEX `scheduledMessagesBody` ON `scheduled_messages` (`body`)")
        execute("INSERT INTO `scheduled_messages` (`body`) VALUES ('pending')")
    }

    private fun addColumn(table: String, column: String, definition: String) =
        execute("ALTER TABLE `$table` ADD COLUMN `$column` $definition")

    private fun insertMessage(table: String, id: Int, outbox: Int, createdAt: Long, postedAt: Long?, sortAt: Long? = null) {
        val withSort = sortAt != null
        val sql = if (withSort) {
            "INSERT INTO `$table` (`id`, `outbox`, `createdAtUtc`, `postedAtUtc`, `sortAtUtc`) VALUES (?, ?, ?, ?, ?)"
        } else {
            "INSERT INTO `$table` (`id`, `outbox`, `createdAtUtc`, `postedAtUtc`) VALUES (?, ?, ?, ?)"
        }
        db.prepareStatement(sql).use { statement ->
            statement.setInt(1, id)
            statement.setInt(2, outbox)
            statement.setLong(3, createdAt)
            statement.setObject(4, postedAt)
            if (withSort) {
                statement.setObject(5, sortAt)
            }
            statement.executeUpdate()
        }
    }

    private fun insertConversation(table: String, id: Int, localTimer: Int?, peerTimer: Int? = null) {
        val withPeer = columnExists(table, "peerDisappearingTimerSeconds")
        val sql = if (withPeer) {
            "INSERT INTO `$table` (`id`, `disappearingMessagesTimerSeconds`, `peerDisappearingTimerSeconds`) VALUES (?, ?, ?)"
        } else {
            "INSERT INTO `$table` (`id`, `disappearingMessagesTimerSeconds`) VALUES (?, ?)"
        }
        db.prepareStatement(sql).use { statement ->
            statement.setInt(1, id)
            statement.setObject(2, localTimer)
            if (withPeer) {
                statement.setObject(3, peerTimer)
            }
            statement.executeUpdate()
        }
    }

    private fun sortAt(table: String, id: Int): Long? = queryLong("SELECT `sortAtUtc` FROM `$table` WHERE `id` = $id")

    private fun peerTimer(table: String, id: Int): Long? =
        queryLong("SELECT `peerDisappearingTimerSeconds` FROM `$table` WHERE `id` = $id")

    private fun queryLong(sql: String): Long? =
        db.createStatement().use { statement ->
            statement.executeQuery(sql).use { cursor ->
                assertTrue(cursor.next(), "no row for: $sql")
                val value = cursor.getLong(1)
                if (cursor.wasNull()) null else value
            }
        }

    private fun execute(sql: String) {
        db.createStatement().use { statement -> statement.execute(sql) }
    }

    private fun columnExists(table: String, column: String): Boolean = column in columnsOf(table)

    private fun existingDistributionListColumns(): Set<String> = columnsOf("distribution_list_message").toSet()

    private fun columnsOf(table: String): List<String> =
        db.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$table`)").use { cursor ->
                buildList {
                    while (cursor.next()) {
                        add(cursor.getString("name"))
                    }
                }
            }
        }

    private fun tableExists(table: String): Boolean =
        db.prepareStatement("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { cursor ->
                cursor.next()
                cursor.getInt(1) > 0
            }
        }

    private fun schemaSnapshot(): List<String> =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT type || ' ' || name || ' ' || IFNULL(sql, '') FROM sqlite_master ORDER BY type, name").use {
                buildList {
                    while (it.next()) {
                        add(it.getString(1))
                    }
                }
            }
        }

    private fun rowSnapshot(): Map<String, List<List<Any?>>> =
        (MESSAGE_TABLES + CONVERSATION_TABLES).associateWith { table ->
            db.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM `$table` ORDER BY `id`").use { cursor ->
                    val width = cursor.metaData.columnCount
                    buildList {
                        while (cursor.next()) {
                            add((1..width).map { cursor.getObject(it) })
                        }
                    }
                }
            }
        }

    private companion object {
        val MESSAGE_TABLES = listOf("message", "m_group_message", "distribution_list_message")
        val CONVERSATION_TABLES = listOf("contacts", "m_group")
        val DML = Regex("""^\s*(UPDATE|INSERT|DELETE|REPLACE)\b""", RegexOption.IGNORE_CASE)
    }
}
