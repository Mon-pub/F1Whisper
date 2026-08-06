package ch.threema.domain.taskmanager

import ch.threema.domain.protocol.connection.layer.Layer5Codec
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * F1Whisper: regressions for the two [TaskRunner] wedges.
 *
 * These are SECONDARY defects. Both predict a connection that reports LOGGEDIN while the message
 * pipeline is dead, and the incident that motivated this work reported DISCONNECTED, so neither of
 * them is the reported bug. They are real, they are permanent once they fire, and they are cheap to
 * close, which is why they are fixed here.
 */
class TaskRunnerSemaphoreTest {

    /**
     * A STRICT TaskQueue mock. Deliberately not `relaxed = true`.
     *
     * A relaxed mock returns a relaxed `TaskQueueElement` from `getNextTask()`, whose `isCompleted()`
     * then answers `false` forever, so `runNextTask`'s `while (!isCompleted())` becomes a tight
     * infinite loop. Every iteration is recorded by mockk, which exhausts the test worker heap in
     * seconds and kills the whole Gradle test executor, taking unrelated test classes down with it and
     * reporting the run as "failed to execute tests" rather than as a test failure. It also made an
     * earlier version of the state assertion below pass for the wrong reason: the executor job was
     * never dying, it was spinning.
     *
     * Strict stubbing means any interaction these tests did not anticipate fails loudly and locally.
     */
    private fun strictTaskQueue(): TaskQueue {
        val taskQueue = mockk<TaskQueue>()
        every { taskQueue.recreateIncomingMessageQueue(any()) } returns Unit
        coEvery { taskQueue.removeDropOnDisconnectTasks(any()) } returns Unit
        // An idle runner SUSPENDS waiting for work; it does not busy-loop. Modelling that faithfully
        // is what keeps a started executor job alive without allocating, so a test that starts the
        // runner and then walks away cannot burn the worker down behind the next test class.
        coEvery { taskQueue.getNextTask() } coAnswers { awaitCancellation() }
        return taskQueue
    }

    private fun createDispatchers() = TaskManagerImpl.TaskManagerDispatchers(
        executorDispatcher = SingleThreadedTaskManagerDispatcher(
            assertContext = false,
            threadName = "TestExecutorDispatcher",
        ),
        scheduleDispatcher = SingleThreadedTaskManagerDispatcher(
            assertContext = false,
            threadName = "TestScheduleDispatcher",
        ),
    )

    /**
     * The stranded permit. `startTaskRunner` acquires a `Semaphore(1, 0)` and releases it at the very
     * end with nothing in between guaranteeing the release. The `TaskRunner` is a `lazy` singleton on
     * the task manager, so the semaphore outlives every reconnect: one throw between acquire and
     * release blocks every future `startTaskRunner` for the life of the process, with no exception
     * and no log line. The connection keeps reconnecting and reporting LOGGEDIN, and no task ever
     * runs again.
     */
    @Test
    fun `a throw between acquire and release leaves the runner startable`() = runBlocking {
        val taskQueue = strictTaskQueue()
        val runner = TaskRunner(createDispatchers(), taskQueue)
        val codec = mockk<Layer5Codec>(relaxed = true)
        val processor = mockk<IncomingMessageProcessor>(relaxed = true)

        // Fail inside the window between the acquire and the release. This mirrors a cancellation of
        // the EndToEndLayer init scope while suspended in the queue recreation.
        every { taskQueue.recreateIncomingMessageQueue(any()) } throws IllegalStateException("boom")

        assertFailsWith<IllegalStateException> {
            runner.startTaskRunner(codec, processor)
        }

        // The permit must have been returned, so a later start can proceed. Without the fix this call
        // parks forever on the semaphore and the timeout fires.
        every { taskQueue.recreateIncomingMessageQueue(any()) } returns Unit
        withTimeout(5.seconds) {
            runner.startTaskRunner(codec, processor)
        }

        runner.stopTaskRunner(null)
    }

    /**
     * The same wedge reached by cancellation rather than by a throw, which is the shape actually
     * described in the code: the caller's scope is cancelled while `startTaskRunner` is suspended.
     */
    @Test
    fun `a cancellation between acquire and release leaves the runner startable`() = runBlocking {
        val taskQueue = strictTaskQueue()
        val runner = TaskRunner(createDispatchers(), taskQueue)
        val codec = mockk<Layer5Codec>(relaxed = true)
        val processor = mockk<IncomingMessageProcessor>(relaxed = true)

        val enteredTheWindow = CompletableDeferred<Unit>()
        every { taskQueue.recreateIncomingMessageQueue(any()) } answers {
            // We are now past the acquire. Signal the test and let it cancel us.
            enteredTheWindow.complete(Unit)
            Thread.sleep(200)
        }

        val caller = CoroutineScope(Dispatchers.Default).launch {
            runner.startTaskRunner(codec, processor)
        }
        enteredTheWindow.await()
        caller.cancel()
        caller.join()

        every { taskQueue.recreateIncomingMessageQueue(any()) } returns Unit
        withTimeout(5.seconds) {
            runner.startTaskRunner(codec, processor)
        }

        runner.stopTaskRunner(null)
    }

    /**
     * The lying state getter. `state` returned RUNNING whenever `layer5Codec != null`, and the codec
     * is only nulled by `stopTaskRunner`. So an executor job that dies on its own (any exception that
     * is not a ConnectionStoppedException or a ProtocolException) left `state` reporting RUNNING for
     * a runner that runs nothing, and `TaskManagerImpl.schedule` would keep enqueueing
     * DropOnDisconnectTasks into it instead of failing them fast.
     */
    @Test
    fun `state is not RUNNING after the executor job dies without stopTaskRunner`() = runBlocking {
        val taskQueue = strictTaskQueue()
        val runner = TaskRunner(createDispatchers(), taskQueue)
        val codec = mockk<Layer5Codec>(relaxed = true)
        val processor = mockk<IncomingMessageProcessor>(relaxed = true)

        // The executor job dies on an unexpected exception. stopTaskRunner is never called, so
        // layer5Codec stays non-null.
        coEvery { taskQueue.getNextTask() } throws RuntimeException("executor died")

        runner.startTaskRunner(codec, processor)

        // Give the executor job a bounded moment to fail. Do not assert on a fixed sleep alone: poll,
        // so a slow host does not turn a real pass into a flake.
        withTimeout(5.seconds) {
            while (runner.state == TaskRunner.State.RUNNING) {
                delay(20)
            }
        }

        assertNotEquals(
            TaskRunner.State.RUNNING,
            runner.state,
            "state must not report RUNNING for a dead executor job",
        )
    }
}
