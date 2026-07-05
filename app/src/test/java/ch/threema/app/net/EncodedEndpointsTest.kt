package ch.threema.app.net

import kotlin.test.Test
import kotlin.test.assertEquals

class EncodedEndpointsTest {

    // Expected values are assembled from fragments so this (public-repo) test source
    // itself carries no contiguous plaintext of the private host/IP for scrapers to grab.
    private val expectedHost = listOf("dns", "f1tech", "info").joinToString(".")
    private val expectedIp = listOf("108", "61", "210", "25").joinToString(".")

    @Test
    fun `dnsHost decodes to the pinned server name`() {
        assertEquals(expectedHost, EncodedEndpoints.dnsHost())
    }

    @Test
    fun `pinnedIp decodes to the pinned server IP`() {
        assertEquals(expectedIp, EncodedEndpoints.pinnedIp())
    }

    @Test
    fun `dot port is 853`() {
        assertEquals(853, EncodedEndpoints.DOT_PORT)
    }

    @Test
    fun `decode is stable across calls`() {
        assertEquals(EncodedEndpoints.dnsHost(), EncodedEndpoints.dnsHost())
        assertEquals(EncodedEndpoints.pinnedIp(), EncodedEndpoints.pinnedIp())
    }
}
