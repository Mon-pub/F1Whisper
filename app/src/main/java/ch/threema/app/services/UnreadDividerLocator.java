package ch.threema.app.services;

import java.util.List;

import androidx.annotation.NonNull;

/**
 * Pure, dependency-free helper that decides where the "first unread" divider
 * ({@link ch.threema.storage.models.FirstUnreadMessageModel}) must be inserted into a loaded
 * message window.
 *
 * <p>The window is ordered newest-first (DESC), i.e. index {@code 0} is the newest message and the
 * highest index is the oldest. The divider is inserted immediately <em>older than</em> the oldest
 * unread message in the window, so that every unread message ends up newer than (below) the divider
 * even when already-read or outgoing rows are interleaved among the unread ones. This matches the
 * count shown on the divider ({@code MessageModelFactory.countUnreadMessages}), which is
 * order-independent.
 *
 * <p>Extracted from {@code MessageServiceImpl.markFirstUnread} to be unit-testable on a plain JVM.
 */
final class UnreadDividerLocator {

    private UnreadDividerLocator() {
    }

    /**
     * Immutable snapshot of the divider-relevant flags of a single message row.
     */
    static final class MessageFlags {
        /**
         * A row that never counts as unread (e.g. a {@code null} entry). Kept as a shared instance
         * so the caller can preserve index alignment without allocating for skipped rows.
         */
        static final MessageFlags NOT_UNREAD = new MessageFlags(false, false, false, false, false);

        private final boolean outbox;
        private final boolean read;
        private final boolean statusMessage;
        private final boolean saved;
        private final boolean deletedForEveryone;

        MessageFlags(boolean outbox, boolean read, boolean statusMessage, boolean saved, boolean deletedForEveryone) {
            this.outbox = outbox;
            this.read = read;
            this.statusMessage = statusMessage;
            this.saved = saved;
            this.deletedForEveryone = deletedForEveryone;
        }

        /**
         * Same predicate as {@code MessageModelFactory.countUnreadMessages}: an incoming
         * ({@code !outbox}), saved, not-yet-read, non-status, not-deleted message.
         *
         * <p>F1Whisper (device report 2026-08-06, U-01): the deleted half is why this is written as one predicate in
         * two places rather than two predicates. A message deleted for everyone can never be marked read - the write
         * that would do it refuses a deleted row structurally - so while this said only "not read", the divider
         * anchored at the same tombstone on every single open of that conversation, for good. The count query and this
         * decision have to agree about what unread means, or the divider is drawn at a row the count does not include.
         */
        boolean isUnread() {
            return !outbox && saved && !read && !statusMessage && !deletedForEveryone;
        }
    }

    /**
     * Determine the index at which to insert the unread divider.
     *
     * @param rowsNewestFirst message rows in DESC order (index {@code 0} = newest)
     * @return the insertion index for the divider (immediately older than the oldest unread row),
     * or {@code -1} when the window contains no unread message
     */
    static int findDividerInsertIndex(@NonNull List<MessageFlags> rowsNewestFirst) {
        int oldestUnreadIndex = -1;
        for (int i = 0; i < rowsNewestFirst.size(); i++) {
            final MessageFlags flags = rowsNewestFirst.get(i);
            if (flags != null && flags.isUnread()) {
                // The list is newest-first, so the highest matching index is the oldest unread row.
                oldestUnreadIndex = i;
            }
        }

        if (oldestUnreadIndex < 0) {
            return -1;
        }
        return oldestUnreadIndex + 1;
    }
}
