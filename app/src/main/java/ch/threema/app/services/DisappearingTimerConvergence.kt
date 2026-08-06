package ch.threema.app.services

/**
 * F1Whisper: the whole 1:1 disappearing-timer convergence decision, in one pure place.
 *
 * A 1:1 conversation has ONE shared timer — [ch.threema.storage.models.ContactModel.disappearingMessagesTimerSeconds] —
 * that governs BOTH the outgoing and the incoming freeze, and an incoming `0x85` overwrites it **unconditionally**, OFF
 * included. Pure last-writer-wins, byte-for-byte the model `IncomingGroupDisappearingTimerTask` has used for groups since
 * v6.4.3-29. [ch.threema.storage.models.ContactModel.peerDisappearingTimerSeconds] is now dead for 1:1 too: no code reads
 * or writes it.
 *
 * [changesSharedTimer] is therefore NOT an adopt gate. It answers a different question — "did this conversation's timer
 * actually move?" — and gates only the status row, so a redelivered or re-asserted advertisement that lands on the value
 * already in force is applied silently instead of printing a row out of nowhere.
 *
 * **Why there is no adopt gate.** An earlier build gated the adopt on "does this differ from what the peer itself last
 * advertised?", to shield an updated client from an un-updated v6.4.3-37 peer's 5-minute re-assert. On device that gate
 * dropped real changes (`/tmp/lww/p1` `debug_log.txt:2579`): a peer's state changes **without advertising** whenever it
 * ADOPTS ours, so "equal to their last advertisement" stopped implying "no change on their side". p2 genuinely went
 * 30 → OFF, but its `0` coincided with the `0` it had advertised two minutes earlier and was ignored. Telling a stale
 * re-assert from a genuine change needs a logical clock, and the `0x85` body is a frozen 4-byte LE int. The residual —
 * an un-updated peer re-injecting its value until everyone updates — is the same one groups already accept, and is what
 * the server-side mandatory-update floor exists to close.
 *
 * This object has NO Android imports so the decision is directly JVM-testable
 * (see `DisappearingTimerConvergenceTest` / `DisappearingTimerConvergenceScenarioTest`), following the same pattern as
 * `DatabaseUpdateToVersion125.planMigration` and `VoipCallLifecycleGate`.
 *
 * Design and rationale: `.claude/tasks/disappearing-timer-1to1-shared-lww.md`.
 */
object DisappearingTimerConvergence {

    /**
     * Normalise an advertised wire value into the shared conversation-field encoding, where `null` means OFF
     * (the encoding the picker already renders via `DisappearingMessageUtil.indexForSeconds`).
     */
    @JvmStatic
    fun toSharedField(timerSeconds: Int): Int? = if (timerSeconds > 0) timerSeconds else null

    /**
     * Whether applying [advertised] actually changes the conversation's shared timer ([currentShared]). Gates the status
     * row only — never the adopt, which is unconditional.
     *
     * False covers both an at-least-once redelivery of a control message already applied and an un-updated peer's
     * re-assert of the value in force; adopting either is a no-op, so the only thing worth suppressing is the row.
     */
    @JvmStatic
    fun changesSharedTimer(currentShared: Int?, advertised: Int): Boolean =
        governingTimerSeconds(currentShared) != toSharedField(advertised)

    /**
     * The one timer that governs BOTH freeze directions for a 1:1 conversation, read from the shared field.
     * Returns `null` when the conversation timer is off.
     */
    @JvmStatic
    fun governingTimerSeconds(sharedField: Int?): Int? = sharedField?.takeIf { it > 0 }
}
