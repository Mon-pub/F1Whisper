package ch.threema.app.services.messageplayer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * F1Whisper: transient in-memory state coordinating the one-shot listen-once "burn" animation so it
 * plays exactly once over the FULL bubble and the bubble only collapses to the small "expired" note
 * AFTER the burst finishes — regardless of how many re-renders the burn fires.
 *
 * <p>Two states per message id:</p>
 * <ul>
 *   <li><b>pending</b>: set by {@link AudioMessagePlayer} the moment the message burns; consumed
 *   (check-and-remove) by the first burned bind, which then starts the burst.</li>
 *   <li><b>burning</b>: set while the burst animation runs. Burned binds that see this keep the
 *   bubble full (they do NOT collapse and do NOT restart the burst), so the several re-renders that
 *   {@code enforceListenOnceIfNeeded} triggers (markAsConsumed / save / explicit notify) cannot
 *   collapse the bubble mid-burst. Cleared when the burst ends, which then fires the collapse.</li>
 * </ul>
 *
 * <p>Both sets are in-memory only: after a process death / chat reopen they are empty, so a
 * previously-burned message just shows the collapsed note with NO animation.</p>
 */
public final class ListenOnceBurnRegistry {

    private static final Set<Integer> pending = Collections.synchronizedSet(new HashSet<>());
    private static final Set<Integer> burning = Collections.synchronizedSet(new HashSet<>());

    private ListenOnceBurnRegistry() {
    }

    /** Mark that the given message id just burned and its bubble should play the burst once. */
    public static void markForBurnAnimation(int messageId) {
        pending.add(messageId);
    }

    /**
     * @return {@code true} exactly once per {@link #markForBurnAnimation(int)} call: the first burned
     * bind consumes the signal so the burst is started a single time (and never on reopen / scroll).
     */
    public static boolean consumeBurnAnimation(int messageId) {
        return pending.remove(messageId);
    }

    /** @return {@code true} while the burst animation for this id is running. */
    public static boolean isBurning(int messageId) {
        return burning.contains(messageId);
    }

    /** Mark the burst as running (keeps the bubble full and uncollapsed until it ends). */
    public static void setBurning(int messageId) {
        burning.add(messageId);
    }

    /** Clear the running state when the burst ends, allowing the bubble to collapse to the note. */
    public static void clearBurning(int messageId) {
        burning.remove(messageId);
    }
}
