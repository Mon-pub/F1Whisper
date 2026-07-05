package ch.threema.app.diagnostics

import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.onprem.OnPremConfig
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private val logger = getThreemaLogger("ConnectivityProbeUseCase")

/**
 * F1Whisper connectivity troubleshooter — probe engine.
 *
 * Runs a fixed battery of network probes against [ProbeTarget] and returns a
 * [ProbeReport].  **DIAGNOSIS ONLY** — this class must never implement
 * circumvention logic (no DoH swap in production, no SNI fronting, etc.).
 *
 * Every probe is wrapped in [withTimeoutOrNull] and catches all [Throwable]s so
 * the engine NEVER throws.  Partial failures degrade gracefully to error fields
 * in the result rather than aborting the whole run.
 *
 * @param unpinnedOkHttpClient  An OkHttpClient that does NOT do certificate
 *   pinning — used for HTTPS probes so they exercise the path a blocked user
 *   sees (our pinned client would reject a MITM cert and mask a DNS-poisoning
 *   scenario differently from what the user experiences).
 */
class ConnectivityProbeUseCase(
    private val unpinnedOkHttpClient: OkHttpClient,
) {
    /**
     * Run all probes against [target] and return a complete [ProbeReport].
     *
     * This is a **suspend** function and must be called from a coroutine.
     * It manages its own dispatcher (IO-bound) internally.
     *
     * @param target       Describes the host(s) to probe.
     * @param cachedConfig The parsed OPPF, if already available (post-registration
     *   or from the Advanced Options entry point).  Provides real chat/dir/blob
     *   hostnames; when null the engine derives them from [target.host].
     */
    suspend fun call(
        target: ProbeTarget,
        cachedConfig: OnPremConfig? = null,
    ): ProbeReport = withContext(Dispatchers.IO) {
        val startedAt = Instant.now()
        val startMs  = System.currentTimeMillis()

        // Derive ancillary hosts from the OPPF (preferred) or from the target domain.
        val chatHost = cachedConfig?.chat?.hostname
            ?: target.chatHost
            ?: "chat.${target.host}"
        val dirHost  = cachedConfig?.directory?.url
            ?.let { extractHost(it) }
            ?: target.dirHost
            ?: "dir.${target.host}"
        val blobHost = cachedConfig?.blob?.downloadUrl?.toString()
            ?.let { extractHost(it) }
            ?: target.blobHost

        // ---------------------------------------------------------------
        // Phase 1: DNS matrix — run all 5 resolvers concurrently
        // ---------------------------------------------------------------
        val dnsResults: List<DnsProbeResult> = coroutineScope {
            listOf(
                async { DnsResolvers.system(target.host) },
                async { DnsResolvers.dotF1Tech(target.host) },
                async { DnsResolvers.dohCloudflare(target.host, unpinnedOkHttpClient) },
                async { DnsResolvers.dohGoogle(target.host, unpinnedOkHttpClient) },
                async { DnsResolvers.dohCloudflarFamily(target.host, unpinnedOkHttpClient) },
            ).map { it.await() }
        }

        // ---------------------------------------------------------------
        // Phase 2: Reachability probes — run concurrently
        // ---------------------------------------------------------------
        val probes: List<ProbeResult> = coroutineScope {
            buildList {
                // TCP 443 to the primary host
                add(async { probeTcp(target.host, 443, "TCP:443") })

                // TLS+SNI: our host vs shop vs neutral comparison
                add(async { probeTlsSni(target.host, 443, target.host, "TLS:host-SNI") })
                add(async { probeTlsSni(target.host, 443, ProbePins.SHOP_SNI_HOST, "TLS:shop-SNI") })
                add(async { probeTlsSni(target.host, 443, ProbePins.NEUTRAL_SNI_HOST, "TLS:neutral-SNI", treatCertMismatchAsReached = true) })

                // HTTPS GET of the OPPF endpoint
                add(async { probeHttps(target.host, target.oppfPath, "HTTPS:${target.oppfPath}") })

                // Chat TCP 5222 + best-effort CSP hello read
                add(async { probeTcp(chatHost, 5222, "TCP-5222:chat") })
                add(async { probeCspHello(chatHost, "CSP-hello:chat") })

                // Directory TLS + HTTPS
                add(async { probeTlsSni(dirHost, 443, dirHost, "TLS:dir") })
                add(async { probeHttps(dirHost, "/", "HTTPS:dir-root") })

                // Blob TLS (skip if no blob host derivable)
                if (blobHost != null) {
                    add(async { probeTlsSni(blobHost, 443, blobHost, "TLS:blob") })
                }

                // Control — neutral internet check via literal IP (avoids DNS entirely)
                add(async { probeControl("HTTPS:control-1.1.1.1") })
            }.map { it.await() }
        }

        val durationMs = System.currentTimeMillis() - startMs

        val verdict = computeVerdict(dnsResults, probes)
        val hints = computeHints(dnsResults, probes)

        ProbeReport(
            target     = target,
            dns        = dnsResults,
            probes     = probes,
            verdict    = verdict,
            hints      = hints,
            startedAt  = startedAt,
            durationMs = durationMs,
        )
    }

    // -----------------------------------------------------------------------
    // Reachability probe implementations
    // -----------------------------------------------------------------------

    private suspend fun probeTcp(host: String, port: Int, name: String): ProbeResult =
        withTimeoutOrNull(ProbeTimeouts.NET_TIMEOUT_MS) {
            val t0 = System.currentTimeMillis()
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), ProbeTimeouts.NET_TIMEOUT_MS.toInt())
                }
                val ms = System.currentTimeMillis() - t0
                ProbeResult(name, ok = true, detail = "connected (${ms}ms)", latencyMs = ms)
            } catch (e: Exception) {
                val ms = System.currentTimeMillis() - t0
                ProbeResult(name, ok = false, detail = errorType(e), latencyMs = ms, error = e.message)
            }
        } ?: ProbeResult(name, ok = false, detail = "timeout (${ProbeTimeouts.NET_TIMEOUT_MS}ms)", latencyMs = ProbeTimeouts.NET_TIMEOUT_MS)

    private suspend fun probeTlsSni(
        connectHost: String,
        port: Int,
        sniName: String,
        name: String,
        // For the neutral-SNI control only: we connect to OUR IP with a foreign SNI, so a valid cert
        // can never be presented — a TLS-layer error (SSLHandshakeException / cert mismatch) actually
        // PROVES the ClientHello reached the server and got a ServerHello, i.e. this SNI was NOT
        // network-blocked. Treat that as reached (ok). Only a connect refusal/timeout means blocked.
        treatCertMismatchAsReached: Boolean = false,
    ): ProbeResult = withTimeoutOrNull(ProbeTimeouts.NET_TIMEOUT_MS) {
        val t0 = System.currentTimeMillis()
        try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val raw = Socket()
            raw.connect(InetSocketAddress(connectHost, port), (ProbeTimeouts.NET_TIMEOUT_MS / 2).toInt())
            val ssl = factory.createSocket(raw, sniName, port, true) as SSLSocket
            ssl.use {
                it.startHandshake()
                val cert = it.session.peerCertificates.firstOrNull() as? X509Certificate
                val ms = System.currentTimeMillis() - t0
                val subject = cert?.subjectX500Principal?.name ?: "no-cert"
                val issuer  = cert?.issuerX500Principal?.name?.substringBefore(",") ?: "?"
                ProbeResult(name, ok = true, detail = "handshake ok; subject=$subject issuer=$issuer (${ms}ms)", latencyMs = ms)
            }
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t0
            if (treatCertMismatchAsReached && (e is SSLHandshakeException || e is CertificateException)) {
                ProbeResult(name, ok = true, detail = "SNI reached server; cert mismatch expected (${ms}ms)", latencyMs = ms)
            } else {
                ProbeResult(name, ok = false, detail = errorType(e), latencyMs = ms, error = e.message)
            }
        }
    } ?: ProbeResult(name, ok = false, detail = "timeout (${ProbeTimeouts.NET_TIMEOUT_MS}ms)", latencyMs = ProbeTimeouts.NET_TIMEOUT_MS)

    private suspend fun probeHttps(host: String, path: String, name: String): ProbeResult =
        withTimeoutOrNull(ProbeTimeouts.NET_TIMEOUT_MS) {
            val t0 = System.currentTimeMillis()
            try {
                val url = if (path.startsWith("/")) "https://$host$path" else "https://$host/$path"
                val req = Request.Builder().url(url).head().build()
                unpinnedOkHttpClient.newCall(req).execute().use { resp ->
                    val ms = System.currentTimeMillis() - t0
                    ProbeResult(name, ok = resp.isSuccessful || resp.code in 300..499,
                        detail = "HTTP ${resp.code} (${ms}ms)", latencyMs = ms)
                }
            } catch (e: Exception) {
                val ms = System.currentTimeMillis() - t0
                ProbeResult(name, ok = false, detail = errorType(e), latencyMs = ms, error = e.message)
            }
        } ?: ProbeResult(name, ok = false, detail = "timeout (${ProbeTimeouts.NET_TIMEOUT_MS}ms)", latencyMs = ProbeTimeouts.NET_TIMEOUT_MS)

    /**
     * Best-effort: connect TCP-5222 to [chatHost], write the 48-byte CSP
     * client-hello stub, and read whether the server sends any bytes back.
     * Wrapped in [withTimeoutOrNull]; never throws.
     */
    private suspend fun probeCspHello(chatHost: String, name: String): ProbeResult =
        withTimeoutOrNull(ProbeTimeouts.NET_TIMEOUT_MS) {
            val t0 = System.currentTimeMillis()
            try {
                Socket().use { s ->
                    s.soTimeout = ProbeTimeouts.NET_TIMEOUT_MS.toInt()
                    s.connect(InetSocketAddress(chatHost, 5222), (ProbeTimeouts.NET_TIMEOUT_MS / 2).toInt())
                    // 48-byte stub clientHello (random; real CSP requires the Curve25519 handshake,
                    // but even random bytes let us detect whether the server replies at all vs silence).
                    val hello = ByteArray(ProbePins.CSP_HELLO_BYTES) { it.toByte() }
                    s.getOutputStream().write(hello)
                    s.getOutputStream().flush()
                    val buf = ByteArray(16)
                    val read = try { s.getInputStream().read(buf) } catch (_: Exception) { -1 }
                    val ms = System.currentTimeMillis() - t0
                    if (read > 0) {
                        ProbeResult(name, ok = true, detail = "server replied $read bytes (${ms}ms)", latencyMs = ms)
                    } else {
                        ProbeResult(name, ok = false, detail = "connected but no reply (${ms}ms)", latencyMs = ms)
                    }
                }
            } catch (e: Exception) {
                val ms = System.currentTimeMillis() - t0
                ProbeResult(name, ok = false, detail = errorType(e), latencyMs = ms, error = e.message)
            }
        } ?: ProbeResult(name, ok = false, detail = "timeout (${ProbeTimeouts.NET_TIMEOUT_MS}ms)", latencyMs = ProbeTimeouts.NET_TIMEOUT_MS)

    private suspend fun probeControl(name: String): ProbeResult =
        withTimeoutOrNull(ProbeTimeouts.NET_TIMEOUT_MS) {
            val t0 = System.currentTimeMillis()
            // Try several well-known literal IPs (DNS-independent). Internet is "up" if ANY responds —
            // a single blocked control IP (e.g. some networks drop 1.1.1.1) must not read as no-internet.
            var lastError: String? = null
            for (ip in ProbePins.CONTROL_IPS) {
                try {
                    val req = Request.Builder().url("https://$ip/").head().build()
                    unpinnedOkHttpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful || resp.code in 300..499) {
                            val ms = System.currentTimeMillis() - t0
                            return@withTimeoutOrNull ProbeResult(name, ok = true, detail = "HTTP ${resp.code} via $ip (${ms}ms)", latencyMs = ms)
                        }
                        lastError = "HTTP ${resp.code} via $ip"
                    }
                } catch (e: Exception) {
                    lastError = "${errorType(e)} via $ip"
                }
            }
            val ms = System.currentTimeMillis() - t0
            ProbeResult(name, ok = false, detail = "no control host reachable (${ms}ms)", latencyMs = ms, error = lastError)
        } ?: ProbeResult(name, ok = false, detail = "timeout (${ProbeTimeouts.NET_TIMEOUT_MS}ms)", latencyMs = ProbeTimeouts.NET_TIMEOUT_MS)

    // -----------------------------------------------------------------------
    // Verdict heuristic
    // -----------------------------------------------------------------------

    internal fun computeVerdict(
        dns: List<DnsProbeResult>,
        probes: List<ProbeResult>,
    ): Verdict {
        val control   = probes.find { it.name.startsWith("HTTPS:control") }
        val tcp443    = probes.find { it.name == "TCP:443" }
        val tlsHost   = probes.find { it.name == "TLS:host-SNI" }
        val tlsShop   = probes.find { it.name == "TLS:shop-SNI" }
        val tcp5222   = probes.find { it.name == "TCP-5222:chat" }
        val cspHello  = probes.find { it.name == "CSP-hello:chat" }
        // The OPPF HTTPS probe (its name starts with "HTTPS:/…"); NOT "HTTPS:dir-root"/"HTTPS:control".
        val oppfHttps = probes.find { it.name.startsWith("HTTPS:/") }

        // 0. What actually matters: can the app reach OUR server? HTTPS to the OPPF host answers (any
        // HTTP response incl. 401/404 = the server replied) AND the chat port is open. If so the
        // connection is healthy — ALL_OK regardless of external controls. A blocked 1.1.1.1 or the
        // expected cert-mismatch on the neutral-SNI probe must NOT downgrade a working connection.
        val serverHttpsOk = tcp443?.ok == true && tlsHost?.ok == true && oppfHttps?.ok == true
        val chatOk        = tcp5222?.ok == true
        if (serverHttpsOk && chatOk) {
            // Reachable, but is it usable? A network that severely throttles/shapes traffic can
            // still pass every probe while taking seconds per round-trip — long enough that the
            // real app will hit its own timeouts. Surface that distinctly instead of a plain
            // ALL_OK, which would tell a still-struggling user "nothing is wrong here".
            val isSlow = (tlsHost?.latencyMs ?: 0) > SLOW_TLS_THRESHOLD_MS ||
                (oppfHttps?.latencyMs ?: 0) > SLOW_TLS_THRESHOLD_MS ||
                (cspHello?.latencyMs ?: 0) > SLOW_CSP_THRESHOLD_MS ||
                (control?.latencyMs ?: 0) > SLOW_CSP_THRESHOLD_MS
            return if (isSlow) Verdict.SLOW_THROTTLED else Verdict.ALL_OK
        }

        // Is the internet up at all? The control probe OR any EXTERNAL DoH/DoT resolver succeeding
        // proves it (those reach 1.1.1.1 / 8.8.8.8 / our pinned DoT server over 443/853).
        val internetUp = control?.ok == true ||
            dns.any { it.resolverName != DnsResolverNames.SYSTEM && it.succeeded }

        // 1. No internet: neither an external control nor our own 443 is reachable.
        if (!internetUp && tcp443?.ok != true) return Verdict.NO_INTERNET

        // 2. DNS poisoning: system DNS returns IPs disjoint from the DoH/DoT consensus.
        val systemIps = dns.find { it.resolverName == DnsResolverNames.SYSTEM }
            ?.allIps?.toSet().orEmpty()
        val dohConsensusIps = dns
            .filter { it.resolverName != DnsResolverNames.SYSTEM && it.succeeded }
            .flatMap { it.allIps }
            .toSet()
        if (systemIps.isNotEmpty() && dohConsensusIps.isNotEmpty() &&
            systemIps.intersect(dohConsensusIps).isEmpty()
        ) {
            return Verdict.DNS_POISONING_SUSPECTED
        }

        // 3. SNI blocking: 443 reachable and the shop SNI handshakes but OUR host SNI fails.
        if (tcp443?.ok == true && tlsShop?.ok == true && tlsHost?.ok == false) {
            return Verdict.SNI_BLOCKING_SUSPECTED
        }

        // 4. IP/host blocked: our 443 fails while the internet is up.
        if (tcp443?.ok != true && internetUp) return Verdict.IP_HOST_BLOCKED

        // 5. Chat port blocked: HTTPS to the server works but chat 5222 is blocked.
        if (serverHttpsOk && !chatOk) return Verdict.CHAT_PORT_BLOCKED

        // 6. Something else failed — judge ONLY probes that reflect the user's own connection health;
        // the neutral-SNI contrast and the external control are diagnostic aids, not health signals.
        val healthProbes = probes.filter {
            it.name != "TLS:neutral-SNI" && !it.name.startsWith("HTTPS:control")
        }
        val anyHealthFailed = healthProbes.any { !it.ok } || dns.none { it.succeeded }
        return if (anyHealthFailed) Verdict.PARTIAL_FAILURE else Verdict.ALL_OK
    }

    // -----------------------------------------------------------------------
    // Diagnostic hints (auxiliary signals, do not change the verdict)
    // -----------------------------------------------------------------------

    internal fun computeHints(
        dns: List<DnsProbeResult>,
        probes: List<ProbeResult>,
    ): List<DiagnosticHint> {
        val hints = mutableListOf<DiagnosticHint>()

        val tcp443  = probes.find { it.name == "TCP:443" }
        val tlsHost = probes.find { it.name == "TLS:host-SNI" }
        val control = probes.find { it.name.startsWith("HTTPS:control") }
        val systemDns = dns.find { it.resolverName == DnsResolverNames.SYSTEM }
        val dotDns    = dns.find { it.resolverName == DnsResolverNames.DOT_F1TECH }

        // Fast SYN + slow TLS data = the TCP connection is being answered (and likely terminated)
        // by something close by (a transparent proxy / middlebox), which then relays — and delays —
        // the actual TLS exchange to the real destination.
        if ((tcp443?.latencyMs ?: Long.MAX_VALUE) < MIDDLEBOX_FAST_CONNECT_MS &&
            (tlsHost?.latencyMs ?: 0) > MIDDLEBOX_SLOW_TLS_MS
        ) {
            hints += DiagnosticHint.MIDDLEBOX_TERMINATED
        }

        // Implausibly fast system DNS while our own encrypted resolver / control probe is slow:
        // the system resolver is answering locally (cached/hijacked at port 53) rather than the
        // query actually reaching an upstream authority.
        val encryptedIsSlow = (dotDns?.latencyMs ?: 0) > PORT53_SLOW_UPSTREAM_MS ||
            (control?.latencyMs ?: 0) > PORT53_SLOW_UPSTREAM_MS
        if ((systemDns?.latencyMs ?: Long.MAX_VALUE) < PORT53_FAST_LOCAL_MS && encryptedIsSlow) {
            hints += DiagnosticHint.PORT53_HIJACK
        }

        return hints
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Extract just the host portion from a full URL string. */
    private fun extractHost(url: String): String? = runCatching {
        java.net.URL(url).host.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Short human-readable exception classifier (no stack trace, no PII). */
    private fun errorType(e: Exception): String = when {
        e.message?.contains("ECONNREFUSED", ignoreCase = true) == true -> "RST/connection-refused"
        e.message?.contains("timeout", ignoreCase = true) == true      -> "timeout"
        e.message?.contains("No address", ignoreCase = true) == true   -> "DNS-failure"
        e.message?.contains("handshake", ignoreCase = true) == true    -> "TLS-handshake-failed"
        e.message?.contains("certificate", ignoreCase = true) == true  -> "TLS-cert-error"
        else -> e.javaClass.simpleName
    }

    private companion object {
        // SLOW_THROTTLED thresholds: the server is reachable (already established by the caller),
        // but round-trips this slow mean the app's own timeouts will routinely fire in practice.
        const val SLOW_TLS_THRESHOLD_MS = 3_000L
        const val SLOW_CSP_THRESHOLD_MS = 5_000L

        // middlebox-terminated hint thresholds.
        const val MIDDLEBOX_FAST_CONNECT_MS = 100L
        const val MIDDLEBOX_SLOW_TLS_MS     = 3_000L

        // port53-hijack hint thresholds.
        const val PORT53_FAST_LOCAL_MS      = 30L
        const val PORT53_SLOW_UPSTREAM_MS   = 3_000L
    }
}
