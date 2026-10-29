package ch.threema.app.voicemessage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * F1Whisper (fork review M-05): malformed RIFF/WAV inputs must be handled as normal parse
 * failures ({@code trimStream} returns {@code false}; the caller then keeps the original,
 * untrimmed audio — the feature is fail-safe), never as unchecked crashes. The historical defect:
 * a declared {@code fmt } chunk smaller than 16 bytes was accepted, then fixed offsets through
 * byte 15 were read — an ArrayIndexOutOfBoundsException.
 */
public class WavAudioTrimmerMalformedInputTest {

    private static void putUInt32Le(byte[] b, int off, long v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static void putUInt16Le(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
    }

    private static byte[] riffHeader() {
        byte[] header = new byte[12];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        putUInt32Le(header, 4, 0xFFFF); // declared RIFF size — not validated against content
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        return header;
    }

    private static byte[] chunk(String id, byte[] payload, long declaredSize) {
        byte[] c = new byte[8 + payload.length];
        for (int i = 0; i < 4; i++) {
            c[i] = (byte) id.charAt(i);
        }
        putUInt32Le(c, 4, declaredSize);
        System.arraycopy(payload, 0, c, 8, payload.length);
        return c;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    /** Canonical 16-byte PCM fmt payload: mono, 8000 Hz, 16-bit. */
    private static byte[] pcmFmtPayload() {
        byte[] fmt = new byte[16];
        putUInt16Le(fmt, 0, 1);       // audioFormat = PCM
        putUInt16Le(fmt, 2, 1);       // channels
        putUInt32Le(fmt, 4, 8000);    // sampleRate
        putUInt32Le(fmt, 8, 16000);   // byteRate
        putUInt16Le(fmt, 12, 2);      // blockAlign
        putUInt16Le(fmt, 14, 16);     // bitsPerSample
        return fmt;
    }

    private boolean runTrim(byte[] wavBytes) throws IOException {
        // context/sourceUri are unused by trimStream; a 1s window is a valid trim request.
        WavAudioTrimmer trimmer = new WavAudioTrimmer(null, null, 0, 1000);
        return trimmer.trimStream(
            new ByteArrayInputStream(wavBytes),
            new BufferedOutputStream(new ByteArrayOutputStream())
        );
    }

    @Test
    public void fmtChunkSmallerThan16BytesFailsInsteadOfCrashing() throws IOException {
        // The historical AIOOBE input: fmt declares (and carries) only 8 bytes.
        byte[] wav = concat(riffHeader(), chunk("fmt ", new byte[8], 8));
        assertFalse(runTrim(wav));
    }

    @Test
    public void truncatedFmtChunkFails() throws IOException {
        // fmt declares 16 bytes but the stream ends after 4.
        byte[] wav = concat(riffHeader(), chunk("fmt ", new byte[4], 16));
        assertFalse(runTrim(wav));
    }

    @Test
    public void dataChunkBeforeFmtFails() throws IOException {
        byte[] wav = concat(riffHeader(), chunk("data", new byte[32], 32));
        assertFalse(runTrim(wav));
    }

    @Test
    public void truncatedChunkHeaderFails() throws IOException {
        // A dangling 3-byte fragment where the next chunk header should be.
        byte[] wav = concat(riffHeader(), new byte[]{'f', 'm', 't'});
        assertFalse(runTrim(wav));
    }

    @Test
    public void zeroSizeFmtChunkFails() throws IOException {
        byte[] wav = concat(riffHeader(), chunk("fmt ", new byte[0], 0));
        assertFalse(runTrim(wav));
    }

    @Test
    public void wellFormedPcmWavStillTrims() throws IOException {
        // Happy-path sanity: 8000 Hz * 2 bytes/frame * 2 s of silence; trimming the first second
        // must still succeed after the bounds hardening.
        byte[] pcm = new byte[8000 * 2 * 2];
        byte[] wav = concat(
            riffHeader(),
            chunk("fmt ", pcmFmtPayload(), 16),
            chunk("data", pcm, pcm.length)
        );
        assertTrue(runTrim(wav));
    }
}
