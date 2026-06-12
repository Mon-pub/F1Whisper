package ch.threema.app.emojis;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracks which messages have had their spoiler content revealed during the current app session.
 * <p>
 * This is intentionally in-memory only and never persisted: spoilers are re-obscured on the next
 * app launch (matching the behaviour of other messengers). Reveal granularity is per-message (keyed
 * by {@code AbstractMessageModel.getId()}), so revealing one spoiler in a message reveals every
 * spoiler in that same message.
 */
public class SpoilerRevealState {

    private static volatile SpoilerRevealState instance;

    private final Set<Integer> revealedMessageIds = Collections.synchronizedSet(new HashSet<>());

    private SpoilerRevealState() {
    }

    public static SpoilerRevealState getInstance() {
        if (instance == null) {
            synchronized (SpoilerRevealState.class) {
                if (instance == null) {
                    instance = new SpoilerRevealState();
                }
            }
        }
        return instance;
    }

    public boolean isRevealed(int messageModelId) {
        return revealedMessageIds.contains(messageModelId);
    }

    public void reveal(int messageModelId) {
        revealedMessageIds.add(messageModelId);
    }

    public void clear() {
        revealedMessageIds.clear();
    }
}
