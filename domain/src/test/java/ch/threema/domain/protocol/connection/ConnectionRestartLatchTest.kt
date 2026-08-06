package ch.threema.domain.protocol.connection

import ch.threema.domain.protocol.Version
import ch.threema.domain.protocol.connection.csp.CspConnectionConfiguration
import ch.threema.domain.protocol.connection.csp.CspConnectionImpl
import ch.threema.domain.protocol.connection.csp.CspControllers
import ch.threema.domain.protocol.connection.csp.socket.CspSocket
import ch.threema.domain.protocol.connection.csp.socket.SocketFactory
import ch.threema.domain.protocol.connection.data.CspMessage
import ch.threema.domain.protocol.connection.data.InboundD2mMessage
import ch.threema.domain.protocol.connection.data.InboundMessage
import ch.threema.domain.protocol.connection.layer.AuthLayer
import ch.threema.domain.protocol.connection.layer.CspFrameLayer
import ch.threema.domain.protocol.connection.layer.EndToEndLayer
import ch.threema.domain.protocol.connection.layer.Layer5Codec
import ch.threema.domain.protocol.connection.layer.MonitoringLayer
import ch.threema.domain.protocol.connection.layer.MultiplexLayer
import ch.threema.domain.protocol.connection.layer.ServerConnectionLayers
import ch.threema.domain.protocol.connection.socket.ServerSocketCloseReason
import ch.threema.domain.protocol.csp.coders.MessageBox
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.IncomingMessageProcessor
import ch.threema.domain.taskmanager.InternalTaskManager
import ch.threema.domain.taskmanager.QueueSendCompleteListener
import ch.threema.domain.taskmanager.Task
import ch.threema.domain.taskmanager.TaskCodec
import ch.threema.domain.taskmanager.TaskManager
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/**
 * F1Whisper: regression for the restart latch.
 *
 * The observed failure was a phone that reported DISCONNECTED, could neither send nor receive, and
 * whose own connectivity probes all passed at that moment, including a full CSP hello to the chat
 * server. It never retried, and only a force close restored service. Force close being the only cure
 * is the tell: the flags that gate a restart are process scoped.
 *
 * This test pins the half of that which lives in the domain layer: **once the connection job has
 * terminated abnormally, a subsequent [ServerConnection.start] must create a new job rather than
 * returning early on a stale flag.** `running` used to be cleared only by a statement placed after
 * the reconnect loop and outside any `finally`, so any exit that was not a normal loop exit left it
 * true forever, and `stop()` never cleared it either.
 *
 * The abnormal termination is injected through the connection's own `awaitAppReady` parameter, which
 * is invoked before the loop is entered and outside the loop's `try`. Note what is being modelled and
 * what is not: the production `awaitAppReady` (`appStartupMonitor.awaitAll()`) suspends on state flow
 * collection and does not throw of its own accord, so this is a vehicle for reaching the escape path,
 * not a claim that this particular call site throws in production. The escape path itself is
 * production reachable, because `prepareReconnect()` rethrows `CancellationException` and any failure
 * in a child coroutine of the connection job cancels the parent.
 */
internal class ConnectionRestartLatchTest {
    private companion object {
        private const val STATE_WAIT_SECONDS = 5L
    }

    private lateinit var testSocket: TestSocket
    private lateinit var serverAddressProvider: TestServerAddressProvider
    private var connection: ServerConnection? = null

    @BeforeTest
    fun setUp() {
        testSocket = TestSocket()
        val random = SecureRandom()
        val skPublic = ByteArray(32).also { random.nextBytes(it) }
        val skPublicAlt = ByteArray(32).also { random.nextBytes(it) }
        serverAddressProvider = TestServerAddressProvider(skPublic, skPublicAlt)
    }

    @AfterTest
    fun tearDown() {
        // Best effort. A test that has already failed must not also hang the suite here.
        try {
            connection?.stop()
        } catch (e: Exception) {
            // Nothing to do
        }
    }

    @Test
    fun `a subsequent start creates a new job after the connection job terminated abnormally`() {
        val failNextAwaitAppReady = AtomicBoolean(true)
        val firstAwaitAppReadyEntered = CountDownLatch(1)

        val connection = createConnection {
            if (failNextAwaitAppReady.getAndSet(false)) {
                firstAwaitAppReadyEntered.countDown()
                // Abnormal termination of the connection job, modelled with a CancellationException
                // on purpose, for two independent reasons.
                //
                // Correctness: this is the route the fix actually has to cover. A non-cancellation
                // throwable escaping this body reaches ThreemaUncaughtExceptionHandler and kills the
                // process, and the restart clears every process scoped latch, so that route is self
                // curing. A cancellation is normal completion for a parentless job: nothing is
                // reported, the process survives, and the latch would be stranded. Since
                // prepareReconnect() now rethrows CancellationException, this is exactly the shape
                // that reaches the `finally`.
                //
                // Hygiene: a non-cancellation throwable here would escape onto the shared dispatcher
                // and be collected by kotlinx-coroutines-test's process-wide exception collector,
                // which reports it against whichever `runTest` runs next. That contaminated
                // CspConnectionTest in the pre-fix run. A cancellation is not collected, so this test
                // cannot leak into another.
                throw CancellationException("simulated abnormal termination of the connection job")
            }
        }
        this.connection = connection

        val reachedConnecting = CountDownLatch(1)
        connection.addConnectionStateListener { state ->
            if (state == ConnectionState.CONNECTING) {
                reachedConnecting.countDown()
            }
        }

        connection.start()
        assertTrue(
            firstAwaitAppReadyEntered.await(STATE_WAIT_SECONDS, TimeUnit.SECONDS),
            "the first start must have reached awaitAppReady",
        )
        // Let the failing coroutine finish unwinding so the second start is not merely racing an
        // still-active job, which would make this test pass for the wrong reason.
        Thread.sleep(300)

        connection.start()

        assertTrue(
            reachedConnecting.await(STATE_WAIT_SECONDS, TimeUnit.SECONDS),
            "after an abnormal termination of the connection job, a subsequent start() must create " +
                "a new job and begin connecting; it returned early instead, which is the wedge this " +
                "test exists to prevent",
        )
    }

    @Test
    fun `a normal exit clears the restart latch`() {
        // DIRECTION 1 of the generation guard, and it is not redundant with the other tests.
        //
        // The guard in the job's `finally` is conditional, so it can fail in two opposite ways: by
        // firing when it should not (the race, covered below) and by never firing at all. The second
        // is the ORIGINAL stranding defect returning in the costume of a race fix, and it is strictly
        // worse than the race, because the race self recovers on the next start() and a permanently
        // stranded flag does not.
        //
        // Nothing else here catches it. Every other test in this class asserts that a subsequent
        // start() connects, and start() deliberately RECOVERS from a stranded flag (it logs
        // "Restart latch was set with no live connection job; recovering"). So a `finally` that never
        // cleared the flag would leave all of them green. This asserts the flag itself.
        val terminated = CountDownLatch(1)
        val firstAttempt = AtomicBoolean(true)

        val connection = createConnection {
            if (firstAttempt.getAndSet(false)) {
                terminated.countDown()
                throw CancellationException("terminating the only job")
            }
        }
        this.connection = connection

        connection.start()
        assertTrue(
            terminated.await(STATE_WAIT_SECONDS, TimeUnit.SECONDS),
            "the job must have reached awaitAppReady and terminated",
        )

        // The finally runs as the job unwinds, so poll rather than assert on a fixed sleep.
        val deadline = System.currentTimeMillis() + STATE_WAIT_SECONDS * 1000
        while (connection.isRunning && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        assertFalse(
            connection.isRunning,
            "after the only connection job terminated, the restart latch must be clear; a guard " +
                "that never fires strands it exactly as the original defect did",
        )
    }

    @Test
    fun `an externally cancelled job does not clear the latch of the job that replaced it`() {
        // Reproduces the race the generation guard exists to close, deterministically.
        //
        // `Job.isActive` flips false the instant cancel() is called, while the job's `finally` is
        // still pending. In that window start() sees no live job and launches a replacement. If the
        // dying job then clears `running`, the replacement reads canConnect as false, never enters
        // the reconnect loop, and ends WITHOUT connecting: a start() that reports success and
        // establishes nothing.
        //
        // Two deliberate timing choices, both needed for this to bite:
        //  - the stale job holds its finally with Thread.sleep, not delay, because a blocking sleep
        //    is not a suspension point, so a cancelled coroutine cannot unwind through it;
        //  - the REPLACEMENT job waits longer than the stale job before returning, so the stale
        //    finally lands FIRST. canConnect is only read after awaitAppReady, so without this the
        //    replacement enters the loop before the stale job ever clears the flag and the race is
        //    invisible. In production that ordering is the normal one, because awaitAppReady waits
        //    on real app startup.
        // ORDERING IS A HANDSHAKE, NOT A RACE BETWEEN SLEEPS.
        //
        // An earlier version established the interleaving with two sleeps (stale 200ms, replacement
        // 1200ms) and a margin assertion. It was correct but load dependent: under the full suite
        // with JaCoCo instrumentation the 200ms sleep overshot often enough to fail about one run in
        // three. Widening the sleeps would lower that rate without removing the dependency. These
        // latches make each step wait for the previous one, so the test no longer depends on machine
        // load. Every await is bounded, so a genuine hang fails loudly instead of blocking the suite.
        val staleCancelled = CountDownLatch(1)
        val replacementWaiting = CountDownLatch(1)
        val staleMayUnwind = CountDownLatch(1)
        val staleUnwindWindowClosed = CountDownLatch(1)
        val attempt = java.util.concurrent.atomic.AtomicInteger(0)
        // The vacuity guard survives unchanged in meaning: prove the interleaving really happened
        // rather than assuming it, because a silent false pass is the failure this test already had.
        val staleUnwindStartedAt = java.util.concurrent.atomic.AtomicLong(0L)
        val replacementResumedAt = java.util.concurrent.atomic.AtomicLong(0L)

        val connection = createConnection {
            @Suppress("BlockingMethodInNonBlockingContext")
            if (attempt.incrementAndGet() == 1) {
                // isActive goes false here while this frame keeps running, which is the window the
                // generation guard exists to cover.
                requireNotNull(currentCoroutineContext()[Job]).cancel()
                staleCancelled.countDown()
                // Hold the finally pending until the replacement exists and is parked.
                assertTrue(
                    staleMayUnwind.await(STATE_WAIT_SECONDS, TimeUnit.SECONDS),
                    "the test must release the stale job",
                )
                staleUnwindStartedAt.set(System.nanoTime())
                throw CancellationException("stale job unwinding after being superseded")
            } else {
                replacementWaiting.countDown()
                // Do not read canConnect until the stale job's finally has had its window.
                assertTrue(
                    staleUnwindWindowClosed.await(STATE_WAIT_SECONDS, TimeUnit.SECONDS),
                    "the test must release the replacement",
                )
                replacementResumedAt.set(System.nanoTime())
            }
        }
        this.connection = connection

        val reachedConnecting = CountDownLatch(1)
        connection.addConnectionStateListener { state ->
            if (state == ConnectionState.CONNECTING) {
                reachedConnecting.countDown()
            }
        }

        connection.start()
        assertTrue(
            staleCancelled.await(STATE_WAIT_SECONDS, TimeUnit.SECONDS),
            "the first job must have cancelled itself and be holding its finally",
        )

        // The stale job is not active, so this start() proceeds and creates the replacement.
        connection.start()
        assertTrue(
            replacementWaiting.await(STATE_WAIT_SECONDS, TimeUnit.SECONDS),
            "the replacement job must exist and be parked before the stale job unwinds",
        )

        // Now let the stale job run its finally, with the replacement already created and parked.
        staleMayUnwind.countDown()
        // The unwind from `throw` through the `finally` is straight-line code with no suspension
        // point, so a short bounded wait is enough; it is not racing another sleeping thread.
        Thread.sleep(250)
        staleUnwindWindowClosed.countDown()

        assertTrue(
            reachedConnecting.await(STATE_WAIT_SECONDS, TimeUnit.SECONDS),
            "the replacement connection job must reach CONNECTING; if the superseded job cleared " +
                "`running` from its finally, canConnect is false and the replacement silently " +
                "establishes nothing",
        )

        // VACUITY GUARD. Everything above can pass without the race having happened at all, so
        // prove the intended interleaving actually occurred rather than trusting the sleep margin.
        val staleAt = staleUnwindStartedAt.get()
        val replacementAt = replacementResumedAt.get()
        assertTrue(staleAt != 0L, "the stale job must have reached its unwind")
        assertTrue(replacementAt != 0L, "the replacement must have resumed from awaitAppReady")
        val orderingMarginMillis = (replacementAt - staleAt) / 1_000_000L
        // Threshold derived from the deliberate 250ms unwind window opened above, NOT from a
        // tolerance for machine load; the latches already remove the load dependency. Half that
        // window is comfortably clear of zero, so an inverted or collapsed ordering still fails.
        // (An earlier 500ms bound was calibrated for the old sleep-race design and became wrong the
        // moment the mechanism changed. This guard caught that itself.)
        assertTrue(
            orderingMarginMillis >= 125L,
            "the stale job must begin unwinding well before the replacement reads canConnect, " +
                "otherwise the race is not exercised and this test proves nothing; observed " +
                "margin was $orderingMarginMillis ms",
        )
    }

    @Test
    fun `a start after stop creates a new job`() {
        val connection = createConnection { }
        this.connection = connection

        val connectingCount = CountDownLatch(2)
        connection.addConnectionStateListener { state ->
            if (state == ConnectionState.CONNECTING) {
                connectingCount.countDown()
            }
        }

        connection.start()
        Thread.sleep(300)
        connection.stop()
        connection.start()

        assertTrue(
            connectingCount.await(STATE_WAIT_SECONDS, TimeUnit.SECONDS),
            "a start() after a stop() must create a new job and begin connecting",
        )
    }

    private fun createConnection(awaitAppReady: suspend () -> Unit): ServerConnection {
        val configuration = createConfiguration()
        val taskManager = NoopTaskManager()

        val dependencyProvider = ServerConnectionDependencyProvider { connection ->
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

        return CspConnectionImpl(dependencyProvider, awaitAppReady)
    }

    private fun createConfiguration(): CspConnectionConfiguration {
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
            SocketFactory { testSocket },
        )
    }

    private object NoopConnectionLockProvider : ConnectionLockProvider {
        override fun acquire(timeoutMillis: Long, tag: ConnectionLockProvider.ConnectionLogTag): ConnectionLock =
            object : ConnectionLock {
                override fun release() = Unit
                override fun isHeld() = false
            }
    }

    private class NoopTaskManager : TaskManager, InternalTaskManager {
        override fun processInboundMessage(message: InboundMessage, lock: ConnectionLock) = Unit

        override suspend fun startRunningTasks(
            layer5Codec: Layer5Codec,
            incomingMessageProcessor: IncomingMessageProcessor,
        ) = Unit

        override suspend fun pauseRunningTasks(closeReason: ServerSocketCloseReason) = Unit

        override fun <R> schedule(task: Task<R, TaskCodec>): Deferred<R> = CompletableDeferred()

        override fun hasPendingTasks(): Boolean = false

        override fun addQueueSendCompleteListener(listener: QueueSendCompleteListener) = Unit

        override fun removeQueueSendCompleteListener(listener: QueueSendCompleteListener) = Unit
    }
}
