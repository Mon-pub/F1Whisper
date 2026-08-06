package ch.threema.storage.databaseupdate

import ch.threema.storage.DatabaseUpdater
import io.mockk.mockk
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper (fourth fork review, Job 1): executable test for the removal of `scheduled_messages`.
 *
 * The claim under test is a claim about SQL, so it is tested as SQL: every case executes
 * [DatabaseUpdateToVersion127.DROP_STATEMENT] - the exact string that ships - against a real SQLite database through sqlite-jdbc,
 * rather than a copy of it that could drift from production.
 *
 * Three shapes reach this update in the field and all three must end with no table and no error:
 *  - an install that never created it (every install that upgraded after v120 was removed from the updater list),
 *  - an install still on the original six-column table (created by v120, never upgraded by v126),
 *  - an install on the twelve-column claim-state table (v120 then v126).
 *
 * What this cannot cover, recorded rather than glossed: production runs on SQLCipher through `android.database`, which is not
 * JVM-reachable. The statement is identical; the engine is not.
 */
class DatabaseUpdateToVersion127Test {
    private lateinit var db: Connection

    @BeforeTest
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `dropping an absent table is a successful no-op`() {
        assertFalse(tableExists(), "precondition: the table must not exist")

        drop()

        assertFalse(tableExists())
    }

    @Test
    fun `the original six-column table is dropped`() {
        createV120Table()
        insertLegacyRow()
        assertTrue(tableExists(), "precondition: the v120 table must exist")

        drop()

        assertFalse(tableExists())
    }

    @Test
    fun `the twelve-column claim-state table is dropped`() {
        createV120Table()
        upgradeToV126Table()
        insertLegacyRow()
        assertEquals(12, columnCount(), "precondition: the v126 table has twelve columns")

        drop()

        assertFalse(tableExists())
    }

    @Test
    fun `dropping the table drops its indexes with it`() {
        createV120Table()
        assertEquals(2, scheduledIndexCount(), "precondition: v120 created two indexes")

        drop()

        assertEquals(0, scheduledIndexCount())
    }

    @Test
    fun `the drop is idempotent`() {
        createV120Table()

        drop()
        drop()

        assertFalse(tableExists())
    }

    // -------------------------------------------------------------------------------------------------------------------------
    // Updater ordering: v127 must actually be reached from the versions a real device can be on.
    // -------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `updater reaches v127 from representative old versions`() {
        val updater = DatabaseUpdater(appContext = mockk(), database = mockk())

        // 103: an old install. 119: the version immediately before the (now removed) scheduled-messages table was created.
        // 125 and 126: installs that already had the table, in both of its shapes.
        for (oldVersion in listOf(0, 103, 119, 125, 126)) {
            val updates = updater.getUpdates(oldVersion = oldVersion)
            assertEquals(
                DatabaseUpdateToVersion127.VERSION,
                updates.last().version,
                "an install on v$oldVersion must end at v127",
            )
        }
    }

    @Test
    fun `the removed scheduled-messages updates are no longer registered`() {
        val updater = DatabaseUpdater(appContext = mockk(), database = mockk())

        val versions = updater.getUpdates(oldVersion = 0).map { it.version }

        assertFalse(120 in versions, "v120 created the scheduled_messages table and must no longer run")
        assertFalse(126 in versions, "v126 extended the scheduled_messages table and must no longer run")
        assertEquals(DatabaseUpdater.VERSION, versions.last())
    }

    // -------------------------------------------------------------------------------------------------------------------------

    private fun drop() {
        db.createStatement().use { statement ->
            statement.execute(DatabaseUpdateToVersion127.DROP_STATEMENT)
        }
    }

    /** The table exactly as `DatabaseUpdateToVersion120` created it, kept here because that update no longer exists. */
    private fun createV120Table() {
        db.createStatement().use { statement ->
            statement.execute(
                """
                    CREATE TABLE IF NOT EXISTS `scheduled_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                        `receiverType` INTEGER NOT NULL,
                        `receiverKey` VARCHAR NOT NULL,
                        `body` VARCHAR NOT NULL,
                        `scheduledAt` BIGINT NOT NULL,
                        `createdAt` BIGINT NOT NULL
                    );
                """.trimIndent(),
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS `scheduledMessagesScheduledAt` ON `scheduled_messages` ( `scheduledAt` );",
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS `scheduledMessagesReceiver` ON `scheduled_messages` ( `receiverType`, `receiverKey` );",
            )
        }
    }

    /** The six columns `DatabaseUpdateToVersion126` added, kept here for the same reason. */
    private fun upgradeToV126Table() {
        val columns = listOf(
            "state" to "INTEGER NOT NULL DEFAULT 0",
            "claimNonce" to "VARCHAR DEFAULT NULL",
            "claimedAtUtc" to "BIGINT DEFAULT NULL",
            "attemptCount" to "INTEGER NOT NULL DEFAULT 0",
            "lastAttemptAtUtc" to "BIGINT DEFAULT NULL",
            "handedOffUnits" to "VARCHAR DEFAULT NULL",
        )
        db.createStatement().use { statement ->
            for ((field, definition) in columns) {
                statement.execute("ALTER TABLE `scheduled_messages` ADD COLUMN `$field` $definition")
            }
        }
    }

    /** A pending row, so the drop is proven to discard data rather than only to succeed on an empty table. */
    private fun insertLegacyRow() {
        db.prepareStatement(
            "INSERT INTO `scheduled_messages` (`receiverType`, `receiverKey`, `body`, `scheduledAt`, `createdAt`) VALUES (?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setInt(1, 0)
            statement.setString(2, "ABCD1234")
            statement.setString(3, "a message that will never be sent")
            statement.setLong(4, 1_700_000_000_000L)
            statement.setLong(5, 1_699_999_000_000L)
            statement.executeUpdate()
        }
    }

    private fun tableExists(): Boolean =
        db.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'scheduled_messages'",
            ).use { cursor ->
                cursor.next()
                cursor.getInt(1) > 0
            }
        }

    private fun scheduledIndexCount(): Int =
        db.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name LIKE 'scheduledMessages%'",
            ).use { cursor ->
                cursor.next()
                cursor.getInt(1)
            }
        }

    private fun columnCount(): Int =
        db.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`scheduled_messages`)").use { cursor ->
                var count = 0
                while (cursor.next()) {
                    count++
                }
                count
            }
        }
}
