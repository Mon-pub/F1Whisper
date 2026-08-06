package ch.threema.app.services;

import org.junit.Assert;
import org.junit.Test;

import ch.threema.app.services.ApkUpdateStartArbiter.NotificationAction;
import ch.threema.app.services.ApkUpdateStartArbiter.StartDecision;

/**
 * F1Whisper (follow-up review P0-7, second follow-up S2-01): the self-update service's start/stop
 * lifecycle over every interleaving the reviews call out — a second start arriving at every point
 * around the first start's completion, duplicate taps while active, and invalid URLs while active
 * and while idle.
 *
 * Invariants under test:
 * <ul>
 *   <li>the stop id returned by {@code onFinished} NEVER belongs to an operation that was
 *       accepted and has not itself finished — a finishing operation can never tear down a later
 *       accepted download;</li>
 *   <li>(S2-01) every start decision carries a foreground-notification action, no rejected start
 *       while a download runs ever carries a fresh-progress or teardown action (it must re-post
 *       the CURRENT progress), and the finisher API structurally exposes NO demote action — the
 *       only demotions are the invalid-idle start's own PROMOTE_THEN_STOP and implicit service
 *       destruction.</li>
 * </ul>
 */
public class ApkUpdateStartArbiterTest {

    @Test
    public void acceptWhenIdle() {
        final ApkUpdateStartArbiter arbiter = new ApkUpdateStartArbiter();
        Assert.assertEquals(StartDecision.ACCEPT, arbiter.onStart(1, true));
        Assert.assertTrue(arbiter.isActive());
    }

    @Test
    public void duplicateWhileActiveIsRejectedAndCoveredByTheFinishersStop() {
        final ApkUpdateStartArbiter arbiter = new ApkUpdateStartArbiter();
        Assert.assertEquals(StartDecision.ACCEPT, arbiter.onStart(1, true));
        // Review interleaving: B arrives BEFORE A finishes -> rejected, nothing stops now...
        Assert.assertEquals(StartDecision.REJECT_DUPLICATE, arbiter.onStart(2, true));
        Assert.assertTrue(arbiter.isActive());
        // ...and A's finish stops with B's id (the most recent), which is correct because B
        // produced no work — the service must not stay alive for it.
        Assert.assertEquals(2, arbiter.onFinished(1));
        Assert.assertFalse(arbiter.isActive());
    }

    @Test
    public void startAfterFinishIsAcceptedAndNotStoppedByTheFinisher() {
        final ApkUpdateStartArbiter arbiter = new ApkUpdateStartArbiter();
        Assert.assertEquals(StartDecision.ACCEPT, arbiter.onStart(1, true));
        // Review interleaving: A completes fully, THEN B arrives. A's stop decision was made with
        // A's own id — Android's stopSelfResult(1) then returns false once B (a newer start) is
        // delivered, so B's accepted download proceeds untouched.
        Assert.assertEquals(1, arbiter.onFinished(1));
        Assert.assertEquals(StartDecision.ACCEPT, arbiter.onStart(2, true));
        Assert.assertTrue(arbiter.isActive());
        Assert.assertEquals(2, arbiter.onFinished(2));
    }

    @Test
    public void invalidStartWhileActiveNeverStopsTheDownload() {
        final ApkUpdateStartArbiter arbiter = new ApkUpdateStartArbiter();
        Assert.assertEquals(StartDecision.ACCEPT, arbiter.onStart(1, true));
        Assert.assertEquals(StartDecision.REJECT_INVALID_KEEP_RUNNING, arbiter.onStart(2, false));
        Assert.assertTrue("invalid start must not clear the active download", arbiter.isActive());
        Assert.assertEquals(2, arbiter.onFinished(1));
    }

    @Test
    public void invalidStartWhileIdleStopsWithItsOwnId() {
        final ApkUpdateStartArbiter arbiter = new ApkUpdateStartArbiter();
        Assert.assertEquals(StartDecision.REJECT_INVALID_STOP_SELF, arbiter.onStart(1, false));
        Assert.assertFalse(arbiter.isActive());
        // A valid start right after must still be accepted (the invalid start stopped with its
        // OWN id, so a newer delivered start keeps the service alive per the Android contract).
        Assert.assertEquals(StartDecision.ACCEPT, arbiter.onStart(2, true));
    }

    @Test
    public void foreignFinishDoesNotClearTheActiveDownload() {
        final ApkUpdateStartArbiter arbiter = new ApkUpdateStartArbiter();
        Assert.assertEquals(StartDecision.ACCEPT, arbiter.onStart(1, true));
        arbiter.onFinished(99);
        Assert.assertTrue(arbiter.isActive());
    }

    @Test
    public void repeatedDuplicatesAllCoveredBySingleFinish() {
        final ApkUpdateStartArbiter arbiter = new ApkUpdateStartArbiter();
        Assert.assertEquals(StartDecision.ACCEPT, arbiter.onStart(1, true));
        Assert.assertEquals(StartDecision.REJECT_DUPLICATE, arbiter.onStart(2, true));
        Assert.assertEquals(StartDecision.REJECT_DUPLICATE, arbiter.onStart(3, true));
        Assert.assertEquals(StartDecision.REJECT_INVALID_KEEP_RUNNING, arbiter.onStart(4, false));
        Assert.assertEquals(4, arbiter.onFinished(1));
        Assert.assertFalse(arbiter.isActive());
    }

    /**
     * The review's core scenario, scanned programmatically: start B (valid or invalid) arriving
     * either BEFORE or AFTER A's completion. In every combination, the stop id A uses must not be
     * the id of an operation that was ACCEPTED and is still unfinished.
     */
    @Test
    public void noInterleavingLetsAFinisherStopAnAcceptedUnfinishedDownload() {
        for (boolean bValid : new boolean[]{true, false}) {
            for (boolean bBeforeAFinishes : new boolean[]{true, false}) {
                final ApkUpdateStartArbiter arbiter = new ApkUpdateStartArbiter();
                Assert.assertEquals(StartDecision.ACCEPT, arbiter.onStart(1, true));

                StartDecision bDecision = null;
                if (bBeforeAFinishes) {
                    bDecision = arbiter.onStart(2, bValid);
                }
                final int aStopId = arbiter.onFinished(1);
                if (!bBeforeAFinishes) {
                    bDecision = arbiter.onStart(2, bValid);
                }

                final String scenario = "bValid=" + bValid + " bBeforeAFinishes=" + bBeforeAFinishes;
                if (bBeforeAFinishes) {
                    // B could not be accepted while A was active — stopping with B's id is safe.
                    Assert.assertNotEquals(scenario, StartDecision.ACCEPT, bDecision);
                    Assert.assertEquals(scenario, 2, aStopId);
                } else {
                    // A's stop decision predates B entirely and uses A's own id; if B is later
                    // accepted, Android's stopSelfResult(1) returns false and B proceeds.
                    Assert.assertEquals(scenario, 1, aStopId);
                    Assert.assertEquals(scenario,
                        bValid ? StartDecision.ACCEPT : StartDecision.REJECT_INVALID_STOP_SELF,
                        bDecision);
                }
            }
        }
    }

    // ---- S2-01: foreground ownership is part of the arbitration decision ----

    @Test
    public void everyDecisionCarriesItsForegroundAction() {
        Assert.assertEquals(NotificationAction.PROMOTE_FRESH, StartDecision.ACCEPT.notificationAction);
        Assert.assertEquals(NotificationAction.PROMOTE_CURRENT, StartDecision.REJECT_DUPLICATE.notificationAction);
        Assert.assertEquals(NotificationAction.PROMOTE_CURRENT, StartDecision.REJECT_INVALID_KEEP_RUNNING.notificationAction);
        Assert.assertEquals(NotificationAction.PROMOTE_THEN_STOP, StartDecision.REJECT_INVALID_STOP_SELF.notificationAction);
    }

    /**
     * S2-01 required test: duplicate and invalid starts while A is active must not mutate A's
     * notification — their action must be PROMOTE_CURRENT (re-post the running download's
     * progress), never a fresh reset and never a teardown.
     */
    @Test
    public void rejectedStartsWhileActiveNeverResetOrTearDownTheActiveNotification() {
        final ApkUpdateStartArbiter arbiter = new ApkUpdateStartArbiter();
        Assert.assertEquals(StartDecision.ACCEPT, arbiter.onStart(1, true));
        for (int i = 2; i <= 6; i++) {
            final StartDecision decision = arbiter.onStart(i, i % 2 == 0);
            Assert.assertEquals("start " + i + " while active",
                NotificationAction.PROMOTE_CURRENT, decision.notificationAction);
            Assert.assertTrue(arbiter.isActive());
        }
    }

    /**
     * S2-01 core scenario as a decision/action sequence: B arrives before, at, and after A's
     * terminal step. In every timing, (a) A's finisher never sees a stop id owned by an accepted
     * unfinished operation, and (b) any B accepted after A's finish carries PROMOTE_FRESH — i.e.
     * an accepted operation always (re-)promotes itself under the lock, so a demotion by A's
     * teardown can never leave an accepted B in the background. The finisher itself cannot demote
     * at all: {@code onFinished} returns only a stop id (no action type exists for demotion).
     */
    @Test
    public void acceptedStartAfterAnyFinishTimingAlwaysRepromotes() {
        for (boolean bBeforeAFinishes : new boolean[]{true, false}) {
            final ApkUpdateStartArbiter arbiter = new ApkUpdateStartArbiter();
            Assert.assertEquals(StartDecision.ACCEPT, arbiter.onStart(1, true));

            if (bBeforeAFinishes) {
                // Delivered while A is active: rejected, re-posts current progress. When A then
                // stops with B's id, the service dies and the SYSTEM removes the notification —
                // no explicit demote is ever issued for an operation that could still be running.
                Assert.assertEquals(NotificationAction.PROMOTE_CURRENT,
                    arbiter.onStart(2, true).notificationAction);
                Assert.assertEquals(2, arbiter.onFinished(1));
            } else {
                Assert.assertEquals(1, arbiter.onFinished(1));
                // Delivered after A's decision: stopSelfResult(1) returns false (newer start
                // delivered), the service survives WITHOUT a foreground state — and B's own
                // locked decision re-promotes it. This is the exact S2-01 gap: acceptance and
                // promotion are one transition now.
                final StartDecision bDecision = arbiter.onStart(2, true);
                Assert.assertEquals(StartDecision.ACCEPT, bDecision);
                Assert.assertEquals(NotificationAction.PROMOTE_FRESH, bDecision.notificationAction);
                Assert.assertTrue(arbiter.isActive());
                Assert.assertEquals(2, arbiter.onFinished(2));
            }
        }
    }

    /**
     * A three-operation stress of the same invariants: A active, B duplicate, A finishes (stops
     * with B's id — service dies, notification removed by the system), C starts a fresh service
     * generation and must again be ACCEPT+PROMOTE_FRESH.
     */
    @Test
    public void freshGenerationAfterCoveredDuplicateStopPromotesAgain() {
        final ApkUpdateStartArbiter arbiter = new ApkUpdateStartArbiter();
        Assert.assertEquals(StartDecision.ACCEPT, arbiter.onStart(1, true));
        Assert.assertEquals(StartDecision.REJECT_DUPLICATE, arbiter.onStart(2, true));
        Assert.assertEquals(2, arbiter.onFinished(1));
        Assert.assertFalse(arbiter.isActive());

        final StartDecision cDecision = arbiter.onStart(3, true);
        Assert.assertEquals(StartDecision.ACCEPT, cDecision);
        Assert.assertEquals(NotificationAction.PROMOTE_FRESH, cDecision.notificationAction);
    }
}
