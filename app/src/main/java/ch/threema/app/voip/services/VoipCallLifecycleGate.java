package ch.threema.app.voip.services;

/**
 * F1Whisper (third follow-up S3-08 / T3-15, fork review H-09): the cold-start lifecycle decision of
 * {@link VoipCallService}, extracted as a pure, unit-tested unit.
 *
 * <p>On a cold-start incoming call the process can be woken (via F1 push / the call notification)
 * before the session scope is live — {@code ServiceManager} binds only on master-key unlock. The
 * session-scoped {@code VoipStateService} is then unresolvable, and touching it crashes with a Koin
 * {@code NoDefinitionFoundException}. onCreate() must bail out (stopSelf) WITHOUT resolving any
 * session-scoped dependency, and — crucially — the onDestroy() cleanup Android still invokes must
 * NOT resolve them either, or it throws the very exception the guard exists to prevent.</p>
 *
 * <p>This gate captures the single invariant: session-scoped CLEANUP is permitted only if
 * session-scoped INITIALIZATION actually ran, which happens only when the scope was ready at
 * onCreate. The real Service {@code onCreate -> stopSelf -> onDestroy} lifecycle across the device
 * matrix stays an instrumented item; this class makes the decision itself regression-testable.</p>
 */
public final class VoipCallLifecycleGate {

    // Set true only after onCreate() confirms the session scope was ready. volatile preserves the
    // cross-thread visibility of the original field between onCreate and onDestroy/cleanup.
    private volatile boolean sessionScopedInitialized = false;

    /**
     * onCreate decision.
     *
     * @param sessionScopeReady whether the Koin session scope is bound right now
     * @return {@code true} to proceed with session-scoped initialization (and record that cleanup
     *     may later run); {@code false} to {@code stopSelf} and bail WITHOUT resolving any
     *     session-scoped dependency
     */
    public boolean onCreate(boolean sessionScopeReady) {
        if (!sessionScopeReady) {
            return false;
        }
        sessionScopedInitialized = true;
        return true;
    }

    /**
     * cleanup decision: whether cleanup may resolve/tear down session-scoped services. Stays
     * {@code false} until a successful {@link #onCreate(boolean)} — so a bailed cold start (or an
     * onDestroy with no completed onCreate) never touches the session scope. Idempotent.
     */
    public boolean shouldRunSessionScopedCleanup() {
        return sessionScopedInitialized;
    }
}
