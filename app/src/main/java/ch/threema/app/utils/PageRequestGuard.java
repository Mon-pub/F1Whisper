package ch.threema.app.utils;

import androidx.annotation.NonNull;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * F1Whisper (second follow-up S2-05): request-generation guard for paginated timeline loads.
 *
 * <p>The conversation UI can have several asynchronous page loads in flight on different paths
 * (pull-to-refresh via the view model, the quote-search catch-up loop, the initial conversation
 * load) while the page cursor is atomically replaced or cleared (conversation switch, empty-chat
 * reset). A result computed against a cursor that has since been INVALIDATED must not be applied:
 * it would insert rows for a stale boundary — duplicated or misplaced history.</p>
 *
 * <p>Usage: a dispatcher captures {@link #current()} before starting an asynchronous load and the
 * completion path applies the result only if {@link #isCurrent(int)} still holds. Cursor RESETS
 * call {@link #invalidate()}; ordinary cursor advancement within one load sequence does not (the
 * next page legitimately continues the same generation).</p>
 *
 * <p><b>Third follow-up (S3-06 / T3-11):</b> the generation guard only rejects results computed
 * against a cursor that was RESET; it does not stop two loads dispatched in the SAME generation
 * (pull-to-refresh at one boundary, the quote-jump catch-up at another) from both reading the same
 * cursor page and both advancing the cursor / inserting the rows — duplicated or reordered history.
 * A single-in-flight LATCH now bounds this: at most one "load older page" request may be
 * outstanding against the current cursor. A dispatcher calls {@link #tryBeginLoad()}; a non-null
 * token means it owns the slot and must release it with {@link #endLoad(int)} when the load
 * completes (in EVERY completion branch). A second concurrent dispatch gets {@link #NO_TOKEN} and
 * must not race. {@link #endLoad(int)} is generation-guarded so a stale completion can never
 * release a newer load's slot, and {@link #invalidate()} frees the slot on a cursor reset so a
 * completion that never arrives cannot wedge pagination for the open conversation.</p>
 */
public final class PageRequestGuard {
    /** Sentinel returned by {@link #tryBeginLoad()} when a page load is already outstanding. */
    public static final int NO_TOKEN = -1;

    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicBoolean loadInFlight = new AtomicBoolean(false);

    /**
     * F1Whisper (fifth fork review, F5-03): the monitor that makes "still current" and "apply the result" one step.
     *
     * <p>Checking {@link #isCurrent(int)} and then mutating the fragment's cursor or list was a check-then-act: a
     * conversation reset landing in that gap invalidated the generation, and the stale worker went on to restore the
     * previous conversation's cursor over it anyway. {@link #invalidate()} takes the same monitor, so a reset either
     * happens entirely before a completion or entirely after it.</p>
     */
    private final Object transition = new Object();

    /** The token an asynchronous load must capture before it starts. */
    public int current() {
        return generation.get();
    }

    /** Invalidate every in-flight load (cursor reset / conversation switch). */
    public void invalidate() {
        synchronized (transition) {
            generation.incrementAndGet();
            // A cursor reset abandons any in-flight load (its result is now stale and rejected by
            // isCurrent). Free the single-load slot so a load against the NEW cursor can start — and so
            // a completion that never fires cannot permanently wedge pagination for the conversation.
            loadInFlight.set(false);
        }
    }

    /** Whether a result computed under {@code token} may still be applied. */
    public boolean isCurrent(int token) {
        return generation.get() == token;
    }

    /**
     * F1Whisper (fifth fork review, F5-03): run {@code action} if, and only if, {@code token} is still the current
     * generation, indivisibly with respect to {@link #invalidate()}.
     *
     * <p>Every mutation of shared conversation state - the page cursor, the message list, the pinned set - belongs
     * inside one of these. Doing the check separately let a worker that had been paused after its check restore the
     * previous conversation's boundary on top of the new one.</p>
     *
     * @return whether the action ran.
     */
    public boolean runIfCurrent(int token, @NonNull Runnable action) {
        synchronized (transition) {
            if (generation.get() != token) {
                return false;
            }
            action.run();
            return true;
        }
    }

    /**
     * Try to become the single outstanding "load older page" request against the current cursor.
     *
     * @return the current generation token (pass it to {@link #isCurrent(int)} on completion and to
     *     {@link #endLoad(int)} to release the slot) if the slot was free, or {@link #NO_TOKEN} if a
     *     page load is already in flight (the caller must NOT start a second concurrent load). Every
     *     successful acquisition MUST be released with {@link #endLoad(int)}.
     */
    public int tryBeginLoad() {
        if (loadInFlight.compareAndSet(false, true)) {
            return generation.get();
        }
        return NO_TOKEN;
    }

    /**
     * Release the single-load slot acquired via {@link #tryBeginLoad()}. Generation-guarded: a
     * STALE completion (whose cursor was reset, freeing the slot for a newer load) is a no-op and
     * cannot release the newer load's slot. Safe to call from every completion branch.
     */
    public void endLoad(int token) {
        if (generation.get() == token) {
            loadInFlight.set(false);
        }
    }
}
