package ch.threema.domain.protocol.csp.coders;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.base.ThreemaException;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import ch.threema.domain.stores.IdentityStore;
import ch.threema.libthreema.CryptoException;
import ch.threema.libthreema.LibthreemaKt;
import ch.threema.protobuf.csp.e2e.MessageMetadata;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;

import ch.threema.base.crypto.NaCl;

import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;

public class MetadataCoder {

    private static final Logger logger = getThreemaLogger("MetadataCoder");

    /**
     * F1Whisper: the largest number of bytes {@code f1_disappearing_timer} can occupy on the wire
     * (2 tag bytes for field number 100 plus a 5-byte varint, which is what a value with the high
     * bit set costs when encoded as a {@code uint32}), plus one spare byte.
     * <p>
     * The spare byte guarantees the compensating padding is never zero-length. That matters:
     * protobuf omits an empty {@code bytes} field entirely, so a padding length of {@code 0} costs
     * 0 bytes while a length of {@code 1} costs 3: a discontinuity that would defeat the whole
     * compensation.
     */
    private static final int F1_TIMER_PADDING_BUDGET =
        CodedOutputStream.computeUInt32Size(MessageMetadata.F1_DISAPPEARING_TIMER_FIELD_NUMBER, -1) + 1;

    /**
     * F1Whisper: the largest base padding length for which the compensation below still holds the
     * total length constant.
     * <p>
     * The compensation works by moving bytes between {@code f1_disappearing_timer} and
     * {@code padding}, which only cancels out while {@code padding}'s own <i>length prefix</i> stays
     * the same width. That prefix is a varint, so it is 1 byte up to 127 and 2 bytes from 128. Since
     * the compensated length varies by up to {@link #F1_TIMER_PADDING_BUDGET} bytes with the timer's
     * value, a base padding close to that boundary would let the timer push the length prefix across
     * it and the channel would reopen. Staying at or below this bound keeps the prefix 1 byte for
     * every possible timer.
     * <p>
     * {@code MessageCoder}'s current nickname-driven rule never produces more than 16, so this bound
     * is unreachable today. It exists so that a future change to the padding rule (random padding,
     * block-size padding) fails loudly in {@code MetadataCoderTest} instead of silently reopening the
     * length side channel.
     */
    private static final int F1_MAX_COMPENSABLE_BASE_PADDING = 127 - F1_TIMER_PADDING_BUDGET;

    private final IdentityStore identityStore;

    public MetadataCoder(IdentityStore identityStore) {
        this.identityStore = identityStore;
    }

    public MetadataBox encode(@NonNull MessageMetadata metadata, byte[] nonce, byte[] publicKey) throws ThreemaException {
        final byte[] box;
        try {
            box = NaCl.symmetricEncryptData(metadata.toByteArray(), deriveMetadataKey(publicKey), nonce);
        } catch (CryptoException cryptoException) {
            throw new ThreemaException("Failed to encrypt data", cryptoException);
        }
        return new MetadataBox(box);
    }

    public MessageMetadata decode(byte[] nonce, @NonNull MetadataBox metadataBox, byte[] publicKey) throws InvalidProtocolBufferException, ThreemaException {
        final @NonNull byte[] pb;
        try {
            pb = NaCl.symmetricDecryptData(metadataBox.getBox(), deriveMetadataKey(publicKey), nonce);
        } catch (CryptoException cryptoException) {
            throw new ThreemaException("Metadata decryption failed", cryptoException);
        }
        return MessageMetadata.parseFrom(pb);
    }

    /**
     * F1Whisper: how many padding bytes must be added on top of the nickname-driven padding so
     * that the serialized length of a {@code MessageMetadata} does not vary with the presence or
     * the value of {@code f1_disappearing_timer}.
     * <p>
     * <b>The side channel this closes.</b> The metadata box is encrypted, but its <i>length</i> is
     * not: NaCl secretbox is length-preserving (+16 bytes of tag), so the relay, and anyone
     * observing the connection, sees the exact serialized length of the protobuf. Emitting the
     * timer field unconditionally would grow every metadata box by 3 bytes when the timer is off
     * and by 3 to 7 bytes depending on the timer's magnitude, so the ciphertext length alone would
     * reveal whether disappearing messages are enabled for a conversation and roughly how large
     * the timer is. Handing the unused part of a fixed budget back to {@code padding}, which the
     * receiver ignores by definition, keeps the total constant.
     * <p>
     * With this compensation the padding field always encodes as {@code 1} tag byte plus {@code 1}
     * length byte plus its content (the length never exceeds 127), so
     * {@code padding + f1_disappearing_timer} always costs exactly
     * {@code 2 + basePadding + F1_TIMER_PADDING_BUDGET} bytes.
     *
     * @param disappearingTimerSeconds the value that will be set on the metadata, or {@code null}
     *                                 if the field will be left unset
     * @return a strictly positive number of extra padding bytes
     */
    private static int f1TimerPaddingCompensation(@Nullable Integer disappearingTimerSeconds) {
        if (disappearingTimerSeconds == null) {
            return F1_TIMER_PADDING_BUDGET;
        }
        return F1_TIMER_PADDING_BUDGET - CodedOutputStream.computeUInt32Size(
            MessageMetadata.F1_DISAPPEARING_TIMER_FIELD_NUMBER,
            disappearingTimerSeconds
        );
    }

    /**
     * F1Whisper: the padding length to set on a {@code MessageMetadata}, given the padding the
     * nickname rule asks for and the disappearing timer that will be advertised alongside it.
     * <p>
     * This is the single place where the two halves of the length invariant meet, so that a caller
     * cannot apply one without the other. Use it instead of adding
     * {@link #f1TimerPaddingCompensation} onto a base length by hand.
     *
     * @param basePaddingLength        the nickname-driven padding length, before compensation
     * @param disappearingTimerSeconds the value that will be set on the metadata, or {@code null}
     *                                 if the field will be left unset
     * @return a strictly positive padding length that holds the serialized metadata length constant
     * @throws IllegalArgumentException if {@code basePaddingLength} is negative or large enough that
     *                                  compensation can no longer hold the length constant. Both are
     *                                  unreachable with the current padding rule and indicate that
     *                                  the rule changed without revisiting the invariant.
     */
    public static int f1CompensatedPaddingLength(int basePaddingLength, @Nullable Integer disappearingTimerSeconds) {
        if (basePaddingLength < 0 || basePaddingLength > F1_MAX_COMPENSABLE_BASE_PADDING) {
            throw new IllegalArgumentException(
                "Base metadata padding " + basePaddingLength + " is outside the range this "
                    + "compensation can keep length-invariant (0.." + F1_MAX_COMPENSABLE_BASE_PADDING
                    + "); the disappearing-timer length side channel would reopen"
            );
        }
        return basePaddingLength + f1TimerPaddingCompensation(disappearingTimerSeconds);
    }

    @NonNull
    private byte[] deriveMetadataKey(byte[] publicKey) throws ThreemaException {
        byte[] sharedSecret = identityStore.calcSharedSecret(publicKey);
        try {
            return LibthreemaKt.blake2bMac256(
                sharedSecret,
                "3ma-csp".getBytes(StandardCharsets.UTF_8),
                "mm".getBytes(StandardCharsets.UTF_8),
                new byte[0]
            );
        } catch (CryptoException cryptoException) {
            logger.error("Failed to compute blake2b hash", cryptoException);
            throw new ThreemaException("Failed to compute blake2b hash", cryptoException);
        }
    }
}
