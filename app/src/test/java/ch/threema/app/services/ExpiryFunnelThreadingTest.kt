package ch.threema.app.services

import ch.threema.storage.models.MessageModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper (tenth fork review, F10-06): the expiry funnel answers from memory and removes on a worker.
 *
 * **The defect.** The funnel computed the answer AND performed the removal, synchronously, on whatever thread was
 * about to draw the row: an Activity's initialisation, user-interface actions, and two adapters that posted the
 * enforcement back to the MAIN looper, which guaranteed the disk work landed on the UI thread. The full lint gate
 * called it out as `WrongThread` and it was suppressed rather than fixed, on the stated grounds that the work is "one
 * conditional row update and one delete". It is not: the deadline repair does a lookup, an update and a reload, and
 * `MessageService.removeIfStillDue` performs SQL, cache reconciliation, pending-send cancellation, filesystem removal
 * and synchronous listener dispatch, and can take an entire ballot aggregate with it. On a build that was reporting
 * ANRs, that is a liveness risk whether or not any particular ANR is attributable to it.
 *
 * **The primary enforcement is now the lint gate itself, not this file.** With both suppressions removed and no
 * baseline entry standing in for them, any future call from the `@AnyThread` funnel into `@WorkerThread` work fails the
 * release build. That was verified directly: restoring the pre-fix shape produces
 * `DisappearingMessageService.kt: Error: Method deleteExpiredMessage must be called from the worker thread, currently
 * inferred thread is any thread [WrongThread]`, and removing it again returns lint to 135 baseline-filtered errors and
 * zero unfiltered ones - the same numbers as before this change, so nothing was quietly re-baselined.
 *
 * What this file adds is the executable half - the presentation decision, which is the part that had to keep working
 * synchronously - plus the structural facts a future edit could undo without lint noticing, each proven red by removing
 * the line it names.
 */
class ExpiryFunnelThreadingTest {

    private val service = File("src/main/java/ch/threema/app/services/DisappearingMessageService.kt")
    private val composeAdapter = File("src/main/java/ch/threema/app/adapters/ComposeMessageAdapter.java")
    private val chatDecorator = File("src/main/java/ch/threema/app/adapters/decorators/ChatAdapterDecorator.java")
    private val baseline = File("lint-baseline-onprem.xml")

    private val startedAt = 1_700_000_000_000L

    // -----------------------------------------------------------------------------------------------------------------
    // The presentation decision, which had to stay synchronous
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `an overdue message is withheld without asking the database`() {
        // The whole reason the removal could move off the caller's thread: this answer never needed the database. It is
        // arithmetic on the model the caller is already holding.
        val model = model(expireStartedAt = startedAt, expiresAt = System.currentTimeMillis() - 1_000L)

        assertTrue(DisappearingMessageService.isExpired(model))
    }

    @Test
    fun `a message whose deadline is missing is still withheld, derived from the frozen timer`() {
        // This is the case the old funnel used a database REPAIR to answer, on the UI thread. The same conclusion is
        // reachable from the frozen timer alone, so presentation fails closed immediately and the repair - which is a
        // write - happens on the worker.
        val model = model(expireStartedAt = System.currentTimeMillis() - 60_000L, expiresAt = null, timerSeconds = 30)

        assertTrue(DisappearingMessageService.isExpired(model))
    }

    @Test
    fun `a message whose deadline has not arrived is shown`() {
        val model = model(expireStartedAt = startedAt, expiresAt = System.currentTimeMillis() + 600_000L)

        assertFalse(DisappearingMessageService.isExpired(model))
    }

    @Test
    fun `a countdown that never started is shown`() {
        val model = model(expireStartedAt = null, expiresAt = null, timerSeconds = 30)

        assertFalse(DisappearingMessageService.isExpired(model))
    }

    @Test
    fun `a started countdown with no timer and no deadline is shown`() {
        // Nothing to derive a deadline from. Withholding here would hide a message on no evidence at all.
        val model = model(expireStartedAt = startedAt, expiresAt = null, timerSeconds = null)

        assertFalse(DisappearingMessageService.isExpired(model))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The threading split
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the funnel carries no thread suppression`() {
        assertFalse(
            service.readText().contains("@Suppress(\"WrongThread\")"),
            "a suppression is not acceptance; the gate has to be able to see this file",
        )
    }

    @Test
    fun `no baseline entry stands in for the removed suppressions`() {
        // Deleting a suppression and baselining the finding it was hiding would be the same waiver in a different file.
        val entries = baseline.readText().split("<issue")
        val wrongThreadForThisFile = entries.filter {
            it.contains("id=\"WrongThread\"") && it.contains("DisappearingMessageService")
        }
        assertTrue(
            wrongThreadForThisFile.isEmpty(),
            "found ${wrongThreadForThisFile.size} baselined WrongThread entries for the funnel",
        )
    }

    @Test
    fun `the durable half is scheduled onto a worker`() {
        val body = bodyOf(service.readText(), "private fun enqueueExpiryEnforcement(")
        assertTrue(
            body.contains("RuntimeUtil.runOnWorkerThread"),
            "the SQL, the file removal and the ballot cleanup must leave the caller's thread",
        )
    }

    @Test
    fun `the durable half declares the thread it needs`() {
        // Annotating these is what lets lint prove the split rather than trust it. The pre-fix code had the same
        // annotations and reached them from @AnyThread, which is exactly what it flagged.
        val source = service.readText()
        for (signature in listOf(
            "private fun enforceExpiredOnWorker(",
            "private fun repairMissingDeadline(",
            "private fun deleteExpiredMessage(",
        )) {
            val declaration = source.substring(0, source.indexOf(signature))
            assertTrue(
                declaration.trimEnd().endsWith("@WorkerThread"),
                "$signature must declare @WorkerThread",
            )
        }
    }

    @Test
    fun `the in-flight claim is taken before the hop and released after the work`() {
        // Otherwise a list that rebinds an expired row every frame schedules a worker per bind.
        val body = bodyOf(service.readText(), "private fun enqueueExpiryEnforcement(")
        val claimIndex = body.indexOf("inFlight.add(uid)")
        val hopIndex = body.indexOf("RuntimeUtil.runOnWorkerThread")
        assertTrue(claimIndex in 0..<hopIndex, "the claim must precede the hop")
        assertTrue(
            body.contains("inFlight.remove(uid)"),
            "and must be released, or the message can never be enforced again",
        )
    }

    @Test
    fun `no adapter posts the removal to the main looper`() {
        // The inversion the fix removes: both adapters already used the pure predicate to hide the row, and then posted
        // the DURABLE work to the main thread, so the disk access was on the UI thread by construction.
        for (file in listOf(composeAdapter, chatDecorator)) {
            val source = file.readText()
            assertFalse(
                source.contains("post(() -> ch.threema.app.services.DisappearingMessageService.enforceIfExpired"),
                "${file.name} must not post durable enforcement to the main looper",
            )
        }
        val decoratorConfigure = bodyOf(chatDecorator.readText(), "final protected void configure(")
        assertFalse(
            decoratorConfigure.contains("Looper.getMainLooper()"),
            "the decorator must not construct a main-looper handler for enforcement",
        )
    }

    @Test
    fun `the removal stays database-authoritative`() {
        // Presentation fails closed on in-memory arithmetic, which is the safe direction for a stale snapshot. Deletion
        // must fail SAFE, so the worker re-checks against the row and claims through the conditional delete: a timer
        // turned off or a deadline moved between the two must leave the content alone.
        val body = bodyOf(service.readText(), "private fun enforceExpiredOnWorker(")
        assertTrue(body.contains("if (expiresAt > now)"), "the worker must re-check due-ness against the row")
        assertTrue(body.contains("deleteExpiredMessage("), "and remove only through the claim")
        assertTrue(
            bodyOf(service.readText(), "private fun deleteExpiredMessage(").contains("removeIfStillDue("),
            "which is the conditional claim F5-04 established",
        )
    }

    private fun model(expireStartedAt: Long?, expiresAt: Long?, timerSeconds: Int? = 30): MessageModel =
        MessageModel().apply {
            this.expireStartedAt = expireStartedAt
            this.expiresAt = expiresAt
            this.disappearingTimerSeconds = timerSeconds
        }

    /**
     * The body of the declaration starting with [signature], by brace matching from the first `{` after it.
     */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "signature not found: $signature" }
        var index = source.indexOf('{', start)
        require(index >= 0) { "no body for: $signature" }
        var depth = 0
        val body = StringBuilder()
        while (index < source.length) {
            val character = source[index]
            if (character == '{') depth++
            if (depth > 0) body.append(character)
            if (character == '}') {
                depth--
                if (depth == 0) break
            }
            index++
        }
        return body.toString()
    }
}
