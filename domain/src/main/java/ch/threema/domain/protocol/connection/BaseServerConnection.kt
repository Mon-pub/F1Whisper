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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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

    /**
     * F1Whisper (eleventh fork review, F11-01): owns the blocking half of a task-requested restart, so that half never
     * runs on an attempt's single-thread dispatcher.
     *
     * The deadlock this removes: a task that throws `ProtocolException` asks layer 5 to restart the connection, and the
     * old `EndToEndLayer.restartConnection` resumed after its delay ON the attempt's single executor thread and called
     * the blocking [stop] there. Socket close then does `runBlocking { closeSocket(reason) }`, and both socket
     * implementations dispatch `closeInbound` back onto that same executor via `withContext(inputDispatcher)`. Inside
     * the `runBlocking` the current interceptor is its private event loop, so the `withContext` is a REAL dispatch onto
     * a queue whose only thread is the one parked inside the `runBlocking`: the thread waits for work that only it can
     * run, forever, with [startStopLock] held, so every later start or stop from anywhere blocks behind it too.
     * Messaging is dead until the process restarts. The same cycle exists a second time through the join in [stop]:
     * disposal waits on the attempt scope, whose coroutines can only unwind on the blocked executor.
     *
     * Scoped to the connection, not the attempt, because its whole job is to outlive the attempt that asked: the
     * executing side must be free to tear that attempt down. A [SupervisorJob] so one failed restart cannot poison the
     * scope for the life of the process.
     */
    private val restartCoordinatorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * The single admitted restart request. A newer request supersedes an older one that has not started executing;
     * two restarts back to back must produce one working connection, not two full stop/start cycles.
     */
    private val pendingRestart = AtomicReference<Job?>(null)

    /**
     * F1Whisper (eleventh fork review, F11-01): execute a task-requested restart off the attempt dispatcher.
     *
     * The requesting side ([ch.threema.domain.protocol.connection.layer.EndToEndLayer.restartConnection]) owns the
     * delay as a member of the attempt's scope, so a request from a disposed attempt is cancelled with the attempt and
     * can never fire. This is the handoff that runs the blocking stop and start, and it re-checks under
     * [startStopLock] immediately before acting:
     *
     *  - `reconnectAllowed == false` means an explicit [stop] or a server no-reconnect arrived after the request. A
     *    restart must not resurrect a connection the user or the server just told us to keep down. (At this point the
     *    restart's OWN stop has not run yet, so a false reading here can only be external.)
     *  - [requestingAttemptClosed] completed means the requesting attempt's iteration already ended: the reconnect
     *    loop is itself producing the replacement, and stopping here would kill that successor, not the requester.
     *
     * The gate closes the same windows the old dispatch-rejection trick closed, plus the external-stop one it did not.
     * What it deliberately does NOT claim: atomicity against the reconnect loop's own attempt transitions, which do
     * not (and must not - stop() joins the job while holding the lock) take [startStopLock]. In that residual race the
     * worst case is one spurious clean stop/start of a healthy connection, the same worst case the old code had.
     */
    internal fun requestRestart(requestingAttemptClosed: Deferred<Unit>) {
        val request = restartCoordinatorScope.launch(start = CoroutineStart.LAZY) {
            synchronized(startStopLock) {
                if (!reconnectAllowed.get()) {
                    logger.info("Dropping requested restart: reconnecting is not allowed")
                    return@launch
                }
                if (requestingAttemptClosed.isCompleted) {
                    logger.info("Dropping requested restart: the requesting attempt is already closed")
                    return@launch
                }
                logger.info("Executing task-requested connection restart")
                stop()
                start()
            }
        }
        pendingRestart.getAndSet(request)?.cancel()
        request.invokeOnCompletion { pendingRestart.compareAndSet(request, null) }
        request.start()
    }

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
                    // F1Whisper (tenth fork review, F10-02): the graph THIS attempt created, held locally.
                    //
                    // `setup()` overwrites the `dependencies` field on every iteration, and each new graph allocates a
                    // fresh single-thread executor for its dispatcher. Nothing closed the superseded ones: the only two
                    // close calls both go through the CURRENT `dependencies`, so by the time either could run, the
                    // reference to the graph that owned the thread had already been overwritten and was unreachable.
                    // The result was one leaked executor per reconnect, for the life of the process - 213 of them in
                    // the reporting device's ANR dump, all parked in their own empty queue.
                    //
                    // Holding the graph locally is what makes disposal possible at all: the `finally` below closes the
                    // graph that allocated the resource, never whatever `dependencies` happens to point at by then.
                    var attempt: ServerConnectionDependencies? = null
                    try {
                        try {
                            setup()
                            attempt = dependencies

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
                            // F1Whisper (eleventh fork review, F11-02): a cancellation is not a connection failure.
                            // stop() now cancels the job so its readiness and backoff waits are interruptible, which
                            // makes this catch reachable with a CancellationException on the ordinary teardown path.
                            // It is logged as what it is and NOT reported through onException; the tail below still
                            // runs (it is non-suspending), so state, socket close and connectionClosed are settled
                            // exactly as on any other exit, and the next suspension point re-raises the cancellation.
                            if (e is CancellationException) {
                                logger.info("Connection attempt cancelled")
                            } else {
                                if (e is IOException || e.cause is IOException) {
                                    logger.warn("Connection exception", e)
                                } else {
                                    logger.error("Unexpected connection exception", e)
                                }
                                onException(e)
                            }
                        }

                        setConnectionState(ConnectionState.DISCONNECTED)

                        closeSocket("Disconnected")

                        controller.connectionClosed.complete(Unit)

                        if (canConnect) {
                            prepareReconnect()
                        }
                    } finally {
                        // F1Whisper (tenth fork review, F10-02): every exit of an attempt disposes that attempt.
                        //
                        // The routes this now covers that the old straight-line tail did not: the `break` above when a
                        // reconnect is no longer allowed, a throw from the tail itself, and cancellation of the backoff
                        // delay inside prepareReconnect(). The last one is not hypothetical - any failure in a child
                        // coroutine of this job cancels the parent, and prepareReconnect() rethrows the cancellation on
                        // purpose (see its own comment), so a cancelled backoff is a normal way for an attempt to end.
                        disposeAttempt(attempt, queueSendCompleteListener, monitorCloseEventJob)
                    }
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
                // F1Whisper (tenth fork review, F10-02): a stop can arrive before the job has reached its first
                // setup(), and `dependencies` is a lateinit field, so both of these used to throw
                // UninitializedPropertyAccessException out of stop() in that window. Reachable in the ordinary way -
                // start() launches the job and returns, so any stop() that overtakes it lands here - and surfaced by
                // the disposal test doing exactly that. The connection job's own `finally` disposes the attempt once
                // it exists, so skipping these when there is nothing yet to close leaks nothing.
                val hasGraph = this::dependencies.isInitialized
                if (hasGraph) {
                    closeSocket("Connection stopped")
                }
                logger.trace("Join connection job")
                // F1Whisper (eleventh fork review, F11-02): CANCEL, then join. A plain join could wait forever: before
                // the first setup() the job is suspended in awaitAppReady(), which the app implements as
                // AppStartupMonitor.awaitAll() and which is documented to suspend forever when startup has an error -
                // and with no graph there is no socket whose close could unblock anything. The same applies to the
                // backoff delay between attempts. Cancelling makes both interruptible; the attempt's own disposal is
                // unaffected because it runs in a finally under NonCancellable, in the same order as before.
                runBlocking { connectionJob?.cancelAndJoin() }
                logger.trace("Connection job joined")
                if (hasGraph || this::dependencies.isInitialized) {
                    controller.dispatcher.close()
                }
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

    /**
     * F1Whisper (tenth fork review, F10-02): release everything one connection attempt allocated, exactly once.
     *
     * <p><b>Why the order is what it is.</b> The dispatcher's executor is the last thing to go, because everything
     * above it may still need a thread to unwind on: the listener is detached first so the task manager cannot call
     * back into a graph that is being torn down, then the attempt's own coroutines are cancelled and joined, and only
     * then does [ServerConnectionDispatcher.closeAndJoin] cancel the layers' coroutines and shut the executor down.
     * Closing first would not lose the work - kotlinx answers a rejected dispatch by cancelling the job and falling
     * back to another dispatcher - but it would move cleanup off the thread `assertDispatcherContext` expects.</p>
     *
     * <p>Runs [NonCancellable] because its most important caller is a `finally` on the cancellation route, where every
     * suspending call would otherwise throw immediately and the attempt would leak precisely when it was cancelled.</p>
     *
     * <p>Takes the attempt's graph rather than reading the [dependencies] field: by the time a later attempt is
     * disposed the field has moved on, and closing the current graph instead of the finished one would tear down the
     * live connection while leaving the dead one's thread parked. That inversion is worth being explicit about, since
     * it is the same reference confusion that produced the leak.</p>
     */
    private suspend fun disposeAttempt(
        attempt: ServerConnectionDependencies?,
        queueSendCompleteListener: QueueSendCompleteListener?,
        monitorCloseEventJob: Job?,
    ) {
        if (attempt == null) {
            // setup() threw before it produced a graph, so there is nothing this attempt allocated.
            return
        }
        withContext(NonCancellable) {
            try {
                queueSendCompleteListener?.let { attempt.taskManager.removeQueueSendCompleteListener(it) }
            } catch (e: Exception) {
                logger.warn("Could not detach the queue send complete listener", e)
            }
            connectionLock?.release()
            connectionLock = null
            monitorCloseEventJob?.cancelAndJoin()
            // Normally already finished: both the reconnect path and the exception path join io processing before they
            // get here. It is cancelled anyway for the routes that do not, above all cancellation of the backoff.
            ioJob?.cancelAndJoin()
            ioJob = null
            try {
                attempt.mainController.dispatcher.closeAndJoin()
            } catch (e: Exception) {
                logger.warn("Could not close the connection dispatcher of a finished attempt", e)
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
