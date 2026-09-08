package ch.threema.app.services.messageplayer

import ch.threema.app.services.MediaConsumeOutcome

/**
 * F1Whisper (twelfth fork review, F12-02): whether a listen-once burn's settlement is durable enough to lower the
 * in-process replay barrier, decided from the write's discriminated outcome instead of from "the call did not throw".
 *
 * **The defect.** The burn cleared [ListenOnceBurnBarrier] on any non-throwing return of the consuming metadata
 * write - but `false` from that write also meant an unreadable row, a throwing mutation, or a conditional update that
 * lost every retry. A failed durable write combined with failed file removal left an unclaimed, unconsumed,
 * downloaded row with no owner and no barrier: the recording was audibly played and then playable again. And even a
 * SUCCESSFUL write cleared the barrier before the file removal ran, leaving a preemption window in which a stale
 * detached model could pass the durable gate while the decryptable file still existed.
 *
 * The rule, per outcome (the review's required correction, both disjuncts):
 * - [MediaConsumeOutcome.APPLIED] / [MediaConsumeOutcome.ALREADY_TERMINAL]: clear. The terminal state is durably on
 *   the row AND every admission-visible instance agrees with it (the write reconciles the caches and the caller's
 *   model; the terminal decline adopts the persisted body into the caller's model). The file removal has been
 *   attempted before the clear; a removal failure does not retain the barrier here, because the durable and visible
 *   refusal already stands and the repair burn knows how to finish "flags written, files still present".
 * - [MediaConsumeOutcome.ROW_GONE]: clear only when no decryptable media remains
 *   (`!FileService.hasPersistedMessageMedia` after the removal attempt). The row cannot refuse anything anymore, the
 *   player still holds an in-memory model, and a decryptable file would be exactly one admission away from replay.
 * - [MediaConsumeOutcome.INDETERMINATE]: retain, always. Nothing durable is known; the refusal path re-drives the
 *   burn, and that retry is what eventually settles the message. No removal is attempted in the failed pass either -
 *   the retry gets the clean write-then-remove sequence.
 */
object ListenOnceSettlementDecision {

    /**
     * Whether the settling pass attempts the media removal at all. An indeterminate write skips it: the settlement
     * failed, the retry will run the whole write-then-remove sequence, and deleting the media of a row whose durable
     * flags may never have been written would leave a "playable" row with no content instead of a settled one.
     */
    @JvmStatic
    fun attemptsFileRemoval(outcome: MediaConsumeOutcome): Boolean = outcome != MediaConsumeOutcome.INDETERMINATE

    /**
     * Whether the barrier comes down, given the write's [outcome] and whether the media is confirmed gone
     * ([mediaConfirmedGone] is meaningful only after the removal attempt; pass `false` when none ran).
     */
    @JvmStatic
    fun clearsBarrier(outcome: MediaConsumeOutcome, mediaConfirmedGone: Boolean): Boolean = when (outcome) {
        MediaConsumeOutcome.APPLIED, MediaConsumeOutcome.ALREADY_TERMINAL -> true
        MediaConsumeOutcome.ROW_GONE -> mediaConfirmedGone
        MediaConsumeOutcome.INDETERMINATE -> false
    }

    /**
     * Whether the settled pass marks the one-shot burn animation. Only a settlement whose row still exists has a
     * bubble to animate; a retained (indeterminate) settlement never reaches this question.
     */
    @JvmStatic
    fun marksBurnAnimation(outcome: MediaConsumeOutcome): Boolean =
        outcome == MediaConsumeOutcome.APPLIED || outcome == MediaConsumeOutcome.ALREADY_TERMINAL
}
