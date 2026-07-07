package ch.threema.domain.protocol.connection

import androidx.annotation.WorkerThread
import ch.threema.base.SessionScoped

/**
 * The [ServerConnection] connects to the server used for the exchange of messages. Different types
 * of servers (e.g. CSP-Server, Mediator-Server) will have different implementations.
 *
 * This interface only defines, how the connection is started, stopped and monitored. It does not
 * define how actual messages are exchanged with the server. This can be handled differently depending
 * on the implementation.
 */
@SessionScoped
interface ServerConnection {
    val isRunning: Boolean

    val connectionState: ConnectionState

    val isNewConnectionSession: Boolean

    /**
     * F1Whisper: wall-clock timestamp ([System.currentTimeMillis]) of the last inbound signal
     * received from the server on this connection. It is refreshed whenever an echo *reply* arrives
     * (the ~60s CSP keepalive heartbeat) and once when the connection reaches LOGGEDIN. It is used by
     * the foreground lifecycle observer to distinguish a Doze-dead socket (state still LOGGEDIN but no
     * inbound activity for a staleness window) from a healthy idle-but-alive connection, so we only
     * ever tear down an already-dead socket.
     *
     * Wall-clock is intentional: unlike [android.os.SystemClock.elapsedRealtime], it advances while
     * the device is asleep, so the staleness age reflects real elapsed time across a Doze window.
     *
     * Returns `0L` if no inbound activity has been recorded yet (before login). Callers must gate on
     * [connectionState] == LOGGEDIN, which is only reached after the value has been set.
     */
    fun getLastInboundActivityAtMillis(): Long = 0L

    /**
     * Disable the connection to attempt a reconnect in this session.
     * If a new connection session is started (e.g. [start] or the app is restarted) the
     * flag is reset.
     */
    fun disableReconnect()

    /**
     * Start the connection. The connection must handle sending and receiving of messages in an own thread.
     */
    fun start()

    /**
     * Stop the connection and wait for processing to terminate.
     *
     * There won't be an attempt to reconnect after the connection has been stopped by this method.
     *
     * This is a blocking call and should only be called
     * from a worker thread, not from the main thread.
     */
    @WorkerThread
    @Throws(InterruptedException::class)
    fun stop()

    fun addConnectionStateListener(listener: ConnectionStateListener)
    fun removeConnectionStateListener(listener: ConnectionStateListener)
}
