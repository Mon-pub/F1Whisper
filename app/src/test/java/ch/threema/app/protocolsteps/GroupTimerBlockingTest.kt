package ch.threema.app.protocolsteps

import ch.threema.app.multidevice.MultiDeviceManager
import ch.threema.app.utils.OutgoingCspGroupMessageCreator
import ch.threema.app.utils.OutgoingCspMessageHandle
import ch.threema.app.utils.OutgoingCspMessageServices
import ch.threema.app.utils.runBundledMessagesSendSteps
import ch.threema.base.crypto.NonceFactory
import ch.threema.domain.helpers.DummyUsers
import ch.threema.domain.helpers.InMemoryContactStore
import ch.threema.domain.helpers.InMemoryDHSessionStore
import ch.threema.domain.helpers.InMemoryIdentityStore
import ch.threema.domain.helpers.InMemoryNonceStore
import ch.threema.domain.helpers.ServerAckTaskCodec
import ch.threema.domain.models.GroupId
import ch.threema.domain.models.MessageId
import ch.threema.domain.protocol.connection.data.CspMessage
import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.domain.protocol.csp.coders.MessageBox
import ch.threema.domain.protocol.csp.fs.ForwardSecurityMessageProcessor
import ch.threema.domain.protocol.csp.fs.ForwardSecurityStatusListener
import ch.threema.domain.protocol.csp.messages.DisappearingTimerMessage
import ch.threema.domain.protocol.csp.messages.GroupDisappearingTimerMessage
import ch.threema.domain.protocol.csp.messages.GroupSetupMessage
import ch.threema.domain.protocol.csp.messages.GroupTextMessage
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * F1Whisper (eleventh fork review, F11-06): group timer state transfer gets the same end-to-end blocking treatment as
 * the group setup it accompanies.
 *
 * **The defect.** One predicate - `AbstractMessage.exemptFromBlocking()` - gates blocking in BOTH directions: the
 * outgoing recipient filter inside the bundled send steps, and the incoming discard in `IncomingMessageTask`. Group
 * setup is exempt; the `0x95` group timer control was not. So an explicitly blocked contact who was added, re-added
 * or resynced received the setup and joined the group, while the timer bootstrap that follows it was silently
 * dropped - and the same happened inbound when the recipient had blocked the group creator. The member ended up
 * inside the group under the wrong retention policy: OFF where the group says 30 seconds, or a stale positive value
 * where the group has since turned it off.
 *
 * **What executes here.** The REAL outgoing filter, through the real `runBundledMessagesSendSteps` with a blocking
 * `IdentityBlockedSteps`, bundling exactly what the group update steps bundle for an added member - setup, then the
 * `0x95` - plus ordinary content as the control: the wire must carry setup and timer to the blocked member and must
 * NOT carry the content. Which 0x95s are EMITTED on add, re-add and resync is `GroupTimerBootstrapTest`'s subject
 * (tenth review, F10-05) and is unchanged by this; what changes here is that a blocked member no longer loses them in
 * the filter. The incoming direction runs through the identical predicate, pinned structurally below because driving
 * `IncomingMessageTask` needs the full processor graph.
 */
class GroupTimerBlockingTest {

    private companion object {
        private const val MY_IDENTITY = "0000000A"
    }

    private val blockedMember = DummyUsers.ALICE

    private val identityStore = InMemoryIdentityStore(MY_IDENTITY, null, ByteArray(32) { 3 }, "me")
    private val contactStore = InMemoryContactStore().apply {
        addContact(DummyUsers.getContactForUser(blockedMember))
    }
    private val nonceFactory = NonceFactory(InMemoryNonceStore())

    private val setupMessageId = MessageId.random()
    private val timerMessageId = MessageId.random()
    private val contentMessageId = MessageId.random()

    private val services = OutgoingCspMessageServices(
        forwardSecurityMessageProcessor = ForwardSecurityMessageProcessor(
            InMemoryDHSessionStore(),
            contactStore,
            identityStore,
            nonceFactory,
            mockk<ForwardSecurityStatusListener>(relaxed = true),
        ).apply { setForwardSecurityEnabled(false) },
        identityStore = identityStore,
        userService = mockk(relaxed = true),
        contactStore = contactStore,
        contactService = mockk {
            every { isContactAllowedToReceiveProfilePicture(any()) } returns false
        },
        contactModelRepository = mockk {
            every { getByIdentity(any<String>()) } returns null
        },
        groupService = mockk(relaxed = true),
        nonceFactory = nonceFactory,
        preferenceService = mockk(relaxed = true),
        synchronizedSettingsService = mockk(relaxed = true),
        multiDeviceManager = mockk<MultiDeviceManager> {
            every { isMultiDeviceActive } returns false
        },
    )

    private val everythingBlocked = mockk<IdentityBlockedSteps>().also {
        every { it.run(identity = any()) } returns BlockState.EXPLICITLY_BLOCKED
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The wire, with the recipient explicitly blocked: what the update steps bundle for an added member
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a blocked added member receives setup followed by the timer, and no content`() = runTest {
        val codec = ServerAckTaskCodec()
        val group = DummyUsers.getBasicContactForUser(blockedMember)
        val now = Date()

        codec.runBundledMessagesSendSteps(
            listOf(
                OutgoingCspMessageHandle(
                    group,
                    OutgoingCspGroupMessageCreator(setupMessageId, now, GroupId(0L), MY_IDENTITY) {
                        GroupSetupMessage().apply { members = arrayOf(blockedMember.identity) }
                    },
                ),
                OutgoingCspMessageHandle(
                    group,
                    OutgoingCspGroupMessageCreator(timerMessageId, now, GroupId(0L), MY_IDENTITY) {
                        GroupDisappearingTimerMessage().apply { timerSeconds = 30 }
                    },
                ),
                OutgoingCspMessageHandle(
                    group,
                    OutgoingCspGroupMessageCreator(contentMessageId, now, GroupId(0L), MY_IDENTITY) {
                        GroupTextMessage().apply { text = "content stays blocked" }
                    },
                ),
            ),
            services,
            everythingBlocked,
        )

        val sentBoxes = sentBoxesOf(codec)
        val setupIndex = sentBoxes.indexOfFirst { it.messageId == setupMessageId && it.toIdentity == blockedMember.identity }
        val timerIndex = sentBoxes.indexOfFirst { it.messageId == timerMessageId && it.toIdentity == blockedMember.identity }

        assertTrue(setupIndex >= 0, "group setup is exempt from blocking and must reach the added member")
        assertTrue(
            timerIndex >= 0,
            "the 0x95 bootstrap must reach the blocked member too; dropping it is how they joined under the wrong policy",
        )
        assertTrue(setupIndex < timerIndex, "setup first, so the recipient can resolve the group before the timer")
        assertFalse(
            sentBoxes.any { it.messageId == contentMessageId },
            "ordinary content to a blocked member stays blocked; the exemption is state transfer, not a bypass",
        )
    }

    @Test
    fun `an explicit timer-off bootstrap reaches a blocked member as well`() = runTest {
        // Re-add after the group turned the timer off: the member's stale positive value must be corrected by an
        // explicit 0x95(0), and that correction must not be lost to blocking either.
        val codec = ServerAckTaskCodec()
        val group = DummyUsers.getBasicContactForUser(blockedMember)

        codec.runBundledMessagesSendSteps(
            listOf(
                OutgoingCspMessageHandle(
                    group,
                    OutgoingCspGroupMessageCreator(timerMessageId, Date(), GroupId(0L), MY_IDENTITY) {
                        GroupDisappearingTimerMessage().apply { timerSeconds = 0 }
                    },
                ),
            ),
            services,
            everythingBlocked,
        )

        assertTrue(
            sentBoxesOf(codec).any { it.messageId == timerMessageId && it.toIdentity == blockedMember.identity },
            "0x95(0) is state transfer exactly like a positive value",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The predicate both directions share
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the group timer control is exempt from blocking, like the setup it accompanies`() {
        assertTrue(GroupSetupMessage().exemptFromBlocking(), "the premise: setup flows to blocked members")
        assertTrue(
            GroupDisappearingTimerMessage().exemptFromBlocking(),
            "the timer is group state like the setup; non-exempt is how the member joined under the wrong policy",
        )
    }

    @Test
    fun `the one-to-one timer rule is unchanged`() {
        assertFalse(
            DisappearingTimerMessage().exemptFromBlocking(),
            "blocking a 1:1 peer means exactly 'no state from you'; the review requires this untouched",
        )
    }

    @Test
    fun `the incoming discard runs on the same predicate`() {
        // The incoming half of "end-to-end": IncomingMessageTask discards from blocked senders unless the message is
        // exempt, so with the predicate fixed both directions are fixed. Driving the task needs the whole processor
        // graph, so the gate's shape is pinned here instead; the wire tests above execute the outgoing half for real.
        val source = File("src/main/java/ch/threema/app/processors/IncomingMessageTask.kt").readText()
        val gateIndex = source.indexOf("if (!message.exemptFromBlocking())")
        assertTrue(gateIndex >= 0, "the incoming blocking gate must consult exemptFromBlocking")
        val gateBlock = source.substring(gateIndex, source.indexOf('}', source.indexOf("isBlocked()", gateIndex)))
        assertTrue(
            gateBlock.contains("identityBlockedSteps.run("),
            "and must ask the same blocked-steps the outgoing filter asks",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------------------------------------------------

    private fun sentBoxesOf(codec: ServerAckTaskCodec): List<MessageBox> =
        codec.outboundMessages
            .filterIsInstance<CspMessage>()
            .filter { it.payloadType.toInt() == ProtocolDefines.PLTYPE_OUTGOING_MESSAGE }
            .map { MessageBox.parseBinary(it.toOutgoingMessageData().data) }
}
