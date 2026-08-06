package ch.threema.app.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DnsWireTest {

    // -----------------------------------------------------------------------
    // Query builders
    // -----------------------------------------------------------------------

    @Test
    fun `buildAQuery produces a well-formed A query`() {
        val query = DnsWire.buildAQuery("example.com", txId = 0x1234)
        val buf = ByteBuffer.wrap(query).order(ByteOrder.BIG_ENDIAN)

        assertEquals(0x1234.toShort(), buf.short, "transaction id")
        assertEquals(0x0100.toShort(), buf.short, "flags: RD=1")
        assertEquals(1, buf.short.toInt(), "QDCOUNT = 1")
        assertEquals(0, buf.short.toInt(), "ANCOUNT = 0")
        assertEquals(0, buf.short.toInt(), "NSCOUNT = 0")
        assertEquals(0, buf.short.toInt(), "ARCOUNT = 0")

        // QNAME + QTYPE(2) + QCLASS(2). QTYPE must be A (1), QCLASS IN (1).
        val qtype = query[query.size - 4].toInt() and 0xff shl 8 or (query[query.size - 3].toInt() and 0xff)
        val qclass = query[query.size - 2].toInt() and 0xff shl 8 or (query[query.size - 1].toInt() and 0xff)
        assertEquals(1, qtype, "QTYPE = A")
        assertEquals(1, qclass, "QCLASS = IN")
    }

    @Test
    fun `buildAaaaQuery sets the AAAA qtype`() {
        val query = DnsWire.buildAaaaQuery("example.com", txId = 0x5678)
        val qtype = query[query.size - 4].toInt() and 0xff shl 8 or (query[query.size - 3].toInt() and 0xff)
        assertEquals(28, qtype, "QTYPE = AAAA")

        val buf = ByteBuffer.wrap(query).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x5678.toShort(), buf.short, "transaction id")
    }

    @Test
    fun `qname encodes dotted labels with a root terminator`() {
        // "a.bc" -> [1]'a'[2]'b''c'[0]
        val query = DnsWire.buildAQuery("a.bc", txId = 0x0001)
        // Header is 12 bytes; QNAME starts at offset 12.
        assertEquals(1, query[12].toInt(), "first label length")
        assertEquals('a'.code, query[13].toInt())
        assertEquals(2, query[14].toInt(), "second label length")
        assertEquals('b'.code, query[15].toInt())
        assertEquals('c'.code, query[16].toInt())
        assertEquals(0, query[17].toInt(), "root label")
    }

    // -----------------------------------------------------------------------
    // Response parser
    // -----------------------------------------------------------------------

    @Test
    fun `parseARecords round-trips a hand-crafted A response`() {
        val response = buildAResponse("example.com", "192.0.2.1")
        val records = DnsWire.parseARecords(response)
        assertEquals(listOf("192.0.2.1"), records)
    }

    @Test
    fun `parseARecords returns empty on empty input`() {
        assertTrue(DnsWire.parseARecords(ByteArray(0)).isEmpty())
    }

    @Test
    fun `parseARecords returns empty on malformed response`() {
        assertTrue(DnsWire.parseARecords(byteArrayOf(0x01, 0x02, 0x03, 0x04)).isEmpty())
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Build a minimal DNS response: header (QDCOUNT=1, ANCOUNT=1), one question,
     * and one A answer record for [host] -> [ipv4]. Uses an uncompressed QNAME
     * in both the question and the answer.
     */
    private fun buildAResponse(host: String, ipv4: String): ByteArray {
        val qname = encodeQname(host)
        val rdata = ipv4.split('.').map { it.toInt().toByte() }.toByteArray()

        // 12 header + question(qname + 4) + answer(qname + type2 + class2 + ttl4 + rdlen2 + rdata4)
        val size = 12 + qname.size + 4 + qname.size + 2 + 2 + 4 + 2 + rdata.size
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0x1234) // txId
        buf.putShort(0x8180.toShort()) // flags: QR=1, RD=1, RA=1
        buf.putShort(1) // QDCOUNT
        buf.putShort(1) // ANCOUNT
        buf.putShort(0) // NSCOUNT
        buf.putShort(0) // ARCOUNT

        // Question
        buf.put(qname)
        buf.putShort(1) // QTYPE A
        buf.putShort(1) // QCLASS IN

        // Answer
        buf.put(qname)
        buf.putShort(1) // TYPE A
        buf.putShort(1) // CLASS IN
        buf.putInt(300) // TTL
        buf.putShort(rdata.size.toShort())
        buf.put(rdata)

        return buf.array()
    }

    private fun encodeQname(host: String): ByteArray {
        val labels = host.trimEnd('.').split('.')
        val size = labels.sumOf { it.length + 1 } + 1
        val buf = ByteBuffer.allocate(size)
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            buf.put(bytes.size.toByte())
            buf.put(bytes)
        }
        buf.put(0)
        return buf.array()
    }
}
