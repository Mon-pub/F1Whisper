package ch.threema.app.services

/**
 * F1Whisper: what a scheduler's query could tell us about the next alarm.
 *
 * The three cases are NOT interchangeable, and collapsing two of them is the defect this type exists
 * to remove. [AlarmScheduler] previously took a `() -> Long?`, in which `null` had to mean both
 * "nothing is scheduled" and "I could not find out", because that is all a nullable Long can say.
 * It was read as the first, so a query that ran with the service graph down - a locked master key, a
 * cold boot before initialisation, a database that could not be opened - **cancelled the alarm**.
 * `DisappearingMessageService.fireDue` even logged "will re-arm retry" on that path while doing the
 * opposite, and the engine then stayed disarmed until the next boot.
 */
sealed interface AlarmTarget {
    /** The next alarm is due at [epochMillis]. */
    data class At(val epochMillis: Long) : AlarmTarget

    /** The queue is genuinely empty. Nothing needs to fire, so any pending alarm may be cancelled. */
    object None : AlarmTarget

    /**
     * The queue could not be read at all. Says nothing about whether work is pending, so it must
     * never be treated as [None].
     */
    object Unavailable : AlarmTarget
}

/** What [AlarmScheduler] should do about the pending alarm. */
sealed interface AlarmAction {
    /** Cancel the pending alarm. Only ever the answer to [AlarmTarget.None]. */
    object Cancel : AlarmAction

    /** (Re-)arm the alarm for [epochMillis]. */
    data class ArmAt(val epochMillis: Long) : AlarmAction
}

/**
 * F1Whisper: the pure half of [AlarmScheduler] - turning what the queue said into what to do with
 * the alarm. No Android imports, so both alarm engines' policy is unit-testable without a device.
 *
 * The interesting case is [AlarmTarget.Unavailable], which arms a retry rather than leaving the
 * pending alarm alone. Leaving it alone is correct only when something IS already armed; on a cold
 * boot with a locked master key nothing is, and the engine would then wait for an unrelated future
 * event. Arming the retry can overwrite a sooner pending alarm and delay a deletion by at most
 * [retryDelayMillis], and only inside a window where the service graph is down and no chat can be
 * displayed anyway. That is the cost; the behaviour it replaces was cancelling the alarm outright.
 */
object AlarmPlanDecision {

    /**
     * @param target what the queue query returned.
     * @param nowMillis the current wall clock, passed in so the decision stays pure.
     * @param retryDelayMillis how far ahead to arm the retry when the queue is unreadable. Must be
     *   positive; a zero or negative value would arm an alarm in the past and spin.
     */
    @JvmStatic
    fun resolve(target: AlarmTarget, nowMillis: Long, retryDelayMillis: Long): AlarmAction =
        when (target) {
            is AlarmTarget.At -> AlarmAction.ArmAt(target.epochMillis)
            AlarmTarget.None -> AlarmAction.Cancel
            AlarmTarget.Unavailable -> AlarmAction.ArmAt(nowMillis + maxOf(1L, retryDelayMillis))
        }
}
