package ch.threema.app.services

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.WorkerThread
import ch.threema.app.BuildConfig
import ch.threema.app.cache.ThumbnailCache
import ch.threema.app.files.AppDirectoryProvider
import ch.threema.app.files.GroupProfilePictureFileHandleProvider
import ch.threema.app.files.MessageFileHandleProvider
import ch.threema.app.files.ProfilePictureFileHandleProvider
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.services.avatarcache.AvatarCacheService
import ch.threema.app.utils.AndroidContactUtil
import ch.threema.app.utils.BitmapUtil
import ch.threema.app.utils.ConfigUtils
import ch.threema.app.utils.FileProviderUtil
import ch.threema.app.utils.FileUtil
import ch.threema.app.utils.MimeUtil
import ch.threema.app.utils.ResettableInputStream
import ch.threema.app.utils.RestrictedMediaOutput
import ch.threema.app.utils.RingtoneChecker
import ch.threema.app.utils.RingtoneUtil
import ch.threema.base.utils.getThreemaLogger
import ch.threema.common.TimeProvider
import ch.threema.common.clearDirectoryNonRecursively
import ch.threema.common.clearDirectoryRecursively
import ch.threema.common.copyTo
import ch.threema.common.files.FileHandle
import ch.threema.common.getUniqueFile
import ch.threema.common.takeUnlessEmpty
import ch.threema.data.datatypes.GroupIdentity
import ch.threema.data.models.ContactModel
import ch.threema.domain.types.GroupDatabaseId
import ch.threema.domain.types.IdentityString
import ch.threema.domain.types.MessageUid
import ch.threema.storage.buildContentValues
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.getFileName
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant

private val logger = getThreemaLogger("FileServiceImpl")

class FileServiceImpl(
    private val appContext: Context,
    private val appDirectoryProvider: AppDirectoryProvider,
    private val preferenceService: PreferenceService,
    notificationPreferenceService: NotificationPreferenceService,
    private val avatarCacheService: AvatarCacheService,
    private val messageFileHandleProvider: MessageFileHandleProvider,
    private val profilePictureFileHandleProvider: ProfilePictureFileHandleProvider,
    private val groupProfilePictureFileHandleProvider: GroupProfilePictureFileHandleProvider,
    private val timeProvider: TimeProvider,
) : FileService {
    private val mediaPathPrefix
        get() = Environment.getExternalStorageDirectory().toString() + "/" + BuildConfig.MEDIA_PATH + "/"

    init {
        if (!ConfigUtils.supportsNotificationChannels()) {
            updateLegacyRingtoneIfInvalid(appContext.contentResolver, notificationPreferenceService)
        }
    }

    // TODO(ANDR-4800): Move this elsewhere
    private fun updateLegacyRingtoneIfInvalid(
        contentResolver: ContentResolver,
        notificationPreferenceService: NotificationPreferenceService,
    ) {
        val ringtone = notificationPreferenceService.getLegacyVoipCallRingtone()
        val uriString = ringtone?.toString()
        val ringtoneChecker = RingtoneChecker(contentResolver)
        if (!ringtoneChecker.isValidRingtoneUri(uriString)) {
            notificationPreferenceService.setLegacyVoipCallRingtone(RingtoneUtil.THREEMA_CALL_RINGTONE_URI)
        }
    }

    override fun getDefaultBackupPath(): File {
        val directory = File(mediaPathPrefix, "Backups")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }
        return directory
    }

    override fun getBackupUri(): Uri? =
        preferenceService.getDataBackupUri()
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                null
            } else {
                Uri.fromFile(getDefaultBackupPath())
            }

    @WorkerThread
    @Throws(IOException::class)
    override fun createTempFile(prefix: String, suffix: String?): File =
        createTempFile(prefix, suffix, targetDirectory = appDirectoryProvider.cacheDirectory)

    @WorkerThread
    @Throws(IOException::class)
    override fun createShareableTempFile(prefix: String, suffix: String?): File =
        createTempFile(prefix, suffix, targetDirectory = appDirectoryProvider.shareDirectory)

    private fun createTempFile(prefix: String, suffix: String?, targetDirectory: File): File {
        targetDirectory.mkdirs()
        return File.createTempFile(prefix.padEnd(3, '_'), suffix, targetDirectory)
    }

    override fun hasUserDefinedProfilePicture(identity: IdentityString): Boolean =
        profilePictureFileHandleProvider.getUserDefinedProfilePicture(identity).exists()

    override fun hasContactDefinedProfilePicture(identity: IdentityString): Boolean =
        profilePictureFileHandleProvider.getContactDefinedProfilePicture(identity).exists()

    override fun hasAndroidDefinedProfilePicture(identity: IdentityString): Boolean =
        profilePictureFileHandleProvider.getAndroidDefinedProfilePicture(identity).exists()

    override fun hasMessageFile(messageUid: MessageUid): Boolean =
        messageFileHandleProvider.get(messageUid).exists()

    override fun hasMessageThumbnail(messageUid: MessageUid): Boolean =
        messageFileHandleProvider.getThumbnail(messageUid).exists()

    @WorkerThread
    override fun deleteMessageFiles(messageUid: MessageUid, keepThumbnails: Boolean): Boolean {
        var success = false
        val fileHandle = messageFileHandleProvider.get(messageUid)
        if (fileHandle.exists()) {
            try {
                fileHandle.delete()
                success = true
            } catch (e: IOException) {
                logger.error("Failed to delete message file", e)
            }
        }
        if (!keepThumbnails) {
            val thumbnailFileHandle = messageFileHandleProvider.getThumbnail(messageUid)
            try {
                thumbnailFileHandle.delete()
            } catch (e: IOException) {
                logger.error("Failed to delete message file thumbnail", e)
            }
        }
        return success
    }

    override fun hasPersistedMessageMedia(messageModel: AbstractMessageModel?): Boolean {
        // F1Whisper: the fact the listen-once settlement decides from (see the interface doc). A model without a UID never
        // had a stored media file.
        val messageUid = messageModel?.uid ?: return false
        return messageFileHandleProvider.get(messageUid).exists()
    }

    @WorkerThread
    @Throws(IOException::class)
    override fun decryptMessageFileToTempFile(message: AbstractMessageModel): File? =
        decryptMessageFileToTempFile(message, targetDirectory = appDirectoryProvider.cacheDirectory)

    @WorkerThread
    @Throws(IOException::class)
    override fun decryptMessageFileToShareableTempFile(message: AbstractMessageModel): File? =
        decryptMessageFileToTempFile(message, targetDirectory = appDirectoryProvider.shareDirectory)

    @WorkerThread
    @Throws(IOException::class)
    override fun decryptMessageFileToPlaybackTempFile(message: AbstractMessageModel): File? =
        decryptMessageFileToTempFile(message, targetDirectory = appDirectoryProvider.shareDirectory, uniqueName = true)

    @WorkerThread
    @Throws(IOException::class)
    private fun decryptMessageFileToTempFile(
        message: AbstractMessageModel,
        targetDirectory: File,
        uniqueName: Boolean = false,
    ): File? {
        val messageUid = message.uid ?: return null
        val fileName = message.getFileName()
        val useDeterministicName = fileName != null && !uniqueName
        val decryptedFile = if (useDeterministicName) {
            File(targetDirectory, "${message.id}-$fileName")
        } else {
            createTempFile(
                prefix = message.id.toString(),
                suffix = getMediaFileExtension(message),
                targetDirectory = targetDirectory,
            )
        }
        // F1Whisper: a file this call created or truncated is never left behind (no source, a broken source, a failed
        // copy); a deterministic path this call never opened for writing may be an earlier export and is not touched.
        var outputOpened = false
        val ownsFile = { !useDeterministicName || outputOpened }
        try {
            val inputStream = decryptMessageFileToStream(messageUid)
            if (inputStream == null) {
                if (ownsFile()) {
                    decryptedFile.delete()
                }
                return null
            }
            inputStream.use {
                FileOutputStream(decryptedFile).use { outputStream ->
                    outputOpened = true
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (e: Exception) {
            if (ownsFile()) {
                decryptedFile.delete()
            }
            throw e
        }
        return decryptedFile
    }

    @Throws(IOException::class)
    override fun decryptMessageFileToStream(messageUid: MessageUid): InputStream? =
        messageFileHandleProvider.get(messageUid).read()

    @Throws(IOException::class)
    override fun decryptedMessageThumbnailToStream(messageUid: MessageUid): InputStream? =
        messageFileHandleProvider.getThumbnail(messageUid).read()

    /**
     * Returns the file name "extension" matching the provided message model.
     *
     * @return The extension including a leading "." or null if no extension could be guessed or the message is not a file message
     */
    private fun getMediaFileExtension(message: AbstractMessageModel): String? {
        if (message.type != MessageType.FILE) {
            return null
        }
        return (
            try {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(message.fileData.getMimeType())
                    ?.takeUnless { it == "bin" }
                    ?.takeUnlessEmpty()
            } catch (e: Exception) {
                logger.error("Failed to get file extension", e)
            }
                ?: (
                    message.getFileName()?.let { fileName ->
                        MimeTypeMap.getFileExtensionFromUrl(fileName)
                    }
                        ?.takeUnlessEmpty()
                    )
            )
            ?.let { extension ->
                ".$extension"
            }
    }

    @WorkerThread
    @Throws(IOException::class)
    private fun copyMediaFileIntoPublicDirectory(
        inputStream: InputStream,
        fileName: String,
        mimeType: String?,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val directory = getPublicDirectory(mimeType)
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val destFile = directory.getUniqueFile(fileName)
            FileOutputStream(destFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            appContext.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(destFile)))
            return
        }

        val relativePath: String?
        val contentUri: Uri
        if (MimeUtil.isAudioFile(mimeType)) {
            relativePath = Environment.DIRECTORY_MUSIC
            contentUri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else if (MimeUtil.isVideoFile(mimeType)) {
            relativePath = Environment.DIRECTORY_MOVIES
            contentUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else if (MimeUtil.isImageFile(mimeType)) {
            relativePath = Environment.DIRECTORY_PICTURES
            contentUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else if (MimeUtil.isPdfFile(mimeType)) {
            relativePath = Environment.DIRECTORY_DOCUMENTS
            contentUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            relativePath = Environment.DIRECTORY_DOWNLOADS
            contentUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val contentValues = buildContentValues {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath + "/" + BuildConfig.MEDIA_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, true)
        }

        val fileUri = appContext.contentResolver.insert(contentUri, contentValues)

        if (fileUri != null) {
            appContext.contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, false)
            appContext.contentResolver.update(fileUri, contentValues, null, null)
        } else {
            logger.error(
                "Cannot open file '{}' with mime type '{}' at '{}/{}' for content uri '{}'",
                fileName,
                mimeType,
                relativePath,
                BuildConfig.MEDIA_PATH,
                contentUri,
            )
            throw IOException("Unable to open file")
        }
    }

    @Deprecated("Used only for SDK < 29")
    private fun getPublicDirectory(mimeType: String?): File =
        if (MimeUtil.isAudioFile(mimeType)) {
            File(mediaPathPrefix, "Threema Audio")
        } else if (MimeUtil.isVideoFile(mimeType)) {
            File(mediaPathPrefix + "Threema Videos")
        } else if (MimeUtil.isImageFile(mimeType)) {
            File(mediaPathPrefix, "Threema Pictures")
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }

    @Throws(IOException::class)
    override fun copyMediaFileToGallery(message: AbstractMessageModel) {
        if (message.type != MessageType.FILE) {
            return
        }
        // F1Whisper: saving writes a permanent clear copy into the device gallery, which is exactly what an incoming
        // listen-once voice message must never produce. The claim/burn owner in the chat player is the only consumer allowed
        // to see that plaintext. Thrown rather than skipped silently so that no caller reports the message as saved.
        if (RestrictedMediaOutput.isRestricted(message)) {
            logger.info("Refusing to save restricted media {}", message.uid)
            throw IOException("Restricted media may not be saved")
        }
        val mediaFilename = constructGalleryMediaFilename(message)
        val messageUid = message.uid
            ?: return

        val fileHandle = messageFileHandleProvider.get(messageUid)
            .takeIf { it.exists() }
            ?: messageFileHandleProvider.getThumbnail(messageUid)

        if (!fileHandle.exists()) {
            throw FileNotFoundException()
        }

        fileHandle.read()?.use { inputStream ->
            copyMediaFileIntoPublicDirectory(
                inputStream,
                mediaFilename,
                MimeUtil.getMimeTypeFromMessageModel(message),
            )
        }
    }

    private fun constructGalleryMediaFilename(message: AbstractMessageModel): String =
        message.getFileName()
            ?.takeUnlessEmpty()
            ?: run {
                FileUtil.getMediaFilenamePrefix(message) + getMediaFileExtension(message)
            }

    @WorkerThread
    @Throws(IOException::class)
    override fun copyDecryptedFileToGallery(sourceUri: Uri, mimeType: String) {
        val mediaFilename = FileUtil.getDefaultFilename(mimeType)
        appContext.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            copyMediaFileIntoPublicDirectory(inputStream, mediaFilename, mimeType)
        }
    }

    @WorkerThread
    override fun writeConversationMedia(messageUid: MessageUid, inputStream: InputStream, overwrite: Boolean): Boolean {
        val fileHandle = messageFileHandleProvider.get(messageUid)

        if (fileHandle.exists()) {
            if (overwrite) {
                try {
                    fileHandle.delete()
                } catch (e: IOException) {
                    logger.warn("Failed to delete message file before writing", e)
                }
            } else {
                return false
            }
        }

        try {
            inputStream.copyTo(fileHandle)
        } catch (e: Exception) {
            logger.error("Exception while writing conversation media", e)
            return false
        }

        return true
    }

    @WorkerThread
    @Throws(IOException::class)
    override fun writeGroupProfilePicture(
        groupIdentity: GroupIdentity,
        groupDatabaseId: GroupDatabaseId,
        data: InputStream,
    ) {
        val fileHandle = groupProfilePictureFileHandleProvider.get(groupDatabaseId)
        data.copyTo(fileHandle)
        avatarCacheService.reset(groupIdentity)
    }

    @Throws(IOException::class)
    override fun getGroupProfilePictureStream(groupDatabaseId: GroupDatabaseId): InputStream? =
        groupProfilePictureFileHandleProvider.get(groupDatabaseId).read()

    @Throws(IOException::class)
    override fun getGroupProfilePictureBytes(groupDatabaseId: GroupDatabaseId): ByteArray? =
        getGroupProfilePictureStream(groupDatabaseId)?.use { inputStream ->
            inputStream.readBytes()
        }

    override fun getGroupProfilePictureBitmap(groupDatabaseId: GroupDatabaseId): Bitmap? =
        groupProfilePictureFileHandleProvider.get(groupDatabaseId)
            .decodeToBitmap()

    @WorkerThread
    private fun FileHandle.decodeToBitmap(): Bitmap? =
        try {
            read()?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            logger.error("Failed to decode bitmap", e)
            null
        }

    @WorkerThread
    override fun removeGroupProfilePicture(groupIdentity: GroupIdentity, groupDatabaseId: GroupDatabaseId) {
        val fileHandle = groupProfilePictureFileHandleProvider.get(groupDatabaseId)
        try {
            fileHandle.delete()
            avatarCacheService.reset(groupIdentity)
        } catch (e: IOException) {
            logger.error("Failed to delete group profile picture", e)
        }
    }

    override fun hasGroupProfilePicture(groupDatabaseId: GroupDatabaseId): Boolean =
        groupProfilePictureFileHandleProvider.get(groupDatabaseId).exists()

    @WorkerThread
    override fun writeUserDefinedProfilePicture(identity: IdentityString, file: File): Boolean {
        val fileHandle = profilePictureFileHandleProvider.getUserDefinedProfilePicture(identity)
        try {
            file.copyTo(fileHandle)
        } catch (e: Exception) {
            logger.error("Failed to write user defined profile picture", e)
            return false
        }
        avatarCacheService.reset(identity)
        return true
    }

    @WorkerThread
    @Throws(IOException::class)
    override fun writeUserDefinedProfilePicture(identity: IdentityString, inputStream: InputStream) {
        val fileHandle = profilePictureFileHandleProvider.getUserDefinedProfilePicture(identity)
        inputStream.copyTo(fileHandle)
        avatarCacheService.reset(identity)
    }

    @WorkerThread
    @Throws(IOException::class)
    override fun writeContactDefinedProfilePicture(identity: IdentityString, imageData: InputStream) {
        val fileHandle = profilePictureFileHandleProvider.getContactDefinedProfilePicture(identity)
        imageData.copyTo(fileHandle)
        avatarCacheService.reset(identity)
    }

    @WorkerThread
    @Throws(IOException::class)
    override fun writeAndroidDefinedProfilePicture(identity: IdentityString, imageData: ByteArray) {
        val fileHandle = profilePictureFileHandleProvider.getAndroidDefinedProfilePicture(identity)
        ByteArrayInputStream(imageData).copyTo(fileHandle)
        avatarCacheService.reset(identity)
    }

    override fun getUserDefinedProfilePicture(identity: IdentityString): Bitmap? =
        profilePictureFileHandleProvider.getUserDefinedProfilePicture(identity)
            .decodeToBitmap()

    @WorkerThread
    override fun getAndroidDefinedProfilePicture(contactModel: ContactModel): Bitmap? {
        val contactModelData = contactModel.data
        if (contactModelData == null) {
            logger.error("Contact model data is null")
            return null
        }

        val now = timeProvider.get()
        val expiration = contactModelData.localAvatarExpires ?: Instant.EPOCH
        if (expiration < now) {
            try {
                AndroidContactUtil.getInstance()
                    .updateAvatarByAndroidContact(contactModel, appContext)
            } catch (e: SecurityException) {
                logger.error("Could not update profile picture of Android contact", e)
            }
        }

        return profilePictureFileHandleProvider.getAndroidDefinedProfilePicture(contactModel.identity)
            .decodeToBitmap()
    }

    @Throws(IOException::class)
    override fun getUserDefinedProfilePictureStream(identity: IdentityString): InputStream? =
        profilePictureFileHandleProvider.getUserDefinedProfilePicture(identity).read()

    @Throws(IOException::class)
    override fun getContactDefinedProfilePictureStream(identity: IdentityString): InputStream? =
        profilePictureFileHandleProvider.getContactDefinedProfilePicture(identity).read()

    @WorkerThread
    override fun getContactDefinedProfilePicture(identity: IdentityString): Bitmap? =
        profilePictureFileHandleProvider.getContactDefinedProfilePicture(identity).decodeToBitmap()

    @WorkerThread
    override fun removeUserDefinedProfilePicture(identity: IdentityString): Boolean {
        val fileHandle = profilePictureFileHandleProvider.getUserDefinedProfilePicture(identity)
        try {
            fileHandle.delete()
            avatarCacheService.reset(identity)
            return true
        } catch (e: IOException) {
            logger.error("Failed to delete user defined profile picture", e)
            return false
        }
    }

    @WorkerThread
    override fun removeContactDefinedProfilePicture(identity: IdentityString) {
        val fileHandle = profilePictureFileHandleProvider.getContactDefinedProfilePicture(identity)
        try {
            fileHandle.delete()
            avatarCacheService.reset(identity)
        } catch (e: IOException) {
            logger.error("Failed to delete contact defined profile picture", e)
        }
    }

    @WorkerThread
    override fun removeAndroidDefinedProfilePicture(identity: IdentityString): Boolean {
        val fileHandle = profilePictureFileHandleProvider.getAndroidDefinedProfilePicture(identity)
        if (!fileHandle.exists()) {
            return false
        }
        try {
            fileHandle.delete()
            avatarCacheService.reset(identity)
            return true
        } catch (e: IOException) {
            logger.error("Failed to delete android defined profile picture", e)
            return false
        }
    }

    @WorkerThread
    @Throws(IOException::class)
    override fun writeConversationMediaThumbnail(message: AbstractMessageModel, thumbnail: ResettableInputStream) {
        val maxWidth = ConfigUtils.getPreferredThumbnailWidth(appContext, false)
            .coerceAtMost(MessageServiceImpl.THUMBNAIL_SIZE_PX * 2)
        val resizedThumbnailBytes = BitmapUtil.resizeImageToMaxWidth(thumbnail, maxWidth)
            ?: throw IOException("Unable to scale thumbnail")
        val messageUid = message.uid
            ?: return
        saveThumbnail(messageUid, ByteArrayInputStream(resizedThumbnailBytes))
    }

    @WorkerThread
    @Throws(IOException::class)
    override fun saveThumbnail(messageUid: MessageUid, thumbnail: InputStream) {
        thumbnail.copyTo(messageFileHandleProvider.getThumbnail(messageUid))
    }

    @WorkerThread
    override fun getMessageThumbnailBitmap(message: AbstractMessageModel?, thumbnailCache: ThumbnailCache<Int>?): Bitmap? {
        if (message == null) {
            return null
        }
        if (thumbnailCache != null) {
            val cached = thumbnailCache.get(message.id)
            if (cached != null && !cached.isRecycled) {
                return cached
            }
        }

        val messageUid = message.uid
            ?: return null

        val originalBitmap = messageFileHandleProvider.getThumbnail(messageUid).decodeToBitmap()
            ?: return null

        try {
            val thumbnailBitmap = BitmapUtil.resizeBitmapExactlyToMaxWidth(originalBitmap, MessageServiceImpl.THUMBNAIL_SIZE_PX)
            try {
                thumbnailCache?.set(message.id, thumbnailBitmap)
            } finally {
                if (originalBitmap != thumbnailBitmap) {
                    originalBitmap.recycle()
                }
            }
            return thumbnailBitmap
        } catch (e: Exception) {
            logger.error("Failed to resize thumbnail", e)
        }
        return null
    }

    @WorkerThread
    override fun deleteMediaFiles() {
        appDirectoryProvider.userFilesDirectory.clearDirectoryRecursively()
        @Suppress("DEPRECATION")
        appDirectoryProvider.legacyUserFilesDirectory.clearDirectoryNonRecursively()
    }

    @WorkerThread
    override fun copyUriToTempFile(source: Uri, prefix: String, suffix: String?): File? =
        copyUriToTempFile(source, prefix, suffix, targetDirectory = appDirectoryProvider.cacheDirectory)

    private fun copyUriToTempFile(source: Uri, prefix: String, suffix: String?, targetDirectory: File): File? {
        try {
            appContext.contentResolver.openInputStream(source)?.use { inputStream ->
                val outputFile = createTempFile(prefix, suffix, targetDirectory)
                FileOutputStream(outputFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                return outputFile
            }
        } catch (e: Exception) {
            logger.error("Failed to copy to temp file", e)
        }
        return null
    }

    @WorkerThread
    override fun copyDecryptedMessageFileToShareDirectory(message: AbstractMessageModel, decryptedFile: File): Uri? {
        // F1Whisper: the media viewer's share menu reaches this directly rather than through the multi-select share route, so
        // the same restriction has to be enforced here too.
        if (RestrictedMediaOutput.isRestricted(message)) {
            logger.info("Refusing to copy restricted media to a share file")
            return null
        }
        if (!decryptedFile.exists()) {
            return null
        }
        val destFilePrefix = FileUtil.getMediaFilenamePrefix(message)
        val destFileExtension = getMediaFileExtension(message)
        val destFile = copyUriToTempFile(
            source = Uri.fromFile(decryptedFile),
            prefix = destFilePrefix,
            suffix = destFileExtension,
            targetDirectory = appDirectoryProvider.shareDirectory,
        )
            ?: return null

        val filename = if (message.type == MessageType.FILE) {
            message.fileData.fileName
        } else {
            null
        }
        return FileProviderUtil.getUriForFile(appContext, destFile, filename)
    }

    @WorkerThread
    override fun decryptMessageFiles(messages: List<AbstractMessageModel>): List<File> =
        decryptMessageFilesPositional(messages).filterNotNull()

    @WorkerThread
    override fun decryptMessageFilesPositional(messages: List<AbstractMessageModel>): List<File?> =
        decryptForSharing(messages) { _, file -> file }

    @WorkerThread
    override fun decryptMessageFilesForSharing(messages: List<AbstractMessageModel>): List<Uri> =
        decryptMessageFilesForSharingPositional(messages).filterNotNull()

    @WorkerThread
    override fun decryptMessageFilesForSharingPositional(messages: List<AbstractMessageModel>): List<Uri?> =
        decryptForSharing(messages) { message, file ->
            FileProviderUtil.getUriForFile(appContext, file, message.getFileName())
        }

    /**
     * Decrypts every message into the 'share' directory and maps each decrypted file with [transform].
     *
     * The result has one slot per message, in order: `null` where the message is restricted or its file could not be
     * decrypted. If not a single slot could be filled, the last failure is thrown.
     */
    @WorkerThread
    private fun <T : Any> decryptForSharing(
        messages: List<AbstractMessageModel>,
        transform: (message: AbstractMessageModel, file: File) -> T,
    ): List<T?> {
        val results = mutableListOf<T?>()
        var exception: Exception? = null

        for (message in messages) {
            // F1Whisper: sharing or forwarding hands another app (or chat) a decrypted copy that outlives this one. The slot
            // stays null and counts as a failure, so a mixed selection still shares everything else and the caller can tell
            // that something was left out, rather than silently receiving a short list.
            if (RestrictedMediaOutput.isRestricted(message)) {
                logger.info("Refusing to share restricted media {}", message.uid)
                exception = FileNotFoundException()
                results.add(null)
                continue
            }
            try {
                val file = decryptMessageFileToShareableTempFile(message)
                if (file != null) {
                    results.add(transform(message, file))
                } else {
                    exception = FileNotFoundException()
                    results.add(null)
                }
            } catch (e: Exception) {
                exception = e
                results.add(null)
            }
        }

        // If at least one file could be decrypted, we consider it a success
        if (results.all { it == null } && exception != null) {
            throw exception
        }
        return results
    }

    @WorkerThread
    override fun decryptThumbnailToShareableTempFile(message: AbstractMessageModel, maxSize: Int): Uri? {
        val messageUid = message.uid
            ?: return null
        val fileHandle = messageFileHandleProvider.getThumbnail(messageUid)
        if (!fileHandle.exists()) {
            return null
        }

        try {
            val thumbnailMimeType = message.fileData.thumbnailMimeType
            if (thumbnailMimeType != null) {
                val prefix = FileUtil.getMediaFilenamePrefix(message)
                val suffix = if (MimeUtil.MIME_TYPE_IMAGE_PNG == thumbnailMimeType) ".png" else ".jpg"
                val outputFile = createShareableTempFile(prefix, suffix)

                fileHandle.read()?.use { inputStream ->
                    appContext.contentResolver.openOutputStream(Uri.fromFile(outputFile))?.use { outputStream ->
                        val numBytes = inputStream.copyTo(outputStream)
                        if (numBytes in 1..maxSize) {
                            return FileProviderUtil.getUriForFile(appContext, outputFile)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to copy thumbnail", e)
        }
        return null
    }
}
