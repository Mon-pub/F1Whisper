package ch.threema.app.utils

import ch.threema.app.messagereceiver.MessageReceiver
import io.mockk.mockk
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (fourth fork review, F4-11): a page worker must query and advance only the conversation it was dispatched for.
 *
 * The defect: quote catch-up, pull-to-refresh and the initial load each captured a [PageRequestGuard] generation and then,
 * on their own thread, read the fragment's LIVE receiver and page cursor. The guard only ever governed whether the RESULT
 * could be applied, so during a conversation switch a stale worker queried the NEW conversation, advanced the NEW cursor
 * through `valuesLoaded`, and then had its rows discarded by the old-token check in its completion. A page of the newly
 * opened chat's history was consumed and thrown away, staying missing until a full reload; and a worker that resumed inside
 * the reset's temporary-null window could fail on a null receiver outright.
 *
 * A generation number cannot fix that on its own, because the damage is done by the QUERY. These tests drive the production
 * [PageDispatch] and [PageRequestGuard] through the shape the fragment now uses - validate, query the snapshot, advance
 * only if still current - across the three interleavings the review named, with latches rather than sleeps.
 *
 * [legacyLiveStateWorkerConsumesTheNewConversationsCursor] is the control: it reads live state the way the old code did and
 * shows the new conversation's page being consumed and discarded.
 */
class PageDispatchTest {
    private val timeoutSeconds = 5L
    private val pageSize = 50L

    private val fragment = File("src/main/java/ch/threema/app/fragments/composemessage/ComposeMessageFragment.java")

    private val chatA: MessageReceiver<*> = mockk(relaxed = true)
    private val chatB: MessageReceiver<*> = mockk(relaxed = true)

    /** Stands in for the fragment's live state, which a conversation switch replaces atomically. */
    private class LiveState {
        @Volatile
        var receiver: MessageReceiver<*>? = null

        @Volatile
        var cursorPage: Int = 0
    }

    private val live = LiveState()
    private val guard = PageRequestGuard()

    /** Every (conversation, page) pair a worker actually queried. */
    private val queried: MutableList<Pair<MessageReceiver<*>, Int>> = Collections.synchronizedList(mutableListOf())

    private fun openConversation(receiver: MessageReceiver<*>) {
        live.receiver = null // the reset's temporary-null window
        guard.invalidate()
        live.cursorPage = 0
        live.receiver = receiver
    }

    private fun snapshot(generation: Int): PageDispatch? {
        val receiver = live.receiver ?: return null
        return PageDispatch(generation, receiver, null, live.cursorPage, pageSize)
    }

    /**
     * The production shape: validate, query only the snapshot, and advance shared state through the guard's atomic
     * transition so a reset cannot land between the check and the mutation (fifth review, F5-03).
     */
    private fun loadPage(
        dispatch: PageDispatch,
        beforeQuery: (() -> Unit)? = null,
        beforeApply: (() -> Unit)? = null,
    ): Boolean {
        if (!dispatch.isCurrentIn(guard)) {
            return false
        }
        beforeQuery?.invoke()
        queried.add(dispatch.receiver to (dispatch.pageReferenceId ?: -1))
        beforeApply?.invoke()
        return guard.runIfCurrent(dispatch.generation) {
            live.cursorPage = (dispatch.pageReferenceId ?: 0) + 1
        }
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The three interleavings.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a switch before the worker starts stops it querying at all`() {
        openConversation(chatA)
        val dispatch = snapshot(guard.tryBeginLoad())!!

        // The user switches conversations before the worker gets a thread.
        openConversation(chatB)

        val applied = loadPage(dispatch)

        assertFalse(applied)
        assertTrue(queried.isEmpty(), "a load for a closed conversation must not read the open one")
        assertEquals(0, live.cursorPage, "and must not move the open conversation's cursor")
    }

    @Test
    fun `a switch during the query neither redirects it nor advances the new cursor`() {
        openConversation(chatA)
        val dispatch = snapshot(guard.tryBeginLoad())!!
        val inQuery = CountDownLatch(1)
        val mayFinish = CountDownLatch(1)

        val worker = Thread {
            loadPage(dispatch) {
                inQuery.countDown()
                mayFinish.await()
            }
        }
        worker.start()
        assertTrue(inQuery.await(timeoutSeconds, TimeUnit.SECONDS))

        // The switch lands while the query is running.
        openConversation(chatB)
        mayFinish.countDown()
        worker.join(timeoutSeconds * 1000)

        assertEquals(listOf(chatA to 0), queried.toList(), "the query must still be against the conversation it froze")
        assertEquals(0, live.cursorPage, "and the newly opened conversation's cursor must not move")
    }

    @Test
    fun `a stale worker completing after a fresh one leaves the fresh cursor intact`() {
        openConversation(chatA)
        val staleDispatch = snapshot(guard.tryBeginLoad())!!
        val staleInQuery = CountDownLatch(1)
        val staleMayFinish = CountDownLatch(1)

        val stale = Thread {
            loadPage(staleDispatch) {
                staleInQuery.countDown()
                staleMayFinish.await()
            }
        }
        stale.start()
        assertTrue(staleInQuery.await(timeoutSeconds, TimeUnit.SECONDS))

        // Switch, then run a page load for the new conversation to completion.
        openConversation(chatB)
        val freshDispatch = snapshot(guard.tryBeginLoad())!!
        assertTrue(loadPage(freshDispatch), "the new conversation's own load must succeed")
        assertEquals(1, live.cursorPage)

        // Only now does the stale worker finish.
        staleMayFinish.countDown()
        stale.join(timeoutSeconds * 1000)

        // The stale worker's own query runs last, and against chat A: harmless, because chat A is no longer open and the
        // guard stops it touching anything shared. What matters is that nobody queried chat B twice.
        assertEquals(
            setOf(chatA to 0, chatB to 0),
            queried.toSet(),
            "each worker queried its own conversation",
        )
        assertEquals(2, queried.size, "and chat B's page was read exactly once")
        assertEquals(1, live.cursorPage, "the reversed completion must not rewind or re-advance the fresh cursor")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The null window and the ordinary path.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `no dispatch can be built while a conversation reset is in progress`() {
        openConversation(chatA)
        live.receiver = null // mid-reset

        assertNull(snapshot(guard.current()), "there is nothing to load from, which is an answer and not a crash")
    }

    @Test
    fun `an undisturbed load queries its own conversation and advances the cursor`() {
        openConversation(chatA)

        assertTrue(loadPage(snapshot(guard.tryBeginLoad())!!))

        assertEquals(listOf(chatA to 0), queried.toList())
        assertEquals(1, live.cursorPage)
    }

    @Test
    fun `successive pages of one conversation keep advancing`() {
        openConversation(chatA)
        val first = guard.tryBeginLoad()
        assertTrue(loadPage(snapshot(first)!!))
        guard.endLoad(first)

        val second = guard.tryBeginLoad()
        assertTrue(loadPage(snapshot(second)!!))

        assertEquals(listOf(chatA to 0, chatA to 1), queried.toList(), "pagination within one conversation is unaffected")
        assertEquals(2, live.cursorPage)
    }

    @Test
    fun `the snapshot is immutable once taken`() {
        openConversation(chatA)
        val dispatch = snapshot(7)!!

        live.receiver = chatB
        live.cursorPage = 99

        assertEquals(chatA, dispatch.receiver)
        assertEquals(0, dispatch.pageReferenceId)
        assertEquals(7, dispatch.generation)
        assertEquals(pageSize, dispatch.pageSize, "the row count is frozen too, not read live when the worker runs")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // F5-03: the window between the post-query check and the mutation.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a reset landing between the post-query check and the cursor write cannot be applied`() {
        openConversation(chatA)
        val dispatch = snapshot(guard.tryBeginLoad())!!
        val aboutToApply = CountDownLatch(1)
        val mayApply = CountDownLatch(1)
        var applied = true

        val worker = Thread {
            applied = loadPage(dispatch, beforeApply = {
                aboutToApply.countDown()
                mayApply.await()
            })
        }
        worker.start()
        assertTrue(aboutToApply.await(timeoutSeconds, TimeUnit.SECONDS))

        // The user switches conversations in exactly the gap the old check-then-act left open.
        openConversation(chatB)
        mayApply.countDown()
        worker.join(timeoutSeconds * 1000)

        assertFalse(applied, "the mutation must be refused, not merely preceded by a check that passed")
        assertEquals(0, live.cursorPage, "chat A's boundary must not be restored over chat B's")
    }

    @Test
    fun legacyCheckThenMutateRestoresTheOldConversationsCursor() {
        openConversation(chatA)
        val staleGeneration = guard.current()

        // The old shape: check the generation, then mutate as a separate step. The reset lands in between.
        val checkPassed = guard.isCurrent(staleGeneration)
        openConversation(chatB)
        if (checkPassed) {
            live.cursorPage = 42
        }

        assertEquals(
            42,
            live.cursorPage,
            "this is the defect: a worker paused after its check restored the previous conversation's boundary",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Every page load in the fragment dispatches a snapshot.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `every asynchronous loader dispatches a snapshot`() {
        val source = fragment.readText()

        // Named one by one rather than counted: a count passes when one loader is swapped for another, and the review's
        // point is that the loaders it named were the ones with no snapshot at all.
        for ((signature, expected) in listOf(
            "public void onRefresh()" to "snapshotPageDispatch(token)",
            "private void jumpToPinnedMessage(" to "snapshotFullDispatch(pageRequestGuard.current())",
            "private void refreshFullPinnedSet()" to "snapshotFullDispatch(scanGeneration)",
            "private void initConversationList(" to "snapshotUnreadDispatch(initialLoadToken, this.unreadCount)",
        )) {
            assertTrue(
                bodyOf(source, signature).contains(expected),
                "$signature must freeze its conversation before dispatching",
            )
        }
        assertTrue(
            source.contains("messageModels = getAllRecords(searchDispatch);"),
            "the in-chat search's full load must query a frozen conversation",
        )
        assertTrue(
            source.contains("return getAllRecords(jumpDispatch);"),
            "and so must the pinned jump's",
        )
        assertFalse(
            source.contains("getAllRecords()"),
            "the live-state full load is what let chat A's rows replace chat B's timeline",
        )
        assertFalse(
            source.contains("MessageService.MessageFilter nextMessageFilter"),
            "the live filter field is what made the stale worker paginate the new conversation",
        )
    }

    @Test
    fun `the page snapshot reads the cursor exactly once`() {
        val body = bodyOf(fragment.readText(), "private PageDispatch snapshotPageDispatch(")

        assertTrue(
            body.contains("final PageCursor cursor = this.pageCursor;"),
            "two live reads of a field a switch replaces could carry one conversation's sort key with another's row id",
        )
        val code = body.lines()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            .joinToString("\n")
        assertEquals(
            1,
            Regex("this\\.pageCursor").findAll(code).count(),
            "exactly one read of the live cursor; the second was getCurrentPageReferenceId() re-reading it",
        )
    }

    @Test
    fun `the pinned scan's in-progress flag is owned by a generation`() {
        val source = fragment.readText()
        val body = bodyOf(source, "private void refreshFullPinnedSet()")

        assertTrue(
            body.contains("if (pinnedFullScanInProgress && pinnedFullScanGeneration == scanGeneration) {"),
            "a single unowned boolean let chat A's scan SUPPRESS chat B's request",
        )
        assertTrue(
            body.contains("pageRequestGuard.runIfCurrent(scanGeneration, () -> {") &&
                body.contains("pinnedFullScanInProgress = false;"),
            "and let A's completion clear a flag it no longer owned, leaving B with A's pins and no scan of its own",
        )
    }

    /** The text from [signature] to the end of its body, matched by brace depth. */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "this test's anchor has drifted: $signature")
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

    // -----------------------------------------------------------------------------------------------------------------------------
    // Legacy control: read live state, as the old worker did.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun legacyLiveStateWorkerConsumesTheNewConversationsCursor() {
        openConversation(chatA)
        val staleGeneration = guard.current()

        // The old worker: capture only a generation, then read the LIVE receiver and cursor when it runs.
        val switched = CountDownLatch(1)
        val worker = Thread {
            switched.await()
            val receiver = live.receiver!!
            val page = live.cursorPage
            queried.add(receiver to page)
            live.cursorPage = page + 1 // valuesLoaded advanced the cursor unconditionally
        }
        worker.start()

        openConversation(chatB)
        switched.countDown()
        worker.join(timeoutSeconds * 1000)

        assertEquals(
            listOf(chatB to 0),
            queried.toList(),
            "this is the defect: a worker belonging to chat A queried chat B",
        )
        assertEquals(1, live.cursorPage, "and advanced chat B's cursor past a page")
        assertFalse(
            guard.isCurrent(staleGeneration),
            "while its own completion check would then discard the rows, so that page is simply missing",
        )
    }
}
