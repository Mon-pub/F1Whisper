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
        // Disable the background DoT verifier by default so the fast-path assertions are
        // deterministic; tests that exercise it opt in via an inline dispatcher.
        DotPreferredResolver.backgroundDispatcher = { /* no-op */ }
    }

    @AfterTest
    fun tearDown() {
        DotPreferredResolver.clearCacheForTest()
        // Restore the production seams (tests swap them rather than mocking java.base
        // statics, which the JVM module system forbids).
        DotPreferredResolver.systemResolver = { InetAddress.getAllByName(it).toList() }
        DotPreferredResolver.backgroundDispatcher = { r -> r.run() }
        unmockkAll()
    }

    @Test
    fun `literal IPv4 is returned without any DoT lookup`() {
        mockkObject(SecureDnsClient)
        val result = DotPreferredResolver.resolve("208.85.23.138")
        assertEquals(1, result.size)
        assertEquals("208.85.23.138", result.first().hostAddress)
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
        val systemAddr = InetAddress.getByName("208.85.23.138")
        DotPreferredResolver.systemResolver = { listOf(systemAddr) }

        val result = DotPreferredResolver.resolve("thm.f1tech.info")

        assertEquals(listOf(systemAddr), result)
        // With the background verifier disabled, DoT is NOT consulted on the fast path.
        verify(exactly = 0) { SecureDnsClient.dotLookup(any(), any()) }
    }

    @Test
    fun `a second lookup within the TTL is served from cache`() {
        mockkObject(SecureDnsClient)
        val systemAddr = InetAddress.getByName("208.85.23.138")
        var systemCalls = 0
        DotPreferredResolver.systemResolver = { systemCalls++; listOf(systemAddr) }

        val first = DotPreferredResolver.resolve("thm.f1tech.info")
        val second = DotPreferredResolver.resolve("thm.f1tech.info")

        assertEquals(first, second)
        // Only the first resolve hits the system resolver; the second is a cache hit.
        assertEquals(1, systemCalls)
    }

    @Test
    fun `system failure falls back to DoT synchronously`() {
        mockkObject(SecureDnsClient)
        val dotAddr = InetAddress.getByName("208.85.23.138")
        DotPreferredResolver.systemResolver = { throw UnknownHostException("thm.f1tech.info") }
        every { SecureDnsClient.dotLookup("thm.f1tech.info", any()) } returns listOf(dotAddr)

        val result = DotPreferredResolver.resolve("thm.f1tech.info")

        assertEquals(listOf(dotAddr), result)
        verify(exactly = 1) { SecureDnsClient.dotLookup("thm.f1tech.info", any()) }
    }

    @Test
    fun `empty system answer falls back to DoT`() {
        mockkObject(SecureDnsClient)
        val dotAddr = InetAddress.getByName("208.85.23.138")
        DotPreferredResolver.systemResolver = { emptyList() }
        every { SecureDnsClient.dotLookup("thm.f1tech.info", any()) } returns listOf(dotAddr)

        val result = DotPreferredResolver.resolve("thm.f1tech.info")

        assertEquals(listOf(dotAddr), result)
        verify(exactly = 1) { SecureDnsClient.dotLookup("thm.f1tech.info", any()) }
    }

    @Test
    fun `when both system and DoT fail the system UnknownHostException propagates`() {
        mockkObject(SecureDnsClient)
        DotPreferredResolver.systemResolver = { throw UnknownHostException("thm.f1tech.info") }
        every { SecureDnsClient.dotLookup("thm.f1tech.info", any()) } throws
            java.io.IOException("DoT connect blocked")

        assertFailsWith<UnknownHostException> {
            DotPreferredResolver.resolve("thm.f1tech.info")
        }
    }

    @Test
    fun `background DoT verify overrides the cache with the trusted answer`() {
        mockkObject(SecureDnsClient)
        val systemAddr = InetAddress.getByName("10.7.0.1") // e.g. a hijacked/local answer
        val dotAddr = InetAddress.getByName("208.85.23.138") // the trusted answer
        DotPreferredResolver.systemResolver = { listOf(systemAddr) }
        every { SecureDnsClient.dotLookup("thm.f1tech.info", any()) } returns listOf(dotAddr)
        // Run the background verification inline so its cache overwrite is observable.
        DotPreferredResolver.backgroundDispatcher = { r -> r.run() }

        val first = DotPreferredResolver.resolve("thm.f1tech.info")
        val second = DotPreferredResolver.resolve("thm.f1tech.info")

        // First lookup returns the fast system answer; the background DoT check then
        // overwrites the cache, so the next lookup returns the trusted answer.
        assertEquals(listOf(systemAddr), first)
        assertEquals(listOf(dotAddr), second)
        verify(exactly = 1) { SecureDnsClient.dotLookup("thm.f1tech.info", any()) }
    }
}
