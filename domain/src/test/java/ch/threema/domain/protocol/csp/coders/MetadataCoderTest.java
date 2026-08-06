package ch.threema.domain.protocol.csp.coders;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;

import androidx.annotation.Nullable;

import ch.threema.base.ThreemaException;
import ch.threema.base.crypto.NonceScope;
import ch.threema.base.utils.Utils;
import ch.threema.domain.helpers.DummyUsers;
import ch.threema.domain.models.MessageId;
import ch.threema.domain.stores.IdentityStore;
import ch.threema.domain.testhelpers.TestHelpers;
import ch.threema.protobuf.csp.e2e.MessageMetadata;

public class MetadataCoderTest {

    private static final String TEST_NICKNAME = "John Doe";

    private static final byte[] TEST_NONCE = Utils.hexStringToByteArray("f0a6de071e2fee0ec5e58637f707c73cd5ba1889db2b89b9");
    private static final byte[] TEST_BOX = Utils.hexStringToByteArray("859031ebffa23b44a55fa7e5e8f05db602eef238ba866a25afbe");

    private static final IdentityStore identityStoreA = DummyUsers.getIdentityStoreForUser(DummyUsers.ALICE);
    private static final IdentityStore identityStoreB = DummyUsers.getIdentityStoreForUser(DummyUsers.BOB);

    @Test
    public void testEncodeDecode() throws ThreemaException, InvalidProtocolBufferException {
        byte[] nonce = TestHelpers.getNoopNonceFactory().nextNonce(NonceScope.CSP);
        MessageId messageId = MessageId.random();

        Date createdAt = new Date();
        MessageMetadata metadata = MessageMetadata.newBuilder()
            .setNickname(TEST_NICKNAME)
            .setCreatedAt(createdAt.getTime())
            .setMessageId(messageId.getMessageIdLong())
            .build();
        MetadataBox box = new MetadataCoder(identityStoreA).encode(metadata, nonce, identityStoreB.getPublicKey());

        MessageMetadata metadataDecoded = new MetadataCoder(identityStoreB).decode(nonce, box, identityStoreA.getPublicKey());

        Assertions.assertEquals(TEST_NICKNAME, metadataDecoded.getNickname());
        Assertions.assertEquals(messageId, new MessageId(metadataDecoded.getMessageId()));
        Assertions.assertEquals(createdAt.getTime(), metadataDecoded.getCreatedAt());
    }

    @Test
    public void testEncodedBox() throws ThreemaException {

        MessageMetadata metadata = MessageMetadata.newBuilder()
            .setNickname(TEST_NICKNAME)
            .build();
        MetadataBox box = new MetadataCoder(identityStoreA).encode(metadata, TEST_NONCE, identityStoreB.getPublicKey());

        Assertions.assertArrayEquals(TEST_BOX, box.getBox());
    }

    // F1Whisper: per-message disappearing timer in the encrypted metadata box.
    // See .claude/tasks/disappearing-per-message-timer-metadata.md

    /**
     * (a) A timer of 30 seconds survives the round trip and is reported as present.
     */
    @Test
    public void testDisappearingTimerRoundTrip() throws ThreemaException, InvalidProtocolBufferException {
        byte[] nonce = TestHelpers.getNoopNonceFactory().nextNonce(NonceScope.CSP);

        MessageMetadata metadata = MessageMetadata.newBuilder()
            .setNickname(TEST_NICKNAME)
            .setF1DisappearingTimer(30)
            .build();
        MetadataBox box = new MetadataCoder(identityStoreA).encode(metadata, nonce, identityStoreB.getPublicKey());

        MessageMetadata decoded = new MetadataCoder(identityStoreB).decode(nonce, box, identityStoreA.getPublicKey());

        Assertions.assertTrue(decoded.hasF1DisappearingTimer(), "timer must be reported as present");
        Assertions.assertEquals(30, decoded.getF1DisappearingTimer());
    }

    /**
     * (b) An explicit zero, meaning "the sender turned the timer off", must decode as present-and-zero,
     * not as absent. The whole per-message design rests on that distinction: absent means the
     * sender advertised nothing and the receiver falls back to its local setting, whereas zero
     * means the receiver must never fall back.
     */
    @Test
    public void testDisappearingTimerZeroIsPresentNotAbsent() throws ThreemaException, InvalidProtocolBufferException {
        byte[] nonce = TestHelpers.getNoopNonceFactory().nextNonce(NonceScope.CSP);

        MessageMetadata metadata = MessageMetadata.newBuilder()
            .setNickname(TEST_NICKNAME)
            .setF1DisappearingTimer(0)
            .build();
        MetadataBox box = new MetadataCoder(identityStoreA).encode(metadata, nonce, identityStoreB.getPublicKey());

        MessageMetadata decoded = new MetadataCoder(identityStoreB).decode(nonce, box, identityStoreA.getPublicKey());

        Assertions.assertTrue(decoded.hasF1DisappearingTimer(), "an explicit 0 must be present, not absent");
        Assertions.assertEquals(0, decoded.getF1DisappearingTimer());
    }

    /**
     * (c) Metadata produced by a client that predates the field still parses, and reports the
     * field absent.
     */
    @Test
    public void testMetadataWithoutDisappearingTimerParsesAsAbsent() throws ThreemaException, InvalidProtocolBufferException {
        byte[] nonce = TestHelpers.getNoopNonceFactory().nextNonce(NonceScope.CSP);
        MessageId messageId = MessageId.random();

        MessageMetadata metadata = MessageMetadata.newBuilder()
            .setNickname(TEST_NICKNAME)
            .setMessageId(messageId.getMessageIdLong())
            .build();
        MetadataBox box = new MetadataCoder(identityStoreA).encode(metadata, nonce, identityStoreB.getPublicKey());

        MessageMetadata decoded = new MetadataCoder(identityStoreB).decode(nonce, box, identityStoreA.getPublicKey());

        Assertions.assertFalse(decoded.hasF1DisappearingTimer(), "an old client's metadata must report the field absent");
        Assertions.assertEquals(0, decoded.getF1DisappearingTimer(), "the protobuf default is 0, which is why has...() must be used");
        Assertions.assertEquals(TEST_NICKNAME, decoded.getNickname());
        Assertions.assertEquals(messageId, new MessageId(decoded.getMessageId()));
    }

    /**
     * (d) The serialized length of the metadata, and therefore the length of the encrypted box the
     * relay sees, must not vary with the presence or the value of the timer. Otherwise the
     * ciphertext length alone would reveal whether disappearing messages are on for a conversation
     * and roughly how large the timer is.
     */
    @Test
    public void testSerializedLengthIsIndependentOfTheDisappearingTimer() throws ThreemaException {
        // The invariant is split across two files (base padding in MessageCoder, compensation in
        // MetadataCoder) and rests on the padding length staying inside a one-byte varint. Exercise
        // both ends of the range MessageCoder's rule can produce, not just one nickname, so that a
        // future change to the padding rule cannot reopen the channel with this test still green.
        assertLengthInvariant("", "empty nickname (base padding at its maximum, 16)");
        assertLengthInvariant(TEST_NICKNAME, "ordinary nickname (base padding 8)");
        assertLengthInvariant(
            "a-very-long-nickname-well-over-sixteen-bytes",
            "nickname longer than 16 bytes (base padding at its minimum, 0)"
        );
        assertLengthInvariant(null, "no nickname, i.e. user profile distribution off (base padding 16)");
    }

    /**
     * Asserts that neither the serialized metadata nor the encrypted box changes length across the
     * whole range of timer values, for one nickname shape.
     */
    private void assertLengthInvariant(@Nullable String nickname, String label) throws ThreemaException {
        // Fixed message id and creation timestamp: both are variable-length on the wire, so they
        // would otherwise mask what this test is measuring.
        final long messageId = 0x0123456789abcdefL;
        final long createdAt = 1_785_232_707_650L;

        final Integer[] timers = {null, 0, 1, 30, 300, 604800, Integer.MAX_VALUE, -1};

        MessageMetadata reference = buildPaddedMetadata(messageId, createdAt, nickname, null);
        int expectedSize = reference.getSerializedSize();
        int expectedBoxLength = encodeBox(reference).getBox().length;

        for (Integer timer : timers) {
            MessageMetadata metadata = buildPaddedMetadata(messageId, createdAt, nickname, timer);
            Assertions.assertEquals(
                expectedSize,
                metadata.getSerializedSize(),
                "serialized length must not vary with timer=" + timer + " [" + label + "]"
            );
            // The encrypted box is length-preserving, so the same invariant must hold end to end.
            Assertions.assertEquals(
                expectedBoxLength,
                encodeBox(metadata).getBox().length,
                "encrypted box length must not vary with timer=" + timer + " [" + label + "]"
            );
        }
    }

    /**
     * The compensation only holds the total constant while the padding length stays inside a
     * one-byte varint, so a base padding that could push it over must be rejected rather than
     * silently reopening the length side channel.
     */
    @Test
    public void testCompensatedPaddingRejectsABaseLengthItCannotKeepInvariant() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> MetadataCoder.f1CompensatedPaddingLength(127, 30),
            "a base padding that leaves no room for the timer budget must be rejected"
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> MetadataCoder.f1CompensatedPaddingLength(-1, 30),
            "a negative base padding must be rejected"
        );
        // The two lengths MessageCoder actually asks for must remain accepted.
        Assertions.assertTrue(MetadataCoder.f1CompensatedPaddingLength(0, 30) > 0);
        Assertions.assertTrue(MetadataCoder.f1CompensatedPaddingLength(16, null) > 0);
    }

    private MetadataBox encodeBox(MessageMetadata metadata) throws ThreemaException {
        return new MetadataCoder(identityStoreA).encode(metadata, TEST_NONCE, identityStoreB.getPublicKey());
    }

    /**
     * Builds a metadata the way {@code MessageCoder.encode()} does: nickname-driven padding plus
     * the compensation for the disappearing-timer field. A {@code null} nickname mirrors the branch
     * taken when the message does not distribute the user profile.
     */
    private static MessageMetadata buildPaddedMetadata(
        long messageId,
        long createdAt,
        @Nullable String nickname,
        @Nullable Integer timerSeconds
    ) {
        MessageMetadata.Builder builder = MessageMetadata.newBuilder()
            .setMessageId(messageId)
            .setCreatedAt(createdAt);
        if (timerSeconds != null) {
            builder.setF1DisappearingTimer(timerSeconds);
        }
        // Deliberately mirrors MessageCoder's expression, platform default charset included, so this
        // measures what production actually emits. Every nickname used here is ASCII, so the choice
        // of charset does not change the result today.
        int basePadding = nickname != null ? Math.max(0, 16 - nickname.getBytes().length) : 16;
        builder.setPadding(ByteString.copyFrom(
            new byte[MetadataCoder.f1CompensatedPaddingLength(basePadding, timerSeconds)]
        ));
        if (nickname != null) {
            builder.setNickname(nickname);
        }
        return builder.build();
    }
}
