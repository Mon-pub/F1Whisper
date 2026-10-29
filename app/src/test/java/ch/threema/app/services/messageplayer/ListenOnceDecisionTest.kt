package ch.threema.app.services.messageplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1Whisper: [ListenOnceDecision] - the two-phase claim/burn gate that makes a listen-once voice
 * message unplayable from the moment its audio is released, rather than from the moment playback
 * happens to finish.
 */
class ListenOnceDecisionTest {

    private fun gate(
        isOutbox: Boolean = false,
        isFileMessage: Boolean = true,
        isListenOnce: Boolean = true,
        isClaimed: Boolean = false,
        isConsumed: Boolean = false,
    ) = ListenOnceDecision.evaluate(isOutbox, isFileMessage, isListenOnce, isClaimed, isConsumed)

    @Test
    fun `a fresh incoming listen once message is playable`() {
        assertEquals(ListenOnceGate.PLAYABLE, gate())
    }

    @Test
    fun `an ordinary voice message is not governed at all`() {
        assertEquals(ListenOnceGate.NOT_APPLICABLE, gate(isListenOnce = false))
    }

    @Test
    fun `a non file message is not governed`() {
        assertEquals(ListenOnceGate.NOT_APPLICABLE, gate(isFileMessage = false))
    }

    @Test
    fun `the sender is never restricted from their own message`() {
        assertEquals(ListenOnceGate.NOT_APPLICABLE, gate(isOutbox = true))
    }

    @Test
    fun `a burned message is blocked`() {
        assertEquals(ListenOnceGate.BLOCKED_CONSUMED, gate(isClaimed = true, isConsumed = true))
    }

    @Test
    fun `a claim without a burn is blocked as burn pending`() {
        // This is the state a process death during playback leaves behind, and the state the old
        // single-boolean model had no way to express.
        assertEquals(ListenOnceGate.BLOCKED_BURN_PENDING, gate(isClaimed = true))
    }

    @Test
    fun `consumption outranks the claim`() {
        // A burned message is always claimed. Reporting it burn-pending would make every bind
        // re-run the burn.
        assertEquals(ListenOnceGate.BLOCKED_CONSUMED, gate(isClaimed = true, isConsumed = true))
    }

    @Test
    fun `a consumed message with no claim recorded is still blocked`() {
        // Messages burned before the claim flag existed carry only the consumed flag.
        assertEquals(ListenOnceGate.BLOCKED_CONSUMED, gate(isClaimed = false, isConsumed = true))
    }

    @Test
    fun `both blocked states refuse playback and neither needs a new claim`() {
        for (blocked in listOf(ListenOnceGate.BLOCKED_BURN_PENDING, ListenOnceGate.BLOCKED_CONSUMED)) {
            assertTrue(blocked.toString(), ListenOnceDecision.isPlaybackRefused(blocked))
            assertFalse(blocked.toString(), ListenOnceDecision.needsClaimBeforeRelease(blocked))
        }
    }

    @Test
    fun `only a playable message is claimed before release`() {
        assertTrue(ListenOnceDecision.needsClaimBeforeRelease(ListenOnceGate.PLAYABLE))
        assertFalse(ListenOnceDecision.needsClaimBeforeRelease(ListenOnceGate.NOT_APPLICABLE))
    }

    @Test
    fun `an ungoverned message is neither refused nor claimed`() {
        assertFalse(ListenOnceDecision.isPlaybackRefused(ListenOnceGate.NOT_APPLICABLE))
        assertFalse(ListenOnceDecision.needsClaimBeforeRelease(ListenOnceGate.NOT_APPLICABLE))
    }

    @Test
    fun `a playable message is not refused`() {
        assertFalse(ListenOnceDecision.isPlaybackRefused(ListenOnceGate.PLAYABLE))
    }

    /**
     * Legacy control: the rule that shipped before the claim existed, written out inline so it stays
     * a reproduction of the defect rather than a re-run of the fix.
     *
     * Enforcement keyed on the consumed flag alone, and that flag was only written from the
     * playback-ended callback, on a worker thread. Kill the process during playback and the message
     * on disk was untouched: not consumed, so playable, so replayable - as many times as the user
     * cared to force-stop the app.
     */
    @Test
    fun `legacy consumed only rule handed back a replay after a process death mid playback`() {
        val consumedOnDisk = false // playback started; the ended-callback never ran

        val legacyReplayAllowed = !consumedOnDisk
        assertTrue("the old rule allowed the replay", legacyReplayAllowed)

        // The claim was written before the audio was ever handed to the player, so the same disk
        // state is now unambiguous.
        assertEquals(ListenOnceGate.BLOCKED_BURN_PENDING, gate(isClaimed = true, isConsumed = consumedOnDisk))
        assertTrue(
            "the gate refuses the replay",
            ListenOnceDecision.isPlaybackRefused(gate(isClaimed = true, isConsumed = consumedOnDisk)),
        )
    }
}
