package ch.threema.app.tasks

import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.multidevice.MultiDeviceManager
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.preference.service.SynchronizedSettingsService
import ch.threema.app.profilepicture.GroupProfilePictureUploader
import ch.threema.app.protocolsteps.BlockState
import ch.threema.app.protocolsteps.IdentityBlockedSteps
import ch.threema.app.services.ContactService
import ch.threema.app.services.FileService
import ch.threema.app.services.GroupService
import ch.threema.app.services.MessageService
import ch.threema.app.services.UserService
import ch.threema.app.utils.OutgoingCspMessageServices
import ch.threema.app.voip.services.VoipStateService
import ch.threema.base.crypto.NonceFactory
import ch.threema.data.repositories.ContactModelRepository
import ch.threema.data.repositories.GroupModelRepository
import ch.threema.domain.helpers.DummyUsers
import ch.threema.domain.helpers.InMemoryContactStore
import ch.threema.domain.helpers.InMemoryDHSessionStore
import ch.threema.domain.helpers.InMemoryIdentityStore
import ch.threema.domain.helpers.InMemoryNonceStore
import ch.threema.domain.helpers.ServerAckTaskCodec
import ch.threema.domain.models.GroupId
import ch.threema.domain.models.MessageId
import ch.threema.domain.protocol.api.APIConnector
import ch.threema.domain.protocol.connection.data.InboundMessage
import ch.threema.domain.protocol.csp.coders.MessageBox
import ch.threema.domain.protocol.csp.fs.ForwardSecurityMessageProcessor
import ch.threema.domain.protocol.csp.fs.ForwardSecurityStatusListener
import ch.threema.domain.taskmanager.ConnectionStoppedException
import ch.threema.domain.taskmanager.MessageFilterInstruction
import ch.threema.storage.DatabaseService
import ch.threema.storage.factories.GroupMessageModelFactory
import ch.threema.storage.factories.RejectedGroupMessageFactory
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.group.GroupMessageModel
import ch.threema.storage.models.group.GroupModelOld
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Date
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.test.ClosingKoinTest
import org.koin.test.mock.declare

/**
 * F1Whisper (eleventh fork review, F11-03): a real group acceptance must not be lost when a later send step fails.
 *
 * **The defect.** Each recipient's server acknowledgement was recorded only in an attempt-local set inside
 * `OutgoingCspMessageSender`, and that set reached the message service exclusively through `markAsSent`, which the
 * bundled send steps invoke only after EVERY send, acknowledgement and reflection step of every sender has succeeded.
 * So: recipient A's payload is acknowledged, recipient B's acknowledgement fails on a dropped connection, the task
 * exits with a `NetworkException`, and the retry constructs a fresh sender whose acceptance set is empty. Recipient A
 * can listen to a listen-once voice message while the sender's own copy stays playable for however long the retries
 * keep failing.
 *
 * **These tests drive the production path.** A real `OutgoingTextMessageTask` (the sealed base's `sendGroupMessage`
 * is exactly the code under review; the task type only supplies the payload) runs against the real
 * `runBundledMessagesSendSteps`, real message encoding (in-memory identity and contact stores from the domain test
 * fixtures), a real `ForwardSecurityMessageProcessor`, and a `ServerAckTaskCodec` subclassed to acknowledge
 * selectively and to fail like a lost connection for everything else. What is mocked is the storage boundary
 * (`MessageService`, repositories), which is where the assertions live.
 */
class OutgoingGroupSendAcceptanceTest : ClosingKoinTest {

    private companion object {
        private const val MY_IDENTITY = "0000000A"
        private const val MODEL_ID = 42
        private const val API_MESSAGE_ID = "0123456789abcdef"
    }

    private val recipientA = DummyUsers.ALICE
    private val recipientB = DummyUsers.BOB

    private val identityStore = InMemoryIdentityStore(MY_IDENTITY, null, ByteArray(32) { 7 }, "me")
    private val contactStore = InMemoryContactStore().apply {
        addContact(DummyUsers.getContactForUser(recipientA))
        addContact(DummyUsers.getContactForUser(recipientB))
    }
    private val nonceFactory = NonceFactory(InMemoryNonceStore())
    private val forwardSecurityMessageProcessor = ForwardSecurityMessageProcessor(
        InMemoryDHSessionStore(),
        contactStore,
        identityStore,
        nonceFactory,
        mockk<ForwardSecurityStatusListener>(relaxed = true),
    )

    private val messageService = mockk<MessageService>(relaxed = true)
    private val group = GroupModelOld().apply {
        setId(1)
        setApiGroupId(GroupId(0L))
        setCreatorIdentity(MY_IDENTITY)
        setName("group")
    }
    private val row = GroupMessageModel().apply {
        id = MODEL_ID
        uid = "uid-42"
        apiMessageId = API_MESSAGE_ID
        groupId = 1
        type = MessageType.TEXT
        body = "hello"
        isOutbox = true
        createdAt = Date()
    }

    @BeforeTest
    fun setUp() {
        startKoin { }
        declare<MessageService> { messageService }
        declare<DatabaseService> {
            mockk {
                every { groupMessageModelFactory } returns mockk<GroupMessageModelFactory> {
                    every { getById(MODEL_ID) } returns row
                }
                every { rejectedGroupMessageFactory } returns mockk<RejectedGroupMessageFactory>(relaxed = true) {
                    every { getMessageRejects(any(), any()) } returns emptySet()
                }
            }
        }
        declare<GroupService> {
            mockk {
                every { getById(1) } returns group
                every { isGroupMember(group) } returns true
                every { getGroupMemberIdentities(group) } returns
                    arrayOf(MY_IDENTITY, recipientA.identity, recipientB.identity)
            }
        }
        declare<GroupModelRepository> {
            mockk {
                every { getByCreatorIdentityAndId(any(), any()) } returns mockk<ch.threema.data.models.GroupModel>(relaxed = true)
            }
        }
        declare<ContactModelRepository> {
            mockk {
                every { getByIdentity(recipientA.identity) } returns contactModelFor(recipientA)
                every { getByIdentity(recipientB.identity) } returns contactModelFor(recipientB)
            }
        }
        val identityBlockedSteps = mockk<IdentityBlockedSteps>()
        every { identityBlockedSteps.run(identity = any()) } returns BlockState.NOT_BLOCKED
        declare<IdentityBlockedSteps> { identityBlockedSteps }
        declare<UserService> {
            mockk(relaxed = true) {
                every { identity } returns MY_IDENTITY
            }
        }
        declare<ContactService> {
            mockk {
                every { isContactAllowedToReceiveProfilePicture(any()) } returns false
            }
        }
        declare<PreferenceService> { mockk(relaxed = true) }
        declare<SynchronizedSettingsService> { mockk(relaxed = true) }
        declare<APIConnector> { mockk(relaxed = true) }
        declare<FileService> { mockk(relaxed = true) }
        declare<GroupProfilePictureUploader> { mockk(relaxed = true) }
        declare<VoipStateService> { mockk(relaxed = true) }
        declare {
            OutgoingCspMessageServices(
                forwardSecurityMessageProcessor = forwardSecurityMessageProcessor,
                identityStore = identityStore,
                userService = mockk(relaxed = true),
                contactStore = contactStore,
                contactService = mockk {
                    every { isContactAllowedToReceiveProfilePicture(any()) } returns false
                },
                contactModelRepository = mockk {
                    every { getByIdentity(recipientA.identity) } returns contactModelFor(recipientA)
                    every { getByIdentity(recipientB.identity) } returns contactModelFor(recipientB)
                },
                groupService = mockk(relaxed = true),
                nonceFactory = nonceFactory,
                preferenceService = mockk(relaxed = true),
                synchronizedSettingsService = mockk(relaxed = true),
                multiDeviceManager = mockk<MultiDeviceManager> {
                    every { isMultiDeviceActive } returns false
                },
            )
        }
    }

    private fun contactModelFor(user: DummyUsers.User): ch.threema.data.models.ContactModel = mockk {
        every { data } returns mockk {
            every { toBasicContact() } returns DummyUsers.getBasicContactForUser(user)
        }
    }

    private fun task() = OutgoingTextMessageTask(
        messageModelId = MODEL_ID,
        receiverType = MessageReceiver.Type_GROUP,
        recipientIdentities = setOf(recipientA.identity, recipientB.identity),
    )

    // -----------------------------------------------------------------------------------------------------------------
    // The finding: one acknowledgement, then a failure
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `an acknowledged recipient's acceptance is applied even though a later recipient fails`() = runTest {
        forwardSecurityMessageProcessor.setForwardSecurityEnabled(false)
        val codec = SelectiveAckCodec { messageBox -> messageBox.toIdentity == recipientA.identity }

        assertFailsWith<ConnectionStoppedException>("the task must stay retryable: a network failure must propagate") {
            task().invoke(codec)
        }

        // The heart of F11-03: A's acknowledged payload reached the service AT ACK TIME, before B's failure could
        // throw it away with the attempt.
        verify(exactly = 1) { messageService.applyGroupPayloadAcceptance(row) }
        // And the send is NOT complete: the final state stays deferred until every step has finished.
        verify(exactly = 0) { messageService.applyOutgoingCompletion(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { messageService.updateOutgoingMessageState(any(), any(), any()) }
    }

    @Test
    fun `a send with no acknowledged payload applies no acceptance`() = runTest {
        forwardSecurityMessageProcessor.setForwardSecurityEnabled(false)
        val codec = SelectiveAckCodec { false }

        assertFailsWith<ConnectionStoppedException> {
            task().invoke(codec)
        }

        // Zero acceptances: destroying the sender's only copy here would destroy the message.
        verify(exactly = 0) { messageService.applyGroupPayloadAcceptance(any()) }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The discrimination the review demands: a forward-security control ack is not payload acceptance
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a forward-security init acknowledgement alone is not acceptance`() = runTest {
        // With forward security on and no existing session, the real encapsulation steps emit an FS init message with
        // its OWN message id ahead of the payload, and the init is server-acknowledged too. Acknowledge exactly that
        // init (everything that does NOT carry the payload's id) and fail the payload itself.
        forwardSecurityMessageProcessor.setForwardSecurityEnabled(true)
        val payloadId = MessageId(ch.threema.base.utils.Utils.hexStringToByteArray(API_MESSAGE_ID))
        val codec = SelectiveAckCodec { messageBox -> messageBox.messageId != payloadId }

        assertFailsWith<ConnectionStoppedException> {
            task().invoke(codec)
        }

        // The init's ack arrived and proved nothing about the payload. Before the id match, this recorded acceptance
        // and would have burned a listen-once sender copy whose payload the connection dropped one frame later.
        verify(exactly = 0) { messageService.applyGroupPayloadAcceptance(any()) }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Control: the full success path
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a fully acknowledged send applies acceptance per recipient and completes once`() = runTest {
        forwardSecurityMessageProcessor.setForwardSecurityEnabled(false)
        every {
            messageService.applyOutgoingCompletion(any(), any(), any(), any(), any(), any())
        } returns true
        val codec = SelectiveAckCodec { true }

        task().invoke(codec)

        verify(exactly = 2) { messageService.applyGroupPayloadAcceptance(row) }
        verify(exactly = 1) {
            messageService.applyOutgoingCompletion(
                row,
                MessageState.SENT,
                any(),
                any(),
                true,
                match { it.isGroupSend && it.acceptedRemoteRecipients == 2 },
            )
        }
    }

    /**
     * Acknowledges exactly the outgoing messages [acks] admits, and answers every other awaited acknowledgement the
     * way a dropped connection does, with a [ConnectionStoppedException] - the situation the finding describes.
     */
    private class SelectiveAckCodec(
        private val acks: (MessageBox) -> Boolean,
    ) : ServerAckTaskCodec() {
        override suspend fun handleOutgoingMessageBox(messageBox: MessageBox) {
            if (acks(messageBox)) {
                super.handleOutgoingMessageBox(messageBox)
            }
        }

        override suspend fun read(preProcess: (InboundMessage) -> MessageFilterInstruction): InboundMessage =
            try {
                super.read(preProcess)
            } catch (e: AssertionError) {
                // The base codec asserts when no queued message matches; production would be suspended on a
                // connection that has gone away. Translate to what the task manager would see.
                throw ConnectionStoppedException()
            }
    }
}
