package ch.threema.app.services

import ch.threema.app.services.MessageRowHarness.Companion.BASE_TIME
import ch.threema.app.services.MessageRowHarness.Companion.CONTACT_TABLE
import ch.threema.app.services.MessageRowHarness.Companion.GROUP_TABLE
import ch.threema.app.utils.MessageUtil
import java.io.File
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper (device report 2026-08-06, U-01 / U-02): a message deleted for everyone is not unread.
 *
 * **The failure this reproduces.** Reported from a device: opening a group chat always landed on the unread divider,
 * anchored at an old deleted message, however many times everything after it had been read. The debug log named it on
 * every open, identically, three opens in a row:
 *
 * ```
 * markAsRead: gave up marking f5b2bd4dad3c88ae read after 3 superseded attempts
 * Not marking f5b2bd4dad3c88ae as read: the row was superseded
 * ```
 *
 * A race that never varies is not a race. `MessageRowUpdate` refuses a deleted row structurally (F5-04, "deletion
 * always wins"), and the first-read transition is one of the writes it refuses - so the write could not succeed, this
 * time or ever. The loop read "zero rows" as another transition winning, re-read the same tombstone, decided the same
 * thing, and spent all three attempts on an answer that was already final. `isRead` stayed 0, the unread queries had no
 * deletion predicate, and so the row was unread for the rest of the conversation's life: the divider anchored at it on
 * every open, and the conversation's unread count returned to one after every refresh.
 *
 * **The two shipped decisions, both executed here.**
 *
 * - the unread set is defined by ONE fragment, `AbstractMessageModelFactory.UNREAD_ROW_WHERE`, and it excludes a row
 *   deleted for everyone. That repairs the rows already sitting on devices, with no migration: the reported message
 *   leaves the unread set the moment this ships;
 * - a write that is permanently refused stops instead of retrying. `isDeletedForEveryone` is the twin of the
 *   `current == null` check every one of these loops already made.
 *
 * The legacy controls run the predicate as it shipped, so each assertion is anchored against the behaviour it changed
 * rather than only against itself.
 */
class UnreadTombstoneTest {

    private val harness = MessageRowHarness(CONTACT_TABLE, GROUP_TABLE)

    @AfterTest
    fun tearDown() = harness.close()

    // -----------------------------------------------------------------------------------------------------------------------------
    // The reported failure, at row level.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a group message deleted for everyone leaves the unread set`() {
        arriveUnread(GROUP_TABLE, 91)
        assertEquals(listOf(91), harness.unreadIds(GROUP_TABLE, "groupId", 7), "it arrived unread")

        assertTrue(harness.deleteForEveryone(GROUP_TABLE, 91, DELETED_AT))

        assertEquals(
            emptyList(),
            harness.unreadIds(GROUP_TABLE, "groupId", 7),
            "the divider anchored here on every open, and the conversation count came back to one after every refresh",
        )
    }

    @Test
    fun `a contact message deleted for everyone leaves the unread set`() {
        arriveUnread(CONTACT_TABLE, 92)
        assertEquals(listOf(92), harness.unreadIds(CONTACT_TABLE, "identity", IDENTITY))

        assertTrue(harness.deleteForEveryone(CONTACT_TABLE, 92, DELETED_AT))

        assertEquals(0, harness.countUnread(CONTACT_TABLE, "identity", IDENTITY))
    }

    @Test
    fun `the read this was waiting for could never have happened`() {
        arriveUnread(GROUP_TABLE, 91)
        harness.deleteForEveryone(GROUP_TABLE, 91, DELETED_AT)

        // Exactly what markReadDurably does, three times, exactly as the loop did on the device. Every attempt is
        // refused by the structural predicate, so the retry was never the answer: the row it re-read each time was the
        // row that had just refused it.
        repeat(3) { attempt ->
            assertFalse(
                harness.apply(GROUP_TABLE, 91, firstRead()),
                "attempt ${attempt + 1} must be refused, and refused for the same reason as the last",
            )
        }
        assertEquals(0L, harness.longOf(GROUP_TABLE, 91, "isRead"), "which is why it stayed unread")
    }

    @Test
    fun `a tombstone is not offered to the mark-as-read path at all`() {
        arriveUnread(GROUP_TABLE, 91)
        val unread = harness.requireModel(GROUP_TABLE, 91)
        assertTrue(MessageUtil.canMarkAsRead(unread), "an ordinary unread message still can be")

        harness.deleteForEveryone(GROUP_TABLE, 91, DELETED_AT)

        assertFalse(
            MessageUtil.canMarkAsRead(harness.requireModel(GROUP_TABLE, 91)),
            "no attempt, no retry, and no read receipt for a message its sender withdrew",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The controls: what the predicate did before, and what it must still do.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the predicate without the deletion clause still counts the tombstone`() {
        arriveUnread(GROUP_TABLE, 91)
        harness.deleteForEveryone(GROUP_TABLE, 91, DELETED_AT)

        assertEquals(
            1,
            harness.countMatching(GROUP_TABLE, "groupId", 7, LEGACY_UNREAD_WHERE),
            "the control: the shipped predicate said this row still wanted the user's attention, for good",
        )
    }

    @Test
    fun `an ordinary unread message is still unread, and reading it still works`() {
        arriveUnread(GROUP_TABLE, 91)

        assertTrue(harness.apply(GROUP_TABLE, 91, firstRead()), "nothing refuses a row that is merely unread")

        assertEquals(1L, harness.longOf(GROUP_TABLE, 91, "isRead"))
        assertEquals(READ_AT, harness.longOf(GROUP_TABLE, 91, "readAtUtc"))
        assertEquals(emptyList(), harness.unreadIds(GROUP_TABLE, "groupId", 7), "and it leaves the set by being read")
    }

    @Test
    fun `the other clauses of the unread predicate still hold`() {
        arriveUnread(GROUP_TABLE, 91)
        harness.insertGroupRow(messageId = 93, outbox = true)
        harness.insertGroupRow(messageId = 94, outbox = false, isRead = true)
        harness.insertGroupRow(messageId = 95, outbox = false)
        harness.markStatusMessage(GROUP_TABLE, 95)
        harness.insertGroupRow(messageId = 96, outbox = false)
        harness.markUnsaved(GROUP_TABLE, 96)

        assertEquals(
            listOf(91),
            harness.unreadIds(GROUP_TABLE, "groupId", 7),
            "outgoing, already read, status and unsaved rows must still be excluded, as they always were",
        )
    }

    @Test
    fun `a hard-deleted row is not unread either, as it never was`() {
        arriveUnread(CONTACT_TABLE, 92)
        harness.hardDelete(CONTACT_TABLE, 92)

        assertEquals(0, harness.countUnread(CONTACT_TABLE, "identity", IDENTITY))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The call sites. Source assertions, supplementing the behaviour above: neither the factories' Android queries nor
    // MessageServiceImpl can be constructed in a JVM unit test (see the class doc of MessageRowHarness).
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `both factories ask the unread question through the fragment executed above`() {
        listOf(
            "src/main/java/ch/threema/storage/factories/MessageModelFactory.java",
            "src/main/java/ch/threema/storage/factories/GroupMessageModelFactory.java",
        ).forEach { path ->
            val source = File(path).readText()
            listOf("countUnreadMessages(", "getUnreadMessages(").forEach { method ->
                val start = source.indexOf(method)
                assertTrue(start >= 0, "$path must still have $method")
                val query = source.substring(start, source.indexOf("new String[]{", start))

                assertTrue(query.contains("UNREAD_ROW_WHERE"), "$path.$method must use the shared fragment")
                assertFalse(
                    query.contains("COLUMN_IS_READ + \"=0\""),
                    "$path.$method still spells the predicate out, so it can drift from the one that was tested",
                )
            }
        }
        assertTrue(
            MessageRowHarness.unreadRowWhere().contains("deletedAtUtc IS NULL"),
            "and the fragment the tests above executed is the one that carries the deletion clause",
        )
    }

    @Test
    fun `the unread filter carries the deletion clause too`() {
        val factory = File(ABSTRACT_FACTORY).readText()
        val filter = factory.substring(factory.indexOf("if (filter.onlyUnread()) {"))
        val clause = filter.substring(0, filter.indexOf("}"))

        assertTrue(
            clause.contains("NOT_SOFT_DELETED"),
            "the filter is a third caller of the same question and has to give the same answer",
        )
    }

    @Test
    fun `the divider is placed by the same definition of unread`() {
        val locator = File(LOCATOR).readText()
        assertTrue(
            locator.contains("!statusMessage && !deletedForEveryone"),
            "a divider drawn at a row the count excludes is a divider pointing at nothing",
        )

        val service = File(SERVICE).readText()
        val flags = service.substring(service.indexOf("new UnreadDividerLocator.MessageFlags("))
        assertTrue(
            flags.substring(0, flags.indexOf("));")).contains("m.getDeletedAt() != null"),
            "and the caller must actually pass it",
        )
    }

    @Test
    fun `every reload loop stops on a permanent refusal instead of retrying`() {
        val service = File(SERVICE).readText()

        // One per reload-decide-write loop. commitEditDurably has refused a deleted row explicitly since F7-03 and
        // reads it inline, which is why it is counted separately rather than converted: nine loops, one rule.
        assertEquals(
            8,
            Regex("if \\(isDeletedForEveryone\\(").findAll(service).count(),
            "a loop without it spends every attempt it has re-reading the same tombstone",
        )
        assertTrue(
            service.contains("current == null || current.getDeletedAt() != null"),
            "commitEditDurably is the ninth, and had it first",
        )

        // The reported one, specifically: the refusal must be decided BEFORE the write it would otherwise retry. The
        // region is bounded by the NEXT method rather than by brace matching, which truncates on the literal "{}" that
        // this file's log lines are full of.
        val markRead = service.substring(
            service.indexOf("private boolean markReadDurably("),
            service.indexOf("public boolean updateReceivedTimestamp("),
        )
        val refusal = markRead.indexOf("it was deleted for everyone")
        val write = markRead.indexOf("MessageLifecycleUpdates.firstRead(")
        assertTrue(refusal in 0 until write, "the deletion is answered before a first-read write is even built")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The scenario, written once.
    // -----------------------------------------------------------------------------------------------------------------------------

    /** An incoming message that has arrived and not yet been read: the state the reported message was in. */
    private fun arriveUnread(table: String, messageId: Int) {
        if (table == GROUP_TABLE) {
            harness.insertGroupRow(messageId = messageId, outbox = false)
        } else {
            harness.insertContactRow(messageId = messageId, outbox = false)
        }
    }

    /** The update `markReadDurably` builds, with no countdown: the read state alone. */
    private fun firstRead() = MessageLifecycleUpdates.firstRead(Date(READ_AT), 30, null, null, null)

    private companion object {
        const val SERVICE = "src/main/java/ch/threema/app/services/MessageServiceImpl.java"
        const val LOCATOR = "src/main/java/ch/threema/app/services/UnreadDividerLocator.java"
        const val ABSTRACT_FACTORY = "src/main/java/ch/threema/storage/factories/AbstractMessageModelFactory.java"
        const val IDENTITY = "ECHOECHO"
        const val DELETED_AT = BASE_TIME + 60_000
        const val READ_AT = BASE_TIME + 120_000

        /** The unread predicate as it shipped before this fix: everything except the deletion clause. */
        const val LEGACY_UNREAD_WHERE = "outbox=0 AND isSaved=1 AND isRead=0 AND isStatusMessage=0"
    }
}
