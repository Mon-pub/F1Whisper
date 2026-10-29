package ch.threema.app.voip.services;

import org.junit.Assert;
import org.junit.Test;

/**
 * F1Whisper (third follow-up S3-08 / T3-15, fork review H-09): regression coverage for the VoIP
 * cold-start lifecycle decision that prevents the {@code NoDefinitionFoundException} crash — a
 * bailed-out onCreate must never let onDestroy/cleanup resolve session-scoped services.
 */
public class VoipCallLifecycleGateTest {

    @Test
    public void coldStartWithoutSessionScopeBailsAndForbidsSessionScopedCleanup() {
        final VoipCallLifecycleGate gate = new VoipCallLifecycleGate();
        // onCreate sees the session scope unavailable: must NOT proceed, and cleanup must NOT resolve
        // session-scoped services (the former NoDefinitionFoundException path).
        Assert.assertFalse(gate.onCreate(false));
        Assert.assertFalse(gate.shouldRunSessionScopedCleanup());
    }

    @Test
    public void readySessionScopeProceedsAndPermitsCleanup() {
        final VoipCallLifecycleGate gate = new VoipCallLifecycleGate();
        Assert.assertTrue(gate.onCreate(true));
        Assert.assertTrue(gate.shouldRunSessionScopedCleanup());
    }

    @Test
    public void aGateWithNoOnCreateForbidsSessionScopedCleanup() {
        // Android can invoke onDestroy without a completed onCreate; cleanup must stay scope-free.
        final VoipCallLifecycleGate gate = new VoipCallLifecycleGate();
        Assert.assertFalse(gate.shouldRunSessionScopedCleanup());
    }

    @Test
    public void cleanupDecisionIsStableAndIdempotentAfterSuccessfulInit() {
        final VoipCallLifecycleGate gate = new VoipCallLifecycleGate();
        gate.onCreate(true);
        // Repeated cleanup (Android may call it more than once, and a failure after full init is
        // still safe to tear down because the scope WAS ready) reads the same decision, no flip.
        Assert.assertTrue(gate.shouldRunSessionScopedCleanup());
        Assert.assertTrue(gate.shouldRunSessionScopedCleanup());
    }

    @Test
    public void bailOutDecisionIsStickyAcrossRepeatedCleanup() {
        final VoipCallLifecycleGate gate = new VoipCallLifecycleGate();
        gate.onCreate(false);
        Assert.assertFalse(gate.shouldRunSessionScopedCleanup());
        Assert.assertFalse(gate.shouldRunSessionScopedCleanup());
    }
}
