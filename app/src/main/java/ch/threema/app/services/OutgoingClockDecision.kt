package ch.threema.app.services

import ch.threema.storage.models.MessageState

/**
 * F1Whisper (fourth fork review, F4-04): the single boundary at which an outgoing message's disappearing countdown may start.
 *
 * The defect this exists to remove: the clock was armed at three unrelated lifecycle points and missing from several others.
 *
 *  - **Too early.** `sendText` and `sendLocation` armed it immediately after `saveLocalModel`, BEFORE the receiver had scheduled the
 *    persistent send task. Local tasks only execute while a server connection is available, so a 30-second message composed offline
 *    began counting down at once, expiry hard-deleted the row, and the archived task then found nothing to load. The message
 *    disappeared from the sender without ever reaching the recipient - the timer destroyed the payload it was supposed to govern.
 *  - **Never.** A media send that failed and was retried successfully went through `resendFileMessage`, which has no arming call, and
 *    polls went through `sendBallotMessage`/`resendBallotMessage`, which have none either. Those messages were advertised to the
 *    recipient as disappearing and then kept on the sender indefinitely.
 *
 * The rule that replaces all of it: **a countdown starts when the message has left the device, and at no other moment.** That is
 * exactly what the outgoing state says, so the state is what decides. Every content path - text, location, new media, retried media,
 * polls, and anything added later - converges on it, because every one of them has to report a send state to show the user a tick.
 *
 * [MessageState.DELIVERED] and [MessageState.READ] count too. They are not the normal entry point, but a message can reach them
 * without this device ever having observed [MessageState.SENT] (a state update reflected from another device, or a receipt that
 * overtakes the send confirmation), and each of them is proof that the message left. Arming is idempotent, so treating all three as
 * the boundary costs nothing and closes the gap.
 *
 * Everything before those states is deliberately excluded. `PENDING`, `TRANSCODING`, `UPLOADING` and `SENDING` all describe a message
 * whose only copy is still on this device, and `SENDFAILED` describes one that never left: starting a countdown in any of them is how
 * the payload got deleted from under the send task in the first place.
 *
 * No Android imports, so the rule is unit-testable without a device.
 */
object OutgoingClockDecision {
    /**
     * Whether reaching [state] proves the outgoing message has left this device, and its disappearing countdown may therefore start.
     */
    @JvmStatic
    fun hasLeftTheDevice(state: MessageState?): Boolean =
        when (state) {
            MessageState.SENT,
            MessageState.DELIVERED,
            MessageState.READ,
            -> true

            else -> false
        }

    /**
     * F1Whisper (fifth fork review, F5-06): the countdown start to persist for this transition, or `null` to leave the
     * one on the row exactly as it is.
     *
     * Two things were wrong before, and both are fixed by deriving the start from the TRANSITION's own timestamp instead
     * of from `System.currentTimeMillis()` at the moment the arming code happened to run:
     *
     *  - a `SENT` update reflected from another device carries an authoritative send time, which was discarded. The local
     *    device started a full interval from the moment it processed the reflection, so a reflection delayed by minutes
     *    extended the message's life by those minutes;
     *  - state and clock were two separate writes, and the second one re-read the wall clock, so even the local path
     *    stamped a start slightly later than the transition it was recording.
     *
     * Authority. Only [MessageState.SENT] carries an authoritative send timestamp. `DELIVERED` and `READ` prove the
     * message left, but their timestamps are when the RECEIPT was observed, which is necessarily later; a receipt that
     * overtakes the send confirmation may therefore start the clock PROVISIONALLY, and a later-observed authoritative
     * send time may move that start EARLIER. Nothing may move it later - not a duplicate, not a receipt, not a
     * re-delivery - because every such move would silently extend a retention window the sender already committed to.
     *
     * F1Whisper (sixth fork review, F6-03): departure and authority are two different questions about the same
     * transition, and asking one of them with the other's answer is what made the correction above unreachable.
     *
     *  - **Departure** is a property of the row after this write: [persistedState]. A transition the display-state gate
     *    refuses changes nothing about whether the message left, so the row's own state answers it.
     *  - **Authority** is a property of the event being processed: [transitionState]. Only `SENT` carries a real send
     *    time. A `SENT` refused as a display-state downgrade behind `DELIVERED` or `READ` is still an authoritative send
     *    time, and is precisely the case this exists for.
     *
     * Passing the resulting display state for both meant a `DELIVERED(t2)` followed by `SENT(t1)` saw `DELIVERED` twice,
     * never took the `SENT` branch, and left the message expiring at `t2 + timer` - longer than the sender's interval by
     * the whole acknowledgement or reordering delay.
     *
     * @param transitionState    the state being recorded by this transition.
     * @param persistedState     the state the row will carry after it, which may be higher (a refused downgrade).
     * @param transitionAtMillis the timestamp of the transition being recorded, NOT "now".
     * @param currentStartMillis the countdown start already on the row, if any.
     */
    @JvmStatic
    fun resolveStart(
        transitionState: MessageState?,
        persistedState: MessageState?,
        transitionAtMillis: Long,
        currentStartMillis: Long?,
    ): Long? {
        if (!hasLeftTheDevice(persistedState) || !hasLeftTheDevice(transitionState)) {
            return null
        }
        if (currentStartMillis == null) {
            return transitionAtMillis
        }
        if (transitionState == MessageState.SENT && transitionAtMillis < currentStartMillis) {
            // An authoritative send time observed after a provisional start. Shortening is always safe; the message left
            // the device then, whatever this device learned first.
            return transitionAtMillis
        }
        return null
    }

    /**
     * The deadline for a countdown starting at [startMillis] under [timerSeconds], or `null` when the message carries no
     * timer and therefore no deadline.
     */
    @JvmStatic
    fun deadlineFor(startMillis: Long, timerSeconds: Int?): Long? {
        if (timerSeconds == null || timerSeconds <= 0) {
            return null
        }
        return startMillis + timerSeconds * 1000L
    }
}
