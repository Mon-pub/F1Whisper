package ch.threema.app.services

import ch.threema.app.services.MessageRowHarness.Companion.BASE_TIME
import ch.threema.app.services.MessageRowHarness.Companion.CONTACT_TABLE
import ch.threema.storage.MessageRowUpdate
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageState
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (sixth fork review, F6-03): an authoritative send time observed AFTER a receipt must shorten the countdown
 * that receipt provisionally started, while the higher display state stays exactly where it is.
 *
 * The fifth review got the rule right and asked the wrong question with it. `DELIVERED -> SENT` is correctly refused as
 * a display-state downgrade; the clock decision was then handed the state the row had ENDED UP with, which after that
 * refusal is still `DELIVERED`, so the `SENT` branch was unreachable from production. A message whose receipt overtook
 * its send confirmation therefore expired at `receipt + timer` instead of `send + timer` - longer than the interval its
 * sender committed to by the whole acknowledgement or reordering delay. The previous test suite passed because it called
 * the pure helper with `SENT` directly, which is the one argument production never passed.
 *
 * So these run the whole transition - the timestamp switch, the REAL `MessageUtil.canChangeToState` gate, the terminal
 * display bit, the forward-security column and the clock - against a row in a real database, and assert the columns
 * afterwards. [legacyResultingStateArgumentLosesTheCorrection] is the control: it asks the clock the pre-fix question
 * and shows the correction being dropped.
 *
 * What this cannot cover: the reload, the retry and the mirror back onto the caller's instance live in
 * `MessageServiceImpl`, which cannot be constructed on the JVM. That it delegates here is asserted narrowly against the
 * source.
 */
class OutgoingTransitionIntegrationTest {
    private lateinit var harness: MessageRowHarness

    private val messageId = 1
    private val sentAt = Date(BASE_TIME)
    private val deliveredAt = Date(BASE_TIME + 60_000L)
    private val timerSeconds = 30

    @BeforeTest
    fun setUp() {
        harness = MessageRowHarness(CONTACT_TABLE)
        harness.insertContactRow(
            messageId,
            outbox = true,
            state = MessageState.SENDING,
            timerSeconds = timerSeconds,
        )
    }

    @AfterTest
    fun tearDown() {
        harness.close()
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The finding.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `an authoritative SENT behind a DELIVERED shortens the clock and keeps the higher state`() {
        transition(MessageState.DELIVERED, deliveredAt)

        assertEquals(MessageState.DELIVERED.toString(), state())
        assertEquals(deliveredAt.time, expireStartedAt(), "a receipt starts the clock provisionally")

        transition(MessageState.SENT, sentAt)

        assertEquals(MessageState.DELIVERED.toString(), state(), "the display state must NOT be downgraded")
        assertEquals(sentAt.time, expireStartedAt(), "but the countdown must move back to the authoritative send time")
        assertEquals(sentAt.time + timerSeconds * 1000L, expiresAt())
        assertEquals(sentAt.time, harness.longOf(CONTACT_TABLE, messageId, "postedAtUtc"), "and the send time is recorded")
    }

    @Test
    fun `an authoritative SENT behind a READ shortens the clock and keeps the higher state`() {
        transition(MessageState.READ, deliveredAt)

        assertEquals(MessageState.READ.toString(), state())
        assertEquals(deliveredAt.time, expireStartedAt())

        transition(MessageState.SENT, sentAt)

        assertEquals(MessageState.READ.toString(), state())
        assertEquals(sentAt.time, expireStartedAt())
        assertEquals(sentAt.time + timerSeconds * 1000L, expiresAt())
    }

    @Test
    fun legacyResultingStateArgumentLosesTheCorrection() {
        transition(MessageState.DELIVERED, deliveredAt)

        // The pre-fix question: the state the row ends up with, which the refusal leaves at DELIVERED.
        val corrected = OutgoingClockDecision.resolveStart(
            transitionState = MessageState.DELIVERED,
            persistedState = MessageState.DELIVERED,
            transitionAtMillis = sentAt.time,
            currentStartMillis = deliveredAt.time,
        )

        assertNull(
            corrected,
            "this is the defect: asked about the resulting state, the rule never sees a SENT and the message keeps " +
                "expiring a receipt-delay too late",
        )
        assertEquals(deliveredAt.time, expireStartedAt())
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Nothing may move a start later.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a duplicate SENT does not extend the countdown`() {
        transition(MessageState.SENT, sentAt)
        assertEquals(sentAt.time, expireStartedAt())

        transition(MessageState.SENT, Date(sentAt.time + 120_000L))

        assertEquals(sentAt.time, expireStartedAt(), "a resend confirmation must not buy the message another interval")
        assertEquals(sentAt.time + timerSeconds * 1000L, expiresAt())
    }

    @Test
    fun `a later receipt does not extend the countdown`() {
        transition(MessageState.SENT, sentAt)

        transition(MessageState.DELIVERED, deliveredAt)
        transition(MessageState.READ, Date(deliveredAt.time + 60_000L))

        assertEquals(sentAt.time, expireStartedAt())
        assertEquals(MessageState.READ.toString(), state())
    }

    @Test
    fun `a DELIVERED that arrives after a SENT with an earlier time does not move the start`() {
        transition(MessageState.SENT, sentAt)

        // A receipt is never authoritative, even when its timestamp happens to be earlier.
        transition(MessageState.DELIVERED, Date(sentAt.time - 5_000L))

        assertEquals(sentAt.time, expireStartedAt())
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The ordinary path still behaves.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a SENT starts the clock from its own timestamp, not from now`() {
        transition(MessageState.SENT, sentAt)

        assertEquals(MessageState.SENT.toString(), state())
        assertEquals(sentAt.time, expireStartedAt())
        assertEquals(sentAt.time + timerSeconds * 1000L, expiresAt())
    }

    @Test
    fun `a message with no timer gets a state but no deadline`() {
        harness.insertContactRow(2, outbox = true, state = MessageState.SENDING, timerSeconds = null)

        val current = harness.requireModel(CONTACT_TABLE, 2)
        val update = OutgoingTransitionPlanner.plan(current, MessageState.SENT, sentAt, null, false)
        assertNotNull(update)
        assertTrue(harness.apply(CONTACT_TABLE, 2, update))

        assertEquals(MessageState.SENT.toString(), harness.stringOf(CONTACT_TABLE, 2, "state"))
        assertNull(harness.longOf(CONTACT_TABLE, 2, "expiresAtUtc"))
        assertNull(harness.longOf(CONTACT_TABLE, 2, "expireStartedAtUtc"))
    }

    @Test
    fun `a pre-terminal state starts no clock at all`() {
        val current = harness.requireModel(CONTACT_TABLE, messageId)
        current.state = MessageState.PENDING
        val update = OutgoingTransitionPlanner.plan(current, MessageState.SENDING, sentAt, null, false)

        if (update != null) {
            assertTrue(harness.apply(CONTACT_TABLE, messageId, update))
        }
        assertNull(expireStartedAt(), "a message still on this device must never start expiring")
    }

    @Test
    fun `the state, its timestamp and the countdown reach disk in one statement`() {
        val current = harness.requireModel(CONTACT_TABLE, messageId)
        val update = OutgoingTransitionPlanner.plan(current, MessageState.SENT, sentAt, null, false)
        assertNotNull(update)

        val assigned = update.assignments.keys
        assertTrue(AbstractMessageModel.COLUMN_STATE in assigned)
        assertTrue(AbstractMessageModel.COLUMN_POSTED_AT in assigned)
        assertTrue(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT in assigned)
        assertTrue(
            AbstractMessageModel.COLUMN_EXPIRES_AT in assigned,
            "two statements meant a process death between them left a terminal row with no deadline, which the startup " +
                "repair pass deliberately declines to fix",
        )
        assertTrue(
            update.conditions.containsKey(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT),
            "and the write must still expect the start it decided from",
        )
    }

    @Test
    fun `a transition cannot recreate a message deleted while it was in flight`() {
        val current = harness.requireModel(CONTACT_TABLE, messageId)
        harness.hardDelete(CONTACT_TABLE, messageId)

        val update = OutgoingTransitionPlanner.plan(current, MessageState.SENT, sentAt, null, false)
        assertNotNull(update)

        assertTrue(!harness.apply(CONTACT_TABLE, messageId, update))
        assertEquals(0, harness.rowCount(CONTACT_TABLE, messageId))
    }

    @Test
    fun `the service delegates the whole decision to the planner`() {
        val service = java.io.File("src/main/java/ch/threema/app/services/MessageServiceImpl.java").readText()
        val body = service.substring(service.indexOf("public boolean applyOutgoingStateTransition("))
            .substringBefore("/** Make the caller's instance agree")

        assertTrue(body.contains("OutgoingTransitionPlanner.plan("), "the decision must be the one this test drives")
        assertTrue(body.contains("reloadPersistedModel(messageModel)"), "and it must be taken against the current row")
        assertTrue(body.contains("mirrorOutgoingTransition(messageModel, current)"))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------------------------------------------

    /** Reload, plan, write - what `applyOutgoingStateTransition` does around the decision. */
    private fun transition(state: MessageState, at: Date): MessageRowUpdate? {
        val current = harness.requireModel(CONTACT_TABLE, messageId)
        val update = OutgoingTransitionPlanner.plan(current, state, at, null, false) ?: return null
        assertTrue(harness.apply(CONTACT_TABLE, messageId, update), "the transition to $state must be written")
        return update
    }

    private fun state(): String? = harness.stringOf(CONTACT_TABLE, messageId, "state")

    private fun expireStartedAt(): Long? = harness.longOf(CONTACT_TABLE, messageId, "expireStartedAtUtc")

    private fun expiresAt(): Long? = harness.longOf(CONTACT_TABLE, messageId, "expiresAtUtc")
}
