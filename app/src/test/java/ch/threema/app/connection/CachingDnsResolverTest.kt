package ch.threema.app.connection

import ch.threema.app.connection.CachingDnsResolver.MAX_CACHED_HOSTS
import ch.threema.app.connection.CachingDnsResolver.MAX_CACHE_AGE_MILLIS
import ch.threema.app.net.DotPreferredResolver
import ch.threema.base.utils.AsyncResolver
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOST = "dir.example.invalid"
private const val LIVE_IP = "192.0.2.10"
private const val CACHED_IP = "192.0.2.20"

/**
 * Covers the three resolution tiers, the age bound on the persisted cache, and the split between the
 * CSP entry point (which drives the foreground reconnect flag) and the HTTP one (which must not).
 */
class CachingDnsResolverTest {
    private class FakeStore : CachingDnsResolver.AddressStore {
        val values = mutableMapOf<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String) {
            values[key] = value
        }

        override fun entries(): Map<String, String> = values.toMap()

        override fun remove(keys: Collection<String>) {
            keys.forEach(values::remove)
        }
    }

    private val store = FakeStore()
    private var now = 1_700_000_000_000L

    @BeforeTest
    fun setUp() {
        CachingDnsResolver.store = store
        CachingDnsResolver.nowMillis = { now }
        CachingDnsResolver.preferredResolve = { throw UnknownHostException(it) }
        CachingDnsResolver.liveResolve = { throw UnknownHostException(it) }
        // The CSP flag is process-global; start every test from a known-clear state.
        clearCspFallbackFlag()
    }

    @AfterTest
    fun tearDown() {
        clearCspFallbackFlag()
        // Restore the production seams.
        CachingDnsResolver.store = CachingDnsResolver.SharedPreferencesStore
        CachingDnsResolver.preferredResolve = { DotPreferredResolver.resolve(it) }
        CachingDnsResolver.liveResolve = { AsyncResolver.getAllByName(it) }
        CachingDnsResolver.nowMillis = System::currentTimeMillis
    }

    /** Drive one successful CSP resolve so [CachingDnsResolver.wasLastResolveFromCache] is false. */
    private fun clearCspFallbackFlag() {
        val previous = CachingDnsResolver.preferredResolve
        CachingDnsResolver.preferredResolve = { listOf(address(LIVE_IP)) }
        CachingDnsResolver.getAllByName("flag-reset.invalid")
        store.values.remove("addrs_flag-reset.invalid")
        CachingDnsResolver.preferredResolve = previous
    }

    private fun address(ip: String): InetAddress = InetAddress.getByName(ip)

    private fun storeEntry(host: String, ip: String, storedAtMillis: Long?) {
        store.values["addrs_$host"] = if (storedAtMillis == null) ip else "$storedAtMillis|$ip"
    }

    private fun storedValue(host: String): String? = store.values["addrs_$host"]

    // region resolution tiers

    @Test
    fun `the preferred resolver wins and warms the cache`() {
        CachingDnsResolver.preferredResolve = { listOf(address(LIVE_IP)) }

        val resolved = CachingDnsResolver.getAllByName(HOST)

        assertEquals(listOf(LIVE_IP), resolved.map { it.hostAddress })
        assertEquals("$now|$LIVE_IP", storedValue(HOST))
        assertFalse(CachingDnsResolver.wasLastResolveFromCache())
    }

    @Test
    fun `a failing preferred resolver falls through to the live resolver`() {
        CachingDnsResolver.liveResolve = { arrayOf(address(LIVE_IP)) }

        val resolved = CachingDnsResolver.getAllByName(HOST)

        assertEquals(listOf(LIVE_IP), resolved.map { it.hostAddress })
        assertEquals("$now|$LIVE_IP", storedValue(HOST))
        assertFalse(CachingDnsResolver.wasLastResolveFromCache())
    }

    @Test
    fun `both resolvers failing falls back to the cached address`() {
        storeEntry(HOST, CACHED_IP, now)

        val resolved = CachingDnsResolver.getAllByName(HOST)

        assertEquals(listOf(CACHED_IP), resolved.map { it.hostAddress })
        assertTrue(CachingDnsResolver.wasLastResolveFromCache())
    }

    @Test
    fun `an EMPTY live answer falls back to the cached address`() {
        CachingDnsResolver.liveResolve = { emptyArray() }
        storeEntry(HOST, CACHED_IP, now)

        val resolved = CachingDnsResolver.getAllByName(HOST)

        assertEquals(listOf(CACHED_IP), resolved.map { it.hostAddress })
        assertTrue(CachingDnsResolver.wasLastResolveFromCache())
    }

    @Test
    fun `an EMPTY live answer with no cache raises instead of returning nothing`() {
        CachingDnsResolver.liveResolve = { emptyArray() }

        assertFailsWith<UnknownHostException> { CachingDnsResolver.getAllByName(HOST) }
    }

    @Test
    fun `a cold host with no cache rethrows the live failure`() {
        val failure = ExecutionException("resolver down", null)
        CachingDnsResolver.liveResolve = { throw failure }

        val thrown = assertFailsWith<ExecutionException> { CachingDnsResolver.getAllByName(HOST) }

        assertEquals(failure, thrown)
    }

    @Test
    fun `a live resolution wins over the cache and refreshes it`() {
        storeEntry(HOST, CACHED_IP, now - MAX_CACHE_AGE_MILLIS / 2)
        CachingDnsResolver.preferredResolve = { listOf(address(LIVE_IP)) }

        val resolved = CachingDnsResolver.getAllByName(HOST)

        assertEquals(listOf(LIVE_IP), resolved.map { it.hostAddress })
        assertEquals("$now|$LIVE_IP", storedValue(HOST))
    }

    // endregion

    // region the CSP recovery flag stays CSP-only

    @Test
    fun `an HTTP fallback does not request a chat reconnect`() {
        storeEntry(HOST, CACHED_IP, now)

        val resolved = CachingDnsResolver.resolveForHttp(HOST)

        assertEquals(listOf(CACHED_IP), resolved.map { it.hostAddress })
        assertFalse(CachingDnsResolver.wasLastResolveFromCache())
    }

    @Test
    fun `a successful HTTP resolve does not clear a pending chat reconnect`() {
        storeEntry(HOST, CACHED_IP, now)
        CachingDnsResolver.getAllByName(HOST)
        assertTrue(CachingDnsResolver.wasLastResolveFromCache())

        CachingDnsResolver.preferredResolve = { listOf(address(LIVE_IP)) }
        CachingDnsResolver.resolveForHttp("blob.example.invalid")

        assertTrue(CachingDnsResolver.wasLastResolveFromCache())
    }

    // endregion

    // region the okhttp3.Dns contract

    @Test
    fun `resolveForHttp normalises a non-UnknownHostException failure`() {
        val failure = ExecutionException("resolver down", null)
        CachingDnsResolver.liveResolve = { throw failure }

        val thrown = assertFailsWith<UnknownHostException> { CachingDnsResolver.resolveForHttp(HOST) }

        assertEquals(failure, thrown.cause)
    }

    @Test
    fun `resolveForHttp propagates an UnknownHostException unwrapped`() {
        val failure = UnknownHostException(HOST)
        CachingDnsResolver.liveResolve = { throw failure }

        val thrown = assertFailsWith<UnknownHostException> { CachingDnsResolver.resolveForHttp(HOST) }

        assertEquals(failure, thrown)
        assertNull(thrown.cause)
    }

    @Test
    fun `resolveForHttp never returns an empty list`() {
        CachingDnsResolver.preferredResolve = { emptyList() }
        CachingDnsResolver.liveResolve = { emptyArray() }

        assertFailsWith<UnknownHostException> { CachingDnsResolver.resolveForHttp(HOST) }
    }

    // endregion

    // region the age bound

    @Test
    fun `a cached address just within the age bound is still used`() {
        storeEntry(HOST, CACHED_IP, now - MAX_CACHE_AGE_MILLIS)

        val resolved = CachingDnsResolver.getAllByName(HOST)

        assertEquals(listOf(CACHED_IP), resolved.map { it.hostAddress })
    }

    @Test
    fun `a cached address past the age bound is dropped, not used`() {
        storeEntry(HOST, CACHED_IP, now - MAX_CACHE_AGE_MILLIS - 1)

        assertFailsWith<UnknownHostException> { CachingDnsResolver.getAllByName(HOST) }
        assertNull(storedValue(HOST))
    }

    @Test
    fun `an unparseable timestamp is dropped, not used`() {
        store.values["addrs_$HOST"] = "not-a-number|$CACHED_IP"

        assertFailsWith<UnknownHostException> { CachingDnsResolver.getAllByName(HOST) }
        assertNull(storedValue(HOST))
    }

    @Test
    fun `a clock that jumped backwards keeps the entry usable`() {
        storeEntry(HOST, CACHED_IP, now + MAX_CACHE_AGE_MILLIS)

        val resolved = CachingDnsResolver.getAllByName(HOST)

        assertEquals(listOf(CACHED_IP), resolved.map { it.hostAddress })
    }

    @Test
    fun `an entry written before the age bound existed is accepted once and stamped`() {
        storeEntry(HOST, CACHED_IP, storedAtMillis = null)

        val resolved = CachingDnsResolver.getAllByName(HOST)

        assertEquals(listOf(CACHED_IP), resolved.map { it.hostAddress })
        assertEquals("$now|$CACHED_IP", storedValue(HOST))
    }

    @Test
    fun `a stamped legacy entry then expires from its first use`() {
        storeEntry(HOST, CACHED_IP, storedAtMillis = null)
        CachingDnsResolver.getAllByName(HOST)

        now += MAX_CACHE_AGE_MILLIS + 1

        assertFailsWith<UnknownHostException> { CachingDnsResolver.getAllByName(HOST) }
    }

    // endregion

    // region the persisted cache stays bounded

    @Test
    fun `writing below the host cap prunes nothing`() {
        repeat(MAX_CACHED_HOSTS - 1) { index ->
            storeEntry("host$index.invalid", CACHED_IP, now - MAX_CACHE_AGE_MILLIS - 1)
        }
        CachingDnsResolver.preferredResolve = { listOf(address(LIVE_IP)) }

        CachingDnsResolver.getAllByName(HOST)

        assertEquals(MAX_CACHED_HOSTS, store.values.size)
    }

    @Test
    fun `writing past the host cap drops the expired entries`() {
        repeat(MAX_CACHED_HOSTS) { index ->
            storeEntry("host$index.invalid", CACHED_IP, now - MAX_CACHE_AGE_MILLIS - 1)
        }
        CachingDnsResolver.preferredResolve = { listOf(address(LIVE_IP)) }

        CachingDnsResolver.getAllByName(HOST)

        assertEquals(setOf("addrs_$HOST"), store.values.keys)
    }

    @Test
    fun `writing past the host cap drops the oldest when nothing has expired`() {
        repeat(MAX_CACHED_HOSTS) { index ->
            storeEntry("host$index.invalid", CACHED_IP, now - index)
        }
        CachingDnsResolver.preferredResolve = { listOf(address(LIVE_IP)) }

        CachingDnsResolver.getAllByName(HOST)

        assertEquals(MAX_CACHED_HOSTS, store.values.size)
        assertTrue(store.values.containsKey("addrs_$HOST"))
        // host0 is the newest of the pre-existing entries, the highest index the oldest.
        assertTrue(store.values.containsKey("addrs_host0.invalid"))
        assertFalse(store.values.containsKey("addrs_host${MAX_CACHED_HOSTS - 1}.invalid"))
    }

    // endregion

    // region wiring

    @Test
    fun `the shared okhttp client resolves through the last-good-IP tier`() {
        val source = File("src/main/java/ch/threema/app/di/modules/OkHttp.kt").readText()

        assertTrue(
            source.contains("dns(Dns { hostname -> CachingDnsResolver.resolveForHttp(hostname) })"),
            "The shared base OkHttp client must resolve through CachingDnsResolver.resolveForHttp",
        )
    }

    @Test
    fun `the apk download client resolves through the last-good-IP tier`() {
        val source = File("src/onprem/java/ch/threema/app/services/ApkUpdateDownloadService.java").readText()

        assertTrue(
            source.contains(".dns(CachingDnsResolver::resolveForHttp)"),
            "The self-update download client must resolve through CachingDnsResolver.resolveForHttp",
        )
    }

    @Test
    fun `the link preview fetcher is deliberately left out`() {
        val source = File("src/main/java/ch/threema/app/linkpreview/LinkPreviewFetcher.java").readText()

        assertFalse(
            source.contains("CachingDnsResolver"),
            "LinkPreviewValidator rejects local addresses; a validation lookup followed by a " +
                "differently cached connection lookup would weaken that boundary, and persisting " +
                "arbitrary preview hostnames changes the privacy profile of the cache",
        )
    }

    @Test
    fun `the diagnostic resolvers and the DoT bootstrap are deliberately left out`() {
        val diagnostics = File("src/main/java/ch/threema/app/diagnostics/DnsResolvers.kt").readText()
        val dotBootstrap = File("src/main/java/ch/threema/app/net/SecureDnsClient.kt").readText()

        assertFalse(
            diagnostics.contains("CachingDnsResolver"),
            "A diagnostic must test the method it names, not return production cache entries",
        )
        assertFalse(
            dotBootstrap.contains("CachingDnsResolver"),
            "The DoT bootstrap connects by literal IP; routing it back here would recurse",
        )
    }

    // endregion
}
