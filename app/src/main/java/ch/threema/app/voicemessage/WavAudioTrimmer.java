package ch.threema.app.voicemessage;

import android.content.Context;
import android.net.Uri;

import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

/**
 * F1Whisper: lossless WAV (RIFF/PCM) trimmer.
 *
 * <p>{@link android.media.MediaMuxer} cannot output WAV, and there is nothing to re-encode anyway:
 * WAV is raw PCM. This parses the RIFF container, reads the {@code fmt } chunk (sample rate,
 * channels, bits-per-sample, block align) to map time to a byte offset, then copies the exact PCM
 * byte sub-range for {@code [startTimeMs, endTimeMs]} (snapped to whole sample frames) into a new,
 * minimal canonical WAV (RIFF + fmt + data) with corrected size fields. Byte-exact, no quality loss.
 *
 * <p>minSdk 24: {@code java.io} streams only.
 */
public final class WavAudioTrimmer {
    private static final Logger logger = getThreemaLogger("WavAudioTrimmer");

    private final Context context;
    private final Uri sourceUri;
    private final long startTimeMs;
    private final long endTimeMs;

    public WavAudioTrimmer(@NonNull Context context, @NonNull Uri sourceUri, long startTimeMs, long endTimeMs) {
        this.context = context;
        this.sourceUri = sourceUri;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
    }

    @WorkerThread
    public boolean trim(@NonNull File destinationFile) {
        if (endTimeMs <= startTimeMs) {
            logger.warn("Invalid WAV trim window: start={}ms end={}ms", startTimeMs, endTimeMs);
            return false;
        }

        boolean success = false;
        try (InputStream rawIn = context.getContentResolver().openInputStream(sourceUri)) {
            if (rawIn == null) {
                logger.warn("Unable to open WAV source stream");
                return false;
            }
            try (BufferedInputStream in = new BufferedInputStream(rawIn);
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(destinationFile))) {
                success = trimStream(in, out);
            }
        } catch (IOException e) {
            logger.error("I/O error while trimming WAV", e);
            success = false;
        }

        if (!success && destinationFile.exists() && !destinationFile.delete()) {
            logger.warn("Failed to delete incomplete WAV trim output {}", destinationFile.getAbsolutePath());
        }
        return success;
    }

    private boolean trimStream(@NonNull InputStream in, @NonNull BufferedOutputStream out) throws IOException {
        final byte[] riff = new byte[12];
        if (readFully(in, riff, 0, 12) < 12) {
            logger.warn("WAV too short for a RIFF header");
            return false;
        }
        if (!(riff[0] == 'R' && riff[1] == 'I' && riff[2] == 'F' && riff[3] == 'F'
            && riff[8] == 'W' && riff[9] == 'A' && riff[10] == 'V' && riff[11] == 'E')) {
            logger.warn("Not a RIFF/WAVE file");
            return false;
        }

        int audioFormat = -1;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        int blockAlign = 0;
        byte[] fmtChunk = null;

        // Walk chunks until we find "data". Each chunk = 4-byte id + 4-byte little-endian size.
        final byte[] chunkHeader = new byte[8];
        while (true) {
            if (readFully(in, chunkHeader, 0, 8) < 8) {
                logger.warn("Reached end of WAV before a data chunk");
                return false;
            }
            final String chunkId = new String(chunkHeader, 0, 4, "US-ASCII");
            final long chunkSize = readUInt32Le(chunkHeader, 4);

            if ("fmt ".equals(chunkId)) {
                final int toRead = (int) Math.min(chunkSize, 64);
                fmtChunk = new byte[toRead];
                if (readFully(in, fmtChunk, 0, toRead) < toRead) {
                    logger.warn("Truncated fmt chunk");
                    return false;
                }
                audioFormat = readUInt16Le(fmtChunk, 0);
                channels = readUInt16Le(fmtChunk, 2);
                sampleRate = (int) readUInt32Le(fmtChunk, 4);
                bitsPerSample = readUInt16Le(fmtChunk, 14);
                blockAlign = readUInt16Le(fmtChunk, 12);
                // Skip any fmt bytes beyond what we parsed plus chunk padding.
                long skip = chunkSize - toRead;
                if ((chunkSize & 1L) != 0L) {
                    skip += 1; // chunks are word-aligned
                }
                skipFully(in, skip);
            } else if ("data".equals(chunkId)) {
                if (fmtChunk == null || sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) {
                    logger.warn("WAV data chunk before a valid fmt chunk");
                    return false;
                }
                // Only PCM (1) and IEEE float (3) are raw, byte-range-trimmable. WAVE_FORMAT_EXTENSIBLE
                // (0xFFFE) is also typically PCM but the real format sits in the extension; treat any
                // non-PCM/float as unsupported to stay fail-safe.
                if (audioFormat != 1 && audioFormat != 3) {
                    logger.warn("Unsupported WAV audio format {} (not raw PCM); aborting trim", audioFormat);
                    return false;
                }
                if (blockAlign <= 0) {
                    blockAlign = channels * (bitsPerSample / 8);
                }
                if (blockAlign <= 0) {
                    logger.warn("Invalid WAV block align");
                    return false;
                }
                return copyDataRange(in, out, fmtChunk, sampleRate, blockAlign, chunkSize);
            } else {
                // Unknown chunk (LIST/fact/...): skip its payload (word-aligned).
                long skip = chunkSize;
                if ((chunkSize & 1L) != 0L) {
                    skip += 1;
                }
                skipFully(in, skip);
            }
        }
    }

    private boolean copyDataRange(
        @NonNull InputStream in,
        @NonNull BufferedOutputStream out,
        @NonNull byte[] fmtChunk,
        int sampleRate,
        int blockAlign,
        long dataChunkSize
    ) throws IOException {
        final long bytesPerSecond = (long) sampleRate * blockAlign;
        // Snap the requested window to whole sample frames so we never split a sample.
        long startByte = (startTimeMs * bytesPerSecond) / 1000L;
        long endByte = (endTimeMs * bytesPerSecond) / 1000L;
        startByte -= startByte % blockAlign;
        endByte -= endByte % blockAlign;
        startByte = Math.max(0L, Math.min(startByte, dataChunkSize));
        endByte = Math.max(startByte, Math.min(endByte, dataChunkSize));

        final long trimmedDataSize = endByte - startByte;
        if (trimmedDataSize <= 0L) {
            logger.warn("WAV trim produced no PCM data for window [{}ms, {}ms]", startTimeMs, endTimeMs);
            return false;
        }

        // Skip PCM before the start offset.
        skipFully(in, startByte);

        // Write a canonical RIFF header: "RIFF" + (36 + fmtExtra + dataSize) + "WAVE" then the
        // original fmt chunk verbatim then "data" + dataSize. We re-emit the exact fmt chunk we read
        // so all format nuances (e.g. extensible/float) are preserved.
        final int fmtChunkSize = fmtChunk.length;
        final long riffSize = 4L /* "WAVE" */
            + 8L + fmtChunkSize          /* fmt chunk header + body */
            + 8L + trimmedDataSize;      /* data chunk header + body */

        out.write('R'); out.write('I'); out.write('F'); out.write('F');
        writeUInt32Le(out, riffSize);
        out.write('W'); out.write('A'); out.write('V'); out.write('E');

        out.write('f'); out.write('m'); out.write('t'); out.write(' ');
        writeUInt32Le(out, fmtChunkSize);
        out.write(fmtChunk, 0, fmtChunkSize);

        out.write('d'); out.write('a'); out.write('t'); out.write('a');
        writeUInt32Le(out, trimmedDataSize);

        // Stream the PCM byte range.
        final byte[] buffer = new byte[64 * 1024];
        long remaining = trimmedDataSize;
        while (remaining > 0) {
            final int want = (int) Math.min(buffer.length, remaining);
            final int r = in.read(buffer, 0, want);
            if (r < 0) {
                logger.warn("WAV data ended early while copying trim range");
                return false;
            }
            out.write(buffer, 0, r);
            remaining -= r;
        }
        out.flush();
        logger.info("Trimmed WAV: wrote {} PCM bytes for window [{}ms, {}ms]", trimmedDataSize, startTimeMs, endTimeMs);
        return true;
    }

    private static int readUInt16Le(@NonNull byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static long readUInt32Le(@NonNull byte[] b, int off) {
        return (b[off] & 0xffL)
            | ((b[off + 1] & 0xffL) << 8)
            | ((b[off + 2] & 0xffL) << 16)
            | ((b[off + 3] & 0xffL) << 24);
    }

    private static void writeUInt32Le(@NonNull BufferedOutputStream out, long value) throws IOException {
        out.write((int) (value & 0xff));
        out.write((int) ((value >> 8) & 0xff));
        out.write((int) ((value >> 16) & 0xff));
        out.write((int) ((value >> 24) & 0xff));
    }

    private static int readFully(@NonNull InputStream in, @NonNull byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            final int r = in.read(buf, off + total, len - total);
            if (r < 0) {
                break;
            }
            total += r;
        }
        return total;
    }

    private static void skipFully(@NonNull InputStream in, long toSkip) throws IOException {
        long remaining = toSkip;
        final byte[] scratch = new byte[8192];
        while (remaining > 0) {
            final long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            final int r = in.read(scratch, 0, (int) Math.min(scratch.length, remaining));
            if (r < 0) {
                break;
            }
            remaining -= r;
        }
    }
}
