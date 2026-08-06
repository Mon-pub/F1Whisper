package ch.threema.app.services;

import androidx.annotation.Nullable;
import ch.threema.domain.protocol.connection.ConnectionState;

/**
 * F1Whisper: the decision {@code LifetimeServiceImpl.ensureConnection()} makes about whether to ask
 * the {@code ServiceManager} to start the server connection.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>{@code LifetimeServiceImpl} kept a single {@code boolean active} latch and treated it as proof
 * that a connection existed. The latch is written {@code true} in exactly one place, right after
 * {@code ServiceManager.startConnection()} returns, and {@code false} in exactly one place, inside
 * {@code cleanupConnection()}. That reset sits behind a guard requiring {@code unpausableSlots == 0}.
 *
 * <p>On onprem, Threema Push is forced, so {@code ThreemaPushService} runs for the whole session and
 * holds an <b>unpausable</b> connection slot from {@code onCreate} until {@code onDestroy}. Therefore
 * {@code unpausableSlots >= 1} at all times, the reset is <b>never</b> reachable, and every
 * {@code cleanupConnection()} lands on its refusal branch, the observed
 * {@code "Not cleaning up #slots=1, #unpausable-slots=1"}. The latch is not a race that has to be
 * won: once set it is the permanent steady state until the process dies.
 *
 * <p>The consequence is the reported defect. The app reported {@code DISCONNECTED} while its own
 * in-app network probes were all OK, including a complete CSP hello to the chat server, and it never
 * retried. Every {@code ensureConnection()} returned early with {@code "A connection is already
 * active"}, and {@code acquireConnection}, {@code unpause} and the resend alarm all funnel through
 * {@code ensureConnection()}, so none of them could restart anything. Only a force-close cured it,
 * because the latch is process-scoped.
 *
 * <p>Worse, the latch is also set {@code true} after a start that did nothing:
 * {@code ConvertibleServerConnection.start()} returns normally when it bails at its own
 * already-running guard, and nothing downstream checks the resulting connection state.
 *
 * <h2>The rule</h2>
 *
 * <p><b>A recovery path must never be gated on a flag that only one code path can clear.</b> The
 * latch may say "we started something"; only the connection itself can say whether anything is
 * running. So the latch is cross-checked against the real {@link ConnectionState} before it is
 * allowed to suppress a restart.
 *
 * <h2>Why this cannot become a reconnect storm</h2>
 *
 * <p>The rule below returns {@code true} only for {@link ConnectionState#DISCONNECTED}, which is
 * exactly the precondition {@code ConvertibleServerConnection.start()} itself requires: for any other
 * state it bails with "Connection is neither new nor disconnected". So this never issues a start the
 * domain could not act on.
 *
 * <p>{@code DISCONNECTED} is also the normal transient state during reconnect backoff. In that case
 * the connection's own {@code running} flag is set and its job is live, so
 * {@code ConvertibleServerConnection.start()} bails at its first guard and the call costs nothing.
 * The domain sets that flag <b>synchronously</b> inside {@code start()}, so even two calls racing the
 * job launch collapse to one. {@code ensureConnection()} is additionally {@code synchronized}, and it
 * is driven by slot acquisition, unpause and a 5-minute alarm rather than by any loop.
 *
 * <h2>What this deliberately does not change</h2>
 *
 * <p>{@code LifetimeServiceImpl.isActive()} keeps its existing meaning, "a connection is desired and
 * has been started". {@code ConnectionNetworkCallback} gates all three of its reconnect paths on it
 * precisely because it stays {@code true} while backgrounded or dozing; redefining it as "actually
 * connected" would disable the one automatic recovery route that survives this wedge.
 *
 * <p>Kept free of Android imports so it is directly JVM-testable (see
 * {@code ConnectionRestartDecisionTest}); {@code LifetimeServiceImpl} itself needs a {@code Context}
 * and an {@code AlarmManager} to instantiate, which is why the defect shipped untested. Same pattern
 * as {@link ExpirySweep}, {@code DisappearingFreezeDecision} and
 * {@code ch.threema.app.voip.services.VoipCallLifecycleGate}.
 */
public final class ConnectionRestartDecision {

    private ConnectionRestartDecision() {
    }

    /**
     * Whether {@code ensureConnection()} should ask the {@code ServiceManager} to start the
     * connection.
     *
     * @param hasConnectionSlots whether any connection slot is currently held; no slot means no
     *     connection is wanted
     * @param startLatchSet the {@code LifetimeServiceImpl.active} latch: {@code true} once a start
     *     has been issued and not yet cleaned up. On its own this proves nothing, see the class
     *     Javadoc
     * @param connectionState the connection's real state, or {@code null} if it could not be read
     *     (for example no {@code ServiceManager} yet, pre-unlock)
     * @return {@code true} to issue a start
     */
    public static boolean shouldStartConnection(
        boolean hasConnectionSlots,
        boolean startLatchSet,
        @Nullable ConnectionState connectionState
    ) {
        if (!hasConnectionSlots) {
            // Nothing wants a connection.
            return false;
        }
        if (!startLatchSet) {
            // Nothing has been started yet. Unchanged from the original behaviour.
            return true;
        }
        if (connectionState == null) {
            // The state is unreadable, so there is no evidence contradicting the latch. Behave
            // exactly as before rather than starting blindly; a start would fail here anyway,
            // because an unreadable state means there is no usable ServiceManager to start through.
            return false;
        }
        // The latch says a connection was started. Believe it only while the connection agrees.
        // DISCONNECTED here is the wedge: the latch suppresses every restart while nothing runs.
        return connectionState == ConnectionState.DISCONNECTED;
    }

    /**
     * Whether the latch and the real connection state disagree: the latch claims a connection was
     * started, while the connection reports {@link ConnectionState#DISCONNECTED}. Reporting only.
     *
     * <p><b>On its own this does not prove a wedge.</b> {@code DISCONNECTED} is also the ordinary
     * transient state during reconnect backoff, and nothing at this layer can tell the two apart:
     * only the connection knows whether a job is still live. A consumer that wants to name the wedge
     * must combine this with that fact. Treating this alone as "wedged" would plant precisely the kind
     * of confidently-wrong signal this whole change exists to remove.
     */
    public static boolean latchDisagreesWithState(
        boolean startLatchSet,
        @Nullable ConnectionState connectionState
    ) {
        return startLatchSet && connectionState == ConnectionState.DISCONNECTED;
    }
}
