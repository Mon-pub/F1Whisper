package ch.threema.app.fragments.composemessage

import ch.threema.storage.models.AbstractMessageModel

sealed interface ComposeMessageEvent {

    data class NextRecordsLoaded(
        @JvmField val messageModels: List<AbstractMessageModel>,
        @JvmField val hasMoreRecords: Boolean,
        // F1Whisper (second follow-up S2-05): the PageRequestGuard token captured at dispatch -
        // the fragment drops results whose generation is stale (cursor was reset in between).
        @JvmField val generation: Int,
    ) : ComposeMessageEvent

    /**
     * F1Whisper (fifth fork review, F5-10): a page load that ENDED WITHOUT ROWS.
     *
     * The fork acquires a single-load slot from `PageRequestGuard` before dispatching a refresh, and releases it only
     * from the success event. The query coroutine emitted nothing at all when it threw, so on a failure that left the
     * process alive the slot stayed owned: the refresh indicator kept spinning and every later page request was rejected
     * until the conversation was reset. The slot is fork-exclusive even though the coroutine predates the fork.
     *
     * So the load now has exactly two terminal results, both carrying the generation that acquired the slot, and the
     * fragment releases that generation from both.
     */
    data class NextRecordsFailed(
        @JvmField val generation: Int,
    ) : ComposeMessageEvent
}
