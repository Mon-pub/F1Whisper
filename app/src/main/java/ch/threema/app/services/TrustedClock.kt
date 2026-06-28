package ch.threema.app.services

import androidx.annotation.AnyThread
import ch.threema.app.ThreemaApplication
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.protocol.api.ServerTimeReporter
import java.util.Date

private val logger = getThreemaLogger("TrustedClock")

/**
 * F1Whisper: a server-corrected clock for OUTGOING message timestamps.
 *
 * Why: the chat timeline mixes two clock domains — my phone stamps my outgoing messages, the peer's
 * phone stamps theirs. A phone with a wrong clock (auto-time off, drifted, manually misset) poisons
 * every message it stamps, scattering them across the timeline on the other device. Ordering only
 * needs a *common* reference, not an absolute-true one, so we derive an offset from OUR server's
 * UTC (the HTTP `Date:` response header — see [ServerTimeReporter]) and stamp from
 * `deviceNow + offset`. The app cannot set the system clock (no permission), so we keep an offset.
 *
 * Scope: **message display/sort timestamps ONLY.** Never use this for security- or
 * correctness-time-sensitive logic (Forward Security windows, TLS/token/TURN expiry, AlarmManager,
 * disappearing-message expiry, F1Push revive) — those stay on the real system clock.
 *
 * Source of samples: registered as the [ServerTimeReporter.sink] at app startup; fed ONLY by the
 * cert-pinned OnPrem [ch.threema.domain.protocol.api.HttpRequester] (never the unpinned
 * link-preview fetcher → no clock-poisoning).
 */
object TrustedClock {
    /** Keep the last N raw samples and take their median to resist jitter/outliers. */
    private const val SAMPLE_WINDOW = 5

    /**
     * Sanity clamp: a single sample whose implied offset jumps more than this far from the current
     * smoothed offset is ignored (likely a cached/proxied response). A genuine clock change is
     * still tracked because the median window converges across several samples; the clamp only
     * blocks one bad sample from yanking the clock.
     */
    private const val MAX_JUMP_MS = 24L * 60 * 60 * 1000 // 24h

    private val lock = Any()

    /** Recent raw offset samples (`serverMs - systemReceiveMs`), most-recent appended. */
    private val samples = ArrayDeque<Long>(SAMPLE_WINDOW)

    /** Smoothed offset in ms. Loaded lazily from prefs on first access; 0 before first-ever sync. */
    @Volatile
    private var offsetMs: Long = 0L

    @Volatile
    private var offsetLoaded: Boolean = false

    /** Monotonic guard for [stampNowMillis]: never emit a value <= the previous one this process. */
    private var lastStampMs: Long = 0L

    /**
     * Register this clock as the consumer of server-time samples. Call once at app startup.
     * Idempotent.
     */
    @JvmStatic
    fun register() {
        ServerTimeReporter.sink = { serverMs, systemReceiveMs ->
            onServerTimeSample(serverMs, systemReceiveMs)
        }
    }

    /**
     * Fed by [ServerTimeReporter] for every successful response from our pinned OnPrem server.
     * Computes `offset = serverMs - systemReceiveMs`, smooths it (median of the last N samples with
     * a sanity clamp), and persists the result so the next launch starts corrected.
     */
    @AnyThread
    @JvmStatic
    fun onServerTimeSample(serverMs: Long, systemReceiveMs: Long) {
        val rawOffset = serverMs - systemReceiveMs
        synchronized(lock) {
            ensureOffsetLoadedLocked()

            // Clamp: drop a single wildly-off sample (unless we have never synced — then any first
            // signal is better than the raw device clock).
            val haveSynced = samples.isNotEmpty() || offsetMs != 0L
            if (haveSynced && Math.abs(rawOffset - offsetMs) > MAX_JUMP_MS) {
                logger.debug(
                    "Ignoring outlier server-time sample: raw={} current={} (Δ>{}ms)",
                    rawOffset, offsetMs, MAX_JUMP_MS,
                )
                return
            }

            samples.addLast(rawOffset)
            while (samples.size > SAMPLE_WINDOW) {
                samples.removeFirst()
            }

            val smoothed = median(samples)
            if (smoothed != offsetMs) {
                offsetMs = smoothed
                persistOffsetLocked(smoothed)
            } else if (!isSyncedMarkerSetLocked()) {
                // First sample happened to equal 0; still record that we synced.
                persistOffsetLocked(smoothed)
            }
        }
    }

    /** Corrected wall-clock millis for display/sort. */
    @AnyThread
    @JvmStatic
    fun nowMillis(): Long = System.currentTimeMillis() + currentOffsetMs()

    /** Corrected wall-clock [Date] for display/sort. */
    @AnyThread
    @JvmStatic
    fun now(): Date = Date(nowMillis())

    /**
     * Monotonic stamp for OUTGOING message creation: never goes backwards within a process, even if
     * the offset shifts mid-session (toggled device clock). Use for `createdAt`.
     */
    @JvmStatic
    fun stampNowMillis(): Long = synchronized(lock) {
        val t = maxOf(nowMillis(), lastStampMs + 1)
        lastStampMs = t
        t
    }

    /** Monotonic outgoing-creation stamp as a [Date]. */
    @JvmStatic
    fun stampNow(): Date = Date(stampNowMillis())

    private fun currentOffsetMs(): Long {
        if (!offsetLoaded) {
            synchronized(lock) { ensureOffsetLoadedLocked() }
        }
        return offsetMs
    }

    private fun ensureOffsetLoadedLocked() {
        if (offsetLoaded) {
            return
        }
        val prefs = preferenceServiceOrNull()
        if (prefs != null) {
            offsetMs = prefs.getTrustedClockOffsetMs()
            offsetLoaded = true
            logger.debug("Loaded persisted trusted-clock offset: {}ms", offsetMs)
        }
        // If prefs are not ready yet (very early startup before service manager), keep offset 0 and
        // retry on the next access — offsetLoaded stays false so we re-read once prefs exist.
    }

    private fun persistOffsetLocked(value: Long) {
        val prefs = preferenceServiceOrNull() ?: return
        prefs.setTrustedClockOffsetMs(value)
        offsetLoaded = true
    }

    private fun isSyncedMarkerSetLocked(): Boolean =
        preferenceServiceOrNull()?.hasTrustedClockSynced() ?: false

    private fun preferenceServiceOrNull(): ch.threema.app.preference.service.PreferenceService? =
        try {
            ThreemaApplication.getServiceManager()?.preferenceService
        } catch (e: Exception) {
            logger.debug("ServiceManager/PreferenceService not available yet", e)
            null
        }

    private fun median(values: Collection<Long>): Long {
        if (values.isEmpty()) {
            return 0L
        }
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            // even count: average of the two middle elements (favours stability)
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
