package ch.threema.app.voicemessage;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;

import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import ch.threema.app.utils.FileUtil;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

/**
 * F1Whisper: standalone, lossless audio crop driver for voice messages.
 *
 * <p>It copies the already-encoded AAC frames of a voice message between a chosen start and end
 * time into a new MPEG-4 (.m4a/.mp4) container without re-encoding, so there is no generation loss
 * and no dependency on an OEM encoder (only the platform {@link MediaExtractor}/{@link MediaMuxer},
 * which every device ships). This matches exactly what {@link AudioRecorder} produces:
 * {@code MediaRecorder.OutputFormat.MPEG_4} + {@code MediaRecorder.AudioEncoder.AAC}, mono.
 *
 * <p>Unlike the {@code video/transcoder/audio/*} transcoders, this is decoupled from the video
 * muxer loop: it muxes the single audio track on its own. We deliberately do NOT decode/re-encode
 * because the recorder output is already AAC and a frame copy is both faster and lossless.
 *
 * <p>Robustness notes: AAC-in-MP4 has a sync sample at (effectively) every access unit, so seeking
 * to the start sync sample lands close to the requested start. Presentation timestamps of the
 * copied samples are rebased to zero so the trimmed clip plays from the beginning across vendor
 * decoders. minSdk is 24, so this uses {@code java.io}/{@code FileDescriptor} only - never
 * {@code java.nio.file}/{@code Path} (API 26).
 */
public class AudioTrimmer {
    private static final Logger logger = getThreemaLogger("AudioTrimmer");

    // 256 KiB is comfortably larger than a single AAC access unit at our 32 kbps mono bitrate.
    private static final int MAX_SAMPLE_SIZE = 256 * 1024;

    /**
     * F1Whisper: how a given source container is losslessly croppable. Chosen by sniffing the real
     * container magic bytes (NOT the file extension), so a mislabeled file is handled correctly and
     * an unknown/decode-only format ({@link #UNSUPPORTED}) makes the caller fail-safe (abort the
     * send) rather than silently shipping the untrimmed original.
     */
    public enum TrimMethod {
        /** AAC-in-MP4/m4a: lossless frame copy into an MPEG-4 container via {@link MediaMuxer}. */
        AAC_MP4,
        /** MP3 (MPEG-audio): lossless cut on frame boundaries via {@link Mp3AudioTrimmer}. */
        MP3_FRAMES,
        /** WAV (RIFF/PCM): lossless PCM byte-range copy via {@link WavAudioTrimmer}. */
        WAV_PCM,
        /** Ogg (Opus/Vorbis): lossless page-aligned copy via {@link MediaMuxer} OGG output (API 29+). */
        OGG_MUXER,
        /** Cannot be cropped losslessly here (e.g. FLAC, MIDI): caller must fail-safe. */
        UNSUPPORTED,
    }

    private final Context context;
    private final Uri sourceUri;
    private final long startTimeMs;
    private final long endTimeMs;

    /**
     * @param context    application context
     * @param sourceUri  the recorded voice message file (AAC-in-MPEG-4)
     * @param startTimeMs trim start, in milliseconds from the beginning of the recording
     * @param endTimeMs   trim end, in milliseconds from the beginning of the recording
     */
    public AudioTrimmer(@NonNull Context context, @NonNull Uri sourceUri, long startTimeMs, long endTimeMs) {
        this.context = context;
        this.sourceUri = sourceUri;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
    }

    /**
     * Crop the source recording to the {@code [startTimeMs, endTimeMs]} window and write the result
     * to {@code destinationFile} losslessly, dispatching to the correct per-container strategy:
     * AAC/Opus via {@link MediaMuxer}, MP3 via frame-boundary copy, WAV via PCM byte-range copy.
     * An {@link TrimMethod#UNSUPPORTED} container returns {@code false} so the caller can fail-safe.
     *
     * @param destinationFile the file the cropped recording is written to (will be overwritten)
     * @return true on success, false if the format is unsupported or anything went wrong. The caller
     * MUST abort the send on false; it must never send the untrimmed original after a trim request.
     */
    // MUXER_OUTPUT_OGG is an API-29 constant (inlined at compile time); its use is runtime-gated by
    // the Build.VERSION.SDK_INT check immediately above the reference.
    @SuppressLint("InlinedApi")
    @WorkerThread
    public boolean trim(@NonNull File destinationFile) {
        if (endTimeMs <= startTimeMs) {
            logger.warn("Invalid trim window: start={}ms end={}ms", startTimeMs, endTimeMs);
            return false;
        }

        final TrimMethod method = getTrimMethod(context, sourceUri);
        switch (method) {
            case AAC_MP4:
                return trimViaMuxer(destinationFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            case OGG_MUXER:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return trimViaMuxer(destinationFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG);
                }
                logger.info("Ogg trim needs API 29+; unsupported on this device");
                return false;
            case MP3_FRAMES:
                return new Mp3AudioTrimmer(context, sourceUri, startTimeMs, endTimeMs).trim(destinationFile);
            case WAV_PCM:
                return new WavAudioTrimmer(context, sourceUri, startTimeMs, endTimeMs).trim(destinationFile);
            case UNSUPPORTED:
            default:
                logger.info("Audio container is not losslessly trimmable");
                return false;
        }
    }

    /**
     * Lossless frame/page copy from the source's first audio track into a new {@code outputFormat}
     * container (MPEG-4 for AAC, OGG for Opus/Vorbis) via {@link MediaExtractor}/{@link MediaMuxer}.
     */
    @WorkerThread
    private boolean trimViaMuxer(@NonNull File destinationFile, int outputFormat) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        AssetFileDescriptor sourceDescriptor = null;
        boolean muxerStarted = false;
        boolean success = false;
        try {
            extractor = new MediaExtractor();
            // Prefer a real path; fall back to a content-resolver FileDescriptor so attached audio
            // files (SAF/content URIs without a real path) can be trimmed too. minSdk24-safe: uses
            // AssetFileDescriptor (java.io), never java.nio.file. Returns the descriptor (if any) so
            // we can close it in the finally block.
            sourceDescriptor = openExtractorDataSource(context, sourceUri, extractor);

            final int audioTrackIndex = selectAudioTrack(extractor);
            if (audioTrackIndex < 0) {
                logger.warn("No audio track found in source recording");
                return false;
            }
            extractor.selectTrack(audioTrackIndex);
            final MediaFormat trackFormat = extractor.getTrackFormat(audioTrackIndex);

            // Write into the app-private temp file. minSdk24: MediaMuxer(String, ...) is fine here;
            // the path comes from File.getAbsolutePath() (java.io), never java.nio.file.
            muxer = new MediaMuxer(destinationFile.getAbsolutePath(), outputFormat);
            final int muxerTrackIndex = muxer.addTrack(trackFormat);
            muxer.start();
            muxerStarted = true;

            // Seek to the sync sample at or before the requested start so no encoded frame is lost.
            // Audio before the exact start time is dropped below by skipping samples whose adjusted
            // presentation time would be negative.
            final long startTimeUs = startTimeMs * 1000L;
            final long endTimeUs = endTimeMs * 1000L;
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            // Rebase timestamps to the first kept sample so the clip starts at 0us.
            long firstKeptSampleTimeUs = -1L;
            final ByteBuffer buffer = ByteBuffer.allocate(MAX_SAMPLE_SIZE);
            final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int writtenSamples = 0;

            while (true) {
                final long sampleTimeUs = extractor.getSampleTime();
                if (sampleTimeUs < 0) {
                    // End of stream.
                    break;
                }
                if (sampleTimeUs > endTimeUs) {
                    // Past the requested end.
                    break;
                }

                final int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) {
                    break;
                }

                // Drop samples that fall before the precise start time (the seek lands on the
                // previous sync sample, which may be slightly earlier than startTimeUs).
                if (sampleTimeUs >= startTimeUs) {
                    if (firstKeptSampleTimeUs < 0) {
                        firstKeptSampleTimeUs = sampleTimeUs;
                    }
                    bufferInfo.offset = 0;
                    bufferInfo.size = sampleSize;
                    bufferInfo.presentationTimeUs = sampleTimeUs - firstKeptSampleTimeUs;
                    bufferInfo.flags = toMuxerSampleFlags(extractor.getSampleFlags());
                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo);
                    writtenSamples++;
                }

                if (!extractor.advance()) {
                    break;
                }
            }

            if (writtenSamples == 0) {
                logger.warn("Trim produced no audio samples (start={}ms end={}ms)", startTimeMs, endTimeMs);
                success = false;
            } else {
                logger.info("Trimmed voice message: wrote {} samples for window [{}ms, {}ms]",
                    writtenSamples, startTimeMs, endTimeMs);
                success = true;
            }
        } catch (IOException e) {
            logger.error("I/O error while trimming voice message", e);
            success = false;
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Bad track format / muxer rejected the sample / unexpected extractor state.
            logger.error("Failed to trim voice message", e);
            success = false;
        } finally {
            if (muxer != null) {
                try {
                    if (muxerStarted) {
                        muxer.stop();
                    }
                } catch (IllegalStateException e) {
                    logger.warn("Muxer stop failed", e);
                    success = false;
                }
                try {
                    muxer.release();
                } catch (Exception e) {
                    logger.warn("Muxer release failed", e);
                }
            }
            if (extractor != null) {
                extractor.release();
            }
            if (sourceDescriptor != null) {
                try {
                    sourceDescriptor.close();
                } catch (IOException e) {
                    logger.warn("Failed to close source descriptor", e);
                }
            }
        }

        if (!success && destinationFile.exists()) {
            // Clean up a partial/invalid output so the caller never sends a broken clip.
            if (!destinationFile.delete()) {
                logger.warn("Failed to delete incomplete trim output {}", destinationFile.getAbsolutePath());
            }
        }
        return success;
    }

    /**
     * Translate {@link MediaExtractor} sample flags into {@link MediaCodec.BufferInfo} flags
     * understood by {@link MediaMuxer#writeSampleData}.
     */
    private static int toMuxerSampleFlags(int extractorSampleFlags) {
        int flags = 0;
        if ((extractorSampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
        }
        return flags;
    }

    /**
     * @return the index of the first audio track in the extractor, or -1 if none.
     */
    private static int selectAudioTrack(@NonNull MediaExtractor extractor) {
        final int trackCount = extractor.getTrackCount();
        for (int i = 0; i < trackCount; i++) {
            final MediaFormat format = extractor.getTrackFormat(i);
            final String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Point {@code extractor} at {@code sourceUri}. Prefers a resolved real file path; if none is
     * available (typical for SAF / content URIs of attached files) it opens a content-resolver
     * {@link AssetFileDescriptor} instead.
     *
     * @return the opened {@link AssetFileDescriptor} the caller must close, or {@code null} if the
     * real-path data source was used (nothing to close).
     * @throws IOException if no data source could be established at all.
     */
    @Nullable
    private static AssetFileDescriptor openExtractorDataSource(
        @NonNull Context context,
        @NonNull Uri sourceUri,
        @NonNull MediaExtractor extractor
    ) throws IOException {
        final String sourcePath = FileUtil.getRealPathFromURI(context, sourceUri);
        if (sourcePath != null) {
            try {
                extractor.setDataSource(sourcePath);
                return null;
            } catch (IOException | IllegalArgumentException e) {
                logger.info("Real-path data source failed, falling back to content descriptor");
            }
        }
        final AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(sourceUri, "r");
        if (afd == null) {
            throw new IOException("Unable to open audio source descriptor");
        }
        try {
            extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
        } catch (IOException | IllegalArgumentException e) {
            try {
                afd.close();
            } catch (IOException ignored) {
                // best effort
            }
            throw new IOException("Failed to set audio data source from descriptor", e);
        }
        return afd;
    }

    /**
     * F1Whisper: report whether the audio at {@code sourceUri} can be cropped losslessly, and how.
     *
     * <p>Detection sniffs the real container magic bytes first (never the file extension): an MP3
     * (ID3 tag or MPEG sync), a WAV (RIFF/WAVE) and an Ogg (OggS) are all recognized from their
     * header even if the file is mislabeled. Anything else falls back to the platform
     * {@link MediaExtractor}'s reported MIME so AAC-in-MP4/m4a is detected robustly. A FLAC stream,
     * MIDI, or any codec we can decode-for-waveform but not lossless-crop maps to
     * {@link TrimMethod#UNSUPPORTED}, which forces the caller to fail-safe.
     *
     * @return the {@link TrimMethod} for this source, or {@link TrimMethod#UNSUPPORTED} on any error.
     */
    @WorkerThread
    public static TrimMethod getTrimMethod(@NonNull Context context, @NonNull Uri sourceUri) {
        // 1) Container sniff by magic bytes (extension-independent).
        final TrimMethod sniffed = sniffContainer(context, sourceUri);
        if (sniffed != TrimMethod.UNSUPPORTED) {
            return sniffed;
        }

        // 2) Fall back to the platform extractor's MIME (robust for AAC-in-MP4/m4a, Ogg, etc.).
        MediaExtractor extractor = null;
        AssetFileDescriptor descriptor = null;
        try {
            extractor = new MediaExtractor();
            descriptor = openExtractorDataSource(context, sourceUri, extractor);
            final int audioTrackIndex = selectAudioTrack(extractor);
            if (audioTrackIndex < 0) {
                return TrimMethod.UNSUPPORTED;
            }
            final MediaFormat format = extractor.getTrackFormat(audioTrackIndex);
            final String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                return TrimMethod.UNSUPPORTED;
            }
            // MediaFormat.MIMETYPE_AUDIO_AAC is "audio/mp4a-latm".
            if (mime.equals(MediaFormat.MIMETYPE_AUDIO_AAC) || mime.contains("mp4a") || mime.endsWith("aac")) {
                return TrimMethod.AAC_MP4;
            }
            if (mime.contains("mpeg") && !mime.contains("mp4")) {
                // "audio/mpeg" == MP3.
                return TrimMethod.MP3_FRAMES;
            }
            if ((mime.contains("opus") || mime.contains("vorbis") || mime.endsWith("ogg"))
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return TrimMethod.OGG_MUXER;
            }
            return TrimMethod.UNSUPPORTED;
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            logger.info("Unable to determine the audio trim method", e);
            return TrimMethod.UNSUPPORTED;
        } finally {
            if (extractor != null) {
                extractor.release();
            }
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /**
     * Sniff the container from its first bytes. Returns {@link TrimMethod#UNSUPPORTED} if the magic
     * bytes don't match a directly-cuttable container (the caller then tries the extractor MIME).
     */
    @WorkerThread
    private static TrimMethod sniffContainer(@NonNull Context context, @NonNull Uri sourceUri) {
        try (InputStream in = context.getContentResolver().openInputStream(sourceUri)) {
            if (in == null) {
                return TrimMethod.UNSUPPORTED;
            }
            final byte[] head = new byte[16];
            int total = 0;
            while (total < head.length) {
                final int r = in.read(head, total, head.length - total);
                if (r < 0) {
                    break;
                }
                total += r;
            }
            if (total < 4) {
                return TrimMethod.UNSUPPORTED;
            }

            // WAV: "RIFF"...."WAVE"
            if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && total >= 12 && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E') {
                return TrimMethod.WAV_PCM;
            }
            // MP3: "ID3" tag, or an MPEG-audio frame sync (0xFF 0xEx).
            if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                return TrimMethod.MP3_FRAMES;
            }
            if ((head[0] & 0xff) == 0xff && (head[1] & 0xe0) == 0xe0) {
                return TrimMethod.MP3_FRAMES;
            }
            // FLAC: "fLaC" -> lossless frame trim is infeasible here; mark unsupported (fail-safe).
            if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
                return TrimMethod.UNSUPPORTED;
            }
            // Ogg (Opus/Vorbis): "OggS". MediaMuxer OGG output needs API 29+.
            if (head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') {
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? TrimMethod.OGG_MUXER
                    : TrimMethod.UNSUPPORTED;
            }
            return TrimMethod.UNSUPPORTED;
        } catch (IOException | SecurityException e) {
            logger.info("Container sniff failed; falling back to extractor MIME", e);
            return TrimMethod.UNSUPPORTED;
        }
    }
}
