package ch.threema.app.diagnostics

/**
 * Renders a [ProbeReport] into the `section`/`kv` plain-text format used by
 * [ch.threema.app.usecases.ExportConnectionDiagnosticsUseCase].
 *
 * The output is appended as a `# connectivity probes` block at the end of the
 * same `connection_diagnostic.log` file — no new file, no ZIP restructuring.
 *
 * **No PII, no message content.** Only host names, IP addresses, HTTP status
 * codes, TLS subject/issuer strings, and latency values are included.
 */
object ConnectivityProbeReportWriter {

    private const val MAX_VALUE_CHARS = 512

    /**
     * Render the full [report] as a multi-section string block.
     *
     * The returned string is ready to be appended to an existing log file or
     * passed to [buildString] by the caller.
     */
    fun render(report: ProbeReport): String = buildString {
        section("connectivity probes") {
            kv("probe started") { report.startedAt }
            kv("duration ms") { report.durationMs }
            kv("target host") { report.target.host }
            kv("verdict") { report.verdict.name }
        }

        section("dns matrix") {
            for (dns in report.dns) {
                val value = if (dns.succeeded) {
                    "OK  A=${dns.aRecords}  AAAA=${dns.aaaaRecords}  ${dns.latencyMs}ms"
                } else {
                    "ERR ${dns.error}  ${dns.latencyMs}ms"
                }
                kv(dns.resolverName) { value }
            }
        }

        section("dns consensus check") {
            val systemIps = report.dns
                .find { it.resolverName == DnsResolverNames.SYSTEM }
                ?.allIps?.toSet().orEmpty()
            val dohIps = report.dns
                .filter { it.resolverName != DnsResolverNames.SYSTEM && it.succeeded }
                .flatMap { it.allIps }
                .toSet()
            kv("system IPs") { systemIps.joinToString(", ").ifEmpty { "(none)" } }
            kv("DoH/DoT consensus") { dohIps.joinToString(", ").ifEmpty { "(none)" } }
            val overlap = systemIps.intersect(dohIps)
            kv("overlap") { overlap.joinToString(", ").ifEmpty { "NONE — possible poisoning" } }
        }

        section("reachability probes") {
            for (probe in report.probes) {
                val status = if (probe.ok) "OK " else "ERR"
                kv(probe.name) { "$status  ${probe.detail}" }
            }
        }

        section("verdict detail") {
            kv("verdict") { report.verdict.name }
            kv("verdict meaning") { verdictExplanation(report.verdict) }
        }

        if (report.hints.isNotEmpty()) {
            section("diagnostic hints") {
                for (hint in report.hints) {
                    kv(hint.name) { hintExplanation(hint) }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers mirroring ExportConnectionDiagnosticsUseCase style
    // -----------------------------------------------------------------------

    private fun StringBuilder.kv(key: String, value: () -> Any?) {
        val rendered = try {
            value()?.toString() ?: "null"
        } catch (e: Throwable) {
            "n/a (${e.javaClass.simpleName})"
        }
        val capped = if (rendered.length > MAX_VALUE_CHARS) rendered.take(MAX_VALUE_CHARS) + "…" else rendered
        appendLine("$key:\t$capped")
    }

    private fun StringBuilder.section(title: String, block: StringBuilder.() -> Unit) {
        appendLine()
        appendLine("# $title")
        block()
    }

    private fun verdictExplanation(verdict: Verdict): String = when (verdict) {
        Verdict.ALL_OK -> "All probes passed. Server is reachable from this network."
        Verdict.NO_INTERNET -> "Control 443 probe failed. This network has no working internet."
        Verdict.DNS_POISONING_SUSPECTED -> "System DNS returns different IPs than DoH/DoT resolvers. DNS poisoning suspected."
        Verdict.SNI_BLOCKING_SUSPECTED -> "TCP/DNS OK but TLS with our SNI fails while shop SNI succeeds. SNI-based TLS blocking suspected."
        Verdict.IP_HOST_BLOCKED -> "DNS resolves correctly but TCP 443 is blocked. IP/host block or firewall."
        Verdict.CHAT_PORT_BLOCKED -> "HTTPS reachable but TCP 5222 (chat) is blocked. Chat-port censorship."
        Verdict.SLOW_THROTTLED -> "Server is reachable but latencies are severely high. Traffic shaping / throttling suspected."
        Verdict.PARTIAL_FAILURE -> "Some probes failed but evidence insufficient for a specific conclusion."
    }

    private fun hintExplanation(hint: DiagnosticHint): String = when (hint) {
        DiagnosticHint.MIDDLEBOX_TERMINATED ->
            "Fast TCP connect but slow TLS handshake. Consistent with a transparent proxy / middlebox terminating the connection."
        DiagnosticHint.PORT53_HIJACK ->
            "System DNS answered implausibly fast while encrypted resolvers were slow. Consistent with local port-53 interception."
    }
}
