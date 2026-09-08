package ch.threema.app.tasks

import ch.threema.app.services.monotonicMillis

/**
 * F1Whisper: how long a queued typing indicator is still worth sending.
 *
 * Both outgoing typing tasks stamp their message when they EXECUTE, not when they were scheduled, so a backlog drained
 * after a reconnect tells the peer that someone is composing right now, minutes after they stopped. That backlog is
 * also expensive rather than merely wrong: every queued indicator is still wrapped in Forward Security on the way out,
 * and typing indicators were 54% of all FS-encapsulated sends in 13 days of device logs, each one burning a ratchet
 * counter for a state that no longer exists.
 *
 * Five seconds is half the sender's own resend interval: an indicator that missed its own heartbeat carries nothing
 * the next one will not carry sooner.
 */
internal const val MAX_TYPING_TASK_AGE_MS = 5_000L

/**
 * Whether a typing indicator scheduled at [scheduledAtMillis] has waited too long to still be true.
 *
 * Both timestamps come from the monotonic clock [monotonicMillis] reads, so a clock correction between scheduling and
 * execution cannot make a fresh indicator look ancient or an ancient one look fresh.
 */
internal fun isStaleTypingIndicator(
    scheduledAtMillis: Long,
    nowMillis: Long = monotonicMillis(),
): Boolean = nowMillis - scheduledAtMillis > MAX_TYPING_TASK_AGE_MS
