package ch.threema.app.usecases

import ch.threema.domain.protocol.connection.ConnectionLiveness
import ch.threema.domain.protocol.connection.ConnectionState
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper: the acceptance bar for the connection diagnostics, in test form.
 *
 * The report is what cracked the "app stuck DISCONNECTED" case. The user exported it *during* the
 * incident and it showed `csp state: DISCONNECTED` with every network probe OK, including a complete
 * CSP hello to the chat server. What it could not say was **why**, and that gap is what these tests
 * pin shut: given a DISCONNECTED connection with the latch set and nothing running, the report must
 * **name that as the cause** and must not read as merely "offline".
 *
 * These run on the JVM with no Robolectric, because everything the section needs arrives through
 * [ConnectionDiagnosticsProvider]. Before this change the section reached through
 * `ThreemaApplication.requireServiceManager()` statics and could not be tested at all, which is why
 * the original three-line section shipped without anyone noticing it could not diagnose anything.
 *
 * Design: `.claude/tasks/connection-wedge-hardening-and-diagnostics.md` (task 6).
 */
class ConnectionDiagnosticsSectionTest {

    /**
     * The incident, reconstructed: a slot is held, the lifetime latch says a connection was started,
     * the connection reports DISCONNECTED, and nothing is running or being retried.
     */
    private fun wedgedSnapshot() = ConnectionDiagnosticsSnapshot(
        connectionState = ConnectionState.DISCONNECTED,
        restartInFlight = false,
        lifetimeLatchActive = true,
        connectionSlotsHeld = 1,
        lastInboundAtMillis = 0L,
        lastInboundAtAwakeMillis = 0L,
        nowMillis = 1_700_000_000_000L,
        nowAwakeMillis = 500_000L,
        hasPendingTasks = false,
        networkTransport = "wifi (validated)",
        hasIdentity = true,
        usesMultiDevice = false,
    )

    private fun render(snapshot: ConnectionDiagnosticsSnapshot): String =
        StringBuilder().also { ConnectionDiagnosticsSection.append(it, { snapshot }) }.toString()

    // ---- The acceptance bar ----

    @Test
    fun `wedged connection is named as the cause and does not read as merely offline`() {
        val report = render(wedgedSnapshot())

        assertContains(
            report,
            "DOWN_NOT_RETRYING",
            message = "the report MUST carry the named liveness outcome for 'down and not trying'",
        )
        assertContains(
            report,
            "WEDGED",
            message = "the diagnosis line MUST name the wedge. This is the whole point of the change: " +
                "the old report printed 'csp state: DISCONNECTED' and left the reader to guess.",
        )

        // The facts a reader needs to act, all present and all distinguishable.
        assertContains(report, "restart in flight:\tfalse")
        assertContains(report, "lifetime latch active:\ttrue")
        assertContains(report, "connection slots held:\t1")
    }

    @Test
    fun `the two latches are printed separately and never merged`() {
        // F1 and F2 differ ONLY in restartInFlight, and they have different causes and different
        // fixes. A report that collapsed the two latches into one line could not tell them apart.
        val f1 = render(wedgedSnapshot())
        val f2 = render(wedgedSnapshot().copy(restartInFlight = true))

        assertContains(f1, "restart in flight:\tfalse")
        assertContains(f2, "restart in flight:\ttrue")
        assertTrue(f1 != f2, "the two states must not render identically")
    }

    // ---- The false positive the verdict alone would produce ----

    @Test
    fun `a never-started connection is not reported as a wedge`() {
        // The ConvertibleServerConnection wrapper reports DISCONNECTED and isRunning=false when no
        // inner connection exists yet (pre-unlock, no identity). That satisfies DOWN_NOT_RETRYING
        // even though nothing ever started. The lifetime latch is what tells the two apart.
        val neverStarted = wedgedSnapshot().copy(lifetimeLatchActive = false, connectionSlotsHeld = 0)
        val report = render(neverStarted)

        assertFalse(
            report.contains("WEDGED"),
            "an app that never started a connection must NOT be reported as wedged; without this " +
                "discriminator a bare DOWN_NOT_RETRYING would misdiagnose every pre-unlock export",
        )
        assertContains(report, "not started")
    }

    // ---- The diagnosis rule itself ----

    @Test
    fun `diagnose names the wedge only when a connection was wanted and started`() {
        assertContains(
            ConnectionDiagnosticsSection.diagnose(
                ConnectionLiveness.DOWN_NOT_RETRYING,
                latchActive = true,
                slotsHeld = 1,
            ),
            "WEDGED",
        )
        assertContains(
            ConnectionDiagnosticsSection.diagnose(
                ConnectionLiveness.DOWN_NOT_RETRYING,
                latchActive = false,
                slotsHeld = 0,
            ),
            "not started",
        )
        assertContains(
            ConnectionDiagnosticsSection.diagnose(
                ConnectionLiveness.DOWN_NOT_RETRYING,
                latchActive = true,
                slotsHeld = 0,
            ),
            "intentionally down",
        )
    }

    @Test
    fun `a healthy connection is not diagnosed as a wedge`() {
        for (liveness in ConnectionLiveness.values()) {
            if (liveness == ConnectionLiveness.DOWN_NOT_RETRYING) continue
            val diagnosis = ConnectionDiagnosticsSection.diagnose(liveness, latchActive = true, slotsHeld = 1)
            assertFalse(
                diagnosis.contains("WEDGED"),
                "liveness=$liveness must not be diagnosed as a wedge; only DOWN_NOT_RETRYING is one",
            )
        }
    }

    // ---- Containment: the report is a troubleshooting artifact and must always be produced ----

    @Test
    fun `a failing provider degrades to one line instead of aborting the report`() {
        val report = StringBuilder()
            .also { ConnectionDiagnosticsSection.append(it, { error("service manager unavailable") }) }
            .toString()

        assertContains(report, "# connection", message = "the section header must still be emitted")
        assertContains(report, "n/a", message = "a failed snapshot must degrade to n/a, never throw")
    }

    @Test
    fun `every rendered line uses the key tab value format`() {
        val report = render(wedgedSnapshot())
        val lines = report.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(lines.isNotEmpty())
        for (line in lines) {
            assertTrue(
                line.contains('\t'),
                "line '$line' must use the shared key\\tvalue format so the report stays parseable",
            )
        }
    }

    @Test
    fun `no age is ever computed from a zero stamp`() {
        // lastInbound == 0L means no stamp was ever recorded. A bare `now - 0L` would yield an age of
        // about 54 years that merely HAPPENS to exceed every threshold, which is "correct by accident"
        // rather than by design. This asserts the invariant rather than a particular rendering, so it
        // does not break if the verdict chooses `null` vs some other honest representation.
        val report = render(wedgedSnapshot())

        assertFalse(
            report.contains("1700000000000"),
            "an age must never be computed as `now - 0L`; a missing stamp is missing data, not a " +
                "54-year-old connection",
        )
        assertFalse(
            report.contains("499999") || report.contains("500000"),
            "nor may the awake age be computed as `nowAwake - 0L`",
        )
    }

    @Test
    fun `the section emits only the coarse transport it was given`() {
        // Deliberately NOT asserting against literal infrastructure hostnames or IPs: writing those
        // into a source file is itself the leak the project's hard rule forbids, and a source-tree
        // leak-guard cannot tell an assertion from a disclosure. The gate's binary-safe leak scan is
        // the right place for that check. Here we assert the property that keeps the report clean:
        // the section renders the transport string verbatim and synthesises nothing of its own, so a
        // coarse input stays coarse.
        val report = render(wedgedSnapshot().copy(networkTransport = "cellular (validated)"))

        assertContains(report, "network transport:\tcellular (validated)")
        assertFalse(
            report.contains("://") || report.contains("@"),
            "the section must not synthesise URLs or addresses of its own",
        )
    }
}
