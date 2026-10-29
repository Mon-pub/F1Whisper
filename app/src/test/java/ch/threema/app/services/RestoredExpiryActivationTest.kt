package ch.threema.app.services

import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F1Whisper (fourth fork review, F4-07): what a restore must leave behind for the disappearing-message engine.
 *
 * The defect: restore copies `disappearingTimerSeconds`, `expireStartedAtUtc` and `expiresAtUtc` verbatim, which is right -
 * the deadlines are absolute, so a message restored after its deadline has genuinely expired. What was missing is that
 * nothing acted on them. Rows go in through the factories, and the success path reconnected and posted the completion
 * notification without ever touching the disappearing service. A restored message already past its deadline just stayed in
 * the database, and a restored future deadline had no alarm behind it; both waited for an unrelated trigger - opening that
 * chat, a reboot, a package update - which on a freshly restored device may be a long time coming.
 *
 * Two things are checked here. The end state the lifecycle has to produce for each of the three restored shapes, driven
 * through the real [DisappearingMessageService.isExpired] and [AlarmPlanDecision]; and the fact that the restore success
 * path actually invokes that lifecycle, before it reports success.
 *
 * The second is a source assertion, used for the same reason as in
 * [ch.threema.app.services.IncomingFreezeOrderingSourceTest]: `repairAndPurgeOverdue` needs the whole service graph and a
 * real database, so no JVM test can observe the call, and removing it would be silent.
 */
class RestoredExpiryActivationTest {
    /**
     * The real clock, read once. [DisappearingMessageService.isExpired] compares against `System.currentTimeMillis()`
     * and takes no injectable time, so a fixed epoch would make every row look overdue. Every offset below is at least
     * thirty seconds, so the test cannot be flaky at any plausible execution speed.
     */
    private val now = System.currentTimeMillis()
    private val restoreService = File("src/main/java/ch/threema/app/backuprestore/csv/RestoreService.java")

    private fun restoredRow(uid: String, expiresAt: Long) = MessageModel().apply {
        this.uid = uid
        identity = "AAAAAAAA"
        isOutbox = false
        disappearingTimerSeconds = 30
        // Restore writes all three columns verbatim from the backup.
        expireStartedAt = expiresAt - 30_000
        this.expiresAt = expiresAt
    }

    /**
     * The lifecycle's contract, expressed over a restored set: everything overdue is purged, and the alarm is armed for the
     * earliest deadline still in the future (or cancelled when none remains).
     */
    private fun purgeAndRearm(restored: List<AbstractMessageModel>): Pair<List<AbstractMessageModel>, AlarmAction> {
        val (overdue, remaining) = restored.partition { model -> DisappearingMessageService.isExpired(model) }
        val earliest = remaining.mapNotNull { it.expiresAt }.minOrNull()
        val target = earliest?.let { AlarmTarget.At(it) } ?: AlarmTarget.None
        return overdue to AlarmPlanDecision.resolve(target, now, AlarmScheduler.DEFAULT_RETRY_DELAY_MILLIS)
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The three restored shapes.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `an overdue-only restore purges everything and leaves no alarm`() {
        val restored = listOf(
            restoredRow("a", expiresAt = now - 60_000),
            restoredRow("b", expiresAt = now - 1),
        )

        val (purged, alarm) = purgeAndRearm(restored)

        assertEquals(listOf("a", "b"), purged.map { it.uid }, "every restored message past its deadline must go immediately")
        assertEquals(AlarmAction.Cancel, alarm, "nothing is left to wake up for")
    }

    @Test
    fun `a future-only restore purges nothing and arms the earliest deadline`() {
        val restored = listOf(
            restoredRow("a", expiresAt = now + 600_000),
            restoredRow("b", expiresAt = now + 60_000),
            restoredRow("c", expiresAt = now + 3_600_000),
        )

        val (purged, alarm) = purgeAndRearm(restored)

        assertTrue(purged.isEmpty(), "nothing has expired yet")
        assertEquals(
            AlarmAction.ArmAt(now + 60_000),
            alarm,
            "a restored future deadline needs an alarm behind it, or it waits for an unrelated trigger",
        )
    }

    @Test
    fun `a mixed restore purges the overdue rows and arms the earliest survivor`() {
        val restored = listOf(
            restoredRow("overdue-1", expiresAt = now - 120_000),
            restoredRow("future-late", expiresAt = now + 600_000),
            restoredRow("overdue-2", expiresAt = now - 5),
            restoredRow("future-soon", expiresAt = now + 30_000),
        )

        val (purged, alarm) = purgeAndRearm(restored)

        assertEquals(setOf("overdue-1", "overdue-2"), purged.map { it.uid }.toSet())
        assertEquals(AlarmAction.ArmAt(now + 30_000), alarm)
    }

    @Test
    fun `a restore with no disappearing messages at all leaves the alarm cancelled`() {
        val (purged, alarm) = purgeAndRearm(emptyList())

        assertTrue(purged.isEmpty())
        assertEquals(AlarmAction.Cancel, alarm)
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The call site.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the restore success path runs the expiry lifecycle before reporting success`() {
        val source = restoreService.readText()

        val activateAt = source.indexOf("activateRestoredDisappearingMessages();")
        val successAt = source.indexOf("restoreSuccess = true;")
        val finishedAt = source.indexOf("onFinished(null);")

        assertTrue(activateAt >= 0, "the restore must reactivate the expiry engine for the data it just wrote")
        assertTrue(successAt >= 0 && finishedAt >= 0, "this test's anchors have drifted")
        assertTrue(
            activateAt < successAt && activateAt < finishedAt,
            "restored expiry state must be live before the restore reports completion, not whenever some later " +
                "unrelated trigger happens to sweep the database",
        )
        assertTrue(
            source.contains("DisappearingMessageService.getInstance().repairAndPurgeOverdue()"),
            "it must run the bounded repair/purge/rearm lifecycle, not a partial substitute",
        )
    }
}
