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
     * F1Whisper: wall-clock timestamp ([System.currentTimeMillis]) of the last inbound frame
     * received from the server on this connection. It is refreshed on **every** inbound frame, not
     * only on echo replies, and once when the connection reaches LOGGEDIN.
     *
     * This value is for **reporting only**. Staleness is judged on
     * [getLastInboundActivityAtAwakeMillis] instead, because the heartbeat that refreshes these
     * stamps is driven by a clock that halts while the device is suspended, so a wall-clock
     * staleness threshold flags healthy connections. See [ConnectionLivenessVerdict] for the
     * measured distribution behind that decision.
     *
     * Returns `0L` if no inbound activity has been recorded yet. `0L` must never be read as "no data,
     * assume healthy": [ConnectionLivenessVerdict.evaluate] discriminates on [connectionState] and
     * fails closed. Every implementer must override this; the default exists only so that an
     * implementer who forgets returns the fail-closed sentinel rather than a plausible-looking value.
     */
    fun getLastInboundActivityAtMillis(): Long = 0L

    /**
     * F1Whisper: awake-time timestamp of the last inbound frame received from the server on this
     * connection, in milliseconds, taken from [System.nanoTime].
     *
     * "Awake time" means time elapsed while the device was not suspended to RAM. On Android
     * `System.nanoTime` is the same clock that backs `SystemClock.uptimeMillis`, and it is also the
     * clock behind `kotlinx.coroutines.delay`, which is what drives the echo heartbeat that refreshes
     * this stamp. Measuring staleness on the same clock that drives the heartbeat is the whole point:
     * a Doze window advances wall-clock time without consuming any heartbeat budget, so only awake
     * time yields an age that a threshold can be set against.
     *
     * Returns `0L` if no inbound activity has been recorded yet. Same fail-closed contract as
     * [getLastInboundActivityAtMillis]: never treat `0L` as fresh.
     */
    fun getLastInboundActivityAtAwakeMillis(): Long = 0L

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
