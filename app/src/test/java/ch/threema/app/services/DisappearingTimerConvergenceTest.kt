package ch.threema.app.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper: exhaustive state table for the pure 1:1 disappearing-timer convergence decision.
 *
 * The decision is small enough to be total, so every case is asserted explicitly (never looped) and every case gets its own
 * test, so a failure names the exact state that broke.
 *
 * Design: `.claude/tasks/disappearing-timer-1to1-shared-lww.md`.
 */
class DisappearingTimerConvergenceTest {

    // ---- toSharedField: wire value -> shared conversation field (null = OFF) ----

    @Test
    fun `toSharedField maps a negative wire value to null`() {
        assertNull(
            DisappearingTimerConvergence.toSharedField(-1),
            "toSharedField(-1) must be null (OFF)",
        )
    }

    @Test
    fun `toSharedField maps 0 to null`() {
        assertNull(
            DisappearingTimerConvergence.toSharedField(0),
            "toSharedField(0) must be null (OFF)",
        )
    }

    @Test
    fun `toSharedField keeps 1`() {
        assertEquals(
            1,
            DisappearingTimerConvergence.toSharedField(1),
            "toSharedField(1) must be 1",
        )
    }

    @Test
    fun `toSharedField keeps 30`() {
        assertEquals(
            30,
            DisappearingTimerConvergence.toSharedField(30),
            "toSharedField(30) must be 30",
        )
    }

    @Test
    fun `toSharedField keeps Int MAX_VALUE`() {
        assertEquals(
            Int.MAX_VALUE,
            DisappearingTimerConvergence.toSharedField(Int.MAX_VALUE),
            "toSharedField(Int.MAX_VALUE) must be Int.MAX_VALUE",
        )
    }

    // ---- changesSharedTimer: currentShared {null, 0, 30, 300} x advertised {0, 30, 300} ----
    // Gates the STATUS ROW ONLY. The adopt is unconditional, so this predicate must never be read as permission to apply
    // an advertisement — the build that did exactly that dropped genuine changes on device (see the object's KDoc).

    @Test
    fun `changesSharedTimer(null, 0) is false — already off, stays off`() {
        assertFalse(
            DisappearingTimerConvergence.changesSharedTimer(null, 0),
            "currentShared=null, advertised=0 must NOT change the shared timer (adopt silently, no status row)",
        )
    }

    @Test
    fun `changesSharedTimer(null, 30) is true — off to 30`() {
        assertTrue(
            DisappearingTimerConvergence.changesSharedTimer(null, 30),
            "currentShared=null, advertised=30 must change the shared timer",
        )
    }

    @Test
    fun `changesSharedTimer(null, 300) is true — off to 300`() {
        assertTrue(
            DisappearingTimerConvergence.changesSharedTimer(null, 300),
            "currentShared=null, advertised=300 must change the shared timer",
        )
    }

    @Test
    fun `changesSharedTimer(0, 0) is false — a legacy 0 is off, stays off`() {
        assertFalse(
            DisappearingTimerConvergence.changesSharedTimer(0, 0),
            "currentShared=0, advertised=0 must NOT change the shared timer (0 normalises to off)",
        )
    }

    @Test
    fun `changesSharedTimer(0, 30) is true`() {
        assertTrue(
            DisappearingTimerConvergence.changesSharedTimer(0, 30),
            "currentShared=0, advertised=30 must change the shared timer",
        )
    }

    @Test
    fun `changesSharedTimer(0, 300) is true`() {
        assertTrue(
            DisappearingTimerConvergence.changesSharedTimer(0, 300),
            "currentShared=0, advertised=300 must change the shared timer",
        )
    }

    @Test
    fun `changesSharedTimer(30, 0) is true — 30 turned off is a real change`() {
        assertTrue(
            DisappearingTimerConvergence.changesSharedTimer(30, 0),
            "currentShared=30, advertised=0 must change the shared timer (OFF must produce a status row)",
        )
    }

    @Test
    fun `changesSharedTimer(30, 30) is false — same value, no status row`() {
        assertFalse(
            DisappearingTimerConvergence.changesSharedTimer(30, 30),
            "currentShared=30, advertised=30 must NOT change the shared timer",
        )
    }

    @Test
    fun `changesSharedTimer(30, 300) is true`() {
        assertTrue(
            DisappearingTimerConvergence.changesSharedTimer(30, 300),
            "currentShared=30, advertised=300 must change the shared timer",
        )
    }

    @Test
    fun `changesSharedTimer(300, 0) is true`() {
        assertTrue(
            DisappearingTimerConvergence.changesSharedTimer(300, 0),
            "currentShared=300, advertised=0 must change the shared timer",
        )
    }

    @Test
    fun `changesSharedTimer(300, 30) is true`() {
        assertTrue(
            DisappearingTimerConvergence.changesSharedTimer(300, 30),
            "currentShared=300, advertised=30 must change the shared timer",
        )
    }

    @Test
    fun `changesSharedTimer(300, 300) is false — re-assert of an unchanged 300`() {
        assertFalse(
            DisappearingTimerConvergence.changesSharedTimer(300, 300),
            "currentShared=300, advertised=300 must NOT change the shared timer",
        )
    }

    // ---- governingTimerSeconds: the ONE timer that governs both freeze directions ----

    @Test
    fun `governingTimerSeconds of null shared field is null`() {
        assertNull(
            DisappearingTimerConvergence.governingTimerSeconds(null),
            "governingTimerSeconds(null) must be null (timer off)",
        )
    }

    @Test
    fun `governingTimerSeconds of 0 shared field is null`() {
        assertNull(
            DisappearingTimerConvergence.governingTimerSeconds(0),
            "governingTimerSeconds(0) must be null (a legacy 0 normalises to off in one place)",
        )
    }

    @Test
    fun `governingTimerSeconds of a negative shared field is null`() {
        assertNull(
            DisappearingTimerConvergence.governingTimerSeconds(-1),
            "governingTimerSeconds(-1) must be null (never freeze at a negative timer)",
        )
    }

    @Test
    fun `governingTimerSeconds of 30 is 30`() {
        assertEquals(
            30,
            DisappearingTimerConvergence.governingTimerSeconds(30),
            "governingTimerSeconds(30) must be 30",
        )
    }
}
