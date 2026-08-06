package ch.threema.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Date;

import ch.threema.storage.models.AbstractMessageModel;

/**
 * F1Whisper (fork review H-06 / follow-up P0-6): the SINGLE definition of the chat-timeline
 * ordering tuple and its keyset-pagination boundary.
 *
 * <p>The timeline orders by {@code (effective sort key, id)} descending. A page cursor MUST
 * compare against exactly that tuple: the old id-only boundary silently made any delayed high-ID
 * row with an older sort key (reconnect backlog carrying sender time) unreachable from every later
 * page. Everything that composes ordering or boundary SQL — the three message factories, the
 * pagination filter, and the executable SQL tests — draws the strings from this class, so the
 * filter tuple and the ordering tuple cannot drift apart.</p>
 *
 * <p><b>Cursor semantics (follow-up review P0-6):</b> the boundary is built from a tuple the
 * CALLER carries ({@code referenceSortKey}, {@code referenceId}) captured from an already-loaded
 * row — it is never reconstructed from a database row that may have been deleted between page
 * requests. Two branches:</p>
 * <ul>
 *   <li><b>Reference key non-NULL</b> (the normal case):
 *       {@code (EXPR < ? OR (EXPR = ? AND id < ?) OR EXPR IS NULL)}. The {@code IS NULL} arm keeps
 *       the legacy NULL-key tail reachable: under {@code DESC}, SQLite sorts NULLs last, i.e.
 *       strictly older than every non-NULL key.</li>
 *   <li><b>Reference key NULL</b> (the cursor has entered that NULL tail):
 *       {@code (EXPR IS NULL AND id < ?)}. Inside the tail the ordering degenerates to
 *       {@code id DESC}, so the id boundary is exact there. Without this branch a cursor in the
 *       tail would re-admit ALL tail rows on every page (NULL compares as neither smaller nor
 *       equal), duplicating rows from earlier pages.</li>
 * </ul>
 */
public final class TimelineKeyset {

    /**
     * The timeline's effective sort key. {@code sortAtUtc} is the immutable per-row key
     * (outgoing -> createdAtUtc, incoming -> COALESCE(postedAtUtc, createdAtUtc)); the inner CASE
     * is a defensive fallback for any row written before the v124 backfill.
     */
    public static final String EFFECTIVE_SORT_KEY_EXPR =
        "COALESCE(" + AbstractMessageModel.COLUMN_SORT_AT + ", "
            + "CASE WHEN " + AbstractMessageModel.COLUMN_OUTBOX + " = 1 "
            + "THEN " + AbstractMessageModel.COLUMN_CREATED_AT + " "
            + "ELSE COALESCE(" + AbstractMessageModel.COLUMN_POSTED_AT + ", "
            + AbstractMessageModel.COLUMN_CREATED_AT + ") END)";

    /** Shared timeline ordering: effective sort key, then id for stability. Newest first. */
    public static final String TIMELINE_ORDER_BY =
        EFFECTIVE_SORT_KEY_EXPR + " DESC, " + AbstractMessageModel.COLUMN_ID + " DESC";

    private TimelineKeyset() {
    }

    /**
     * The keyset boundary WHERE fragment for a page cursor at
     * ({@code referenceSortKey}, {@code referenceId}); see the class doc for the two branches.
     * Bind the fragment's placeholders with {@link #boundaryArgs}.
     *
     * <p><b>Every parameter is wrapped in {@code CAST(? AS INTEGER)}.</b> Android binds all
     * selection args as TEXT, and {@code COALESCE(...)} does NOT inherit its column's INTEGER
     * affinity — so a bare {@code EXPR < ?} performs a CROSS-TYPE comparison in which every
     * INTEGER sorts before every TEXT: the boundary would be true for EVERY non-NULL row and each
     * "older" page would restart from the newest message (verified against real SQLite; caught by
     * the executable pagination test, invisible to string comparison). A bare column such as
     * {@code id < ?} would be safe (column affinity converts the TEXT arg), but the CAST form is
     * used uniformly so correctness never depends on that subtlety.</p>
     */
    @NonNull
    public static String boundaryWhereClause(@Nullable Long referenceSortKey) {
        if (referenceSortKey == null) {
            return "(" + EFFECTIVE_SORT_KEY_EXPR + " IS NULL AND "
                + AbstractMessageModel.COLUMN_ID + "<CAST(? AS INTEGER))";
        }
        return "(" + EFFECTIVE_SORT_KEY_EXPR + "<CAST(? AS INTEGER)"
            + " OR (" + EFFECTIVE_SORT_KEY_EXPR + "=CAST(? AS INTEGER) AND "
            + AbstractMessageModel.COLUMN_ID + "<CAST(? AS INTEGER))"
            + " OR " + EFFECTIVE_SORT_KEY_EXPR + " IS NULL)";
    }

    /** Bind arguments matching {@link #boundaryWhereClause} for the same reference tuple. */
    @NonNull
    public static String[] boundaryArgs(@Nullable Long referenceSortKey, int referenceId) {
        if (referenceSortKey == null) {
            return new String[]{String.valueOf(referenceId)};
        }
        return new String[]{
            String.valueOf(referenceSortKey),
            String.valueOf(referenceSortKey),
            String.valueOf(referenceId),
        };
    }

    /**
     * The row's effective sort DATE computed from the model — the value the write path persists
     * into {@code sortAtUtc} on every write. Outgoing rows sort by their local compose time
     * (createdAtUtc) instead of the mutable send-completion time (postedAtUtc is overwritten with
     * sentAt on SEND, which is not monotonic with compose order); incoming rows keep sender time
     * (preserves the reconnect-backlog fix). The formula is stable for a row's whole life, so the
     * stored {@code sortAtUtc} always equals this recomputation — which is what makes
     * {@link #effectiveSortKey} a faithful in-memory mirror of {@link #EFFECTIVE_SORT_KEY_EXPR}.
     */
    @Nullable
    public static Date effectiveSortDate(@NonNull AbstractMessageModel model) {
        if (model.isOutbox()) {
            return model.getCreatedAt();
        }
        return model.getPostedAt() != null ? model.getPostedAt() : model.getCreatedAt();
    }

    /**
     * The row's effective sort key (epoch millis, or null for a legacy NULL-tail row) computed
     * from a LOADED model — this is what page-cursor callers capture and carry, so the boundary
     * never depends on re-reading a row that can disappear between page requests.
     */
    @Nullable
    public static Long effectiveSortKey(@NonNull AbstractMessageModel model) {
        final Date date = effectiveSortDate(model);
        return date != null ? date.getTime() : null;
    }
}
