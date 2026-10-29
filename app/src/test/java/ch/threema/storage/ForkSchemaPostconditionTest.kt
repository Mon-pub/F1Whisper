package ch.threema.storage

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F1Whisper: the schema postcondition checked at the end of every upgrade (see [ForkSchemaPostcondition]).
 *
 * Runs the real fresh-install schema ([DatabaseSchemaCreator]) on SQLite through sqlite-jdbc. The rollback itself is the
 * SQLite open helper's upgrade transaction and is covered by the instrumented upgrade test.
 */
class ForkSchemaPostconditionTest {
    private lateinit var db: Connection

    @BeforeTest
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { statement ->
            DatabaseSchemaCreator.getCreationStatements().forEach(statement::execute)
        }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a fresh install satisfies the postcondition`() {
        assertEquals(emptyList(), ForkSchemaPostcondition.missing(::columnExists))
    }

    @Test
    fun `a missing column is reported by name`() {
        db.createStatement().use { it.execute("ALTER TABLE `m_group_message` DROP COLUMN `sortAtUtc`") }

        assertEquals(listOf("m_group_message.sortAtUtc"), ForkSchemaPostcondition.missing(::columnExists))
    }

    @Test
    fun `a missing table reports every one of its columns`() {
        db.createStatement().use {
            it.execute("DROP TABLE `chat_folder_member`")
        }

        val missing = ForkSchemaPostcondition.missing(::columnExists)

        assertEquals(ForkSchemaPostcondition.REQUIRED_COLUMNS.getValue("chat_folder_member").map { "chat_folder_member.$it" }, missing)
    }

    @Test
    fun `the failure marker cannot be mistaken for an update version`() {
        assertTrue(ForkSchemaPostcondition.FAILURE_MARKER > DatabaseUpdater.VERSION)
    }

    private fun columnExists(table: String, column: String): Boolean =
        db.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$table`)").use { cursor ->
                var found = false
                while (cursor.next()) {
                    if (cursor.getString("name") == column) {
                        found = true
                    }
                }
                found
            }
        }
}
