package ch.threema.app.services

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * F1Whisper: regression tests for the disappearing-expiry traversal rule, [ExpirySweep].
 *
 * These tests exist because of a real crash loop on `6.4.3o-37`: `ConversationServiceImpl.getAll` swept the live
 * `conversationCache` with a plain `for` loop and called `DisappearingMessageService.enforceIfExpired` from inside it.
 * Enforcing deletes a message, the deletion listener runs **synchronously on the same thread**, it re-enters
 * `ConversationServiceImpl` and calls `sort()`, `Collections.sort` bumps the list's `modCount`, and the loop's own
 * iterator then throws [ConcurrentModificationException] on its next `next()`. See the [ExpirySweep] Javadoc for the
 * full call chain and for why neither `synchronized` nor `try/catch` defends against it.
 *
 * [naiveInPlaceTraversalThrowsConcurrentModification] pins that trap down. It is written out **inline** — it must not
 * call production code, or "fixing" production would silently stop it reproducing and it would become a test that
 * cannot fail. It is the control; every other case is the treatment.
 *
 * Design: `.claude/tasks/conversation-cache-reentrancy-crash.md`.
 */
class ExpirySweepTest {
    /**
     * Stands in for `ConversationServiceImpl.sort()`: reorders the shared list, and — decisively — bumps its
     * `modCount`, which `ArrayList.sort` does unconditionally.
     */
    private val descending = Comparator<Int> { a, b -> b.compareTo(a) }

    /** Stands in for a `ConversationModel`, whose `latestMessage` is nullable. */
    private class Conversation(val latestMessage: String?)

    // ---- The defect itself, reproduced inline. This is the shape that must never come back. ----

    @Test
    fun naiveInPlaceTraversalThrowsConcurrentModification() {
        val source = arrayListOf(3, 1, 4, 2)
        val enforced = ArrayList<Int>()

        assertFailsWith<ConcurrentModificationException>(
            "Enforcing while a live iterator walks the same list MUST blow up. If this stops throwing, the JVM " +
                "semantics this fix relies on have changed and ExpirySweep needs re-examining.",
        ) {
            for (item in source) {
                enforced.add(item)
                if (enforced.size == 1) {
                    // The re-entrant mutation: exactly what enforceIfExpired -> onRemoved -> sort() does.
                    Collections.sort(source, descending)
                }
            }
        }

        assertEquals(
            listOf(3),
            enforced,
            "the loop must die on the SECOND next(): ArrayList\$Itr only checks comodification in next(), " +
                "never in hasNext(), which is why the real crash was intermittent",
        )
    }

    // ---- collectThenEnforce: the same hazards, survived. ----

    @Test
    fun collectThenEnforceSurvivesReentrantSort() {
        val source = arrayListOf(3, 1, 4, 2)
        val enforced = ArrayList<Int>()

        ExpirySweep.collectThenEnforce<Int, Int>(
            source,
            { it },
            { value ->
                enforced.add(value)
                // Same list, same sorting callback as the naive case above.
                Collections.sort(source, descending)
            },
        )

        assertEquals(
            listOf(3, 1, 4, 2),
            enforced,
            "every candidate must be enforced exactly once, in traversal order, despite the callback re-sorting " +
                "the source under it",
        )
        assertEquals(
            listOf(4, 3, 2, 1),
            source,
            "the re-entrant sort must really have happened — otherwise this case proves nothing",
        )
    }

    @Test
    fun collectThenEnforceSurvivesReentrantAddAndRemove() {
        val source = arrayListOf("a", "b", "c")
        val snapshotBeforeFirstMutation = ArrayList(source)
        val enforced = ArrayList<String>()

        ExpirySweep.collectThenEnforce<String, String>(
            source,
            { it },
            { value ->
                enforced.add(value)
                // Models ConversationServiceImpl.cache() (adds, size changes) and removeFromCache() (removes).
                source.add("cached-$value")
                source.remove(value)
            },
        )

        assertEquals(
            snapshotBeforeFirstMutation,
            enforced,
            "the enforced set must be exactly the snapshot taken before the first mutation: candidates are read " +
                "before anything is allowed to change, so a candidate the callback removes is still enforced",
        )
        assertEquals(
            listOf("cached-a", "cached-b", "cached-c"),
            source,
            "the callback must really have added AND removed — otherwise this case proves nothing",
        )
    }

    @Test
    fun collectThenEnforceSkipsNullExtractions() {
        val source = arrayListOf(
            Conversation("a"),
            Conversation(null),
            Conversation("c"),
            Conversation(null),
        )
        val enforced = ArrayList<String?>()

        ExpirySweep.collectThenEnforce<Conversation, String?>(
            source,
            { it.latestMessage },
            { enforced.add(it) },
        )

        assertEquals<List<String?>>(
            listOf("a", "c"),
            enforced,
            "a null extraction mirrors conv.latestMessage == null and must be skipped, never passed on to enforce",
        )
    }

    @Test
    fun collectThenEnforceHandlesEmptySource() {
        val source = ArrayList<String>()
        val enforced = ArrayList<String>()

        ExpirySweep.collectThenEnforce<String, String>(
            source,
            { it },
            { enforced.add(it) },
        )

        assertTrue(enforced.isEmpty(), "an empty cache must enforce nothing and must not throw")
    }

    // ---- enforceOnSnapshot: the removeIf-shaped hazard, survived. ----

    @Test
    fun enforceOnSnapshotSurvivesSourceMutation() {
        val source = arrayListOf(1, 2, 3, 4)
        val tested = ArrayList<Int>()

        val matched = ExpirySweep.enforceOnSnapshot<Int>(source) { value ->
            tested.add(value)
            // Structural mutation mid-enforcement: ArrayList.removeIf would throw here, this must not.
            source.add(value * 100)
            value % 2 == 0
        }

        assertEquals(listOf(1, 2, 3, 4), tested, "every item of the snapshot must be tested exactly once")
        assertEquals(listOf(2, 4), matched, "the result must be exactly the items the predicate returned true for")
        assertEquals(
            listOf(1, 2, 3, 4, 100, 200, 300, 400),
            source,
            "the predicate must really have mutated the source — otherwise this case proves nothing; note the " +
                "items it added are NOT tested, because the snapshot was taken first",
        )

        // The caller's follow-up is safe precisely because the traversal is already over.
        source.removeAll(matched)
        assertEquals(listOf(1, 3, 100, 200, 300, 400), source, "removeAll over the returned list must be safe")
    }

    @Test
    fun enforceOnSnapshotReturnsEmptyWhenNothingMatches() {
        val source = arrayListOf("a", "b", "c")

        val matched = ExpirySweep.enforceOnSnapshot<String>(source) { false }

        assertTrue(matched.isEmpty(), "nothing tested true, so nothing may be returned")
        assertEquals(listOf("a", "b", "c"), source, "enforceOnSnapshot must never mutate the source itself")
    }
}
