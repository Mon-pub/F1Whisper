package ch.threema.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1Whisper: [OutputRestrictionPolicy] - the single gate that decides whether restricted content may
 * cross an output boundary, and the substitution the accessibility boundary needs.
 */
class OutputRestrictionPolicyTest {

    // --- mayReleaseThumbnailToNotification -------------------------------------------------------

    @Test
    fun `an ordinary image notification still gets its thumbnail`() {
        assertTrue(
            OutputRestrictionPolicy.mayReleaseThumbnailToNotification(
                isFileMessage = true,
                isSpoiler = false,
                isListenOnce = false,
            ),
        )
    }

    @Test
    fun `a spoiler never reaches a notification`() {
        assertFalse(
            OutputRestrictionPolicy.mayReleaseThumbnailToNotification(
                isFileMessage = true,
                isSpoiler = true,
                isListenOnce = false,
            ),
        )
    }

    @Test
    fun `a listen once message never reaches a notification`() {
        assertFalse(
            OutputRestrictionPolicy.mayReleaseThumbnailToNotification(
                isFileMessage = true,
                isSpoiler = false,
                isListenOnce = true,
            ),
        )
    }

    @Test
    fun `a non file message carries no restriction flags and is released`() {
        // Legacy IMAGE/VIDEO messages predate the metadata map, so they cannot be spoilers. Passing
        // the flags as true anyway proves the type guard, not the flags, is what decides.
        assertTrue(
            OutputRestrictionPolicy.mayReleaseThumbnailToNotification(
                isFileMessage = false,
                isSpoiler = true,
                isListenOnce = true,
            ),
        )
    }

    /**
     * Legacy control. The rule this replaces asked only whether the message rendered as media, which
     * is exactly the question that lets a spoiler through. Written out inline so it can never stop
     * reproducing: routing it through the production object would make it a test that cannot fail.
     */
    @Test
    fun `legacy rendering type check released the spoiler it was supposed to hide`() {
        val isRenderedAsMedia = true
        val legacyDecision = isRenderedAsMedia
        assertTrue("the old gate released it", legacyDecision)
        assertFalse(
            "the policy withholds it",
            OutputRestrictionPolicy.mayReleaseThumbnailToNotification(
                isFileMessage = true,
                isSpoiler = true,
                isListenOnce = false,
            ),
        )
    }

    // --- obscureSpans ---------------------------------------------------------------------------

    @Test
    fun `a single span is replaced and the rest of the text is untouched`() {
        assertEquals(
            "meet me at [hidden] tomorrow",
            OutputRestrictionPolicy.obscureSpans(
                "meet me at the docks tomorrow",
                intArrayOf(11),
                intArrayOf(20),
                "[hidden]",
            ),
        )
    }

    @Test
    fun `two separate spans are replaced independently`() {
        assertEquals(
            "X and X",
            OutputRestrictionPolicy.obscureSpans("aa and bb", intArrayOf(0, 7), intArrayOf(2, 9), "X"),
        )
    }

    @Test
    fun `overlapping spans collapse to one replacement`() {
        // Two spoilers reported over overlapping ranges must not be announced twice.
        assertEquals(
            "X",
            OutputRestrictionPolicy.obscureSpans("abcdef", intArrayOf(0, 2), intArrayOf(4, 6), "X"),
        )
    }

    @Test
    fun `adjacent spans collapse to one replacement`() {
        assertEquals(
            "X!",
            OutputRestrictionPolicy.obscureSpans("abcd!", intArrayOf(0, 2), intArrayOf(2, 4), "X"),
        )
    }

    @Test
    fun `spans given out of order are still replaced in position`() {
        assertEquals(
            "X and X",
            OutputRestrictionPolicy.obscureSpans("aa and bb", intArrayOf(7, 0), intArrayOf(9, 2), "X"),
        )
    }

    @Test
    fun `a reversed span is normalised rather than dropped`() {
        // Spannable can report a span reversed; dropping it would leak the very run it covers.
        assertEquals(
            "X and bb",
            OutputRestrictionPolicy.obscureSpans("aa and bb", intArrayOf(2), intArrayOf(0), "X"),
        )
    }

    @Test
    fun `a removed span reporting minus one cannot throw or leak`() {
        // getSpanStart returns -1 for a span that is no longer attached.
        assertEquals(
            "aa and bb",
            OutputRestrictionPolicy.obscureSpans("aa and bb", intArrayOf(-1), intArrayOf(-1), "X"),
        )
    }

    @Test
    fun `an out of bounds span is clamped into the text`() {
        assertEquals(
            "aaX",
            OutputRestrictionPolicy.obscureSpans("aa and bb", intArrayOf(2), intArrayOf(9999), "X"),
        )
    }

    @Test
    fun `an empty span is dropped`() {
        assertEquals(
            "aa and bb",
            OutputRestrictionPolicy.obscureSpans("aa and bb", intArrayOf(3), intArrayOf(3), "X"),
        )
    }

    @Test
    fun `no spans returns the text unchanged`() {
        assertEquals(
            "aa and bb",
            OutputRestrictionPolicy.obscureSpans("aa and bb", intArrayOf(), intArrayOf(), "X"),
        )
    }

    @Test
    fun `mismatched array lengths are refused rather than half applied`() {
        assertEquals(
            "aa and bb",
            OutputRestrictionPolicy.obscureSpans("aa and bb", intArrayOf(0, 3), intArrayOf(2), "X"),
        )
    }

    @Test
    fun `a span covering the whole text leaves only the replacement`() {
        assertEquals(
            "X",
            OutputRestrictionPolicy.obscureSpans("secret", intArrayOf(0), intArrayOf(6), "X"),
        )
    }

    /**
     * Legacy control: what the accessibility layer used to hand out. The span painted the glyphs
     * transparent and the text buffer kept them, so the node text was the raw message.
     */
    @Test
    fun `legacy accessibility text was the raw message including the secret`() {
        val raw = "meet me at the docks tomorrow"
        val legacyNodeText = raw
        assertTrue("the old node text contained the secret", legacyNodeText.contains("the docks"))
        val safe = OutputRestrictionPolicy.obscureSpans(raw, intArrayOf(11), intArrayOf(20), "[hidden]")
        assertFalse("the policy removes it", safe.contains("the docks"))
    }
}
