package ch.threema.app.services

import ch.threema.base.utils.getThreemaLogger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = getThreemaLogger("AlarmRecomputationGate")

/**
 * F1Whisper (fourth fork review, F4-03): makes "read the queue, decide, act on the alarm" ONE indivisible step.
 *
 * The defect this exists to remove: [AlarmScheduler.rescheduleNextAlarm] ran the query, the decision and the `AlarmManager` call with
 * nothing serialising them, while every producer of disappearing-message state - creation, first read, an incoming control message,
 * deletion, startup, the alarm firing itself - called it from its own thread against one shared `PendingIntent`. Two calls could
 * therefore overlap, and the alarm ended up reflecting whichever call happened to FINISH last rather than whichever read the newest
 * database state:
 *
 * ```
 * call A: read queue (empty) ............................................. cancel   <- lands last, wins
 * call B:                     insert earlier item, read queue, arm t1
 * ```
 *
 * The result is an alarm that is cancelled or pushed later than the data says, so disappearing content outlives its deadline until
 * some unrelated event re-arms the engine.
 *
 * The fix is ordering, not retrying: with the read inside the same critical section as the action, the last action to run is by
 * construction the one whose read saw the newest committed state. A generation counter would be redundant on top of that - there is no
 * window left in which a computation can go stale - so this deliberately does not add one.
 *
 * Cost: recomputations serialise. They are rare (one per state change), the read is a single indexed `MIN` and the action is one
 * `AlarmManager` call, and the alternative is the wrong alarm. [ReentrantLock] rather than a plain object monitor so that a re-entrant
 * call from inside a query cannot deadlock itself.
 *
 * No Android imports, so the whole ordering contract is unit-testable without a device; only the `AlarmManager` call itself stays
 * behind the boundary.
 */
class AlarmRecomputationGate {
    private val lock = ReentrantLock()

    /**
     * Read the queue with [queryEarliestTarget], resolve what that means for the pending alarm, and hand the verdict to [act] - all
     * while holding this gate, so no other recomputation can interleave between the read and the action.
     *
     * A throwing [queryEarliestTarget] is treated exactly as [AlarmTarget.Unavailable]: an exception is one more way of not knowing,
     * and the one thing it must never do is look like an empty queue.
     *
     * @param retryDelayMillis how far ahead to arm the recovery alarm when the queue cannot be read.
     * @param nowMillis the wall clock to resolve against, injectable so the decision stays deterministic under test.
     */
    fun applyLatest(
        retryDelayMillis: Long,
        nowMillis: () -> Long = System::currentTimeMillis,
        queryEarliestTarget: () -> AlarmTarget,
        act: (AlarmAction) -> Unit,
    ) {
        lock.withLock {
            val target: AlarmTarget = try {
                queryEarliestTarget()
            } catch (e: Exception) {
                logger.error("Could not read earliest alarm time; arming a retry", e)
                AlarmTarget.Unavailable
            }
            if (target is AlarmTarget.Unavailable) {
                logger.warn("Alarm queue unreadable; arming a retry in {} ms instead of cancelling", retryDelayMillis)
            }
            act(AlarmPlanDecision.resolve(target, nowMillis(), retryDelayMillis))
        }
    }
}
