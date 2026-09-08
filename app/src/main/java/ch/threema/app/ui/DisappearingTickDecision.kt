package ch.threema.app.ui

/**
 * F1Whisper (twelfth fork review, F12-03): what one countdown tick of [DisappearingTimerBadgeView] does, decided
 * purely from the remaining time so the deadline transition is unit-testable without a device.
 *
 * **The defect this closes.** Expiry used to be checked only at bind time (`ComposeMessageAdapter.getItemType` and
 * `ChatAdapterDecorator.configure`). The badge's tick runnable repainted frames forever - 50 ms apart inside the
 * final 30 s - and at zero did nothing: a row bound while live stayed fully visible and actionable past its deadline
 * until the durable worker deletion happened to land, and indefinitely when that deletion failed. The tick is the
 * only code guaranteed to be running on a bound live row when its deadline passes, so the tick is where the deadline
 * must become an event: [DisappearingTick.DEADLINE] retires the countdown and fires the badge's deadline listener,
 * which the binder arms with the enforcement + withhold reaction.
 */
enum class DisappearingTick {
    /** The deadline has passed: paint the final frame, retire the countdown, fire the deadline listener once. */
    DEADLINE,

    /** Inside the final 30 seconds: repaint and re-post quickly so the clock visibly ticks down to zero. */
    FAST_TICK,

    /** More than 30 seconds remain: repaint and re-post at the relaxed once-a-second cadence. */
    SLOW_TICK,
}

object DisappearingTickDecision {

    /** Cadence inside the final 30 seconds, and the arming delay when the deadline is due immediately. */
    const val FAST_TICK_MILLIS: Long = 50L

    /** Cadence while more than [FAST_WINDOW_MILLIS] remains. */
    const val SLOW_TICK_MILLIS: Long = 1000L

    /** The final window in which the clock ticks at [FAST_TICK_MILLIS]. */
    const val FAST_WINDOW_MILLIS: Long = 30_000L

    /**
     * Decides the tick for [remainingMillis] of countdown left (negative when the deadline already passed).
     */
    @JvmStatic
    fun decide(remainingMillis: Long): DisappearingTick = when {
        remainingMillis <= 0L -> DisappearingTick.DEADLINE
        remainingMillis < FAST_WINDOW_MILLIS -> DisappearingTick.FAST_TICK
        else -> DisappearingTick.SLOW_TICK
    }

    /**
     * The delay before the NEXT tick, given what this scheduling decision saw. [DisappearingTick.DEADLINE] maps to
     * [FAST_TICK_MILLIS]: it is used when ARMING a countdown that is already due (or crosses zero between ticks), so
     * the tick that delivers the deadline event runs promptly. The deadline tick itself never re-posts - the
     * runnable retires instead of asking for a delay.
     */
    @JvmStatic
    fun delayFor(tick: DisappearingTick): Long = when (tick) {
        DisappearingTick.DEADLINE, DisappearingTick.FAST_TICK -> FAST_TICK_MILLIS
        DisappearingTick.SLOW_TICK -> SLOW_TICK_MILLIS
    }
}
