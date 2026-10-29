package ch.threema.app.net

import ch.threema.base.utils.getThreemaLogger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private val logger = getThreemaLogger("SecureDnsClient")

/**
 * DNS-over-TLS client that resolves hostnames via our pinned DoT server WITHOUT
 * ever leaking the server's own name.
 *
 * The server ([EncodedEndpoints.pinnedIp]) is connected to by literal IP, so the
 * server hostname is never itself DNS-resolved and never sent as TLS SNI. The
 * server presents its Let's Encrypt certificate as the default (no-SNI) vhost;
 * after the standard chain validation we manually verify the certificate's SANs
 * cover [EncodedEndpoints.dnsHost].
 *
 * Blocking; callers wrap in an IO dispatcher.
 */
object SecureDnsClient {

    private const val DNS_A_TXID: Short = 0x1234
    private const val DNS_AAAA_TXID: Short = 0x5678

    /**
     * Resolve [host] over DoT against the pinned server. Returns the combined,
     * de-duplicated A + AAAA addresses (may be empty if the server has no records
     * for the name). Throws only on connect / TLS / certificate-validation
     * failure; "no records" is NOT an error.
     */
    fun dotLookup(host: String, timeoutMs: Int): List<InetAddress> {
        val ip = EncodedEndpoints.pinnedIp()
        val port = EncodedEndpoints.DOT_PORT

        val raw = Socket()
        try {
            // Literal IP => no DNS lookup happens here; we never resolve dnsHost().
            raw.connect(InetSocketAddress(ip, port), timeoutMs)
            // withTimeoutOrNull-style cancellation cannot interrupt a blocked JVM readFully; the
            // soTimeout guarantees a server that completes the handshake but never answers can't hang.
            raw.soTimeout = timeoutMs

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            // Passing the literal IP as the peer host keeps SSLSocket from deriving an SNI hostname
            // (an IP is not a valid SNI); we also clear serverNames explicitly below.
            val ssl = factory.createSocket(raw, ip, port, true) as SSLSocket
            ssl.use {
                // Explicitly send NO SNI — the server serves the pinned host's cert on the default vhost.
                val params = it.sslParameters
                params.serverNames = emptyList()
                it.sslParameters = params

                // The default SSLSocket validates the cert CHAIN against the system trust store
                // (an untrusted / MITM chain throws here). It does NOT verify the hostname, so we
                // do the SAN check ourselves against the pinned server name after the handshake.
                it.startHandshake()

                val peer = it.session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: throw SSLPeerUnverifiedException("No peer certificate presented")
                if (!certMatchesHost(peer, EncodedEndpoints.dnsHost())) {
                    throw SSLPeerUnverifiedException("Pinned DoT cert SAN mismatch")
                }

                val out = DataOutputStream(it.getOutputStream().buffered())
                val din = DataInputStream(it.getInputStream().buffered())

                // A query.
                DnsWire.writeDotMessage(out, DnsWire.buildAQuery(host, txId = DNS_A_TXID))
                val aResponse = DnsWire.readDotMessage(din) ?: ByteArray(0)
                val aRecords = DnsWire.parseARecords(aResponse)

                // AAAA query on the same connection.
                DnsWire.writeDotMessage(out, DnsWire.buildAaaaQuery(host, txId = DNS_AAAA_TXID))
                val aaaaResponse = DnsWire.readDotMessage(din) ?: ByteArray(0)
                val aaaaRecords = DnsWire.parseAaaaRecords(aaaaResponse)

                return toInetAddresses(aRecords + aaaaRecords)
            }
        } finally {
            try {
                raw.close()
            } catch (e: Exception) {
                logger.debug("Failed to close DoT socket: {}", e.message)
            }
        }
    }

    /**
     * Convert parsed literal IP strings to [InetAddress]es. Each string is a
     * literal IP, so [InetAddress.getByName] performs no DNS lookup. De-dups and
     * silently drops any unparseable entry.
     */
    private fun toInetAddresses(ipStrings: List<String>): List<InetAddress> {
        val result = LinkedHashMap<String, InetAddress>()
        for (ip in ipStrings) {
            if (ip.isEmpty() || result.containsKey(ip)) {
                continue
            }
            try {
                result[ip] = InetAddress.getByName(ip)
            } catch (e: Exception) {
                logger.debug("Skipping unparseable DoT record '{}': {}", ip, e.message)
            }
        }
        return result.values.toList()
    }

    /**
     * True iff [cert] carries a dNSName SAN (type 2) equal (case-insensitively)
     * to [host]. Pure and testable; no wildcard matching (the pinned server name
     * is an exact FQDN).
     */
    internal fun certMatchesHost(cert: X509Certificate, host: String): Boolean {
        val sans = try {
            cert.subjectAlternativeNames
        } catch (e: Exception) {
            logger.debug("Failed to read SANs: {}", e.message)
            null
        } ?: return false

        for (san in sans) {
            // Each entry is a 2-element list: [type, value]. Type 2 == dNSName.
            val type = san.getOrNull(0) as? Int ?: continue
            if (type != 2) {
                continue
            }
            val name = san.getOrNull(1) as? String ?: continue
            if (name.equals(host, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
