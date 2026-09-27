package ch.threema.app.mediagallery

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * F1Whisper: the two `MediaGalleryAdapter` getters that index the item list with a position from somewhere
 * else, and so must tolerate that position being out of date.
 *
 * Both are the same defect class as the chat list's `notifyItemsChanged` sweep, which did crash in the field
 * (see [ch.threema.app.adapters.ComposeMessageAdapterSweepBoundTest]). Neither has been observed crashing, and the point of
 * pinning them is that the field evidence for the third one arrived as a 5m43s freeze on a user's phone.
 *
 * - `getItemAtPosition` is called by the fast-scroll popup with
 *   `gridLayoutManager.findFirstCompletelyVisibleItemPosition()`, a LAYOUT-derived position. A layout manager
 *   keeps its positions until the next layout pass, so a list replaced under it leaves that position naming
 *   an item that is gone.
 * - `getCheckedItemAt` is called with an index into the SELECTION, which it converts to an index into the
 *   item list. `setItems` replaces the item list without clearing the selection, so a selection made before a
 *   filter change survives into a shorter list: select the 50th item, filter down to 10, "Show in chat".
 *
 * The adapter cannot be constructed on a unit-test JVM -- its `init` block calls `LayoutInflater.from` and
 * `ConfigUtils.getColorFromAttribute`, and there is no Robolectric in this project -- so this is pinned at
 * source, as the other structural tests in this module are.
 *
 * Note what this does NOT cover: a selection that is still IN range after a replacement now points at a
 * DIFFERENT message. Bounds-checking cannot fix that; clearing or remapping the selection in `setItems` is
 * the real answer, and it is a behaviour change, so it is left as a separate decision.
 */
class MediaGalleryAdapterBoundsTest {

    private val source =
        File("src/main/java/ch/threema/app/mediagallery/MediaGalleryAdapter.kt")
            .readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `getItemAtPosition tolerates a stale layout position`() {
        assertSafelyIndexed(
            "getItemAtPosition",
            "the fast-scroll popup passes a layout-manager position, which outlives the data it names",
        )
    }

    @Test
    fun `getCheckedItemAt tolerates a selection made before the list was replaced`() {
        assertSafelyIndexed(
            "getCheckedItemAt",
            "the bounds check on the SELECTION's size says nothing about the item list the resulting key " +
                "indexes into, and setItems() replaces that list without clearing the selection",
        )
    }

    private fun assertSafelyIndexed(methodName: String, why: String) {
        val body = methodBody(methodName)

        assertTrue(
            body.contains("getOrNull("),
            "$methodName must index the item list with getOrNull, not get: $why. Body was: $body",
        )
        assertTrue(
            !Regex("""\bit\[""").containsMatchIn(body) && !Regex("""\.get\(""").containsMatchIn(body),
            "$methodName must not index the item list unchecked anywhere: $why. Body was: $body",
        )
    }

    /** The brace-matched body of `fun [methodName](`, with comments already stripped. */
    private fun methodBody(methodName: String): String {
        val signature = source.indexOf("fun $methodName(")
        assertTrue(signature >= 0, "$methodName must still exist on MediaGalleryAdapter")

        val open = source.indexOf('{', signature)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    return source.substring(open + 1, index).replace(Regex("""\s+"""), "")
                }
            }
        }
        error("$methodName has unbalanced braces")
    }
}
