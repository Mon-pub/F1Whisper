package ch.threema.domain.protocol.connection

import ch.threema.base.concurrent.TrulySingleThreadExecutorThreadFactory
import ch.threema.domain.protocol.connection.util.ConnectionLoggingUtil
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withTimeoutOrNull

private val logger = ConnectionLoggingUtil.getConnectionLogger("ServerConnectionDispatcher")

internal interface ServerConnectionDispatcher {
    fun interface ExceptionHandler {
        fun handleException(throwable: Throwable)
    }

    var exceptionHandler: ExceptionHandler?

    val coroutineContext: CoroutineContext

    /**
     * F1Whisper (tenth fork review, F10-02): the scope that OWNS every long-lived coroutine started for this connection
     * attempt.
     *
     * <p>The layers used to start theirs on a bare `CoroutineScope(dispatcher.coroutineContext)`, which is a scope with
     * no owner: nothing could cancel them, so the collector in `EndToEndLayer`'s constructor and the two waiters in
     * `MonitoringLayer`'s outlived their attempt with the executor thread still parked underneath. Launching into this
     * scope is what makes "close the attempt" mean something. Anything launched here MUST NOT wait on the connection
     * job, because [closeAndJoin] waits on this scope. The tenth review's one exception - the restart coroutine, left
     * unowned because it blocked on `stop()` - is gone: since the eleventh review (F11-01) the restart's blocking half
     * runs on the connection-level coordinator, and what remains in this scope only delays and hands off.</p>
     */
    val scope: CoroutineScope

    fun assertDispatcherContext()

    /**
     * Close the dispatcher and shutdown the executor. Beware that the [coroutineContext] cannot be used
     * after this call.
     *
     * Idempotent. Cancels [scope] without waiting for it; prefer [closeAndJoin] from a coroutine.
     */
    fun close()

    /**
     * F1Whisper (tenth fork review, F10-02): cancel this attempt's coroutines, wait for them, and only then shut the
     * executor down.
     *
     * The order is the point. Closing first would leave a cancelled coroutine's cleanup with no thread to run on:
     * kotlinx answers a rejected dispatch by cancelling the job and falling back to [Dispatchers.IO], which does not
     * lose the work but does move it off the thread `assertDispatcherContext` expects. Cancelling first and joining
     * means every child has already finished on its own thread by the time the executor goes away.
     */
    suspend fun closeAndJoin()
}

internal class SingleThreadedServerConnectionDispatcher(private val assertContext: Boolean) :
    ServerConnectionDispatcher {
    internal companion object {
        var threadsCreated = 0

        private val openDispatchers = AtomicInteger(0)

        /**
         * F1Whisper (tenth fork review, F10-02): how many dispatchers exist that have not been closed.
         *
         * <p>Every connection ATTEMPT builds a fresh dependency graph, and every graph allocates one of these, so this
         * is one per attempt while an attempt is live and zero between them. The defect it measures: nothing closed the
         * superseded ones, so the count grew without bound for the life of the process. The ANR dump from the reporting
         * device declared 287 managed threads, 213 of them named `ServerConnectionWorker-*` and all parked in their own
         * executor's queue - one per reconnect since the process started, minus the single one that had been closed by
         * an exception callback.</p>
         *
         * <p>Exposed so the regression test can assert disposal directly rather than by inference.</p>
         */
        val openDispatcherCount: Int
            get() = openDispatchers.get()

        /**
         * How long [closeAndJoin] waits for this attempt's coroutines before shutting the executor down anyway.
         *
         * A backstop, not the mechanism: everything in the scope suspends on cancellable primitives, so the join
         * normally completes at once. If some future child ever failed to unwind, an unbounded wait here would park the
         * whole reconnect loop, which is a worse failure than the leak this closes. Shutting down regardless is safe:
         * kotlinx answers a rejected dispatch by cancelling the job and falling back to another dispatcher.
         */
        private const val CLOSE_JOIN_TIMEOUT_MS = 2_000L
    }

    private lateinit var thread: Thread

    private var exceptionHandlerReference: WeakReference<ServerConnectionDispatcher.ExceptionHandler>? =
        null
    override var exceptionHandler: ServerConnectionDispatcher.ExceptionHandler?
        get() = exceptionHandlerReference?.get()
        set(value) {
            exceptionHandlerReference = WeakReference(value)
        }

    private val dispatcher: ExecutorCoroutineDispatcher
    override val coroutineContext: CoroutineContext

    /**
     * Parent of every coroutine in [scope]. A [SupervisorJob] so that one failing layer coroutine cancels itself rather
     * than every other layer of the same attempt, which is the isolation the bare scopes gave by accident.
     */
    private val attemptJob = SupervisorJob()

    override val scope: CoroutineScope

    private val closed = AtomicBoolean(false)

    init {
        val factory =
            TrulySingleThreadExecutorThreadFactory("ServerConnectionWorker-${threadsCreated++}") {
                thread = it
            }
        dispatcher = Executors.newSingleThreadExecutor(factory).asCoroutineDispatcher()

        val handler =
            CoroutineExceptionHandler { _, throwable -> exceptionHandler?.handleException(throwable) }
        coroutineContext = dispatcher.plus(handler)
        scope = CoroutineScope(coroutineContext + attemptJob)
        openDispatchers.incrementAndGet()
    }

    override fun assertDispatcherContext() {
        if (assertContext) {
            val actual = Thread.currentThread()
            if (actual !== thread) {
                val msg = "Thread mismatch, expected '${thread.name}', got '${actual.name}'"
                logger.error(msg)
                throw Error(msg)
            }
        }
    }

    override fun close() {
        // F1Whisper (tenth fork review, F10-02): idempotent, because an attempt is now disposed by whichever of its
        // three exits reaches it first - the loop's own `finally`, the dispatcher exception callback, or an explicit
        // stop() - and all three legitimately fire for the same attempt.
        if (!closed.compareAndSet(false, true)) {
            return
        }
        logger.info("Close connection dispatcher")
        attemptJob.cancel()
        dispatcher.close()
        openDispatchers.decrementAndGet()
    }

    override suspend fun closeAndJoin() {
        if (closed.get()) {
            return
        }
        attemptJob.cancel()
        withTimeoutOrNull(CLOSE_JOIN_TIMEOUT_MS) { attemptJob.join() }
            ?: logger.warn("Attempt coroutines did not unwind within {}ms; closing anyway", CLOSE_JOIN_TIMEOUT_MS)
        close()
    }
}
