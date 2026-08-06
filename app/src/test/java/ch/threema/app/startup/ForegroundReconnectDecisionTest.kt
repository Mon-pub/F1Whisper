package ch.threema.app.startup

import ch.threema.domain.protocol.connection.ConnectionLiveness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper: the foreground reconnect rule, which until now had **no test coverage at all**.
 *
 * The shipped v6.4.3-35 check compared a wall-clock age against a 90s threshold and asserted in a
 * comment that "a healthy idle-but-alive link is NEVER falsely reconnected". Measurement refuted
 * that: wall-clock echo intervals on the reporting device ran median 61s, p90 141s, max 403s, and of
 * the 34 windows over 120s the socket was alive at the end of **all 34**. The check was tearing down
 * healthy connections, and `AppProcessLifecycleObserverTest` never exercised it, so nothing caught it.
 *
 * These tests pin both halves of the replacement rule: it must act on the two genuinely dead states,
 * and it must NOT act on the two "something is already happening" states.
 *
 * Design: `.claude/tasks/connection-wedge-hardening-and-diagnostics.md` (task 6).
 */
class ForegroundReconnectDecisionTest {

    @Test
    fun `reconnects on the wedge`() {
        // The reported bug. The old check gated on LOGGEDIN and was structurally blind to it.
        assertTrue(
            ForegroundReconnectDecision.shouldRequestReconnect(ConnectionLiveness.DOWN_NOT_RETRYING),
        )
    }

    @Test
    fun `reconnects on a presumed-dead socket`() {
        assertTrue(
            ForegroundReconnectDecision.shouldRequestReconnect(ConnectionLiveness.PRESUMED_DEAD),
        )
    }

    @Test
    fun `reconnects when the stamping path is broken`() {
        // LOGGEDIN with no inbound stamp is a contradiction: no liveness judgement can be trusted, so
        // re-establishing is the only way back to a state we can reason about.
        assertTrue(
            ForegroundReconnectDecision.shouldRequestReconnect(ConnectionLiveness.STAMPING_BROKEN),
        )
    }

    @Test
    fun `does not reconnect a verified-live connection`() {
        assertFalse(
            ForegroundReconnectDecision.shouldRequestReconnect(ConnectionLiveness.VERIFIED_LIVE),
        )
    }

    @Test
    fun `does not reconnect while something is already in progress`() {
        // NOT_APPLICABLE covers CONNECTING, CONNECTED, and DISCONNECTED with a restart in flight.
        // Reconnecting here would interrupt a handshake or a backoff on EVERY foreground event, which
        // is the v6.4.3-35 defect in a new costume. This is the single most important negative case.
        assertFalse(
            ForegroundReconnectDecision.shouldRequestReconnect(ConnectionLiveness.NOT_APPLICABLE),
            "reconnecting on NOT_APPLICABLE would tear down connecting and backing-off sockets",
        )
    }

    @Test
    fun `does not reconnect when liveness merely could not be confirmed`() {
        // "Cannot confirm" is not "has failed". Acting on it is the same mistake the old wall-clock
        // threshold made: treating absence of proof as proof of death.
        assertFalse(
            ForegroundReconnectDecision.shouldRequestReconnect(ConnectionLiveness.UNVERIFIED),
        )
    }

    /**
     * Pins the whole mapping, so a future edit that flips any single outcome has to change this test
     * and say why. The production `when` has no `else`, so a NEW enum value is a compile error rather
     * than a silent default; this covers the existing values.
     */
    @Test
    fun `exactly the three dead states trigger a reconnect`() {
        val reconnecting = ConnectionLiveness.values()
            .filter { ForegroundReconnectDecision.shouldRequestReconnect(it) }
            .toSet()

        assertEquals(
            setOf(
                ConnectionLiveness.DOWN_NOT_RETRYING,
                ConnectionLiveness.PRESUMED_DEAD,
                ConnectionLiveness.STAMPING_BROKEN,
            ),
            reconnecting,
            "the set of reconnect-triggering outcomes changed; that is a behaviour change and needs " +
                "a deliberate justification, not a test update",
        )
    }
}
