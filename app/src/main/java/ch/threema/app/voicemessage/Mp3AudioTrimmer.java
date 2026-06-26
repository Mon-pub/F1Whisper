package ch.threema.app.voicemessage;

import android.content.Context;
import android.net.Uri;

import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

/**
 * F1Whisper: lossless MP3 (MPEG-1/2/2.5 Audio Layer I/II/III) trimmer.
 *
 * <p>{@link android.media.MediaMuxer} cannot OUTPUT an MP3 stream, so an MP3 cannot be remuxed the
 * way AAC-in-MP4 is. Instead this performs a true lossless cut on MPEG-audio frame boundaries:
 *
 * <ol>
 *   <li>Skip a leading ID3v2 tag if present (so we don't mistake tag bytes for audio).</li>
 *   <li>Walk the stream frame by frame. Each frame header (4 bytes, starting with an 11-bit sync
 *       {@code 0xFFE}) encodes the MPEG version, layer, bitrate, sample rate and padding bit, from
 *       which we derive the exact frame byte length AND its playback duration.</li>
 *   <li>Accumulate elapsed time and copy verbatim every frame whose time window intersects the
 *       requested {@code [startTimeMs, endTimeMs]} range.</li>
 * </ol>
 *
 * <p>This is byte-exact (no decode/re-encode, no generation loss) and produces a standalone,
 * playable MP3 because each MPEG-audio frame is self-contained. The (rare) MP3 "bit reservoir" of
 * Layer III can make the very first kept frame depend on a few preceding frames; in practice
 * players tolerate the tiny ramp-up and the cut stays accurate to a single frame (~26 ms at
 * 44.1 kHz). A leading Xing/Info/VBR header frame is intentionally NOT copied (its frame count
 * would be wrong for the cropped clip); decoders treat its absence as a normal CBR/VBR stream.
 *
 * <p><b>Real-world robustness:</b> production MP3s contain encoder quirks, trailing metadata
 * (ID3v1/APE), and the occasional stray byte that breaks naive frame walking. Rather than aborting
 * the whole trim on the first non-sync byte, this re-synchronizes: when an expected header is not a
 * valid frame it scans forward byte-by-byte (bounded by {@link #MAX_RESYNC_BYTES}) for the next
 * header that is BOTH internally valid AND back-to-back consistent with the frame after it (the
 * standard "double sync" check). Only a genuinely unparseable stream (no recoverable sync within
 * the bound) makes the caller fail-safe.
 *
 * <p>minSdk 24: uses {@code java.io} streams only, never {@code java.nio.file}.
 */
public final class Mp3AudioTrimmer {
    private static final Logger logger = getThreemaLogger("Mp3AudioTrimmer");

    // MPEG-audio bitrate table [versionIndex][layerIndex][bitrateIndex] in kbps.
    // versionIndex: 0 = MPEG2/2.5, 1 = MPEG1. layerIndex: 0 = Layer III, 1 = Layer II, 2 = Layer I.
    private static final int[][][] BITRATE_TABLE = {
        // MPEG 2 & 2.5
        {
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0},   // Layer III
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0},   // Layer II
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0}, // Layer I
        },
        // MPEG 1
        {
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0}, // Layer III
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0}, // Layer II
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0}, // Layer I
        },
    };

    // Sample-rate table [versionField][sampleRateIndex] in Hz.
    // versionField: 0 = MPEG2.5, 2 = MPEG2, 3 = MPEG1 (1 is reserved).
    private static final int[][] SAMPLE_RATE_TABLE = {
        {11025, 12000, 8000, 0},  // MPEG 2.5
        {0, 0, 0, 0},             // reserved
        {22050, 24000, 16000, 0}, // MPEG 2
        {44100, 48000, 32000, 0}, // MPEG 1
    };

    // Samples per frame [layerIndex][isMpeg1]. layerIndex: 0 = III, 1 = II, 2 = I.
    // NOTE: this drives ONLY playback duration. The frame BYTE SIZE is computed from explicit
    // per-version/layer coefficients in computeFrameLengthBytes() (matching the MPEG spec /
    // ExoPlayer's MpegAudioUtil), NOT from samplesPerFrame/8.
    private static final int[][] SAMPLES_PER_FRAME = {
        {576, 1152}, // Layer III: MPEG2/2.5 = 576, MPEG1 = 1152
        {1152, 1152}, // Layer II
        {384, 384},  // Layer I
    };

    // Upper bound on the forward byte-scan when re-synchronizing after a lost frame boundary.
    // Matches ExoPlayer's Mp3Extractor sync budget; comfortably larger than any single frame or
    // inter-frame junk run, but bounded so a non-MP3 / shredded stream still fails fast.
    private static final int MAX_RESYNC_BYTES = 128 * 1024;

    private final Context context;
    private final Uri sourceUri;
    private final long startTimeMs;
    private final long endTimeMs;

    public Mp3AudioTrimmer(@NonNull Context context, @NonNull Uri sourceUri, long startTimeMs, long endTimeMs) {
        this.context = context;
        this.sourceUri = sourceUri;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
    }

    /**
     * Crop the MP3 to {@code [startTimeMs, endTimeMs]} losslessly and write it to
     * {@code destinationFile}.
     *
     * @return true on success, false on any error (caller must fail-safe; never send the original).
     */
    @WorkerThread
    public boolean trim(@NonNull File destinationFile) {
        if (endTimeMs <= startTimeMs) {
            logger.warn("Invalid MP3 trim window: start={}ms end={}ms", startTimeMs, endTimeMs);
            return false;
        }

        boolean success = false;
        try (InputStream in = context.getContentResolver().openInputStream(sourceUri)) {
            if (in == null) {
                logger.warn("Unable to open MP3 source stream");
                return false;
            }
            // Read the whole stream into memory once. Trimmable audio is already capped by
            // MAX_BLOB_SIZE upstream, and an in-memory buffer makes byte-by-byte re-sync exact and
            // cheap (no fragile mark/reset over a BufferedInputStream). minSdk24-safe (java.io only).
            final byte[] data = readAll(in);
            if (data.length < 4) {
                logger.warn("MP3 source too small to contain any audio frame");
                return false;
            }
            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(destinationFile))) {
                success = trimBuffer(data, out);
            }
        } catch (IOException e) {
            logger.error("I/O error while trimming MP3", e);
            success = false;
        } catch (OutOfMemoryError e) {
            // Pathologically large input: fail-safe rather than crash.
            logger.error("Out of memory while trimming MP3", e);
            success = false;
        }

        if (!success && destinationFile.exists() && !destinationFile.delete()) {
            logger.warn("Failed to delete incomplete MP3 trim output {}", destinationFile.getAbsolutePath());
        }
        return success;
    }

    /**
     * Walk the in-memory MP3 {@code data} frame by frame and copy the frames overlapping the
     * requested window into {@code out}.
     */
    private boolean trimBuffer(@NonNull byte[] data, @NonNull BufferedOutputStream out) throws IOException {
        int pos = skipId3v2(data);

        double elapsedMs = 0.0;
        long writtenFrames = 0L;
        boolean seenFirstAudioFrame = false;

        while (pos + 4 <= data.length) {
            FrameInfo frame = parseFrameAt(data, pos);

            if (frame == null) {
                // The bytes at pos are not a valid frame header. Could be a trailing ID3v1 tag
                // ("TAG", 128 bytes), an APE tag, ID3v2 footer, or a single stray byte mid-stream.
                if (isId3v1At(data, pos) || isApeTagAt(data, pos)) {
                    // Known trailer: we've reached the metadata at the end; stop cleanly.
                    break;
                }
                // Try to re-synchronize: scan forward for the next valid, self-consistent frame.
                final int resynced = findNextFrameSync(data, pos + 1);
                if (resynced < 0) {
                    if (writtenFrames > 0) {
                        // We already emitted whole valid frames and can't find more; the tail is
                        // junk/metadata. Stop cleanly rather than discarding good output.
                        break;
                    }
                    logger.warn("Lost MP3 frame alignment and could not re-synchronize; aborting trim");
                    return false;
                }
                logger.info("Re-synchronized MP3 frame boundary: skipped {} junk byte(s)", resynced - pos);
                pos = resynced;
                continue;
            }

            // A truncated final frame: copy nothing more, stop cleanly (we keep whole frames only).
            if (pos + frame.frameLengthBytes > data.length) {
                break;
            }

            final double frameStartMs = elapsedMs;
            final double frameEndMs = elapsedMs + frame.durationMs;

            // A Xing/Info/VBRI header lives in the first audio frame and carries a (now-wrong) frame
            // count. Skip copying it but still advance time so timestamps stay correct. It is only a
            // metadata frame; a decoder plays the clip fine without it.
            final boolean isVbrHeaderFrame = !seenFirstAudioFrame
                && isVbrHeaderFrame(data, pos, frame.frameLengthBytes);
            seenFirstAudioFrame = true;

            // Copy any frame that overlaps the requested window.
            if (!isVbrHeaderFrame && frameEndMs > startTimeMs && frameStartMs < endTimeMs) {
                out.write(data, pos, frame.frameLengthBytes);
                writtenFrames++;
            }

            elapsedMs = frameEndMs;
            pos += frame.frameLengthBytes;
            if (frameStartMs >= endTimeMs) {
                break; // past the requested end
            }
        }

        out.flush();
        if (writtenFrames == 0) {
            logger.warn("MP3 trim produced no frames for window [{}ms, {}ms]", startTimeMs, endTimeMs);
            return false;
        }
        logger.info("Trimmed MP3: wrote {} frames for window [{}ms, {}ms]", writtenFrames, startTimeMs, endTimeMs);
        return true;
    }

    /**
     * Skip a leading ID3v2 tag if present. ID3v2 header = "ID3" + 2 version bytes + 1 flags byte +
     * 4 syncsafe size bytes (7 bits each). The size excludes the 10-byte header (and an optional
     * 10-byte footer if the footer flag is set).
     *
     * @return the byte offset of the first audio byte (0 if there is no ID3v2 tag).
     */
    private static int skipId3v2(@NonNull byte[] data) {
        if (data.length >= 10 && data[0] == 'I' && data[1] == 'D' && data[2] == '3') {
            final int size =
                ((data[6] & 0x7f) << 21) |
                    ((data[7] & 0x7f) << 14) |
                    ((data[8] & 0x7f) << 7) |
                    (data[9] & 0x7f);
            final boolean hasFooter = (data[5] & 0x10) != 0;
            final long end = 10L + size + (hasFooter ? 10L : 0L);
            // Clamp into bounds; a corrupt size must never push the cursor past EOF.
            return (int) Math.min(end, data.length);
        }
        return 0;
    }

    /**
     * Scan forward from {@code from} for the next valid, self-consistent MPEG-audio frame header,
     * bounded by {@link #MAX_RESYNC_BYTES}. "Self-consistent" means the candidate frame's trailing
     * boundary is ALSO a valid frame header (or end-of-stream) - the classic double-sync check that
     * rejects a sync pattern that happens to appear inside frame payload.
     *
     * @return the offset of the recovered frame header, or -1 if none was found within the bound.
     */
    private static int findNextFrameSync(@NonNull byte[] data, int from) {
        final int limit = Math.min(data.length - 4, from + MAX_RESYNC_BYTES);
        for (int i = Math.max(from, 0); i <= limit; i++) {
            if ((data[i] & 0xff) != 0xff || (data[i + 1] & 0xe0) != 0xe0) {
                continue;
            }
            final FrameInfo frame = parseFrameAt(data, i);
            if (frame == null) {
                continue;
            }
            final int next = i + frame.frameLengthBytes;
            // Accept if the next boundary is end-of-stream, a known trailer, or another valid frame
            // header. This double check makes a false-positive sync inside audio data vanishingly
            // unlikely while still recovering from a single corrupt frame.
            if (next + 4 > data.length
                || isId3v1At(data, next)
                || isApeTagAt(data, next)
                || parseFrameAt(data, next) != null) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isId3v1At(@NonNull byte[] data, int pos) {
        return pos >= 0 && pos + 3 <= data.length
            && data[pos] == 'T' && data[pos + 1] == 'A' && data[pos + 2] == 'G';
    }

    private static boolean isApeTagAt(@NonNull byte[] data, int pos) {
        // APEv1/APEv2 header or footer both start with the 8-byte preamble "APETAGEX".
        if (pos < 0 || pos + 8 > data.length) {
            return false;
        }
        return data[pos] == 'A' && data[pos + 1] == 'P' && data[pos + 2] == 'E'
            && data[pos + 3] == 'T' && data[pos + 4] == 'A' && data[pos + 5] == 'G'
            && data[pos + 6] == 'E' && data[pos + 7] == 'X';
    }

    /**
     * Parse the 4-byte MPEG-audio frame header at {@code pos} and compute its byte length and
     * duration. Returns {@code null} if the bytes at {@code pos} are not a valid frame header.
     */
    @Nullable
    private static FrameInfo parseFrameAt(@NonNull byte[] data, int pos) {
        if (pos < 0 || pos + 4 > data.length) {
            return null;
        }
        // 11-bit frame sync: first byte 0xFF, top 3 bits of the second byte set.
        if ((data[pos] & 0xff) != 0xff || (data[pos + 1] & 0xe0) != 0xe0) {
            return null;
        }

        final int b1 = data[pos + 1] & 0xff;
        final int b2 = data[pos + 2] & 0xff;

        final int versionField = (b1 >> 3) & 0x03; // 0=2.5,1=reserved,2=2,3=1
        final int layerField = (b1 >> 1) & 0x03;   // 0=reserved,1=III,2=II,3=I
        if (versionField == 1 || layerField == 0) {
            return null; // reserved / invalid
        }

        final int bitrateIndex = (b2 >> 4) & 0x0f;
        final int sampleRateIndex = (b2 >> 2) & 0x03;
        final int paddingBit = (b2 >> 1) & 0x01;
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) {
            return null; // "free"/reserved bitrate or reserved sample rate: not frame-sizeable
        }

        final boolean isMpeg1 = versionField == 3;
        // MPEG layer field (header bits 17-18): 01 = Layer III, 10 = Layer II, 11 = Layer I.
        // Our table convention is index 0 = III, 1 = II, 2 = I, so layerIndex = layerField - 1.
        // (The previous 3 - layerField swapped Layer III and Layer I: a normal MP3 -- which is
        // Layer III, layerField 1 -- mapped to the Layer I index, looked up the Layer I bitrate
        // table + Layer I frame-size formula + Layer I sample count, and so computed a wrong frame
        // length. The cursor then advanced by the wrong number of bytes and landed mid-frame on the
        // next iteration -> "Lost MP3 frame alignment". layerField 0 was already rejected above.)
        final int layerIndex = layerField - 1; // 1=III->0, 2=II->1, 3=I->2
        final int versionTableIndex = isMpeg1 ? 1 : 0;

        final int bitrateKbps = BITRATE_TABLE[versionTableIndex][layerIndex][bitrateIndex];
        final int sampleRate = SAMPLE_RATE_TABLE[versionField][sampleRateIndex];
        if (bitrateKbps == 0 || sampleRate == 0) {
            return null;
        }

        final int bitrate = bitrateKbps * 1000;
        final int frameLengthBytes = computeFrameLengthBytes(layerIndex, isMpeg1, bitrate, sampleRate, paddingBit);
        if (frameLengthBytes <= 4) {
            return null;
        }

        final int samplesPerFrame = SAMPLES_PER_FRAME[layerIndex][isMpeg1 ? 1 : 0];
        final double durationMs = samplesPerFrame * 1000.0 / sampleRate;

        final FrameInfo info = new FrameInfo();
        info.frameLengthBytes = frameLengthBytes;
        info.durationMs = durationMs;
        return info;
    }

    /**
     * Exact MPEG-audio frame size in bytes, per the spec coefficients (cf. ExoPlayer
     * {@code MpegAudioUtil.Header.setForHeaderData}):
     *
     * <ul>
     *   <li>Layer I  (all versions): {@code (12 * bitrate / sampleRate + padding) * 4}
     *       (slot size = 4 bytes).</li>
     *   <li>Layer II (all versions): {@code 144 * bitrate / sampleRate + padding}.</li>
     *   <li>Layer III, MPEG1:        {@code 144 * bitrate / sampleRate + padding}.</li>
     *   <li>Layer III, MPEG2/2.5:    {@code 72  * bitrate / sampleRate + padding}
     *       (half the samples per frame -> half the coefficient).</li>
     * </ul>
     *
     * <p>This explicit form replaces the previous {@code (samplesPerFrame / 8)} derivation, which,
     * while numerically equal for these cases, hid the per-version distinction and was brittle to
     * extend. The slot/coefficient is now matched directly to the spec.
     *
     * @param layerIndex 0 = Layer III, 1 = Layer II, 2 = Layer I.
     */
    private static int computeFrameLengthBytes(
        int layerIndex, boolean isMpeg1, int bitrate, int sampleRate, int paddingBit) {
        switch (layerIndex) {
            case 2: // Layer I: slot = 4 bytes.
                return (12 * bitrate / sampleRate + paddingBit) * 4;
            case 1: // Layer II: 144 for every MPEG version.
                return 144 * bitrate / sampleRate + paddingBit;
            case 0: // Layer III: 144 for MPEG1, 72 for MPEG2/2.5.
            default:
                final int coefficient = isMpeg1 ? 144 : 72;
                return coefficient * bitrate / sampleRate + paddingBit;
        }
    }

    /**
     * Detect a Xing/Info (Layer III) or VBRI metadata frame whose payload should not be copied into
     * the cropped clip (its embedded frame/byte counts would be wrong). The tag lives inside the
     * frame body, i.e. after the 4-byte header within {@code [pos, pos+frameLengthBytes)}.
     */
    private static boolean isVbrHeaderFrame(@NonNull byte[] data, int pos, int frameLengthBytes) {
        final int bodyStart = pos + 4;
        final int bodyEnd = Math.min(pos + frameLengthBytes, data.length);
        final int bodyLength = bodyEnd - bodyStart;
        if (bodyLength < 4) {
            return false;
        }
        // VBRI sits at a fixed offset 32 bytes after the 4-byte header (i.e. body offset 28).
        if (bodyLength >= 32 && containsTagAt(data, bodyStart + 28, "VBRI")) {
            return true;
        }
        // Xing/Info tag offset depends on MPEG version + channel mode; scan the body for it.
        final int scanLimit = Math.min(bodyLength - 4, 40);
        for (int i = 0; i <= scanLimit; i++) {
            if (containsTagAt(data, bodyStart + i, "Xing") || containsTagAt(data, bodyStart + i, "Info")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTagAt(@NonNull byte[] data, int offset, @NonNull String tag) {
        if (offset < 0 || offset + tag.length() > data.length) {
            return false;
        }
        for (int i = 0; i < tag.length(); i++) {
            if (data[offset + i] != (byte) tag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Read the whole stream into a byte array. minSdk24-safe (no {@code InputStream.readAllBytes},
     * which is API 33).
     */
    @NonNull
    private static byte[] readAll(@NonNull InputStream in) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
        final byte[] chunk = new byte[16 * 1024];
        int r;
        while ((r = in.read(chunk)) >= 0) {
            buffer.write(chunk, 0, r);
        }
        return buffer.toByteArray();
    }

    private static final class FrameInfo {
        int frameLengthBytes;
        double durationMs;
    }
}
