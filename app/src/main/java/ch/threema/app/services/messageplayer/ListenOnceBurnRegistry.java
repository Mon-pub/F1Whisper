package ch.threema.app.services.messageplayer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * F1Whisper: transient in-memory state coordinating the one-shot listen-once "burn" animation so it
 * plays exactly once over the FULL bubble and the bubble only collapses to the small "expired" note
 * AFTER the burst finishes — regardless of how many re-renders the burn fires.
 *
 * <p>Two states per message:</p>
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
 *
 * <p>F1Whisper (twelfth fork review, F12-01): keyed by {@link ListenOnceMessageIdentity}, not the
 * table-local integer row id — equal ids across the contact/group/distribution-list tables are
 * normal, and an integer key played the burst (or froze the collapse) on an unrelated bubble that
 * happened to share the number.</p>
 */
public final class ListenOnceBurnRegistry {

    private static final Set<ListenOnceMessageIdentity> pending = Collections.synchronizedSet(new HashSet<>());
    private static final Set<ListenOnceMessageIdentity> burning = Collections.synchronizedSet(new HashSet<>());

    private ListenOnceBurnRegistry() {
    }

    /** Mark that the given message just burned and its bubble should play the burst once. */
    public static void markForBurnAnimation(ListenOnceMessageIdentity identity) {
        pending.add(identity);
    }

    /**
     * @return {@code true} exactly once per {@link #markForBurnAnimation} call: the first burned
     * bind consumes the signal so the burst is started a single time (and never on reopen / scroll).
     */
    public static boolean consumeBurnAnimation(ListenOnceMessageIdentity identity) {
        return pending.remove(identity);
    }

    /** @return {@code true} while the burst animation for this message is running. */
    public static boolean isBurning(ListenOnceMessageIdentity identity) {
        return burning.contains(identity);
    }

    /** Mark the burst as running (keeps the bubble full and uncollapsed until it ends). */
    public static void setBurning(ListenOnceMessageIdentity identity) {
        burning.add(identity);
    }

    /** Clear the running state when the burst ends, allowing the bubble to collapse to the note. */
    public static void clearBurning(ListenOnceMessageIdentity identity) {
        burning.remove(identity);
    }
}
