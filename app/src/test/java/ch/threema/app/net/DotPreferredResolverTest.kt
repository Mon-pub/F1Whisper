package ch.threema.app.net

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DotPreferredResolverTest {

    @BeforeTest
    fun setUp() {
        DotPreferredResolver.clearCacheForTest()
    }

    @AfterTest
    fun tearDown() {
        DotPreferredResolver.clearCacheForTest()
        // Restore the production seam (tests swap it rather than mocking java.base
        // statics, which the JVM module system forbids).
        DotPreferredResolver.systemResolver = { InetAddress.getAllByName(it).toList() }
        unmockkAll()
    }

    @Test
    fun `literal IPv4 is returned without any DoT lookup`() {
        mockkObject(SecureDnsClient)
        val result = DotPreferredResolver.resolve("192.0.2.1")
        assertEquals(1, result.size)
        assertEquals("192.0.2.1", result.first().hostAddress)
        verify(exactly = 0) { SecureDnsClient.dotLookup(any(), any()) }
    }

    @Test
    fun `literal IPv6 is returned without any DoT lookup`() {
        mockkObject(SecureDnsClient)
        val result = DotPreferredResolver.resolve("2001:4860:4860::8888")
        assertTrue(result.isNotEmpty())
        verify(exactly = 0) { SecureDnsClient.dotLookup(any(), any()) }
    }

    @Test
    fun `system resolver is the fast path and DoT does not block it`() {
        mockkObject(SecureDnsClient)
        val systemAddr = InetAddress.getByName("192.0.2.1")
        DotPreferredResolver.systemResolver = { listOf(systemAddr) }

        val result = DotPreferredResolver.resolve("example.com")

        assertEquals(listOf(systemAddr), result)
        verify(exactly = 0) { SecureDnsClient.dotLookup(any(), any()) }
    }

    @Test
    fun `a second lookup within the TTL is served from cache`() {
        mockkObject(SecureDnsClient)
        val systemAddr = InetAddress.getByName("192.0.2.1")
        var systemCalls = 0
        DotPreferredResolver.systemResolver = {
            systemCalls++
            listOf(systemAddr)
        }

        val first = DotPreferredResolver.resolve("example.com")
        val second = DotPreferredResolver.resolve("example.com")

        assertEquals(first, second)
        // Only the first resolve hits the system resolver; the second is a cache hit.
        assertEquals(1, systemCalls)
    }

    @Test
    fun `system failure falls back to DoT synchronously`() {
        mockkObject(SecureDnsClient)
        val dotAddr = InetAddress.getByName("192.0.2.1")
        DotPreferredResolver.systemResolver = { throw UnknownHostException("example.com") }
        every { SecureDnsClient.dotLookup("example.com", any()) } returns listOf(dotAddr)

        val result = DotPreferredResolver.resolve("example.com")

        assertEquals(listOf(dotAddr), result)
        verify(exactly = 1) { SecureDnsClient.dotLookup("example.com", any()) }
    }

    @Test
    fun `empty system answer falls back to DoT`() {
        mockkObject(SecureDnsClient)
        val dotAddr = InetAddress.getByName("192.0.2.1")
        DotPreferredResolver.systemResolver = { emptyList() }
        every { SecureDnsClient.dotLookup("example.com", any()) } returns listOf(dotAddr)

        val result = DotPreferredResolver.resolve("example.com")

        assertEquals(listOf(dotAddr), result)
        verify(exactly = 1) { SecureDnsClient.dotLookup("example.com", any()) }
    }

    @Test
    fun `when both system and DoT fail the system UnknownHostException propagates`() {
        mockkObject(SecureDnsClient)
        DotPreferredResolver.systemResolver = { throw UnknownHostException("example.com") }
        every { SecureDnsClient.dotLookup("example.com", any()) } throws
            java.io.IOException("DoT connect blocked")

        assertFailsWith<UnknownHostException> {
            DotPreferredResolver.resolve("example.com")
        }
    }

    // region fork review M-03: fallback-only — no hostname leak, resolver-scoped caches

    @Test
    fun `healthy system resolution NEVER contacts DoT - no hostname leak`() {
        mockkObject(SecureDnsClient)
        val systemAddr = InetAddress.getByName("192.0.2.1")
        DotPreferredResolver.systemResolver = { listOf(systemAddr) }

        // Multiple fresh lookups across different hosts: the DoT provider must learn nothing.
        DotPreferredResolver.resolve("example.com")
        DotPreferredResolver.clearCacheForTest()
        DotPreferredResolver.resolve("example.com")
        DotPreferredResolver.resolve("dir.example.com")

        verify(exactly = 0) { SecureDnsClient.dotLookup(any(), any()) }
    }

    @Test
    fun `a DoT fallback answer does not displace a later working system answer`() {
        mockkObject(SecureDnsClient)
        val dotAddr = InetAddress.getByName("192.0.2.1")
        val privateAddr = InetAddress.getByName("10.7.0.1") // e.g. a split-horizon/VPN answer
        DotPreferredResolver.systemResolver = { throw UnknownHostException("example.com") }
        every { SecureDnsClient.dotLookup("example.com", any()) } returns listOf(dotAddr)

        // System broken: served via DoT.
        assertEquals(listOf(dotAddr), DotPreferredResolver.resolve("example.com"))

        // System recovers with a (different, e.g. private) answer: the DoT-scoped cache must
        // NOT mask it — the working system answer wins immediately.
        DotPreferredResolver.systemResolver = { listOf(privateAddr) }
        assertEquals(listOf(privateAddr), DotPreferredResolver.resolve("example.com"))
    }

    @Test
    fun `while the system resolver stays broken the DoT answer is served from the DoT cache`() {
        mockkObject(SecureDnsClient)
        val dotAddr = InetAddress.getByName("192.0.2.1")
        DotPreferredResolver.systemResolver = { throw UnknownHostException("example.com") }
        every { SecureDnsClient.dotLookup("example.com", any()) } returns listOf(dotAddr)

        val first = DotPreferredResolver.resolve("example.com")
        val second = DotPreferredResolver.resolve("example.com")

        assertEquals(listOf(dotAddr), first)
        assertEquals(listOf(dotAddr), second)
        // The second failure is served from the DoT-scoped cache — one DoT round-trip total.
        verify(exactly = 1) { SecureDnsClient.dotLookup("example.com", any()) }
    }

    // endregion
}
