package ch.threema.app.fragments.composemessage

import ch.threema.app.utils.RuntimeUtil
import ch.threema.storage.models.AbstractMessageModel
import java.util.function.LongSupplier
import java.util.function.Predicate

/**
 * F1Whisper (twelfth fork review, F12-04): fires a callback when a disappearing message's deadline passes WHILE a UI
 * operation is holding its content - the open quote popup, the share caption dialog. Those surfaces used to outlive
 * the deadline indefinitely: nothing re-checked expiry once they were open, so the quoted text stayed readable in the
 * composer and the share dialog kept its exportable decrypted copy for as long as the user left them up.
 *
 * On fire the watch re-asks the injected expiry authority (`DisappearingMessageService::enforceIfExpired` in
 * production - the same synchronous decision every other surface consults, which also re-enqueues the conditional
 * durable removal) and runs the reaction only on `true`; a premature fire re-arms with a floor so it can never hot
 * loop. [cancel] and re-[watch] are safe at any time: a superseded or cancelled tick recognises it is no longer the
 * current one and does nothing.
 *
 * The scheduler, authority and clock are injected so arming, firing, cancelling and superseding are executable on
 * the JVM; production uses [onMainThread].
 */
class MessageExpiryWatch(
    private val scheduler: Scheduler,
    private val expiryCheck: Predicate<AbstractMessageModel>,
    private val clock: LongSupplier,
) {

    interface Scheduler {
        fun postDelayed(runnable: Runnable, delayMillis: Long)

        fun cancel(runnable: Runnable)
    }

    companion object {
        /**
         * A premature fire (clock skew, an early Handler dispatch) re-arms at no less than this, so a deadline the
         * authority does not yet confirm can never spin the main thread.
         */
        const val RECHECK_FLOOR_MILLIS: Long = 250L

        /** The production scheduler: the app's main-thread handler. */
        @JvmStatic
        fun onMainThread(): Scheduler = object : Scheduler {
            override fun postDelayed(runnable: Runnable, delayMillis: Long) {
                RuntimeUtil.handler.postDelayed(runnable, delayMillis)
            }

            override fun cancel(runnable: Runnable) {
                RuntimeUtil.handler.removeCallbacks(runnable)
            }
        }
    }

    private var current: Runnable? = null

    /**
     * Arms [onDeadline] for [model]'s deadline, superseding any previous watch. A model with no RUNNING countdown
     * (no timer, or a timer not yet started) arms nothing - it has no deadline to watch.
     */
    fun watch(model: AbstractMessageModel, onDeadline: Runnable) {
        cancel()
        val deadline = runningDeadlineOf(model) ?: return
        val tick = object : Runnable {
            override fun run() {
                synchronized(this@MessageExpiryWatch) {
                    if (current !== this) {
                        // Cancelled or superseded after dispatch: the current owner drives the watch, not us.
                        return
                    }
                    current = null
                }
                if (expiryCheck.test(model)) {
                    onDeadline.run()
                    return
                }
                // Premature (or the countdown was re-checked as not expired): re-arm for the remainder, floored.
                val remainingDeadline = runningDeadlineOf(model) ?: return
                val delay = maxOf(remainingDeadline - clock.asLong, RECHECK_FLOOR_MILLIS)
                synchronized(this@MessageExpiryWatch) {
                    if (current != null) {
                        // A new watch was armed while we were deciding; it owns the slot now.
                        return
                    }
                    current = this
                }
                scheduler.postDelayed(this, delay)
            }
        }
        synchronized(this) {
            current = tick
        }
        scheduler.postDelayed(tick, maxOf(0L, deadline - clock.asLong))
    }

    /** Disarms the watch. Safe when nothing is armed; a tick already dispatched recognises the cancellation. */
    fun cancel() {
        val cancelled: Runnable?
        synchronized(this) {
            cancelled = current
            current = null
        }
        if (cancelled != null) {
            scheduler.cancel(cancelled)
        }
    }

    private fun runningDeadlineOf(model: AbstractMessageModel): Long? {
        if ((model.disappearingTimerSeconds ?: 0) <= 0 || model.expireStartedAt == null) {
            return null
        }
        return model.expiresAt
    }
}
