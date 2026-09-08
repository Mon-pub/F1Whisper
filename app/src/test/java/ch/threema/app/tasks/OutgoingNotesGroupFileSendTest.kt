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
import ch.threema.app.services.OutgoingSendBoundaryDecision
import ch.threema.app.services.OutgoingSendEvidence
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
import ch.threema.domain.protocol.api.APIConnector
import ch.threema.domain.protocol.connection.data.D2dMessage
import ch.threema.domain.protocol.connection.data.D2mProtocolVersion
import ch.threema.domain.protocol.connection.data.DeviceId
import ch.threema.domain.protocol.connection.data.InboundD2mMessage
import ch.threema.domain.protocol.connection.data.InboundMessage
import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.domain.protocol.csp.fs.ForwardSecurityMessageProcessor
import ch.threema.domain.protocol.csp.fs.ForwardSecurityStatusListener
import ch.threema.domain.protocol.multidevice.MultiDeviceKeys
import ch.threema.domain.protocol.multidevice.MultiDeviceProperties
import ch.threema.domain.taskmanager.MessageFilterInstruction
import ch.threema.storage.DatabaseService
import ch.threema.storage.factories.GroupMessageModelFactory
import ch.threema.storage.factories.RejectedGroupMessageFactory
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.data.media.FileDataModel
import ch.threema.storage.models.group.GroupMessageModel
import ch.threema.storage.models.group.GroupModelOld
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.Date
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.test.ClosingKoinTest
import org.koin.test.mock.declare

/**
 * F1Whisper (eleventh fork review, F11-04): a multi-device notes-group file must not be mistaken for a remote group
 * send.
 *
 * **The defect.** The normal file path supplies `getGroupMemberIdentities()` as the recipient list, which INCLUDES
 * the local user; the send layer filters the local user out only later, inside the send steps. `sendGroupMessage`
 * classified the notes state and built the completion evidence from the RAW list, so a file sent to a self-only
 * notes group with multi-device active - the only configuration in which the task runs at all for such a group -
 * reported one intended remote recipient and zero accepted. The state came out SENT instead of READ, and the
 * sender's listen-once burn was refused forever, even though the reflection had been acknowledged and there was
 * never anybody else to wait for.
 *
 * **These tests drive the real initial group-file path**: a real [OutgoingFileMessageTask] through the real send
 * steps with multi-device active (real [MultiDeviceKeys], real envelope encryption) against a codec that
 * acknowledges reflections the way the mediator does, and count exactly when the completion fires relative to those
 * acknowledgements.
 */
class OutgoingNotesGroupFileSendTest : ClosingKoinTest {

    private companion object {
        private const val MY_IDENTITY = "0000000A"
        private const val MODEL_ID = 43
        private const val API_MESSAGE_ID = "fedcba9876543210"
    }

    private val remoteMember = DummyUsers.ALICE
    private val departedMember = DummyUsers.BOB

    private val identityStore = InMemoryIdentityStore(MY_IDENTITY, null, ByteArray(32) { 9 }, "me")
    private val contactStore = InMemoryContactStore().apply {
        addContact(DummyUsers.getContactForUser(remoteMember))
        addContact(DummyUsers.getContactForUser(departedMember))
    }
    private val nonceFactory = NonceFactory(InMemoryNonceStore())
    private val forwardSecurityMessageProcessor = ForwardSecurityMessageProcessor(
        InMemoryDHSessionStore(),
        contactStore,
        identityStore,
        nonceFactory,
        mockk<ForwardSecurityStatusListener>(relaxed = true),
    ).apply { setForwardSecurityEnabled(false) }

    private val messageService = mockk<MessageService>(relaxed = true)
    private val groupService = mockk<GroupService>()
    private val group = GroupModelOld().apply {
        setId(1)
        setApiGroupId(GroupId(0L))
        setCreatorIdentity(MY_IDENTITY)
        setName("group")
    }
    private val row = GroupMessageModel().apply {
        id = MODEL_ID
        uid = "uid-43"
        apiMessageId = API_MESSAGE_ID
        groupId = 1
        type = MessageType.FILE
        isOutbox = true
        createdAt = Date()
        fileData = FileDataModel(
            ByteArray(ProtocolDefines.BLOB_ID_LEN) { 1 },
            ByteArray(ProtocolDefines.BLOB_KEY_LEN) { 2 },
            "audio/aac",
            null,
            1234L,
            "voice.aac",
            1,
            null,
            true,
            mutableMapOf<String, Any>("lo" to true),
        )
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
        every { groupService.getById(1) } returns group
        every { groupService.isGroupMember(group) } returns true
        declare<GroupService> { groupService }
        declare<GroupModelRepository> {
            mockk {
                every { getByCreatorIdentityAndId(any(), any()) } returns mockk<ch.threema.data.models.GroupModel>(relaxed = true)
            }
        }
        declare<ContactModelRepository> {
            mockk {
                every { getByIdentity(MY_IDENTITY) } returns null
                every { getByIdentity(remoteMember.identity) } returns contactModelFor(remoteMember)
                every { getByIdentity(departedMember.identity) } returns contactModelFor(departedMember)
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
                    every { getByIdentity(MY_IDENTITY) } returns null
                    every { getByIdentity(remoteMember.identity) } returns contactModelFor(remoteMember)
                    every { getByIdentity(departedMember.identity) } returns contactModelFor(departedMember)
                },
                groupService = mockk(relaxed = true),
                nonceFactory = nonceFactory,
                preferenceService = mockk(relaxed = true),
                synchronizedSettingsService = mockk(relaxed = true),
                multiDeviceManager = mockk<MultiDeviceManager> {
                    every { isMultiDeviceActive } returns true
                    every { propertiesProvider } returns
                        ch.threema.domain.protocol.connection.d2m.MultiDevicePropertyProvider {
                            MultiDeviceProperties(
                                registrationTime = null,
                                mediatorDeviceId = DeviceId(1u),
                                cspDeviceId = DeviceId(2u),
                                keys = MultiDeviceKeys(ByteArray(32) { 5 }),
                                deviceInfo = D2dMessage.DeviceInfo.INVALID_DEVICE_INFO,
                                protocolVersion = D2mProtocolVersion(0u, 0u),
                                serverInfoListener = { },
                            )
                        }
                },
            )
        }
    }

    private fun contactModelFor(user: DummyUsers.User): ch.threema.data.models.ContactModel = mockk {
        every { data } returns mockk {
            every { toBasicContact() } returns DummyUsers.getBasicContactForUser(user)
        }
    }

    private fun fileTask(recipients: Set<String>) = OutgoingFileMessageTask(
        messageModelId = MODEL_ID,
        receiverType = MessageReceiver.Type_GROUP,
        recipientIdentities = recipients,
        thumbnailBlobId = null,
    )

    // -----------------------------------------------------------------------------------------------------------------
    // The finding: a self-only notes group with multi-device active
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a multi-device notes-group file completes as READ with zero intended remotes, only after the reflection ack`() = runTest {
        every { groupService.getGroupMemberIdentities(group) } returns arrayOf(MY_IDENTITY)
        val codec = ReflectCountingCodec()
        val evidence = slot<OutgoingSendEvidence>()
        var reflectAcksAtCompletion = -1
        every {
            messageService.applyOutgoingCompletion(any(), any(), any(), any(), any(), capture(evidence))
        } answers {
            reflectAcksAtCompletion = codec.reflectAcksAccepted
            true
        }

        // The real initial group-file path passes ALL member identities, including the local user.
        fileTask(setOf(MY_IDENTITY)).invoke(codec)

        verify(exactly = 1) {
            messageService.applyOutgoingCompletion(row, MessageState.READ, any(), any(), true, any())
        }
        assertEquals(0, evidence.captured.intendedRemoteRecipients, "the local user is not a remote recipient")
        assertEquals(0, evidence.captured.acceptedRemoteRecipients)
        assertTrue(
            OutgoingSendBoundaryDecision.burnsSenderCopy(MessageState.READ, evidence.captured),
            "a notes-group completion IS the burn boundary; before the fix the evidence read (1, 0) and refused it",
        )
        // "Prove it never burns before that acknowledgement": the completion carrying the burn ran only once both
        // reflections (the message and its sent-update) had been acknowledged.
        assertEquals(
            2,
            reflectAcksAtCompletion,
            "the completion must run after the reflection acknowledgements, never before",
        )
        // And no remote acceptance path fired: there is no remote recipient to accept anything.
        verify(exactly = 0) { messageService.applyGroupPayloadAcceptance(any()) }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The canonical set on real groups
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a real group's intended remotes exclude the local user`() = runTest {
        every { groupService.getGroupMemberIdentities(group) } returns arrayOf(MY_IDENTITY, remoteMember.identity)
        val codec = ReflectCountingCodec()
        val evidence = slot<OutgoingSendEvidence>()
        every {
            messageService.applyOutgoingCompletion(any(), any(), any(), any(), any(), capture(evidence))
        } returns true

        fileTask(setOf(MY_IDENTITY, remoteMember.identity)).invoke(codec)

        verify(exactly = 1) {
            messageService.applyOutgoingCompletion(row, MessageState.SENT, any(), any(), true, any())
        }
        assertEquals(1, evidence.captured.intendedRemoteRecipients, "me + one member is ONE intended remote, not two")
        assertEquals(1, evidence.captured.acceptedRemoteRecipients)
    }

    @Test
    fun `an identity that is no longer a group member is not counted as intended`() = runTest {
        every { groupService.getGroupMemberIdentities(group) } returns arrayOf(MY_IDENTITY, remoteMember.identity)
        val codec = ReflectCountingCodec()
        val evidence = slot<OutgoingSendEvidence>()
        every {
            messageService.applyOutgoingCompletion(any(), any(), any(), any(), any(), capture(evidence))
        } returns true

        // The task's stored recipient list can predate a membership change; the departed member may still be sent to,
        // but must not keep the completion looking pending.
        fileTask(setOf(remoteMember.identity, departedMember.identity)).invoke(codec)

        assertEquals(1, evidence.captured.intendedRemoteRecipients, "the departed member is no longer intended")
    }

    /**
     * The stock server-ack codec, additionally counting every reflect acknowledgement the moment a reader ACCEPTS it,
     * so a test can pin WHEN the completion ran relative to the mediator's acknowledgements.
     */
    private class ReflectCountingCodec : ServerAckTaskCodec() {
        var reflectAcksAccepted = 0
            private set

        override suspend fun read(preProcess: (InboundMessage) -> MessageFilterInstruction): InboundMessage {
            val message = super.read(preProcess)
            if (message is InboundD2mMessage.ReflectAck) {
                reflectAcksAccepted++
            }
            return message
        }
    }
}
