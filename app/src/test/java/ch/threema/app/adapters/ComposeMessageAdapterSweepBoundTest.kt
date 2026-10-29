package ch.threema.app.adapters

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F1Whisper: the sweep bound in [ComposeMessageAdapter.notifyItemsChanged].
 *
 * **The defect.** The refresh loop ran from the ListView's first to its last visible position and called
 * `listView.getItemAtPosition(i)` for each. Those positions are not a safe index range.
 * `getLastVisiblePosition()` is `mFirstPosition + getChildCount() - 1`, a property of the CHILD VIEWS,
 * whereas the count is a property of the adapter. The adapter's count updates synchronously; the attached
 * children can still reflect an earlier state, because a data change only `requestLayout()`s and a stopped
 * window runs no layout at all. So the list can go on reporting a position the adapter no longer has for as
 * long as the chat sits in the background.
 *
 * **The state that actually crashed, which is not "the list emptied".** `HeaderViewListAdapter.getItem`
 * tries headers, then the wrapped adapter, then footers: `mFooterViewInfos.get(position - adapterCount)`.
 * So `Index 0 out of bounds for length 0` says the footer list was empty and `position == adapterCount` -- the
 * slot one past the last item, for ANY item count. On 2026-09-13 the chat was backgrounded with the peer's
 * typing footer attached; the peer's next message cleared the typing state, which removed the footer from the
 * adapter but not the child view, so the list reported one position more than the adapter had. Three minutes
 * later a delivery receipt for an UNRELATED chat swept this one (`onModified` does not filter by receiver),
 * reached that last position and threw on the main thread, where the refresh had been posted from a background
 * message-state update. Recovered from the device's logcat crash buffer, from the same process that
 * then froze for 5m43s. It froze instead of crashing because a voice call had already cleared the process-wide
 * uncaught exception handler; that is a separate defect, fixed alongside this one.
 *
 * The loop itself needs a real `ListView` and there is no Robolectric in this module, so the arithmetic is
 * extracted and executed here, and the loop's use of it is pinned at source. The pin is deliberately strict:
 * a loose one was satisfied by a loop that computed the bound and then ignored it.
 */
class ComposeMessageAdapterSweepBoundTest {

    @Test
    fun `a last-visible position equal to the item count is clamped below it`() {
        // THE RECORDED CRASH. N items, and the ListView still reporting the removed footer's position N.
        // Returning N here is what reached mFooterViewInfos.get(0) on an empty footer list.
        assertEquals(4, ComposeMessageAdapter.lastSweepablePosition(5, 5))
        assertEquals(0, ComposeMessageAdapter.lastSweepablePosition(1, 1))
    }

    @Test
    fun `an empty adapter yields nothing to sweep`() {
        // The degenerate end of the same shape: no items at all, so there is no safe index and the bound
        // must fall below the loop's start position to end it immediately.
        assertEquals(
            -1,
            ComposeMessageAdapter.lastSweepablePosition(0, 0),
            "with no items there is no safe index, so the bound must fall below the loop's start position",
        )
    }

    @Test
    fun `a stale last-visible position is clamped to the live item count`() {
        // The list shrank from 20 rows to 5 but has not been laid out yet.
        assertEquals(4, ComposeMessageAdapter.lastSweepablePosition(19, 5))
    }

    @Test
    fun `a last-visible position within the adapter is left alone`() {
        // The ordinary case: the visible window is a subset of the data, so nothing is clamped and no row
        // that used to be refreshed stops being refreshed.
        assertEquals(12, ComposeMessageAdapter.lastSweepablePosition(12, 40))
        assertEquals(39, ComposeMessageAdapter.lastSweepablePosition(39, 40))
    }

    @Test
    fun `AdapterView INVALID_POSITION yields nothing to sweep`() {
        // getLastVisiblePosition() returns -1 when the ListView has no children at all.
        assertEquals(-1, ComposeMessageAdapter.lastSweepablePosition(-1, 0))
        assertEquals(-1, ComposeMessageAdapter.lastSweepablePosition(-1, 40))
    }

    @Test
    fun `the bound is never above the last valid index, for any pairing`() {
        for (lastVisible in -1..30) {
            for (itemCount in 0..30) {
                val bound = ComposeMessageAdapter.lastSweepablePosition(lastVisible, itemCount)
                assertTrue(
                    bound < itemCount,
                    "bound $bound must stay below itemCount $itemCount (lastVisible=$lastVisible), " +
                        "otherwise getItemAtPosition indexes past the end of the wrapped adapter",
                )
                assertTrue(
                    bound <= lastVisible,
                    "bound $bound must never exceed the visible range $lastVisible",
                )
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------
    // Pinned at source: the loop must actually USE the bound
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun `the refresh loop binds its upper bound to the helper and nothing else`() {
        // Every assertion here is deliberately exact. A pin that merely checked for the presence of the
        // words "lastSweepablePosition(" and "getAdapter().getCount()" was satisfied by a loop that computed
        // the bound into an unused local and went on iterating to getLastVisiblePosition(), and by
        // `j = lastSweepablePosition + 1`. Both compile, both reintroduce the exact throw, and neither is
        // visible to the arithmetic tests above, which never see the loop.
        val method = compactMethodBody("notifyItemsChanged")

        assertTrue(
            method.contains("for(inti=firstVisiblePosition,j=lastSweepablePosition;i<=j;i++)"),
            "the loop must bind j to the helper's result verbatim - no arithmetic on it, and no second " +
                "call to getLastVisiblePosition() in the loop header. Loop region was: $method",
        )
        assertTrue(
            method.contains(
                "finalintlastSweepablePosition=lastSweepablePosition(listView.getLastVisiblePosition()," +
                    "listView.getAdapter()!=null?listView.getAdapter().getCount():0);",
            ),
            "the helper must be fed the raw last visible position and the WRAPPED adapter's live count, in " +
                "that order, and the whole declaration must end there. Matching only the CALL leaves the " +
                "assignment open: `= lastSweepablePosition(...) + 1;` satisfies a call-only check, keeps " +
                "the loop header intact, and puts the bound straight back on the removed footer's slot. " +
                "getAdapter().getCount() is the same quantity HeaderViewListAdapter.getItem consults, so " +
                "the loop cannot disagree with the method it calls; listView.getCount() is a different " +
                "number and is 0 after notifyDataSetInvalidated() while rows still exist",
        )
        assertTrue(
            method.contains("listView.getItemAtPosition(i)"),
            "the sweep must look up the position it bounded. Bounding i and then asking for i + 1 (or any " +
                "other offset) walks off the end again, and nothing else in this test class looks at the " +
                "lookup itself",
        )
        assertEquals(
            1,
            Regex("""getLastVisiblePosition\(\)""").findAll(method).count(),
            "getLastVisiblePosition() may appear exactly once in this method, as the helper's argument. A " +
                "second occurrence means the raw visible position is being used as an index range again",
        )
        assertTrue(
            method.contains("finalintfirstVisiblePosition=Math.max(listView.getFirstVisiblePosition(),0)"),
            "the start position must stay clamped at 0, so that i - firstVisiblePosition cannot select the " +
                "wrong child view in getChildAt",
        )
    }

    /**
     * The body of [name] with comments and all whitespace removed, so the assertions above match code and
     * cannot be satisfied by prose in a comment. The method is brace-matched rather than cut at the next
     * annotation, so the region cannot silently grow into a neighbouring declaration.
     */
    private fun compactMethodBody(name: String): String {
        val source = File("src/main/java/ch/threema/app/adapters/ComposeMessageAdapter.java").readText()
        val signature = source.indexOf("public void $name(")
        assertTrue(signature >= 0, "$name must still exist on ComposeMessageAdapter")

        val open = source.indexOf('{', signature)
        var depth = 0
        var close = -1
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    close = index
                    break
                }
            }
        }
        assertTrue(close > open, "$name has unbalanced braces")

        return source.substring(open + 1, close)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
            .replace(Regex("""\s+"""), "")
    }
}
