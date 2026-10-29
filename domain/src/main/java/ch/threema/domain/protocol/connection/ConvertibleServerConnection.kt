package ch.threema.domain.protocol.connection

import androidx.annotation.WorkerThread
import ch.threema.common.DelegateStateFlow
import ch.threema.common.stateFlowOf
import ch.threema.domain.protocol.connection.util.ConnectionLoggingUtil
import java.util.function.Supplier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val logger = ConnectionLoggingUtil.getConnectionLogger("ConvertibleServerConnection")

/**
 * A wrapper that can handle changing connections. Every time the connection is started the used
 * connection is retrieved by calling [Supplier.get] of the [connectionSupplier].
 *
 * The [connectionSupplier] is responsible to provide a [ServerConnection] that can be used without
 * interfering with the previous connection.
 */
open class ConvertibleServerConnection(
    private val connectionSupplier: Supplier<ServerConnection>,
) : ServerConnection, ReconnectableServerConnection {
    private var connection: ServerConnection? = null
        set(value) {
            connectionStateFlow.delegate = value?.watchConnectionState() ?: stateFlowOf(ConnectionState.DISCONNECTED)
            loginCountFlow.delegate = value?.watchLoginCount() ?: stateFlowOf(0L)
            field = value
        }

    override val isRunning: Boolean
        get() = connection?.isRunning ?: false

    private val connectionStateFlow = DelegateStateFlow(stateFlowOf(ConnectionState.DISCONNECTED))

    override fun watchConnectionState(): StateFlow<ConnectionState> = connectionStateFlow

    // F1Whisper: delegate to the wrapped connection like the state flow. Connections share one process-wide count, so a
    // swap never repeats a value a collector has already seen.
    private val loginCountFlow = DelegateStateFlow(stateFlowOf(0L))

    override fun watchLoginCount(): StateFlow<Long> = loginCountFlow

    override val connectionState: ConnectionState
        get() = connectionStateFlow.value

    override val isNewConnectionSession: Boolean
        get() = connection?.isNewConnectionSession ?: true

    // F1Whisper: delegate both last-inbound-activity reads to the currently wrapped connection (0L if
    // none is active yet). getConnection() returns this wrapper, so the delegation is required for the
    // liveness verdict to see the real BaseServerConnection values. These overrides must never be
    // dropped in favour of the ServerConnection interface defaults: the wrapper would then report 0L
    // forever while a perfectly healthy connection ran underneath it.
    override fun getLastInboundActivityAtMillis(): Long =
        connection?.getLastInboundActivityAtMillis() ?: 0L

    override fun getLastInboundActivityAtAwakeMillis(): Long =
        connection?.getLastInboundActivityAtAwakeMillis() ?: 0L

    override fun disableReconnect() {
        connection?.disableReconnect()
    }

    override fun start() {
        logger.debug("Start")

        if (connection?.isRunning == true) {
            logger.warn("Connection is already running")
            return
        }

        if (!connection.let { it == null || it.connectionState == ConnectionState.DISCONNECTED }) {
            logger.warn("Connection is neither new nor disconnected. Abort connecting.")
            return
        }

        connectionSupplier.get().also { newConnection ->
            if (newConnection != connection) {
                logger.debug("Connection has changed")

                // Drop and stop old connection
                connection?.let { oldConnection ->
                    logger.debug("Stopping old connection asynchronously")
                    CoroutineScope(Dispatchers.IO).launch {
                        oldConnection.stop()
                        logger.debug("Old connection stopped")
                    }
                }

                // Register new connection
                connection = newConnection
            }
        }.start()
    }

    @WorkerThread
    @Throws(InterruptedException::class)
    override fun stop() {
        logger.debug("Stop")

        synchronized(this) {
            connection?.stop()
        }
    }

    @WorkerThread
    @Throws(InterruptedException::class)
    override fun reconnect() {
        synchronized(this) {
            logger.info("Reconnect")
            stop()
            start()
        }
    }
}
