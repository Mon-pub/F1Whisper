package ch.threema.app.services;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import ch.threema.app.services.UnreadDividerLocator.MessageFlags;

import static org.junit.Assert.assertEquals;

/**
 * Pure-JVM tests for the unread-divider placement decision. The window is newest-first (DESC):
 * index 0 is the newest message, the highest index is the oldest. The returned insert index is the
 * position at which the divider is inserted, i.e. immediately older than the oldest unread row, so
 * every unread row ends up newer than (below) the divider.
 */
public class UnreadDividerLocatorTest {

    private static MessageFlags unread() {
        // incoming, saved, not read, not status
        return new MessageFlags(false, false, false, true);
    }

    private static MessageFlags read() {
        // incoming, already read
        return new MessageFlags(false, true, false, true);
    }

    private static MessageFlags outbox() {
        // outgoing (own) message (always considered read)
        return new MessageFlags(true, true, false, true);
    }

    private static MessageFlags status() {
        // status message (e.g. date separator / call status); must be ignored
        return new MessageFlags(false, false, true, true);
    }

    private static MessageFlags unsavedIncoming() {
        // incoming but not yet saved -> not counted as unread (matches count predicate)
        return new MessageFlags(false, false, false, false);
    }

    private static List<MessageFlags> rows(MessageFlags... flags) {
        return new ArrayList<>(Arrays.asList(flags));
    }

    @Test
    public void emptyWindow_returnsMinusOne() {
        assertEquals(-1, UnreadDividerLocator.findDividerInsertIndex(Collections.emptyList()));
    }

    @Test
    public void zeroUnread_returnsMinusOne() {
        assertEquals(-1, UnreadDividerLocator.findDividerInsertIndex(rows(read(), outbox(), read())));
    }

    @Test
    public void onlyStatusAndReadRows_returnsMinusOne() {
        assertEquals(-1, UnreadDividerLocator.findDividerInsertIndex(rows(status(), read(), outbox())));
    }

    @Test
    public void singleUnread_dividerBelowIt() {
        assertEquals(1, UnreadDividerLocator.findDividerInsertIndex(rows(unread())));
    }

    @Test
    public void contiguousUnread_upstreamCase_dividerBelowOldestUnread() {
        // newest 3 are unread, older 2 already read -> divider inserted at index 3
        assertEquals(3, UnreadDividerLocator.findDividerInsertIndex(
            rows(unread(), unread(), unread(), read(), read())));
    }

    @Test
    public void interleavedOutboxRow_dividerBracketsAllUnread() {
        // THE BUG: 9 unread with an own (outbox) reply sitting in the middle of the backlog.
        // indices 0-3 unread, 4 outbox, 5-9 unread -> oldest unread at index 9 -> insert at 10,
        // so all 9 unread rows (every index < 10) stay below the divider.
        List<MessageFlags> window = rows(
            unread(), unread(), unread(), unread(),
            outbox(),
            unread(), unread(), unread(), unread(), unread());
        assertEquals(10, UnreadDividerLocator.findDividerInsertIndex(window));
    }

    @Test
    public void interleavedReadRow_dividerBracketsAllUnread() {
        // a read row interleaved mid-unread must not stop the scan early
        assertEquals(5, UnreadDividerLocator.findDividerInsertIndex(
            rows(unread(), unread(), read(), unread(), unread())));
    }

    @Test
    public void allUnread_dividerAtOldestEdge() {
        assertEquals(3, UnreadDividerLocator.findDividerInsertIndex(rows(unread(), unread(), unread())));
    }

    @Test
    public void unreadExtendsBeyondWindowEdge_dividerAtOldestRow() {
        // The oldest loaded row is itself unread (the unread run continues past the loaded page).
        // Divider anchors at the oldest unread in the window: insert index == window size.
        List<MessageFlags> window = rows(outbox(), unread(), unread());
        assertEquals(window.size(), UnreadDividerLocator.findDividerInsertIndex(window));
    }

    @Test
    public void statusMessagesIgnored_betweenUnread() {
        // status row between unread rows is neither counted nor a stopping point
        assertEquals(3, UnreadDividerLocator.findDividerInsertIndex(
            rows(unread(), status(), unread(), read())));
    }

    @Test
    public void statusMessageAtOldestEdge_notCountedAsUnread() {
        // trailing status row does not push the divider past the oldest real unread row
        assertEquals(2, UnreadDividerLocator.findDividerInsertIndex(
            rows(unread(), unread(), status())));
    }

    @Test
    public void unsavedIncoming_notCountedAsUnread() {
        // an incoming-but-unsaved row mirrors isSaved=1 in the count predicate: it is skipped
        assertEquals(3, UnreadDividerLocator.findDividerInsertIndex(
            rows(unread(), unsavedIncoming(), unread())));
    }

    @Test
    public void notUnreadSentinel_ignored() {
        // the caller maps null rows to NOT_UNREAD; verify it never counts as unread
        List<MessageFlags> window = rows(unread(), MessageFlags.NOT_UNREAD, unread());
        assertEquals(3, UnreadDividerLocator.findDividerInsertIndex(window));
    }
}
