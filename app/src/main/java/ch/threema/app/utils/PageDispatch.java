package ch.threema.app.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.storage.PageCursor;

/**
 * F1Whisper (fourth fork review, F4-11): everything one paginated page load is allowed to see, frozen at dispatch.
 *
 * <p>The defect this exists to remove: a page worker captured a generation token and then, on its own thread, read the
 * fragment's LIVE {@code messageReceiver} and page cursor. {@link PageRequestGuard} only ever governed whether the
 * RESULT could be applied, so during a conversation switch a stale worker would query the NEW conversation, advance the
 * NEW cursor through {@code valuesLoaded}, and then have its rows discarded by the old-token check in its completion. A
 * page of the newly opened chat's history was consumed and thrown away, so it stayed missing until a full reload, and a
 * worker that resumed inside the reset's temporary-null window could fail on a null receiver outright.</p>
 *
 * <p>A generation number alone cannot fix that, because the damage is done by the QUERY, not by the result. What is
 * needed is that the worker never looks at live state at all: it validates its generation, queries the receiver and
 * cursor it was dispatched with, and only advances shared cursor state if the generation still holds when it finishes.
 * This class is that snapshot - immutable, so there is nothing for a switch to change under it.</p>
 *
 * <p>A dispatcher that cannot build one (no receiver, because a reset is in progress) has nothing to load, which is the
 * correct answer and not an error.</p>
 */
public final class PageDispatch {
    /** {@link #getPageSize()} for a load that reads the WHOLE conversation and therefore has no page at all. */
    public static final long WHOLE_CONVERSATION = 0L;

    private final int generation;
    private final @NonNull MessageReceiver<?> receiver;
    private final @Nullable PageCursor cursor;
    private final @Nullable Integer pageReferenceId;
    private final long pageSize;

    public PageDispatch(
        int generation,
        @NonNull MessageReceiver<?> receiver,
        @Nullable PageCursor cursor,
        @Nullable Integer pageReferenceId,
        long pageSize
    ) {
        this.generation = generation;
        this.receiver = receiver;
        this.cursor = cursor;
        this.pageReferenceId = pageReferenceId;
        this.pageSize = pageSize;
    }

    /** The {@link PageRequestGuard} generation this load was dispatched under. */
    public int getGeneration() {
        return generation;
    }

    /** The conversation this load belongs to. Never the one that happens to be open when it runs. */
    @NonNull
    public MessageReceiver<?> getReceiver() {
        return receiver;
    }

    /** The pagination boundary as it stood at dispatch. */
    @Nullable
    public PageCursor getCursor() {
        return cursor;
    }

    /** The legacy page reference, for the pre-cursor query path. */
    @Nullable
    public Integer getPageReferenceId() {
        return pageReferenceId;
    }

    /**
     * F1Whisper (fifth fork review, F5-03): how many rows this load may read, frozen at dispatch.
     *
     * <p>The large-unread initial load sized its query from the fragment's live {@code unreadCount}, so a worker that
     * resumed after a conversation switch queried the NEW conversation with the NEW conversation's count. The count is
     * part of what a load is dispatched with, exactly like the receiver and the cursor.</p>
     *
     * @return the page size, or {@link #WHOLE_CONVERSATION} for a load that reads everything.
     */
    public long getPageSize() {
        return pageSize;
    }

    /**
     * Whether this load may still touch shared state, i.e. its conversation is still the open one.
     *
     * <p>Asked twice on purpose: BEFORE the query, so a load whose conversation has already been replaced never reads
     * the new one at all, and again after it, so one that was replaced mid-query does not advance the new cursor.</p>
     */
    public boolean isCurrentIn(@NonNull PageRequestGuard guard) {
        return guard.isCurrent(generation);
    }
}
