package ch.threema.app.services

import ch.threema.domain.protocol.connection.ConnectionState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper: regression tests for [ConnectionRestartDecision], the restart rule of
 * `LifetimeServiceImpl.ensureConnection()`.
 *
 * These tests exist because of a real, total loss of messaging on `6.4.3o-37`. The in-app diagnostics
 * report, exported by the user *during* the incident, showed `csp state: DISCONNECTED` while every
 * network probe came back OK, including a complete CSP hello to the chat server. The app was not
 * reconnecting and only a force-close restored service.
 *
 * The cause was a one-way latch. `LifetimeServiceImpl.active` is cleared in exactly one place, behind
 * a guard requiring zero unpausable connection slots, and on onprem `ThreemaPushService` holds an
 * unpausable slot for the entire session because Threema Push is forced. So the latch could never
 * reset, and every `ensureConnection()` returned early with "A connection is already active" while
 * nothing was running. See the [ConnectionRestartDecision] Javadoc for the full chain.
 *
 * [legacyLatchOnlyRuleRefusesToRestartAWedgedConnection] pins that trap down. It is written out
 * **inline** so it does not call production code: if it did, fixing production would silently stop it
 * reproducing and it would become a test that cannot fail. It is the control; every other case is the
 * treatment.
 *
 * Design: `.claude/tasks/connection-wedge-hardening-and-diagnostics.md` (task 3, finding F0).
 */
class ConnectionRestartDecisionTest {
    // ---- The defect itself, reproduced inline. This is the shape that must never come back. ----

    /**
     * The old rule in full: slots held, latch set, therefore return without starting. It cannot see
     * the connection state at all, which is why a DISCONNECTED connection stayed DISCONNECTED for the
     * life of the process.
     */
    private fun legacyShouldStartConnection(
        hasConnectionSlots: Boolean,
        startLatchSet: Boolean,
    ): Boolean {
        if (!hasConnectionSlots) {
            return false
        }
        if (startLatchSet) {
            return false
        }
        return true
    }

    @Test
    fun legacyLatchOnlyRuleRefusesToRestartAWedgedConnection() {
        assertFalse(
            legacyShouldStartConnection(hasConnectionSlots = true, startLatchSet = true),
            "the shipped rule could not restart a DISCONNECTED connection, because the latch was the " +
                "only thing it consulted. If this ever starts returning true, the reproduction has " +
                "drifted and this control no longer proves anything.",
        )

        assertTrue(
            ConnectionRestartDecision.shouldStartConnection(true, true, ConnectionState.DISCONNECTED),
            "the fixed rule MUST restart in exactly the state the old one refused to",
        )
    }

    // ---- The wedge: latch set, connection genuinely down. ----

    @Test
    fun restartsWhenLatchIsSetButConnectionIsDisconnected() {
        assertTrue(
            ConnectionRestartDecision.shouldStartConnection(true, true, ConnectionState.DISCONNECTED),
        )
    }

    // ---- No storm: never start when the connection is already up or coming up. ----

    @Test
    fun doesNotStartWhenLatchIsSetAndConnectionIsNotDisconnected() {
        for (state in listOf(
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.LOGGEDIN,
        )) {
            assertFalse(
                ConnectionRestartDecision.shouldStartConnection(true, true, state),
                "state=$state: ConvertibleServerConnection.start() bails for every non-DISCONNECTED " +
                    "state, so issuing a start here could only ever be a no-op",
            )
        }
    }

    /**
     * DISCONNECTED is also the normal transient state during reconnect backoff, so this rule does fire
     * there. That is intentional and harmless: the connection's own `running` flag is set and its job
     * is live, so the domain bails at its first guard. The rule is deliberately NOT made cleverer than
     * the domain's own precondition, because guessing "a reconnect is probably already in flight" is
     * precisely the assumption that produced the wedge.
     */
    @Test
    fun startsDuringBackoffToo() {
        assertTrue(
            ConnectionRestartDecision.shouldStartConnection(true, true, ConnectionState.DISCONNECTED),
        )
    }

    // ---- First start: unchanged from the original behaviour. ----

    @Test
    fun startsWhenLatchIsNotSetRegardlessOfState() {
        for (state in ConnectionState.values().toList() + listOf(null)) {
            assertTrue(
                ConnectionRestartDecision.shouldStartConnection(true, false, state),
                "state=$state: with the latch unset nothing has been started yet, which is the " +
                    "original behaviour and must not change",
            )
        }
    }

    // ---- No slots: no connection is wanted, whatever anything else says. ----

    @Test
    fun neverStartsWithoutConnectionSlots() {
        for (state in ConnectionState.values().toList() + listOf(null)) {
            for (latch in listOf(true, false)) {
                assertFalse(
                    ConnectionRestartDecision.shouldStartConnection(false, latch, state),
                    "state=$state latch=$latch: no slot means no connection is wanted",
                )
            }
        }
    }

    // ---- Unreadable state: fall back to the original behaviour, never start blindly. ----

    @Test
    fun doesNotStartWhenLatchIsSetAndStateIsUnreadable() {
        assertFalse(
            ConnectionRestartDecision.shouldStartConnection(true, true, null),
            "a null state means there is no usable ServiceManager to start through, so there is no " +
                "evidence contradicting the latch and a start would fail anyway",
        )
    }

    // ---- The reporting helper used by the diagnostics. ----

    @Test
    fun latchDisagreesWithStateOnlyWhenSetAndDisconnected() {
        assertTrue(ConnectionRestartDecision.latchDisagreesWithState(true, ConnectionState.DISCONNECTED))

        assertFalse(
            ConnectionRestartDecision.latchDisagreesWithState(false, ConnectionState.DISCONNECTED),
            "an unset latch is an ordinary offline app, not a stranded latch",
        )
        assertFalse(ConnectionRestartDecision.latchDisagreesWithState(true, null))
        for (state in listOf(
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.LOGGEDIN,
        )) {
            assertFalse(ConnectionRestartDecision.latchDisagreesWithState(true, state))
        }
    }

    /**
     * Guards against a new [ConnectionState] constant being added and silently inheriting the
     * "do not start" branch without anyone deciding that is right.
     */
    @Test
    fun everyConnectionStateIsAccountedFor() {
        val restartable = ConnectionState.values().toList().filter {
            ConnectionRestartDecision.shouldStartConnection(true, true, it)
        }
        assertTrue(
            restartable == listOf(ConnectionState.DISCONNECTED),
            "exactly DISCONNECTED must be restartable with the latch set, but got $restartable. " +
                "A new ConnectionState constant needs a deliberate decision here.",
        )
    }
}
