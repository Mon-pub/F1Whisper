package ch.threema.app.services

/**
 * F1Whisper: the whole INCOMING disappearing-timer freeze decision, in one pure place.
 *
 * A message expires according to the timer **the sender advertised for that specific message**, carried inside the
 * end-to-end-encrypted `MessageMetadata` box (`f1_disappearing_timer`, Signal's `DataMessage.expireTimer` equivalent), and
 * never according to anything the recipient does before or after receiving it. This object turns the sender's advertisement
 * plus the recipient's local shared field into the single value to freeze.
 *
 * ## The tri-state, and why it is load-bearing
 *
 * [resolveIncomingTimer] returns a nullable `Int` whose three shapes are three genuinely different facts:
 *
 * | Result | Fact | What the caller must do |
 * | --- | --- | --- |
 * | `null` | nothing to store | fall through to whatever the model already has |
 * | `0` | **the sender explicitly said OFF** | store `0`, never expire, and **never fall back** to anything local |
 * | `> 0` | the sender's timer for this message | freeze at exactly this value |
 *
 * The distinction between *"the sender said OFF"* (`0`) and *"the sender said nothing"* (`null`) is the hinge of the whole
 * design. They look alike — both end with a message that does not disappear — but they are opposite statements about
 * **whose** policy is in force, and they must therefore behave differently on every later read of the model:
 *
 * - `0` is a **positive assertion by the sender**. The sender's own copy of this message will never expire either, so the
 *   two sides agree, and no later local change may revisit that.
 * - `null` means the peer transmitted no timer at all — it is a pre-v6.4.3-38 client, or a path that carries no metadata.
 *   Only then may the recipient legitimately substitute its own conversation setting, which is exactly today's behaviour
 *   and the reason disappearing messages keep working against every shipped v6.4.3-26..-37 peer.
 *
 * **What breaks if `0` and `null` are collapsed.** `MessageServiceImpl.markAsRead` starts the countdown at first read and
 * falls back to the conversation's shared timer when the message carries no frozen timer. Collapse `0` into `null` and that
 * fallback fires on an explicitly-OFF message: at read time it is re-frozen at the **recipient's** local timer and starts
 * counting down, silently deleting a message the sender said should be kept. That is a mirror of the very defect this wave
 * exists to remove (a recipient's local setting overriding the sender's stated per-message policy), just arriving at read
 * time instead of receive time — and every test that does not exercise the read path still passes. Hence the fallback
 * condition in `markAsRead` must test `timerSecs == null`, never `timerSecs <= 0`.
 *
 * This object has NO Android imports so the decision is directly JVM-testable (see `DisappearingFreezeDecisionTest`),
 * following the same pattern as [DisappearingTimerConvergence], `DatabaseUpdateToVersion125.planMigration` and
 * `VoipCallLifecycleGate`.
 *
 * Design and rationale: `.claude/tasks/disappearing-per-message-timer-metadata.md`.
 */
object DisappearingFreezeDecision {

    /**
     * Upper bound applied to any timer arriving from the network, in seconds. **365 days.**
     *
     * The value is a remote peer's choice, so it is hostile input and is clamped here — on the receive path, before
     * anything is persisted or handed to `AlarmManager`. The bound is chosen so that:
     *
     * - it is far above every value our own picker can produce (`DisappearingMessageUtil.DURATIONS_SECONDS` tops out at
     *   4 weeks = 2 419 200 s), leaving ~13x headroom for any future preset, so it can never clamp a legitimate timer;
     * - `MAX_TIMER_SECONDS * 1000L` is 3.1536e10 ms, nine orders of magnitude below `Long.MAX_VALUE`, so the
     *   `expiresAt = startedAt + timerSecs * 1000L` arithmetic in `DisappearingMessageService` cannot overflow;
     * - it stays inside `Int` even if some future caller multiplies it further (`31_536_000 * 60` is still < `Int.MAX_VALUE`);
     * - a one-year deadline is a finite, schedulable alarm. An unclamped `Int.MAX_VALUE` would be ~68 years, an alarm that
     *   outlives the installation and simply occupies the alarm table forever.
     */
    const val MAX_TIMER_SECONDS: Int = 365 * 24 * 60 * 60

    /**
     * Resolve the timer an incoming message must be frozen at.
     *
     * @param advertisedBySender the value the sender put in the encrypted metadata, or `null` when the peer transmitted no
     *   timer at all (a pre-v6.4.3-38 client, or a path with no metadata). Hostile values are clamped: negative is read as
     *   an explicit OFF, and anything above [MAX_TIMER_SECONDS] is capped.
     * @param localSharedField the recipient's own conversation timer, consulted **only** when the sender advertised
     *   nothing. Its `null`/`0`/negative encodings all mean "off", exactly as
     *   [DisappearingTimerConvergence.governingTimerSeconds] defines it.
     *
     * @return `null` to store nothing, `0` for an explicit sender OFF, or a positive number of seconds to freeze at. See
     *   the class KDoc — these three are not interchangeable.
     */
    @JvmStatic
    fun resolveIncomingTimer(advertisedBySender: Int?, localSharedField: Int?): Int? {
        if (advertisedBySender == null) {
            // The sender said nothing, so — and only so — the recipient's own conversation setting applies. Resolving to
            // "off" here yields `null`, not `0`: nothing was asserted by anyone, so the caller keeps falling through
            // (including at read time), which is precisely today's behaviour against every shipped client.
            return clampLocal(localSharedField)
        }
        return clampAdvertised(advertisedBySender)
    }

    /**
     * The outgoing counterpart: the value this device advertises for a message it is sending, derived from the timer already
     * frozen on that message's own model at compose time.
     *
     * Never returns `null`, because a device that understands the field asserts a policy for **every** message it sends —
     * `0` when its timer is off. That is what keeps *absent* unambiguously meaning "the peer is a pre-v6.4.3-38 client", which
     * is the whole basis of [resolveIncomingTimer]'s fallback. The "advertise nothing" case is not this function's to make: it
     * belongs to send paths that have no message model at all (delivery receipts, `0x85` itself, typing indicators), which
     * simply leave `AbstractMessage.disappearingTimerSeconds` null.
     *
     * @param frozenTimerSeconds the sending model's `disappearingTimerSeconds`, frozen at compose time by `createLocalModel`.
     */
    @JvmStatic
    fun advertisedTimer(frozenTimerSeconds: Int?): Int = clampAdvertised(frozenTimerSeconds ?: 0)

    /**
     * Clamp a sender-advertised value into `0` (explicit OFF) or `1..`[MAX_TIMER_SECONDS].
     * Never returns `null`: an advertisement present on the wire is always an assertion by the sender.
     */
    private fun clampAdvertised(advertised: Int): Int = when {
        advertised <= 0 -> 0
        advertised > MAX_TIMER_SECONDS -> MAX_TIMER_SECONDS
        else -> advertised
    }

    /**
     * Clamp the local shared field into `null` (off) or `1..`[MAX_TIMER_SECONDS]. The shared field can itself have been set
     * by a peer's `0x85`, so it gets the same upper bound; its off encoding stays `null` so the caller keeps falling through.
     */
    private fun clampLocal(localSharedField: Int?): Int? {
        val governing = DisappearingTimerConvergence.governingTimerSeconds(localSharedField) ?: return null
        return if (governing > MAX_TIMER_SECONDS) MAX_TIMER_SECONDS else governing
    }
}
