package ch.threema.app.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F1Whisper: exhaustive state table for the pure incoming disappearing-timer freeze decision.
 *
 * Every combination of `advertisedBySender ∈ {null, -1, 0, 1, 30, 300, Int.MAX_VALUE}` ×
 * `localSharedField ∈ {null, 0, 30, 300}` — 28 cases — is asserted explicitly, never looped, and each gets its own test with
 * a failure message naming the state, so a failure identifies the exact combination that broke.
 *
 * The three result shapes are NOT interchangeable: `null` = store nothing, `0` = the sender explicitly said OFF (never fall
 * back), `> 0` = freeze here. See [DisappearingFreezeDecision]'s KDoc for what breaks when `0` and `null` are collapsed.
 *
 * Design: `.claude/tasks/disappearing-per-message-timer-metadata.md`.
 */
class DisappearingFreezeDecisionTest {

    // ---- advertised = null: the peer transmitted no timer (pre-v6.4.3-38 client). Fall back to the local shared field. ----

    @Test
    fun `advertised null with local null falls back to off`() {
        assertNull(
            DisappearingFreezeDecision.resolveIncomingTimer(null, null),
            "advertised=null, local=null: nothing was asserted by anyone, so nothing may be stored",
        )
    }

    @Test
    fun `advertised null with local 0 falls back to off`() {
        assertNull(
            DisappearingFreezeDecision.resolveIncomingTimer(null, 0),
            "advertised=null, local=0: a legacy 0 in the shared field means off, so nothing may be stored",
        )
    }

    @Test
    fun `advertised null with local 30 falls back to 30`() {
        assertEquals(
            30,
            DisappearingFreezeDecision.resolveIncomingTimer(null, 30),
            "advertised=null, local=30: an old-client peer must degrade to today's behaviour, i.e. the local shared field",
        )
    }

    @Test
    fun `advertised null with local 300 falls back to 300`() {
        assertEquals(
            300,
            DisappearingFreezeDecision.resolveIncomingTimer(null, 300),
            "advertised=null, local=300: an old-client peer must degrade to today's behaviour, i.e. the local shared field",
        )
    }

    // ---- advertised = -1: hostile input. Clamped to an explicit OFF, and the local field is NOT consulted. ----

    @Test
    fun `advertised -1 with local null is an explicit off`() {
        assertEquals(
            0,
            DisappearingFreezeDecision.resolveIncomingTimer(-1, null),
            "advertised=-1, local=null: a negative advertisement is clamped to an explicit OFF (0), never to null",
        )
    }

    @Test
    fun `advertised -1 with local 0 is an explicit off`() {
        assertEquals(
            0,
            DisappearingFreezeDecision.resolveIncomingTimer(-1, 0),
            "advertised=-1, local=0: a negative advertisement is clamped to an explicit OFF (0)",
        )
    }

    @Test
    fun `advertised -1 with local 30 is an explicit off and ignores the local timer`() {
        assertEquals(
            0,
            DisappearingFreezeDecision.resolveIncomingTimer(-1, 30),
            "advertised=-1, local=30: the sender asserted something, so the recipient's 30 must NOT be substituted",
        )
    }

    @Test
    fun `advertised -1 with local 300 is an explicit off and ignores the local timer`() {
        assertEquals(
            0,
            DisappearingFreezeDecision.resolveIncomingTimer(-1, 300),
            "advertised=-1, local=300: the sender asserted something, so the recipient's 300 must NOT be substituted",
        )
    }

    // ---- advertised = 0: the sender explicitly said OFF. Must be 0, never null — see the class KDoc. ----

    @Test
    fun `advertised 0 with local null is an explicit off`() {
        assertEquals(
            0,
            DisappearingFreezeDecision.resolveIncomingTimer(0, null),
            "advertised=0, local=null: an explicit sender OFF must be stored as 0, not collapsed to null",
        )
    }

    @Test
    fun `advertised 0 with local 0 is an explicit off`() {
        assertEquals(
            0,
            DisappearingFreezeDecision.resolveIncomingTimer(0, 0),
            "advertised=0, local=0: an explicit sender OFF must be stored as 0, not collapsed to null",
        )
    }

    @Test
    fun `advertised 0 with local 30 is an explicit off and ignores the local timer`() {
        assertEquals(
            0,
            DisappearingFreezeDecision.resolveIncomingTimer(0, 30),
            "advertised=0, local=30: the sender said OFF, so the recipient's 30 must NEVER be substituted",
        )
    }

    @Test
    fun `advertised 0 with local 300 is an explicit off and ignores the local timer`() {
        assertEquals(
            0,
            DisappearingFreezeDecision.resolveIncomingTimer(0, 300),
            "advertised=0, local=300: the sender said OFF, so the recipient's 300 must NEVER be substituted",
        )
    }

    // ---- advertised = 1: the smallest live timer. ----

    @Test
    fun `advertised 1 with local null freezes at 1`() {
        assertEquals(
            1,
            DisappearingFreezeDecision.resolveIncomingTimer(1, null),
            "advertised=1, local=null: freeze at the sender's value even when the recipient's timer is off",
        )
    }

    @Test
    fun `advertised 1 with local 0 freezes at 1`() {
        assertEquals(
            1,
            DisappearingFreezeDecision.resolveIncomingTimer(1, 0),
            "advertised=1, local=0: freeze at the sender's value even when the recipient's timer is off",
        )
    }

    @Test
    fun `advertised 1 with local 30 freezes at 1`() {
        assertEquals(
            1,
            DisappearingFreezeDecision.resolveIncomingTimer(1, 30),
            "advertised=1, local=30: the sender's value wins over the recipient's, shorter or longer",
        )
    }

    @Test
    fun `advertised 1 with local 300 freezes at 1`() {
        assertEquals(
            1,
            DisappearingFreezeDecision.resolveIncomingTimer(1, 300),
            "advertised=1, local=300: the sender's value wins over the recipient's, shorter or longer",
        )
    }

    // ---- advertised = 30: the reported-bug case. A local OFF must not rescue the sender's timed messages. ----

    @Test
    fun `advertised 30 with local null freezes at 30`() {
        assertEquals(
            30,
            DisappearingFreezeDecision.resolveIncomingTimer(30, null),
            "advertised=30, local=null: THE REPORTED BUG — a recipient's OFF must not un-time the sender's message",
        )
    }

    @Test
    fun `advertised 30 with local 0 freezes at 30`() {
        assertEquals(
            30,
            DisappearingFreezeDecision.resolveIncomingTimer(30, 0),
            "advertised=30, local=0: THE REPORTED BUG — a recipient's OFF must not un-time the sender's message",
        )
    }

    @Test
    fun `advertised 30 with local 30 freezes at 30`() {
        assertEquals(
            30,
            DisappearingFreezeDecision.resolveIncomingTimer(30, 30),
            "advertised=30, local=30: agreeing values still resolve through the sender's advertisement",
        )
    }

    @Test
    fun `advertised 30 with local 300 freezes at 30`() {
        assertEquals(
            30,
            DisappearingFreezeDecision.resolveIncomingTimer(30, 300),
            "advertised=30, local=300: the recipient's longer timer must not extend the sender's policy",
        )
    }

    // ---- advertised = 300: the same-burst ordering race from p2:4404-4520. ----

    @Test
    fun `advertised 300 with local null freezes at 300`() {
        assertEquals(
            300,
            DisappearingFreezeDecision.resolveIncomingTimer(300, null),
            "advertised=300, local=null: queue position relative to an OFF control message must not matter",
        )
    }

    @Test
    fun `advertised 300 with local 0 freezes at 300`() {
        assertEquals(
            300,
            DisappearingFreezeDecision.resolveIncomingTimer(300, 0),
            "advertised=300, local=0: queue position relative to an OFF control message must not matter",
        )
    }

    @Test
    fun `advertised 300 with local 30 freezes at 300`() {
        assertEquals(
            300,
            DisappearingFreezeDecision.resolveIncomingTimer(300, 30),
            "advertised=300, local=30: the recipient's shorter timer must not shorten the sender's policy",
        )
    }

    @Test
    fun `advertised 300 with local 300 freezes at 300`() {
        assertEquals(
            300,
            DisappearingFreezeDecision.resolveIncomingTimer(300, 300),
            "advertised=300, local=300: agreeing values still resolve through the sender's advertisement",
        )
    }

    // ---- advertised = Int.MAX_VALUE: hostile input, capped before anything is persisted or scheduled. ----

    @Test
    fun `advertised Int MAX_VALUE with local null is capped`() {
        assertEquals(
            DisappearingFreezeDecision.MAX_TIMER_SECONDS,
            DisappearingFreezeDecision.resolveIncomingTimer(Int.MAX_VALUE, null),
            "advertised=Int.MAX_VALUE, local=null: an absurd advertisement must be capped at MAX_TIMER_SECONDS",
        )
    }

    @Test
    fun `advertised Int MAX_VALUE with local 0 is capped`() {
        assertEquals(
            DisappearingFreezeDecision.MAX_TIMER_SECONDS,
            DisappearingFreezeDecision.resolveIncomingTimer(Int.MAX_VALUE, 0),
            "advertised=Int.MAX_VALUE, local=0: an absurd advertisement must be capped at MAX_TIMER_SECONDS",
        )
    }

    @Test
    fun `advertised Int MAX_VALUE with local 30 is capped`() {
        assertEquals(
            DisappearingFreezeDecision.MAX_TIMER_SECONDS,
            DisappearingFreezeDecision.resolveIncomingTimer(Int.MAX_VALUE, 30),
            "advertised=Int.MAX_VALUE, local=30: the cap applies, and the local timer is still not substituted",
        )
    }

    @Test
    fun `advertised Int MAX_VALUE with local 300 is capped`() {
        assertEquals(
            DisappearingFreezeDecision.MAX_TIMER_SECONDS,
            DisappearingFreezeDecision.resolveIncomingTimer(Int.MAX_VALUE, 300),
            "advertised=Int.MAX_VALUE, local=300: the cap applies, and the local timer is still not substituted",
        )
    }
}
