package ch.threema.app.processors.incomingcspmessage.groupcontrol

import ch.threema.app.managers.ServiceManager
import ch.threema.app.processors.incomingcspmessage.IncomingCspMessageSubTask
import ch.threema.app.processors.incomingcspmessage.ReceiveStepsResult
import ch.threema.app.services.DisappearingMessageService
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.protocol.csp.messages.DisappearingTimerMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.TriggerSource

private val logger = getThreemaLogger("IncomingDisappearingTimerTask")

/**
 * F1Whisper: handle an incoming 1:1 disappearing-timer control message (type 0x85).
 *
 * On receive:
 * 1. Look up the contact — discard if unknown.
 * 2. Persist the peer's advertised timer on [ContactModel.peerDisappearingTimerSeconds].
 *    Adopt-if-unset: if the user has never set their own timer, mirror the peer's value into
 *    [ContactModel.disappearingMessagesTimerSeconds] so a one-sided enable gives a shared feel.
 *    An explicit user choice (including OFF = 0) is NEVER overwritten.
 * 3. Insert a local DISAPPEARING_STATUS status message ("X set disappearing messages to Y").
 *    Suppressed when the incoming value equals the previously-stored peer value (piggyback re-assert).
 * 4. Re-arm the disappearing alarm.
 */
class IncomingDisappearingTimerTask(
    message: DisappearingTimerMessage,
    triggerSource: TriggerSource,
    serviceManager: ServiceManager,
) : IncomingCspMessageSubTask<DisappearingTimerMessage>(message, triggerSource, serviceManager) {

    private val contactService = serviceManager.contactService
    private val messageService by lazy { serviceManager.messageService }
    private val databaseService = serviceManager.databaseService

    override suspend fun executeMessageStepsFromRemote(handle: ActiveTaskCodec): ReceiveStepsResult =
        processTimer()

    override suspend fun executeMessageStepsFromSync(): ReceiveStepsResult =
        ReceiveStepsResult.DISCARD

    private fun processTimer(): ReceiveStepsResult {
        val fromIdentity = message.fromIdentity ?: run {
            logger.warn("Disappearing timer message has no fromIdentity; discarding")
            return ReceiveStepsResult.DISCARD
        }

        val contact = contactService.getByIdentity(fromIdentity) ?: run {
            logger.warn("Unknown contact {} sent disappearing timer; discarding", fromIdentity)
            return ReceiveStepsResult.DISCARD
        }

        val timerSeconds = message.timerSeconds
        logger.info("Incoming 1:1 disappearing timer from {}: {}s", fromIdentity, timerSeconds)

        // E2 isReassert: compare the incoming value to the PEER column (not the my-field)
        // so that piggyback re-asserts from the peer are suppressed regardless of our own timer.
        val previousPeerTimer = contact.peerDisappearingTimerSeconds
        val isReassert = (timerSeconds > 0 && timerSeconds == previousPeerTimer) ||
                         (timerSeconds <= 0 && (previousPeerTimer == null || previousPeerTimer <= 0))

        // 1a. Persist peer's advertised timer (0 = peer turned it OFF; null = never advertised).
        contact.setPeerDisappearingTimerSeconds(if (timerSeconds > 0) timerSeconds else 0)

        // 1b. Adopt-if-unset: if the user has never explicitly set their own timer,
        //     mirror the peer's value so a one-sided enable still makes both sides disappear.
        //     An explicit user value (including OFF = 0, i.e. non-null) is NEVER overwritten.
        val myTimer = contact.disappearingMessagesTimerSeconds
        if (myTimer == null) {
            // User has never made an explicit choice — adopt the peer's advertisement.
            contact.setDisappearingMessagesTimerSeconds(if (timerSeconds > 0) timerSeconds else null)
            logger.info(
                "Disappearing adopt-if-unset: mirroring peer timer {}s for contact {}",
                timerSeconds, fromIdentity
            )
        }

        databaseService.contactModelFactory.createOrUpdate(contact)

        // 2. Insert status message in the conversation — suppressed for piggyback re-asserts.
        if (!isReassert) {
            try {
                val receiver = contactService.createReceiver(contact)
                messageService.createDisappearingStatus(receiver, fromIdentity, timerSeconds)
            } catch (e: Exception) {
                logger.warn("Could not insert disappearing status message for contact {}", fromIdentity, e)
            }
        } else {
            logger.debug("E2: suppressing duplicate status row for 1:1 timer re-assert from {}", fromIdentity)
        }

        // 3. Re-arm the disappearing alarm.
        try {
            DisappearingMessageService.getInstance().rescheduleNextAlarm()
        } catch (e: Exception) {
            logger.warn("Could not rearm disappearing alarm after incoming timer from {}", fromIdentity, e)
        }

        return ReceiveStepsResult.SUCCESS
    }
}
