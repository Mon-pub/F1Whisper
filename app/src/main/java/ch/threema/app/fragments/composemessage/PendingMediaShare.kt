package ch.threema.app.fragments.composemessage

import android.net.Uri
import ch.threema.base.utils.getThreemaLogger
import ch.threema.storage.models.AbstractMessageModel
import java.io.File
import java.util.function.Consumer
import java.util.function.Predicate

private val logger = getThreemaLogger("PendingMediaShare")

/**
 * F1Whisper (twelfth fork review, F12-04): a share operation in flight - the selected message models, the share URIs
 * handed out for them, and the decrypted temp copies backing those URIs - held so the operation can be revalidated at
 * every later async boundary and killed, copies deleted, when a member expires or is removed before the export
 * happens.
 *
 * **The defect.** The share pipeline checked expiry exactly once, at selection time. The decrypt callbacks, the
 * caption-dialog confirm and the chooser launch all ran later - seconds later with a dialog open - and never asked
 * again. A message whose deadline passed mid-flight was still exported (chooser + URI grant), and its decrypted
 * plaintext copy in the app's cache directory (a file expiry's durable deletion does not know about) survived
 * indefinitely.
 *
 * The rule: an expired or removed member is dropped from the export and its decrypted copy is deleted - via
 * [File.delete] where the temp file is known (the single-share path holds it), else through the injected [uriDeleter]
 * (a `ContentResolver.delete` on the FileProvider URI, which removes the underlying file). After a SUCCESSFUL chooser
 * handoff nothing is ever deleted ([discardAfterHandoff]): the receiving app still reads those URIs.
 *
 * The expiry authority and the URI deletion are injected so the class is executable on the JVM;
 * `ComposeMessageFragment` passes `DisappearingMessageService::enforceIfExpired` (which also re-enqueues the
 * conditional durable removal) and a resolver-backed deleter.
 */
class PendingMediaShare(
    members: List<Member>,
    private val expiryCheck: Predicate<AbstractMessageModel>,
    private val uriDeleter: Consumer<Uri>,
) {

    /**
     * One shared message: its model, the share URI exported for it (`null` when decryption failed - kept as a
     * positional placeholder, exactly as the decrypt callback delivered it), and the decrypted temp file where the
     * caller knows it (single-share path); `null` where only the URI is known.
     */
    class Member(
        @JvmField val model: AbstractMessageModel,
        @JvmField val uri: Uri?,
        @JvmField val tempFile: File?,
    )

    private val members: MutableList<Member> = members.toMutableList()

    companion object {
        /**
         * Builds a share from positionally aligned model and URI lists (the contract of
         * `FileService.loadDecryptedMessageFiles`: one URI per model, `null` placeholders included). A size
         * mismatch keeps the aligned prefix - a misaligned pair must never be exported.
         */
        @JvmStatic
        fun of(
            models: List<AbstractMessageModel>,
            uris: List<Uri?>,
            expiryCheck: Predicate<AbstractMessageModel>,
            uriDeleter: Consumer<Uri>,
        ): PendingMediaShare {
            val paired = minOf(models.size, uris.size)
            if (paired != models.size || paired != uris.size) {
                logger.warn("Share models ({}) and URIs ({}) are misaligned; keeping the aligned prefix", models.size, uris.size)
            }
            return PendingMediaShare(
                (0 until paired).map { Member(models[it], uris[it], null) },
                expiryCheck,
                uriDeleter,
            )
        }

        /**
         * Whether two models name the same persisted message: same concrete subtype (the tables are separate and
         * their integer ids collide across them - the F12-01 lesson) and same UID where both carry one, falling
         * back to the row id for uid-less models. Never instance identity - listener callbacks deliver fresh
         * instances of the same row.
         */
        @JvmStatic
        fun isSameMessage(a: AbstractMessageModel, b: AbstractMessageModel): Boolean {
            if (a.javaClass != b.javaClass) {
                return false
            }
            val aUid = a.uid
            val bUid = b.uid
            if (!aUid.isNullOrEmpty() && !bUid.isNullOrEmpty()) {
                return aUid == bUid
            }
            return a.id > 0 && a.id == b.id
        }
    }

    /**
     * Re-asks the expiry authority for every member; an expired member is dropped and its decrypted copy deleted.
     * Returns whether anything exportable remains (at least one surviving member with a URI). A `false` means the
     * whole operation must be abandoned - and by then every expired member's copy is already gone.
     */
    @Synchronized
    fun revalidateForExport(): Boolean {
        val iterator = members.iterator()
        while (iterator.hasNext()) {
            val member = iterator.next()
            if (expiryCheck.test(member.model)) {
                logger.info("Share member {} expired mid-flight; dropping it and deleting its decrypted copy", member.model.id)
                deleteDecryptedCopy(member)
                iterator.remove()
            }
        }
        return members.any { it.uri != null }
    }

    /** Abandons the share: every remaining member's decrypted copy is deleted and the share empties. */
    @Synchronized
    fun cancelAndCleanup() {
        for (member in members) {
            deleteDecryptedCopy(member)
        }
        members.clear()
    }

    /**
     * Empties the share after a successful chooser handoff WITHOUT deleting anything: the receiving app still
     * reads the granted URIs. A later [cancelAndCleanup] therefore has nothing left to delete.
     */
    @Synchronized
    fun discardAfterHandoff() {
        members.clear()
    }

    /** Whether the share still holds the given message (stable identity, see [isSameMessage]). */
    @Synchronized
    fun contains(model: AbstractMessageModel): Boolean = members.any { isSameMessage(it.model, model) }

    @Synchronized
    fun isEmpty(): Boolean = members.isEmpty()

    /** The surviving models, positionally aligned with [uris] (the `ArrayList` shape the share intent API takes). */
    @Synchronized
    fun models(): ArrayList<AbstractMessageModel> = members.mapTo(ArrayList()) { it.model }

    /** The surviving share URIs, positionally aligned with [models], `null` placeholders preserved. */
    @Synchronized
    fun uris(): ArrayList<Uri?> = members.mapTo(ArrayList()) { it.uri }

    private fun deleteDecryptedCopy(member: Member) {
        val tempFile = member.tempFile
        if (tempFile != null) {
            if (!tempFile.delete() && tempFile.exists()) {
                logger.warn("Could not delete the decrypted copy of share member {}", member.model.id)
            }
            return
        }
        val uri = member.uri ?: return
        try {
            uriDeleter.accept(uri)
        } catch (e: Exception) {
            logger.warn("Could not delete the decrypted copy behind an expired share URI", e)
        }
    }
}
