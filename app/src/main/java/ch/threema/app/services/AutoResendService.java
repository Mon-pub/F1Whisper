package ch.threema.app.services;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ch.threema.base.utils.LoggingKt;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.storage.models.AbstractMessageModel;

/**
 * F1Whisper auto-resend: finds unsent outgoing messages that failed for a transient connectivity
 * reason (or were left in flight by a process death) and silently re-sends them once the connection
 * has returned - Signal/Telegram parity, no user nagging. The message keeps its original
 * apiMessageId so the receiver dedupes a redelivery (see
 * {@link MessageService#autoResendMessage}).
 *
 * <p>Triggering is entirely event-driven (no polling): call {@link #scheduleScan} on every CSP
 * {@code LOGGEDIN} transition and once at app start. The scan is debounced ({@link #DEBOUNCE_MS})
 * so a burst of reconnect events collapses to a single run, and at most one scan runs at a time
 * ({@link #scanInFlight}); a request that arrives while a scan is running re-arms one follow-up run.
 *
 * <p>Eligibility mirrors {@code AbstractMessageModelFactory.AUTO_RESEND_WHERE}: outgoing,
 * non-deleted, not marked terminal, in an unsent state (PENDING/UPLOADING/TRANSCODING/SENDING or a
 * connectivity-class SENDFAILED), and younger than {@link #MAX_AGE_MS}. Candidates are re-sent
 * oldest compose-time first.
 */
public class AutoResendService {
    private static final Logger logger = LoggingKt.getThreemaLogger("AutoResendService");

    /**
     * Debounce window: reconnect events (and the app-start trigger) that arrive within this window
     * collapse into a single scan. Long enough to let a freshly-established connection settle
     * (~3s after auth), short enough that resends feel immediate.
     */
    @VisibleForTesting
    static final long DEBOUNCE_MS = 3_000L;

    /**
     * Signal-parity message lifespan: a message that has been unsent for longer than this is no
     * longer auto-resent (the reconnect scan ignores it and, once past this age, the nag fires).
     */
    @VisibleForTesting
    static final long MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    /**
     * Abstraction over the two message tables so the sequencing logic is unit-testable without a
     * real database.
     */
    public interface CandidateSource {
        /**
         * Outgoing candidates younger than {@code minCreatedAtMillis} that are eligible for
         * auto-resend, in no particular cross-table order.
         */
        @WorkerThread
        @NonNull
        List<AbstractMessageModel> findAutoResendCandidates(long minCreatedAtMillis);

        /**
         * Outgoing messages still unsent but at or before {@code maxCreatedAtMillis} (past the 24h
         * window) - given up on and handed to the nag path.
         */
        @WorkerThread
        @NonNull
        List<AbstractMessageModel> findAgedOutUnsentMessages(long maxCreatedAtMillis);
    }

    /**
     * Abstraction over the actual per-message resend so tests can capture the resent messages
     * without touching the send pipeline. Implementations must reuse the original apiMessageId.
     */
    public interface Resender {
        @WorkerThread
        void autoResend(@NonNull AbstractMessageModel messageModel) throws Exception;

        /**
         * Mark a message that has exhausted the 24h auto-resend window as terminally SENDFAILED so
         * the user is finally nagged once. No-op if the message is no longer eligible.
         */
        @WorkerThread
        void markAgedOutFailed(@NonNull AbstractMessageModel messageModel);
    }

    private final CandidateSource candidateSource;
    private final Resender resender;
    private final ScheduledExecutorService scheduler;

    /** True while a scan is executing; a request during a scan re-arms exactly one follow-up. */
    private final AtomicBoolean scanInFlight = new AtomicBoolean(false);
    /** Set when a scan is requested while one is in flight; consumed at the end of the scan. */
    private final AtomicBoolean rescanRequested = new AtomicBoolean(false);
    /** The currently-armed debounce task, cancelled and replaced by a newer request. */
    private ScheduledFuture<?> pendingScan;
    private final Object scheduleLock = new Object();

    public AutoResendService(
        @NonNull CandidateSource candidateSource,
        @NonNull Resender resender,
        @NonNull ScheduledExecutorService scheduler
    ) {
        this.candidateSource = candidateSource;
        this.resender = resender;
        this.scheduler = scheduler;
    }

    /**
     * Request an auto-resend scan. Debounced: a burst of calls within {@link #DEBOUNCE_MS} runs the
     * scan once. Safe to call from any thread and as often as desired (every LOGGEDIN transition,
     * app start). Never blocks the caller.
     */
    @AnyThread
    public void scheduleScan(@NonNull String reason) {
        synchronized (scheduleLock) {
            if (pendingScan != null && !pendingScan.isDone()) {
                pendingScan.cancel(false);
            }
            try {
                pendingScan = scheduler.schedule(
                    () -> runScanGuarded(reason),
                    DEBOUNCE_MS,
                    TimeUnit.MILLISECONDS
                );
                logger.debug("Auto-resend scan scheduled ({}), debounce {} ms", reason, DEBOUNCE_MS);
            } catch (Exception e) {
                // e.g. executor shut down during teardown; nothing to do.
                logger.debug("Could not schedule auto-resend scan ({})", reason, e);
            }
        }
    }

    /**
     * Ensure only one scan runs at a time. If a scan is requested while one is running, arm exactly
     * one follow-up run so a message that became eligible mid-scan is still picked up.
     */
    @WorkerThread
    private void runScanGuarded(@NonNull String reason) {
        if (!scanInFlight.compareAndSet(false, true)) {
            // A scan is already running; remember to run once more when it finishes.
            rescanRequested.set(true);
            logger.debug("Auto-resend scan already in flight; re-arming follow-up ({})", reason);
            return;
        }
        try {
            runScan(reason);
        } catch (Exception e) {
            logger.error("Auto-resend scan failed", e);
        } finally {
            scanInFlight.set(false);
            if (rescanRequested.compareAndSet(true, false)) {
                logger.debug("Running re-armed follow-up auto-resend scan");
                scheduleScan("follow-up");
            }
        }
    }

    /**
     * Collect eligible candidates from all tables, order them oldest-first, and resend each. Public
     * for testing (bypasses the debounce/guard).
     */
    @VisibleForTesting
    @WorkerThread
    void runScan(@NonNull String reason) {
        final long now = nowMillis();
        final long minCreatedAtMillis = now - MAX_AGE_MS;

        // First, give up on anything that has now exhausted the 24h window and nag the user once.
        final List<AbstractMessageModel> agedOut = candidateSource.findAgedOutUnsentMessages(minCreatedAtMillis);
        if (!agedOut.isEmpty()) {
            logger.info("Auto-resend scan ({}): {} message(s) exhausted the 24h window; nagging", reason, agedOut.size());
            for (AbstractMessageModel messageModel : agedOut) {
                try {
                    resender.markAgedOutFailed(messageModel);
                } catch (Exception e) {
                    logger.warn("Failed to mark aged-out message {} as failed", messageModel.getUid(), e);
                }
            }
        }

        final List<AbstractMessageModel> candidates =
            new ArrayList<>(candidateSource.findAutoResendCandidates(minCreatedAtMillis));

        if (candidates.isEmpty()) {
            logger.debug("Auto-resend scan ({}): no candidates", reason);
            return;
        }

        // Oldest compose-time first so a batch preserves the original send order; a null createdAt
        // is treated as oldest so it is not starved.
        candidates.sort(Comparator.comparingLong(AutoResendService::createdAtMillisOrMin));

        logger.info("Auto-resend scan ({}): re-sending {} message(s)", reason, candidates.size());
        for (AbstractMessageModel messageModel : candidates) {
            try {
                resender.autoResend(messageModel);
            } catch (Exception e) {
                // One message failing must not abort the batch; it stays eligible for the next scan.
                logger.warn("Auto-resend failed for message {}; will retry on next scan", messageModel.getUid(), e);
            }
        }
    }

    private static long createdAtMillisOrMin(@NonNull AbstractMessageModel messageModel) {
        final Date createdAt = messageModel.getCreatedAt();
        return createdAt != null ? createdAt.getTime() : Long.MIN_VALUE;
    }

    @VisibleForTesting
    long nowMillis() {
        return System.currentTimeMillis();
    }

    /** The trigger source used for every auto-resend (a local, non-user action). */
    public static final TriggerSource TRIGGER_SOURCE = TriggerSource.LOCAL;

    /**
     * F1Whisper auto-resend policy: whether {@code messageModel} may be silently auto-resent. This
     * is the SINGLE source of truth for auto-resend eligibility (the SQL scan query mirrors it), and
     * the critical guard against double-sending:
     *
     *  - must be an outgoing, non-deleted FILE message - FILE is the only type whose send has a
     *    separate, process-death-fragile blob-upload phase (the exponential-backoff pipeline);
     *    text/location/ballot go straight to the persistent task queue which already survives
     *    process death and auto-retries connectivity, so resending them would double-send;
     *  - must be in an unsent blob-phase state (PENDING or UPLOADING, or a connectivity-class
     *    SENDFAILED). {@link ch.threema.storage.models.MessageState#SENDING} is EXCLUDED: once
     *    SENDING the persistent {@code OutgoingCspMessageTask} owns delivery.
     *    {@link ch.threema.storage.models.MessageState#TRANSCODING} is EXCLUDED too: a half-
     *    transcoded video has no uploadable blob to resume, matching upstream's own
     *    {@code markUnscheduledFileMessagesAsFailed} which only recovers PENDING/UPLOADING;
     *  - must NOT be marked terminal ({@code DISPLAY_TAG_SEND_FAILED_TERMINAL}).
     *
     * Age (24h) and the distribution-list / receiver checks are applied by the caller.
     */
    public static boolean isAutoResendEligible(@NonNull AbstractMessageModel messageModel) {
        if (messageModel.isDeleted()) {
            return false;
        }
        if (!messageModel.isOutbox()) {
            return false;
        }
        if (messageModel.getType() != ch.threema.storage.models.MessageType.FILE) {
            return false;
        }
        final ch.threema.storage.models.MessageState state = messageModel.getState();
        if (state == ch.threema.storage.models.MessageState.SENDFAILED) {
            return !messageModel.isSendFailedTerminal();
        }
        return state == ch.threema.storage.models.MessageState.PENDING
            || state == ch.threema.storage.models.MessageState.UPLOADING;
    }
}
