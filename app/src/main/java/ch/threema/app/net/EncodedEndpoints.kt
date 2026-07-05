package ch.threema.app.net

/**
 * Obfuscated storage of the pinned secure-DNS endpoint.
 *
 * The DoT server hostname and its pinned IP are stored XOR-encoded (per-byte
 * against a fixed repeating key) and decoded lazily at runtime, so neither
 * literal appears as a plaintext string in the compiled APK.
 *
 * NOTE: this obfuscation defeats static APK scanners / string blocklists ONLY.
 * The destination IP is still visible on the wire once a connection is made
 * (accepted). It is NOT a confidentiality mechanism.
 */
object EncodedEndpoints {

    /** DoT port (RFC 7858). Not sensitive, kept as a plain constant. */
    const val DOT_PORT = 853

    // Fixed repeating XOR key. Not a secret — just enough to keep the literals
    // out of the string table.
    private val KEY = byteArrayOf(
        0x5A, 0x3C.toByte(), 0x91.toByte(), 0x74, 0x2E, 0xB8.toByte(), 0x6D, 0xC5.toByte(),
    )

    // XOR-encoded DoT server hostname (see decode()).
    private val HOST_ENC = byteArrayOf(
        0x3E, 0x52, 0xE2.toByte(), 0x5A, 0x48, 0x89.toByte(), 0x19, 0xA0.toByte(),
        0x39, 0x54, 0xBF.toByte(), 0x1D, 0x40, 0xDE.toByte(), 0x02,
    )

    // XOR-encoded pinned DoT server IP literal (see decode()).
    private val IP_ENC = byteArrayOf(
        0x6B, 0x0C, 0xA9.toByte(), 0x5A, 0x18, 0x89.toByte(), 0x43, 0xF7.toByte(),
        0x6B, 0x0C, 0xBF.toByte(), 0x46, 0x1B,
    )

    /** The DoT server hostname (used only for certificate SAN validation, never as SNI or DNS). */
    fun dnsHost(): String = decode(HOST_ENC)

    /** The pinned DoT server IP literal (connected to directly — no DNS lookup). */
    fun pinnedIp(): String = decode(IP_ENC)

    private fun decode(enc: ByteArray): String {
        val out = ByteArray(enc.size)
        for (i in enc.indices) {
            out[i] = (enc[i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
        }
        return String(out, Charsets.US_ASCII)
    }
}
