package ch.threema.app.net

import ch.threema.base.utils.getThreemaLogger
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private val logger = getThreemaLogger("DotPreferredResolver")

/**
 * F1Whisper: a DNS resolver that keeps the platform resolver on the fast path but
 * treats our trusted DNS-over-TLS server ([SecureDnsClient]) as a non-blocking
 * verifier and a fallback.
 *
 * Why the split: on the censored / throttled networks this build targets, plain DNS
 * is locally hijacked (port-53 interception). But DoT must NOT sit in the blocking
 * resolution hot path — that would add a TLS round-trip to the pinned server on
 * every lookup and regress healthy networks. So:
 *
 * Order per lookup:
 *   1. literal IP  -> return as-is (no lookup, no DoT);
 *   2. fresh TTL cache hit -> return cached addresses (the cache may already hold a
 *      DoT-verified answer from a previous background check);
 *   3. system resolver -> on success, return IMMEDIATELY and kick off a NON-BLOCKING
 *      background DoT check that overwrites the cache with the trusted answer, so the
 *      next lookup / reconnect uses it (a poisoned first answer self-heals within one
 *      reconnect; the OPPF fetch's own retry also picks up the corrected cache);
 *   4. system resolver FAILS -> DoT is used synchronously as the FALLBACK, then the
 *      cache; if DoT also fails the original [java.net.UnknownHostException] is
 *      propagated so callers' own fallback (e.g. [ch.threema.app.connection.CachingDnsResolver]'s
 *      last-good-IP cache) still triggers.
 *
 * This type MUST NOT call back into the app's OkHttp base client or into
 * [ch.threema.app.connection.CachingDnsResolver] — doing so would recurse, since
 * both delegate their name resolution here.
 *
 * Blocking on the fast path only for the system lookup; callers already run off the
 * main thread. The background DoT check runs on a dedicated daemon thread.
 */
object DotPreferredResolver {

    /** How long a resolved answer stays valid in the in-memory cache. */
    private const val CACHE_TTL_NANOS = 5L * 60L * 1_000_000_000L // 5 minutes

    private class CacheEntry(val addresses: List<InetAddress>, val storedAtNanos: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * The platform resolver call, injectable for tests. Production uses
     * [InetAddress.getAllByName]; tests replace it to avoid mocking java.base static
     * methods (which the JVM module system forbids reflective access to).
     */
    internal var systemResolver: (String) -> List<InetAddress> =
        { InetAddress.getAllByName(it).toList() }

    /** Daemon executor for the non-blocking background DoT verification. */
    private val bgExecutor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "dot-verify").apply { isDaemon = true }
        }
    }

    /**
     * How a background DoT verification is dispatched. Production posts to [bgExecutor]
     * (non-blocking); tests replace it to run inline (or to disable it) for determinism.
     */
    internal var backgroundDispatcher: (Runnable) -> Unit = { r -> bgExecutor.execute(r) }

    /** Hosts with an in-flight background verification, to avoid piling up duplicates. */
    private val inFlightVerifications = ConcurrentHashMap.newKeySet<String>()

    /**
     * Resolve [host]. See the class doc for the full order. DoT never blocks the fast
     * (system) path; it only blocks as a fallback when the system resolver fails.
     *
     * @throws java.net.UnknownHostException if neither the system resolver nor DoT can
     *   resolve [host] (the system exception is propagated unchanged).
     */
    @JvmStatic
    @JvmOverloads
    @Throws(java.net.UnknownHostException::class)
    fun resolve(host: String, timeoutMs: Int = 8000): List<InetAddress> {
        // 1. A literal IP needs no resolution at all. Never route the pinned DoT server
        //    IP through DoT either (it is only ever reached by literal IP anyway).
        if (isLiteralIp(host)) {
            return listOf(InetAddress.getByName(host))
        }

        // 2. Fresh cache entry (may be a DoT-verified answer from a prior background check).
        cachedAddresses(host)?.let { return it }

        // 3. Fast path: the system resolver. On success return immediately and verify in
        //    the background — DoT stays OFF the blocking path.
        val viaSystem = try {
            systemResolver(host)
        } catch (e: Exception) {
            // 4. System failed (e.g. DNS frozen in Doze, or NXDOMAIN). DoT is the fallback.
            logger.debug("System resolve failed for {}: {}; falling back to DoT", host, e.message)
            val viaDot = tryDot(host, timeoutMs)
            if (viaDot != null) {
                store(host, viaDot)
                return viaDot
            }
            throw e
        }

        if (viaSystem.isNotEmpty()) {
            store(host, viaSystem)
            scheduleBackgroundDotVerify(host, timeoutMs)
            return viaSystem
        }

        // System returned no records: try DoT as a fallback before giving up.
        val viaDot = tryDot(host, timeoutMs)
        if (viaDot != null) {
            store(host, viaDot)
            return viaDot
        }
        return viaSystem
    }

    /** One synchronous DoT attempt; null on failure or empty answer (never throws). */
    private fun tryDot(host: String, timeoutMs: Int): List<InetAddress>? = try {
        SecureDnsClient.dotLookup(host, timeoutMs).takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        logger.debug("DoT lookup failed for {}: {}", host, e.message)
        null
    }

    /**
     * Non-blocking check: resolve [host] over DoT on a background thread and, if it
     * yields a trusted answer, overwrite the cache so the next lookup / reconnect uses
     * it. De-duplicated per host. Failures are ignored (the system answer stands).
     */
    private fun scheduleBackgroundDotVerify(host: String, timeoutMs: Int) {
        if (!inFlightVerifications.add(host)) {
            return
        }
        try {
            backgroundDispatcher {
                try {
                    val viaDot = SecureDnsClient.dotLookup(host, timeoutMs)
                    if (viaDot.isNotEmpty()) {
                        store(host, viaDot)
                    }
                } catch (e: Exception) {
                    logger.debug("Background DoT verify failed for {}: {}", host, e.message)
                } finally {
                    inFlightVerifications.remove(host)
                }
            }
        } catch (e: Exception) {
            // Executor rejected (e.g. shutting down) — drop the in-flight marker and move on.
            inFlightVerifications.remove(host)
        }
    }

    /** Return the cached addresses for [host] if present and not expired, else null. */
    private fun cachedAddresses(host: String): List<InetAddress>? {
        val entry = cache[host] ?: return null
        if (System.nanoTime() - entry.storedAtNanos > CACHE_TTL_NANOS) {
            cache.remove(host, entry)
            return null
        }
        return entry.addresses
    }

    private fun store(host: String, addresses: List<InetAddress>) {
        cache[host] = CacheEntry(addresses, System.nanoTime())
    }

    /**
     * Cheap check for a literal IPv4 / IPv6 address — no DNS lookup. A hostname
     * always contains at least one letter (TLDs are alphabetic) or is otherwise not
     * a valid IP literal; IPv4 is digits+dots, IPv6 contains a colon.
     */
    private fun isLiteralIp(host: String): Boolean {
        if (host.isEmpty()) {
            return false
        }
        // IPv6 literals contain a colon; hostnames never do.
        if (host.indexOf(':') >= 0) {
            return true
        }
        // IPv4 literal: every character is a digit or a dot AND it has 4 dot-separated
        // numeric parts. This avoids accidentally treating a numeric-looking hostname
        // label as an address while still short-circuiting real IPv4 literals.
        var digitOrDotOnly = true
        for (c in host) {
            if (c != '.' && (c < '0' || c > '9')) {
                digitOrDotOnly = false
                break
            }
        }
        if (!digitOrDotOnly) {
            return false
        }
        val parts = host.split('.')
        if (parts.size != 4) {
            return false
        }
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && (part.toIntOrNull()?.let { it in 0..255 } ?: false)
        }
    }

    /** Test-only: drop all cached entries and any in-flight verification markers. */
    internal fun clearCacheForTest() {
        cache.clear()
        inFlightVerifications.clear()
    }
}
