package ch.threema.app.services

/**
 * The countdown stamps a broken disappearing row should be given.
 *
 * @param expireStartedAt when the countdown should be considered to have begun.
 * @param expiresAt the derived hard-delete deadline.
 */
data class ExpiryRepair(val expireStartedAt: Long, val expiresAt: Long)

/**
 * F1Whisper: decides whether a disappearing-message row is in a state that can never reach its
 * deadline, and what stamps would fix it. Pure, no Android imports, JVM-testable.
 *
 * ## Why a repair pass exists at all
 *
 * Two rows shapes are invisible to the whole enforcement engine, because both the sweep
 * (`getMessagesExpiredBefore`) and the alarm (`getEarliestExpiry`) select on `expiresAtUtc IS NOT
 * NULL`. A row that will never have an `expiresAtUtc` is not "late", it is **gone from the engine's
 * view entirely**, and the only thing that can still rescue it is
 * [DisappearingMessageService.enforceIfExpired] happening to be called on it by a UI surface. If the
 * user never opens that chat again, the message the sender asked to have deleted is kept forever.
 *
 * - **Started but unstamped**: `expireStartedAt` set, `expiresAt` null. The deadline is fully
 *   derivable from the timer, so this is pure repair with no guessing.
 * - **Read but never started**: an incoming message that is `isRead` and carries a frozen timer but
 *   no `expireStartedAt`. This is the fallout of the non-atomic first-read transition that
 *   `MessageServiceImpl.markAsRead` used to perform: it wrote `read = true` in one row write and the
 *   countdown in a second one, so a process kill between them left exactly this shape - and
 *   `MessageUtil.canMarkAsRead` refuses to re-mark a read message, so nothing would ever start the
 *   countdown again. That write is now atomic, which stops NEW rows appearing in this shape; rows
 *   already on disk from before still need repairing, and that is what this case is for.
 *
 * ## What it deliberately does NOT repair
 *
 * - A timer of `0` or `null`. `0` is the sender's explicit "never expire"
 *   ([DisappearingFreezeDecision]'s tri-state) and inventing a countdown for it would delete
 *   messages the sender said to keep - the exact policy defeat the per-message-timer wave closed.
 * - An outgoing message with no `expireStartedAt`. The outgoing clock is armed by the send path
 *   after the handoff succeeds; a missing start means the message has not been handed off yet, so
 *   starting a countdown here would expire a draft that never left the device.
 * - An unread incoming message. Its countdown correctly starts at first read, and starting it early
 *   would delete a message before the recipient ever sees it.
 * - A row that already has both stamps. Consistent or not, re-deriving it here would fight
 *   [DisappearingMessageService.freezeIncomingTimer], which owns that correction and has the sender's
 *   advertised value to do it with.
 */
object ExpiryRepairDecision {

    /**
     * @param readAt when the message was read, epoch millis, or `null` if unknown. The countdown is
     *   restarted from this rather than from [nowMillis] wherever possible: `nowMillis` would hand
     *   the recipient a longer window than the sender allowed, by however long the row stayed broken
     *   - which for the crash case is "until the next reboot".
     * @return the stamps to write, or `null` if the row is not broken (or not repairable).
     */
    @JvmStatic
    fun repairFor(
        isOutbox: Boolean,
        isRead: Boolean,
        timerSeconds: Int?,
        expireStartedAt: Long?,
        expiresAt: Long?,
        readAt: Long?,
        nowMillis: Long,
    ): ExpiryRepair? {
        if (timerSeconds == null || timerSeconds <= 0) {
            return null
        }
        val timerMillis = timerSeconds.toLong() * 1000L

        if (expireStartedAt != null) {
            if (expiresAt != null) {
                return null
            }
            return ExpiryRepair(expireStartedAt, expireStartedAt + timerMillis)
        }

        if (isOutbox || !isRead) {
            return null
        }
        val startsAt = readAt ?: nowMillis
        return ExpiryRepair(startsAt, startsAt + timerMillis)
    }
}
