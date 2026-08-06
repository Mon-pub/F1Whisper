package ch.threema.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.Iterator;

import ch.threema.storage.models.AbstractMessageModel;

/**
 * F1Whisper (sixth fork review, F6-01): make every cached instance of a row agree with the row after a conditional
 * lifecycle write has changed it.
 *
 * <p><b>The defect.</b> {@link MessageRowUpdate} made the fifth review's lifecycle transitions column-scoped and
 * non-inserting, so they can no longer resurrect a deleted message or write back a field they never looked at. What they
 * could not do on their own is stay written. The service keeps incoming messages in per-type caches from the moment they
 * are created, and an unread query, a timeline page and a send callback each hand out their own instance of the same row.
 * A transition updates the row and the one instance it was given; every OTHER live instance is, from that moment, a
 * pre-transition snapshot. The next ordinary full-row save from one of them - an incoming edit resolved through the
 * cache, a group delivery receipt, a star or pin toggle from the timeline - writes that snapshot back over the top. A
 * countdown that had just started was cancelled, a read message went unread again, a terminal state regressed.</p>
 *
 * <p><b>The rule.</b> A write and the refresh of every instance of what it wrote are ONE operation, under the same lock
 * the full-row save takes. So there is no instant at which a full-row save can observe a stale instance of a row that has
 * already moved, and any instance that outlives the write carries the winner's values rather than its own.</p>
 *
 * <p>When the row has gone - claimed by expiry, hard-deleted - the instances are evicted instead: there is nothing left
 * for them to agree with, and leaving them cached is how a later lookup found a message the user had deleted.</p>
 *
 * <p>Android-free, so the reconciliation that ships is executable in a JVM test with real models and a real collection.</p>
 */
public final class MessageCacheCoherence {

    private MessageCacheCoherence() {
    }

    /**
     * Refresh (or evict) every entry of {@code cache} that represents row {@code messageId}.
     *
     * <p>The caller must already hold the monitor that guards {@code cache} - the same one
     * {@code MessageServiceImpl.save} takes - because "write, then refresh" is only worth anything if a full-row save
     * cannot run between the two halves.</p>
     *
     * @param persisted the row as it now stands, or {@code null} if it no longer exists.
     * @return how many cached instances were refreshed or evicted.
     */
    public static int reconcile(
        @NonNull Collection<? extends AbstractMessageModel> cache,
        int messageId,
        @Nullable AbstractMessageModel persisted
    ) {
        if (messageId <= 0) {
            return 0;
        }
        int touched = 0;
        final Iterator<? extends AbstractMessageModel> iterator = cache.iterator();
        while (iterator.hasNext()) {
            final AbstractMessageModel cached = iterator.next();
            if (cached == null || cached.getId() != messageId) {
                continue;
            }
            if (persisted == null) {
                iterator.remove();
                touched++;
            } else if (cached != persisted) {
                cached.adoptPersistedRow(persisted);
                touched++;
            }
        }
        return touched;
    }

    /**
     * F1Whisper (seventh fork review, F7-01): reconcile {@code cache} with the OUTCOME of a full-row save, and admit
     * {@code model} only if that save actually wrote a row.
     *
     * <p><b>The defect.</b> The sixth review taught {@code createOrUpdate} to refuse to reinsert a row that had gone,
     * and the caller discarded that answer: it cached the model either way. The service's id getters read the cache
     * BEFORE the database, and the persistent send tasks load their message by local id, so a message the user had
     * hard-deleted mid-send was handed back to its task in full - body, blob id, encryption key - and transmitted. The
     * database was empty throughout, so nothing could show that the deleted payload had left the device. A
     * non-reinsertion guard is not a deletion boundary while a cache-backed sender can carry on from the same deleted
     * model.</p>
     *
     * <p>So a refused save EVICTS. Every instance of that id leaves the cache and the model is not admitted; a
     * successful one makes every instance agree with what was written, exactly as a conditional write does.</p>
     *
     * <p>The caller must hold the monitor guarding {@code cache}, which is also the monitor the save itself took.</p>
     *
     * @param persisted   whether the row was actually written.
     * @param cachesModel whether this type is held in {@code cache} at all (distribution-list messages are not, and
     *                    admitting them would change which instance every reader of them sees).
     * @return {@code persisted}, so a caller can return this directly.
     */
    @SuppressWarnings("unchecked")
    public static boolean admit(
        @NonNull Collection<? extends AbstractMessageModel> cache,
        @NonNull AbstractMessageModel model,
        boolean persisted,
        boolean cachesModel
    ) {
        if (!persisted) {
            reconcile(cache, model.getId(), null);
            return false;
        }
        reconcile(cache, model.getId(), model);
        if (cachesModel && !contains(cache, model)) {
            ((Collection<AbstractMessageModel>) cache).add(model);
        }
        return true;
    }

    /** Whether {@code cache} already holds this very instance. Identity, not equality: these models have no equals. */
    private static boolean contains(
        @NonNull Collection<? extends AbstractMessageModel> cache,
        @NonNull AbstractMessageModel model
    ) {
        for (AbstractMessageModel cached : cache) {
            if (cached == model) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code cache} holds any instance of row {@code messageId}.
     *
     * <p>Exists so the caller can skip the re-read that {@link #reconcile} needs when nothing is cached, which is the
     * common case for a lifecycle write on an old message.</p>
     */
    public static boolean holds(@NonNull Collection<? extends AbstractMessageModel> cache, int messageId) {
        if (messageId <= 0) {
            return false;
        }
        for (AbstractMessageModel cached : cache) {
            if (cached != null && cached.getId() == messageId) {
                return true;
            }
        }
        return false;
    }
}
