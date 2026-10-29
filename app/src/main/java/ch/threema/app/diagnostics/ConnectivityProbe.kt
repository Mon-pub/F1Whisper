package ch.threema.app.diagnostics

import java.time.Instant

// ---------------------------------------------------------------------------
// ConnectivityProbe.kt — shared data contract for the F1Whisper connectivity
// troubleshooter (DIAGNOSIS ONLY — no circumvention logic here).
//
// Both the probe engine (ConnectivityProbeUseCase) and the UI/dialog
// (ConnectivityDiagnosticsDialog) depend on this file as their sole shared
// contract.  Nothing in this file performs I/O.
// ---------------------------------------------------------------------------

/**
 * The host + port (+ optional path) to probe.
 *
 * @param host        Bare hostname (e.g. "example.com").
 * @param port        TCP port for reachability / TLS probes (default 443).
 * @param oppfPath    Relative path of the OnPrem config endpoint (default "/prov/config.oppf").
 * @param chatHost    Derived chat hostname for TCP-5222 + CSP probes; null = derive as "chat.<host>".
 * @param dirHost     Directory hostname for TLS/HTTPS probe; null = derive from host.
 * @param blobHost    Blob hostname for TLS probe; null = skip.
 */
data class ProbeTarget(
    val host: String,
    val port: Int = 443,
    val oppfPath: String = "/prov/config.oppf",
    val chatHost: String? = null,
    val dirHost: String? = null,
    val blobHost: String? = null,
)

// ---------------------------------------------------------------------------
// DNS results
// ---------------------------------------------------------------------------

/**
 * Outcome of one DNS resolver against [host].
 *
 * @param resolverName  Human-readable label (e.g. "system", "DoT:private-dns", "DoH:1.1.1.1").
 * @param aRecords      IPv4 addresses resolved (empty on failure).
 * @param aaaaRecords   IPv6 addresses resolved (empty on failure).
 * @param latencyMs     Wall-clock milliseconds for the query (0 on failure).
 * @param error         Exception message on failure; null on success.
 */
data class DnsProbeResult(
    val resolverName: String,
    val aRecords: List<String>,
    val aaaaRecords: List<String>,
    val latencyMs: Long,
    val error: String?,
) {
    val succeeded: Boolean get() = error == null && (aRecords.isNotEmpty() || aaaaRecords.isNotEmpty())

    /** All returned IP strings (A + AAAA) in insertion order. */
    val allIps: List<String> get() = aRecords + aaaaRecords
}

// ---------------------------------------------------------------------------
// Reachability / TLS / HTTPS / chat probe results
// ---------------------------------------------------------------------------

/**
 * Outcome of a single non-DNS probe (TCP, TLS+SNI, HTTPS GET, chat port, control).
 *
 * @param name      Short label (e.g. "TCP:443", "TLS:thm", "HTTPS:/prov/config.oppf").
 * @param ok        True when the probe reached its success condition.
 * @param detail    Human-readable outcome string (status code, cert subject, bytes, error type).
 * @param latencyMs Wall-clock milliseconds; 0 on immediate failure.
 * @param error     Exception message on failure; null on success.
 */
data class ProbeResult(
    val name: String,
    val ok: Boolean,
    val detail: String,
    val latencyMs: Long,
    val error: String? = null,
)

// ---------------------------------------------------------------------------
// Verdict
// ---------------------------------------------------------------------------

/**
 * Heuristic verdict rolled up from all probe results.
 *
 * The verdict is for human display only — the raw probe data in [ProbeReport]
 * is the ground truth for developer analysis.
 */
enum class Verdict {
    /** All probes green — the server is reachable from this network. */
    ALL_OK,

    /** Control 443 fails — this network has no working internet. */
    NO_INTERNET,

    /** System DNS returns a different IP than DoH/DoT resolvers. */
    DNS_POISONING_SUSPECTED,

    /** DNS agrees but TLS with host SNI fails while TLS with shop SNI succeeds. */
    SNI_BLOCKING_SUSPECTED,

    /** DNS agrees, but TCP 443 is RST/timeout while control 443 succeeds. */
    IP_HOST_BLOCKED,

    /** TCP 443 / TLS OK, but TCP 5222 (chat port) is blocked. */
    CHAT_PORT_BLOCKED,

    /** Server is reachable but latencies are so high that connections routinely time out —
     *  distinct from ALL_OK (healthy) and PARTIAL_FAILURE (unclear evidence): here the evidence is
     *  clear, the network is just severely slow/throttled (e.g. traffic shaping, a transparent
     *  proxy, or port-53 interception forcing every real query onto a slow path). */
    SLOW_THROTTLED,

    /** Some probes failed but evidence is insufficient for a specific verdict. */
    PARTIAL_FAILURE,
}

/**
 * Auxiliary diagnostic signals appended alongside the [Verdict] — narrower observations that
 * don't each merit their own verdict but are useful context for a report reader (e.g. "this looks
 * like a transparent proxy", not just "it's slow").
 */
enum class DiagnosticHint {
    /** TCP:443 connects fast but the TLS handshake with our host SNI is slow — consistent with a
     *  transparent proxy / middlebox terminating the TCP connection immediately and only then
     *  proxying (and delaying) the TLS data. */
    MIDDLEBOX_TERMINATED,

    /** The system resolver answers implausibly fast while the DoT/control probes are slow —
     *  consistent with a local/ISP resolver on port 53 answering out of a cache or intercepting the
     *  query, rather than the query reaching the real upstream. */
    PORT53_HIJACK,
}

// ---------------------------------------------------------------------------
// Full report
// ---------------------------------------------------------------------------

/**
 * Complete output of one [ConnectivityProbeUseCase.call] run.
 *
 * @param target     The target that was probed.
 * @param dns        One entry per resolver in the DNS matrix (always 5 entries, failures included).
 * @param probes     One entry per reachability probe, in the order they were collected.
 * @param verdict    Heuristic roll-up.
 * @param hints      Additional diagnostic signals that don't change the verdict but add context.
 * @param startedAt  Wall-clock UTC instant when probing began.
 * @param durationMs Total wall-clock milliseconds (end − start).
 */
data class ProbeReport(
    val target: ProbeTarget,
    val dns: List<DnsProbeResult>,
    val probes: List<ProbeResult>,
    val verdict: Verdict,
    val hints: List<DiagnosticHint> = emptyList(),
    val startedAt: Instant,
    val durationMs: Long,
)

// ---------------------------------------------------------------------------
// Resolver identity constants (shared by engine and report writer)
// ---------------------------------------------------------------------------

/** Labels for the five DNS resolvers, in the order the engine runs them. */
object DnsResolverNames {
    const val SYSTEM = "system"
    const val DOT_F1TECH = "DoT:private-dns"
    const val DOH_CF = "DoH:1.1.1.1"
    const val DOH_GOOGLE = "DoH:8.8.8.8"
    const val DOH_CF_FAMILY = "DoH:1.1.1.3"
}

// ---------------------------------------------------------------------------
// Timeout constants (single source of truth for engine + tests)
// ---------------------------------------------------------------------------

object ProbeTimeouts {
    /** Maximum milliseconds per DNS query (across all resolvers). Relaxed generously: on a
     *  severely throttled/censored network a real query can take several seconds even when it
     *  will eventually succeed, and a too-tight timeout misreports "blocked" as the verdict for
     *  what is actually "slow". Accuracy takes priority over a snappy dialog here. */
    const val DNS_TIMEOUT_MS: Long = 15_000L

    /** Maximum milliseconds per TCP / TLS / HTTPS / chat probe. Same reasoning as [DNS_TIMEOUT_MS]. */
    const val NET_TIMEOUT_MS: Long = 25_000L
}

// ---------------------------------------------------------------------------
// Pinned resolver addresses
// ---------------------------------------------------------------------------

object ProbePins {
    /** Neutral third host for SNI comparison ("is it just our SNI that's blocked?"). */
    const val NEUTRAL_SNI_HOST = "cloudflare.com"

    /** Subdomain prefix for the shop-SNI comparison probe. Combined with the configured server
     *  host at the call site (e.g. host "example.org" -> "shop.example.org") so the server host is
     *  never hardcoded here. */
    const val SHOP_SNI_PREFIX = "shop."

    /** 48-byte CSP client-hello magic (first 16 bytes of our test payload — padded to 48). */
    const val CSP_HELLO_BYTES = 48

    /**
     * Well-known literal IPs (DNS-independent) that answer HTTPS on 443, tried in order as the
     * "is the internet up at all?" control. Multiple hosts because some networks block a single one
     * (e.g. 1.1.1.1 is dropped on some ISPs) — internet is "up" if ANY of them responds.
     */
    val CONTROL_IPS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
}
