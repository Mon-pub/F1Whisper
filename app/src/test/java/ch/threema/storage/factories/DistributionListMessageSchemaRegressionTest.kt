package ch.threema.storage.factories

import android.content.ContentValues
import ch.threema.storage.databaseupdate.DatabaseUpdateToVersion125
import ch.threema.storage.models.DistributionListMessageModel
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.MessageType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.sql.Connection
import java.sql.DriverManager
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * F1Whisper (fork review H-01, follow-up review "Required Regression Coverage"): EXECUTABLE
 * regression test for the distribution-list schema fix.
 *
 * The original defect: the shared message write path
 * ([AbstractMessageModelFactory.buildContentValues]) writes the disappearing-messages columns for
 * EVERY message table, but the shipped v122 migration only added them to `message` and
 * `m_group_message` — so on an UPGRADED database every `distribution_list_message` INSERT/UPDATE
 * failed at runtime while the fresh schema worked. A test that checks constants cannot catch that
 * class of bug; this one:
 *
 *  1. builds the REAL fresh `distribution_list_message` table (CREATE statement executed verbatim
 *     from the generated `database/schema.sql` snapshot),
 *  2. derives a faithful **v124** table (fresh minus exactly the columns v125 adds — sound because
 *     the shipped v124 migration added `sortAtUtc` to all three message tables and v125 adds only
 *     these three), runs the EXACT DDL strings of [DatabaseUpdateToVersion125] on it, and asserts
 *     column-level equivalence with fresh,
 *  3. captures the REAL production write set (every `ContentValues.put` issued by
 *     [AbstractMessageModelFactory.buildContentValues] plus the `distributionListId` the factory's
 *     create() adds) and executes it as a REAL INSERT and UPDATE against BOTH table shapes.
 *
 * If any column the production write path emits is missing from either shape, the INSERT fails —
 * exactly the way production failed before H-01. Migration numbering is intentionally untouched
 * (follow-up review, "Explicitly Deferred Scope").
 */
class DistributionListMessageSchemaRegressionTest {

    private lateinit var freshDb: Connection
    private lateinit var upgradedDb: Connection

    @BeforeTest
    fun setUp() {
        freshDb = DriverManager.getConnection("jdbc:sqlite::memory:")
        upgradedDb = DriverManager.getConnection("jdbc:sqlite::memory:")

        val createStatement = loadCreateStatement()
        freshDb.createStatement().use { it.execute(createStatement) }

        // Derive v124: the fresh table minus exactly the columns v125 introduces...
        upgradedDb.createStatement().use { statement ->
            statement.execute(createStatement)
            for ((field, _) in DatabaseUpdateToVersion125.COLUMN_DEFINITIONS) {
                // (DROP throws if the column is absent — implicitly asserting the fresh schema
                // really contains every column the migration targets.)
                statement.execute("ALTER TABLE `${DatabaseUpdateToVersion125.DISTRIBUTION_LIST_MESSAGE_TABLE}` DROP COLUMN `$field`")
            }
            // ...then run the EXACT DDL the production migration executes.
            for ((field, definition) in DatabaseUpdateToVersion125.COLUMN_DEFINITIONS) {
                statement.execute(DatabaseUpdateToVersion125.alterStatement(field, definition))
            }
        }
    }

    @AfterTest
    fun tearDown() {
        freshDb.close()
        upgradedDb.close()
    }

    private fun loadCreateStatement(): String {
        javaClass.getResourceAsStream("/database/schema.sql")!!.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith("CREATE TABLE `distribution_list_message`(")) {
                    return line
                }
            }
        }
        fail("CREATE TABLE `distribution_list_message` not found in schema.sql")
    }

    /** name -> (type, notnull, default, pk) from PRAGMA table_info. */
    private fun columnInfo(connection: Connection): Map<String, List<String?>> {
        val result = linkedMapOf<String, List<String?>>()
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "PRAGMA table_info(`${DatabaseUpdateToVersion125.DISTRIBUTION_LIST_MESSAGE_TABLE}`)",
            ).use { resultSet ->
                while (resultSet.next()) {
                    result[resultSet.getString("name")] = listOf(
                        resultSet.getString("type"),
                        resultSet.getString("notnull"),
                        resultSet.getString("dflt_value"),
                        resultSet.getString("pk"),
                    )
                }
            }
        }
        return result
    }

    @Test
    fun `v124 plus migration converges to the fresh schema`() {
        assertEquals(columnInfo(freshDb), columnInfo(upgradedDb))
    }

    @Test
    fun `migration guard would skip every column on a fresh database`() {
        // Production guards each ALTER with fieldExists; on a fresh install every target column
        // already exists, so the guard must find each one (otherwise re-running would throw).
        val freshColumns = columnInfo(freshDb).keys
        for ((field, _) in DatabaseUpdateToVersion125.COLUMN_DEFINITIONS) {
            assertTrue(field in freshColumns, "fresh schema is missing `$field`")
        }
    }

    @Test
    fun `real production write set inserts and updates on both fresh and upgraded tables`() {
        val writeSet = captureProductionWriteSet()

        // The original H-01 columns MUST be part of the captured production write set — if the
        // shared write path stops emitting them this test must not silently pass.
        for ((field, _) in DatabaseUpdateToVersion125.COLUMN_DEFINITIONS) {
            assertTrue(field in writeSet, "production write set no longer contains `$field`")
        }

        for (connection in listOf(freshDb, upgradedDb)) {
            val columns = writeSet.keys.toList()
            val insertSql = "INSERT INTO `${DatabaseUpdateToVersion125.DISTRIBUTION_LIST_MESSAGE_TABLE}` " +
                "(${columns.joinToString(", ") { "`$it`" }}) VALUES (${columns.joinToString(", ") { "?" }})"
            connection.prepareStatement(insertSql).use { statement ->
                columns.forEachIndexed { index, column -> statement.setObject(index + 1, writeSet[column]) }
                assertEquals(1, statement.executeUpdate())
            }

            val updateSql = "UPDATE `${DatabaseUpdateToVersion125.DISTRIBUTION_LIST_MESSAGE_TABLE}` " +
                "SET ${columns.joinToString(", ") { "`$it`=?" }} WHERE id=?"
            connection.prepareStatement(updateSql).use { statement ->
                columns.forEachIndexed { index, column -> statement.setObject(index + 1, writeSet[column]) }
                statement.setInt(columns.size + 1, 1)
                statement.executeUpdate()
            }

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT expiresAtUtc, expireStartedAtUtc, disappearingTimerSeconds " +
                        "FROM `${DatabaseUpdateToVersion125.DISTRIBUTION_LIST_MESSAGE_TABLE}`",
                ).use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(1111L, resultSet.getLong(1))
                    assertEquals(2222L, resultSet.getLong(2))
                    assertEquals(3600, resultSet.getInt(3))
                }
            }
        }
    }

    // --- Third follow-up S3-07 / T3-14: drive the PRODUCTION migration decision (planMigration,
    // which DatabaseUpdateToVersion125.run() executes verbatim) rather than re-deriving the DDL. ---

    @Test
    fun `planMigration on a v124 database emits exactly the three v125 alters`() {
        val v125Columns = DatabaseUpdateToVersion125.COLUMN_DEFINITIONS.map { it.first }.toSet()
        val v124Columns = columnInfo(freshDb).keys - v125Columns
        val expected = DatabaseUpdateToVersion125.COLUMN_DEFINITIONS
            .map { (field, definition) -> DatabaseUpdateToVersion125.alterStatement(field, definition) }
        assertEquals(expected, DatabaseUpdateToVersion125.planMigration(v124Columns))
    }

    @Test
    fun `planMigration on the fresh schema is a no-op (per-column idempotency guard)`() {
        assertTrue(DatabaseUpdateToVersion125.planMigration(columnInfo(freshDb).keys).isEmpty())
    }

    @Test
    fun `planMigration applies only the still-missing columns on a partially-upgraded database`() {
        // A partially-applied upgrade already carries SOME of the three columns; the production
        // decision must ALTER only the still-missing ones, never re-add an existing column.
        val newColumns = DatabaseUpdateToVersion125.COLUMN_DEFINITIONS.map { it.first }
        val alreadyPresent = newColumns.first()
        val stillMissing = newColumns.drop(1).toSet()
        val partialColumns = columnInfo(freshDb).keys - stillMissing
        val expected = DatabaseUpdateToVersion125.COLUMN_DEFINITIONS
            .filter { (field, _) -> field in stillMissing }
            .map { (field, definition) -> DatabaseUpdateToVersion125.alterStatement(field, definition) }
        val plan = DatabaseUpdateToVersion125.planMigration(partialColumns)
        assertEquals(expected, plan)
        assertTrue(plan.none { it.contains("`$alreadyPresent`") }, "the already-present column must not be re-altered")
    }

    @Test
    fun `running planMigration output on a real v124 table converges to the fresh schema`() {
        val v124 = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            v124.createStatement().use { statement ->
                statement.execute(loadCreateStatement())
                for ((field, _) in DatabaseUpdateToVersion125.COLUMN_DEFINITIONS) {
                    statement.execute(
                        "ALTER TABLE `${DatabaseUpdateToVersion125.DISTRIBUTION_LIST_MESSAGE_TABLE}` DROP COLUMN `$field`",
                    )
                }
            }
            val existingColumns = columnInfo(v124).keys
            v124.createStatement().use { statement ->
                for (sql in DatabaseUpdateToVersion125.planMigration(existingColumns)) {
                    statement.execute(sql)
                }
            }
            assertEquals(columnInfo(freshDb).keys, columnInfo(v124).keys)
        } finally {
            v124.close()
        }
    }

    /**
     * Every (column, value) the REAL factory write path emits for a fully-populated
     * distribution-list message: [AbstractMessageModelFactory.buildContentValues] captured via
     * mockkConstructor, plus the `distributionListId` that [DistributionListMessageModelFactory]'s
     * create()/update() add alongside it.
     */
    private fun captureProductionWriteSet(): Map<String, Any?> {
        val factory = mockk<DistributionListMessageModelFactory>(relaxed = true)
        every { factory.buildContentValues(any()) } answers { callOriginal() }

        val captured = linkedMapOf<String, Any?>()
        mockkConstructor(ContentValues::class)
        try {
            every { anyConstructed<ContentValues>().put(any<String>(), anyNullable<String>()) } answers {
                captured[firstArg()] = secondArg<String?>()
            }
            every { anyConstructed<ContentValues>().put(any<String>(), anyNullable<Int>()) } answers {
                captured[firstArg()] = secondArg<Int?>()
            }
            every { anyConstructed<ContentValues>().put(any<String>(), anyNullable<Long>()) } answers {
                captured[firstArg()] = secondArg<Long?>()
            }
            every { anyConstructed<ContentValues>().put(any<String>(), anyNullable<Boolean>()) } answers {
                captured[firstArg()] = secondArg<Boolean?>()
            }

            val model = DistributionListMessageModel().apply {
                setDistributionListId(42L)
                uid = "uid-1"
                apiMessageId = "0011223344556677"
                identity = "AAAAAAAA"
                isOutbox = true
                type = MessageType.TEXT
                body = "hello distribution list"
                correlationId = "corr-1"
                isRead = true
                isSaved = true
                state = MessageState.SENT
                postedAt = Date(1000L)
                createdAt = Date(900L)
                modifiedAt = Date(1100L)
                isStatusMessage = false
                caption = "cap"
                quotedMessageId = null
                messageContentsType = 1
                messageFlags = 0
                // The three H-01 columns, explicitly non-null:
                expiresAt = 1111L
                expireStartedAt = 2222L
                disappearingTimerSeconds = 3600
            }

            factory.buildContentValues(model)
            // create()/update() add the receiver id next to the shared write set:
            captured[DistributionListMessageModel.COLUMN_DISTRIBUTION_LIST_ID] = model.distributionListId
        } finally {
            unmockkConstructor(ContentValues::class)
        }
        return captured
    }
}
