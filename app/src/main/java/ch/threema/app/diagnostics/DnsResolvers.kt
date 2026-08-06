package ch.threema.app.diagnostics

import ch.threema.app.net.SecureDnsClient
import ch.threema.base.utils.getThreemaLogger
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

private val logger = getThreemaLogger("DnsResolvers")

/**
 * DNS resolver matrix for the F1Whisper connectivity troubleshooter.
 *
 * Each function corresponds to one row in the resolver matrix defined in the
 * plan.  All functions:
 *  - are suspend functions running on [Dispatchers.IO]
 *  - wrap the work in [withTimeoutOrNull] ([ProbeTimeouts.DNS_TIMEOUT_MS])
 *  - NEVER throw — all failures are captured in [DnsProbeResult.error]
 *  - return both A (IPv4) and AAAA (IPv6) records where the protocol supports it
 */
object DnsResolvers {

    // -----------------------------------------------------------------------
    // 1. System resolver
    // -----------------------------------------------------------------------

    /**
     * Resolver 1 — the OS system resolver (via [InetAddress.getAllByName]).
     * This is the baseline: if it returns a different IP than the DoH/DoT
     * resolvers it indicates DNS poisoning.
     */
    suspend fun system(host: String): DnsProbeResult =
        withContext(Dispatchers.IO) {
            val name = DnsResolverNames.SYSTEM
            val t0 = System.currentTimeMillis()
            withTimeoutOrNull(ProbeTimeouts.DNS_TIMEOUT_MS) {
                try {
                    val addrs = InetAddress.getAllByName(host)
                    val ms = System.currentTimeMillis() - t0
                    val aRecords = addrs.filter { it is java.net.Inet4Address }.map { it.hostAddress ?: "" }
                    val aaaaRecords = addrs.filter { it is java.net.Inet6Address }.map { it.hostAddress ?: "" }
                    DnsProbeResult(name, aRecords, aaaaRecords, ms, null)
                } catch (e: Exception) {
                    val ms = System.currentTimeMillis() - t0
                    logger.debug("System DNS failed for {}: {}", host, e.message)
                    DnsProbeResult(name, emptyList(), emptyList(), ms, e.message ?: e.javaClass.simpleName)
                }
            } ?: DnsProbeResult(name, emptyList(), emptyList(), ProbeTimeouts.DNS_TIMEOUT_MS, "timeout")
        }

    // -----------------------------------------------------------------------
    // 2. DoT — pinned secure-DNS server (no SNI, SAN-validated)
    // -----------------------------------------------------------------------

    /**
     * Resolver 2 — DNS-over-TLS to our pinned secure-DNS server via
     * [SecureDnsClient.dotLookup]. Connects by literal IP with no SNI (the
     * server name is never DNS-resolved, never sent as SNI, and never appears
     * in this report — only used internally for SAN validation), so it works
     * even when the system DNS / SNI is being interfered with.
     */
    suspend fun dotF1Tech(host: String): DnsProbeResult =
        withContext(Dispatchers.IO) {
            val name = DnsResolverNames.DOT_F1TECH
            val t0 = System.currentTimeMillis()
            withTimeoutOrNull(ProbeTimeouts.DNS_TIMEOUT_MS) {
                try {
                    val addrs = SecureDnsClient.dotLookup(host, ProbeTimeouts.DNS_TIMEOUT_MS.toInt())
                    val ms = System.currentTimeMillis() - t0
                    val aRecords = addrs.filter { it is java.net.Inet4Address }.map { it.hostAddress ?: "" }
                    val aaaaRecords = addrs.filter { it is java.net.Inet6Address }.map { it.hostAddress ?: "" }
                    DnsProbeResult(name, aRecords, aaaaRecords, ms, null)
                } catch (e: Exception) {
                    val ms = System.currentTimeMillis() - t0
                    logger.debug("DoT probe failed for {}: {}", host, e.message)
                    DnsProbeResult(name, emptyList(), emptyList(), ms, e.message ?: e.javaClass.simpleName)
                }
            } ?: DnsProbeResult(name, emptyList(), emptyList(), ProbeTimeouts.DNS_TIMEOUT_MS, "timeout")
        }

    // -----------------------------------------------------------------------
    // 3. DoH — Cloudflare 1.1.1.1 (literal IP URL — no DNS needed)
    // -----------------------------------------------------------------------

    suspend fun dohCloudflare(host: String, baseClient: OkHttpClient): DnsProbeResult =
        dohResolve(
            resolverName = DnsResolverNames.DOH_CF,
            host = host,
            url = "https://1.1.1.1/dns-query",
            bootstrapIp = "1.1.1.1",
            baseClient = baseClient,
        )

    // -----------------------------------------------------------------------
    // 4. DoH — Google 8.8.8.8 (literal IP URL)
    // -----------------------------------------------------------------------

    suspend fun dohGoogle(host: String, baseClient: OkHttpClient): DnsProbeResult =
        dohResolve(
            resolverName = DnsResolverNames.DOH_GOOGLE,
            host = host,
            url = "https://8.8.8.8/dns-query",
            bootstrapIp = "8.8.8.8",
            baseClient = baseClient,
        )

    // -----------------------------------------------------------------------
    // 5. DoH — Cloudflare Family 1.1.1.3 (literal IP URL)
    // -----------------------------------------------------------------------

    suspend fun dohCloudflarFamily(host: String, baseClient: OkHttpClient): DnsProbeResult =
        dohResolve(
            resolverName = DnsResolverNames.DOH_CF_FAMILY,
            host = host,
            url = "https://1.1.1.3/dns-query",
            bootstrapIp = "1.1.1.3",
            baseClient = baseClient,
        )

    // -----------------------------------------------------------------------
    // Shared DoH helper
    // -----------------------------------------------------------------------

    private suspend fun dohResolve(
        resolverName: String,
        host: String,
        url: String,
        bootstrapIp: String,
        baseClient: OkHttpClient,
    ): DnsProbeResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        withTimeoutOrNull(ProbeTimeouts.DNS_TIMEOUT_MS) {
            try {
                // Dedicated call-timeout client: the base client's own connect/read/write timeouts
                // can add up to well past DNS_TIMEOUT_MS, so withTimeoutOrNull's cancellation would
                // race a blocking okhttp call that ignores it. A callTimeout caps the whole exchange
                // at the same ceiling we report, keeping the reported latency honest.
                val timedClient = baseClient.newBuilder()
                    .callTimeout(ProbeTimeouts.DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build()
                val bootstrapAddress = InetAddress.getByName(bootstrapIp)
                val doh = DnsOverHttps.Builder()
                    .client(timedClient)
                    .url(url.toHttpUrl())
                    .bootstrapDnsHosts(bootstrapAddress)
                    .build()
                val addrs = doh.lookup(host)
                val ms = System.currentTimeMillis() - t0
                val aRecords = addrs.filter { it is java.net.Inet4Address }.map { it.hostAddress ?: "" }
                val aaaaRecords = addrs.filter { it is java.net.Inet6Address }.map { it.hostAddress ?: "" }
                DnsProbeResult(resolverName, aRecords, aaaaRecords, ms, null)
            } catch (e: Exception) {
                val ms = System.currentTimeMillis() - t0
                logger.debug("{} failed for {}: {}", resolverName, host, e.message)
                DnsProbeResult(resolverName, emptyList(), emptyList(), ms, e.message ?: e.javaClass.simpleName)
            }
        } ?: DnsProbeResult(resolverName, emptyList(), emptyList(), ProbeTimeouts.DNS_TIMEOUT_MS, "timeout")
    }
}
