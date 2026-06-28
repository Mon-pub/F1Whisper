package ch.threema.app.processors.incomingcspmessage.groupcontrol

import ch.threema.app.managers.ServiceManager
import ch.threema.app.processors.incomingcspmessage.IncomingCspMessageSubTask
import ch.threema.app.processors.incomingcspmessage.ReceiveStepsResult
import ch.threema.app.services.DisappearingMessageService
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.protocol.csp.messages.GroupDisappearingTimerMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.TriggerSource

private val logger = getThreemaLogger("IncomingGroupDisappearingTimerTask")

/**
 * F1Whisper: handle an incoming group disappearing-timer control message (type 0x95).
 *
 * On receive:
 * 1. Run common group receive steps — discard if the group cannot be found or sender is invalid.
 * 2. Persist the peer's advertised timer on [GroupModelOld.peerDisappearingTimerSeconds].
 *    Adopt-if-unset: if no explicit group timer has been set locally, mirror the peer's value so a
 *    one-sided enable still gives a shared feel.  An explicit local choice (incl. OFF = 0) is NEVER
 *    overwritten.
 * 3. Insert a local DISAPPEARING_STATUS status message in the group conversation.
 *    Suppressed when the incoming value equals the previously-stored peer value (piggyback re-assert).
 * 4. Re-arm the disappearing alarm.
 */
class IncomingGroupDisappearingTimerTask(
    message: GroupDisappearingTimerMessage,
    triggerSource: TriggerSource,
    serviceManager: ServiceManager,
) : IncomingCspMessageSubTask<GroupDisappearingTimerMessage>(message, triggerSource, serviceManager) {

    private val messageService by lazy { serviceManager.messageService }
    private val groupService = serviceManager.groupService
    private val databaseService = serviceManager.databaseService

    override suspend fun executeMessageStepsFromRemote(handle: ActiveTaskCodec): ReceiveStepsResult {
        val groupModel = runCommonGroupReceiveSteps(message, handle, serviceManager) ?: run {
            logger.warn("Group disappearing timer: common receive steps failed; discarding")
            return ReceiveStepsResult.DISCARD
        }

        val fromIdentity = message.fromIdentity ?: run {
            logger.warn("Group disappearing timer message has no fromIdentity; discarding")
            return ReceiveStepsResult.DISCARD
        }

        val timerSeconds = message.timerSeconds
        logger.info(
            "Incoming group disappearing timer from {} for group {}: {}s",
            fromIdentity,
            message.apiGroupId,
            timerSeconds,
        )

        val oldGroupModel = groupService.getById(groupModel.getDatabaseId()) ?: run {
            logger.warn("Could not find GroupModelOld for group {}", message.apiGroupId)
            return ReceiveStepsResult.DISCARD
        }

        // E2 isReassert: compare incoming value to the PEER column (not the my-field)
        // so piggyback re-asserts are suppressed independently of the local group timer.
        val previousPeerTimer = oldGroupModel.peerDisappearingTimerSeconds
        val isReassert = (timerSeconds > 0 && timerSeconds == previousPeerTimer) ||
                         (timerSeconds <= 0 && (previousPeerTimer == null || previousPeerTimer <= 0))

        // 1a. Persist peer's advertised timer (0 = peer turned it OFF; null = never advertised).
        oldGroupModel.setPeerDisappearingTimerSeconds(if (timerSeconds > 0) timerSeconds else 0)

        // 1b. Adopt-if-unset: if no local group timer has ever been explicitly configured,
        //     mirror the sender's value so the group conversation disappears for everyone.
        //     A non-null local value (including OFF = 0) is NEVER overwritten.
        val myTimer = oldGroupModel.disappearingMessagesTimerSeconds
        if (myTimer == null) {
            oldGroupModel.setDisappearingMessagesTimerSeconds(if (timerSeconds > 0) timerSeconds else null)
            logger.info(
                "Disappearing adopt-if-unset: mirroring peer timer {}s for group {}",
                timerSeconds, message.apiGroupId
            )
        }

        databaseService.groupModelFactory.update(oldGroupModel)

        // 2. Insert status message — suppressed for piggyback re-asserts.
        if (!isReassert) {
            try {
                val receiver = groupService.createReceiver(oldGroupModel)
                messageService.createDisappearingStatus(receiver, fromIdentity, timerSeconds)
            } catch (e: Exception) {
                logger.warn("Could not insert group disappearing status message", e)
            }
        } else {
            logger.debug("E2: suppressing duplicate status row for group timer re-assert from {}", fromIdentity)
        }

        // 3. Re-arm the disappearing alarm.
        try {
            DisappearingMessageService.getInstance().rescheduleNextAlarm()
        } catch (e: Exception) {
            logger.warn("Could not rearm disappearing alarm after incoming group timer", e)
        }

        return ReceiveStepsResult.SUCCESS
    }

    override suspend fun executeMessageStepsFromSync(): ReceiveStepsResult =
        ReceiveStepsResult.DISCARD
}
