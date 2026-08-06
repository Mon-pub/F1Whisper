package ch.threema.domain.protocol.connection

import androidx.annotation.WorkerThread
import ch.threema.domain.protocol.ServerAddressProvider
import ch.threema.domain.protocol.Version
import ch.threema.domain.protocol.connection.csp.DeviceCookieManager
import ch.threema.domain.protocol.connection.socket.ServerSocket
import ch.threema.domain.protocol.connection.socket.ServerSocketCloseReason
import ch.threema.domain.protocol.connection.util.ConnectionLoggingUtil
import ch.threema.domain.protocol.connection.util.MainConnectionController
import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.domain.stores.IdentityStore
import ch.threema.domain.taskmanager.IncomingMessageProcessor
import ch.threema.domain.taskmanager.QueueSendCompleteListener
import ch.threema.domain.taskmanager.TaskManager
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val logger = ConnectionLoggingUtil.getConnectionLogger("BaseServerConnection")

/**
 * The [BaseServerConnection] is an (abstract) implementation of the [ServerConnection] that utilises
 * different layers for handling different aspects of the connection:
 *  - Layer 1: Decodes the bytes received from the server into a container format
 *  - Layer 2: Demultiplexes the container into messages from different protocols (e.g. CSP, D2M)
 *  - Layer 3: Handles the authentication to the server and the transport encryption
 *  - Layer 4: Monitors the connection, reacts to some control message and sends keepalive echo requests
 *  - Layer 5: Dispatches the messages to the task manager for further processing
 *
 * Messages received in the layer 5 ([ch.threema.domain.protocol.connection.layer.EndToEndLayer]) are
 * passed on to the [ch.threema.domain.taskmanager.TaskManager] by the EndToEnd layer for further
 * processing.
 */
internal abstract class BaseServerConnection(
    private val dependencyProvider: ServerConnectionDependencyProvider,
    private val awaitAppReady: suspend () -> Unit,
) : ServerConnection, ServerConnectionDispatcher.ExceptionHandler {
    private val connectionStateListeners = mutableSetOf<ConnectionStateListener>()

    private val stateLock = ReentrantLock()

    @Volatile
    private var state: ConnectionState = ConnectionState.DISCONNECTED

    override val connectionState: ConnectionState
        get() = stateLock.withLock { state }

    private val running = AtomicBoolean(false)

    /**
     * F1Whisper: true while a start is in flight or a connection job is alive.
     *
     * This is honest only because [running] can no longer be stranded. It used to be able to report
     * `true` forever with no job behind it, because the reset sat outside any `finally`; the reset is
     * now in one, and [stop] clears the flag as well, so the two disjuncts cannot disagree with
     * reality. The diagnostics report consumes this to distinguish "disconnected and retrying" from
     * "disconnected and given up", so it must stay truthful.
     */
    override val isRunning: Boolean
        get() = running.get() || hasLiveConnectionJob

    protected val socket: ServerSocket
        get() = dependencies.socket

    private lateinit var dependencies: ServerConnectionDependencies

    private var connectionLock: ConnectionLock? = null

    private var reconnectAllowed = AtomicBoolean(true)

    @Volatile
    private var isReconnect = false

    override val isNewConnectionSession: Boolean
        get() = !isReconnect

    private var reconnectAttemptsSinceLastLogin = 0
    private var ioJob: Job? = null

    /**
     * F1Whisper: wall-clock timestamp of the last inbound frame received on this connection. Written
     * by the [ch.threema.domain.protocol.connection.layer.MonitoringLayer] on every inbound frame and
     * once here when LOGGEDIN is reached (fresh start, before the first echo reply). Reported by the
     * diagnostics export; **not** used to judge staleness, see [lastInboundActivityAtAwakeMillis].
     *
     * The AtomicLong is not merely for visibility. Both stamps are read together by
     * [ConnectionLivenessVerdict], and correctness rests on an ordering argument rather than on
     * tolerating a stale read: [recordInboundActivity] is called at the LOGGEDIN seed site *before*
     * [setConnectionState] flips the state, and [state] is both `@Volatile` and written under
     * [stateLock], so any thread that observes LOGGEDIN must also observe a non-zero stamp. That is
     * what makes "LOGGEDIN with a `0L` stamp" a sound contradiction to fail loud on rather than a
     * benign race. The earlier rationale here ("a stale read at worst delays a reconnect by one
     * cycle, which is acceptable") no longer covers what depends on this value: under the fail-loud
     * rule a stale read would mean a spurious teardown, not a delayed reconnect.
     */
    private val lastInboundActivityAtMillis = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * F1Whisper: awake-time stamp of the last inbound frame, in milliseconds, from [System.nanoTime].
     *
     * Staleness is judged on this clock, never on wall-clock. The echo heartbeat that refreshes this
     * stamp is driven by `kotlinx.coroutines.delay`, which rides the same monotonic clock, and that
     * clock halts while the device is suspended to RAM. So a Doze window advances wall-clock time
     * without consuming any heartbeat budget: measured on the reporting device, wall-clock echo gaps
     * reached 403s while the socket was alive at the end of all 34 windows over 120s. See
     * [ConnectionLivenessVerdict] for the full derivation.
     */
    private val lastInboundActivityAtAwakeMillis = java.util.concurrent.atomic.AtomicLong(0L)

    override fun getLastInboundActivityAtMillis(): Long = lastInboundActivityAtMillis.get()

    override fun getLastInboundActivityAtAwakeMillis(): Long = lastInboundActivityAtAwakeMillis.get()

    /**
     * F1Whisper: record that an inbound frame was received now, on both clocks.
     *
     * Called from the monitoring layer for every inbound frame and once at the LOGGEDIN seed below.
     * The awake stamp is written first so that a reader which observes a non-zero wall stamp also
     * observes a non-zero awake stamp; the verdict treats "wall set, awake unset" as a broken
     * stamping path, and this ordering keeps that signal meaningful instead of racy.
     */
    fun recordInboundActivity() {
        // 0L is the "never recorded" sentinel, so a genuine reading of exactly 0 must not collide
        // with it. nanoTime's origin is unspecified by the JVM (on Android it is boot, so this only
        // matters in the first millisecond after boot), and only the exact-zero case is nudged, so
        // negative readings keep producing correct deltas.
        val awakeMillis = System.nanoTime() / 1_000_000L
        lastInboundActivityAtAwakeMillis.set(if (awakeMillis == 0L) 1L else awakeMillis)
        lastInboundActivityAtMillis.set(System.currentTimeMillis())
    }

    @Volatile
    private var connectionJob: Job? = null

    /**
     * F1Whisper: monotonically increasing id of the current start attempt.
     *
     * This exists to close a race that the `finally` in [createConnectionJob] would otherwise create.
     * See the comment on that `finally` for the mechanism. Incremented inside [startStopLock] before
     * a new job is created, so a job that captured an older value can tell it has been superseded.
     */
    private val startGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    private val canConnect: Boolean
        get() = running.get() && reconnectAllowed.get()

    protected val controller: MainConnectionController
        get() = dependencies.mainController

    override fun disableReconnect() {
        reconnectAllowed.set(false)
    }

    override fun handleException(throwable: Throwable) {
        logger.error("Exception in connection dispatcher; Cancel io processing")
        if (this::dependencies.isInitialized) {
            controller.ioProcessingStoppedSignal.completeExceptionally(throwable)
            controller.dispatcher.close()
        }
    }

    override fun addConnectionStateListener(listener: ConnectionStateListener) {
        synchronized(connectionStateListeners) {
            connectionStateListeners.add(listener)
        }
    }

    override fun removeConnectionStateListener(listener: ConnectionStateListener) {
        synchronized(connectionStateListeners) {
            connectionStateListeners.remove(listener)
        }
    }

    /**
     * F1Whisper: start and stop must reason about the SAME predicate.
     *
     * `start()` used to gate on `running.getAndSet(true)` and `stop()` on `running.get()`, so once
     * that one flag drifted out of step with reality the two disagreed about whether a connection
     * existed, and neither could put it right. Both now hold this monitor and both ask
     * [hasLiveConnectionJob]. This replaces `stop()`'s previous `synchronized(this)`: a private lock
     * cannot be taken by outside code, so serialising start against stop here cannot be perturbed by
     * a caller that happens to lock the connection object. Nothing inside the connection job acquires
     * it, so `stop()` holding it across its join cannot deadlock against the job it is waiting for.
     */
    private val startStopLock = Any()

    /**
     * F1Whisper: is a connection job actually alive right now?
     *
     * This is the question both [start] and [stop] must ask. `running` alone cannot answer it: it is
     * a flag, and a flag can outlive the thing it describes.
     */
    private val hasLiveConnectionJob: Boolean
        get() = connectionJob?.isActive == true

    final override fun start() {
        logger.info("Start")

        synchronized(startStopLock) {
            // THE RECOVERY GUARD. Verify a LIVE JOB, never merely the flag.
            //
            // This used to read `if (running.getAndSet(true) || connectionJob?.isActive == true)`,
            // which meant a stale `running` alone was enough to refuse every future start for the
            // life of the process. The observed consequence: the app reports DISCONNECTED with the
            // network provably fine (its own probes completed a full CSP hello at the time), never
            // retries, shows no error, and only a force close restores service, because the flag is
            // process scoped and a new process is the only thing that clears it.
            //
            // THE PRINCIPLE, and please do not undo it: a recovery path must never be gated on a
            // flag that only one code path can clear. `running` is still set here, because it marks
            // the window between this call and the job actually starting, but it is no longer
            // trusted as evidence that a connection exists, and the `finally` below guarantees it
            // cannot outlive the job.
            if (hasLiveConnectionJob) {
                logger.warn("Connection is already running")
                return
            }

            // SUPERSEDE ANY DEAD-BUT-UNWINDING JOB, and note carefully where this sits.
            //
            // It must come AFTER the live-job guard above and BEFORE `running.set(true)` below.
            //
            // Why not earlier: bumping before the guard would orphan a job that is still ALIVE. That
            // job would then fail its own generation check when it eventually finishes, never clear
            // `running`, and strand the latch. That is the original defect, reintroduced by a fix
            // for a race. Past the guard we know no live job exists, so nothing can be orphaned.
            //
            // Why not later: a superseded job's `finally` landing between `running.set(true)` and
            // the job creation below would still match its own generation, clear the latch, and
            // leave the replacement to read `canConnect` as false and exit without ever connecting.
            // The window is small but the state check below takes `stateLock` and can block in it.
            //
            // The early return below is unaffected: it clears the latch itself, and a stale job that
            // no longer matches simply leaves an already-false flag alone.
            val generation = startGeneration.incrementAndGet()

            if (running.get()) {
                // Reachable only if a previous job died without clearing the flag. That is the wedge
                // this method used to make permanent. Recover from it, and say so, because it is
                // evidence of a defect elsewhere and it must never again be silent.
                logger.warn("Restart latch was set with no live connection job; recovering")
            }
            running.set(true)

            if (connectionState != ConnectionState.DISCONNECTED) {
                logger.warn("Connection is not disconnected. Abort connecting.")
                // Do not leave the latch set on an early return, or the next start would have to
                // recover from a state this method created.
                running.set(false)
                return
            }

            isReconnect = false
            // Allow reconnect attempts in a new session
            reconnectAllowed.set(true)

            connectionJob = createConnectionJob(generation)
        }
    }

    /**
     * F1Whisper: there is deliberately NO `CoroutineExceptionHandler` on this scope. Do not add one.
     *
     * The temptation is real, because this scope has no parent and it looks like an escaping
     * throwable goes nowhere. It does not. It falls through to the thread's uncaught handler, which
     * the app installs as `ThreemaUncaughtExceptionHandler`
     * (`ThreemaApplication.setUpUnhandledExceptionLogger`). That handler logs at ERROR, stores the
     * error for reporting, and then delegates to the Android platform default, **which kills the
     * process**. So a non-cancellation escape is already loud, already recorded, and fatal.
     *
     * Why that is the outcome we want here, specifically. The process death clears every process
     * scoped latch, so the crash is crude but **self curing**: the user gets a restarted app with a
     * working connection. Installing a handler would intercept the throwable before the thread
     * handler, leaving the process alive with the reconnect loop exited and nothing in this layer
     * retrying, so recovery would depend on the app layer's `ensureConnection` actually being
     * reached. Trading a visible self curing crash for a possibly silent non-retrying messenger moves
     * toward the exact failure this work exists to remove: the user's report was "the app looks fine
     * and nothing arrives, and only force-closing fixes it". A crash is honest; a quiet dead
     * connection is not.
     *
     * A `CancellationException` never reaches an exception handler anyway: for a job with no parent
     * it is normal completion, is reported nowhere, and never killed the process. That is the quiet
     * route, and it is covered by the `finally` below.
     */
    private fun createConnectionJob(generation: Long): Job =
        CoroutineScope(Dispatchers.Default).launch {
            // F1Whisper: `running` is released from a `finally` so that NO exit path can strand it.
            // The reset used to sit after the `while` loop, outside any `finally`, so a throw that
            // escaped the body skipped it and pinned the connection off for the life of the process.
            // The `try` inside the loop has a `catch` but no `finally`, and `awaitAppReady()` below
            // is outside it entirely.
            try {
                awaitAppReady()
                while (canConnect) {
                    var monitorCloseEventJob: Job? = null
                    var queueSendCompleteListener: QueueSendCompleteListener? = null
                    try {
                        setup()

                        logger.debug("Start connecting")
                        setConnectionState(ConnectionState.CONNECTING)
                        socket.connect()
                        setConnectionState(ConnectionState.CONNECTED)

                        connectionLock = dependencies.connectionLockProvider.acquire(
                            60_000,
                            ConnectionLockProvider.ConnectionLogTag.PURGE_INCOMING_MESSAGE_QUEUE,
                        )

                        // To prevent races where this while loop has been entered just before stop()
                        // has been called, and stop() has been called before the socket was
                        // initialized, check again if a reconnect is still allowed. Otherwise, close
                        // the socket and abort connection.
                        if (!reconnectAllowed.get()) {
                            socket.close(ServerSocketCloseReason("Reconnect not allowed"))
                            connectionLock?.release()
                            break
                        }

                        // We must keep the CPU awake until we have processed all incoming messages
                        // to avoid missing messages in deeper sleep states.
                        queueSendCompleteListener = QueueSendCompleteListener {
                            logger.info("CSP queue was processed, releasing connection lock")
                            connectionLock?.release()
                        }
                        // The listener must be registered before processing io has been started.
                        // Otherwise the queue send complete event could already have been triggered
                        // before the listener was added.
                        dependencies.taskManager.addQueueSendCompleteListener(queueSendCompleteListener)

                        // Handle IO until the connection dies
                        ioJob = launch { processIo() }

                        controller.connected.complete(Unit)
                        onConnected()

                        val waitForCspAuthenticatedJob = launch {
                            controller.cspAuthenticated.await()
                            onCspAuthenticated()
                            reconnectAttemptsSinceLastLogin = 0
                            // F1Whisper: seed inbound-activity timestamp on login so a freshly-logged-in
                            // connection isn't flagged stale before the first ~60s echo reply arrives.
                            recordInboundActivity()
                            setConnectionState(ConnectionState.LOGGEDIN)
                        }
                        // Monitor close events of the socket
                        monitorCloseEventJob = launch {
                            val reason = socket.closedSignal.await()
                            logger.warn("Socket was closed, reason={}", reason)
                            if (reason.reconnectAllowed == false) {
                                disableReconnect()
                            }
                            onSocketClosed(reason)
                            if (!waitForCspAuthenticatedJob.isCompleted) {
                                // Cancel awaiting the csp authentication when the socket is closed
                                // as it will never complete
                                logger.debug("Cancel waiting for csp authentication.")
                                waitForCspAuthenticatedJob.cancel()
                            } else {
                                logger.debug("Csp authentication already completed")
                            }
                            logger.debug("Socket watchdog completed")
                        }

                        waitForCspAuthenticatedJob.join()

                        ioJob?.join()
                    } catch (e: Exception) {
                        if (e is IOException || e.cause is IOException) {
                            logger.warn("Connection exception", e)
                        } else {
                            logger.error("Unexpected connection exception", e)
                        }
                        onException(e)
                    }

                    setConnectionState(ConnectionState.DISCONNECTED)

                    closeSocket("Disconnected")

                    controller.connectionClosed.complete(Unit)

                    queueSendCompleteListener?.let { dependencies.taskManager.removeQueueSendCompleteListener(it) }

                    if (canConnect) {
                        prepareReconnect()
                    }
                    connectionLock?.release()
                    monitorCloseEventJob?.cancel()
                }
                logger.info("Connection ended")
            } finally {
                // MUST stay in a `finally`, and it is load-bearing on the CANCELLATION route.
                //
                // Why it is required, and note that this fix created the need for it. prepareReconnect()
                // now RETHROWS CancellationException instead of swallowing it. Before that change the
                // swallow meant the loop always exited normally, so the old reset (which sat after the
                // loop, outside any finally) always ran. Now a cancellation propagates out of the loop
                // and out of this body, and a cancellation is NOT a crash: for a job with no parent it
                // is normal completion, nothing is reported, and the process survives. So without this
                // finally the rethrow would strand `running` true with no live job, on a path that is
                // production reachable, because any failure in a child coroutine of this job cancels
                // the parent. That is precisely the wedge this work removes: DISCONNECTED, network
                // provably fine, no retry, no error, curable only by a force close.
                //
                // For a non-cancellation throwable this reset is defence in depth rather than the
                // cure: that route reaches ThreemaUncaughtExceptionHandler and kills the process, and
                // the restart clears every process scoped latch by itself. See the note on the scope
                // above for why no exception handler is installed to intercept it.
                // GENERATION GUARD. Only release the latch if this job is still the current one.
                //
                // Without it, an EXTERNALLY cancelled job silently breaks the next connection.
                // `Job.isActive` flips to false the instant cancel() is called, while this `finally`
                // is still pending, so:
                //   1. job A is cancelled: isActive false, `running` still true, finally not yet run;
                //   2. start() takes the lock, sees no live job, sets `running` true, launches job B;
                //   3. job A's finally finally runs and sets `running` FALSE;
                //   4. job B reads `canConnect` (running && reconnectAllowed) as false, never enters
                //      the loop, and ends without ever connecting.
                // Net effect: a start() that reports success and establishes nothing. It self
                // recovers on the next start(), so it is not a permanent wedge, but it is a silent
                // failed reconnect in exactly the scenario this work exists to fix.
                //
                // A job-identity check (`connectionJob === thisJob`) is NOT sufficient: job A's
                // finally can also interleave between start() setting `running` true and start()
                // assigning `connectionJob`, and at that instant the identity still matches job A.
                // The generation is bumped inside the lock BEFORE the new job exists, so it covers
                // that window too.
                //
                // This cannot take `startStopLock`: stop() holds that lock across
                // `runBlocking { connectionJob?.join() }`, so a `finally` waiting on it would
                // deadlock against the very stop() that is waiting for this job to finish. The
                // atomic read is lock free precisely for that reason.
                if (startGeneration.get() == generation) {
                    running.set(false)
                }
            }
        }

    @WorkerThread
    @Throws(InterruptedException::class)
    override fun stop() {
        synchronized(startStopLock) {
            // F1Whisper: gate on the same predicate `start()` uses, not on the raw flag.
            //
            // This used to read `if (running.get())`. Once that flag drifted out of step with
            // reality, `start()` and `stop()` disagreed about whether a connection existed and
            // neither could put it right: `start()` refused because the flag was set, `stop()`
            // believed there was something to stop. Asking about a live job keeps the two coherent
            // by construction. `running` is still consulted so that a start which has been issued
            // but whose job has not yet begun is still stoppable.
            if (hasLiveConnectionJob || running.get()) {
                logger.info("Stop")
                disableReconnect()
                closeSocket("Connection stopped")
                logger.trace("Join connection job")
                runBlocking { connectionJob?.join() }
                logger.trace("Connection job joined")
                controller.dispatcher.close()
                // DECISION: stop() clears the latch as well, even though the job's `finally` already
                // does. The two cover different holes. The `finally` cannot run if no job was ever
                // created, which is exactly the case where `start()` set the flag and then took an
                // early return or threw before the launch. Clearing here costs nothing when the
                // `finally` has already done it, and it removes the last route by which the flag can
                // outlive every job.
                running.set(false)
                logger.info("Connection is stopped")
            } else {
                logger.warn("Connection has not been started or is already stopped")
            }
            setConnectionState(ConnectionState.DISCONNECTED)
        }
    }

    /**
     * Called when the socket connection to the server has been established.
     */
    protected open fun onConnected() {}

    /**
     * Called when the csp handshake has been completed.
     */
    protected open fun onCspAuthenticated() {}

    /**
     * Called when an exception occurs during establishing the connection or if processing io has been
     * stopped exceptionally.
     * Note that exceptions that this method will not be called with exceptions that occurred while
     * processing messages in the pipelines.
     * If this method is called it means that the connection has failed and will be disconnected. It may
     * be reconnected subsequently depending on the state of the connection.
     */
    protected open fun onException(t: Throwable) {}

    /**
     * Called when the server socket has been closed.
     */
    protected open fun onSocketClosed(reason: ServerSocketCloseReason) {}

    private fun setConnectionState(state: ConnectionState) {
        stateLock.withLock {
            val previousState = this.state
            this.state = state

            synchronized(connectionStateListeners) {
                if (previousState != this.state) {
                    logger.debug(
                        "Notify connection state listeners. state={}, address={}",
                        state,
                        socket.address,
                    )
                    connectionStateListeners.forEach { listener ->
                        try {
                            listener.updateConnectionState(state)
                        } catch (e: Exception) {
                            logger.warn("Exception while invoking connection state listener", e)
                        }
                    }
                }
            }
        }
    }

    private fun setup() {
        dependencies = dependencyProvider.create(this)
        dependencies.mainController.dispatcher.exceptionHandler = this

        val socket = dependencies.socket
        val layers = dependencies.layers

        // Setup io pipeline
        socket.source
            .pipeThrough(layers.layer1Codec.decoder)
            .pipeThrough(layers.layer2Codec.decoder)
            .pipeThrough(layers.layer3Codec.decoder)
            .pipeThrough(layers.layer4Codec.decoder)
            .pipeInto(layers.layer5Codec)

        layers.layer5Codec.source
            .pipeThrough(layers.layer4Codec.encoder)
            .pipeThrough(layers.layer3Codec.encoder)
            .pipeThrough(layers.layer2Codec.encoder)
            .pipeThrough(layers.layer1Codec.encoder)
            .pipeInto(socket)
    }

    /**
     * Process IO of the underlying socket.
     *
     * This will continue until there is either an exception while processing or the
     * connection has been closed.
     */
    private suspend fun processIo() {
        try {
            socket.processIo()
        } catch (e: SocketException) {
            // This exception is thrown on a regular basis
            // e.g. when the server closes the connection or when the socket is closed
            // during device sleep.
            // Since we do not want to flood the log with redundant stack traces only
            // the exception message is logged
            logger.warn("Socket exception while processing io: {}", e.message)
        } catch (e: Exception) {
            logger.error("Connection exception while processing io", e)
        }
    }

    /**
     * F1Whisper: was `runBlocking { ioJob?.join() }`, called from the suspending [prepareReconnect].
     *
     * `runBlocking` is a blocking bridge, not a child of the calling coroutine, so it blocked the
     * connection job's thread and, worse, was NOT cancellable: a cancellation of the connection job
     * could not interrupt it, so an `ioJob` that never completed parked the connection there
     * indefinitely with the state already set to DISCONNECTED. A plain suspending `join()` keeps the
     * same ordering guarantee (the socket must be closed before reconnecting or deadlocks follow)
     * while remaining cancellable and without pinning a thread.
     */
    private suspend fun joinIoProcessing() {
        logger.trace("Join io processing job")
        ioJob?.join()
        logger.trace("Io processing joined")
    }

    private fun closeSocket(msg: String) {
        logger.info("Close socket")
        try {
            socket.close(ServerSocketCloseReason(msg))
        } catch (e: IOException) {
            logger.warn("Exception when closing socket", e)
        }
    }

    /**
     * Make sure [ServerSocket.close] has been called prior to reconnecting (or stopping of io processing
     * has been initiated by other means).
     * There might be deadlocks otherwise.
     * This methods also waits for a calculated delay based on the previous reconnect attempts before
     * returning.
     */
    private suspend fun prepareReconnect() {
        logger.debug("Prepare reconnect")
        isReconnect = true
        reconnectAttemptsSinceLastLogin++
        try {
            joinIoProcessing()
            /* Don't reconnect too quickly */
            val reconnectDelay = getReconnectDelay()
            logger.info("Waiting {} milliseconds before reconnecting", reconnectDelay)
            delay(reconnectDelay)
        } catch (e: CancellationException) {
            // F1Whisper: this used to call disableReconnect(), which is the primary defect behind
            // the reported wedge.
            //
            // A cancellation HERE means the backoff wait was interrupted. It does not mean
            // reconnecting is forbidden. Treating the two as the same thing turned any transient
            // cancellation into a permanent refusal to reconnect for the rest of the process, and
            // `reconnectAllowed` is re-armed only inside start(), so nothing else could undo it.
            // The reachable trigger is ordinary: any failure in one of the connection job's child
            // coroutines cancels the parent, and the parent then lands right here.
            //
            // The genuine no-reconnect signals are untouched and still work: a close reason with
            // reconnectAllowed == false, a server close error with the can-reconnect flag clear
            // (MonitoringLayer), and stop(). Those say the server or the user told us not to come
            // back. A cancelled delay says nothing of the sort.
            //
            // Rethrowing keeps structured concurrency honest: if the job really is cancelled, the
            // `while` loop must not carry on as though it were not, and the `finally` still releases
            // the latch on the way out.
            logger.debug("Reconnect wait cancelled; not disabling reconnect", e)
            throw e
        }
    }

    /**
     * Calculate the reconnect delay with bounded exponential backoff.
     */
    private fun getReconnectDelay(): Long {
        val base = ProtocolDefines.RECONNECT_BASE_INTERVAL.toDouble()
        val exponent = min(reconnectAttemptsSinceLastLogin - 1, 10)
        val reconnectDelayS = base.pow(exponent)
        val delayS = min(reconnectDelayS, ProtocolDefines.RECONNECT_MAX_INTERVAL.toDouble())
        return (delayS * 1000).toLong()
    }
}

/**
 * The lock keeps the device awake and prevents the app from being stopped while in the background.
 */
interface ConnectionLock {
    /**
     * Release the lock when the device can go to sleep.
     */
    fun release()

    /**
     * Returns true if the lock currently keeps the device awake. If false is returned, the
     * connection lock has either been released or timed out.
     */
    fun isHeld(): Boolean
}

interface ConnectionLockProvider {
    enum class ConnectionLogTag {
        PURGE_INCOMING_MESSAGE_QUEUE,
        INBOUND_MESSAGE,
    }

    fun acquire(timeoutMillis: Long, tag: ConnectionLogTag): ConnectionLock
}

interface BaseServerConnectionConfiguration {
    val identityStore: IdentityStore
    val serverAddressProvider: ServerAddressProvider
    val version: Version

    /**
     * If set to `true` it will be asserted that received messages
     * are actually processed in the connection's [ServerConnectionDispatcher]'s
     * context.
     * If the messages are not processed in the correct context and
     * [assertDispatcherContext] set to `true`, an [Error] will
     * be thrown.
     * This is meant for development purposes and should be disabled in production.
     */
    val assertDispatcherContext: Boolean

    val deviceCookieManager: DeviceCookieManager

    val incomingMessageProcessor: IncomingMessageProcessor

    val taskManager: TaskManager
}
