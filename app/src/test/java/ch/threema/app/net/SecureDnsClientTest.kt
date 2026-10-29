package ch.threema.app.net

import io.mockk.every
import io.mockk.mockk
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureDnsClientTest {

    // certMatchesHost is host-agnostic (it just compares a dNSName SAN to the given host),
    // so we use a neutral fixture host — the real private DoT host never appears in this
    // (public-repo) test source.
    private val host = "secure-dns.example.test"

    @Test
    fun `certMatchesHost accepts a matching dNSName SAN`() {
        val cert = certWithSans(listOf(sanEntry(2, host)))
        assertTrue(SecureDnsClient.certMatchesHost(cert, host))
    }

    @Test
    fun `certMatchesHost is case-insensitive on the SAN name`() {
        val cert = certWithSans(listOf(sanEntry(2, host.uppercase())))
        assertTrue(SecureDnsClient.certMatchesHost(cert, host))
    }

    @Test
    fun `certMatchesHost accepts when the host is one of several SANs`() {
        val cert = certWithSans(
            listOf(
                sanEntry(2, "other.example.com"),
                sanEntry(2, host),
            ),
        )
        assertTrue(SecureDnsClient.certMatchesHost(cert, host))
    }

    @Test
    fun `certMatchesHost rejects a non-matching SAN`() {
        val cert = certWithSans(listOf(sanEntry(2, "evil.example.com")))
        assertFalse(SecureDnsClient.certMatchesHost(cert, host))
    }

    @Test
    fun `certMatchesHost ignores non-dNSName SAN types`() {
        // Type 7 == iPAddress; must not match even if the string is equal.
        val cert = certWithSans(listOf(sanEntry(7, host)))
        assertFalse(SecureDnsClient.certMatchesHost(cert, host))
    }

    @Test
    fun `certMatchesHost rejects when there are no SANs`() {
        val cert = mockk<X509Certificate> {
            every { subjectAlternativeNames } returns null
        }
        assertFalse(SecureDnsClient.certMatchesHost(cert, host))
    }

    @Test
    fun `certMatchesHost rejects when reading SANs throws`() {
        val cert = mockk<X509Certificate> {
            every { subjectAlternativeNames } throws java.security.cert.CertificateParsingException("bad")
        }
        assertFalse(SecureDnsClient.certMatchesHost(cert, host))
    }

    private fun sanEntry(type: Int, value: String): List<Any> = listOf(type, value)

    private fun certWithSans(sans: Collection<List<Any>>): X509Certificate =
        mockk<X509Certificate> {
            every { subjectAlternativeNames } returns sans
        }
}
