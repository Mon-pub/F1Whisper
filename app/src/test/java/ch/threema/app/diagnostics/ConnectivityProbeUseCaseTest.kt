package ch.threema.app.diagnostics

import ch.threema.app.net.DnsWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient

/**
 * Unit tests for the connectivity probe data model and report writer.
 *
 * These tests run on the JVM (no Android runtime needed) and verify:
 *  - [DnsWire] encode/decode round-trip
 *  - [ProbeReport] shape invariants
 *  - [ConnectivityProbeReportWriter] renders without throwing
 *  - Verdict heuristic logic (via synthetic probe result lists)
 *
 * Network I/O tests are intentionally excluded from this class — they would
 * require a live network and would be flaky in CI.  The engine's coroutine
 * concurrency and timeout behaviour are validated at integration-test time.
 */
class ConnectivityProbeUseCaseTest {

    // -----------------------------------------------------------------------
    // DnsWire encode / decode
    // -----------------------------------------------------------------------

    @Test
    fun `DnsWire buildAQuery produces valid DNS header`() {
        val query = DnsWire.buildAQuery("thm.f1tech.info", txId = 0x1234)
        // Must be at least 12 (header) + labels + 4 (qtype+qclass)
        assertTrue(query.size >= 16, "Query too short: ${query.size}")
        // First two bytes = txId
        assertEquals(0x12.toByte(), query[0])
        assertEquals(0x34.toByte(), query[1])
        // Byte 2/3 = flags QR=0, RD=1 → 0x01 0x00
        assertEquals(0x01.toByte(), query[2])
        assertEquals(0x00.toByte(), query[3])
        // QDCOUNT = 1
        assertEquals(0x00.toByte(), query[4])
        assertEquals(0x01.toByte(), query[5])
    }

    @Test
    fun `DnsWire buildAaaaQuery sets AAAA qtype`() {
        val query = DnsWire.buildAaaaQuery("example.com", txId = 0x5678)
        // QTYPE is in the last 4 bytes (before QCLASS): QTYPE_AAAA = 28 = 0x001C
        val qtypeOffset = query.size - 4
        assertEquals(0x00.toByte(), query[qtypeOffset])
        assertEquals(28.toByte(), query[qtypeOffset + 1])
    }

    @Test
    fun `DnsWire parseARecords returns empty list on empty input`() {
        val result = DnsWire.parseARecords(ByteArray(0))
        assertTrue(result.isEmpty(), "Expected empty list for empty input")
    }

    @Test
    fun `DnsWire parseARecords returns empty list on malformed response`() {
        // 4 bytes — too short to be a valid DNS message
        val result = DnsWire.parseARecords(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        assertTrue(result.isEmpty(), "Expected empty list for malformed input")
    }

    @Test
    fun `DnsWire DoT framing round-trip`() {
        // Create a fake DNS message, frame it, then unframe it
        val original = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val baos = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(baos)
        DnsWire.writeDotMessage(out, original)
        val framed = baos.toByteArray()
        // First 2 bytes must be the length
        assertEquals(0.toByte(), framed[0])
        assertEquals(original.size.toByte(), framed[1])

        val din = java.io.DataInputStream(java.io.ByteArrayInputStream(framed))
        val unframed = DnsWire.readDotMessage(din)
        assertNotNull(unframed)
        assertTrue(original.contentEquals(unframed), "Round-trip mismatch")
    }

    // -----------------------------------------------------------------------
    // Data model invariants
    // -----------------------------------------------------------------------

    @Test
    fun `DnsProbeResult succeeded requires non-empty IP list and null error`() {
        val ok  = DnsProbeResult("test", listOf("1.2.3.4"), emptyList(), 50L, null)
        val err = DnsProbeResult("test", emptyList(), emptyList(), 50L, "timeout")
        assertTrue(ok.succeeded)
        assertFalse(err.succeeded)
    }

    @Test
    fun `DnsProbeResult allIps concatenates A and AAAA`() {
        val r = DnsProbeResult("test", listOf("1.1.1.1"), listOf("2606:4700::1"), 10L, null)
        assertEquals(listOf("1.1.1.1", "2606:4700::1"), r.allIps)
    }

    @Test
    fun `ProbeTarget defaults are correct`() {
        val t = ProbeTarget("thm.f1tech.info")
        assertEquals(443, t.port)
        assertEquals("/prov/config.oppf", t.oppfPath)
    }

    // -----------------------------------------------------------------------
    // Report writer — smoke test (no network, synthetic report)
    // -----------------------------------------------------------------------

    @Test
    fun `ConnectivityProbeReportWriter renders without throwing`() {
        val report = syntheticReport(Verdict.ALL_OK)
        val text = ConnectivityProbeReportWriter.render(report)
        assertTrue(text.contains("connectivity probes"), "Missing section header")
        assertTrue(text.contains("dns matrix"),          "Missing DNS section")
        assertTrue(text.contains("reachability probes"), "Missing probes section")
        assertTrue(text.contains("verdict"),             "Missing verdict")
        assertTrue(text.contains("ALL_OK"),              "Missing verdict value")
    }

    @Test
    fun `ConnectivityProbeReportWriter renders DNS poisoning verdict`() {
        val report = syntheticReport(Verdict.DNS_POISONING_SUSPECTED)
        val text = ConnectivityProbeReportWriter.render(report)
        assertTrue(text.contains("DNS_POISONING_SUSPECTED"))
    }

    @Test
    fun `ConnectivityProbeReportWriter renders SNI blocking verdict`() {
        val text = ConnectivityProbeReportWriter.render(syntheticReport(Verdict.SNI_BLOCKING_SUSPECTED))
        assertTrue(text.contains("SNI_BLOCKING_SUSPECTED"))
    }

    @Test
    fun `ConnectivityProbeReportWriter includes all 5 DNS resolvers`() {
        val text = ConnectivityProbeReportWriter.render(syntheticReport(Verdict.ALL_OK))
        assertTrue(text.contains(DnsResolverNames.SYSTEM))
        assertTrue(text.contains(DnsResolverNames.DOT_F1TECH))
        assertTrue(text.contains(DnsResolverNames.DOH_CF))
        assertTrue(text.contains(DnsResolverNames.DOH_GOOGLE))
        assertTrue(text.contains(DnsResolverNames.DOH_CF_FAMILY))
    }

    @Test
    fun `DoT resolver label carries no private hostname or IP`() {
        // Hard requirement: the resolver label must never leak the private DoT host/IP.
        // Needles are assembled from fragments so this public-repo test carries no
        // contiguous plaintext of the private host/IP.
        assertFalse(DnsResolverNames.DOT_F1TECH.contains(listOf("f1tech", "info").joinToString(".")))
        assertFalse(DnsResolverNames.DOT_F1TECH.contains(listOf("108", "61", "210", "25").joinToString(".")))
    }

    // -----------------------------------------------------------------------
    // Probe constants
    // -----------------------------------------------------------------------

    @Test
    fun `ProbeTimeouts constants are within reasonable range`() {
        assertTrue(ProbeTimeouts.DNS_TIMEOUT_MS in 1_000..30_000)
        assertTrue(ProbeTimeouts.NET_TIMEOUT_MS in 1_000..60_000)
    }

    @Test
    fun `EncodedEndpoints DOT_PORT is 853`() {
        assertEquals(853, ch.threema.app.net.EncodedEndpoints.DOT_PORT)
    }

    // -----------------------------------------------------------------------
    // Verdict heuristic (regression tests for the field-report false verdicts)
    // -----------------------------------------------------------------------

    /** Regression: Phone 1 (Samsung) — server fully reachable but its network drops 1.1.1.1 control
     *  → previously false NO_INTERNET. Must be ALL_OK. */
    @Test
    fun `server reachable wins even when the control probe fails`() {
        val probes = healthyProbes(overrides = mapOf("HTTPS:control-1.1.1.1" to false))
        assertEquals(Verdict.ALL_OK, verdictOf(probes))
    }

    /** Regression: Phone 2 (Xiaomi) — everything healthy but the neutral-SNI probe failed
     *  → previously false PARTIAL_FAILURE. A reachable server must be ALL_OK. */
    @Test
    fun `neutral-SNI failure alone does not downgrade a healthy connection`() {
        val probes = healthyProbes(overrides = mapOf("TLS:neutral-SNI" to false))
        assertEquals(Verdict.ALL_OK, verdictOf(probes))
    }

    @Test
    fun `fully healthy connection is ALL_OK`() {
        assertEquals(Verdict.ALL_OK, verdictOf(healthyProbes()))
    }

    /** Server reachable, but round-trips are so slow the app would time out in practice — must be
     *  distinguished from a plain ALL_OK, not silently reported as healthy. */
    @Test
    fun `healthy but slow connection is SLOW_THROTTLED`() {
        val slowProbes = allProbeNames().map { name ->
            val latency = when (name) {
                "TLS:host-SNI", "HTTPS:/prov/config.oppf" -> 4_000L
                "CSP-hello:chat", "HTTPS:control-1.1.1.1" -> 6_000L
                else -> 10L
            }
            ProbeResult(name, ok = true, detail = "ok", latencyMs = latency)
        }
        assertEquals(Verdict.SLOW_THROTTLED, verdictOf(slowProbes))
    }

    /** Healthy AND fast must remain ALL_OK — SLOW_THROTTLED must not fire on a normal connection. */
    @Test
    fun `healthy and fast connection stays ALL_OK`() {
        assertEquals(Verdict.ALL_OK, verdictOf(healthyProbes()))
    }

    @Test
    fun `sni blocking detected when host SNI fails but shop SNI ok`() {
        val probes = healthyProbes(overrides = mapOf("TLS:host-SNI" to false, "HTTPS:/prov/config.oppf" to false))
        assertEquals(Verdict.SNI_BLOCKING_SUSPECTED, verdictOf(probes))
    }

    @Test
    fun `chat port blocked when https ok but 5222 fails`() {
        val probes = healthyProbes(overrides = mapOf("TCP-5222:chat" to false, "CSP-hello:chat" to false))
        assertEquals(Verdict.CHAT_PORT_BLOCKED, verdictOf(probes))
    }

    @Test
    fun `no internet when nothing is reachable`() {
        val deadDns = allResolverNames().map { DnsProbeResult(it, emptyList(), emptyList(), 8000L, "timeout") }
        val probes = healthyProbes(overrides = allProbeNames().associateWith { false })
        assertEquals(Verdict.NO_INTERNET, verdictOf(probes, deadDns))
    }

    @Test
    fun `dns poisoning detected when system DNS diverges from DoH consensus`() {
        val poisoned = buildList {
            add(DnsProbeResult(DnsResolverNames.SYSTEM, listOf("10.0.0.1"), emptyList(), 5L, null))
            allResolverNames().filter { it != DnsResolverNames.SYSTEM }.forEach { add(syntheticDnsResult(it)) }
        }
        // Server unreachable at the poisoned IP, but the internet is up (control ok).
        val probes = healthyProbes(overrides = allProbeNames().filter { it != "HTTPS:control-1.1.1.1" }.associateWith { false })
        assertEquals(Verdict.DNS_POISONING_SUSPECTED, verdictOf(probes, poisoned))
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun allResolverNames() = listOf(
        DnsResolverNames.SYSTEM, DnsResolverNames.DOT_F1TECH,
        DnsResolverNames.DOH_CF, DnsResolverNames.DOH_GOOGLE, DnsResolverNames.DOH_CF_FAMILY,
    )

    private fun allProbeNames() = listOf(
        "TCP:443", "TLS:host-SNI", "TLS:shop-SNI", "TLS:neutral-SNI", "HTTPS:/prov/config.oppf",
        "TCP-5222:chat", "CSP-hello:chat", "TLS:dir", "HTTPS:dir-root", "HTTPS:control-1.1.1.1",
    )

    /** A full healthy probe set; pass [overrides] to flip specific probes to failed. */
    private fun healthyProbes(overrides: Map<String, Boolean> = emptyMap()): List<ProbeResult> =
        allProbeNames().map { name ->
            val ok = overrides[name] ?: true
            ProbeResult(name, ok = ok, detail = if (ok) "ok" else "fail", latencyMs = 10L)
        }

    private fun healthyDns(): List<DnsProbeResult> = allResolverNames().map { syntheticDnsResult(it) }

    private fun verdictOf(probes: List<ProbeResult>, dns: List<DnsProbeResult> = healthyDns()): Verdict =
        ConnectivityProbeUseCase(OkHttpClient()).computeVerdict(dns, probes)

    private fun syntheticDnsResult(name: String, ip: String = "208.85.23.138"): DnsProbeResult =
        DnsProbeResult(name, listOf(ip), emptyList(), 42L, null)

    private fun syntheticReport(verdict: Verdict): ProbeReport {
        val dns = listOf(
            syntheticDnsResult(DnsResolverNames.SYSTEM),
            syntheticDnsResult(DnsResolverNames.DOT_F1TECH),
            syntheticDnsResult(DnsResolverNames.DOH_CF),
            syntheticDnsResult(DnsResolverNames.DOH_GOOGLE),
            syntheticDnsResult(DnsResolverNames.DOH_CF_FAMILY),
        )
        val probes = listOf(
            ProbeResult("TCP:443",             ok = true,  detail = "connected (50ms)",     latencyMs = 50),
            ProbeResult("TLS:host-SNI",        ok = true,  detail = "handshake ok",         latencyMs = 80),
            ProbeResult("TLS:shop-SNI",        ok = true,  detail = "handshake ok",         latencyMs = 80),
            ProbeResult("TLS:neutral-SNI",     ok = true,  detail = "handshake ok",         latencyMs = 80),
            ProbeResult("HTTPS:/prov/config.oppf", ok = true, detail = "HTTP 200 (100ms)", latencyMs = 100),
            ProbeResult("TCP-5222:chat",       ok = true,  detail = "connected (60ms)",     latencyMs = 60),
            ProbeResult("CSP-hello:chat",      ok = true,  detail = "server replied 16 bytes", latencyMs = 90),
            ProbeResult("TLS:dir",             ok = true,  detail = "handshake ok",         latencyMs = 75),
            ProbeResult("HTTPS:dir-root",      ok = true,  detail = "HTTP 200 (90ms)",      latencyMs = 90),
            ProbeResult("HTTPS:control-1.1.1.1", ok = true, detail = "HTTP 200 (30ms)",    latencyMs = 30),
        )
        return ProbeReport(
            target     = ProbeTarget("thm.f1tech.info"),
            dns        = dns,
            probes     = probes,
            verdict    = verdict,
            startedAt  = java.time.Instant.parse("2026-07-01T00:00:00Z"),
            durationMs = 500L,
        )
    }
}
