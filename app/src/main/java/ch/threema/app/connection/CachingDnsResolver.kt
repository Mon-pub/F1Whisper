package ch.threema.app.connection

import ch.threema.app.ThreemaApplication
import ch.threema.app.net.DotPreferredResolver
import ch.threema.base.utils.AsyncResolver
import ch.threema.base.utils.getThreemaLogger
import java.net.InetAddress

private val logger = getThreemaLogger("CachingDnsResolver")

/**
 * F1Whisper: a DNS resolver that caches the last successfully-resolved address(es) per host and
 * falls back to them when live resolution fails.
 *
 * Why: this is a GMS-free build — background message delivery relies on a persistent CSP socket
 * revived by [ch.threema.app.services.ThreemaPushService] / its revive alarm. On Doze-aggressive
 * OEMs (HONOR / Xiaomi / Oppo / Vivo) the network is frozen in deep idle and a background
 * `InetAddress.getAllByName(chatHost)` throws `UnknownHostException: No address` — so the revive
 * fires but the reconnect dies at DNS and no message is delivered until the user opens the app
 * (which is when DNS works again and the server's queued messages drain in a burst).
 *
 * Fix: remember the IP(s) we last resolved for a host while online, and when a later resolve fails,
 * connect to those cached IP(s) directly. Connecting by literal IP needs no DNS lookup, so it can
 * succeed during a Doze maintenance window where name resolution cannot.
 *
 * SECURITY: connecting to a cached/stale/hijacked IP is safe because the CSP handshake validates
 * the server's permanent Curve25519 public key (the trusted chat-server key) — a wrong endpoint
 * fails the handshake and no plaintext is ever exposed. The hostname is not a trust anchor here.
 *
 * The cache holds only our public server's IP(s) (not sensitive) in a small dedicated
 * SharedPreferences. Live resolution always wins when it succeeds (and refreshes the cache), so a
 * genuine server-IP change is picked up automatically; the cache is consulted ONLY on failure.
 */
object CachingDnsResolver {
    private const val PREFS_NAME = "f1w_dns_cache"
    private const val KEY_PREFIX = "addrs_"
    private const val SEPARATOR = ","

    /**
     * True when the MOST RECENT resolution fell back to a cached address (live DNS failed). Lets the
     * foreground hook ([ch.threema.app.startup.AppProcessLifecycleObserver]) force a fresh-resolve
     * reconnect once the user opens the app (no Doze restriction → DNS works again), so we never stay
     * pinned to a possibly-stale cached IP. Cleared on the next successful live resolution.
     */
    @Volatile
    private var lastResolveFromCache: Boolean = false

    @JvmStatic
    fun wasLastResolveFromCache(): Boolean = lastResolveFromCache

    /**
     * Drop-in replacement for [AsyncResolver.getAllByName] (same signature, used as the CSP
     * connection's resolver).
     *
     * Resolution order (F1Whisper): [DotPreferredResolver] (the system resolver on the FAST path,
     * with a synchronous DoT FALLBACK only when the system resolver fails or returns no records —
     * fallback-only per fork review M-03: DoT is never contacted for hostnames the system resolver
     * answers, so no hostname leaks to the DoT provider and a working split-horizon answer is never
     * displaced) -> then the ORIGINAL behaviour unchanged: live [AsyncResolver.getAllByName]
     * (refreshing the cache on success), and on its failure the cached address(es) if any,
     * otherwise rethrow the original failure.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun getAllByName(host: String): Array<InetAddress> {
        // System-resolver fast path with a DoT fallback on failure only (DotPreferredResolver
        // does NOT call back here — no recursion — it uses the system resolver and the pinned DoT
        // server directly). A successful resolve warms the last-good cache so a later Doze reconnect
        // can reuse the IP by literal.
        try {
            val resolved = DotPreferredResolver.resolve(host)
            if (resolved.isNotEmpty()) {
                val addrs = resolved.toTypedArray()
                persist(host, addrs)
                lastResolveFromCache = false
                return addrs
            }
        } catch (e: Exception) {
            logger.debug("Resolve failed for {}: {}; trying the app resolver", host, e.message)
        }

        return try {
            val resolved = AsyncResolver.getAllByName(host)
            if (resolved.isNotEmpty()) {
                persist(host, resolved)
            }
            lastResolveFromCache = false
            resolved
        } catch (e: Exception) {
            val cached = loadCached(host)
            if (cached.isNotEmpty()) {
                logger.warn(
                    "DNS resolution failed for {}; falling back to {} cached address(es) (e.g. {})",
                    host,
                    cached.size,
                    cached.first().hostAddress,
                )
                lastResolveFromCache = true
                cached.toTypedArray()
            } else {
                logger.warn("DNS resolution failed for {} and no cached address is available", host)
                throw e
            }
        }
    }

    private fun persist(host: String, addrs: Array<InetAddress>) {
        try {
            val csv = addrs.mapNotNull { it.hostAddress }.joinToString(SEPARATOR)
            if (csv.isNotEmpty()) {
                prefs()?.edit()?.putString(KEY_PREFIX + host, csv)?.apply()
            }
        } catch (e: Exception) {
            logger.debug("Could not persist DNS cache for {}", host, e)
        }
    }

    private fun loadCached(host: String): List<InetAddress> {
        return try {
            val csv = prefs()?.getString(KEY_PREFIX + host, null) ?: return emptyList()
            csv.split(SEPARATOR)
                .filter { it.isNotBlank() }
                .mapNotNull { ip ->
                    // A literal IP string resolves WITHOUT a DNS lookup (no network in Doze needed).
                    runCatching { InetAddress.getByName(ip) }.getOrNull()
                }
        } catch (e: Exception) {
            logger.debug("Could not read DNS cache for {}", host, e)
            emptyList()
        }
    }

    private fun prefs(): android.content.SharedPreferences? =
        try {
            ThreemaApplication.getAppContext()
                .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }
}
