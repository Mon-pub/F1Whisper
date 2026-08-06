package ch.threema.app.net

import ch.threema.base.utils.getThreemaLogger
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

private val logger = getThreemaLogger("DotPreferredResolver")

/**
 * F1Whisper: a DNS resolver that keeps the platform resolver on the fast path and uses our
 * trusted DNS-over-TLS server ([SecureDnsClient]) strictly as a FALLBACK.
 *
 * Why the split: on the censored / throttled networks this build targets, plain DNS is locally
 * hijacked (port-53 interception) or frozen (Doze). But DoT must NOT sit in the blocking
 * resolution hot path — that would add a TLS round-trip to the pinned server on every lookup and
 * regress healthy networks.
 *
 * FALLBACK-ONLY (fork review M-03): the earlier design additionally sent every successfully
 * system-resolved hostname to the external DoT service as a non-blocking background "verification"
 * and overwrote the cache with the DoT answer. That (a) disclosed every hostname this client talks
 * to — including the OnPrem server names — to the public DoT provider on every fresh lookup, and
 * (b) could replace a WORKING split-horizon/private answer with a public one. Both are gone: DoT
 * is now consulted ONLY when the system resolver fails or returns no records — exactly the
 * manifestations the censorship/Doze fixes target. Accepted trade-off (recorded in the plan): a
 * hijacked answer that still resolves "successfully" no longer self-heals in the background; it
 * heals when the system path actually fails. A connection-failure feedback trigger can be added
 * later without reintroducing the always-on leak.
 *
 * Order per lookup:
 *   1. literal IP  -> return as-is (no lookup, no DoT);
 *   2. fresh SYSTEM cache hit -> return it (a working system answer always wins — DoT answers are
 *      cached separately and never displace it);
 *   3. system resolver -> on success, store in the system cache and return. NO DoT contact.
 *   4. system resolver fails or returns empty -> fresh DoT cache hit, else one synchronous DoT
 *      lookup (stored in the DoT-scoped cache); if DoT also fails, the original
 *      [java.net.UnknownHostException] propagates so callers' own fallback (e.g.
 *      [ch.threema.app.connection.CachingDnsResolver]'s last-good-IP cache) still triggers.
 *
 * This type MUST NOT call back into the app's OkHttp base client or into
 * [ch.threema.app.connection.CachingDnsResolver] — doing so would recurse, since both delegate
 * their name resolution here.
 *
 * Blocking only for the system lookup on the fast path and for the DoT fallback on the failure
 * path; callers already run off the main thread.
 */
object DotPreferredResolver {

    /** How long a resolved answer stays valid in the in-memory caches. */
    private const val CACHE_TTL_NANOS = 5L * 60L * 1_000_000_000L // 5 minutes

    private class CacheEntry(val addresses: List<InetAddress>, val storedAtNanos: Long)

    /**
     * Resolver-scoped caches (fork review M-03): system answers and DoT answers never share an
     * entry, so a DoT fallback answer cannot mask a later working system/private answer.
     */
    private val systemCache = ConcurrentHashMap<String, CacheEntry>()
    private val dotCache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * The platform resolver call, injectable for tests. Production uses
     * [InetAddress.getAllByName]; tests replace it to avoid mocking java.base static
     * methods (which the JVM module system forbids reflective access to).
     */
    internal var systemResolver: (String) -> List<InetAddress> =
        { InetAddress.getAllByName(it).toList() }

    /**
     * Resolve [host]. See the class doc for the full order. DoT is contacted ONLY on the
     * failure path (system resolver threw or returned no records) — never for a hostname the
     * system resolver answered.
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

        // 2. Fresh SYSTEM cache entry — a working system answer always wins.
        cachedAddresses(systemCache, host)?.let { return it }

        // 3. Fast path: the system resolver. On success, return WITHOUT contacting DoT.
        val viaSystem = try {
            systemResolver(host)
        } catch (e: Exception) {
            // 4. System failed (e.g. DNS frozen in Doze, NXDOMAIN, or censored). DoT is the fallback.
            logger.debug("System resolve failed for {}: {}; falling back to DoT", host, e.message)
            val viaDot = dotFallback(host, timeoutMs)
            if (viaDot != null) {
                return viaDot
            }
            throw e
        }

        if (viaSystem.isNotEmpty()) {
            store(systemCache, host, viaSystem)
            return viaSystem
        }

        // System returned no records: try DoT as a fallback before giving up.
        val viaDot = dotFallback(host, timeoutMs)
        if (viaDot != null) {
            return viaDot
        }
        return viaSystem
    }

    /**
     * The failure-path DoT fallback: a fresh DoT-scoped cache hit, else one synchronous DoT
     * lookup (cached on success). Null on failure or empty answer (never throws).
     */
    private fun dotFallback(host: String, timeoutMs: Int): List<InetAddress>? {
        cachedAddresses(dotCache, host)?.let { return it }
        return try {
            SecureDnsClient.dotLookup(host, timeoutMs)
                .takeIf { it.isNotEmpty() }
                ?.also { store(dotCache, host, it) }
        } catch (e: Exception) {
            logger.debug("DoT lookup failed for {}: {}", host, e.message)
            null
        }
    }

    /** Return the cached addresses for [host] from [cache] if present and not expired, else null. */
    private fun cachedAddresses(cache: ConcurrentHashMap<String, CacheEntry>, host: String): List<InetAddress>? {
        val entry = cache[host] ?: return null
        if (System.nanoTime() - entry.storedAtNanos > CACHE_TTL_NANOS) {
            cache.remove(host, entry)
            return null
        }
        return entry.addresses
    }

    private fun store(cache: ConcurrentHashMap<String, CacheEntry>, host: String, addresses: List<InetAddress>) {
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

    /** Test-only: drop all cached entries (both resolver-scoped caches). */
    internal fun clearCacheForTest() {
        systemCache.clear()
        dotCache.clear()
    }
}
