package ch.threema.app.processors

import androidx.annotation.VisibleForTesting
import ch.threema.app.AppConstants
import ch.threema.app.managers.ServiceManager
import ch.threema.app.multidevice.MultiDeviceManager
import ch.threema.app.processors.incomingcspmessage.ReceiveStepsResult
import ch.threema.app.processors.incomingcspmessage.getSubTaskFromMessage
import ch.threema.app.processors.incomingcspmessage.workdelta.IncomingWorkSyncDeltaMessageTask
import ch.threema.app.processors.push.IncomingWebSessionResumeMessageTask
import ch.threema.app.protocolsteps.Contact
import ch.threema.app.protocolsteps.IdentityBlockedSteps
import ch.threema.app.protocolsteps.Init
import ch.threema.app.protocolsteps.Invalid
import ch.threema.app.protocolsteps.SpecialContact
import ch.threema.app.protocolsteps.UserContact
import ch.threema.app.protocolsteps.ValidContactsLookupSteps
import ch.threema.app.services.ContactService
import ch.threema.app.services.ContactServiceImpl
import ch.threema.app.services.MessageService
import ch.threema.app.tasks.ActiveComposableTask
import ch.threema.base.crypto.Nonce
import ch.threema.base.crypto.NonceFactory
import ch.threema.base.crypto.NonceScope
import ch.threema.base.utils.Utils
import ch.threema.base.utils.getThreemaLogger
import ch.threema.common.now
import ch.threema.data.models.ContactModelData
import ch.threema.data.models.ModelDeletedException
import ch.threema.data.repositories.ContactModelRepository
import ch.threema.domain.models.IdentityState
import ch.threema.domain.models.MessageId
import ch.threema.domain.models.VerificationLevel
import ch.threema.domain.protocol.api.APIConnector
import ch.threema.domain.protocol.connection.data.CspMessage
import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.domain.protocol.csp.coders.MessageBox
import ch.threema.domain.protocol.csp.coders.MessageCoder
import ch.threema.domain.protocol.csp.fs.ForwardSecurityMessageProcessor
import ch.threema.domain.protocol.csp.fs.PeerRatchetIdentifier
import ch.threema.domain.protocol.csp.messages.AbstractGroupMessage
import ch.threema.domain.protocol.csp.messages.AbstractMessage
import ch.threema.domain.protocol.csp.messages.BadMessageException
import ch.threema.domain.protocol.csp.messages.MissingPublicKeyException
import ch.threema.domain.protocol.csp.messages.WebSessionResumeMessage
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityEnvelopeMessage
import ch.threema.domain.protocol.csp.messages.workdelta.WorkSyncDeltaMessage
import ch.threema.domain.stores.ContactStore
import ch.threema.domain.stores.IdentityStore
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.NetworkException
import ch.threema.domain.taskmanager.ProtocolException
import ch.threema.domain.taskmanager.TriggerSource
import ch.threema.domain.taskmanager.catchAllExceptNetworkException
import ch.threema.domain.taskmanager.catchExceptNetworkException
import ch.threema.domain.taskmanager.getEncryptedIncomingMessageEnvelope
import ch.threema.domain.taskmanager.runCatchingExceptNetworkException
import ch.threema.domain.types.IdentityString
import ch.threema.storage.models.ContactModel.AcquaintanceLevel
import java.util.Date
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private val logger = getThreemaLogger("IncomingMessageTask")

class IncomingMessageTask(
    private val messageBox: MessageBox,
    private val serviceManager: ServiceManager,
) : ActiveComposableTask<Unit>, KoinComponent {
    private val contactService: ContactService by inject()
    private val contactModelRepository: ContactModelRepository by inject()
    private val contactStore: ContactStore by inject()
    private val identityStore: IdentityStore by inject()
    private val nonceFactory: NonceFactory by inject()
    private val messageService: MessageService by inject()
    private val multiDeviceManager: MultiDeviceManager by inject()
    private val forwardSecurityMessageProcessor: ForwardSecurityMessageProcessor by inject()
    private val identityBlockedSteps: IdentityBlockedSteps by inject()
    private val validContactsLookupSteps: ValidContactsLookupSteps by inject()
    private val incomingForwardSecurityPreProcessor by lazy {
        IncomingForwardSecurityProcessor(
            serviceManager,
        )
    }

    override suspend fun run(handle: ActiveTaskCodec) {
        suspend {
            processMessage(handle)
        }.catchAllExceptNetworkException { e ->
            val messageId = messageBox.messageId
            val fromIdentity = messageBox.fromIdentity

            logger.error("Processing message {} from {} failed", messageId, fromIdentity, e)

            // If we catch a network related exception, we throw a protocol exception to trigger a
            // reconnect by the task manager.
            if (e is APIConnector.HttpConnectionException || e is APIConnector.NetworkException) {
                logger.error("Could not process message {} from {}}", messageId, fromIdentity, e)
                throw ProtocolException(e.message ?: "")
            }

            // For every other exception, we acknowledge the failed message. Due to forward
            // security, it would not make sense to process a message later on. Note that when a
            // NetworkException is thrown, we do not need to acknowledge the message as the task
            // manager will be stopped before the next message can be processed. On the next
            // reconnect, we will receive the non-acked messages.
            // Note that we do not protect messages that could not be decoded against replay
            acknowledgeMessage(messageBox, false, null, handle)
        }
    }

    private suspend fun processMessage(handle: ActiveTaskCodec) {
        logger.info(
            "Incoming message from {} with ID {}",
            messageBox.fromIdentity,
            messageBox.messageId,
        )

        val (message, peerRatchetIdentifier, contactOrInit) = suspend {
            // If the nonce has already been used, acknowledge and discard the message
            if (nonceFactory.exists(NonceScope.CSP, Nonce(messageBox.nonce))) {
                logger.warn(
                    "Skipped processing message {} as its nonce has already been used",
                    messageBox.messageId,
                )
                throw DiscardMessageException()
            }

            if (messageBox.toIdentity != identityStore.getIdentityString()) {
                logger.warn(
                    "Skipped processing message {} as its recipient identity {} is not the user's identity",
                    messageBox.messageId,
                    messageBox.toIdentity,
                )
                throw DiscardMessageException()
            }

            val contactOrInit = validContactsLookupSteps.run(identity = messageBox.fromIdentity)
            if (contactOrInit is UserContact) {
                logger.warn("Skipped processing message {} as its sender is the user itself", messageBox.messageId)
                throw DiscardMessageException()
            }
            if (contactOrInit is Invalid) {
                logger.warn("Skipped processing message {} as its sender ({}) is invalid", messageBox.messageId, messageBox.fromIdentity)
                throw DiscardMessageException()
            }
            if (contactOrInit is Init) {
                // We need to cache the contact so that the message can be decrypted
                contactStore.cacheContact(contactOrInit.contactModelData)
            }

            val (message, peerRatchetIdentifier) = decryptMessage(messageBox, handle)
            Triple(message, peerRatchetIdentifier, contactOrInit)
        }.catchExceptNetworkException { e: DiscardMessageException ->
            logger.warn("Discard message {}", messageBox.messageId)
            // If the message could be decrypted, then check if it should be protected against
            // replay. Otherwise, we do not protect it against replay.
            val protectAgainstReplay = e.discardedMessage?.protectAgainstReplay() ?: false
            acknowledgeMessage(messageBox, protectAgainstReplay, e.peerRatchetIdentifier, handle)
            return
        }

        if (message == null) {
            // Note that if the message is null, it is an fs control message or an invalid message
            // that does not need any further processing. Therefore we just acknowledge the message.
            // Note that we need to protect fs control message against replay.
            acknowledgeMessage(messageBox, true, peerRatchetIdentifier, handle)
            return
        }

        suspend {
            if (!message.exemptFromBlocking()) {
                val blockState = identityBlockedSteps.run(
                    identity = message.fromIdentity,
                )
                if (blockState.isBlocked()) {
                    logger.info(
                        "Message {} from {} will be discarded: Contact is implicitly or explicitly blocked.",
                        message.messageId,
                        message.fromIdentity,
                    )
                    throw DiscardMessageException(
                        message,
                        peerRatchetIdentifier,
                    )
                }
            }

            if (message.fromIdentity == ProtocolDefines.SPECIAL_CONTACT_PUSH) {
                // Handle messages from special push contact
                when (message) {
                    is WebSessionResumeMessage -> IncomingWebSessionResumeMessageTask(
                        message,
                        TriggerSource.REMOTE,
                        serviceManager,
                    )

                    else -> {
                        logReceivedUnexpectedMessageFromSpecialIdentity(message)
                        throw DiscardMessageException(message)
                    }
                }.run(handle)
            } else if (message.fromIdentity == AppConstants.THREEMA_WORK_SYNC_IDENTITY) {
                // Handle messages from special work delta sync contact
                when (message) {
                    is WorkSyncDeltaMessage -> IncomingWorkSyncDeltaMessageTask(
                        message = message,
                        triggerSource = TriggerSource.REMOTE,
                        serviceManager = serviceManager,
                    )

                    else -> {
                        logReceivedUnexpectedMessageFromSpecialIdentity(message)
                        throw DiscardMessageException(message)
                    }
                }.run(handle)
            } else if (contactOrInit !is SpecialContact) {
                // Handle message from other contact

                // Extract the nickname from the message
                val nickname = message.nickname?.trim()

                if (message.createImplicitlyDirectContact()) {
                    if (contactOrInit is Init) {
                        // Create contact if message allows it and contact does not exist yet
                        createDirectContact(
                            contactModelData = contactOrInit.contactModelData,
                            nickname = nickname,
                            handle = handle,
                        )
                    } else if (contactOrInit is Contact) {
                        // Change acquaintance level of contact if it is group or deleted
                        val contactModelData = contactOrInit.contactModel.data!!
                        if (contactModelData.acquaintanceLevel == AcquaintanceLevel.GROUP) {
                            // Note that it is ok to set it _from local_ because we do not await its completion inside the incoming message task
                            contactOrInit.contactModel.setAcquaintanceLevelFromLocal(
                                acquaintanceLevel = AcquaintanceLevel.DIRECT,
                            )
                        }
                    }
                }

                // Update the nickname if it changed (and if contact exists)
                if (nickname != null) {
                    updateNicknameIfChanged(message.fromIdentity, nickname, handle)
                }

                // Set the contact as active
                setIdentityStateToActive(message.fromIdentity)

                executeMessageSteps(message, handle)
            }
        }.catchExceptNetworkException { _: DiscardMessageException ->
            logger.warn("Discard message {}", messageBox.messageId)
            acknowledgeMessage(
                messageBox,
                message.protectAgainstReplay(),
                peerRatchetIdentifier,
                handle,
            )
            return
        }

        val receivedTimestamp =
            if (multiDeviceManager.isMultiDeviceActive && message.reflectIncoming()) {
                logger.info("Reflecting incoming message {}", message.messageId)
                reflectMessage(message, messageBox.nonce, handle)
            } else {
                now().time.toULong()
            }

        updateReceivedTimestamp(message, receivedTimestamp ?: now().time.toULong())

        // If the message type requires automatic delivery receipts and the message does not contain
        // the "no delivery receipt" flag, schedule the sending of a delivery receipt
        if (message.sendAutomaticDeliveryReceipt() &&
            !message.hasFlag(ProtocolDefines.MESSAGE_FLAG_NO_DELIVERY_RECEIPTS)
        ) {
            contactService.getByIdentity(message.fromIdentity)?.let { contactModel ->
                contactService.createReceiver(contactModel).sendDeliveryReceipt(
                    ProtocolDefines.DELIVERYRECEIPT_MSGRECEIVED,
                    arrayOf(message.messageId),
                    now().time,
                )
                logger.info(
                    "Enqueued delivery receipt (delivered) message for message ID {} from {}",
                    message.messageId,
                    message.fromIdentity,
                )
            }
        }

        // Acknowledge the message
        acknowledgeMessage(
            messageBox,
            message.protectAgainstReplay(),
            peerRatchetIdentifier,
            handle,
        )
    }

    private fun logReceivedUnexpectedMessageFromSpecialIdentity(message: AbstractMessage) {
        logger.warn(
            "Received unexpected message {} from {}",
            Utils.byteToHex(message.type.toByte(), true, true),
            message.fromIdentity,
        )
    }

    private suspend fun decryptMessage(
        messageBox: MessageBox,
        handle: ActiveTaskCodec,
    ): Pair<AbstractMessage?, PeerRatchetIdentifier?> {
        // Try to decode the message. At this point we have the public key of the sender either
        // stored or cached in the contact store.
        val messageCoder = MessageCoder(this.contactStore, this.identityStore)
        val encapsulatedMessage = try {
            messageCoder.decode(messageBox)
        } catch (e: BadMessageException) {
            logger.warn("Could not decode message: {}", e.message)
            throw DiscardMessageException()
        }

        logger.info(
            "Incoming message {} from {} to {} (type {})",
            messageBox.messageId,
            messageBox.fromIdentity,
            messageBox.toIdentity,
            Utils.byteToHex(encapsulatedMessage.type.toByte(), true, true),
        )

        // Decapsulate fs message if it is an fs envelope message
        val (message, peerRatchetIdentifier) = decapsulateMessage(encapsulatedMessage, handle)

        // In case there is no decapsulated message, it was an fs control message that does not need
        // further processing
        if (message == null) {
            return null to peerRatchetIdentifier
        }

        logger.info(
            "Processing decrypted message {} from {} to {} (type {})",
            message.messageId,
            message.fromIdentity,
            message.toIdentity,
            Utils.byteToHex(message.type.toByte(), true, true),
        )

        return Pair(message, peerRatchetIdentifier)
    }

    private suspend fun decapsulateMessage(
        encapsulated: AbstractMessage,
        handle: ActiveTaskCodec,
    ): Pair<AbstractMessage?, PeerRatchetIdentifier?> {
        // If the message is not a fs encapsulated message, warn the user depending on the fs
        // session and return the already decapsulated message.
        if (encapsulated !is ForwardSecurityEnvelopeMessage) {
            forwardSecurityMessageProcessor.warnIfMessageWithoutForwardSecurityReceived(
                encapsulated,
                handle,
            )
            return Pair(encapsulated, null)
        }

        val contact = contactStore.getContactForIdentityIncludingCache(encapsulated.fromIdentity)
            ?: throw MissingPublicKeyException("Missing public key for ID ${encapsulated.fromIdentity}")

        val fsDecryptionResult = incomingForwardSecurityPreProcessor
            .processEnvelopeMessage(contact, encapsulated, handle)

        return Pair(fsDecryptionResult.message, fsDecryptionResult.peerRatchetIdentifier)
    }

    /**
     * Persist everything that records this message as processed, and only then tell the server it
     * may drop its copy.
     *
     * The order is load-bearing. The ack is the one thing standing between us and the server
     * forgetting the message, so anything that has to outlive the server's copy must be written
     * first. Acking first spent that guarantee before the replay nonce and the peer ratchet were
     * committed, and a crash in the gap left the message gone from the server with neither written.
     *
     * The ack is still sent when persistence fails, deliberately: the incoming queue is processed in
     * order, so withholding it would have the server redeliver this same message on every reconnect
     * forever and wedge every message behind it. That is the same reasoning under which [run]
     * already acks a message whose processing threw. What changes here is that the failure is
     * reported as one rather than passing unnoticed. A [NetworkException] is NOT caught: there the
     * ack could not be delivered anyway and the task manager reconnects and receives the message
     * again, which is the outcome we want.
     */
    @VisibleForTesting
    internal suspend fun acknowledgeMessage(
        messageBox: MessageBox,
        protectAgainstReplay: Boolean,
        peerRatchetIdentifier: PeerRatchetIdentifier?,
        handle: ActiveTaskCodec,
    ) {
        runCatchingExceptNetworkException {
            // If the message should be protected against replay, store the nonce
            if (protectAgainstReplay) {
                storeNonceForReplayProtection(messageBox)
            }

            // If there is a peer ratchet identifier known, then turn the peer ratchet
            peerRatchetIdentifier?.let {
                forwardSecurityMessageProcessor.commitPeerRatchet(it, handle)
            }
        }.onFailure { e ->
            logger.error(
                "Could not persist that message {} from {} was processed; acknowledging it anyway to keep the queue moving",
                messageBox.messageId,
                messageBox.fromIdentity,
                e,
            )
        }

        // If the no-server-ack message flag is not set, send a message-ack to the server
        if (!messageBox.hasFlag(ProtocolDefines.MESSAGE_FLAG_NO_SERVER_ACK)) {
            sendAck(messageBox.messageId, messageBox.fromIdentity, handle)
        }
    }

    /**
     * Store the message's nonce so that a redelivery of it is dropped by the dedup check in
     * [processMessage] instead of being processed a second time.
     *
     * [NonceFactory.store] answers false to two opposite outcomes: the nonce was already stored,
     * which is exactly what an at-least-once redelivery looks like and is fine, and the insert
     * failed, which means this message is NOT protected against replay. Only the second is a defect,
     * so they are told apart here instead of both passing unremarked.
     */
    private fun storeNonceForReplayProtection(messageBox: MessageBox) {
        val nonce = try {
            Nonce(messageBox.nonce)
        } catch (e: IllegalArgumentException) {
            logger.error("Cannot protect message {} against replay due to invalid nonce", messageBox.messageId, e)
            return
        }

        if (nonceFactory.store(NonceScope.CSP, nonce)) {
            return
        }
        if (nonceFactory.exists(NonceScope.CSP, nonce)) {
            logger.info("Nonce of message {} was already stored", messageBox.messageId)
        } else {
            logger.error(
                "Could not store the nonce of message {} from {}; it is not protected against replay",
                messageBox.messageId,
                messageBox.fromIdentity,
            )
        }
    }

    private suspend fun sendAck(messageId: MessageId, identity: IdentityString, handle: ActiveTaskCodec) {
        logger.debug("Sending ack for message ID {} from {}", messageId, identity)

        val data = identity.encodeToByteArray() + messageId.messageId

        handle.write(CspMessage(ProtocolDefines.PLTYPE_INCOMING_MESSAGE_ACK.toUByte(), data))
    }

    private suspend fun reflectMessage(
        message: AbstractMessage,
        cspNonce: ByteArray,
        handle: ActiveTaskCodec,
    ): ULong? {
        val multiDeviceProperties = multiDeviceManager.propertiesProvider.get()
        val encryptedEnvelopeResult = getEncryptedIncomingMessageEnvelope(
            message,
            cspNonce,
            multiDeviceProperties.mediatorDeviceId,
            multiDeviceProperties.keys,
        )

        if (encryptedEnvelopeResult == null) {
            logger.error("Cannot reflect message")
            return null
        }

        return handle.reflectAndAwaitAck(
            encryptedEnvelopeResult = encryptedEnvelopeResult,
            storeD2dNonce = message.protectAgainstReplay(),
            nonceFactory = nonceFactory,
        )
    }

    private fun setIdentityStateToActive(identity: IdentityString) {
        val contactModel = contactModelRepository.getByIdentity(identity)
        try {
            // Note: Actually, this change is triggered by remote and not by local. However, this is
            // not a change that should prevent the message from being further processed. Therefore,
            // we use 'from local' as this just schedules a persistent task that will reflect the
            // change after the message has been processed completely.
            contactModel?.setActivityStateFromLocal(IdentityState.ACTIVE)
        } catch (e: ModelDeletedException) {
            logger.warn("The model has been deleted", e)
        }
    }

    private suspend fun updateNicknameIfChanged(
        fromIdentity: IdentityString,
        nickname: String,
        handle: ActiveTaskCodec,
    ) {
        val contactModel = contactModelRepository.getByIdentity(fromIdentity) ?: run {
            // This can happen for example if a group message is received from a member that is
            // not in the group anymore and has been deleted.
            logger.warn("Contact data is null. Nickname cannot be updated.")
            return
        }
        contactModel.setNicknameFromRemote(nickname, handle)
    }

    private fun ContactStore.cacheContact(contactModelData: ContactModelData) {
        addCachedContact(contactModelData.toBasicContact())
    }

    private suspend fun createDirectContact(
        contactModelData: ContactModelData,
        nickname: String?,
        handle: ActiveTaskCodec,
    ) {
        // TODO(ANDR-3105): Remove this once predefined contacts are implemented correctly
        val verificationLevel =
            if (ContactServiceImpl.TRUSTED_PUBLIC_KEYS.contains(contactModelData.publicKey)) {
                VerificationLevel.FULLY_VERIFIED
            } else {
                contactModelData.verificationLevel
            }

        // Create new contact
        contactModelRepository.createFromRemote(
            contactModelData = contactModelData.copy(
                nickname = nickname,
                verificationLevel = verificationLevel,
            ),
            handle = handle,
        )
    }

    private suspend fun executeMessageSteps(
        message: AbstractMessage,
        handle: ActiveTaskCodec,
    ) {
        val result = try {
            getSubTaskFromMessage(message, TriggerSource.REMOTE, serviceManager).run(handle)
        } catch (e: Exception) {
            when (e) {
                // If a network exception is thrown, we cancel processing the message to start over
                // when the connection has been restarted.
                is NetworkException -> throw e
                // Any other exception should never be thrown. If there is one, we discard the
                // message. Note that we also acknowledge discarded messages towards the server.
                else -> {
                    logger.error("Error while processing incoming message", e)
                    throw DiscardMessageException(message)
                }
            }
        }

        if (result == ReceiveStepsResult.DISCARD) {
            throw DiscardMessageException(message)
        }

        clearSenderTyping(message)
    }

    /**
     * F1Whisper: a message from X means X is no longer composing it.
     *
     * Until now nothing cleared a sender's typing state except another typing message: the only callers of
     * `ContactServiceImpl.setIsTyping` and `GroupServiceImpl.setMemberTyping` were the two incoming typing indicator
     * tasks. That made the explicit "stopped typing" message load-bearing, and it is the more expensive half of a
     * typing burst - typing indicators were 54% of all outbound end-to-end sends in 13 days of device logs, and 54% of
     * everything wrapped in Forward Security. Deriving the stop from the message that caused it is free on the wire,
     * and strictly faster: it lands with the message rather than as a separate send behind it.
     *
     * The rule is scoped to messages the composer produces, which is exactly the set that asks for a push
     * (`MessageFlags.flagSendPush`): text, media, location, polls, edits, deletes and reactions. Delivery receipts,
     * poll votes and group control messages do not clear it, because they can legitimately arrive WHILE the peer is
     * typing.
     *
     * Only the REMOTE path needs this. A reflected incoming message reaches a linked device that was never told about
     * the typing in the first place: typing indicators are deliberately not reflected.
     */
    private fun clearSenderTyping(message: AbstractMessage) {
        if (!message.flagSendPush()) {
            return
        }
        val senderIdentity = message.fromIdentity ?: return
        try {
            if (message is AbstractGroupMessage) {
                val group = serviceManager.groupService.getByGroupMessage(message) ?: return
                serviceManager.groupService.setMemberTyping(group.id.toLong(), senderIdentity, false)
            } else {
                contactService.setIsTyping(senderIdentity, false)
            }
        } catch (e: Exception) {
            // Never let a cosmetic state update fail a message that was already processed successfully.
            logger.warn("Could not clear the typing state of {}", senderIdentity, e)
        }
    }

    private fun updateReceivedTimestamp(message: AbstractMessage, receivedTimestamp: ULong) {
        if (message is AbstractGroupMessage) {
            messageService.getGroupMessageModel(
                message.messageId,
                message.groupCreator,
                message.apiGroupId,
            )
        } else {
            messageService.getContactMessageModel(message.messageId, message.fromIdentity)
        }?.let {
            // Note that for incoming messages the received timestamp is stored in 'createdAt'
            //
            // F1Whisper (sixth fork review, F6-01): one conditional column write. The model here is resolved through the
            // service message cache, so full-row-saving it wrote back whatever snapshot was cached - which for a message
            // being processed right now can already be behind its own freeze or a reflected read.
            messageService.updateReceivedTimestamp(it, Date(receivedTimestamp.toLong()))
        }
    }

    private class DiscardMessageException(
        val discardedMessage: AbstractMessage?,
        val peerRatchetIdentifier: PeerRatchetIdentifier?,
    ) : Exception() {
        constructor() : this(null, null)
        constructor(discardedMessage: AbstractMessage?) : this(discardedMessage, null)
    }
}
