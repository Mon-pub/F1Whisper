package ch.threema.domain.protocol.csp.coders

import ch.threema.base.crypto.NaCl
import ch.threema.base.crypto.NonceScope
import ch.threema.domain.fs.DHSessionId
import ch.threema.domain.helpers.InMemoryContactStore
import ch.threema.domain.helpers.InMemoryIdentityStore
import ch.threema.domain.models.Contact
import ch.threema.domain.models.GroupId
import ch.threema.domain.models.VerificationLevel
import ch.threema.domain.protocol.csp.messages.AbstractMessage
import ch.threema.domain.protocol.csp.messages.BadMessageException
import ch.threema.domain.protocol.csp.messages.GroupTextMessage
import ch.threema.domain.protocol.csp.messages.TextMessage
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityDataMessage
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityEnvelopeMessage
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityMode
import ch.threema.domain.protocol.csp.messages.voip.VoipCallAnswerData
import ch.threema.domain.protocol.csp.messages.voip.VoipCallAnswerMessage
import ch.threema.domain.protocol.csp.messages.voip.VoipCallHangupData
import ch.threema.domain.protocol.csp.messages.voip.VoipCallHangupMessage
import ch.threema.domain.protocol.csp.messages.voip.VoipCallOfferData
import ch.threema.domain.protocol.csp.messages.voip.VoipCallOfferData.OfferData
import ch.threema.domain.protocol.csp.messages.voip.VoipCallOfferMessage
import ch.threema.domain.protocol.csp.messages.voip.VoipICECandidatesData
import ch.threema.domain.protocol.csp.messages.voip.VoipICECandidatesMessage
import ch.threema.domain.stores.ContactStore
import ch.threema.domain.stores.IdentityStore
import ch.threema.domain.testhelpers.TestHelpers.noopContactStore
import ch.threema.domain.testhelpers.TestHelpers.noopIdentityStore
import ch.threema.domain.testhelpers.TestHelpers.noopNonceFactory
import ch.threema.domain.testhelpers.TestHelpers.setMessageDefaultSenderAndReceiver
import ch.threema.protobuf.csp.e2e.fs.Encapsulated
import ch.threema.protobuf.csp.e2e.fs.Version
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MessageCoderTest {
    private val encoder: MessageCoder
    private val decoder: MessageCoder

    init {

        val myPublicKey = ByteArray(NaCl.PUBLIC_KEY_BYTES)
        val myPrivateKey = ByteArray(NaCl.SECRET_KEY_BYTES)
        val peerPublicKey = ByteArray(NaCl.PUBLIC_KEY_BYTES)
        val peerPrivateKey = ByteArray(NaCl.SECRET_KEY_BYTES)

        NaCl.generateKeypairInPlace(myPublicKey, myPrivateKey)
        NaCl.generateKeypairInPlace(peerPublicKey, peerPrivateKey)

        val myIdentityStore: IdentityStore = InMemoryIdentityStore(
            "01234567",
            null,
            myPrivateKey,
            "Me",
        )

        val peerIdentityStore: IdentityStore = InMemoryIdentityStore(
            "0ABCDEFG",
            null,
            peerPrivateKey,
            "Peer",
        )

        val myContactStore: ContactStore = InMemoryContactStore()
        myContactStore.addContact(Contact("0ABCDEFG", peerPublicKey, VerificationLevel.UNVERIFIED))

        val peerContactStore: ContactStore = InMemoryContactStore()
        peerContactStore.addContact(Contact("01234567", myPublicKey, VerificationLevel.UNVERIFIED))

        encoder = MessageCoder(
            myContactStore,
            myIdentityStore,
        )

        decoder = MessageCoder(
            peerContactStore,
            peerIdentityStore,
        )
    }

    private fun box(msg: AbstractMessage): MessageBox {
        val messageCoder = MessageCoder(
            noopContactStore,
            noopIdentityStore,
        )
        val nonceFactory = noopNonceFactory
        val nonce: ByteArray = nonceFactory.next(NonceScope.CSP).bytes
        return messageCoder.encode(msg, nonce)
    }

    @Test
    fun testVoipFlagsOffer() {
        val voipCallOfferMessage = VoipCallOfferMessage()
        setMessageDefaultSenderAndReceiver(voipCallOfferMessage)
        val voipCallOfferData = VoipCallOfferData()
        val offerData = OfferData()
            .setSdp("testsdp")
            .setSdpType("offer")
        voipCallOfferData.setOfferData(offerData)
        voipCallOfferMessage.data = voipCallOfferData
        val messageBox = this.box(voipCallOfferMessage)
        // Flags: Voip + Push
        assertEquals((0x20 or 0x01).toLong(), messageBox.flags.toLong())
    }

    @Test
    fun testVoipFlagsAnswer() {
        val voipCallAnswerMessage = VoipCallAnswerMessage()
        val voipCallAnswerData = VoipCallAnswerData()
            .setAction(VoipCallAnswerData.Action.REJECT)
            .setAnswerData(null)
            .setRejectReason(VoipCallAnswerData.RejectReason.BUSY)
        voipCallAnswerMessage.data = voipCallAnswerData
        setMessageDefaultSenderAndReceiver(voipCallAnswerMessage)
        val messageBox = this.box(voipCallAnswerMessage)
        // Flags: Voip + Push
        assertEquals((0x20 or 0x01).toLong(), messageBox.flags.toLong())
    }

    @Test
    fun testVoipFlagsCandidates() {
        val voipICECandidatesMessage = VoipICECandidatesMessage()
        val voipICECandidatesData = VoipICECandidatesData()
            .setCandidates(
                arrayOf(
                    VoipICECandidatesData.Candidate("testcandidate1", "testmid1", 42, "testufrag1"),
                    VoipICECandidatesData.Candidate("testcandidate2", "testmid2", 23, "testufrag2"),
                ),
            )
        voipICECandidatesMessage.data = voipICECandidatesData
        setMessageDefaultSenderAndReceiver(voipICECandidatesMessage)
        val messageBox = this.box(voipICECandidatesMessage)
        // Flags: Voip + Push
        assertEquals((0x20 or 0x01).toLong(), messageBox.flags.toLong())
    }

    @Test
    fun testVoipFlagsHangup() {
        val voipCallHangupMessage = VoipCallHangupMessage()
        voipCallHangupMessage.data = VoipCallHangupData()
        setMessageDefaultSenderAndReceiver(voipCallHangupMessage)
        val messageBox = this.box(voipCallHangupMessage)
        // Flags: Push only
        assertEquals(0x01, messageBox.flags.toLong())
    }

    @Test
    fun testDeserializeTextMessage() {
        val textMessage = TextMessage()

        setAndAssertText(textMessage, "Hello")
        assertEqualMessage(textMessage, encodeAndDecode(textMessage))

        setAndAssertText(textMessage, ".")
        assertEqualMessage(textMessage, encodeAndDecode(textMessage))

        setAndAssertText(textMessage, String(Character.toChars(0x1F4A1)))
        assertEqualMessage(textMessage, encodeAndDecode(textMessage))

        setAndAssertText(textMessage, "")

        assertFailsWith<BadMessageException> { encodeAndDecode(textMessage) }
        setAndAssertText(textMessage, "a")
        val body: ByteArray = encode(textMessage).box
        assertFailsWith<BadMessageException> {
            // Invalid offset
            TextMessage.fromByteArray(body, body.size, body.size)
        }

        assertFailsWith<BadMessageException> {
            // Invalid length
            TextMessage.fromByteArray(body, 1, body.size)
        }
    }

    private fun setAndAssertText(message: TextMessage, text: String) {
        message.text = text
        assertEquals(text, message.text)
    }

    @Test
    fun testDeserializeGroupTextMessage() {
        val textMessage = GroupTextMessage()
        textMessage.groupCreator = "01234567"
        textMessage.apiGroupId = GroupId()

        setAndAssertText(textMessage, "Hello")
        assertEqualMessage(textMessage, encodeAndDecode(textMessage))

        setAndAssertText(textMessage, ".")
        assertEqualMessage(textMessage, encodeAndDecode(textMessage))

        setAndAssertText(textMessage, String(Character.toChars(0x1F4A1)))
        assertEqualMessage(textMessage, encodeAndDecode(textMessage))

        setAndAssertText(textMessage, "")
        assertFailsWith<BadMessageException> { encodeAndDecode(textMessage) }

        setAndAssertText(textMessage, "a")
        val body = encode(textMessage).box
        assertFailsWith<BadMessageException> {
            // Invalid offset
            GroupTextMessage.fromByteArray(body, body.size, body.size)
        }

        assertFailsWith<BadMessageException> {
            // Invalid length
            GroupTextMessage.fromByteArray(body, 1, body.size)
        }
    }

    private fun setAndAssertText(message: GroupTextMessage, text: String) {
        message.text = text
        assertEquals(text, message.text)
    }

    // F1Whisper: per-message disappearing timer carried in the encrypted metadata box.
    // See .claude/tasks/disappearing-per-message-timer-metadata.md

    @Test
    fun testDisappearingTimerTravelsInTheMetadataBox() {
        val textMessage = TextMessage()
        textMessage.text = "Hello"
        textMessage.disappearingTimerSeconds = 30

        val decoded = encodeAndDecode(textMessage)

        assertEquals(30, decoded.disappearingTimerSeconds)
    }

    @Test
    fun testDisappearingTimerOffIsTransmittedAsAnExplicitZero() {
        val textMessage = TextMessage()
        textMessage.text = "Hello"
        textMessage.disappearingTimerSeconds = 0

        val decoded = encodeAndDecode(textMessage)

        // Not null: the sender said OFF, which the receiver must not confuse with "said nothing".
        assertEquals(0, decoded.disappearingTimerSeconds)
    }

    @Test
    fun testNoDisappearingTimerDecodesAsNull() {
        val textMessage = TextMessage()
        textMessage.text = "Hello"

        val decoded = encodeAndDecode(textMessage)

        assertNull(decoded.disappearingTimerSeconds, "an unstamped message must decode as null, not 0")
    }

    /**
     * The relay sees the length of the metadata box. It must not change with the timer, otherwise
     * the ciphertext length alone reveals whether disappearing messages are on and roughly how
     * large the timer is.
     */
    @Test
    fun testMetadataBoxLengthDoesNotVaryWithTheDisappearingTimer() {
        // Fixed date: created_at is a varint, so a differing timestamp magnitude would mask what
        // this test measures.
        val date = Date(1_785_232_707_650L)

        fun metadataLengthFor(timerSeconds: Int?): Int {
            val textMessage = TextMessage()
            textMessage.text = "Hello"
            textMessage.date = date
            textMessage.disappearingTimerSeconds = timerSeconds
            return encode(textMessage).metadataBox!!.box.size
        }

        val expected = metadataLengthFor(null)
        assertEquals(expected, metadataLengthFor(0), "timer=0 must not change the metadata box length")
        assertEquals(expected, metadataLengthFor(30), "timer=30 must not change the metadata box length")
        assertEquals(expected, metadataLengthFor(604800), "timer=604800 must not change the metadata box length")
        assertEquals(
            expected,
            metadataLengthFor(Int.MAX_VALUE),
            "timer=Int.MAX_VALUE must not change the metadata box length",
        )
    }

    /**
     * Nearly all real 1:1 traffic is FS-encapsulated, and it is the envelope that owns the metadata
     * box. The envelope must therefore report the inner message's timer.
     */
    @Test
    fun testForwardSecurityEnvelopeReportsTheInnerMessageTimer() {
        val innerMessage = TextMessage()
        innerMessage.text = "Hello"
        innerMessage.toIdentity = "0ABCDEFG"
        innerMessage.fromIdentity = "01234567"
        innerMessage.disappearingTimerSeconds = 30

        val envelope = ForwardSecurityEnvelopeMessage(
            dummyForwardSecurityDataMessage(),
            innerMessage,
            ForwardSecurityMode.FOURDH,
        )

        assertEquals(30, envelope.disappearingTimerSeconds)

        // Delegation, not a copy taken at construction time: an envelope built before the inner
        // message is stamped must not go stale.
        innerMessage.disappearingTimerSeconds = 300
        assertEquals(300, envelope.disappearingTimerSeconds)
    }

    /**
     * The counterpart on the receiving side: the timer decoded from the envelope's metadata box has
     * to be carried into the encapsulated message.
     */
    @Test
    fun testDecodeEncapsulatedTransfersTheDisappearingTimerInward() {
        val innerMessage = TextMessage()
        innerMessage.text = "Hello"

        // Serialized inner message, exactly as ForwardSecurityMessageProcessor hands it over.
        val plaintext = byteArrayOf(innerMessage.type.toByte()) + innerMessage.body!!

        // An *incoming* envelope has no inner message; MessageCoder.decode() sets the timer on it.
        val envelope = ForwardSecurityEnvelopeMessage(dummyForwardSecurityDataMessage())
        envelope.toIdentity = "0ABCDEFG"
        envelope.fromIdentity = "01234567"
        envelope.disappearingTimerSeconds = 30

        val decapsulated = decoder.decodeEncapsulated(plaintext, envelope, Version.V1_2)

        assertEquals(30, decapsulated.disappearingTimerSeconds)
    }

    @Test
    fun testDecodeEncapsulatedCarriesAnAbsentTimerAsNull() {
        val innerMessage = TextMessage()
        innerMessage.text = "Hello"
        val plaintext = byteArrayOf(innerMessage.type.toByte()) + innerMessage.body!!

        val envelope = ForwardSecurityEnvelopeMessage(dummyForwardSecurityDataMessage())
        envelope.toIdentity = "0ABCDEFG"
        envelope.fromIdentity = "01234567"

        val decapsulated = decoder.decodeEncapsulated(plaintext, envelope, Version.V1_2)

        assertNull(decapsulated.disappearingTimerSeconds)
    }

    private fun dummyForwardSecurityDataMessage() = ForwardSecurityDataMessage(
        DHSessionId(),
        Encapsulated.DHType.FOURDH,
        1L,
        Version.V1_2.number,
        Version.V1_2.number,
        null,
        ByteArray(0),
    )

    private fun assertEqualMessage(expected: AbstractMessage, actual: AbstractMessage) {
        assertContentEquals(expected.body, actual.body)
    }

    private fun encode(abstractMessage: AbstractMessage): MessageBox {
        abstractMessage.toIdentity = "0ABCDEFG"
        abstractMessage.fromIdentity = "01234567"
        return encoder.encode(abstractMessage, ByteArray(NaCl.NONCE_BYTES))
    }

    private fun encodeAndDecode(abstractMessage: AbstractMessage): AbstractMessage =
        decoder.decode(encode(abstractMessage))
}
