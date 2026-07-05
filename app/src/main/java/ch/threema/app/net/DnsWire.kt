package ch.threema.app.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal DNS wire-format encoder and response decoder.
 *
 * Used by the DoT clients ([SecureDnsClient] and the diagnostics DoT resolver) —
 * DoH delegates the DNS wire format entirely to the `okhttp-dnsoverhttps` library.
 *
 * Implements the minimum subset of RFC 1035 needed to send an A or AAAA query
 * and parse the address records from the response, per RFC 7858 (DoT
 * transport = 2-byte big-endian length prefix before the DNS message).
 *
 * Nothing in this file does I/O.  Socket lifecycle is managed in DnsResolvers.
 */
object DnsWire {

    private const val QTYPE_A    = 1
    private const val QTYPE_AAAA = 28
    private const val QCLASS_IN  = 1

    // Header flags: QR=0 (query), OPCODE=0, RD=1
    private const val FLAGS_QUERY: Short = 0x0100

    // -----------------------------------------------------------------------
    // Query builders
    // -----------------------------------------------------------------------

    /** Encode a DNS A query for [host] with the given [txId]. */
    fun buildAQuery(host: String, txId: Short = 0x1234): ByteArray =
        buildQuery(host, txId, QTYPE_A)

    /** Encode a DNS AAAA query for [host] with the given [txId]. */
    fun buildAaaaQuery(host: String, txId: Short = 0x5678): ByteArray =
        buildQuery(host, txId, QTYPE_AAAA)

    private fun buildQuery(host: String, txId: Short, qtype: Int): ByteArray {
        val qname = encodeQname(host)
        // Header (12 bytes) + QNAME + QTYPE (2) + QCLASS (2)
        val buf = ByteBuffer.allocate(12 + qname.size + 4).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(txId)      // Transaction ID
        buf.putShort(FLAGS_QUERY) // Flags
        buf.putShort(1)          // QDCOUNT = 1
        buf.putShort(0)          // ANCOUNT
        buf.putShort(0)          // NSCOUNT
        buf.putShort(0)          // ARCOUNT
        buf.put(qname)
        buf.putShort(qtype.toShort())
        buf.putShort(QCLASS_IN.toShort())
        return buf.array()
    }

    /**
     * RFC 1035 §3.1 — encode a dotted hostname as a sequence of
     * length-prefixed labels followed by a zero-length root label.
     */
    private fun encodeQname(host: String): ByteArray {
        val labels = host.trimEnd('.').split('.')
        val size = labels.sumOf { it.length + 1 } + 1
        val buf = ByteBuffer.allocate(size)
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            buf.put(bytes.size.toByte())
            buf.put(bytes)
        }
        buf.put(0)  // root label
        return buf.array()
    }

    // -----------------------------------------------------------------------
    // Response parser
    // -----------------------------------------------------------------------

    /**
     * Parse IPv4 address strings from the ANSWER section of a raw DNS response.
     * Returns an empty list on any parse error (caller treats that as no records).
     */
    fun parseARecords(response: ByteArray): List<String> =
        parseAddressRecords(response, QTYPE_A)

    /**
     * Parse IPv6 address strings from the ANSWER section of a raw DNS response.
     */
    fun parseAaaaRecords(response: ByteArray): List<String> =
        parseAddressRecords(response, QTYPE_AAAA)

    private fun parseAddressRecords(response: ByteArray, targetQtype: Int): List<String> {
        return try {
            val buf = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN)
            if (buf.remaining() < 12) return emptyList()

            // Skip header
            buf.position(4)                       // skip txId + flags
            val qdcount = buf.short.toInt() and 0xffff
            val ancount = buf.short.toInt() and 0xffff
            buf.position(12)                      // skip nscount + arcount

            // Skip the question section
            repeat(qdcount) {
                skipQname(buf, response)
                if (buf.remaining() >= 4) buf.position(buf.position() + 4)  // qtype + qclass
            }

            val addresses = mutableListOf<String>()
            repeat(ancount) {
                if (buf.remaining() < 10) return addresses
                skipQname(buf, response)
                if (buf.remaining() < 10) return addresses
                val rtype  = buf.short.toInt() and 0xffff
                buf.short  // rclass
                buf.int    // ttl
                val rdlen  = buf.short.toInt() and 0xffff
                if (buf.remaining() < rdlen) return addresses
                val rdata = ByteArray(rdlen).also { buf.get(it) }
                if (rtype == targetQtype) {
                    addresses += formatIp(rdata, targetQtype)
                }
            }
            addresses
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Skip a (possibly compressed) QNAME, advancing [buf.position]. */
    private fun skipQname(buf: ByteBuffer, raw: ByteArray) {
        while (buf.hasRemaining()) {
            val len = buf.get().toInt() and 0xff
            when {
                len == 0    -> return                          // root label
                len and 0xC0 == 0xC0 -> { buf.get(); return } // compression pointer (2 bytes total)
                else        -> if (buf.remaining() >= len) buf.position(buf.position() + len)
                               else return
            }
        }
    }

    /** Format 4-byte IPv4 or 16-byte IPv6 RDATA as a string. */
    private fun formatIp(rdata: ByteArray, qtype: Int): String = when (qtype) {
        QTYPE_A    -> rdata.joinToString(".") { (it.toInt() and 0xff).toString() }
        QTYPE_AAAA -> {
            val sb = StringBuilder()
            for (i in 0 until 8) {
                if (i > 0) sb.append(':')
                val hi = rdata[i * 2].toInt() and 0xff
                val lo = rdata[i * 2 + 1].toInt() and 0xff
                sb.append(String.format("%02x%02x", hi, lo))
            }
            sb.toString()
        }
        else -> rdata.joinToString(" ") { "%02x".format(it) }
    }

    // -----------------------------------------------------------------------
    // DoT framing helpers (RFC 7858 §3.3: 2-byte big-endian length prefix)
    // -----------------------------------------------------------------------

    /** Write a DNS message to a DoT stream (prefixed with its 2-byte length). */
    @Throws(IOException::class)
    fun writeDotMessage(out: DataOutputStream, msg: ByteArray) {
        out.writeShort(msg.size)
        out.write(msg)
        out.flush()
    }

    /**
     * Read one DNS message from a DoT stream (strips the 2-byte length prefix).
     * Returns null if the stream ends cleanly before the message.
     */
    @Throws(IOException::class)
    fun readDotMessage(din: DataInputStream): ByteArray? {
        val len = try { din.readUnsignedShort() } catch (_: java.io.EOFException) { return null }
        val buf = ByteArray(len)
        din.readFully(buf)
        return buf
    }
}
