package ch.threema.app.utils;

import org.junit.Assert;
import org.junit.Test;

/**
 * F1Whisper (second follow-up S2-05): the request-generation guard that rejects page-load results
 * computed against a cursor that was reset while the load was in flight.
 */
public class PageRequestGuardTest {

    @Test
    public void tokenStaysCurrentWhileNothingInvalidates() {
        final PageRequestGuard guard = new PageRequestGuard();
        final int token = guard.current();
        Assert.assertTrue(guard.isCurrent(token));
        Assert.assertTrue(guard.isCurrent(guard.current()));
    }

    @Test
    public void invalidateRejectsEveryPreviouslyCapturedToken() {
        final PageRequestGuard guard = new PageRequestGuard();
        final int before = guard.current();
        guard.invalidate();
        Assert.assertFalse("token captured before the reset must be rejected", guard.isCurrent(before));
        Assert.assertTrue(guard.isCurrent(guard.current()));
    }

    @Test
    public void outOfOrderCompletionOfTwoGenerationsKeepsOnlyTheNewest() {
        final PageRequestGuard guard = new PageRequestGuard();
        final int firstLoad = guard.current();
        guard.invalidate(); // conversation switched while firstLoad was in flight
        final int secondLoad = guard.current();

        // Completion order reversed: the newer load's result applies, the older one is dropped.
        Assert.assertTrue(guard.isCurrent(secondLoad));
        Assert.assertFalse(guard.isCurrent(firstLoad));
    }

    @Test
    public void repeatedInvalidationsKeepRejectingOlderGenerations() {
        final PageRequestGuard guard = new PageRequestGuard();
        final int t0 = guard.current();
        guard.invalidate();
        final int t1 = guard.current();
        guard.invalidate();

        Assert.assertFalse(guard.isCurrent(t0));
        Assert.assertFalse(guard.isCurrent(t1));
        Assert.assertTrue(guard.isCurrent(guard.current()));
    }

    // --- Third follow-up S3-06 / T3-11: single-in-flight page-load latch ---

    @Test
    public void tryBeginLoadSucceedsWhenIdleAndReturnsCurrentGeneration() {
        final PageRequestGuard guard = new PageRequestGuard();
        final int token = guard.tryBeginLoad();
        Assert.assertNotEquals(PageRequestGuard.NO_TOKEN, token);
        Assert.assertEquals(guard.current(), token);
    }

    @Test
    public void secondConcurrentDispatchIsRejectedWhileALoadIsInFlight() {
        final PageRequestGuard guard = new PageRequestGuard();
        final int first = guard.tryBeginLoad();
        Assert.assertNotEquals(PageRequestGuard.NO_TOKEN, first);
        // Pull-to-refresh + quote catch-up overlap: the second dispatch must NOT get a slot.
        Assert.assertEquals(PageRequestGuard.NO_TOKEN, guard.tryBeginLoad());
    }

    @Test
    public void slotIsReusableAfterTheOwningLoadReleasesIt() {
        final PageRequestGuard guard = new PageRequestGuard();
        final int first = guard.tryBeginLoad();
        guard.endLoad(first);
        final int second = guard.tryBeginLoad();
        Assert.assertNotEquals("the slot must be acquirable again after endLoad", PageRequestGuard.NO_TOKEN, second);
    }

    @Test
    public void invalidateFreesTheSlotEvenWithoutEndLoad() {
        final PageRequestGuard guard = new PageRequestGuard();
        final int first = guard.tryBeginLoad();
        Assert.assertNotEquals(PageRequestGuard.NO_TOKEN, first);
        // A cursor reset (conversation switch) whose in-flight completion never arrives must not
        // wedge pagination: the slot is freed, and the old token is now stale.
        guard.invalidate();
        Assert.assertFalse(guard.isCurrent(first));
        Assert.assertNotEquals(PageRequestGuard.NO_TOKEN, guard.tryBeginLoad());
    }

    @Test
    public void staleCompletionDoesNotReleaseANewerLoadsSlot() {
        final PageRequestGuard guard = new PageRequestGuard();
        // Load A starts, cursor is reset (freeing the slot), then load B starts against the new
        // cursor. A now completes out of order: its endLoad must be a no-op, NOT free B's slot.
        final int a = guard.tryBeginLoad();
        guard.invalidate();
        final int b = guard.tryBeginLoad();
        Assert.assertNotEquals(PageRequestGuard.NO_TOKEN, b);

        guard.endLoad(a); // stale completion of A — generation-guarded no-op

        // B still owns the slot: a fresh dispatch must be rejected.
        Assert.assertEquals(PageRequestGuard.NO_TOKEN, guard.tryBeginLoad());
        // And B's own completion still correctly releases it.
        guard.endLoad(b);
        Assert.assertNotEquals(PageRequestGuard.NO_TOKEN, guard.tryBeginLoad());
    }

    @Test
    public void refreshQuoteOverlapSerializesThenReleasesInOrder() {
        final PageRequestGuard guard = new PageRequestGuard();
        // Refresh acquires the slot; the quote catch-up dispatched concurrently is rejected.
        final int refresh = guard.tryBeginLoad();
        Assert.assertEquals(PageRequestGuard.NO_TOKEN, guard.tryBeginLoad());
        // Refresh completes and releases; the quote catch-up (retried) can now proceed.
        guard.endLoad(refresh);
        final int quote = guard.tryBeginLoad();
        Assert.assertNotEquals(PageRequestGuard.NO_TOKEN, quote);
        Assert.assertTrue(guard.isCurrent(quote));
    }
}
