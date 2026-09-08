package ch.threema.app.processors.reflectedoutgoingmessage

import ch.threema.app.listeners.MessageListener
import ch.threema.app.managers.ListenerManager
import ch.threema.app.managers.ServiceManager
import ch.threema.app.messagereceiver.GroupMessageReceiver
import ch.threema.app.services.ContactService
import ch.threema.app.services.GroupService
import ch.threema.app.services.MessageService
import ch.threema.app.services.notification.NotificationService
import ch.threema.base.crypto.NonceFactory
import ch.threema.domain.models.GroupId
import ch.threema.domain.models.MessageId
import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.domain.stores.IdentityStore
import ch.threema.protobuf.common.CspE2eMessageType
import ch.threema.protobuf.d2d.ConversationId
import ch.threema.protobuf.d2d.OutgoingMessage
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.group.GroupMessageModel
import ch.threema.storage.models.group.GroupModelOld
import com.google.protobuf.ByteString
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.charset.StandardCharsets
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F1Whisper: a group message read on a LINKED device must become read on this one.
 *
 * The defect this reproduces: [ReflectedOutgoingGroupDeliveryReceiptTask] accepted reactions only and returned early
 * with `unknown or unsupported delivery receipt type` for DELIVERED and READ, before the target message was even
 * looked up. Android's own read path announces a group read twice - the peer receipt AND a separate
 * `reflectGroupReadToLinkedDevices` - so phone-to-Desktop worked; Desktop with read receipts enabled sends only the
 * group receipt, which lands here. 29 such receipts were dropped in 13 days of device logs, each costing the read
 * state, the first-read disappearing countdown and the notification cancel.
 */
class ReflectedOutgoingGroupDeliveryReceiptTaskTest {

    private val messageService = mockk<MessageService>(relaxed = true)
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val groupService = mockk<GroupService>(relaxed = true)
    private val contactService = mockk<ContactService>(relaxed = true)
    private val groupReceiver = mockk<GroupMessageReceiver>(relaxed = true)
    private val groupModel = mockk<GroupModelOld>(relaxed = true)
    private val serviceManager = mockk<ServiceManager>(relaxed = true)

    private val targetModel = GroupMessageModel()
    private val modified = mutableListOf<List<AbstractMessageModel>>()
    private val listener = object : MessageListener {
        override fun onModified(modifiedMessageModel: MutableList<AbstractMessageModel>) {
            modified += modifiedMessageModel.toList()
        }
    }

    init {
        every { serviceManager.messageService } returns messageService
        every { serviceManager.notificationService } returns notificationService
        every { serviceManager.groupService } returns groupService
        every { serviceManager.contactService } returns contactService
        every { serviceManager.nonceFactory } returns mockk<NonceFactory>(relaxed = true)
        every { serviceManager.identityStore } returns mockk<IdentityStore>(relaxed = true) {
            every { getIdentityString() } returns MY_IDENTITY
        }
        every { groupService.getByApiGroupIdAndCreator(any(), any()) } returns groupModel
        every { groupService.createReceiver(groupModel) } returns groupReceiver
        every { messageService.getGroupMessageModel(any<MessageId>(), any(), any()) } returns targetModel
        every { messageService.markAsReadFromSync(any(), any()) } returns true
        every { messageService.updateReceivedTimestamp(any(), any()) } returns true
        ListenerManager.messageListeners.add(listener)
    }

    @AfterTest
    fun tearDown() = ListenerManager.messageListeners.remove(listener)

    // -----------------------------------------------------------------------------------------------------------------------------
    // The two states the handler used to drop.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a reflected READ marks the group message read through the durable sync transition`() {
        process(ProtocolDefines.DELIVERYRECEIPT_MSGREAD)

        verify(exactly = 1) { messageService.markAsReadFromSync(targetModel, Date(CREATED_AT)) }
        verify(exactly = 1) { notificationService.cancel(groupReceiver) }
        assertEquals(
            listOf<List<AbstractMessageModel>>(listOf(targetModel)),
            modified,
            "exactly one modification event, carrying the row",
        )
    }

    @Test
    fun `a reflected DELIVERED stores the received timestamp and does not touch the read state`() {
        process(ProtocolDefines.DELIVERYRECEIPT_MSGRECEIVED)

        verify(exactly = 1) { messageService.updateReceivedTimestamp(targetModel, Date(CREATED_AT)) }
        verify(exactly = 0) { messageService.markAsReadFromSync(any(), any()) }
        verify(exactly = 0) { notificationService.cancel(any<GroupMessageReceiver>()) }
        assertEquals(1, modified.size)
    }

    /**
     * The receipt is one WE sent about a message WE received. The per-member map holds the opposite: what OTHER
     * members did to messages WE sent. Writing our own identity into it would put us in our own "Read by" list.
     */
    @Test
    fun `neither state is written into the per-member group state map`() {
        process(ProtocolDefines.DELIVERYRECEIPT_MSGREAD)
        process(ProtocolDefines.DELIVERYRECEIPT_MSGRECEIVED)

        verify(exactly = 0) { messageService.addGroupMessageState(any(), any(), any()) }
        verify(exactly = 0) { messageService.addMessageReaction(any(), any(), any(), any()) }
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The reaction path the handler already had is unchanged.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a reflected ACK still routes to the reaction path under our own identity`() {
        process(ProtocolDefines.DELIVERYRECEIPT_MSGUSERACK)

        verify(exactly = 1) {
            messageService.addMessageReaction(targetModel, MessageState.USERACK, MY_IDENTITY, Date(CREATED_AT))
        }
        verify(exactly = 0) { messageService.markAsReadFromSync(any(), any()) }
        verify(exactly = 0) { messageService.updateReceivedTimestamp(any(), any()) }
    }

    @Test
    fun `a reflected DEC still routes to the reaction path`() {
        process(ProtocolDefines.DELIVERYRECEIPT_MSGUSERDEC)

        verify(exactly = 1) {
            messageService.addMessageReaction(targetModel, MessageState.USERDEC, MY_IDENTITY, Date(CREATED_AT))
        }
    }

    @Test
    fun `an unmappable receipt type is still rejected without touching the message`() {
        process(0x7f)

        verify(exactly = 0) { messageService.getGroupMessageModel(any<MessageId>(), any(), any()) }
        verify(exactly = 0) { notificationService.cancel(any<GroupMessageReceiver>()) }
        assertEquals(emptyList(), modified)
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // A row that owes nothing, or is no longer there, publishes nothing.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a read that the row refused publishes no event and cancels no notification`() {
        every { messageService.markAsReadFromSync(any(), any()) } returns false

        process(ProtocolDefines.DELIVERYRECEIPT_MSGREAD)

        verify(exactly = 0) { notificationService.cancel(any<GroupMessageReceiver>()) }
        assertEquals(emptyList(), modified, "a hard-deleted, tombstoned or superseded row publishes nothing")
    }

    @Test
    fun `a delivered that the row refused publishes no event`() {
        every { messageService.updateReceivedTimestamp(any(), any()) } returns false

        process(ProtocolDefines.DELIVERYRECEIPT_MSGRECEIVED)

        assertEquals(emptyList(), modified)
    }

    @Test
    fun `a receipt for a message this device does not have is skipped`() {
        every { messageService.getGroupMessageModel(any<MessageId>(), any(), any()) } returns null

        process(ProtocolDefines.DELIVERYRECEIPT_MSGREAD)

        verify(exactly = 0) { messageService.markAsReadFromSync(any(), any()) }
        verify(exactly = 0) { notificationService.cancel(any<GroupMessageReceiver>()) }
        assertEquals(emptyList(), modified)
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The conversation the receipt was addressed to is not the conversation it is about.
    // -----------------------------------------------------------------------------------------------------------------------------

    /**
     * The receipt legitimately carries a CONTACT outer conversation - it is addressed to one member - so the task
     * keeps its contact superclass. The notification, however, belongs to the group, and cancelling through the
     * inherited contact receiver would clear the wrong conversation while leaving the group's own notification up.
     */
    @Test
    fun `the notification is cancelled for the group, and the contact receiver is never resolved`() {
        process(ProtocolDefines.DELIVERYRECEIPT_MSGREAD)

        verify(exactly = 1) { groupService.getByApiGroupIdAndCreator(GroupId(GROUP_ID), GROUP_CREATOR) }
        verify(exactly = 1) { notificationService.cancel(groupReceiver) }
        verify(exactly = 0) { contactService.getByIdentity(any()) }
    }

    @Test
    fun `an unknown group cancels nothing rather than clearing the aggregate notification`() {
        every { groupService.getByApiGroupIdAndCreator(any(), any()) } returns null

        process(ProtocolDefines.DELIVERYRECEIPT_MSGREAD)

        verify(exactly = 1) { messageService.markAsReadFromSync(targetModel, Date(CREATED_AT)) }
        verify(exactly = 0) { notificationService.cancel(any<GroupMessageReceiver>()) }
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // A receipt may carry more than one message id.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `every message id in one receipt is transitioned`() {
        process(ProtocolDefines.DELIVERYRECEIPT_MSGREAD, receiptMessageIdCount = 3)

        verify(exactly = 3) { messageService.markAsReadFromSync(targetModel, Date(CREATED_AT)) }
        assertEquals(3, modified.size)
    }

    private fun process(receiptType: Int, receiptMessageIdCount: Int = 1) {
        ReflectedOutgoingGroupDeliveryReceiptTask(
            outgoingMessage(receiptType, receiptMessageIdCount),
            serviceManager,
        ).executeReflectedOutgoingMessageSteps()
    }

    private fun outgoingMessage(receiptType: Int, receiptMessageIdCount: Int): OutgoingMessage {
        val body = ByteArray(
            ProtocolDefines.IDENTITY_LEN + ProtocolDefines.GROUP_ID_LEN + 1 +
                receiptMessageIdCount * ProtocolDefines.MESSAGE_ID_LEN,
        )
        GROUP_CREATOR.toByteArray(StandardCharsets.US_ASCII).copyInto(body)
        GroupId(GROUP_ID).groupId.copyInto(body, ProtocolDefines.IDENTITY_LEN)
        body[ProtocolDefines.IDENTITY_LEN + ProtocolDefines.GROUP_ID_LEN] = receiptType.toByte()
        repeat(receiptMessageIdCount) { index ->
            MessageId(100L + index).messageId.copyInto(
                body,
                ProtocolDefines.IDENTITY_LEN + ProtocolDefines.GROUP_ID_LEN + 1 +
                    index * ProtocolDefines.MESSAGE_ID_LEN,
            )
        }
        return OutgoingMessage.newBuilder()
            .setConversation(ConversationId.newBuilder().setContact(PEER_IDENTITY))
            .setMessageId(42L)
            .setCreatedAt(CREATED_AT)
            .setType(CspE2eMessageType.GROUP_DELIVERY_RECEIPT)
            .setBody(ByteString.copyFrom(body))
            .build()
    }

    private companion object {
        const val MY_IDENTITY = "ABCD1234"
        const val PEER_IDENTITY = "PEER5678"
        const val GROUP_CREATOR = "CREATOR1"
        const val GROUP_ID = 0x0102030405060708L
        const val CREATED_AT = 1_700_000_000_000L
    }
}
