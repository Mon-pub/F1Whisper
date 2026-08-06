package ch.threema.domain.protocol.connection

import ch.threema.domain.protocol.connection.ConnectionLivenessVerdict.Companion.DEFAULT_STALENESS_THRESHOLD_AWAKE_MILLIS
import ch.threema.domain.protocol.connection.ConnectionLivenessVerdict.Companion.evaluate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper: boundary coverage for the pure liveness verdict.
 *
 * The two `0L` cases are pinned by their own named tests on purpose. Without them a reviewer cannot
 * tell "handled deliberately" from "never considered", and the `= 0L` interface default on
 * [ServerConnection.getLastInboundActivityAtMillis] makes that distinction load-bearing: an
 * implementer that forgets to override it must fail closed, never be blessed as healthy.
 */
class ConnectionLivenessVerdictTest {
    private companion object {
        private const val WALL_NOW = 1_700_000_000_000L
        private const val AWAKE_NOW = 5_000_000L
    }

    // region the wedge, as a first-class named cause

    @Test
    fun `DISCONNECTED with nothing in flight is DOWN_NOT_RETRYING`() {
        // The reported incident, in verdict form: DISCONNECTED, network fine, no retry. This must be
        // a named cause and not merely the absence of VERIFIED_LIVE, or a report renders it as
        // ordinary offline and the next investigation starts from zero again.
        val verdict = evaluate(
            connectionState = ConnectionState.DISCONNECTED,
            restartInFlight = false,
            lastInboundAtMillis = WALL_NOW - 600_000L,
            lastInboundAtAwakeMillis = AWAKE_NOW - 600_000L,
            nowMillis = WALL_NOW,
            nowAwakeMillis = AWAKE_NOW,
        )
        assertEquals(ConnectionLiveness.DOWN_NOT_RETRYING, verdict.liveness)
        assertFalse(verdict.isVerifiedLive)
        assertTrue(
            verdict.reason.contains("stopped trying"),
            "the reason must name the cause, was: ${verdict.reason}",
        )
    }

    @Test
    fun `DISCONNECTED with a restart in flight is only NOT_APPLICABLE`() {
        // The ordinary transient case. It must NOT be reported as the wedge, or the wedge signal
        // becomes noise and gets ignored.
        val verdict = evaluate(
            connectionState = ConnectionState.DISCONNECTED,
            restartInFlight = true,
            lastInboundAtMillis = WALL_NOW - 600_000L,
            lastInboundAtAwakeMillis = AWAKE_NOW - 600_000L,
            nowMillis = WALL_NOW,
            nowAwakeMillis = AWAKE_NOW,
        )
        assertEquals(ConnectionLiveness.NOT_APPLICABLE, verdict.liveness)
        assertFalse(verdict.isVerifiedLive)
    }

    @Test
    fun `a mid-handshake state is never reported as the wedge`() {
        // CONNECTING and CONNECTED are transient by definition. Even with restartInFlight false, a
        // connection that has got as far as CONNECTING is demonstrably trying.
        for (state in listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED)) {
            val verdict = evaluate(
                connectionState = state,
                restartInFlight = false,
                lastInboundAtMillis = 0L,
                lastInboundAtAwakeMillis = 0L,
                nowMillis = WALL_NOW,
                nowAwakeMillis = AWAKE_NOW,
            )
            assertEquals(ConnectionLiveness.NOT_APPLICABLE, verdict.liveness, "state=$state")
        }
    }

    // endregion

    // region the 0L rule, both halves

    @Test
    fun `0L stamp while not LOGGEDIN is never VERIFIED_LIVE`() {
        for (state in listOf(
            ConnectionState.DISCONNECTED,
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
        )) {
            val verdict = evaluate(
                connectionState = state,
                restartInFlight = true,
                lastInboundAtMillis = 0L,
                lastInboundAtAwakeMillis = 0L,
                nowMillis = WALL_NOW,
                nowAwakeMillis = AWAKE_NOW,
            )
            assertEquals(ConnectionLiveness.NOT_APPLICABLE, verdict.liveness, "state=$state")
            assertFalse(verdict.isVerifiedLive, "state=$state must never be verified live")
            // No bare `now - 0L` age is ever computed.
            assertNull(verdict.awakeAgeMillis, "state=$state")
            assertNull(verdict.wallClockAgeMillis, "state=$state")
        }
    }

    @Test
    fun `0L stamp while LOGGEDIN is STAMPING_BROKEN and fails loud`() {
        val verdict = evaluate(
            connectionState = ConnectionState.LOGGEDIN,
            restartInFlight = true,
            lastInboundAtMillis = 0L,
            lastInboundAtAwakeMillis = 0L,
            nowMillis = WALL_NOW,
            nowAwakeMillis = AWAKE_NOW,
        )
        assertEquals(ConnectionLiveness.STAMPING_BROKEN, verdict.liveness)
        assertFalse(verdict.isVerifiedLive)
        assertNull(verdict.awakeAgeMillis)
        assertNull(verdict.wallClockAgeMillis)
        assertTrue(
            verdict.reason.contains("stamping path is broken"),
            "the reason must name the cause, was: ${verdict.reason}",
        )
    }

    @Test
    fun `a wall stamp without an awake stamp is STAMPING_BROKEN`() {
        // Only reachable if a future implementer stamps one clock and not the other. Fail closed.
        val verdict = evaluate(
            connectionState = ConnectionState.LOGGEDIN,
            restartInFlight = true,
            lastInboundAtMillis = WALL_NOW - 1_000L,
            lastInboundAtAwakeMillis = 0L,
            nowMillis = WALL_NOW,
            nowAwakeMillis = AWAKE_NOW,
        )
        assertEquals(ConnectionLiveness.STAMPING_BROKEN, verdict.liveness)
        assertFalse(verdict.isVerifiedLive)
    }

    @Test
    fun `an implementer inheriting both interface defaults is never blessed`() {
        // Exactly what ServerConnection's `= 0L` defaults produce. This is the silent-bless failure
        // mode the verdict exists to prevent, so it gets its own test.
        val inheritedDefaults = object : ServerConnection {
            override val isRunning = true
            override val connectionState = ConnectionState.LOGGEDIN
            override val isNewConnectionSession = false
            override fun disableReconnect() = Unit
            override fun start() = Unit
            override fun stop() = Unit
            override fun addConnectionStateListener(listener: ConnectionStateListener) = Unit
            override fun removeConnectionStateListener(listener: ConnectionStateListener) = Unit
        }
        assertEquals(0L, inheritedDefaults.getLastInboundActivityAtMillis())
        assertEquals(0L, inheritedDefaults.getLastInboundActivityAtAwakeMillis())

        val verdict = evaluate(
            connectionState = inheritedDefaults.connectionState,
            restartInFlight = inheritedDefaults.isRunning,
            lastInboundAtMillis = inheritedDefaults.getLastInboundActivityAtMillis(),
            lastInboundAtAwakeMillis = inheritedDefaults.getLastInboundActivityAtAwakeMillis(),
            nowMillis = WALL_NOW,
            nowAwakeMillis = AWAKE_NOW,
        )
        assertFalse(verdict.isVerifiedLive)
        assertEquals(ConnectionLiveness.STAMPING_BROKEN, verdict.liveness)
    }

    // endregion

    // region staleness, judged in awake time

    @Test
    fun `fresh inbound while LOGGEDIN is VERIFIED_LIVE`() {
        val verdict = evaluate(
            connectionState = ConnectionState.LOGGEDIN,
            restartInFlight = true,
            lastInboundAtMillis = WALL_NOW - 5_000L,
            lastInboundAtAwakeMillis = AWAKE_NOW - 5_000L,
            nowMillis = WALL_NOW,
            nowAwakeMillis = AWAKE_NOW,
        )
        assertEquals(ConnectionLiveness.VERIFIED_LIVE, verdict.liveness)
        assertTrue(verdict.isVerifiedLive)
        assertEquals(5_000L, verdict.awakeAgeMillis)
        assertEquals(5_000L, verdict.wallClockAgeMillis)
    }

    @Test
    fun `exactly at the awake threshold is still VERIFIED_LIVE`() {
        val verdict = evaluate(
            connectionState = ConnectionState.LOGGEDIN,
            restartInFlight = true,
            lastInboundAtMillis = WALL_NOW - DEFAULT_STALENESS_THRESHOLD_AWAKE_MILLIS,
            lastInboundAtAwakeMillis = AWAKE_NOW - DEFAULT_STALENESS_THRESHOLD_AWAKE_MILLIS,
            nowMillis = WALL_NOW,
            nowAwakeMillis = AWAKE_NOW,
        )
        assertEquals(ConnectionLiveness.VERIFIED_LIVE, verdict.liveness)
        assertEquals(DEFAULT_STALENESS_THRESHOLD_AWAKE_MILLIS, verdict.awakeAgeMillis)
    }

    @Test
    fun `one millisecond past the awake threshold is PRESUMED_DEAD`() {
        val verdict = evaluate(
            connectionState = ConnectionState.LOGGEDIN,
            restartInFlight = true,
            lastInboundAtMillis = WALL_NOW - DEFAULT_STALENESS_THRESHOLD_AWAKE_MILLIS - 1L,
            lastInboundAtAwakeMillis = AWAKE_NOW - DEFAULT_STALENESS_THRESHOLD_AWAKE_MILLIS - 1L,
            nowMillis = WALL_NOW,
            nowAwakeMillis = AWAKE_NOW,
        )
        assertEquals(ConnectionLiveness.PRESUMED_DEAD, verdict.liveness)
        assertFalse(verdict.isVerifiedLive)
        assertEquals(DEFAULT_STALENESS_THRESHOLD_AWAKE_MILLIS + 1L, verdict.awakeAgeMillis)
    }

    @Test
    fun `a long wall-clock gap with little awake time stays VERIFIED_LIVE`() {
        // The regression that matters. The worst measured healthy window on the reporting device was
        // a 403s wall-clock echo gap during which the device was awake for at most about 105s. A
        // wall-clock rule would have torn that connection down; the awake-time rule must not.
        val verdict = evaluate(
            connectionState = ConnectionState.LOGGEDIN,
            restartInFlight = true,
            lastInboundAtMillis = WALL_NOW - 403_000L,
            lastInboundAtAwakeMillis = AWAKE_NOW - 105_000L,
            nowMillis = WALL_NOW,
            nowAwakeMillis = AWAKE_NOW,
        )
        assertEquals(ConnectionLiveness.VERIFIED_LIVE, verdict.liveness)
        assertEquals(105_000L, verdict.awakeAgeMillis)
        assertEquals(403_000L, verdict.wallClockAgeMillis)
    }

    @Test
    fun `a caller-supplied threshold overrides the default`() {
        val verdict = evaluate(
            connectionState = ConnectionState.LOGGEDIN,
            restartInFlight = true,
            lastInboundAtMillis = WALL_NOW - 20_000L,
            lastInboundAtAwakeMillis = AWAKE_NOW - 20_000L,
            nowMillis = WALL_NOW,
            nowAwakeMillis = AWAKE_NOW,
            stalenessThresholdAwakeMillis = 10_000L,
        )
        assertEquals(ConnectionLiveness.PRESUMED_DEAD, verdict.liveness)
    }

    // endregion

    // region clock anomalies

    @Test
    fun `a backwards awake clock yields UNVERIFIED rather than a bogus age`() {
        val verdict = evaluate(
            connectionState = ConnectionState.LOGGEDIN,
            restartInFlight = true,
            lastInboundAtMillis = WALL_NOW - 1_000L,
            lastInboundAtAwakeMillis = AWAKE_NOW + 7_000L,
            nowMillis = WALL_NOW,
            nowAwakeMillis = AWAKE_NOW,
        )
        assertEquals(ConnectionLiveness.UNVERIFIED, verdict.liveness)
        assertFalse(verdict.isVerifiedLive)
        assertNull(verdict.awakeAgeMillis)
        assertEquals(1_000L, verdict.wallClockAgeMillis)
    }

    @Test
    fun `a backwards wall clock does not change the verdict and reports an unknown wall age`() {
        // An NTP correction can step wall-clock backwards. Staleness is judged on awake time, so the
        // verdict must be unaffected, and the unusable wall age must be reported as unknown rather
        // than as a negative number.
        val verdict = evaluate(
            connectionState = ConnectionState.LOGGEDIN,
            restartInFlight = true,
            lastInboundAtMillis = WALL_NOW + 60_000L,
            lastInboundAtAwakeMillis = AWAKE_NOW - 3_000L,
            nowMillis = WALL_NOW,
            nowAwakeMillis = AWAKE_NOW,
        )
        assertEquals(ConnectionLiveness.VERIFIED_LIVE, verdict.liveness)
        assertEquals(3_000L, verdict.awakeAgeMillis)
        assertNull(verdict.wallClockAgeMillis)
    }

    // endregion

    // region the fail-closed contract

    @Test
    fun `only VERIFIED_LIVE reports isVerifiedLive`() {
        // Guards the fail-closed contract against a value added later.
        for (liveness in ConnectionLiveness.entries) {
            assertEquals(
                liveness == ConnectionLiveness.VERIFIED_LIVE,
                liveness.isVerifiedLive,
                "$liveness",
            )
        }
    }

    // endregion
}
