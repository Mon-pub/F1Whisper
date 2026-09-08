package ch.threema.app.fragments.composemessage

import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageType
import java.util.function.LongSupplier
import java.util.function.Predicate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * F1Whisper (twelfth fork review, F12-04): executable spec of [MessageExpiryWatch] on the held scheduler seam -
 * arming at the deadline, re-checking the authority on fire, cancel and supersede safety, and the re-arm floor. The
 * production wiring (which surfaces arm it and what their reactions do) is pinned in [InFlightExpiryWiringTest].
 */
class MessageExpiryWatchTest {

    private class HeldScheduler : MessageExpiryWatch.Scheduler {
        val posted = mutableListOf<Pair<Runnable, Long>>()
        val cancelled = mutableListOf<Runnable>()

        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            posted.add(runnable to delayMillis)
        }

        override fun cancel(runnable: Runnable) {
            cancelled.add(runnable)
        }
    }

    private val scheduler = HeldScheduler()
    private var now = 1_000_000L
    private val expired = mutableSetOf<AbstractMessageModel>()
    private var deadlineFired = 0

    private val watch = MessageExpiryWatch(
        scheduler,
        Predicate { expired.contains(it) },
        LongSupplier { now },
    )

    private fun runningCountdown(expiresInMillis: Long): MessageModel =
        MessageModel().apply {
            id = 5
            type = MessageType.TEXT
            disappearingTimerSeconds = 30
            expireStartedAt = now
            expiresAt = now + expiresInMillis
        }

    private val onDeadline = Runnable { deadlineFired++ }

    // -----------------------------------------------------------------------------------------------------------------
    // Arming
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `watching a running countdown arms at the remaining time`() {
        watch.watch(runningCountdown(expiresInMillis = 5_000L), onDeadline)

        assertEquals(1, scheduler.posted.size)
        assertEquals(5_000L, scheduler.posted.single().second)
        assertEquals(0, deadlineFired, "arming alone must not fire")
    }

    @Test
    fun `a message without a running countdown arms nothing`() {
        watch.watch(MessageModel().apply { type = MessageType.TEXT }, onDeadline)
        watch.watch(
            MessageModel().apply {
                type = MessageType.TEXT
                disappearingTimerSeconds = 30
                // Frozen: timer exists but never started - there is no deadline yet.
            },
            onDeadline,
        )

        assertTrue(scheduler.posted.isEmpty())
    }

    @Test
    fun `an already-due countdown arms immediately, never with a negative delay`() {
        watch.watch(runningCountdown(expiresInMillis = -2_000L), onDeadline)

        assertEquals(0L, scheduler.posted.single().second)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Firing: the authority is re-asked, never trusted from arming time
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the fire re-checks the authority and reacts only when it confirms expiry`() {
        val model = runningCountdown(expiresInMillis = 5_000L)
        watch.watch(model, onDeadline)
        val tick = scheduler.posted.single().first

        now += 5_000L
        expired.add(model)
        tick.run()

        assertEquals(1, deadlineFired)
        assertEquals(1, scheduler.posted.size, "a confirmed deadline retires the watch - no re-arm")
    }

    @Test
    fun `a premature fire re-arms with the floor instead of reacting or spinning`() {
        val model = runningCountdown(expiresInMillis = 5_000L)
        watch.watch(model, onDeadline)
        val tick = scheduler.posted.single().first

        // Dispatched early (clock skew): the authority does not confirm, so nothing fires and the watch
        // re-arms - with at least the floor, so a deadline the authority never confirms cannot hot-loop.
        now += 4_990L
        tick.run()

        assertEquals(0, deadlineFired)
        assertEquals(2, scheduler.posted.size)
        val rearm = scheduler.posted[1]
        assertSame(tick, rearm.first)
        assertEquals(MessageExpiryWatch.RECHECK_FLOOR_MILLIS, rearm.second, "10 ms remaining floors to the re-check minimum")
    }

    @Test
    fun `a fire on a countdown whose timer was cleared retires silently`() {
        val model = runningCountdown(expiresInMillis = 5_000L)
        watch.watch(model, onDeadline)
        val tick = scheduler.posted.single().first

        model.disappearingTimerSeconds = null
        now += 5_000L
        tick.run()

        assertEquals(0, deadlineFired, "no timer, no deadline - nothing to enforce anymore")
        assertEquals(1, scheduler.posted.size, "and nothing to re-arm for")
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Cancel and supersede
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `cancel unhooks the armed tick and a stale dispatch of it does nothing`() {
        val model = runningCountdown(expiresInMillis = 5_000L)
        watch.watch(model, onDeadline)
        val tick = scheduler.posted.single().first

        watch.cancel()

        assertEquals(listOf(tick), scheduler.cancelled)

        // A dispatch that slipped past the scheduler-side cancellation must recognise it is no longer current.
        now += 5_000L
        expired.add(model)
        tick.run()

        assertEquals(0, deadlineFired)
    }

    @Test
    fun `a new watch supersedes the old one`() {
        val oldModel = runningCountdown(expiresInMillis = 5_000L)
        watch.watch(oldModel, onDeadline)
        val oldTick = scheduler.posted[0].first

        var newFired = 0
        val newModel = runningCountdown(expiresInMillis = 9_000L)
        watch.watch(newModel, Runnable { newFired++ })
        val newTick = scheduler.posted[1].first

        assertEquals(listOf(oldTick), scheduler.cancelled, "arming a new watch disarms the old")

        now += 9_000L
        expired.add(oldModel)
        expired.add(newModel)
        oldTick.run()
        newTick.run()

        assertEquals(0, deadlineFired, "the superseded tick may never fire its reaction")
        assertEquals(1, newFired)
    }

    @Test
    fun `a stale premature tick does not re-arm over a newer watch`() {
        val oldModel = runningCountdown(expiresInMillis = 5_000L)
        watch.watch(oldModel, onDeadline)
        val oldTick = scheduler.posted[0].first

        watch.watch(runningCountdown(expiresInMillis = 9_000L), onDeadline)

        // The old tick is dispatched anyway (already in flight when superseded): it must not steal the slot
        // back by re-arming itself.
        oldTick.run()

        assertEquals(2, scheduler.posted.size, "only the two watch() arms - no re-arm from the stale tick")
        assertEquals(1, scheduler.posted.count { it.first === oldTick }, "the stale tick appears only from its own arm")
    }
}
