package ch.threema.app.services

import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.taskmanager.NetworkException
import java.io.FileNotFoundException
import java.io.IOException

private val logger = getThreemaLogger("SendFailureClassifier")

/**
 * F1Whisper auto-resend: classifies an exception thrown while sending an outgoing media/file/ballot
 * message (the legacy [MessageSendingServiceExponentialBackOff] pipeline) as either a transient
 * CONNECTIVITY failure (should NOT mark SENDFAILED; the message stays pending and the reconnect
 * scan re-sends it once the connection returns) or a TERMINAL failure (mark SENDFAILED immediately
 * and nag, exactly like upstream).
 *
 * The taxonomy is deliberately conservative: only the two clearly-transient network exception
 * families are treated as connectivity, everything else falls through to terminal. If we ever
 * misclassify a truly transient error as terminal the user still gets the manual "try again"
 * bubble/notification (upstream behaviour preserved); the risk we must avoid is the opposite -
 * silently retrying a message that can never succeed.
 *
 *  - [IOException] (and subclasses): blob upload/download failures, socket timeouts,
 *    [java.net.UnknownHostException], and the on-prem auth-token HTTP 401/403 (which surfaces as
 *    `HttpResponseException extends IOException`; the pipeline already invalidates + re-fetches the
 *    token on retry). All transient - a later attempt on a live connection can succeed. The sole
 *    exception is [java.io.FileNotFoundException]: a missing local source file never returns, so it
 *    is classified TERMINAL despite extending IOException.
 *  - [NetworkException] (`ConnectionStoppedException` / `ConnectionUnavailableException` /
 *    `ProtocolException`): the CSP connection was down/dropped when the send task ran. Transient.
 *
 * Everything else - notably every [ch.threema.base.ThreemaException] subtype
 * (`NotAllowedException` for ballots, `MessageTooLongException`, `BadDHStateException` for FS,
 * "Message file not present", encryption failures) and any unexpected [RuntimeException] - is
 * terminal: retrying cannot fix it, so we keep marking SENDFAILED right away.
 */
object SendFailureClassifier {
    /**
     * @return `true` if [throwable] is a transient connectivity-class failure that should NOT
     * result in an immediate SENDFAILED (the message is left pending for the reconnect scan).
     */
    @JvmStatic
    fun isConnectivityFailure(throwable: Throwable?): Boolean {
        var cause: Throwable? = throwable
        // Walk the cause chain: a connectivity error is often wrapped (e.g. a ThreemaException
        // whose cause is an IOException). Bounded to avoid a pathological self-referential chain.
        var depth = 0
        while (cause != null && depth < 16) {
            // A missing local source file (deleted/moved after send was scheduled) can NEVER come
            // back on a later attempt, so it is TERMINAL even though FileNotFoundException extends
            // IOException. Check it before the IOException branch so it is not misclassified as a
            // transient connectivity failure that the reconnect scan would retry until the 24h
            // age-out.
            if (cause is FileNotFoundException) {
                logger.debug("Classified as terminal failure (missing source file): {}", cause.javaClass.simpleName)
                return false
            }
            if (cause is IOException || cause is NetworkException) {
                logger.debug("Classified as connectivity failure: {}", cause.javaClass.simpleName)
                return true
            }
            val next = cause.cause
            if (next === cause) {
                break
            }
            cause = next
            depth++
        }
        return false
    }

    /**
     * @return `true` if [throwable] is a terminal failure that should mark SENDFAILED immediately.
     * Equivalent to `!isConnectivityFailure(throwable)`; provided for call-site readability.
     */
    @JvmStatic
    fun isTerminalFailure(throwable: Throwable?): Boolean = !isConnectivityFailure(throwable)
}
