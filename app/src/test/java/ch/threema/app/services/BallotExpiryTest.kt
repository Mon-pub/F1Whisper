package ch.threema.app.services

import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.data.media.BallotDataModel
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (fourth fork review, F4-08): expiring a poll must take the poll with it.
 *
 * The defect: a poll is not stored in the message that shows it. The carrier message holds only a pointer, while the
 * question, choices, votes and the group/identity links live in their own five tables, and the conversation's open-poll
 * surface (`OpenBallotNoticeView`) queries those tables through `BallotService.getBallots` rather than reading the message
 * list. All three expiry paths called the generic `messageService.remove`, which deletes the message row and its files and
 * nothing else, so an expired poll lost its bubble and kept everything else: the question readable, the votes intact, and
 * vote / results / close still offered.
 *
 * Three things are checked. Which carrier governs the aggregate, through the production
 * [BallotCarrierDecision]; what a complete removal has to leave behind, against a real SQLite database carrying the five
 * ballot tables from the schema snapshot, asserted through the same open-state query the notice view's filter performs; and
 * that all three expiry paths actually route through the ballot-aware deletion.
 *
 * [legacyRemovingOnlyTheCarrierLeavesThePollFullyAlive] is the control: it removes only the message, as the old code did,
 * and shows the poll still answering the open-poll query with its votes intact.
 */
class BallotExpiryTest {
    private lateinit var db: Connection

    private val ballotId = 7
    private val otherBallotId = 8
    private val disappearingService = File("src/main/java/ch/threema/app/services/DisappearingMessageService.kt")

    private val ballotTables = listOf("ballot_vote", "ballot_choice", "group_ballot", "identity_ballot", "ballot")

    @BeforeTest
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { statement ->
            for (table in ballotTables + "message") {
                statement.execute(loadCreateStatement(table))
            }
        }
        insertBallotAggregate(ballotId)
        insertBallotAggregate(otherBallotId)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Which carrier governs the aggregate.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the setup carrier governs the ballot it introduced`() {
        val carrier = carrierModel(BallotDataModel.Type.BALLOT_CREATED)

        assertTrue(BallotCarrierDecision.governsBallotAggregate(carrier))
        assertEquals(ballotId, BallotCarrierDecision.governedBallotId(carrier))
    }

    @Test
    fun `a close carrier does not govern the ballot`() {
        val carrier = carrierModel(BallotDataModel.Type.BALLOT_CLOSED)

        assertNull(
            BallotCarrierDecision.governedBallotId(carrier),
            "the setup message may still be visible; removing the poll from under it would be the same defect in reverse",
        )
    }

    @Test
    fun `an ordinary message governs nothing`() {
        val text = MessageModel().apply {
            uid = "uid-text"
            type = MessageType.TEXT
        }

        assertNull(BallotCarrierDecision.governedBallotId(text))
    }

    @Test
    fun `a ballot message with no ballot data governs nothing`() {
        val broken = MessageModel().apply {
            uid = "uid-broken"
            type = MessageType.BALLOT
        }

        assertNull(BallotCarrierDecision.governedBallotId(broken))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // What a complete removal has to leave behind.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `removing the aggregate empties every ballot table for that ballot`() {
        for (table in ballotTables) {
            assertTrue(rowsFor(table, ballotId) > 0, "precondition: $table must hold a row for the expiring poll")
        }

        removeAggregate(ballotId)

        for (table in ballotTables) {
            assertEquals(0, rowsFor(table, ballotId), "$table still holds data for a poll whose message has expired")
        }
    }

    @Test
    fun `removing the aggregate takes the poll off the open-poll surface`() {
        assertEquals(
            listOf(ballotId, otherBallotId),
            openBallotIds(),
            "precondition: both polls are open and would be shown",
        )

        removeAggregate(ballotId)

        assertEquals(
            listOf(otherBallotId),
            openBallotIds(),
            "the expired poll must stop being offered for voting, results and closing",
        )
    }

    @Test
    fun `removing one aggregate leaves every other poll untouched`() {
        removeAggregate(ballotId)

        for (table in ballotTables) {
            assertTrue(rowsFor(table, otherBallotId) > 0, "$table lost data belonging to an unrelated poll")
        }
    }

    @Test
    fun `removing the aggregate also removes the messages that carried it`() {
        insertCarrierRow(id = 1, ballotId = ballotId, type = BallotDataModel.Type.BALLOT_CREATED)
        insertCarrierRow(id = 2, ballotId = ballotId, type = BallotDataModel.Type.BALLOT_CLOSED)
        insertCarrierRow(id = 3, ballotId = otherBallotId, type = BallotDataModel.Type.BALLOT_CREATED)

        removeAggregate(ballotId)
        removeCarrierRows(ballotId)

        assertEquals(0, carrierRows(ballotId), "the close carrier goes with the setup carrier, not on its own timer")
        assertEquals(1, carrierRows(otherBallotId))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The wiring: every expiry path has to use the ballot-aware deletion.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `all three expiry paths delete through the ballot-aware removal`() {
        val source = disappearingService.readText()

        assertEquals(
            3,
            Regex("deleteExpiredMessage\\(").findAll(source).count() - 1,
            "fireDue, the startup sweep and enforceIfExpired must all delete through deleteExpiredMessage " +
                "(count excludes its own declaration)",
        )
        assertTrue(
            source.contains("ballotService.remove(ballot)"),
            "the ballot-aware deletion must remove the aggregate, not just the message",
        )
        assertTrue(
            !Regex("""messageService\.remove\(model, false\)""").containsMatchIn(
                source.substringAfter("fun fireDue()").substringBefore("fun repairAndPurgeOverdue()"),
            ),
            "fireDue must not still remove expired messages directly",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Legacy control: remove only the carrier, as the old code did.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun legacyRemovingOnlyTheCarrierLeavesThePollFullyAlive() {
        insertCarrierRow(id = 1, ballotId = ballotId, type = BallotDataModel.Type.BALLOT_CREATED)

        // The old expiry: the message row and nothing else.
        db.createStatement().use { it.executeUpdate("DELETE FROM `message` WHERE `id` = 1") }

        assertEquals(0, carrierRows(ballotId), "the bubble is gone")
        assertTrue(
            ballotId in openBallotIds(),
            "this is the defect: the poll is still offered on the open-poll surface after its message expired",
        )
        assertTrue(rowsFor("ballot_vote", ballotId) > 0, "with the votes still stored")
        assertTrue(rowsFor("ballot_choice", ballotId) > 0, "and the choices still readable")
    }

    // -----------------------------------------------------------------------------------------------------------------------------

    /** The five deletes `BallotServiceImpl.remove` performs, in its order. */
    private fun removeAggregate(id: Int) {
        db.createStatement().use { statement ->
            statement.executeUpdate("DELETE FROM `ballot_vote` WHERE `ballotId` = $id")
            statement.executeUpdate("DELETE FROM `ballot_choice` WHERE `ballotId` = $id")
            statement.executeUpdate("DELETE FROM `group_ballot` WHERE `ballotId` = $id")
            statement.executeUpdate("DELETE FROM `identity_ballot` WHERE `ballotId` = $id")
            statement.executeUpdate("DELETE FROM `ballot` WHERE `id` = $id")
        }
    }

    private fun removeCarrierRows(id: Int) {
        db.createStatement().use { statement ->
            statement.executeUpdate("DELETE FROM `message` WHERE `body` = '$id'")
        }
    }

    private fun insertBallotAggregate(id: Int) {
        db.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO `ballot` (`id`, `apiBallotId`, `creatorIdentity`, `name`, `state`, `assessment`," +
                    " `type`, `choiceType`, `createdAt`, `modifiedAt`)" +
                    " VALUES ($id, 'api-$id', 'AAAAAAAA', 'lunch?', 'OPEN', 'SINGLE_CHOICE', 'RESULT_ON_CLOSE'," +
                    " 'TEXT', 1700000000000, 1700000000000)",
            )
            statement.executeUpdate(
                "INSERT INTO `ballot_choice` (`ballotId`, `apiBallotChoiceId`, `type`, `name`, `order`, `createdAt`)" +
                    " VALUES ($id, 1, 'TEXT', 'pizza', 0, 1700000000000)",
            )
            statement.executeUpdate(
                "INSERT INTO `ballot_vote` (`ballotId`, `ballotChoiceId`, `votingIdentity`, `choice`, `createdAt`," +
                    " `modifiedAt`) VALUES ($id, 1, 'BBBBBBBB', 1, 1700000000000, 1700000000000)",
            )
            statement.executeUpdate("INSERT INTO `group_ballot` (`ballotId`, `groupId`) VALUES ($id, 1)")
            statement.executeUpdate("INSERT INTO `identity_ballot` (`ballotId`, `identity`) VALUES ($id, 'AAAAAAAA')")
        }
    }

    /**
     * A carrier message. The ballot id lives in the body, the way `BallotDataModel` encodes it, and the type is stored
     * alongside so a setup carrier and a close carrier are distinguishable.
     */
    private fun insertCarrierRow(id: Int, ballotId: Int, type: BallotDataModel.Type) {
        db.prepareStatement(
            "INSERT INTO `message` (`id`, `uid`, `identity`, `outbox`, `type`, `body`, `caption`) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setInt(1, id)
            statement.setString(2, "uid-$id")
            statement.setString(3, "AAAAAAAA")
            statement.setInt(4, 0)
            statement.setInt(5, MessageType.BALLOT.ordinal)
            statement.setString(6, ballotId.toString())
            statement.setString(7, type.name)
            statement.executeUpdate()
        }
    }

    /** The state filter `OpenBallotNoticeView` applies through `BallotService.getBallots`. */
    private fun openBallotIds(): List<Int> =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT `id` FROM `ballot` WHERE `state` = 'OPEN' ORDER BY `id`").use { cursor ->
                buildList {
                    while (cursor.next()) {
                        add(cursor.getInt(1))
                    }
                }
            }
        }

    private fun rowsFor(table: String, id: Int): Int {
        val column = if (table == "ballot") "id" else "ballotId"
        return db.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM `$table` WHERE `$column` = $id").use {
                it.next()
                it.getInt(1)
            }
        }
    }

    private fun carrierRows(ballotId: Int): Int =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM `message` WHERE `body` = '$ballotId'").use {
                it.next()
                it.getInt(1)
            }
        }

    private fun carrierModel(type: BallotDataModel.Type) = MessageModel().apply {
        uid = "uid-carrier"
        this.type = MessageType.BALLOT
        ballotData = BallotDataModel(type, ballotId)
    }

    private fun loadCreateStatement(table: String): String {
        javaClass.getResourceAsStream("/database/schema.sql")!!.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith("CREATE TABLE `$table` (") || line.startsWith("CREATE TABLE `$table`(")) {
                    return line
                }
            }
        }
        error("no CREATE TABLE for `$table` in the schema snapshot")
    }
}
