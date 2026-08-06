package ch.threema.app.services;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * F1Whisper (fourth fork review, F4-12): a self-update service instance's authority over the SHARED update state, and the
 * guarantee that losing it and using it cannot interleave.
 *
 * <p>The defect this exists to remove: the download worker checked "am I still alive?" while holding the lifecycle lock,
 * released it, and only then performed the side effect. {@code onDestroy} could land in that gap, so a predecessor could
 *
 * <ul>
 *     <li>pass the startup check, be torn down, and then delete the final APK and clear the ready-state record that its
 *     REPLACEMENT had already published - leaving the app showing an update as ready whose file is gone; or</li>
 *     <li>pass the publication check, be torn down, and then rename its own file over the shared final name and record it
 *     as ready - after its authority had ended, contradicting whatever its replacement had done.</li>
 * </ul>
 *
 * <p>Both of those are process-wide state: one final filename, one ready-state record. The operation-unique partial
 * filenames that already exist protect the partial downloads and nothing else, and interrupting the worker does not roll
 * back a filesystem or preference write that is already past the check.</p>
 *
 * <p>So the check and the act become one critical section. {@link #destroy()} takes the same monitor, which makes the two
 * mutually exclusive: a transition either runs entirely before ownership is lost, or does not run at all.</p>
 *
 * <p>What deliberately stays OUTSIDE: the network transfer and the APK validation. They are slow, they touch only the
 * operation's own temp file, and holding this monitor across them would block {@code onDestroy} for the length of a
 * download.</p>
 *
 * <p>No Android imports, so the ownership contract is unit-testable without a device.</p>
 */
public final class ApkUpdateLifecycleOwnership {

    /** A shared-state transition that only the owning instance may perform. */
    @FunctionalInterface
    public interface OwnedAction {
        /**
         * @throws IOException if the transition itself failed - as opposed to not being permitted, which
         * {@link #runIfOwned} reports by returning {@code false}.
         */
        void run() throws IOException;
    }

    private final Object lock = new Object();
    private boolean destroyed = false;

    /**
     * Perform [action] if, and only if, this instance still owns the shared update state.
     *
     * <p>The ownership check and the action are indivisible with respect to {@link #destroy()}.</p>
     *
     * @return {@code true} if ownership held and the action ran; {@code false} if it had already been lost, in which case
     * nothing was done.
     */
    public boolean runIfOwned(@NonNull OwnedAction action) throws IOException {
        synchronized (lock) {
            if (destroyed) {
                return false;
            }
            action.run();
            return true;
        }
    }

    /**
     * Whether this instance has lost ownership. For call sites that only need to abort, not to act.
     *
     * <p>A {@code false} answer is only a snapshot: anything that then TOUCHES shared state must go through
     * {@link #runIfOwned} instead, or it reintroduces exactly the check-then-act gap this class exists to close.</p>
     */
    public boolean isDestroyed() {
        synchronized (lock) {
            return destroyed;
        }
    }

    /**
     * End this instance's authority over the shared update state.
     *
     * <p>Blocks until any transition already inside {@link #runIfOwned} has finished, which is what makes "destroyed" mean
     * "will not touch shared state again" rather than "was asked to stop".</p>
     */
    public void destroy() {
        synchronized (lock) {
            destroyed = true;
        }
    }
}
