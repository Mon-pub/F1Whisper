package ch.threema.app.services

import android.os.SystemClock
import androidx.annotation.AnyThread
import androidx.annotation.VisibleForTesting
import ch.threema.app.preference.service.PreferenceService
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.protocol.api.ServerTimeReporter
import java.util.Date
import org.koin.mp.KoinPlatform

private val logger = getThreemaLogger("TrustedClock")

/**
 * F1Whisper: a server-corrected clock for OUTGOING message timestamps.
 *
 * Why: the chat timeline mixes two clock domains: my phone stamps my outgoing messages, the peer's phone stamps
 * theirs. A phone with a wrong clock (auto-time off, drifted, manually misset) poisons every message it stamps,
 * scattering them across the timeline on the other device. Ordering only needs a *common* reference, not an
 * absolute-true one, so we follow OUR server's UTC. The app cannot set the system clock (no permission).
 *
 * Scope: **message display/sort timestamps ONLY.** Never use this for security- or correctness-time-sensitive logic
 * (Forward Security windows, TLS/token/TURN expiry, AlarmManager, disappearing-message expiry, F1Push revive), those
 * stay on the real system clock.
 *
 * Samples come from two trusted sources only:
 * - the HTTP `Date:` header of the cert-pinned OnPrem API client ([ServerTimeReporter], never the unpinned
 *   link-preview fetcher, so no clock poisoning). A sample more than [MAX_JUMP_MS] away from the current time is held
 *   as a candidate and adopted when a second one agrees with it; it is no longer rejected forever.
 * - the mediator's `ServerInfo.currentTime` at every multi-device login, accepted immediately.
 *
 * Once a sample is accepted in this process, time runs on the monotonic elapsed clock from that sample, so a manual
 * clock change while the app runs no longer moves our timestamps. The persisted offset is only the fallback between
 * a cold start and the first sample.
 */
object TrustedClock {

    /** Where accepted samples come from. */
    enum class Source { HTTP, MEDIATOR }

    /** Upper bound on how long ago a reported receive time may be (the report happens right after receipt). */
    private const val MAX_RECEIVE_DELAY_MS = 60_000L

    @Volatile
    private var core: TrustedClockCore = TrustedClockCore(
        wallClock = System::currentTimeMillis,
        elapsedClock = SystemClock::elapsedRealtime,
        store = ::preferenceStoreOrNull,
    )

    /**
     * Register this clock as the consumer of HTTP server-time samples. Call once at app startup. Idempotent.
     */
    @JvmStatic
    fun register() {
        ServerTimeReporter.sink = { serverMs, systemReceiveMs ->
            onServerTimeSample(serverMs, systemReceiveMs)
        }
    }

    /** Fed by [ServerTimeReporter] for every successful response from our pinned OnPrem server. */
    @AnyThread
    @JvmStatic
    fun onServerTimeSample(serverMs: Long, systemReceiveMs: Long) {
        // Convert the receive time to the elapsed domain right away, before any locking or preference work, so a
        // device clock change after this point cannot turn into a fake receive delay.
        val receiveDelayMs = (System.currentTimeMillis() - systemReceiveMs).coerceIn(0L, MAX_RECEIVE_DELAY_MS)
        core.onSample(serverMs, SystemClock.elapsedRealtime() - receiveDelayMs, Source.HTTP)
    }

    /** Fed by the multi-device manager with the mediator's `ServerInfo.currentTime` (milliseconds). */
    @AnyThread
    @JvmStatic
    fun onMediatorTimeSample(serverMs: Long) {
        core.onSample(serverMs, SystemClock.elapsedRealtime(), Source.MEDIATOR)
    }

    /** Corrected wall-clock millis for display/sort. */
    @AnyThread
    @JvmStatic
    fun nowMillis(): Long = core.nowMillis()

    /** Corrected wall-clock [Date] for display/sort. */
    @AnyThread
    @JvmStatic
    fun now(): Date = Date(nowMillis())

    /**
     * The corrected time, but only when a server sample was accepted in THIS process (so it runs on the elapsed clock
     * and is independent of the device clock). Null otherwise: an old persisted offset alone is not trusted enough
     * to override what a sender claims.
     */
    @AnyThread
    @JvmStatic
    fun trustedNowMillisOrNull(): Long? = core.trustedNowMillisOrNull()

    /**
     * Monotonic stamp for OUTGOING message creation: never goes backwards within a process, except right after an
     * accepted server correction pulled the clock back by more than a few seconds. Use for `createdAt`.
     */
    @JvmStatic
    fun stampNowMillis(): Long = core.stampNowMillis()

    /** Monotonic outgoing-creation stamp as a [Date]. */
    @JvmStatic
    fun stampNow(): Date = Date(stampNowMillis())

    @VisibleForTesting
    internal fun replaceCoreForTesting(replacement: TrustedClockCore) {
        core = replacement
    }

    /**
     * The singleton preference service, NOT the session-scoped service manager: samples may arrive before unlock,
     * and the offset preferences are plain (unencrypted) preferences.
     */
    private fun preferenceStoreOrNull(): TrustedClockStore? {
        val preferenceService = try {
            KoinPlatform.getKoinOrNull()?.getOrNull<PreferenceService>()
        } catch (e: Exception) {
            logger.debug("PreferenceService not available yet", e)
            null
        } ?: return null
        return object : TrustedClockStore {
            override fun loadOffsetMs(): Long? =
                if (preferenceService.hasTrustedClockSynced()) preferenceService.getTrustedClockOffsetMs() else null

            override fun saveOffsetMs(offsetMs: Long) {
                preferenceService.setTrustedClockOffsetMs(offsetMs)
            }
        }
    }
}

/** Persistence of the wall-clock offset (`serverTime - deviceTime`) used between a cold start and the first sample. */
interface TrustedClockStore {
    /** The persisted offset, or null when this device never synced. A zero offset counts as synced. */
    fun loadOffsetMs(): Long?

    fun saveOffsetMs(offsetMs: Long)
}

/**
 * The clock logic behind [TrustedClock], with injected time sources and store so it is unit-testable.
 *
 * Two offset domains:
 * - wall offset = `serverTime - System.currentTimeMillis()`: persisted, used only before the first sample of a process.
 * - elapsed offset = `serverTime - elapsedRealtime`: used after the first accepted sample. The elapsed clock is not
 *   affected by manual clock changes, so `elapsedRealtime + elapsedOffset` keeps following server time.
 */
class TrustedClockCore(
    private val wallClock: () -> Long,
    private val elapsedClock: () -> Long,
    private val store: () -> TrustedClockStore?,
) {
    companion object {
        /** Keep the last N accepted offsets and take their median to resist jitter. */
        const val SAMPLE_WINDOW = 5

        /** An HTTP sample further than this from the current time is a candidate, not accepted on its own. */
        const val MAX_JUMP_MS = 24L * 60 * 60 * 1000

        /** Two candidates confirm each other when their offsets agree within this. */
        const val CANDIDATE_AGREEMENT_MS = 60_000L

        /**
         * A held candidate expires after this long (elapsed time). DECISION: 24 h, not minutes. A single-device phone
         * may make an API call only every few hours, and the agreement window above already rules out pairing two
         * unrelated readings (the pinned API client has no HTTP cache, so there are no replayed stale responses).
         */
        const val CANDIDATE_TTL_MS = 24L * 60 * 60 * 1000

        /** After an accepted correction, a previous stamp further ahead than this releases the monotonic latch. */
        const val LATCH_RELEASE_MS = 5_000L

        /** Upper bound on how far before the processing time a sample's receive time may be. */
        private const val MAX_RECEIVE_AGE_MS = 60_000L
    }

    private class Candidate(val elapsedOffsetMs: Long, val heldAtElapsedMs: Long)

    private val lock = Any()

    /** Accepted elapsed-domain offsets of this process, most recent last. */
    private val samples = ArrayDeque<Long>(SAMPLE_WINDOW)

    /** Median of [samples]; null until a sample is accepted in this process. */
    @Volatile
    private var elapsedOffsetMs: Long? = null

    /** Persisted wall offset (startup fallback); null when never synced. */
    @Volatile
    private var persistedWallOffsetMs: Long? = null

    @Volatile
    private var persistedLoaded = false

    /** An accepted offset that could not be written yet because the store was unavailable. */
    private var pendingPersistWallOffsetMs: Long? = null

    private var candidate: Candidate? = null

    /** Monotonic guard for [stampNowMillis]. */
    private var lastStampMs = 0L

    /**
     * One server-time sample. [receiveElapsedMs] is the elapsed clock when the server time was received; it is
     * clamped to at most [MAX_RECEIVE_AGE_MS] before processing.
     */
    fun onSample(serverMs: Long, receiveElapsedMs: Long, source: TrustedClock.Source) {
        if (serverMs <= 0L) {
            logger.info("Ignoring invalid {} server time {}", source, serverMs)
            return
        }
        synchronized(lock) {
            ensurePersistedLoadedLocked()
            flushPendingPersistLocked()

            val elapsedNow = elapsedClock()
            val wallNow = wallClock()
            val sampleElapsedOffset = serverMs - receiveElapsedMs.coerceIn(elapsedNow - MAX_RECEIVE_AGE_MS, elapsedNow)
            val currentElapsedOffset = currentElapsedOffsetLocked(wallNow, elapsedNow)

            when {
                source == TrustedClock.Source.MEDIATOR -> {
                    adoptLocked(sampleElapsedOffset, wallNow, elapsedNow, "mediator sample")
                }
                currentElapsedOffset == null -> {
                    adoptLocked(sampleElapsedOffset, wallNow, elapsedNow, "first sync")
                }
                kotlin.math.abs(sampleElapsedOffset - currentElapsedOffset) <= MAX_JUMP_MS -> {
                    candidate = null
                    samples.addLast(sampleElapsedOffset)
                    while (samples.size > SAMPLE_WINDOW) {
                        samples.removeFirst()
                    }
                    val wasAnchored = elapsedOffsetMs != null
                    elapsedOffsetMs = median(samples)
                    logger.debug("Accepted {} server-time sample", source)
                    onAcceptedLocked(wallNow, elapsedNow, logAsCorrection = !wasAnchored)
                }
                else -> {
                    val held = candidate
                    if (held != null &&
                        elapsedNow - held.heldAtElapsedMs <= CANDIDATE_TTL_MS &&
                        kotlin.math.abs(sampleElapsedOffset - held.elapsedOffsetMs) <= CANDIDATE_AGREEMENT_MS
                    ) {
                        adoptLocked(sampleElapsedOffset, wallNow, elapsedNow, "confirmed clock jump")
                    } else {
                        candidate = Candidate(sampleElapsedOffset, elapsedNow)
                        logger.info(
                            "Holding {} server-time candidate: {} ms away from current time, waiting for a second sample",
                            source,
                            sampleElapsedOffset - currentElapsedOffset,
                        )
                    }
                }
            }
        }
    }

    fun nowMillis(): Long {
        elapsedOffsetMs?.let { return elapsedClock() + it }
        // Not anchored yet: decide under the lock, so a sample accepted concurrently is never missed in favour of the
        // (device-clock dependent) fallback. Only until the first sample of the process.
        synchronized(lock) {
            elapsedOffsetMs?.let { return elapsedClock() + it }
            ensurePersistedLoadedLocked()
            return wallClock() + (persistedWallOffsetMs ?: 0L)
        }
    }

    fun trustedNowMillisOrNull(): Long? = elapsedOffsetMs?.let { elapsedClock() + it }

    fun stampNowMillis(): Long = synchronized(lock) {
        flushPendingPersistLocked()
        val t = maxOf(nowMillis(), lastStampMs + 1)
        lastStampMs = t
        t
    }

    /** The offset currently in force, in the elapsed domain; null when this device has never synced. */
    private fun currentElapsedOffsetLocked(wallNow: Long, elapsedNow: Long): Long? {
        elapsedOffsetMs?.let { return it }
        val persisted = persistedWallOffsetMs ?: return null
        // now = wallNow + wallOffset = elapsedNow + elapsedOffset
        return wallNow + persisted - elapsedNow
    }

    /** Start over from one sample: a confirmed jump, an authoritative mediator sample, or the first sync. */
    private fun adoptLocked(sampleElapsedOffset: Long, wallNow: Long, elapsedNow: Long, reason: String) {
        candidate = null
        samples.clear()
        samples.addLast(sampleElapsedOffset)
        elapsedOffsetMs = sampleElapsedOffset
        logger.info("Trusted clock adopted ({}): device clock off by {} ms", reason, sampleElapsedOffset + elapsedNow - wallNow)
        onAcceptedLocked(wallNow, elapsedNow, logAsCorrection = false)
    }

    private fun onAcceptedLocked(wallNow: Long, elapsedNow: Long, logAsCorrection: Boolean) {
        val corrected = elapsedNow + (elapsedOffsetMs ?: return)
        val wallOffset = corrected - wallNow
        if (logAsCorrection) {
            logger.info("Trusted clock synced this session: device clock off by {} ms", wallOffset)
        }
        persistLocked(wallOffset)
        if (lastStampMs > corrected + LATCH_RELEASE_MS) {
            logger.info("Releasing outgoing stamp latch: last stamp was {} ms ahead of corrected time", lastStampMs - corrected)
            lastStampMs = 0L
        }
    }

    private fun persistLocked(wallOffset: Long) {
        // From now on memory is newer than anything a late store load could return.
        persistedWallOffsetMs = wallOffset
        persistedLoaded = true
        val target = store()
        if (target == null) {
            if (pendingPersistWallOffsetMs == null) {
                logger.info("Trusted clock offset not persisted yet: preferences unavailable")
            }
            pendingPersistWallOffsetMs = wallOffset
            return
        }
        try {
            target.saveOffsetMs(wallOffset)
            pendingPersistWallOffsetMs = null
        } catch (e: Exception) {
            logger.info("Failed to persist trusted clock offset", e)
            pendingPersistWallOffsetMs = wallOffset
        }
    }

    private fun flushPendingPersistLocked() {
        val pending = pendingPersistWallOffsetMs ?: return
        val target = store() ?: return
        try {
            target.saveOffsetMs(pending)
            pendingPersistWallOffsetMs = null
        } catch (e: Exception) {
            logger.debug("Retrying trusted clock persist failed", e)
        }
    }

    private fun ensurePersistedLoadedLocked() {
        if (persistedLoaded) {
            return
        }
        val source = store() ?: return // not ready yet (very early startup), retry on the next access
        persistedWallOffsetMs = try {
            source.loadOffsetMs()
        } catch (e: Exception) {
            logger.debug("Failed to load trusted clock offset", e)
            return
        }
        persistedLoaded = true
        logger.debug("Loaded persisted trusted-clock offset: {}", persistedWallOffsetMs)
    }

    private fun median(values: Collection<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
