package ch.threema.domain.protocol.connection

import ch.threema.domain.protocol.Version
import ch.threema.domain.protocol.connection.csp.CspConnectionConfiguration
import ch.threema.domain.protocol.connection.csp.CspConnectionImpl
import ch.threema.domain.protocol.connection.csp.CspControllers
import ch.threema.domain.protocol.connection.csp.socket.CspSocket
import ch.threema.domain.protocol.connection.csp.socket.SocketFactory
import ch.threema.domain.protocol.connection.d2m.D2mConnectionConfiguration
import ch.threema.domain.protocol.connection.d2m.D2mConnectionImpl
import ch.threema.domain.protocol.connection.d2m.D2mControllers
import ch.threema.domain.protocol.connection.d2m.socket.D2mServerAddressProvider
import ch.threema.domain.protocol.connection.d2m.socket.D2mSocket
import ch.threema.domain.protocol.connection.data.CspMessage
import ch.threema.domain.protocol.connection.data.D2dMessage
import ch.threema.domain.protocol.connection.data.D2mProtocolVersion
import ch.threema.domain.protocol.connection.data.DeviceId
import ch.threema.domain.protocol.connection.data.InboundD2mMessage
import ch.threema.domain.protocol.connection.layer.AuthLayer
import ch.threema.domain.protocol.connection.layer.CspFrameLayer
import ch.threema.domain.protocol.connection.layer.D2mFrameLayer
import ch.threema.domain.protocol.connection.layer.EndToEndLayer
import ch.threema.domain.protocol.connection.layer.MonitoringLayer
import ch.threema.domain.protocol.connection.layer.MultiplexLayer
import ch.threema.domain.protocol.connection.layer.ServerConnectionLayers
import ch.threema.domain.protocol.csp.coders.MessageBox
import ch.threema.domain.protocol.multidevice.MultiDeviceKeys
import ch.threema.domain.protocol.multidevice.MultiDeviceProperties
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.IncomingMessageProcessor
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient

/**
 * F1Whisper (eleventh fork review, F11-01 and F11-02): a task-requested restart must not deadlock the connection
 * worker, and a stop must be bounded even before the first setup.
 *
 * **F11-01, the deadlock.** A task that throws `ProtocolException` asks layer 5 to restart the connection. The old
 * `EndToEndLayer.restartConnection` resumed after its delay ON the attempt's single-thread dispatcher and called the
 * blocking `stop()` there. Socket close runs `runBlocking { closeSocket(reason) }`, and both socket implementations
 * dispatch `closeInbound` back onto that same single thread via `withContext(inputDispatcher)`; inside the
 * `runBlocking` that is a real dispatch onto a queue whose only thread is the one parked inside the `runBlocking`.
 * The thread waits forever for work only it can run, holding the start/stop lock, so every later start or stop from
 * anywhere blocks behind it too. These tests drive `restartConnection` exactly where `TaskRunner` does, against the
 * REAL single-thread dispatcher and the real socket-close path, for CSP and for D2M, and assert the three facts the
 * review demands: stop returns, the old attempt is disposed, and a replacement connection starts.
 *
 * **F11-02, the unbounded stop.** `stop()` used to join the connection job without cancelling it. Before the first
 * `setup()` the job is suspended in `awaitAppReady()`, which production implements as
 * `AppStartupMonitor.awaitAll()` - documented to suspend forever when startup has an error - and with no graph there
 * is no socket whose close could unblock anything, so the join waited forever. Startup cleanup, network reconnect,
 * app lock and lifecycle teardown all run through this stop.
 *
 * Every asynchronous assertion here is BOUNDED: on the pre-fix code these tests fail within their timeout instead of
 * hanging the suite (teardown stops through a daemon thread for the same reason).
 */
internal class TaskRequestedRestartTest {
    private companion object {
        private const val WAIT_SECONDS = 15L
    }

    private lateinit var serverAddressProvider: TestServerAddressProvider
    private var connection: ServerConnection? = null
    private var silentServer: ServerSocket? = null

    @BeforeTest
    fun setUp() {
        val random = SecureRandom()
        val skPublic = ByteArray(32).also { random.nextBytes(it) }
        val skPublicAlt = ByteArray(32).also { random.nextBytes(it) }
        serverAddressProvider = TestServerAddressProvider(skPublic, skPublicAlt)
    }

    @AfterTest
    fun tearDown() {
        // On a deadlocked connection (the red-probe world) a plain stop() would block this thread forever behind the
        // held start/stop lock and hang the whole suite. The daemon thread keeps a failure a failure.
        connection?.let { stopBounded(it) }
        connection = null
        silentServer?.close()
        silentServer = null
    }

    // -----------------------------------------------------------------------------------------------------------------
    // F11-01: the restart itself, on the real dispatch boundary
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a task-requested CSP restart survives the single-thread dispatch boundary`() {
        val dispatchersBefore = SingleThreadedServerConnectionDispatcher.openDispatcherCount
        val graphsCreated = AtomicInteger(0)
        val attemptEntered = CountDownLatch(1)
        val layer5 = AtomicReference<EndToEndLayer?>(null)

        val connection = createCspConnection(
            graphsCreated = graphsCreated,
            layer5 = layer5,
            onConnectAttempt = { attemptEntered.countDown() },
        )
        this.connection = connection

        connection.start()
        assertTrue(attemptEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first attempt must have begun")
        val requestingLayer = awaitNotNull(layer5, "the first attempt must have built its layer stack")

        // Exactly what TaskRunner.restartConnection does when a task throws ProtocolException.
        requestingLayer.restartConnection(0)

        // Stop returned and a replacement started: the second graph can only exist if the blocking stop+start
        // completed rather than deadlocking the worker.
        assertTrue(
            awaitCondition { graphsCreated.get() >= 2 },
            "the restart must produce a replacement attempt; before the fix the worker deadlocked in stop() and " +
                "no second graph was ever built (graphs=${graphsCreated.get()})",
        )
        // The old attempt was disposed: only the replacement's dispatcher may remain open.
        assertTrue(
            awaitCondition { SingleThreadedServerConnectionDispatcher.openDispatcherCount == dispatchersBefore + 1 },
            "exactly the replacement attempt may hold a dispatcher after the restart, but " +
                "${SingleThreadedServerConnectionDispatcher.openDispatcherCount - dispatchersBefore} were open",
        )

        // And the whole connection is still stoppable afterwards - the deadlock held the start/stop lock, so this
        // is the assertion that the lock came back out alive.
        assertTrue(stopBounded(connection), "stop() after a task-requested restart must complete")
        assertTrue(
            awaitCondition { SingleThreadedServerConnectionDispatcher.openDispatcherCount == dispatchersBefore },
            "the final stop must dispose the replacement attempt too",
        )
    }

    @Test
    fun `a task-requested D2M restart survives the single-thread dispatch boundary`() {
        // A real TCP listener that accepts the WebSocket upgrade and never answers it, so the D2M attempt stays
        // parked in connect() long enough to be restarted - which is all the deadlock needs, because the D2M close
        // path (D2mSocket.closeSocket -> closeInbound -> withContext(inputDispatcher)) runs regardless of whether
        // the socket ever opened.
        val server = ServerSocket(0).also { silentServer = it }
        thread(isDaemon = true, name = "silent-mediator") {
            try {
                while (true) {
                    server.accept()
                }
            } catch (e: Exception) {
                // The listener is closed by tearDown; ending the thread is the point.
            }
        }
        serverAddressProvider = TestServerAddressProvider(
            ByteArray(32),
            ByteArray(32),
            mediatorUrl = "ws://127.0.0.1:${server.localPort}/",
        )

        val dispatchersBefore = SingleThreadedServerConnectionDispatcher.openDispatcherCount
        val graphsCreated = AtomicInteger(0)
        val layer5 = AtomicReference<EndToEndLayer?>(null)

        val connection = createD2mConnection(graphsCreated, layer5)
        this.connection = connection

        connection.start()
        assertTrue(
            awaitCondition { graphsCreated.get() >= 1 && connection.connectionState == ConnectionState.CONNECTING },
            "the first D2M attempt must be connecting",
        )
        val requestingLayer = awaitNotNull(layer5, "the first attempt must have built its layer stack")

        requestingLayer.restartConnection(0)

        assertTrue(
            awaitCondition { graphsCreated.get() >= 2 },
            "the restart must produce a replacement D2M attempt; before the fix the worker deadlocked in the " +
                "socket-close dispatch (graphs=${graphsCreated.get()})",
        )
        assertTrue(stopBounded(connection), "stop() after a task-requested D2M restart must complete")
        assertTrue(
            awaitCondition { SingleThreadedServerConnectionDispatcher.openDispatcherCount == dispatchersBefore },
            "every attempt must be disposed after the final stop",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------
    // F11-01: the coordinator's own gate
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a restart requested by a superseded attempt is dropped`() {
        val graphsCreated = AtomicInteger(0)
        val attemptEntered = CountDownLatch(1)

        val connection = createCspConnection(
            graphsCreated = graphsCreated,
            layer5 = AtomicReference(null),
            onConnectAttempt = { attemptEntered.countDown() },
        )
        this.connection = connection
        connection.start()
        assertTrue(attemptEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the attempt must have begun")
        val graphsBefore = graphsCreated.get()

        // The requesting attempt's iteration already ended, so the reconnect loop owns the replacement. A restart
        // firing here would stop the SUCCESSOR, which is exactly what "unable to restart a superseded attempt" forbids.
        val closedAttempt = CompletableDeferred<Unit>().also { it.complete(Unit) }
        (connection as BaseServerConnection).requestRestart(closedAttempt)

        Thread.sleep(500)
        assertEquals(graphsBefore, graphsCreated.get(), "a superseded attempt's restart must not produce a new attempt")
        assertTrue(connection.isRunning, "and must not have stopped the live connection")
    }

    @Test
    fun `a restart request does not resurrect an explicitly stopped connection`() {
        val graphsCreated = AtomicInteger(0)
        val attemptEntered = CountDownLatch(1)

        val connection = createCspConnection(
            graphsCreated = graphsCreated,
            layer5 = AtomicReference(null),
            onConnectAttempt = { attemptEntered.countDown() },
        )
        this.connection = connection
        connection.start()
        assertTrue(attemptEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the attempt must have begun")

        assertTrue(stopBounded(connection), "the explicit stop must complete")
        val graphsBefore = graphsCreated.get()

        // A restart that was in flight when the user stopped the connection must not bring it back: reconnectAllowed
        // is false until the next start(), and the coordinator's gate reads it before acting.
        (connection as BaseServerConnection).requestRestart(CompletableDeferred())

        Thread.sleep(500)
        assertEquals(graphsBefore, graphsCreated.get(), "a stopped connection must stay stopped")
        assertEquals(ConnectionState.DISCONNECTED, connection.connectionState)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // F11-02: stop is bounded before the first setup, and afterwards
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a stop while start waits for app readiness completes without a graph, and a later start works`() {
        val readiness = CompletableDeferred<Unit>()
        val graphsCreated = AtomicInteger(0)
        val attemptEntered = CountDownLatch(1)

        val connection = createCspConnection(
            graphsCreated = graphsCreated,
            layer5 = AtomicReference(null),
            onConnectAttempt = { attemptEntered.countDown() },
            awaitAppReady = { readiness.await() },
        )
        this.connection = connection

        connection.start()
        // The job is now suspended in awaitAppReady(). Production readiness is allowed to suspend forever when
        // startup has an error, so nothing will ever complete it from inside; only a cancelling stop can end this.
        assertTrue(awaitCondition { connection.isRunning }, "the start must be in flight")

        assertTrue(
            stopBounded(connection),
            "stop() must finish while readiness is held; before the fix it joined a job that only readiness could " +
                "ever unblock",
        )
        assertEquals(0, graphsCreated.get(), "no setup() may have run: the stop arrived before the first attempt")
        assertEquals(ConnectionState.DISCONNECTED, connection.connectionState)

        // Releasing readiness and starting again must produce a normal connection: the cancelled first job must not
        // have poisoned the latch, the generation, or the readiness gate.
        readiness.complete(Unit)
        connection.start()
        assertTrue(
            attemptEntered.await(WAIT_SECONDS, TimeUnit.SECONDS),
            "a fresh start after the pre-setup stop must connect normally",
        )
        assertTrue(stopBounded(connection), "and must remain stoppable")
    }

    @Test
    fun `a stop during the reconnect backoff completes promptly`() {
        val graphsCreated = AtomicInteger(0)
        val attemptsMade = CountDownLatch(1)

        val connection = createCspConnection(
            graphsCreated = graphsCreated,
            layer5 = AtomicReference(null),
            onConnectAttempt = {
                attemptsMade.countDown()
                throw java.io.IOException("simulated connect failure")
            },
        )
        this.connection = connection

        connection.start()
        assertTrue(attemptsMade.await(WAIT_SECONDS, TimeUnit.SECONDS), "the failing attempt must have run")
        // Give the loop a moment to enter the backoff delay between attempts.
        Thread.sleep(200)

        val stopStarted = System.currentTimeMillis()
        assertTrue(stopBounded(connection), "stop() during the backoff must complete")
        val stopMillis = System.currentTimeMillis() - stopStarted
        assertTrue(
            stopMillis < 5_000,
            "the backoff wait must be interruptible; stop took ${stopMillis}ms, which means the delay ran to its end",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------------------------------------------------

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(20)
        }
        return condition()
    }

    private fun <T> awaitNotNull(reference: AtomicReference<T?>, message: String): T {
        assertTrue(awaitCondition { reference.get() != null }, message)
        return reference.get()!!
    }

    /** Runs stop() on a daemon thread with a bound, so a deadlocked stop fails the test instead of hanging the JVM. */
    private fun stopBounded(connection: ServerConnection): Boolean {
        val stopper = thread(isDaemon = true, name = "bounded-stop") {
            try {
                connection.stop()
            } catch (e: Exception) {
                // A throwing stop is a failing stop; the join below still returns.
            }
        }
        stopper.join(WAIT_SECONDS * 1000)
        return !stopper.isAlive
    }

    private fun createCspConnection(
        graphsCreated: AtomicInteger,
        layer5: AtomicReference<EndToEndLayer?>,
        onConnectAttempt: () -> Unit,
        awaitAppReady: suspend () -> Unit = { },
    ): ServerConnection {
        val configuration = CspConnectionConfiguration(
            TestIdentityStore(),
            serverAddressProvider,
            Version(),
            assertDispatcherContext = true,
            TestNoopDeviceCookieManager(),
            noopIncomingMessageProcessor(),
            NoopTaskManager(),
            { emptyArray() },
            ipv6 = false,
            SocketFactory {
                onConnectAttempt()
                TestSocket()
            },
        )
        val taskManager = NoopTaskManager()

        val dependencyProvider = ServerConnectionDependencyProvider { connection ->
            graphsCreated.incrementAndGet()
            val controllers = CspControllers(configuration)
            val socket = CspSocket(
                configuration.socketFactory,
                TestChatServerAddressProvider(),
                controllers.serverConnectionController.ioProcessingStoppedSignal,
                controllers.serverConnectionController.dispatcher.coroutineContext,
            )
            val endToEndLayer = EndToEndLayer(
                controllers.serverConnectionController.dispatcher.coroutineContext,
                controllers.serverConnectionController,
                connection,
                configuration.incomingMessageProcessor,
                taskManager,
                NoopConnectionLockProvider,
            )
            layer5.set(endToEndLayer)
            ServerConnectionDependencies(
                controllers.mainController,
                socket,
                ServerConnectionLayers(
                    CspFrameLayer(),
                    MultiplexLayer(controllers.serverConnectionController),
                    AuthLayer(controllers.layer3Controller),
                    MonitoringLayer(connection, controllers.layer4Controller),
                    endToEndLayer,
                ),
                NoopConnectionLockProvider,
                taskManager,
            )
        }

        return CspConnectionImpl(dependencyProvider, awaitAppReady)
    }

    private fun createD2mConnection(
        graphsCreated: AtomicInteger,
        layer5: AtomicReference<EndToEndLayer?>,
    ): ServerConnection {
        val multiDeviceKeys = MultiDeviceKeys(ByteArray(32))
        val multiDeviceProperties = MultiDeviceProperties(
            registrationTime = null,
            mediatorDeviceId = DeviceId(1u),
            cspDeviceId = DeviceId(2u),
            keys = multiDeviceKeys,
            deviceInfo = D2dMessage.DeviceInfo.INVALID_DEVICE_INFO,
            protocolVersion = D2mProtocolVersion(0u, 0u),
            serverInfoListener = { },
        )
        val configuration = D2mConnectionConfiguration(
            TestIdentityStore(),
            serverAddressProvider,
            Version(),
            assertDispatcherContext = true,
            TestNoopDeviceCookieManager(),
            noopIncomingMessageProcessor(),
            NoopTaskManager(),
            { multiDeviceProperties },
            closeListener = { },
            OkHttpClient(),
        )
        val taskManager = NoopTaskManager()

        val dependencyProvider = ServerConnectionDependencyProvider { connection ->
            graphsCreated.incrementAndGet()
            val controllers = D2mControllers(configuration)
            val addressProvider = D2mServerAddressProvider(
                serverAddressProvider,
                multiDeviceKeys.dgid,
                "00",
            )
            val socket = D2mSocket(
                configuration.okHttpClient,
                addressProvider,
                controllers.mainController.ioProcessingStoppedSignal,
                controllers.serverConnectionController.dispatcher.coroutineContext,
            )
            val endToEndLayer = EndToEndLayer(
                controllers.serverConnectionController.dispatcher.coroutineContext,
                controllers.serverConnectionController,
                connection,
                configuration.incomingMessageProcessor,
                taskManager,
                NoopConnectionLockProvider,
            )
            layer5.set(endToEndLayer)
            ServerConnectionDependencies(
                controllers.mainController,
                socket,
                ServerConnectionLayers(
                    D2mFrameLayer(),
                    MultiplexLayer(controllers.serverConnectionController),
                    AuthLayer(controllers.layer3Controller),
                    MonitoringLayer(connection, controllers.layer4Controller),
                    endToEndLayer,
                ),
                NoopConnectionLockProvider,
                taskManager,
            )
        }

        return D2mConnectionImpl(dependencyProvider, configuration.closeListener) { }
    }

    private fun noopIncomingMessageProcessor(): IncomingMessageProcessor = object : IncomingMessageProcessor {
        override suspend fun processIncomingCspMessage(messageBox: MessageBox, handle: ActiveTaskCodec) = Unit
        override suspend fun processIncomingD2mMessage(
            message: InboundD2mMessage.Reflected,
            handle: ActiveTaskCodec,
        ) = Unit

        override fun processIncomingServerAlert(alertData: CspMessage.ServerAlertData) = Unit
        override fun processIncomingServerError(errorData: CspMessage.ServerErrorData) = Unit
    }

    private object NoopConnectionLockProvider : ConnectionLockProvider {
        override fun acquire(timeoutMillis: Long, tag: ConnectionLockProvider.ConnectionLogTag): ConnectionLock =
            object : ConnectionLock {
                override fun release() = Unit
                override fun isHeld() = false
            }
    }
}
