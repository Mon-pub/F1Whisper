package ch.threema.app.usecases

import ch.threema.domain.protocol.connection.ConnectionLiveness
import ch.threema.domain.protocol.connection.ConnectionLivenessVerdict

/**
 * F1Whisper: the `connection` section of the diagnostics report, and the diagnosis it prints.
 *
 * ## Why this exists as its own unit
 *
 * The report is what cracked the "app stuck DISCONNECTED" case: the user exported it *during* the
 * incident and it carried facts the debug log could not, namely the connection state at the moment of
 * failure plus live network probes proving the environment was healthy. Its gap was that it said
 * **what** the state was and not **why**. It printed `csp state: DISCONNECTED` next to a full set of
 * passing probes and left the reader to guess.
 *
 * The section could not be tested at all, because [ExportConnectionDiagnosticsUseCase] reached
 * through `ThreemaApplication.requireServiceManager()` statics. Everything here therefore takes a
 * [ConnectionDiagnosticsProvider], so the whole section is JVM-testable with no Robolectric and no
 * database. Same pattern as `ExpirySweep`, `ConnectionRestartDecision` and `VoipCallLifecycleGate`.
 *
 * ## Why the verdict alone is not the diagnosis
 *
 * [ConnectionLiveness.DOWN_NOT_RETRYING] fires on `DISCONNECTED && !restartInFlight`. On the app side
 * the connection object is the `ConvertibleServerConnection` **wrapper**, whose accessors fall back to
 * `false` and `DISCONNECTED` when no inner connection exists yet. So a process that has simply never
 * started a connection (pre-unlock, no identity, or a deliberately stopped connection) satisfies that
 * predicate too, and would otherwise be reported as "the app has stopped trying to reconnect" when it
 * never began.
 *
 * The discriminator is app-layer state, which is why it lives here and not in the domain verdict: the
 * `LifetimeService` latch plus the held slot count say whether a connection was ever **wanted** and
 * **started**. See [diagnose].
 */
internal object ConnectionDiagnosticsSection {

    /**
     * The one line a reader should be able to act on. Combines the domain verdict with the app-layer
     * latch facts that tell "gave up" apart from "never started".
     */
    fun diagnose(
        liveness: ConnectionLiveness,
        latchActive: Boolean,
        slotsHeld: Int,
    ): String = when {
        liveness != ConnectionLiveness.DOWN_NOT_RETRYING ->
            "no wedge detected (liveness=$liveness)"

        // A slot is held (a connection is wanted) AND the latch says one was started, yet nothing is
        // running and nothing is being retried. This is the reported defect.
        latchActive && slotsHeld > 0 ->
            "WEDGED: a connection is wanted and was started, but nothing is running and no restart " +
                "is in flight; the app will not recover on its own until something calls " +
                "ensureConnection() or the default network changes"

        // The latch was never set, so nothing ever started. Not a defect.
        !latchActive ->
            "not started: no connection has been started in this process (not a wedge)"

        // Latch set but no slot held: nothing currently wants a connection, so staying down is correct.
        else ->
            "intentionally down: no connection slot is held, so no connection is wanted (not a wedge)"
    }

    /**
     * Append the `connection` section. Every value is read through a lazy [kv] block, so a single
     * failing probe degrades to `n/a (...)` and never aborts the report.
     */
    fun append(builder: StringBuilder, provider: ConnectionDiagnosticsProvider) {
        // Read once, up front, so every line below describes the SAME instant. Reading per-line would
        // let the state change mid-section and produce a self-contradictory report, which is exactly
        // the sort of artifact that sends an investigation down the wrong path.
        val snapshot = runCatching { provider.snapshot() }.getOrNull()

        if (snapshot == null) {
            // The whole snapshot is unavailable, e.g. no ServiceManager yet (pre-unlock). Say exactly
            // that rather than emitting a page of `n/a` lines that look like probe failures.
            builder.section("connection") {
                kv("snapshot") { "n/a (connection services unavailable)" }
            }
            return
        }

        val verdict: ConnectionLivenessVerdict? = runCatching {
            ConnectionLivenessVerdict.evaluate(
                connectionState = snapshot.connectionState,
                restartInFlight = snapshot.restartInFlight,
                lastInboundAtMillis = snapshot.lastInboundAtMillis,
                lastInboundAtAwakeMillis = snapshot.lastInboundAtAwakeMillis,
                nowMillis = snapshot.nowMillis,
                nowAwakeMillis = snapshot.nowAwakeMillis,
            )
        }.getOrNull()

        builder.section("connection") {
            kv("csp state") { snapshot.connectionState }
            kv("liveness") { verdict?.liveness ?: "n/a" }
            kv("liveness reason") { verdict?.reason ?: "n/a" }
            kv("diagnosis") {
                verdict?.let {
                    diagnose(
                        liveness = it.liveness,
                        latchActive = snapshot.lifetimeLatchActive,
                        slotsHeld = snapshot.connectionSlotsHeld,
                    )
                } ?: "n/a (no liveness verdict)"
            }

            // The two latches, printed separately and never merged. `restart in flight` is the
            // connection's own `isRunning`; `lifetime latch active` is LifetimeServiceImpl.active.
            // They fail in different ways and have different fixes, so a report that collapses them
            // cannot tell a reader which one to look at.
            kv("restart in flight") { snapshot.restartInFlight }
            kv("lifetime latch active") { snapshot.lifetimeLatchActive }
            kv("connection slots held") { snapshot.connectionSlotsHeld }

            // Staleness is judged on awake time; wall-clock age is carried for context only. A
            // wall-clock threshold is what made the shipped v6.4.3-35 foreground check fire on
            // provably healthy sockets. A null age means the verdict declined to compute one (no
            // stamp recorded), and is rendered n/a rather than as a bogus `now - 0`.
            kv("last inbound age (awake)") { verdict?.awakeAgeMillis?.let { "$it ms" } ?: "n/a" }
            kv("last inbound age (wall)") { verdict?.wallClockAgeMillis?.let { "$it ms" } ?: "n/a" }

            kv("has pending tasks") { snapshot.hasPendingTasks }
            kv("network transport") { snapshot.networkTransport }
            kv("has identity") { snapshot.hasIdentity }
            kv("uses multi device") { snapshot.usesMultiDevice }
        }
    }
}
