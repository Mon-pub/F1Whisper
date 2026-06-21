package ch.threema.storage.models.data.media;

import android.util.JsonWriter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import ch.threema.app.utils.FileUtil;
import ch.threema.app.utils.JsonUtil;
import ch.threema.app.utils.ListReader;
import ch.threema.app.utils.MimeUtil;
import ch.threema.app.utils.ElapsedTimeFormatter;
import ch.threema.app.utils.TestUtil;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

import ch.threema.base.utils.Utils;
import ch.threema.domain.protocol.csp.messages.file.FileData;

public class FileDataModel implements MediaMessageDataInterface {
    private static final Logger logger = getThreemaLogger("FileDataModel");

    public static final String METADATA_KEY_DURATION = "d";
    public static final String METADATA_KEY_WIDTH = "w";
    public static final String METADATA_KEY_HEIGHT = "h";
    public static final String METADATA_KEY_ANIMATED = "a";
    /**
     * Custom (F1Whisper) metadata flag marking a voice message as "listen once". When {@code true},
     * the recipient may play the message a single time, after which the decrypted media is deleted
     * and replay is blocked. This is carried inside the E2E-encrypted file metadata map ("x"), so it
     * is invisible to the server. NOTE: enforcement is purely client-side and best-effort.
     */
    public static final String METADATA_KEY_LISTEN_ONCE = "lo";

    /**
     * Custom (F1Whisper) metadata flag marking a "listen once" voice message as already consumed
     * (played once by the recipient and its media deleted). Stored locally only - it is set after
     * playback and persisted in the message's file-data JSON so the "burned" placeholder survives
     * closing and reopening the chat, independent of the volatile {@link ch.threema.storage.models.MessageState}
     * (which a later reaction/receipt could move away from {@code CONSUMED}). Never sent over the wire.
     */
    public static final String METADATA_KEY_LISTEN_ONCE_CONSUMED = "loc";

    /**
     * Custom (F1Whisper) metadata flag marking an image or video message as a "spoiler". When
     * {@code true}, the recipient sees a blurred thumbnail with a tap-to-reveal overlay until they
     * choose to reveal it (re-hidden each session). Carried inside the E2E-encrypted file metadata
     * map ("x"), invisible to the server. Rendering/reveal are purely client-side.
     */
    public static final String METADATA_KEY_SPOILER = "sp";

    /**
     * Custom (F1Whisper) metadata flag marking a forwarded media/file/voice message. Set on the
     * forwarded copy at send time and carried inside the E2E-encrypted file metadata map ("x"), so a
     * receiving F1Whisper client also sees the "Forwarded" header (invisible to the server). Plain
     * text/location messages have no metadata carrier and are therefore not marked.
     */
    public static final String METADATA_KEY_FORWARDED = "fwd";

    private byte[] fileBlobId;
    private byte[] encryptionKey;
    private String mimeType;
    private String thumbnailMimeType;
    private long fileSize;
    private @Nullable String fileName;
    private @FileData.RenderingType int renderingType;
    private boolean isDownloaded;
    private String caption;
    private Map<String, Object> metaData;

    /**
     * @return A new instance of {@code FileDataModel} with the field {@code isDownloaded} set to {@code false} (as its an incoming message file data).
     */
    @NonNull
    public static FileDataModel fromIncomingFileData(@NonNull FileData fileData) {
        return new FileDataModel(
            /* fileBlobId = */ fileData.getFileBlobId(),
            /* encryptionKey = */ fileData.getEncryptionKey(),
            /* mimeType = */ fileData.getMimeType(),
            /* thumbnailMimeType = */ fileData.getThumbnailMimeType(),
            /* fileSize = */ fileData.getFileSize(),
            /* fileName = */ FileUtil.sanitizeFileName(fileData.getFileName()),
            /* renderingType = */ fileData.getRenderingType(),
            /* caption = */ fileData.getCaption(),
            /* isDownloaded = */ false,
            /* metaData = */ fileData.getMetaData()
        );
    }

    // incoming
    public FileDataModel(
        byte[] fileBlobId,
        byte[] encryptionKey,
        String mimeType,
        String thumbnailMimeType,
        long fileSize,
        @Nullable String fileName,
        @FileData.RenderingType int renderingType,
        String caption,
        boolean isDownloaded,
        Map<String, Object> metaData
    ) {
        this.fileBlobId = fileBlobId;
        this.encryptionKey = encryptionKey;
        this.mimeType = mimeType;
        this.thumbnailMimeType = thumbnailMimeType;
        this.fileSize = fileSize;
        this.fileName = fileName;
        this.renderingType = renderingType;
        this.caption = caption;
        this.isDownloaded = isDownloaded;
        this.metaData = metaData;
    }

    // outgoing
    public FileDataModel(
        String mimeType,
        String thumbnailMimeType,
        long fileSize,
        @Nullable String fileName,
        @FileData.RenderingType int renderingType,
        String caption,
        boolean isDownloaded,
        Map<String, Object> metaData
    ) {
        this.mimeType = mimeType;
        this.thumbnailMimeType = thumbnailMimeType;
        this.fileSize = fileSize;
        this.fileName = fileName;
        this.renderingType = renderingType;
        this.caption = caption;
        this.isDownloaded = isDownloaded;
        this.metaData = metaData;
    }

    private FileDataModel() {
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public void setFileName(@Nullable String fileName) {
        this.fileName = fileName;
    }

    public void setRenderingType(@FileData.RenderingType int renderingType) {
        this.renderingType = renderingType;
    }

    public void setBlobId(byte[] blobId) {
        this.fileBlobId = blobId;
    }

    @Override
    public byte[] getBlobId() {
        return this.fileBlobId;
    }

    public void setEncryptionKey(byte[] encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    @Override
    public byte[] getEncryptionKey() {
        return this.encryptionKey;
    }

    @Override
    public boolean isDownloaded() {
        return this.isDownloaded;
    }

    @Override
    public void isDownloaded(boolean isDownloaded) {
        this.isDownloaded = isDownloaded;
    }

    @Override
    public byte[] getNonce() {
        return new byte[0];
    }

    @NonNull
    public String getMimeType() {
        if (this.mimeType == null) {
            return MimeUtil.MIME_TYPE_DEFAULT;
        }
        return this.mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    @Nullable
    public String getThumbnailMimeType() {
        return this.thumbnailMimeType;
    }

    public void setThumbnailMimeType(String thumbnailMimeType) {
        this.thumbnailMimeType = thumbnailMimeType;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public long getFileSize() {
        return this.fileSize;
    }

    public @Nullable String getFileName() {
        return this.fileName;
    }

    public @FileData.RenderingType int getRenderingType() {
        return this.renderingType;
    }

    public String getCaption() {
        return this.caption;
    }

    public Map<String, Object> getMetaData() {
        return this.metaData;
    }

    public void setMetaData(Map<String, Object> metaData) {
        this.metaData = metaData;
    }

    @Nullable
    public Integer getMetaDataInt(String metaDataKey) {
        return this.metaData != null
            && this.metaData.containsKey(metaDataKey)
            && this.metaData.get(metaDataKey) instanceof Number ?
            (Integer) this.metaData.get(metaDataKey) : null;
    }

    @Nullable
    public String getMetaDataString(String metaDataKey) {
        return this.metaData != null
            && this.metaData.containsKey(metaDataKey)
            && this.metaData.get(metaDataKey) instanceof String ?
            (String) this.metaData.get(metaDataKey) : null;
    }

    @Nullable
    public Boolean getMetaDataBool(String metaDataKey) {
        return this.metaData != null
            && this.metaData.containsKey(metaDataKey)
            && this.metaData.get(metaDataKey) instanceof Boolean ?
            (Boolean) this.metaData.get(metaDataKey) : null;
    }

    @Nullable
    public Float getMetaDataFloat(String metaDataKey) {
        if (this.metaData != null && this.metaData.containsKey(metaDataKey)) {
            final @Nullable Object value = this.metaData.get(metaDataKey);
            if (value == null) {
                return null;
            }
            if (value instanceof Number) {
                if (value instanceof Double) {
                    return ((Double) value).floatValue();
                } else if (value instanceof Float) {
                    return (Float) value;
                } else if (value instanceof Integer) {
                    return ((Integer) value).floatValue();
                } else {
                    return 0F;
                }
            }
        }
        return null;
    }

    /**
     * @return {@code true} if this file message carries the "listen once" metadata flag (a voice
     * message that the recipient may play only a single time). Returns {@code false} when the flag
     * is absent or not set, so it is safe to call on any file message.
     */
    public boolean isListenOnce() {
        return Boolean.TRUE.equals(getMetaDataBool(METADATA_KEY_LISTEN_ONCE));
    }

    /**
     * @return {@code true} if this listen-once voice message has already been played once and burned
     * (media deleted). The burned state is persisted in the file-data metadata, so it survives a
     * chat close/reopen regardless of the message's {@link ch.threema.storage.models.MessageState}.
     */
    public boolean isListenOnceConsumed() {
        return Boolean.TRUE.equals(getMetaDataBool(METADATA_KEY_LISTEN_ONCE_CONSUMED));
    }

    /**
     * Persistently mark this listen-once voice message as consumed (burned). Lazily creates the
     * metadata map if absent. Call after the single playback completes and the media is deleted.
     */
    public void setListenOnceConsumed() {
        if (this.metaData == null) {
            this.metaData = new HashMap<>();
        }
        this.metaData.put(METADATA_KEY_LISTEN_ONCE_CONSUMED, Boolean.TRUE);
    }

    /**
     * @return {@code true} if this media/file/voice message was forwarded (carries the "fwd"
     * metadata flag). Safe to call on any file message; returns {@code false} when absent.
     */
    public boolean isForwarded() {
        return Boolean.TRUE.equals(getMetaDataBool(METADATA_KEY_FORWARDED));
    }

    /**
     * @return {@code true} if this image/video file message carries the "spoiler" metadata flag.
     * Safe to call on any file message (returns {@code false} when absent).
     */
    public boolean isSpoiler() {
        return Boolean.TRUE.equals(getMetaDataBool(METADATA_KEY_SPOILER));
    }

    /**
     * Return a formatted string representing the duration as provided by the respective metadata field
     * in the format of hours:minutes:seconds
     *
     * @return Formatted duration string or 00:00 in case of error
     */
    public @NonNull String getDurationString() {
        return ElapsedTimeFormatter.secondsToString(getDurationSeconds());
    }

    /**
     * Return the duration in SECONDS as set in the metadata field.
     */
    public long getDurationSeconds() {
        try {
            Float durationF = getMetaDataFloat(METADATA_KEY_DURATION);
            if (durationF != null) {
                return Math.round(durationF);
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    /**
     * Note: Floats are converted to long integers. No rounding.
     *
     * @return The value in the meta-data-map for key {@code d} converted to milliseconds or {@code 0L} as fallback.
     */
    public long getDurationMs() {
        try {
            @Nullable Float durationF = getMetaDataFloat(METADATA_KEY_DURATION);
            if (durationF != null) {
                durationF *= 1000F;
                return durationF.longValue();
            }
        } catch (Exception exception) {
            logger.warn("Ignored exception", exception);
        }
        return 0L;
    }

    private void fromString(String s) {
        if (TestUtil.isEmptyOrNull(s)) {
            return;
        }

        try {
            ListReader reader = new ListReader(JsonUtil.convertArray(s));
            this.fileBlobId = reader.nextStringAsByteArray();
            this.encryptionKey = reader.nextStringAsByteArray();
            this.mimeType = reader.nextString();
            this.fileSize = reader.nextInteger();
            this.fileName = reader.nextString();
            try {
                Integer typeId = reader.nextInteger();
                if (typeId != null) {
                    this.renderingType = typeId;
                }
            } catch (ClassCastException ignore) {
                // ignore very old filedatamodel without rendering type
            }
            this.isDownloaded = reader.nextBool();
            this.caption = reader.nextString();
            this.thumbnailMimeType = reader.nextString();
            this.metaData = reader.nextMap();
        } catch (Exception e) {
            // Ignore error, just log
            logger.error("Extract file data model", e);
        }
    }

    @Override
    public String toString() {
        StringWriter sw = new StringWriter();
        JsonWriter j = new JsonWriter(sw);

        try {
            j.beginArray();
            j
                .value(Utils.byteArrayToHexString(this.getBlobId()))
                .value(Utils.byteArrayToHexString(this.getEncryptionKey()))
                .value(this.mimeType)
                .value(this.fileSize)
                .value(this.fileName)
                .value(this.renderingType)
                .value(this.isDownloaded)
                .value(this.caption)
                .value(this.thumbnailMimeType);

            // Always write the meta data object
            JsonWriter metaDataObject = j.beginObject();
            if (this.metaData != null) {
                Iterator<String> keys = this.metaData.keySet().iterator();

                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = this.metaData.get(key);

                    metaDataObject.name(key);

                    try {
                        if (value instanceof Integer) {
                            metaDataObject.value((Integer) value);
                        } else if (value instanceof Float) {
                            metaDataObject.value((Float) value);
                        } else if (value instanceof Double) {
                            metaDataObject.value((Double) value);
                        } else if (value instanceof Boolean) {
                            metaDataObject.value((Boolean) value);
                        } else if (value == null) {
                            metaDataObject.nullValue();
                        } else {
                            metaDataObject.value(value.toString());
                        }
                    } catch (IOException x) {
                        logger.error("Failed to write meta data", x);
                        // Write a NULL
                        metaDataObject.nullValue();
                    }
                }
            }
            j.endObject();
            j.endArray();
        } catch (Exception x) {
            logger.error("Exception", x);
            return null;
        }

        return sw.toString();
    }

    @NonNull
    public static FileDataModel create(@NonNull String s) {
        FileDataModel m = new FileDataModel();
        m.fromString(s);
        return m;
    }

    /**
     * Do not use this in new code. It only exists to handle places where a [FileModel] needs to be returned and `null` is not allowed.
     */
    @NonNull
    @Deprecated()
    public static FileDataModel createEmpty() {
        return new FileDataModel();
    }
}
