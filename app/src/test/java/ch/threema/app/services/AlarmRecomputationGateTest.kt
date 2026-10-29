package ch.threema.app.services

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (fourth fork review, F4-03): deterministic interleaving tests for [AlarmRecomputationGate].
 *
 * The defect: `AlarmScheduler.rescheduleNextAlarm` read the disappearing-message queue, decided, and called `AlarmManager` with nothing
 * serialising the three steps, while creation, first read, incoming control messages, deletion, startup and the alarm itself all called
 * it from their own threads against ONE shared `PendingIntent` (request code 201). A recomputation that read an older state could
 * therefore finish last and undo a newer one, leaving disappearing content alive past its deadline.
 *
 * These tests drive the production gate directly. The queue is a shared reference read inside the query lambda, exactly as production
 * reads `earliestExpiry` there, and the "alarm" is a shared record of the actions the gate hands out - so the assertions are about the
 * ORDER production establishes, which is the whole of the fix. Latches, never sleeps: every interleaving here is forced, not hoped for.
 *
 * [legacyUnguardedRecomputationLetsAStaleReadWinTheAlarm] is the control. It writes the OLD rule out inline and deliberately does NOT
 * call production code: routed through the gate it would stop reproducing, which is the point of keeping it.
 *
 * What this cannot cover, recorded rather than glossed: `alarmManager.cancel/setExactAndAllowWhileIdle` and the `PendingIntent` itself
 * are not JVM-reachable. The gate is what orders them; the Android half stays on the device-matrix debt list.
 */
class AlarmRecomputationGateTest {
    private val retryDelay = 5 * 60 * 1000L
    private val now = 1_700_000_000_000L
    private val timeoutSeconds = 5L

    /**
     * How long a recomputation held behind the gate is given to prove it did NOT run. Generous enough that the ungated
     * implementation always slips through it (thread start is a millisecond), so removing the gate turns these tests red.
     */
    private val blockedWindowMillis = 500L

    /** Stands in for the two message tables: what a query issued right now would find. */
    private val queue = AtomicReference<AlarmTarget>(AlarmTarget.None)

    /** Stands in for the one request-code-201 alarm slot: every action overwrites the previous one. */
    private val actions: MutableList<AlarmAction> = Collections.synchronizedList(mutableListOf())

    private fun gateApply(gate: AlarmRecomputationGate, query: () -> AlarmTarget) {
        gate.applyLatest(
            retryDelayMillis = retryDelay,
            nowMillis = { now },
            queryEarliestTarget = query,
        ) { action ->
            actions.add(action)
        }
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The three interleavings the review named.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a stale empty read cannot cancel a newer alarm`() {
        val gate = AlarmRecomputationGate()
        val staleHasRead = CountDownLatch(1)
        val staleMayAct = CountDownLatch(1)

        // A: reads the queue while it is still empty, then stalls before acting on that reading.
        val stale = Thread {
            gateApply(gate) {
                val seen = queue.get()
                staleHasRead.countDown()
                staleMayAct.await()
                seen
            }
        }
        stale.start()
        assertTrue(staleHasRead.await(timeoutSeconds, TimeUnit.SECONDS))

        // The producer commits an earlier item and asks for a recomputation, exactly as an incoming message does.
        queue.set(AlarmTarget.At(now + 30_000))
        val freshHasRead = CountDownLatch(1)
        val fresh = Thread {
            gateApply(gate) {
                freshHasRead.countDown()
                queue.get()
            }
        }
        fresh.start()

        // The gate must hold the newer recomputation until the stale one has finished acting. Waiting for its READ (not its
        // action) is what makes this decisive: ungated, the newer query runs the moment its thread starts.
        assertFalse(
            freshHasRead.await(blockedWindowMillis, TimeUnit.MILLISECONDS),
            "the newer recomputation must not even read the queue while the stale one holds the gate",
        )
        assertTrue(actions.isEmpty(), "no action may land while the first recomputation still holds the gate")

        staleMayAct.countDown()
        stale.join(timeoutSeconds * 1000)
        fresh.join(timeoutSeconds * 1000)

        assertEquals(
            listOf<AlarmAction>(AlarmAction.Cancel, AlarmAction.ArmAt(now + 30_000)),
            actions.toList(),
            "the stale cancel may still run, but the newer arming must run after it and win",
        )
    }

    @Test
    fun `a stale later time cannot replace a newer earlier alarm`() {
        val gate = AlarmRecomputationGate()
        queue.set(AlarmTarget.At(now + 600_000))
        val staleHasRead = CountDownLatch(1)
        val staleMayAct = CountDownLatch(1)

        val stale = Thread {
            gateApply(gate) {
                val seen = queue.get()
                staleHasRead.countDown()
                staleMayAct.await()
                seen
            }
        }
        stale.start()
        assertTrue(staleHasRead.await(timeoutSeconds, TimeUnit.SECONDS))

        // A message with a much shorter timer arrives.
        queue.set(AlarmTarget.At(now + 5_000))
        val freshHasRead = CountDownLatch(1)
        val fresh = Thread {
            gateApply(gate) {
                freshHasRead.countDown()
                queue.get()
            }
        }
        fresh.start()

        assertFalse(
            freshHasRead.await(blockedWindowMillis, TimeUnit.MILLISECONDS),
            "the newer recomputation must not read the queue while the stale one holds the gate",
        )

        staleMayAct.countDown()
        stale.join(timeoutSeconds * 1000)
        fresh.join(timeoutSeconds * 1000)

        assertEquals(
            AlarmAction.ArmAt(now + 5_000),
            actions.last(),
            "the alarm must end on the earliest pending deadline, not on the reading that happened to finish last",
        )
    }

    @Test
    fun `reversed completion order still leaves the newest persisted state armed`() {
        val gate = AlarmRecomputationGate()
        val entered = AtomicInteger(0)
        val concurrentPeak = AtomicInteger(0)
        val threadCount = 8

        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val threads = (0 until threadCount).map { index ->
            Thread {
                start.await()
                gateApply(gate) {
                    val depth = entered.incrementAndGet()
                    concurrentPeak.updateAndGet { peak -> maxOf(peak, depth) }
                    // Every caller writes the queue before reading it, so completion order and read order are shuffled together.
                    val target = AlarmTarget.At(now + 1_000L * (index + 1))
                    queue.set(target)
                    val seen = queue.get()
                    entered.decrementAndGet()
                    seen
                }
                done.countDown()
            }
        }
        threads.forEach(Thread::start)
        start.countDown()
        assertTrue(done.await(timeoutSeconds, TimeUnit.SECONDS))

        assertEquals(1, concurrentPeak.get(), "read-decide-act must never overlap with another read-decide-act")
        assertEquals(threadCount, actions.size)
        assertEquals(
            AlarmAction.ArmAt((queue.get() as AlarmTarget.At).epochMillis),
            actions.last(),
            "the last action must match the last committed queue state",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The wiring the gate took over from AlarmScheduler.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `an unreadable queue arms a retry rather than cancelling`() {
        val gate = AlarmRecomputationGate()

        gateApply(gate) { AlarmTarget.Unavailable }

        assertEquals(AlarmAction.ArmAt(now + retryDelay), actions.single())
    }

    @Test
    fun `a throwing query is treated as unreadable, never as empty`() {
        val gate = AlarmRecomputationGate()

        gateApply(gate) { throw IllegalStateException("database is locked") }

        assertEquals(AlarmAction.ArmAt(now + retryDelay), actions.single())
    }

    @Test
    fun `a throwing query does not leave the gate held`() {
        val gate = AlarmRecomputationGate()

        gateApply(gate) { throw IllegalStateException("database is locked") }
        gateApply(gate) { AlarmTarget.At(now + 1_000) }

        assertEquals(AlarmAction.ArmAt(now + 1_000), actions.last())
    }

    @Test
    fun `an empty queue still cancels`() {
        val gate = AlarmRecomputationGate()

        gateApply(gate) { AlarmTarget.None }

        assertEquals(AlarmAction.Cancel, actions.single())
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Legacy control: the old rule, written out inline. Calls no production code on purpose.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun legacyUnguardedRecomputationLetsAStaleReadWinTheAlarm() {
        val armed = AtomicReference<AlarmAction?>(null)
        val staleHasRead = CountDownLatch(1)
        val staleMayAct = CountDownLatch(1)
        val freshHasActed = CountDownLatch(1)

        // The old shape: read, then decide, then act, with nothing between them.
        fun unguardedReschedule(query: () -> AlarmTarget) {
            val target = query()
            armed.set(
                when (target) {
                    is AlarmTarget.At -> AlarmAction.ArmAt(target.epochMillis)
                    AlarmTarget.None -> AlarmAction.Cancel
                    AlarmTarget.Unavailable -> AlarmAction.ArmAt(now + retryDelay)
                },
            )
        }

        val stale = Thread {
            unguardedReschedule {
                val seen = queue.get()
                staleHasRead.countDown()
                staleMayAct.await()
                seen
            }
        }
        stale.start()
        assertTrue(staleHasRead.await(timeoutSeconds, TimeUnit.SECONDS))

        queue.set(AlarmTarget.At(now + 30_000))
        val fresh = Thread {
            unguardedReschedule { queue.get() }
            freshHasActed.countDown()
        }
        fresh.start()
        assertTrue(freshHasActed.await(timeoutSeconds, TimeUnit.SECONDS), "nothing held the newer recomputation back")

        staleMayAct.countDown()
        stale.join(timeoutSeconds * 1000)
        fresh.join(timeoutSeconds * 1000)

        assertEquals(
            AlarmAction.Cancel,
            armed.get(),
            "this is the defect: the stale read finished last and cancelled the alarm the newer state had armed",
        )
        assertNull(actions.firstOrNull(), "the control must not touch production code")
    }
}
