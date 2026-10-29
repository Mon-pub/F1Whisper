package ch.threema.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.storage.models.AbstractMessageModel;

/**
 * F1Whisper (second follow-up S2-05): the timeline pagination boundary as ONE immutable value —
 * the {@code (effective sort key, id)} tuple of the oldest loaded row, captured atomically at
 * construction from an already-loaded model.
 *
 * <p>The defect this replaces: the conversation UI stored the id and the sort key in two separate
 * mutable fields exposed through separate filter calls. A worker thread building a page query
 * could observe a new id with an old sort key (or vice versa), or a non-null id that became null
 * before unboxing — producing a boundary that omits or duplicates history, or throws. A single
 * immutable object in a single volatile field makes a torn cursor unrepresentable: readers see
 * either the whole previous cursor or the whole next one.</p>
 *
 * <p>Consumers must snapshot the cursor ONCE (one {@code getPageCursor()} call, one local) before
 * deriving anything from it — see {@code AbstractMessageModelFactory#appendFilter}.</p>
 */
public final class PageCursor {
    private final int id;
    @Nullable
    private final Long sortKey;

    private PageCursor(int id, @Nullable Long sortKey) {
        this.id = id;
        this.sortKey = sortKey;
    }

    /** Capture the boundary tuple from a loaded model (the only production entry point). */
    @NonNull
    public static PageCursor of(@NonNull AbstractMessageModel model) {
        return new PageCursor(model.getId(), TimelineKeyset.effectiveSortKey(model));
    }

    /** Construct from raw tuple values (tests and callers that already hold a captured tuple). */
    @NonNull
    public static PageCursor of(int id, @Nullable Long sortKey) {
        return new PageCursor(id, sortKey);
    }

    public int getId() {
        return id;
    }

    /**
     * The reference row's effective sort key; null when the boundary sits inside the legacy
     * NULL-sort-key tail (see {@code TimelineKeyset.boundaryWhereClause}).
     */
    @Nullable
    public Long getSortKey() {
        return sortKey;
    }

    @NonNull
    @Override
    public String toString() {
        return "PageCursor(id=" + id + ", sortKey=" + sortKey + ")";
    }
}
