package ch.threema.domain.protocol.connection.layer

import ch.threema.domain.protocol.connection.BaseServerConnection
import ch.threema.domain.protocol.connection.ConnectionLock
import ch.threema.domain.protocol.connection.ConnectionLockProvider
import ch.threema.domain.protocol.connection.InputPipe
import ch.threema.domain.protocol.connection.Pipe
import ch.threema.domain.protocol.connection.PipeCloseHandler
import ch.threema.domain.protocol.connection.PipeHandler
import ch.threema.domain.protocol.connection.ServerConnection
import ch.threema.domain.protocol.connection.csp.CspConnection
import ch.threema.domain.protocol.connection.data.CspMessage
import ch.threema.domain.protocol.connection.data.InboundL4Message
import ch.threema.domain.protocol.connection.data.InboundMessage
import ch.threema.domain.protocol.connection.data.OutboundD2mMessage
import ch.threema.domain.protocol.connection.data.OutboundL5Message
import ch.threema.domain.protocol.connection.data.OutboundMessage
import ch.threema.domain.protocol.connection.socket.ServerSocketCloseReason
import ch.threema.domain.protocol.connection.util.ConnectionLoggingUtil
import ch.threema.domain.protocol.connection.util.ServerConnectionController
import ch.threema.domain.taskmanager.IncomingMessageProcessor
import ch.threema.domain.taskmanager.InternalTaskManager
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

private val logger = ConnectionLoggingUtil.getConnectionLogger("EndToEndLayer")

internal class EndToEndLayer(
    private val outputDispatcher: CoroutineContext,
    private val connectionController: ServerConnectionController,
    private val connection: ServerConnection,
    private val incomingMessageProcessor: IncomingMessageProcessor,
    private val taskManager: InternalTaskManager,
    private val connectionLockProvider: ConnectionLockProvider,
) : Layer5Codec {
    private val inboundMessageChannel = Channel<Pair<InboundMessage, ConnectionLock>>(capacity = Channel.UNLIMITED)

    private val isCspConnection = connection is CspConnection

    init {
        // F1Whisper (tenth fork review, F10-02): owned by the attempt. This collector is the longest-lived coroutine
        // in the graph - it awaits authentication and then collects until cancelled - so with no owner it, its channel
        // and the executor thread underneath it survived every reconnect. That is the leak ANDR-3579 asks about.
        connectionController.dispatcher.scope.launch {
            // TODO(ANDR-3579): How does cancellation work here? Will the content of inboundMessageChannel linger
            //  indefinitely if a reconnection/exception happens? In that case, we somehow need to release all ConnectionLocks

            connectionController.cspAuthenticated.await()
            // Start task manager when csp has been authenticated
            taskManager.startRunningTasks(this@EndToEndLayer, incomingMessageProcessor)

            // Forward inbound messages to task manager after it has been started
            inboundMessageChannel.receiveAsFlow().collect { (inboundMessage, lock) ->
                taskManager.processInboundMessage(inboundMessage, lock)
            }
        }
    }

    private val outbound = InputPipe<OutboundL5Message, Unit>()

    override val source: Pipe<OutboundL5Message, Unit> = outbound

    override val sink: PipeHandler<InboundL4Message> = PipeHandler { handleInboundMessage(it) }

    override val closeHandler: PipeCloseHandler<ServerSocketCloseReason> = PipeCloseHandler {
        handleInboundClose(it)
    }

    override fun sendOutbound(message: OutboundMessage) {
        // We check this here to let the task fail immediately. This is required when the connection
        // changes and the task does not check whether MD is still enabled or not.
        if (isCspConnection && message is OutboundD2mMessage) {
            throw IllegalStateException("Cannot send d2m message on csp connection")
        }

        CoroutineScope(outputDispatcher).launch {
            val l5Message = mapMessage(message)
            logger.info("Queuing outbound message of type `{}`", l5Message.type)
            outbound.send(l5Message)
        }
    }

    override fun restartConnection(delayMs: Long) {
        // F1Whisper (eleventh fork review, F11-01): the delay is attempt-owned, the stop is not, and the split is the
        // whole fix.
        //
        // The tenth review's version ran BOTH halves on the attempt's single-thread dispatcher (unowned, so disposal
        // would drop it by rejected dispatch). That deadlocked the connection worker: after the delay the coroutine
        // resumed on the attempt's only thread and called the blocking `connection.stop()` there, and socket close
        // dispatches `closeInbound` back onto that same thread from inside a `runBlocking` - the thread ends up
        // waiting for work that only it can run, with the connection's start/stop lock held. Messaging stayed dead
        // until the process was killed. Reachable from any task that throws `ProtocolException`
        // (`TaskRunner.restartConnection`), for CSP and D2M alike.
        //
        // Owning the delay in `dispatcher.scope` is deliberate and safe precisely BECAUSE nothing here blocks: a
        // member of the attempt scope must never wait on the connection job (disposal joins this scope), and this one
        // only sleeps, checks, and hands off. Cancellation on disposal is now structured rather than relying on the
        // closed executor rejecting the resumption, which is what makes the request unable to outlive its attempt.
        // The blocking stop+start runs on the connection-level coordinator - see
        // [BaseServerConnection.requestRestart] for its own gate against superseded attempts and external stops.
        connectionController.dispatcher.scope.launch {
            delay(delayMs)
            if (connectionController.connectionClosed.isCompleted) {
                // The attempt already ended; the reconnect loop is producing the replacement itself.
                return@launch
            }
            val restartable = connection as? BaseServerConnection
            if (restartable == null) {
                // Only reachable with a ServerConnection implementation this module did not build.
                logger.error("Cannot request a connection restart on {}", connection::class.simpleName)
                return@launch
            }
            restartable.requestRestart(connectionController.connectionClosed)
        }
    }

    private fun mapMessage(message: OutboundMessage): OutboundL5Message {
        return when (message) {
            is CspMessage -> message.toCspContainer()
            is OutboundD2mMessage -> message
        }
    }

    private fun handleInboundMessage(message: InboundL4Message) {
        logger.debug("Handle inbound message of type `{}`", message.type)
        // TODO(ANDR-3580): Should we acquire this way earlier, i.e. when receiving bytes on the TCP
        //  layer / a datagram on the WS layer and shove it through the whole pipeline?
        val lock = connectionLockProvider.acquire(60_000, ConnectionLockProvider.ConnectionLogTag.INBOUND_MESSAGE)
        val result = inboundMessageChannel.trySend(
            Pair(
                message.toInboundMessage(),
                lock,
            ),
        )
        if (result.isFailure) {
            logger.error("Unable to forward inbound message to task manager", result.exceptionOrNull())
            lock.release()
        }
    }

    private fun handleInboundClose(closeReason: ServerSocketCloseReason) {
        logger.debug("Handle inbound close: Pausing task manager because of {}", closeReason)
        // F1Whisper (tenth fork review, F10-02): owned by the attempt. It runs while the socket is closing, well
        // before the attempt is disposed at the end of the loop iteration.
        connectionController.dispatcher.scope.launch {
            taskManager.pauseRunningTasks(closeReason)
        }
    }
}
