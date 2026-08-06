package ch.threema.app.utils

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper (fifth fork review, F5-10 and F5-03): releasing the page-ownership slot on EVERY outcome, and the atomic
 * transition that guards shared conversation state.
 *
 * **F5-10.** The fork acquires a single-load slot before dispatching a refresh and releases it only from the view model's
 * SUCCESS event. The query coroutine emitted nothing at all when it threw, so on a failure that left the process alive
 * the slot stayed owned: the refresh indicator kept spinning and every later page request was rejected until the
 * conversation was reset. The ownership leak is fork-exclusive even though the coroutine predates the fork.
 *
 * **F5-03.** Checking `isCurrent` and then mutating the cursor was a check-then-act, and a conversation reset landing in
 * that gap did not stop the stale worker restoring the previous conversation's boundary over the new one.
 *
 * The slot's own mechanics (acquire, reject a second dispatch, generation-guarded release) are covered by
 * `PageRequestGuardTest`; what is added here is the release-on-failure path and the atomicity of `runIfCurrent`. The view
 * model's half - emitting a terminal result on every path - is asserted against the source, and was proven red by
 * removing the catch.
 */
class PageOwnershipReleaseTest {
    private val timeoutSeconds = 5L
    private val blockedWindowMillis = 300L

    private val viewModel = File("src/main/java/ch/threema/app/fragments/composemessage/ComposeMessageViewModel.kt")
    private val fragment = File("src/main/java/ch/threema/app/fragments/composemessage/ComposeMessageFragment.java")

    // -----------------------------------------------------------------------------------------------------------------------------
    // F5-10: a load that ends without rows.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a load that ends without rows still frees the slot`() {
        val guard = PageRequestGuard()
        val token = guard.tryBeginLoad()

        // The query threw. Under the old view model this emitted nothing at all, so this release never happened.
        guard.endLoad(token)

        assertTrue(
            guard.tryBeginLoad() != PageRequestGuard.NO_TOKEN,
            "a failed query must not wedge pagination for the life of the process",
        )
    }

    @Test
    fun legacyMissingFailureResultWedgesPaginationForTheConversation() {
        val guard = PageRequestGuard()

        // The old shape: acquire, then throw, and emit nothing. Nothing releases the slot.
        guard.tryBeginLoad()

        assertEquals(
            PageRequestGuard.NO_TOKEN,
            guard.tryBeginLoad(),
            "this is the defect: every later page request is rejected until the conversation is reset",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // F5-03: the atomic transition.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `an action for the current generation runs`() {
        val guard = PageRequestGuard()
        var ran = false

        assertTrue(guard.runIfCurrent(guard.current()) { ran = true })
        assertTrue(ran)
    }

    @Test
    fun `an action for an invalidated generation does not run`() {
        val guard = PageRequestGuard()
        val token = guard.current()
        guard.invalidate()
        var ran = false

        assertFalse(guard.runIfCurrent(token) { ran = true })
        assertFalse(ran, "a stale worker must not touch shared conversation state at all")
    }

    @Test
    fun `a reset cannot land inside a transition that is already running`() {
        val guard = PageRequestGuard()
        val token = guard.current()
        val inTransition = CountDownLatch(1)
        val mayFinish = CountDownLatch(1)
        val resetReturned = CountDownLatch(1)

        val worker = Thread {
            guard.runIfCurrent(token) {
                inTransition.countDown()
                mayFinish.await()
            }
        }
        worker.start()
        assertTrue(inTransition.await(timeoutSeconds, TimeUnit.SECONDS))

        val resetter = Thread {
            guard.invalidate()
            resetReturned.countDown()
        }
        resetter.start()

        assertFalse(
            resetReturned.await(blockedWindowMillis, TimeUnit.MILLISECONDS),
            "the reset must wait: that mutual exclusion is what makes 'still current' and 'apply' one step",
        )

        mayFinish.countDown()
        worker.join(timeoutSeconds * 1000)
        resetter.join(timeoutSeconds * 1000)

        assertFalse(guard.isCurrent(token), "and the reset must still take effect afterwards")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The view model emits a terminal result on every path.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a page load has exactly two terminal results, both carrying the generation`() {
        val source = viewModel.readText()

        assertTrue(source.contains("} catch (e: Exception) {"), "a throwing query must still produce a result")
        assertTrue(
            source.contains("ComposeMessageEvent.NextRecordsFailed(generation = generation)"),
            "and that result must carry the generation that acquired the slot",
        )
        assertEquals(
            1,
            Regex("_events\\.postValue\\(").findAll(source).count(),
            "one post, one terminal result: two would let a load release the slot twice",
        )
    }

    @Test
    fun `the fragment releases the slot and stops the indicator on failure`() {
        val body = bodyOf(fragment.readText(), "private void onNextRecordsFailedEvent(")

        assertTrue(body.contains("pageRequestGuard.endLoad(event.generation)"), "release only the generation that owns it")
        assertTrue(
            body.contains("swipeRefreshLayout.setRefreshing(false)"),
            "and stop the indicator, which otherwise spins forever on a failed refresh",
        )
    }

    /** The text from [signature] to the end of its body, matched by brace depth. */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "this test\'s anchor has drifted: $signature")
        var depth = 0
        var seenOpen = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> {
                    depth++
                    seenOpen = true
                }

                '}' -> {
                    depth--
                    if (seenOpen && depth == 0) {
                        return source.substring(start, index + 1)
                    }
                }
            }
        }
        error("unbalanced braces after $signature")
    }
}
