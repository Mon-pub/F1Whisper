package ch.threema.app.services;

/**
 * F1Whisper (follow-up review P0-7, second follow-up S2-01): the start/stop lifecycle decision
 * logic of the self-update download service, extracted as a pure, unit-tested state machine.
 *
 * <p>The original defect (P0-7): the service kept one global "latest start id" and a separate
 * "download active" boolean that was cleared BEFORE terminal handling. A finishing download A
 * could then observe a newer start B (already accepted into the executor) and call
 * {@code stopSelfResult(B)} — destroying the service and cancelling B mid-download. An invalid
 * start while a download ran was even worse: its error path stopped the service outright with its
 * own (most recent) start id.</p>
 *
 * <p>The residual defect this revision closes (S2-01): the service promoted itself to foreground
 * at {@code onStartCommand} entry, BEFORE arbitration, while the finisher demoted the whole
 * service ({@code stopForeground}) under the lock. A start B that promoted pre-lock could be
 * demoted by a concurrently finishing A and — once accepted — never promoted again, leaving an
 * active download in a background service Android may kill with no {@code START_NOT_STICKY}
 * recovery. The fix makes foreground handling PART of the arbitration decision:</p>
 *
 * <ul>
 *   <li>Every decision carries a {@link NotificationAction}. The service executes BOTH under one
 *       shared monitor, so no promotion or teardown can interleave with another operation's.</li>
 *   <li>{@link NotificationAction#PROMOTE_FRESH}: this start owns the download now — promote with
 *       a fresh progress notification.</li>
 *   <li>{@link NotificationAction#PROMOTE_CURRENT}: a download is running — re-promote with the
 *       CURRENT progress (every {@code startForegroundService} delivery must be answered by a
 *       {@code startForeground} call, but a rejected start must never reset the active
 *       operation's notification).</li>
 *   <li>{@link NotificationAction#PROMOTE_THEN_STOP}: invalid request while idle — promote to
 *       satisfy the contract, post the failure result, demote, and stop with THIS start id.</li>
 *   <li>The finisher NEVER demotes: there is deliberately no demote action in this API. It calls
 *       {@link #onFinished} and hands the returned id to {@code stopSelfResult}. If that stops the
 *       service, the system removes the foreground notification with it; if a newer start was
 *       delivered, {@code stopSelfResult} returns false and that start's own locked decision
 *       governs the notification.</li>
 * </ul>
 *
 * <p>The stop-id model is unchanged: at most one ACTIVE operation, identified by its own start
 * id. Every start is recorded as latest-seen; acceptance requires idleness. {@link #onFinished}
 * clears the active operation and returns the latest-seen id. That is always safe because a later
 * start can only have been ACCEPTED after {@code onFinished} ran (acceptance requires idle, and
 * only {@code onFinished} establishes idle) — so at decision time the latest-seen id belongs
 * either to the finishing operation itself or to a REJECTED start, both of which the service may
 * stop for. If an even newer start races in after the decision, {@code stopSelfResult} returns
 * false by the Android contract and that start proceeds.</p>
 *
 * <p>The service must run {@link #onStart} plus its notification action, and the
 * {@link #onFinished}-plus-stop sequence, each under one shared monitor; terminal handling
 * (result notification, ready-state persistence) must complete BEFORE {@link #onFinished} so
 * idleness is never exposed early. Real Android service-lifecycle coverage (API 26/31/33/35)
 * remains a device-matrix item; this class makes every decision interleaving JVM-testable.</p>
 */
public final class ApkUpdateStartArbiter {

    /** What the service must do with the foreground notification, under the same lock. */
    public enum NotificationAction {
        /** Promote with a fresh (0%, indeterminate) progress notification. */
        PROMOTE_FRESH,
        /** Re-promote with the LAST KNOWN progress — never reset the active operation's bar. */
        PROMOTE_CURRENT,
        /** Promote (contract), post the failure result, demote, and stop with this start id. */
        PROMOTE_THEN_STOP,
    }

    public enum StartDecision {
        /** Idle and the request is valid: the caller owns the download now. */
        ACCEPT(NotificationAction.PROMOTE_FRESH),
        /** A download is running: ignore this start entirely (never stop anything). */
        REJECT_DUPLICATE(NotificationAction.PROMOTE_CURRENT),
        /** Invalid request while idle: the caller should post failure and stop with THIS start id. */
        REJECT_INVALID_STOP_SELF(NotificationAction.PROMOTE_THEN_STOP),
        /** Invalid request while a download runs: log only — never touch the running download. */
        REJECT_INVALID_KEEP_RUNNING(NotificationAction.PROMOTE_CURRENT);

        /** The foreground-notification transition that MUST accompany this decision. */
        public final NotificationAction notificationAction;

        StartDecision(NotificationAction notificationAction) {
            this.notificationAction = notificationAction;
        }
    }

    public static final int NO_ACTIVE = -1;

    private int activeStartId = NO_ACTIVE;
    private int latestStartId = NO_ACTIVE;

    public synchronized StartDecision onStart(int startId, boolean requestValid) {
        latestStartId = startId;
        if (!requestValid) {
            return activeStartId == NO_ACTIVE
                ? StartDecision.REJECT_INVALID_STOP_SELF
                : StartDecision.REJECT_INVALID_KEEP_RUNNING;
        }
        if (activeStartId != NO_ACTIVE) {
            return StartDecision.REJECT_DUPLICATE;
        }
        activeStartId = startId;
        return StartDecision.ACCEPT;
    }

    /**
     * The operation owning {@code myStartId} has completed ALL terminal handling. Clears the
     * active state (only if actually owned by {@code myStartId}) and returns the start id to pass
     * to {@code stopSelfResult}. The caller must NOT demote the foreground state: service
     * destruction removes the notification, and a surviving newer start re-promotes itself.
     */
    public synchronized int onFinished(int myStartId) {
        if (activeStartId == myStartId) {
            activeStartId = NO_ACTIVE;
        }
        return latestStartId;
    }

    public synchronized boolean isActive() {
        return activeStartId != NO_ACTIVE;
    }
}
