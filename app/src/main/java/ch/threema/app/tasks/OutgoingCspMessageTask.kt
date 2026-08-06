package ch.threema.app.tasks

import ch.threema.app.listeners.MessageListener
import ch.threema.app.managers.ListenerManager
import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.messagereceiver.MessageReceiver.MessageReceiverType
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.preference.service.SynchronizedSettingsService
import ch.threema.app.profilepicture.GroupProfilePictureUploader
import ch.threema.app.protocolsteps.IdentityBlockedSteps
import ch.threema.app.services.ContactService
import ch.threema.app.services.DisappearingFreezeDecision
import ch.threema.app.services.FileService
import ch.threema.app.services.GroupService
import ch.threema.app.services.MessageService
import ch.threema.app.services.UserService
import ch.threema.app.utils.GroupUtil
import ch.threema.app.utils.OutgoingCspContactMessageCreator
import ch.threema.app.utils.OutgoingCspGroupMessageCreator
import ch.threema.app.utils.OutgoingCspMessageHandle
import ch.threema.app.utils.OutgoingCspMessageServices
import ch.threema.app.utils.runBundledMessagesSendSteps
import ch.threema.app.voip.services.VoipStateService
import ch.threema.base.crypto.NonceFactory
import ch.threema.base.utils.Utils
import ch.threema.base.utils.getThreemaLogger
import ch.threema.data.repositories.ContactModelRepository
import ch.threema.data.repositories.GroupModelRepository
import ch.threema.domain.models.MessageId
import ch.threema.domain.protocol.api.APIConnector
import ch.threema.domain.protocol.csp.fs.BadDHStateException
import ch.threema.domain.protocol.csp.fs.ForwardSecurityMessageProcessor
import ch.threema.domain.protocol.csp.messages.AbstractGroupMessage
import ch.threema.domain.protocol.csp.messages.AbstractMessage
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityMode
import ch.threema.domain.stores.ContactStore
import ch.threema.domain.stores.IdentityStore
import ch.threema.domain.taskmanager.ActiveTask
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.NetworkException
import ch.threema.domain.taskmanager.catchAllExceptNetworkException
import ch.threema.domain.taskmanager.catchExceptNetworkException
import ch.threema.domain.types.IdentityString
import ch.threema.storage.DatabaseService
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.group.GroupMessageModel
import ch.threema.storage.models.group.GroupModelOld
import java.util.Date
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private val logger = getThreemaLogger("OutgoingCspMessageTask")

sealed class OutgoingCspMessageTask :
    ActiveTask<Unit>, PersistableTask, KoinComponent {
    protected val userService: UserService by inject()
    protected val contactModelRepository: ContactModelRepository by inject()
    protected val groupModelRepository: GroupModelRepository by inject()
    protected val contactService: ContactService by inject()
    protected val groupService: GroupService by inject()
    protected val contactStore: ContactStore by inject()
    protected val identityStore: IdentityStore by inject()
    protected val nonceFactory: NonceFactory by inject()
    protected val forwardSecurityMessageProcessor: ForwardSecurityMessageProcessor by inject()
    protected val messageService: MessageService by inject()
    protected val databaseService: DatabaseService by inject()
    protected val preferenceService: PreferenceService by inject()
    protected val synchronizedSettingsService: SynchronizedSettingsService by inject()
    protected val outgoingCspMessageServices: OutgoingCspMessageServices by inject()
    protected val multiDeviceManager by lazy { outgoingCspMessageServices.multiDeviceManager }
    protected val apiConnector: APIConnector by inject()
    protected val fileService: FileService by inject()
    protected val groupProfilePictureUploader: GroupProfilePictureUploader by inject()
    protected val voipStateService: VoipStateService by inject()
    protected val identityBlockedSteps: IdentityBlockedSteps by inject()

    final override suspend fun invoke(handle: ActiveTaskCodec) {
        suspend {
            runSendingSteps(handle)
        }.catchAllExceptNetworkException { exception ->
            onSendingStepsFailed(exception)
            throw exception
        }
    }

    /**
     * Run the steps that need to be performed to send the message(s). If this throws an exception
     * that is not an [NetworkException], [onSendingStepsFailed] is called.
     */
    abstract suspend fun runSendingSteps(handle: ActiveTaskCodec)

    /**
     * This method is called if the sending steps have thrown an exception.
     *
     * Note that this method won't be called if the thrown exception is a [NetworkException].
     *
     * Note that the task will be finished and [runSendingSteps] must not be executed again to
     * prevent infinite retries. The task will exit by throwing [e]. This exception will then be
     * handled by the task manager that may re-run this task.
     */
    open fun onSendingStepsFailed(e: Exception) {
        // Nothing to do here
    }

    /**
     * Encapsulate and send the given message. Note that the message must be ready to be sent. This
     * must include the message flags that should be used. Note that the following fields should not
     * be set as they will get overridden:
     *
     * - [AbstractMessage.toIdentity]
     * - [AbstractMessage.fromIdentity]
     * - [AbstractMessage.messageId]
     * - [AbstractMessage.date]
     *
     * If there is a message model to this message, it should also be passed to this method, so that
     * it gets updated.
     *
     * Note that there must exists a contact model of the receiver. Otherwise, the message cannot be
     * sent. In this case the message model state is set to [MessageState.SENDFAILED] (if provided)
     * and an [IllegalStateException] is thrown.
     */
    suspend fun sendContactMessage(
        message: AbstractMessage,
        messageModel: MessageModel?,
        toIdentity: IdentityString,
        messageId: MessageId,
        createdAt: Date,
        handle: ActiveTaskCodec,
    ) {
        val contactModelData = contactModelRepository.getByIdentity(toIdentity)?.data
        if (contactModelData == null) {
            logger.error(
                "Could not send message to {} as the contact model data is null",
                toIdentity,
            )
            messageModel?.let {
                messageService.updateOutgoingMessageState(
                    it,
                    MessageState.SENDFAILED,
                    Date(),
                )
            }
            throw IllegalStateException("Could not send message as the receiver model is unknown")
        }

        message.disappearingTimerSeconds = advertisedDisappearingTimer(messageModel)

        val createMessage = OutgoingCspContactMessageCreator(
            messageId,
            createdAt,
            toIdentity,
        ) { message }

        val markAsSent = { sentAt: ULong ->
            if (messageModel != null) {
                // Update the message state for the outgoing message
                messageService.updateOutgoingMessageState(
                    messageModel,
                    MessageState.SENT,
                    Date(sentAt.toLong()),
                )
            }
        }

        val updateFsState = { stateMap: Map<String, ForwardSecurityMode> ->
            if (messageModel != null) {
                val forwardSecurityMode = stateMap[toIdentity]
                if (forwardSecurityMode == null) {
                    logger.error("No forward security mode available")
                } else {
                    // F1Whisper (fifth fork review, F5-06): one column, conditionally. This used to full-row-save the
                    // model captured when the task started, so it could recreate a message deleted during the send and
                    // wrote back that whole stale snapshot over the terminal state and countdown markAsSent had just
                    // established a moment earlier.
                    messageService.updateForwardSecurityMode(messageModel, forwardSecurityMode)
                }
            }
        }

        suspend {
            handle.runBundledMessagesSendSteps(
                outgoingCspMessageHandle = OutgoingCspMessageHandle(
                    contactModelData.toBasicContact(),
                    createMessage,
                    markAsSent,
                    updateFsState,
                ),
                services = outgoingCspMessageServices,
                identityBlockedSteps = identityBlockedSteps,
            )
        }.catchExceptNetworkException { e: BadDHStateException ->
            if (messageModel != null) {
                messageService.updateOutgoingMessageState(
                    messageModel,
                    MessageState.SENDFAILED,
                    Date(),
                )
            }
            throw e
        }
    }

    /**
     * Send the group message to the recipients following the _Common Send Steps_. The message is
     * only sent to those recipients that are still part of the group.
     *
     * Note that the message created by [createAbstractMessage] must be ready to be sent. This must
     * include the message flags that should be used. Note that the following fields should not be
     * set as they will get overridden:
     *
     * - [AbstractMessage.toIdentity]
     * - [AbstractMessage.fromIdentity]
     * - [AbstractMessage.messageId]
     * - [AbstractMessage.date]
     * - [AbstractGroupMessage.apiGroupId]
     * - [AbstractGroupMessage.groupCreator]
     *
     * Each invocation of [createAbstractMessage] must return a new instance of the message.
     *
     * Note that the message is only sent to valid members and if the member is not blocked (except
     * the message should be exempted from blocking). If there is no contact model for a member, the
     * message will not be sent to this member.
     */
    suspend fun sendGroupMessage(
        group: GroupModelOld,
        recipients: Collection<String>,
        messageModel: GroupMessageModel?,
        createdAt: Date,
        messageId: MessageId,
        createAbstractMessage: () -> AbstractGroupMessage,
        handle: ActiveTaskCodec,
    ) {
        if (!groupService.isGroupMember(group)) {
            logger.warn("The user is no member of the group and the message is therefore not sent")
            return
        }

        val groupModel = groupModelRepository.getByCreatorIdentityAndId(
            creatorIdentity = group.creatorIdentity,
            groupId = group.apiGroupId,
        ) ?: throw IllegalStateException("Could not get new group model")

        // Get the known contacts
        val finalRecipients = recipients
            .mapNotNull { contactModelRepository.getByIdentity(it) }
            .removeGroupCreatorIfRequired(group)
            .mapNotNull { it.data }
            .map { it.toBasicContact() }
            .toSet()

        // F1Whisper: createAbstractMessage is invoked once per recipient, so the stamp goes inside the factory
        // rather than onto a single instance — every per-member copy must carry the sender's policy. The value
        // is resolved (and logged) once, outside the factory, so a large group produces one line, not N.
        val advertisedTimer = advertisedDisappearingTimer(messageModel)
        val messageCreator = OutgoingCspGroupMessageCreator(
            messageId,
            createdAt,
            group,
        ) {
            createAbstractMessage().also { it.disappearingTimerSeconds = advertisedTimer }
        }

        // F1Whisper (fifth fork review, F5-06): the group completion's ONE authoritative transition timestamp. It used to
        // be persisted by its own full-row save in markAsSent, ahead of a second full-row save in updateFsState that
        // carried the state, and then a third write that armed the clock from the wall clock rather than from this
        // timestamp. Three writes for one lifecycle fact, any pair of which a process death could separate. The value is
        // captured here and spent once, below.
        var acceptedAt: Date? = null

        val markAsSent = { sentAt: ULong ->
            acceptedAt = Date(sentAt.toLong())
        }

        val updateFsState = { fsStateMap: Map<String, ForwardSecurityMode> ->
            fsStateMap.keys.forEach {
                databaseService.rejectedGroupMessageFactory.removeMessageReject(messageId, it, groupModel)
            }

            // Update the message state as all messages have been sent now
            if (messageModel != null) {
                // If there is no recipient (notes group), then we set the message state directly to
                // read, otherwise sent. If there are (still) some rejected identities, we set the state
                // to fs key mismatch, so that the message can be sent again to those. Note that we use
                // the fs key mismatch state to represent the 're-send requested'-mark.
                val state = when {
                    recipients.isEmpty() -> MessageState.READ

                    databaseService.rejectedGroupMessageFactory.getMessageRejects(messageId, groupModel)
                        .isNotEmpty() -> MessageState.FS_KEY_MISMATCH

                    else -> MessageState.SENT
                }

                // Update forward security mode
                val forwardSecurityMode =
                    when (fsStateMap.count { it.value != ForwardSecurityMode.NONE }) {
                        0 -> ForwardSecurityMode.NONE
                        fsStateMap.size -> ForwardSecurityMode.ALL
                        else -> ForwardSecurityMode.PARTIAL
                    }
                val resolvedForwardSecurityMode = if (messageModel.forwardSecurityMode == null) {
                    // If the forward security mode is null, it is the first time we send this message.
                    // Therefore we can set the mode directly to the current mode.
                    forwardSecurityMode
                } else if (forwardSecurityMode == ForwardSecurityMode.PARTIAL || forwardSecurityMode == ForwardSecurityMode.NONE) {
                    // If the previous forward security mode is already set, this means this has been a
                    // resend of the message that only reached a subset of the group members. Therefore
                    // we follow a best effort downgrade procedure: if there is a re-sent message without
                    // forward security, we set the mode to partial, as some may have received the
                    // message with forward security in an earlier attempt.
                    ForwardSecurityMode.PARTIAL
                } else {
                    messageModel.forwardSecurityMode
                }

                // State, the server's acceptance timestamp, the forward-security mode and the disappearing countdown are
                // ONE conditional write. bypassStateGate is set because this path must stamp postedAt even when the
                // outcome is FS_KEY_MISMATCH, which is why it never went through updateOutgoingMessageState.
                //
                // For a notes group this IS the durable local-completion boundary: without multi-device there is no
                // remote recipient to wait for and the state goes straight to READ, and with multi-device active this
                // callback runs only after the reflection has been acknowledged. Either way the boundary timestamp, the
                // state, the start and the deadline reach disk together.
                messageService.applyOutgoingStateTransition(
                    messageModel,
                    state,
                    acceptedAt ?: Date(),
                    resolvedForwardSecurityMode,
                    true,
                )

                // Trigger listener
                ListenerManager.messageListeners.handle { listener: MessageListener ->
                    listener.onModified(listOf(messageModel))
                }
            }
        }

        suspend {
            handle.runBundledMessagesSendSteps(
                outgoingCspMessageHandle = OutgoingCspMessageHandle(
                    finalRecipients,
                    messageCreator,
                    markAsSent,
                    updateFsState,
                ),
                services = outgoingCspMessageServices,
                identityBlockedSteps = identityBlockedSteps,
            )
        }.catchExceptNetworkException { e: BadDHStateException ->
            if (messageModel != null) {
                messageService.updateOutgoingMessageState(
                    messageModel,
                    MessageState.SENDFAILED,
                    Date(),
                )
            }
            throw e
        }

        groupModel.setIsArchivedFromLocalOrRemote(false)
    }

    /**
     * F1Whisper: the disappearing timer this device advertises for an outgoing message, to be written onto
     * [AbstractMessage.disappearingTimerSeconds] and carried to the recipient inside the encrypted
     * `MessageMetadata` box.
     *
     * The advertised value is the timer already FROZEN on [messageModel] at compose time by
     * `createLocalModel` — not the conversation timer read at transmit time. A message queued before a local
     * timer change therefore carries the policy that was in force when it was written, which is both what the
     * sender's own countdown (`DisappearingMessageService.startOutgoingClock`) uses and what Signal does
     * (`message.expiresIn` stamped at insert). Sender and recipient consequently expire it at the same value.
     *
     * Returns `null` — meaning "advertise nothing" — only when there is no message model at all: delivery
     * receipts, the `0x85`/`0x95` timer control messages themselves, typing indicators. That asymmetry is
     * deliberate. A model whose timer is off advertises `0` (an explicit "I say OFF"), so an ABSENT field
     * keeps meaning exactly one thing on the receive side: the peer is a pre-v6.4.3-38 client. See
     * [DisappearingFreezeDecision] for the tri-state that rests on it.
     */
    private fun advertisedDisappearingTimer(messageModel: AbstractMessageModel?): Int? {
        if (messageModel == null) {
            return null
        }
        val advertised = DisappearingFreezeDecision.advertisedTimer(messageModel.disappearingTimerSeconds)
        logger.info(
            "Disappearing: outgoing stamped uid={} timer={}s advertised={}",
            messageModel.uid,
            messageModel.disappearingTimerSeconds,
            advertised,
        )
        return advertised
    }

    /**
     * Returns the message id of the message model.
     *
     * @throws IllegalArgumentException if the message id of the message model is null
     */
    protected fun ensureMessageId(messageModel: AbstractMessageModel): MessageId {
        messageModel.apiMessageId?.let {
            return MessageId(Utils.hexStringToByteArray(it))
        }

        throw IllegalArgumentException("Message id of message model is null")
    }

    /**
     * Get the message model with the given local database message model id.
     *
     * @throws IllegalArgumentException if receiver type is not [MessageReceiver.Type_CONTACT] or
     * [MessageReceiver.Type_GROUP]
     */
    protected fun getMessageModel(
        @MessageReceiverType receiverType: Int,
        messageModelId: Int,
    ): AbstractMessageModel? {
        return when (receiverType) {
            MessageReceiver.Type_CONTACT -> getContactMessageModel(messageModelId)
            MessageReceiver.Type_GROUP -> getGroupMessageModel(messageModelId)
            else -> throw IllegalArgumentException("Invalid receiver type: $receiverType")
        }
    }

    /**
     * Get the contact message model with the given local database message model id.
     *
     * Answers from the service cache when it can. Use [getContactContentRow] instead whenever the model is about to be
     * turned into a payload; see [PersistentTaskRowGate].
     */
    protected fun getContactMessageModel(messageModelId: Int): MessageModel? {
        val messageModel = messageService.getContactMessageModel(messageModelId)
        if (messageModel == null) {
            logger.warn("Could not find contact message model with id {}", messageModelId)
        }
        return messageModel
    }

    /**
     * Get the group message model with the given local database message model id.
     *
     * Answers from the service cache when it can. Use [getGroupContentRow] instead whenever the model is about to be
     * turned into a payload; see [PersistentTaskRowGate].
     */
    protected fun getGroupMessageModel(messageModelId: Int): GroupMessageModel? {
        val messageModel = messageService.getGroupMessageModel(messageModelId)
        if (messageModel == null) {
            logger.warn("Could not find group message model with id {}", messageModelId)
        }
        return messageModel
    }

    /**
     * F1Whisper (seventh fork review, F7-01): the contact row this task may transmit from, straight from the database
     * and only if it is still there and not deleted for everyone.
     *
     * The database, not [getContactMessageModel], because that one answers from the in-memory cache first, and the
     * cache is precisely what a deletion racing a queued task may fail to reach. `null` means "complete this task
     * without transmitting anything".
     */
    protected fun getContactContentRow(messageModelId: Int): MessageModel? {
        val current = databaseService.messageModelFactory.getById(messageModelId)
        if (!PersistentTaskRowGate.transmits(current)) {
            logger.info(
                "Not transmitting contact message {}: its row is gone or deleted",
                messageModelId,
            )
            return null
        }
        return current
    }

    /**
     * F1Whisper (seventh fork review, F7-01): the group row this task may transmit from. See [getContactContentRow].
     */
    protected fun getGroupContentRow(messageModelId: Int): GroupMessageModel? {
        val current = databaseService.groupMessageModelFactory.getById(messageModelId)
        if (!PersistentTaskRowGate.transmits(current)) {
            logger.info(
                "Not transmitting group message {}: its row is gone or deleted",
                messageModelId,
            )
            return null
        }
        return current
    }

    /**
     * Set the message model state to [MessageState.SENDFAILED] and save the model to the database.
     *
     * F1Whisper auto-resend: this is the task-layer entry point into SENDFAILED (invoked from
     * [onSendingStepsFailed]). Only NON-network exceptions reach it (network ones are retried by the
     * persistent task queue), so every failure that lands here is TERMINAL by construction - re-driving
     * the send cannot fix it. We therefore set the DISPLAY_TAG_SEND_FAILED_TERMINAL bit exactly as the
     * central [MessageService.updateOutgoingMessageState] path does, so [AutoResendService] never
     * silently re-uploads the blob and re-sends this message on every reconnect. We also fire
     * onModified (which the central path does but plain [MessageService.save] does not) so the
     * unsent-message nag surfaces immediately instead of only after the 24h aged-out sweep.
     *
     * The state is set unconditionally (matching the previous behaviour) rather than routing through
     * updateOutgoingMessageState, whose canChangeToState gate would skip both the state change AND the
     * terminal bit for a message in an edge state - which would reintroduce the non-terminal SENDFAILED
     * that the auto-resend scan would keep retrying.
     */
    protected fun AbstractMessageModel.saveWithStateFailed() {
        logger.info("Setting message state of model with message id {} to failed (terminal)", apiMessageId)
        // F1Whisper (fifth fork review, F5-06): a conditional, column-scoped write rather than a full-row save of the
        // model this task captured when it started. bypassStateGate keeps the previous behaviour of setting the state
        // unconditionally: canChangeToState would skip both the state change AND the terminal bit for a message in an
        // edge state, and a non-terminal SENDFAILED is one the auto-resend scan keeps retrying forever.
        messageService.applyOutgoingStateTransition(this, MessageState.SENDFAILED, Date(), null, true)
        ListenerManager.messageListeners.handle { listener: MessageListener ->
            listener.onModified(listOf(this))
        }
    }

    /**
     * Remove the group creator if no messages should be sent to it according to [GroupUtil.shouldSendMessagesToCreator].
     */
    private fun Collection<ch.threema.data.models.ContactModel>.removeGroupCreatorIfRequired(group: GroupModelOld) =
        filterIf(!GroupUtil.shouldSendMessagesToCreator(group)) { it.identity != group.creatorIdentity }

    /**
     * If the [condition] is fulfilled, the [predicate] is used to filter the collection.
     */
    private fun <T> Collection<T>.filterIf(condition: Boolean, predicate: (T) -> Boolean): Collection<T> =
        if (condition) {
            this.filter(predicate)
        } else {
            this
        }
}
