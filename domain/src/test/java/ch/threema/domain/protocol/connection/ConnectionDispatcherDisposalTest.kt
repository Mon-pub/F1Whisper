package ch.threema.domain.protocol.connection

import ch.threema.domain.protocol.Version
import ch.threema.domain.protocol.connection.csp.CspConnectionConfiguration
import ch.threema.domain.protocol.connection.csp.CspConnectionImpl
import ch.threema.domain.protocol.connection.csp.CspControllers
import ch.threema.domain.protocol.connection.csp.socket.CspSocket
import ch.threema.domain.protocol.connection.csp.socket.SocketFactory
import ch.threema.domain.protocol.connection.data.CspMessage
import ch.threema.domain.protocol.connection.data.InboundD2mMessage
import ch.threema.domain.protocol.connection.layer.AuthLayer
import ch.threema.domain.protocol.connection.layer.CspFrameLayer
import ch.threema.domain.protocol.connection.layer.EndToEndLayer
import ch.threema.domain.protocol.connection.layer.MonitoringLayer
import ch.threema.domain.protocol.connection.layer.MultiplexLayer
import ch.threema.domain.protocol.connection.layer.ServerConnectionLayers
import ch.threema.domain.protocol.csp.coders.MessageBox
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.IncomingMessageProcessor
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * F1Whisper (tenth fork review, F10-02): regression for the per-attempt dispatcher leak.
 *
 * The defect: every connection ATTEMPT calls `setup()`, which replaces the `dependencies` field with a freshly built
 * graph, and every graph allocates a [SingleThreadedServerConnectionDispatcher] with its own single-thread executor.
 * Nothing closed the superseded ones. Both close calls that existed went through the CURRENT `dependencies`, so by the
 * time either could run, the reference to the graph that owned the executor had already been overwritten. One thread
 * leaked per reconnect for the life of the process: the reporting device's ANR dump declared 287 managed threads, 213
 * of them named `ServerConnectionWorker-*`, numbered 0 to 213 with exactly one missing - the single dispatcher that an
 * exception callback had happened to close - and every one of them parked in its own empty queue.
 *
 * On timing. The review asks for 100 sequential in-loop reconnects. The production backoff is real and exponential
 * (1s, 2s, 4s, 8s, then 10s a time), and this loop runs on `Dispatchers.Default` rather than a test scheduler, so 100
 * of them would take over fifteen minutes of wall clock in a unit test. The invariant is instead pinned three ways that
 * together say the same thing and run in seconds: the disposal contract directly on the dispatcher, four genuine
 * in-loop reconnects inside ONE start() session, and thirty attempts by start/stop. What is deliberately NOT claimed
 * here is a 100-iteration soak; that belongs to the device run.
 */
internal class ConnectionDispatcherDisposalTest {
    private companion object {
        private const val WAIT_SECONDS = 10L

        /** 1s + 2s + 4s of production backoff, plus room for the attempts themselves. */
        private const val FOUR_ATTEMPT_WAIT_SECONDS = 25L
    }

    private lateinit var serverAddressProvider: TestServerAddressProvider
    private var connection: ServerConnection? = null

    @BeforeTest
    fun setUp() {
        val random = SecureRandom()
        val skPublic = ByteArray(32).also { random.nextBytes(it) }
        val skPublicAlt = ByteArray(32).also { random.nextBytes(it) }
        serverAddressProvider = TestServerAddressProvider(skPublic, skPublicAlt)
    }

    @AfterTest
    fun tearDown() {
        try {
            connection?.stop()
        } catch (e: Exception) {
            // Best effort. A test that has already failed must not also hang the suite here.
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The disposal contract, directly on the resource that leaked
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `closing a dispatcher cancels the attempt's coroutines and shuts its executor down`() {
        val before = SingleThreadedServerConnectionDispatcher.openDispatcherCount
        val dispatcher = SingleThreadedServerConnectionDispatcher(assertContext = false)
        assertEquals(before + 1, SingleThreadedServerConnectionDispatcher.openDispatcherCount)

        // Exactly the shape the layers use: a coroutine that parks on something a superseded attempt will never
        // complete. This is what kept the executor thread reachable and busy-looking forever.
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val neverCompletes = CompletableDeferred<Unit>()
        dispatcher.scope.launch {
            try {
                entered.countDown()
                neverCompletes.await()
            } finally {
                cancelled.countDown()
            }
        }
        assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the attempt coroutine must have started")

        val threadName = workerThreadNameOf(dispatcher)
        assertTrue(liveThreadNames().contains(threadName), "the executor thread must exist while the attempt is live")

        runBlocking { dispatcher.closeAndJoin() }

        assertTrue(
            cancelled.await(WAIT_SECONDS, TimeUnit.SECONDS),
            "closing the attempt must cancel the coroutines it owns; without an owner nothing could ever cancel them",
        )
        assertEquals(before, SingleThreadedServerConnectionDispatcher.openDispatcherCount)
        assertTrue(
            awaitThreadGone(threadName),
            "the executor thread must be gone; 213 of these were still parked in the reporting device's ANR dump",
        )
    }

    @Test
    fun `a dispatcher is closed once however many exits reach it`() {
        val before = SingleThreadedServerConnectionDispatcher.openDispatcherCount
        val dispatcher = SingleThreadedServerConnectionDispatcher(assertContext = false)

        // All three of the loop's own finally, the exception callback and an explicit stop can legitimately reach the
        // same attempt. A count that went negative here would mean disposal was being double-counted, and one that
        // stayed high would mean it was being skipped.
        runBlocking { dispatcher.closeAndJoin() }
        dispatcher.close()
        runBlocking { dispatcher.closeAndJoin() }

        assertEquals(before, SingleThreadedServerConnectionDispatcher.openDispatcherCount)
    }

    @Test
    fun `the attempt scope isolates its coroutines from each other`() {
        // A SupervisorJob, so one layer's failure cannot take the rest of the attempt down with it. The bare scopes
        // gave this by accident because they had no shared parent at all; it has to be deliberate now.
        val dispatcher = SingleThreadedServerConnectionDispatcher(assertContext = false)
        val survivorRan = CountDownLatch(1)
        dispatcher.scope.launch { throw IllegalStateException("one layer fails") }
        dispatcher.scope.launch {
            delay(50)
            survivorRan.countDown()
        }

        assertTrue(survivorRan.await(WAIT_SECONDS, TimeUnit.SECONDS), "a sibling failure must not cancel the attempt")
        runBlocking { dispatcher.closeAndJoin() }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The reconnect loop itself
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `four genuine in-loop reconnects leave one live dispatcher, not four`() {
        val attemptsMade = CountDownLatch(4)
        val graphsCreated = AtomicInteger(0)
        val before = SingleThreadedServerConnectionDispatcher.openDispatcherCount

        val connection = createConnection(
            graphsCreated = graphsCreated,
            onConnectAttempt = {
                attemptsMade.countDown()
                // Fail the connect so the loop reconnects. This is the ordinary reconnect path: the inner catch runs,
                // then the tail, then the backoff, then setup() builds the next graph and overwrites the field.
                throw IOException("simulated connect failure")
            },
        )
        this.connection = connection

        connection.start()
        assertTrue(
            attemptsMade.await(FOUR_ATTEMPT_WAIT_SECONDS, TimeUnit.SECONDS),
            "the loop must have made four connect attempts",
        )

        // Four graphs were built, so four dispatchers were allocated. Before the fix all four were still open here,
        // which is the leak in miniature: the count grew by one per reconnect and never came down.
        assertTrue(graphsCreated.get() >= 4, "expected at least four dependency graphs, got ${graphsCreated.get()}")
        val openDuringSession = SingleThreadedServerConnectionDispatcher.openDispatcherCount - before
        assertTrue(
            openDuringSession <= 1,
            "at most the current attempt may be open while a session is running, but $openDuringSession were, " +
                "after ${graphsCreated.get()} attempts",
        )

        connection.stop()
        assertTrue(
            awaitOpenDispatcherCount(before),
            "an explicit stop must leave the last attempt closed too; open count settled at " +
                "${SingleThreadedServerConnectionDispatcher.openDispatcherCount}, expected $before",
        )
    }

    @Test
    fun `repeated sessions do not grow the dispatcher or thread count`() {
        // Volume, at a fraction of the backoff cost: twelve sessions of two attempts each. Two attempts is the least
        // that exercises SUPERSESSION - the route where a graph is replaced by the next `setup()` while the job runs -
        // which is the route the old code could not clean up at all, since its only close went through whatever
        // `dependencies` pointed at by then. The invariant under test is the one that failed on the device: the counts
        // must not grow with the number of attempts.
        val before = SingleThreadedServerConnectionDispatcher.openDispatcherCount
        val workersBefore = liveWorkerThreadCount()
        val graphsCreated = AtomicInteger(0)
        val sessions = 12

        repeat(sessions) {
            val secondAttemptReached = CountDownLatch(2)
            val connection = createConnection(
                graphsCreated = graphsCreated,
                onConnectAttempt = {
                    secondAttemptReached.countDown()
                    throw IOException("simulated connect failure")
                },
            )
            this.connection = connection
            connection.start()
            assertTrue(
                secondAttemptReached.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "each session must reach a second attempt, or supersession is never exercised",
            )
            connection.stop()
        }
        this.connection = null

        val expectedGraphs = sessions * 2
        assertTrue(
            graphsCreated.get() >= expectedGraphs,
            "expected at least $expectedGraphs graphs, got ${graphsCreated.get()}",
        )
        assertTrue(
            awaitOpenDispatcherCount(before),
            "after ${graphsCreated.get()} attempts every dispatcher must be closed; open count settled at " +
                "${SingleThreadedServerConnectionDispatcher.openDispatcherCount}, expected $before",
        )
        val leaked = liveWorkerThreadCount() - workersBefore
        assertTrue(
            leaked <= 2,
            "worker threads must not grow with the attempt count; $leaked survived ${graphsCreated.get()} attempts. " +
                "Before the fix this grew one-for-one, which is what put 213 of them in the device's ANR dump",
        )
    }

    @Test
    fun `an explicit stop during the very first attempt still disposes it`() {
        val before = SingleThreadedServerConnectionDispatcher.openDispatcherCount
        val attemptEntered = CountDownLatch(1)
        val graphsCreated = AtomicInteger(0)

        val connection = createConnection(
            graphsCreated = graphsCreated,
            onConnectAttempt = {
                attemptEntered.countDown()
                // Hold the attempt open across the stop, so disposal has to happen on the teardown route rather than
                // on a route the attempt would have taken anyway.
                Thread.sleep(400)
                throw IOException("simulated connect failure")
            },
        )
        this.connection = connection

        connection.start()
        assertTrue(attemptEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first attempt must have begun")
        connection.stop()
        this.connection = null

        assertTrue(
            awaitOpenDispatcherCount(before),
            "a stop during connect must still dispose the attempt; open count settled at " +
                "${SingleThreadedServerConnectionDispatcher.openDispatcherCount}, expected $before",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Control
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun legacyUndisposedAttemptsGrowWithoutBound() {
        // Proves the meter reads what it claims to. Ten graphs' worth of dispatchers, none disposed - the old
        // behaviour - and both the open count and the live worker threads rise one-for-one.
        val before = SingleThreadedServerConnectionDispatcher.openDispatcherCount
        val workersBefore = liveWorkerThreadCount()

        val leaked = (1..10).map { SingleThreadedServerConnectionDispatcher(assertContext = false) }
        // A single-thread executor creates its thread on the first submission, so give each one the work a real
        // attempt's layers would have given it. Otherwise this measures nothing.
        leaked.forEach { workerThreadNameOf(it) }

        assertEquals(before + 10, SingleThreadedServerConnectionDispatcher.openDispatcherCount)
        assertTrue(
            liveWorkerThreadCount() - workersBefore >= 10,
            "this is the defect: one parked executor thread per attempt, retained for the life of the process",
        )

        leaked.forEach { it.close() }
        assertEquals(before, SingleThreadedServerConnectionDispatcher.openDispatcherCount)
        assertTrue(
            awaitWorkerThreadCountBelow(workersBefore + 10),
            "and closing them is what takes the threads away again",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------------------------------------------------

    private fun awaitOpenDispatcherCount(expected: Int): Boolean {
        // Disposal runs as the attempt unwinds, so poll rather than assert on a fixed sleep.
        val deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000
        while (System.currentTimeMillis() < deadline) {
            if (SingleThreadedServerConnectionDispatcher.openDispatcherCount == expected) {
                return true
            }
            Thread.sleep(20)
        }
        return SingleThreadedServerConnectionDispatcher.openDispatcherCount == expected
    }

    private fun awaitThreadGone(name: String): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000
        while (System.currentTimeMillis() < deadline) {
            if (!liveThreadNames().contains(name)) {
                return true
            }
            Thread.sleep(20)
        }
        return !liveThreadNames().contains(name)
    }

    private fun awaitWorkerThreadCountBelow(limit: Int): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000
        while (System.currentTimeMillis() < deadline) {
            if (liveWorkerThreadCount() < limit) {
                return true
            }
            Thread.sleep(20)
        }
        return liveWorkerThreadCount() < limit
    }

    private fun liveThreadNames(): Set<String> = Thread.getAllStackTraces().keys.mapNotNull { it.name }.toSet()

    private fun liveWorkerThreadCount(): Int = liveThreadNames().count { it.startsWith("ServerConnectionWorker-") }

    /**
     * The executor thread is named at construction, so its name identifies this dispatcher. Reading it back through the
     * scope is the only way to learn it from outside without exposing internals for the test's convenience.
     */
    private fun workerThreadNameOf(dispatcher: ServerConnectionDispatcher): String {
        val name = CompletableDeferred<String>()
        dispatcher.scope.launch { name.complete(Thread.currentThread().name) }
        // The coroutine debug agent appends " @coroutine#N" to the thread name while a coroutine is on it, so the
        // reading has to be trimmed back to the name the factory actually gave the thread.
        return runBlocking { name.await() }.substringBefore(" @")
    }

    private fun createConnection(
        graphsCreated: AtomicInteger,
        onConnectAttempt: () -> Unit,
    ): ServerConnection {
        val configuration = createConfiguration(onConnectAttempt)
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

            ServerConnectionDependencies(
                controllers.mainController,
                socket,
                ServerConnectionLayers(
                    CspFrameLayer(),
                    MultiplexLayer(controllers.serverConnectionController),
                    AuthLayer(controllers.layer3Controller),
                    MonitoringLayer(connection, controllers.layer4Controller),
                    EndToEndLayer(
                        controllers.serverConnectionController.dispatcher.coroutineContext,
                        controllers.serverConnectionController,
                        connection,
                        configuration.incomingMessageProcessor,
                        taskManager,
                        NoopConnectionLockProvider,
                    ),
                ),
                NoopConnectionLockProvider,
                taskManager,
            )
        }

        return CspConnectionImpl(dependencyProvider) { }
    }

    private fun createConfiguration(onConnectAttempt: () -> Unit): CspConnectionConfiguration {
        val incomingMessageProcessor = object : IncomingMessageProcessor {
            override suspend fun processIncomingCspMessage(messageBox: MessageBox, handle: ActiveTaskCodec) = Unit
            override suspend fun processIncomingD2mMessage(
                message: InboundD2mMessage.Reflected,
                handle: ActiveTaskCodec,
            ) = Unit

            override fun processIncomingServerAlert(alertData: CspMessage.ServerAlertData) = Unit
            override fun processIncomingServerError(errorData: CspMessage.ServerErrorData) = Unit
        }

        return CspConnectionConfiguration(
            TestIdentityStore(),
            serverAddressProvider,
            Version(),
            assertDispatcherContext = true,
            TestNoopDeviceCookieManager(),
            incomingMessageProcessor,
            NoopTaskManager(),
            { emptyArray() },
            ipv6 = false,
            // The socket factory is where an attempt is made to succeed or fail. Throwing here reaches the loop the
            // same way a real connect failure does, through CspSocket.connect().
            SocketFactory {
                onConnectAttempt()
                TestSocket()
            },
        )
    }

    private object NoopConnectionLockProvider : ConnectionLockProvider {
        override fun acquire(timeoutMillis: Long, tag: ConnectionLockProvider.ConnectionLogTag): ConnectionLock =
            object : ConnectionLock {
                override fun release() = Unit
                override fun isHeld() = false
            }
    }
}
