package ch.threema.app.utils

import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageType

/**
 * F1Whisper (fourth fork review, F4-09): the one adapter from a message model to
 * [OutputRestrictionPolicy.mayReleaseMediaToGenericOutput].
 *
 * [OutputRestrictionPolicy] takes primitives so it stays pure and JVM-testable. Five boundaries need the same three fields
 * pulled off a model - the gallery, the generic media viewer, save, share and chat export - and five copies of that
 * extraction is five chances to read one of them wrongly. So it is written once, here.
 *
 * Fails closed, like the policy it delegates to: a model that cannot be read is treated as restricted. The cost of that is
 * one absent item in a gallery or an export; the cost of the other default is a permanent clear copy of a message the
 * sender said could be heard once.
 */
object RestrictedMediaOutput {
    /**
     * Whether [model]'s decrypted media may cross a generic output boundary.
     */
    @JvmStatic
    fun mayRelease(model: AbstractMessageModel?): Boolean {
        if (model == null) {
            return false
        }
        return try {
            val isFileMessage = model.type == MessageType.FILE
            OutputRestrictionPolicy.mayReleaseMediaToGenericOutput(
                isOutbox = model.isOutbox,
                isFileMessage = isFileMessage,
                isListenOnce = isFileMessage && model.fileData?.isListenOnce == true,
            )
        } catch (e: Exception) {
            false
        }
    }

    /** The inverse of [mayRelease], for call sites that read better as a rejection. */
    @JvmStatic
    fun isRestricted(model: AbstractMessageModel?): Boolean = !mayRelease(model)

    /** [models] with every restricted entry removed, preserving order. */
    @JvmStatic
    fun releasable(models: List<AbstractMessageModel>): List<AbstractMessageModel> = models.filter(::mayRelease)

    /**
     * F1Whisper (fifth fork review, F5-01): reduce a restricted message to a SPENT PLACEHOLDER, for the two archive
     * boundaries.
     *
     * A data backup is an output boundary that F4-09 did not reach, and the leak it left had two halves. The media file
     * itself went into the archive through `getDecryptedMessageStream`, and the CSV preserved the message body verbatim -
     * which for a FILE message is the serialised file-data metadata, including the blob id, the blob encryption key and
     * an unclaimed `lo=true`. Either half alone is enough: back up an unclaimed listen-once voice message, restore it and
     * play it, then restore the same archive again and play it again, or fetch the blob afresh with the credentials the
     * CSV kept.
     *
     * Withholding the file without stripping the body would therefore fix nothing, and stripping the body without
     * withholding the file would fix nothing either. This does both, at the point the archive entry is produced: no blob
     * credentials, not downloaded, and claimed AND consumed so the restored bubble renders as an already-heard message
     * rather than an unopened one.
     *
     * Applied on restore as well, for archives created before this existed, whose payload is already on disk somewhere -
     * a spent placeholder is the most that may be reconstructed from those, and no media is written for them.
     *
     * @return whether [model] was changed, i.e. whether it was a restricted message at all.
     */
    @JvmStatic
    fun spendForArchive(model: AbstractMessageModel?): Boolean {
        if (model == null || mayRelease(model)) {
            return false
        }
        return try {
            val fileData = model.fileData ?: return false
            fileData.setBlobId(null)
            fileData.setEncryptionKey(null)
            fileData.isDownloaded(false)
            fileData.setListenOnceClaimed()
            fileData.setListenOnceConsumed()
            model.fileData = fileData
            true
        } catch (e: Exception) {
            false
        }
    }
}
