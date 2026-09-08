package ch.threema.app.services

/**
 * F1Whisper (twelfth fork review, F12-02): the durable outcome of a consuming media-metadata write
 * ([MessageService.consumeAndUpdateMediaMetadata]).
 *
 * The write used to answer a bare boolean, and `false` conflated five different situations: a row that is gone, a row
 * that cannot be read (database error), a mutation that threw, a conditional update that lost every retry, and a row
 * that is simply already terminal. The listen-once burn cleared its replay barrier on any non-throwing return, so a
 * recording whose consumed-metadata write actually FAILED became replayable again after audible playback. The caller
 * deciding whether a settlement is durable needs the outcomes distinguished, so the write now reports which one
 * happened instead of collapsing them.
 */
enum class MediaConsumeOutcome {
    /** The conditional write applied: the row now durably carries the consumed state and mutated metadata. */
    APPLIED,

    /**
     * The row exists and already carries the terminal state, so there was nothing to write. The caller's instance has
     * been reconciled to the persisted body, so admission decisions made from it agree with the row.
     */
    ALREADY_TERMINAL,

    /** The row is confirmed absent (hard-removed or never persisted) or tombstoned by a delete-for-everyone. */
    ROW_GONE,

    /**
     * Nothing durable is known: the row was unreadable, the mutation threw, or the conditional write lost every
     * retry. The terminal state may or may not be on disk; the caller must not treat the settlement as durable.
     */
    INDETERMINATE,
}
