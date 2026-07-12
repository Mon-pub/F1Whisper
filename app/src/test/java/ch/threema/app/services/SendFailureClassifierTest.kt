package ch.threema.app.services

import ch.threema.app.exceptions.NotAllowedException
import ch.threema.base.ThreemaException
import ch.threema.common.HttpResponseException
import ch.threema.domain.protocol.csp.MessageTooLongException
import ch.threema.domain.taskmanager.ProtocolException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper auto-resend: the failure-class matrix. Connectivity-class failures must NOT be terminal
 * (they stay pending for the reconnect scan); everything else must be terminal (SENDFAILED now).
 */
class SendFailureClassifierTest {

    // ---- Connectivity (transient) -> NOT terminal ----

    @Test
    fun `plain IOException is connectivity`() {
        assertTrue(SendFailureClassifier.isConnectivityFailure(IOException("boom")))
        assertFalse(SendFailureClassifier.isTerminalFailure(IOException("boom")))
    }

    @Test
    fun `socket timeout is connectivity`() {
        assertTrue(SendFailureClassifier.isConnectivityFailure(SocketTimeoutException()))
    }

    @Test
    fun `unknown host is connectivity`() {
        assertTrue(SendFailureClassifier.isConnectivityFailure(UnknownHostException("dns down")))
    }

    @Test
    fun `http auth error 401 is connectivity (onprem auth-token refetch)`() {
        assertTrue(SendFailureClassifier.isConnectivityFailure(HttpResponseException(401)))
    }

    @Test
    fun `http forbidden 403 is connectivity`() {
        assertTrue(SendFailureClassifier.isConnectivityFailure(HttpResponseException(403)))
    }

    @Test
    fun `http server error 500 is connectivity (still an IOException)`() {
        // Any HttpResponseException is an IOException; a 5xx blob failure is transient.
        assertTrue(SendFailureClassifier.isConnectivityFailure(HttpResponseException(500)))
    }

    @Test
    fun `network exception (protocol) is connectivity`() {
        assertTrue(SendFailureClassifier.isConnectivityFailure(ProtocolException("csp down")))
    }

    @Test
    fun `wrapped IOException cause is connectivity`() {
        val wrapped = ThreemaException("upload failed", IOException("socket reset"))
        assertTrue(SendFailureClassifier.isConnectivityFailure(wrapped))
    }

    @Test
    fun `deeply wrapped IOException cause is connectivity`() {
        val deep = RuntimeException("outer", ThreemaException("mid", SocketTimeoutException()))
        assertTrue(SendFailureClassifier.isConnectivityFailure(deep))
    }

    // ---- Terminal -> IS terminal ----

    @Test
    fun `null cause is terminal (e_g_ user-cancel interrupt)`() {
        assertFalse(SendFailureClassifier.isConnectivityFailure(null))
        assertTrue(SendFailureClassifier.isTerminalFailure(null))
    }

    @Test
    fun `ballot NotAllowedException is terminal`() {
        assertFalse(SendFailureClassifier.isConnectivityFailure(NotAllowedException()))
        assertTrue(SendFailureClassifier.isTerminalFailure(NotAllowedException()))
    }

    @Test
    fun `MessageTooLongException is terminal`() {
        assertTrue(SendFailureClassifier.isTerminalFailure(MessageTooLongException()))
    }

    @Test
    fun `plain ThreemaException (e_g_ file missing) is terminal`() {
        assertTrue(SendFailureClassifier.isTerminalFailure(ThreemaException("Message file not present")))
    }

    @Test
    fun `arbitrary RuntimeException is terminal`() {
        assertTrue(SendFailureClassifier.isTerminalFailure(IllegalStateException("unexpected")))
    }

    @Test
    fun `FileNotFoundException is terminal (deleted source file never returns)`() {
        // FileNotFoundException extends IOException but a missing local source file can never be
        // recovered on a later attempt, so it must NOT be classified as transient connectivity
        // (which would loop the reconnect scan until the 24h age-out).
        assertFalse(SendFailureClassifier.isConnectivityFailure(FileNotFoundException("gone")))
        assertTrue(SendFailureClassifier.isTerminalFailure(FileNotFoundException("gone")))
    }

    @Test
    fun `wrapped FileNotFoundException cause is terminal even under an IOException outer`() {
        // The missing-file cause must win over any generic IOException wrapper earlier in the chain.
        val wrapped = ThreemaException("send failed", FileNotFoundException("source removed"))
        assertTrue(SendFailureClassifier.isTerminalFailure(wrapped))
    }

    @Test
    fun `self-referential cause chain does not loop and is terminal`() {
        // Defensive: a throwable whose cause is itself must not hang the classifier.
        val selfRef = object : ThreemaException("self") {
            override val cause: Throwable
                get() = this
        }
        assertTrue(SendFailureClassifier.isTerminalFailure(selfRef))
    }
}
