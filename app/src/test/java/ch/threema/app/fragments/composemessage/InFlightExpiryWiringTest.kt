package ch.threema.app.fragments.composemessage

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper (twelfth fork review, F12-04): structural pins on `ComposeMessageFragment`'s share/quote wiring - the
 * production surfaces that consume [PendingMediaShare] and [MessageExpiryWatch], whose behaviour is executed in
 * [PendingMediaShareTest] and [MessageExpiryWatchTest]. The fragment itself cannot be instantiated on the JVM, so
 * each wiring fact is pinned at source, provable red by reverting the line it names.
 *
 * **The defect.** The share and quote pipelines checked expiry exactly once, at selection time. The decrypt
 * callbacks, the caption-dialog confirm (`onYes` -> chooser + URI grant), and the 150-550 ms delayed quote-popup
 * runnable never revalidated; the decrypted share copies in the cache directory were unknown to expiry's durable
 * deletion; and `onRemoved` only removed the adapter row - no share cancellation, no dialog dismissal (shown with a
 * null tag, it could not even be found), no quote dismissal, no temp cleanup.
 */
class InFlightExpiryWiringTest {

    private val fragment = File("src/main/java/ch/threema/app/fragments/composemessage/ComposeMessageFragment.java")

    // -----------------------------------------------------------------------------------------------------------------
    // 1. The decrypt callbacks revalidate at their boundary
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the single-share decrypt callback revalidates before any dialog or export, deleting the expired copy`() {
        val share = bodyOf(fragment.readText(), "private void shareMessages(")
        val revalidate = share.indexOf("DisappearingMessageService.enforceIfExpired(messageModel)")
        assertTrue(revalidate >= 0, "the single-share decrypt callback must re-ask the authority")

        val deleteExpired = share.indexOf("decryptedFile.delete()")
        val captionDialog = share.indexOf("showShareCaptionDialog(")
        val textShare = share.indexOf("shareTextMessage(")
        assertTrue(deleteExpired >= 0, "the expired branch must delete the decrypted copy it was just handed")
        assertTrue(captionDialog >= 0 && textShare >= 0, "both export routes must exist")
        assertTrue(
            revalidate < deleteExpired && deleteExpired < captionDialog && captionDialog < textShare,
            "the revalidation and the expired-copy deletion come BEFORE either export route - the decision to " +
                "abort must precede any dialog or chooser",
        )
    }

    @Test
    fun `the multi-share decrypt callback revalidates positionally and hands off only survivors`() {
        val share = bodyOf(fragment.readText(), "private void shareMessages(")
        val build = share.indexOf("PendingMediaShare.of(")
        val revalidate = share.indexOf("revalidateForExport()")
        val export = share.indexOf("messageService.shareMediaMessages(")
        val discard = share.indexOf("discardAfterHandoff()")
        assertTrue(build >= 0, "the multi callback must build the share from the positional model/URI lists")
        assertTrue(
            build < revalidate && revalidate < export && export < discard,
            "build -> revalidate -> export -> discard: expired members drop (copies deleted) before the chooser, " +
                "and the discard after a successful handoff never deletes",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 2. The caption dialog: recorded pending share, real tag, deadline watch; the confirm revalidates
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the caption dialog is shown over the recorded share, with a real tag and a deadline watch`() {
        val show = bodyOf(fragment.readText(), "private void showShareCaptionDialog(")
        assertTrue(
            show.contains("show(getParentFragmentManager(), DIALOG_TAG_SHARE_CAPTION)"),
            "the dialog must carry a real tag - with the null tag it could never be found to dismiss on expiry " +
                "or removal",
        )
        assertFalse(
            show.contains("show(getParentFragmentManager(), null)"),
            "the null-tag show is the pre-F12-04 defect",
        )
        assertTrue(
            show.contains("pendingShareExpiryWatch.watch("),
            "the open dialog holds an exportable decrypted copy; its deadline must be watched",
        )
    }

    @Test
    fun `the confirm exports the revalidated pending share, never the dialog payload or the live selection`() {
        val onYes = bodyOf(fragment.readText(), "public void onYes(String tag, Object data, String text)")
        assertFalse(
            onYes.contains("(List<Uri>) data"),
            "reading the URIs back out of the dialog payload is the pre-F12-04 defect - it bypasses every " +
                "revalidation",
        )
        assertFalse(
            onYes.contains("selectedMessages"),
            "the confirm must export the recorded pending share, not whatever the selection list holds by now",
        )
        val revalidate = onYes.indexOf("revalidateForExport()")
        val export = onYes.indexOf("messageService.shareMediaMessages(")
        val discard = onYes.indexOf("discardAfterHandoff()")
        assertTrue(
            revalidate >= 0 && revalidate < export && export < discard,
            "revalidate -> export -> discard: expired between dialog open and confirm means nothing leaves the " +
                "device, and a successful handoff is discarded without deletion",
        )
    }

    @Test
    fun `the dialog's cancel and the deadline watch both abandon the share with cleanup`() {
        val source = fragment.readText()
        val onNo = bodyOf(source, "public void onNo(String tag)")
        assertTrue(
            onNo.contains("DIALOG_TAG_SHARE_CAPTION") && onNo.contains("cancelPendingShare()"),
            "cancelling the caption dialog must delete the decrypted copy, not leave it in the cache directory",
        )

        val deadline = bodyOf(source, "private void onPendingShareDeadline(")
        assertTrue(
            deadline.contains("cancelPendingShare()") &&
                deadline.contains("DialogUtil.dismissDialog(getParentFragmentManager(), DIALOG_TAG_SHARE_CAPTION"),
            "at the deadline the dialog is dismissed AND the copy deleted, without waiting for durable deletion",
        )

        val cancel = bodyOf(source, "private void cancelPendingShare(")
        assertTrue(
            cancel.contains("pendingShareExpiryWatch.cancel()") && cancel.contains("cancelAndCleanup()"),
            "abandoning the share disarms the watch and deletes every decrypted copy",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 3. The quote popup: the delayed runnable revalidates, the open popup is watched, dismissal disarms
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the delayed quote runnable refuses expired content and watches the popup it opens`() {
        val quote = bodyOf(fragment.readText(), "private void showQuotePopup(")
        val revalidate = quote.indexOf("DisappearingMessageService.enforceIfExpired(quotedMessageModel)")
        val show = quote.indexOf("quotePopup.show(")
        val watchArm = quote.indexOf("quotePopupExpiryWatch.watch(quotedMessageModel")
        assertTrue(
            revalidate >= 0 && revalidate < show,
            "the 150-550 ms delayed runnable must re-ask the authority BEFORE showing - the composer must not " +
                "(re)acquire content whose deadline passed in between",
        )
        assertTrue(
            watchArm > show,
            "once shown, the popup holds the quoted content and must be watched so it dies at the deadline",
        )
    }

    @Test
    fun `dismissing the quote popup disarms its deadline watch`() {
        val dismiss = bodyOf(fragment.readText(), "private void dismissQuotePopup(@Nullable Runnable")
        assertTrue(dismiss.contains("quotePopupExpiryWatch.cancel()"))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 4. Removal kills the in-flight operations, not just the adapter row
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `both removal listeners abort the removed message's in-flight operations`() {
        val source = fragment.readText()
        assertTrue(
            bodyOf(source, "public void onRemoved(final AbstractMessageModel removedMessageModel)")
                .contains("abortInFlightOperationsFor("),
            "single removal must abort in-flight share/quote of the removed message",
        )
        assertTrue(
            bodyOf(source, "public void onRemoved(List<AbstractMessageModel> removedMessageModels)")
                .contains("abortInFlightOperationsFor("),
            "bulk removal must abort them for every removed message",
        )

        val abort = bodyOf(source, "private void abortInFlightOperationsFor(")
        assertTrue(
            abort.contains("share.contains(") &&
                abort.contains("cancelPendingShare()") &&
                abort.contains("DIALOG_TAG_SHARE_CAPTION"),
            "a removed member kills the pending share: copies deleted, caption dialog dismissed",
        )
        assertTrue(
            abort.contains("PendingMediaShare.isSameMessage(") && abort.contains("dismissQuotePopup()"),
            "a quote popup quoting the removed message is dismissed - matched by stable identity, never instance",
        )
    }

    /** The body of the declaration starting with [signature], by brace matching from the first `{` after it. */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "signature not found: $signature" }
        var index = source.indexOf('{', start)
        require(index >= 0) { "no body for: $signature" }
        var depth = 0
        val body = StringBuilder()
        while (index < source.length) {
            val c = source[index]
            body.append(c)
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) break
            }
            index++
        }
        return body.toString()
    }
}
