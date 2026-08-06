package ch.threema.app.services

/**
 * F1Whisper (fifth fork review, F5-04): whether marking a message read starts its countdown, and at what values.
 *
 * Extracted from `MessageServiceImpl.markAsRead` because that decision now has to be made against a FRESHLY RELOADED row
 * and re-made when the conditional write that follows it is refused. A decision that is re-run in a retry loop should not
 * also be spelled out inline in that loop.
 *
 * The fallback tests `frozenTimerSeconds == null` and NOT `<= 0`, and that distinction is load-bearing. A frozen `0` means
 * the SENDER explicitly advertised "timer off" for this message and the recipient must keep it forever; falling back on `0`
 * would re-freeze exactly those messages at the RECIPIENT's conversation timer at read time and start counting them down,
 * silently deleting messages the sender said to keep. Only a `null` - the sender advertised nothing, i.e. a pre-v6.4.3-38
 * client - may consult local state. See [DisappearingFreezeDecision] for the tri-state.
 */
object FirstReadDecision {

    /**
     * The countdown to persist alongside the read state, or `null` when reading this message starts nothing.
     *
     * @param existingStart the countdown start ALREADY on the row. A non-null value means a countdown is running, and
     *   first-read must not restart it.
     * @param frozenTimerSeconds the per-message timer frozen from the sender's metadata: `null` = the sender advertised
     *   nothing, `0` = the sender explicitly said OFF, `> 0` = the sender's timer.
     * @param conversationTimerSeconds the local shared conversation timer, consulted ONLY when the sender advertised
     *   nothing.
     */
    @JvmStatic
    fun countdownAtFirstRead(
        isOutbox: Boolean,
        isDisappearingStatus: Boolean,
        existingStart: Long?,
        frozenTimerSeconds: Int?,
        conversationTimerSeconds: Int?,
        readAtMillis: Long,
    ): Countdown? {
        if (isOutbox || isDisappearingStatus || existingStart != null) {
            return null
        }
        val timerSeconds = frozenTimerSeconds ?: conversationTimerSeconds ?: return null
        if (timerSeconds <= 0) {
            return null
        }
        return Countdown(
            timerSeconds = timerSeconds,
            startedAt = readAtMillis,
            expiresAt = readAtMillis + timerSeconds * 1000L,
        )
    }

    /** The three values a started countdown writes, together, because they are one fact. */
    data class Countdown(
        @JvmField val timerSeconds: Int,
        @JvmField val startedAt: Long,
        @JvmField val expiresAt: Long,
    )
}
