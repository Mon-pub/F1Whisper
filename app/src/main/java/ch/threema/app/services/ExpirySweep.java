package ch.threema.app.services;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import androidx.annotation.NonNull;

/**
 * F1Whisper: traversal rules for sweeps that enforce disappearing-message expiry.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Enforcing expiry is <b>not</b> a read. {@code DisappearingMessageService.enforceIfExpired} deletes the message
 * (via {@code MessageService.remove}) or, on its repair path, saves it (to stamp a lazily-computed {@code expiresAt}).
 * Either way it fires a listener, and <b>listener dispatch is synchronous and inline on the calling thread</b>
 * ({@code ListenerManager.TypedListenerManager.handle}, {@code ListenerManager.java:77}). The handler runs before
 * {@code enforceIfExpired} returns, and it re-enters {@link ConversationServiceImpl} and <b>structurally modifies the
 * shared conversation cache</b>:
 *
 * <pre>
 * enforceIfExpired(latest)
 *   +- delete path
 *   |    MessageService.remove -&gt; ListenerManager.messageListeners.handle   [SYNCHRONOUS, same thread]
 *   |      -&gt; GlobalListeners.onRemoved                        (GlobalListeners.java:540)
 *   |        -&gt; ConversationServiceImpl.refreshWithDeletedMessage       (:506)
 *   |          -&gt; ConversationModelParser.messageDeleted               (:1179)
 *   |            -&gt; sort() -&gt; Collections.sort(conversationCache, ...)   (:375)  *** modCount++ ***
 *   +- repair path
 *        MessageService.save -&gt; ListenerManager.messageListeners.handle   [SYNCHRONOUS, same thread]
 *          -&gt; GlobalListeners.onModified                       (GlobalListeners.java:506)
 *            -&gt; ConversationServiceImpl.refresh                        (:397)
 *              -&gt; ConversationModelParser.refresh                      (:1046)
 *                -&gt; sort() (:375) and, on a cache miss, cache() (:176)  *** modCount++, size changes ***
 * </pre>
 *
 * <p>So a sweep written as the obvious loop
 * <pre>
 * for (ConversationModel conv : conversationCache) {          // iterator created
 *     DisappearingMessageService.enforceIfExpired(conv.latestMessage);   // re-enters, bumps modCount
 * }                                                          // next() -&gt; ConcurrentModificationException
 * </pre>
 * crashes the caller with {@link java.util.ConcurrentModificationException}, thrown by the <i>caller's own</i>
 * iterator after {@code enforceIfExpired} has already returned normally. That was the crash-loop-at-launch defect
 * reported on {@code 6.4.3o-37}: home screen -&gt; unread count -&gt; {@code getAll} -&gt; sweep -&gt; delete -&gt; crash -&gt; relaunch.
 *
 * <h2>Why {@code synchronized} does not help</h2>
 *
 * <p>The crashing loop already ran inside {@code synchronized (this.conversationCache)}, and {@code sort()} takes that
 * same monitor. A <i>different</i> thread therefore cannot get in — it would block. The mutation arrives on the
 * <b>same thread</b>, through the synchronous listener dispatch, and intrinsic locks are <b>reentrant</b>: the nested
 * {@code synchronized} is entered without waiting. No lock, of any kind, defends against re-entrancy. Nor does a
 * {@code try/catch} around the enforce call: the exception is not thrown by {@code enforceIfExpired} (which promises
 * never to throw), it is thrown afterwards by the iterator.
 *
 * <h2>The rule</h2>
 *
 * <p><b>Never call an expiry-enforcing method while an iterator over a shared collection is live.</b> Read what you
 * need first, let the iterator die, then act. This is the same defensive idiom the codebase already applies one layer
 * up: {@code ListenerManager.handle} copies the listener list before dispatching, precisely because "a handler might
 * modify the array of listeners".
 *
 * <p>The rule lives here, free of Android imports, so it is directly JVM-testable (see {@code ExpirySweepTest}) —
 * {@link ConversationServiceImpl} itself needs Robolectric and a database to instantiate, which is why the defect
 * shipped untested. Same pattern as {@code DisappearingFreezeDecision}, {@code DisappearingTimerConvergence} and
 * {@code VoipCallLifecycleGate}.
 *
 * <p>Note that the collection at risk is process-wide: {@code conversationCache} is a plain shared {@code ArrayList}
 * handed out by {@code CacheService.getConversationModelCache()} ({@code CacheService.java:30}).
 */
public final class ExpirySweep {

    private ExpirySweep() {
    }

    /**
     * Read every target first, then enforce over what was read.
     *
     * <p>Pass 1 iterates {@code source} and collects every non-null {@code extract.apply(item)} into a local list.
     * Pass 2 runs {@code enforce} over that local list, after the iterator over {@code source} is dead. {@code enforce}
     * is therefore <b>never</b> invoked while {@code source} is being iterated, so it is free to re-enter and
     * structurally modify {@code source} — add, remove, sort — without invalidating anything.
     *
     * <p>{@code extract} must be a pure read. It runs during the traversal, so mutating {@code source} from it
     * reintroduces exactly the defect this method exists to remove.
     *
     * <p>Every extracted target is enforced exactly once, in traversal order. Targets are collected before the first
     * enforcement, so a target that {@code enforce} concurrently removes from {@code source} is still enforced; that is
     * intentional and matches the semantics of the loop this replaces.
     *
     * @param source  the collection to walk; iterated exactly once, never mutated by this method
     * @param extract reads the target out of a source item; a {@code null} result skips that item
     * @param enforce the mutating action, run only after the traversal has finished
     * @param <S>     source item type
     * @param <T>     enforced target type
     */
    public static <S, T> void collectThenEnforce(
        @NonNull Collection<S> source,
        @NonNull Function<S, T> extract,
        @NonNull Consumer<T> enforce
    ) {
        final List<T> targets = new ArrayList<>(source.size());
        for (S item : source) {
            final T target = extract.apply(item);
            if (target != null) {
                targets.add(target);
            }
        }
        // The iterator over `source` is dead by here. Only now may anything mutate.
        for (T target : targets) {
            enforce.accept(target);
        }
    }

    /**
     * Enforce over a snapshot of {@code source} and report which items matched.
     *
     * <p>The in-place equivalent, {@code source.removeIf(enforce)}, is unsafe for the same reason: {@code ArrayList}'s
     * {@code removeIf} validates {@code modCount} and throws {@link java.util.ConcurrentModificationException} if the
     * predicate mutated the list. Here the predicate runs over a copy, so it may mutate {@code source} freely.
     *
     * <p>The caller decides what to do with the result — typically {@code source.removeAll(returnedList)}, which is
     * safe because the traversal is already over.
     *
     * @param source  the collection to enforce over; copied, never mutated by this method
     * @param enforce the mutating action; items for which it returns {@code true} are returned
     * @param <T>     item type
     * @return the items, in {@code source} order, for which {@code enforce} returned {@code true}; never {@code null}
     */
    @NonNull
    public static <T> List<T> enforceOnSnapshot(
        @NonNull Collection<T> source,
        @NonNull Predicate<T> enforce
    ) {
        final List<T> snapshot = new ArrayList<>(source);
        final List<T> matched = new ArrayList<>();
        for (T item : snapshot) {
            if (enforce.test(item)) {
                matched.add(item);
            }
        }
        return matched;
    }
}
