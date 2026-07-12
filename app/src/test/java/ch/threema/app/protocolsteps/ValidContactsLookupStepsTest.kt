package ch.threema.app.protocolsteps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guards the transient-vs-permanent classification used by `checkWorkAPI` to decide whether a
 * failed work-API fetch should retry (via reconnect) or be acked and dropped. A permanent 4xx must
 * be recognised so it is dropped instead of causing an unbounded ~1s reconnect loop.
 */
class ValidContactsLookupStepsTest {
    // Exactly the message APIConnector.postJson throws for a non-2xx response.
    private fun postJsonMessage(code: Int) = "HTTP POST failed. Server response code: $code"

    @Test
    fun `extracts the status code from a real postJson error message`() {
        assertEquals(403, extractWorkApiStatusCode(postJsonMessage(403)))
        assertEquals(404, extractWorkApiStatusCode(postJsonMessage(404)))
        assertEquals(400, extractWorkApiStatusCode(postJsonMessage(400)))
        assertEquals(500, extractWorkApiStatusCode(postJsonMessage(500)))
        assertEquals(502, extractWorkApiStatusCode(postJsonMessage(502)))
    }

    @Test
    fun `returns null for a connectivity error with no status code`() {
        assertNull(extractWorkApiStatusCode(null))
        assertNull(extractWorkApiStatusCode(""))
        assertNull(extractWorkApiStatusCode("Unable to resolve host \"work.example\": No address associated with hostname"))
        assertNull(extractWorkApiStatusCode("Software caused connection abort"))
    }

    @Test
    fun `classifies 4xx as permanent and everything else as transient`() {
        // A 4xx is permanent -> the message must be dropped (rethrow original IOException).
        for (code in listOf(400, 401, 403, 404, 410, 429, 499)) {
            val parsed = extractWorkApiStatusCode(postJsonMessage(code))
            assertEquals(true, parsed != null && parsed in 400..499, "HTTP $code should be permanent")
        }
        // A 5xx is transient -> retry via reconnect (ProtocolException).
        for (code in listOf(500, 502, 503, 504)) {
            val parsed = extractWorkApiStatusCode(postJsonMessage(code))
            assertEquals(false, parsed != null && parsed in 400..499, "HTTP $code should be transient")
        }
        // A connectivity error (no code) is transient.
        val connectivity = extractWorkApiStatusCode("timeout")
        assertEquals(false, connectivity != null && connectivity in 400..499)
    }
}
