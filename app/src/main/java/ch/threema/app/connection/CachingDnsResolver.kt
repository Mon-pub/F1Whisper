package ch.threema.app.connection

import ch.threema.app.ThreemaApplication
import ch.threema.app.net.DotPreferredResolver
import ch.threema.base.utils.AsyncResolver
import ch.threema.base.utils.getThreemaLogger
import java.net.InetAddress
import java.net.UnknownHostException

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
 * SECURITY: for the CSP socket ([getAllByName]) a stale or hijacked IP is harmless because the CSP
 * handshake validates the server's permanent Curve25519 public key — a wrong endpoint fails the
 * handshake and no plaintext is ever exposed. For HTTP ([resolveForHttp]) the hostname is likewise
 * NOT the trust anchor: only the address the connection is made to is taken from this cache, while
 * TLS still runs against the ORIGINAL url hostname (SNI, hostname verification and, on OnPrem, the
 * OPPF certificate pinning in [ch.threema.app.onprem.OnPremCertPinning] all keep working unchanged).
 * Nothing here ever rewrites a url to a literal IP.
 *
 * The cache holds server IP(s) (not sensitive) in a small dedicated SharedPreferences. Live
 * resolution always wins when it succeeds (and refreshes the cache), so a genuine server-IP change
 * is picked up automatically; the cache is consulted ONLY on failure.
 *
 * Entries carry the wall-clock time they were written and expire after [MAX_CACHE_AGE_MILLIS]. Wall
 * clock, not [System.nanoTime], because the cache outlives the process. A clock that jumps backwards
 * keeps an entry alive (fail-open, the pre-expiry behaviour); a clock that jumps far forwards
 * discards it, which just returns the caller to plain resolution.
 */
object CachingDnsResolver {
    private const val PREFS_NAME = "f1w_dns_cache"
    private const val KEY_PREFIX = "addrs_"
    private const val SEPARATOR = ","

    /** Separates the write timestamp from the address list. Absent in pre-expiry entries. */
    private const val STAMP_SEPARATOR = '|'

    /**
     * How long a persisted last-good address may still be used as a fallback. Long enough to cover
     * the failure this cache exists for (a device that has been offline or Doze-frozen for days),
     * short enough that a server that moved is not chased forever: live resolution always wins, so
     * the bound only ever limits how long a FAILING resolver can be papered over.
     */
    internal const val MAX_CACHE_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L

    /**
     * Hard cap on persisted hosts. Before the HTTP tier existed exactly one host (the chat server)
     * was ever written; now every host the shared OkHttp client talks to is, including redirect CDN
     * hosts we do not choose. Expired entries are dropped first, then the oldest.
     */
    internal const val MAX_CACHED_HOSTS = 32

    /** Persisted last-good addresses, keyed by [KEY_PREFIX] + host. */
    internal interface AddressStore {
        fun read(key: String): String?

        fun write(key: String, value: String)

        fun entries(): Map<String, String>

        fun remove(keys: Collection<String>)
    }

    /**
     * Production seams, replaced in unit tests. Kept as plain properties in the style of
     * [DotPreferredResolver.systemResolver]: the alternative is mocking java.base statics, which the
     * JVM module system forbids reflective access to.
     */
    internal var store: AddressStore = SharedPreferencesStore

    internal var preferredResolve: (String) -> List<InetAddress> = { DotPreferredResolver.resolve(it) }

    internal var liveResolve: (String) -> Array<InetAddress> = { AsyncResolver.getAllByName(it) }

    internal var nowMillis: () -> Long = System::currentTimeMillis

    /**
     * True when the most recent CSP resolution fell back to a cached address (live DNS failed). Lets
     * the foreground hook ([ch.threema.app.startup.AppProcessLifecycleObserver]) force a
     * fresh-resolve reconnect once the user opens the app (no Doze restriction → DNS works again),
     * so we never stay pinned to a possibly-stale cached IP. Cleared on the next successful live
     * resolution.
     *
     * CSP-ONLY ON PURPOSE. Its single consumer reconnects the CHAT connection, so an HTTP fallback
     * (a blob download, an OPPF fetch) must not set it: that would tear down and rebuild a perfectly
     * healthy chat socket because an unrelated host failed to resolve. [resolveForHttp] therefore
     * shares every resolution tier with [getAllByName] but never touches this flag.
     */
    @Volatile
    private var lastResolveFromCache: Boolean = false

    @JvmStatic
    fun wasLastResolveFromCache(): Boolean = lastResolveFromCache

    /**
     * Drop-in replacement for [AsyncResolver.getAllByName] (same signature, used as the CSP
     * connection's resolver). Updates [wasLastResolveFromCache].
     */
    @JvmStatic
    @Throws(Exception::class)
    fun getAllByName(host: String): Array<InetAddress> =
        resolveWithLastGoodFallback(host, trackForCspRecovery = true)

    /**
     * The same resolution for [okhttp3.Dns], so every host derived from the shared base client
     * (mediator, directory, Work, blob, Safe, OPPF, self-update) gets the last-good-IP fallback the
     * CSP socket already had. Does NOT update [wasLastResolveFromCache] — see its doc.
     *
     * Failures are normalised to [UnknownHostException] because that is the only checked exception
     * `Dns.lookup` declares; letting an `ExecutionException` from [AsyncResolver] escape would
     * surface from `Call.execute()` as something no caller's `catch (IOException)` handles.
     */
    @JvmStatic
    @Throws(UnknownHostException::class)
    fun resolveForHttp(host: String): List<InetAddress> =
        try {
            resolveWithLastGoodFallback(host, trackForCspRecovery = false).toList()
        } catch (e: UnknownHostException) {
            throw e
        } catch (e: Exception) {
            throw UnknownHostException(host).apply { initCause(e) }
        }

    /**
     * Resolution order: [DotPreferredResolver] (the system resolver on the FAST path, with a
     * synchronous DoT FALLBACK only when the system resolver fails or returns no records —
     * fallback-only per fork review M-03, so no hostname leaks to the DoT provider and a working
     * split-horizon answer is never displaced) -> live [AsyncResolver] (refreshing the cache on
     * success) -> the persisted last-good address(es), otherwise rethrow.
     */
    private fun resolveWithLastGoodFallback(host: String, trackForCspRecovery: Boolean): Array<InetAddress> {
        var liveFailure: Exception? = null

        try {
            val resolved = preferredResolve(host)
            if (resolved.isNotEmpty()) {
                return succeed(host, resolved.toTypedArray(), trackForCspRecovery)
            }
        } catch (e: Exception) {
            logger.debug("Resolve failed for {}: {}; trying the app resolver", host, e.message)
            liveFailure = e
        }

        try {
            val resolved = liveResolve(host)
            if (resolved.isNotEmpty()) {
                return succeed(host, resolved, trackForCspRecovery)
            }
            // An EMPTY answer is a resolution FAILURE, not a result, and must reach the cache tier
            // exactly as a thrown one does. Returning it instead was a real hole: the CSP address
            // provider turns an empty array straight into UnknownHostException without ever
            // consulting the cache, and okhttp3.Dns forbids an empty list outright.
            logger.debug("Resolve returned no records for {}; trying the last-good cache", host)
        } catch (e: Exception) {
            liveFailure = e
        }

        val cached = loadCached(host)
        if (cached.isNotEmpty()) {
            logger.warn(
                "DNS resolution failed for {}; falling back to {} cached address(es) (e.g. {})",
                host,
                cached.size,
                cached.first().hostAddress,
            )
            if (trackForCspRecovery) {
                lastResolveFromCache = true
            }
            return cached.toTypedArray()
        }

        logger.warn("DNS resolution failed for {} and no cached address is available", host)
        throw liveFailure ?: UnknownHostException(host)
    }

    private fun succeed(host: String, addrs: Array<InetAddress>, trackForCspRecovery: Boolean): Array<InetAddress> {
        persist(host, addrs)
        if (trackForCspRecovery) {
            lastResolveFromCache = false
        }
        return addrs
    }

    private fun persist(host: String, addrs: Array<InetAddress>) {
        try {
            val csv = addrs.mapNotNull { it.hostAddress }.joinToString(SEPARATOR)
            if (csv.isNotEmpty()) {
                store.write(KEY_PREFIX + host, "${nowMillis()}$STAMP_SEPARATOR$csv")
                pruneIfOversized()
            }
        } catch (e: Exception) {
            logger.debug("Could not persist DNS cache for {}", host, e)
        }
    }

    private fun loadCached(host: String): List<InetAddress> {
        return try {
            val key = KEY_PREFIX + host
            val stored = store.read(key) ?: return emptyList()
            val csv = readAddressesWithinAgeBound(key, stored) ?: return emptyList()
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

    /**
     * The address list of [stored] if it is still within [MAX_CACHE_AGE_MILLIS], else null (and the
     * entry is dropped).
     *
     * An entry written before the age bound existed carries no timestamp. Its age is unknowable, and
     * discarding it would remove this cache's protection on exactly the first post-upgrade Doze
     * window — the case it was built for. So it is accepted once and stamped as it is read, which
     * bounds it to [MAX_CACHE_AGE_MILLIS] from first use instead of forever.
     */
    private fun readAddressesWithinAgeBound(key: String, stored: String): String? {
        val stampEnd = stored.indexOf(STAMP_SEPARATOR)
        if (stampEnd < 0) {
            store.write(key, "${nowMillis()}$STAMP_SEPARATOR$stored")
            return stored
        }
        val storedAt = stored.substring(0, stampEnd).toLongOrNull()
        if (storedAt == null || nowMillis() - storedAt > MAX_CACHE_AGE_MILLIS) {
            store.remove(listOf(key))
            return null
        }
        return stored.substring(stampEnd + 1)
    }

    private fun pruneIfOversized() {
        val entries = store.entries()
        if (entries.size <= MAX_CACHED_HOSTS) {
            return
        }
        val now = nowMillis()
        val expired = entries.filterValues { value ->
            storedAtMillis(value)?.let { now - it > MAX_CACHE_AGE_MILLIS } == true
        }.keys
        val remaining = entries - expired
        val overflow = if (remaining.size > MAX_CACHED_HOSTS) {
            remaining.keys
                // An entry with no timestamp is the oldest thing we can have: it predates the bound.
                .sortedBy { key -> storedAtMillis(remaining.getValue(key)) ?: Long.MIN_VALUE }
                .take(remaining.size - MAX_CACHED_HOSTS)
        } else {
            emptyList()
        }
        val doomed = expired + overflow
        if (doomed.isNotEmpty()) {
            store.remove(doomed)
        }
    }

    private fun storedAtMillis(stored: String): Long? {
        val stampEnd = stored.indexOf(STAMP_SEPARATOR)
        return if (stampEnd < 0) null else stored.substring(0, stampEnd).toLongOrNull()
    }

    internal object SharedPreferencesStore : AddressStore {
        override fun read(key: String): String? = prefs()?.getString(key, null)

        override fun write(key: String, value: String) {
            prefs()?.edit()?.putString(key, value)?.apply()
        }

        override fun entries(): Map<String, String> =
            prefs()?.all.orEmpty()
                .filterKeys { it.startsWith(KEY_PREFIX) }
                .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
                .toMap()

        override fun remove(keys: Collection<String>) {
            val editor = prefs()?.edit() ?: return
            keys.forEach(editor::remove)
            editor.apply()
        }

        private fun prefs(): android.content.SharedPreferences? =
            try {
                ThreemaApplication.getAppContext()
                    .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            } catch (e: Exception) {
                null
            }
    }
}
