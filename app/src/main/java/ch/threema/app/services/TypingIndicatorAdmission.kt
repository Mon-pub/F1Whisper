package ch.threema.app.services

import androidx.annotation.VisibleForTesting
import ch.threema.base.utils.getThreemaLogger
import java.util.Timer
import java.util.TimerTask

private val logger = getThreemaLogger("TypingIndicatorAdmission")

/**
 * F1Whisper: how often a conversation may put a typing START on the wire.
 *
 * Matched to the sender's own heartbeat (`TypingIndicatorTextWatcher.TYPING_RESEND_INTERVAL`) rather than chosen
 * independently: both receivers expire a typing state 15 s after the last one they saw
 * (`ContactServiceImpl.TYPING_RECEIVE_TIMEOUT`, `GroupServiceImpl.GROUP_TYPING_RESET_TIMEOUT_MS`), so a longer window
 * would leave the indicator absent during continuous typing rather than merely less frequent.
 */
private const val MIN_START_INTERVAL_MS = 10_000L

/**
 * How long a conversation's admission state outlives its last admitted start.
 *
 * Ending a burst removes the state, but not every burst ends through this gate (an app kill, a fragment torn down
 * mid-word), so it also has to expire on its own or the map grows for the life of the process. Anything past one
 * interval carries no decision.
 */
private const val STATE_RETENTION_MS = 60_000L

/**
 * F1Whisper: rate-limits OUTGOING typing indicators, one conversation at a time.
 *
 * Typing indicators were 54% of all outbound end-to-end messages in 13 days of device logs - 4603 of 8524 - and 54% of
 * everything wrapped in Forward Security, each one burning a ratchet counter. The group indicator is the expensive
 * half: 242 typing events became 2516 wire messages, a measured fan-out of 10.4, against 386 for group text.
 *
 * This gate sits at the SERVICE entry rather than in the composer's watcher, because the watcher is not the only
 * caller: legacy Web's `IsTypingHandler` calls [ContactService.sendTypingIndicator] directly and bypasses it entirely.
 *
 * Two properties matter and neither is achieved by a bare "return if too soon":
 *
 * - A suppressed START is **coalesced into a trailing one**, emitted when the window closes. Dropping it instead would
 *   silently skip a heartbeat and open a gap longer than the receiver's 15 s lease, so the indicator would flicker off
 *   in the middle of continuous typing.
 * - A STOP **ends the burst here and is never put on the wire**. Suppressing the stop is the second half of the
 *   traffic saving (it is one of the two indicators a typing burst costs), and the receiver does not need it: it drops
 *   the sender's typing state when a message from them arrives, and expires it after 15 s regardless.
 *
 * Ending the burst is the part that must NOT be suppressed, and suppressing it in the composer's watcher instead of
 * here was wrong: the watcher stopped reporting the end at all, so a coalesced trailing start outlived the send that
 * should have cancelled it and turned the peer's indicator back on AFTER the message had already cleared it. The end
 * of a burst is a fact this gate needs; only the wire message is optional.
 *
 * Scheduling races are settled the way `GroupServiceImpl.setMemberTyping` settles them: every call bumps a
 * per-conversation generation, and a trailing task only fires while its captured generation is still current. That
 * closes the window between releasing the lock to schedule and storing the handle, in which a stop can arrive with
 * nothing yet to cancel.
 *
 * One window is knowingly left open. A trailing start that has already passed its generation check is emitted outside
 * the lock, so a stop landing in those few instructions cannot recall it and one start reaches the peer just after
 * composing ended. Closing it would mean holding this lock across a call into the messaging stack, which trades a
 * bounded cosmetic indicator - the receiver expires it in 15 s - for a deadlock class. Not worth it.
 *
 * @param emit called for each admitted indicator, always OUTSIDE this object's lock. Only ever called with `true`:
 *   see the STOP note above.
 */
class TypingIndicatorAdmission<K : Any> @JvmOverloads constructor(
    private val emit: Emitter<K>,
    private val nowMillis: () -> Long = ::monotonicMillis,
    private val scheduler: Scheduler = TimerScheduler(),
) {
    fun interface Emitter<K : Any> {
        fun emit(conversation: K, isTyping: Boolean)
    }

    fun interface Cancellable {
        fun cancel()
    }

    fun interface Scheduler {
        /** Runs [action] after [delayMillis], unless the returned handle is cancelled first. */
        fun schedule(delayMillis: Long, action: Runnable): Cancellable
    }

    private class ConversationState {
        var lastAdmittedStartAt: Long? = null
        var pendingStart: Cancellable? = null
        var generation: Long = 0
    }

    private val states = HashMap<K, ConversationState>()

    /**
     * Decide what this typing event costs on the wire: emit it now, hold it back as a trailing start, or - for a stop -
     * end the conversation's window and put nothing on the wire.
     */
    fun admit(conversation: K, isTyping: Boolean) {
        val generation: Long
        val trailingDelay: Long?

        synchronized(states) {
            pruneIdleStates()
            val state = states.getOrPut(conversation) { ConversationState() }
            generation = ++state.generation
            state.pendingStart?.cancel()
            state.pendingStart = null

            if (!isTyping) {
                // A stop ends the burst: the coalesced start is dropped rather than delivered late, and the next start
                // belongs to a new burst and must not be held back. Nothing goes on the wire.
                states.remove(conversation)
                return
            }

            val elapsed = state.lastAdmittedStartAt?.let { nowMillis() - it }
            trailingDelay = if (elapsed == null || elapsed >= MIN_START_INTERVAL_MS) {
                state.lastAdmittedStartAt = nowMillis()
                null
            } else {
                MIN_START_INTERVAL_MS - elapsed
            }
        }

        if (trailingDelay == null) {
            emit.emit(conversation, true)
            return
        }

        val handle = scheduler.schedule(trailingDelay) { emitTrailingStart(conversation, generation) }
        synchronized(states) {
            val state = states[conversation]
            if (state == null || state.generation != generation) {
                // Superseded between the two locks. The newer call owns this conversation.
                handle.cancel()
            } else {
                state.pendingStart = handle
            }
        }
    }

    private fun emitTrailingStart(conversation: K, generation: Long) {
        synchronized(states) {
            val state = states[conversation] ?: return
            if (state.generation != generation) {
                return
            }
            state.pendingStart = null
            state.lastAdmittedStartAt = nowMillis()
        }
        emit.emit(conversation, true)
    }

    /** How many conversations currently hold admission state. Only the retention rule is asserted through it. */
    @VisibleForTesting
    internal fun trackedConversationCount(): Int = synchronized(states) { states.size }

    /** Caller holds the lock. */
    private fun pruneIdleStates() {
        val now = nowMillis()
        states.entries.removeAll { (_, state) ->
            state.pendingStart == null &&
                state.lastAdmittedStartAt?.let { now - it > STATE_RETENTION_MS } == true
        }
    }

    private class TimerScheduler : Scheduler {
        private val timer = Timer("TypingIndicatorAdmission", true)

        override fun schedule(delayMillis: Long, action: Runnable): Cancellable {
            val task = object : TimerTask() {
                override fun run() = action.run()
            }
            return try {
                timer.schedule(task, delayMillis)
                Cancellable { task.cancel() }
            } catch (e: Exception) {
                logger.error("Failed to schedule a trailing typing indicator", e)
                Cancellable {}
            }
        }
    }
}

/**
 * F1Whisper: elapsed time for the typing paths, from a clock that cannot jump.
 *
 * Wall-clock time is wrong for both the admission window and the task freshness check: an NTP correction or a manual
 * time change would either release a whole burst at once or discard every queued indicator.
 */
internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000
