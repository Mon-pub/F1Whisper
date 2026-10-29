package ch.threema.storage.factories

import ch.threema.storage.DatabaseSchemaCreator
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.MessageType
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F1Whisper auto-resend: the candidate and aged-out WHERE clauses, executed as SQL against the fresh-install schema.
 *
 * Message types are stored by [MessageType.serializedValue], which differs from the enum ordinal (FILE is stored as 8, its
 * ordinal is 4, which is LOCATION's stored value). A clause built from the ordinal compiles, and the in-memory predicate
 * tests stay green, while the query silently selects location messages instead of files. Only running the SQL shows it.
 */
class AutoResendCandidateSqlTest {
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
    fun `only unsent outgoing file messages inside the window are candidates`() {
        insert(id = 1, type = MessageType.FILE, state = MessageState.SENDFAILED, createdAt = RECENT)
        insert(id = 2, type = MessageType.LOCATION, state = MessageState.SENDFAILED, createdAt = RECENT)
        insert(id = 3, type = MessageType.TEXT, state = MessageState.SENDFAILED, createdAt = RECENT)
        insert(id = 4, type = MessageType.FILE, state = MessageState.SENDING, createdAt = RECENT)
        insert(id = 5, type = MessageType.FILE, state = MessageState.UPLOADING, createdAt = RECENT, outbox = false)
        insert(id = 6, type = MessageType.FILE, state = MessageState.PENDING, createdAt = OLD)

        assertEquals(listOf(1), select(AbstractMessageModelFactory.AUTO_RESEND_WHERE, CUTOFF))
    }

    @Test
    fun `only unsent outgoing file messages past the window are aged out`() {
        insert(id = 1, type = MessageType.FILE, state = MessageState.PENDING, createdAt = OLD)
        insert(id = 2, type = MessageType.LOCATION, state = MessageState.PENDING, createdAt = OLD)
        insert(id = 3, type = MessageType.FILE, state = MessageState.PENDING, createdAt = RECENT)

        assertEquals(listOf(1), select(AbstractMessageModelFactory.AGED_OUT_UNSENT_WHERE, CUTOFF))
    }

    private fun insert(id: Int, type: MessageType, state: MessageState, createdAt: Long, outbox: Boolean = true) {
        db.prepareStatement(
            "INSERT INTO `message` (`id`, `uid`, `identity`, `outbox`, `type`, `state`, `createdAtUtc`, `isStatusMessage`, `displayTags`) " +
                "VALUES (?, ?, 'ABCD1234', ?, ?, ?, ?, 0, 0)",
        ).use { statement ->
            statement.setInt(1, id)
            statement.setString(2, "uid-$id")
            statement.setInt(3, if (outbox) 1 else 0)
            statement.setInt(4, type.serializedValue)
            statement.setString(5, state.toString())
            statement.setLong(6, createdAt)
            statement.executeUpdate()
        }
    }

    private fun select(where: String, bind: Long): List<Int> =
        db.prepareStatement("SELECT `id` FROM `message` WHERE $where ORDER BY `id`").use { statement ->
            statement.setString(1, bind.toString())
            statement.executeQuery().use { cursor ->
                buildList {
                    while (cursor.next()) {
                        add(cursor.getInt(1))
                    }
                }
            }
        }

    private companion object {
        const val CUTOFF = 1_700_000_000_000L
        const val RECENT = CUTOFF + 60_000L
        const val OLD = CUTOFF - 60_000L
    }
}
