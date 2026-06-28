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
 * GROUP convergence model (Option X): unlike 1:1 (which is per-direction), a group disappearing
 * timer is a SINGLE shared value. Both outgoing and incoming freezing read
 * [GroupModelOld.disappearingMessagesTimerSeconds]; the per-direction peer column is unused for
 * groups. Any member's genuine change is adopted unconditionally (last change wins). There is no
 * group piggyback re-assert (see [DisappearingMessageService.piggybackTimerReassert]), so no stale
 * value is ever re-injected and the group converges on the last genuine change.
 *
 * On receive:
 * 1. Run common group receive steps — discard if the group cannot be found or sender is invalid.
 * 2. Unconditionally adopt the advertised timer into the shared field
 *    [GroupModelOld.disappearingMessagesTimerSeconds] (OFF → null).
 * 3. Insert a local DISAPPEARING_STATUS status message in the group conversation.
 *    Suppressed when the incoming value equals the previously-stored shared value (no real change).
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

        // F1Whisper GROUP convergence fix (Option X — single shared field, pure last-writer-wins).
        // Groups use ONE shared timer (disappearingMessagesTimerSeconds) for BOTH directions; the
        // per-direction peer column is NOT used for groups. Any member's genuine change is adopted
        // unconditionally (last change wins). There is no group piggyback re-assert, so no stale
        // value is ever re-injected → the group converges on the last genuine change (incl. OFF).
        // isReassert baseline is the previous shared value, so a duplicate-of-current advertisement
        // (e.g. a second member who already holds the value) does not re-print a status row.
        val previousSharedTimer = oldGroupModel.disappearingMessagesTimerSeconds
        val isReassert = (timerSeconds > 0 && timerSeconds == previousSharedTimer) ||
                         (timerSeconds <= 0 && (previousSharedTimer == null || previousSharedTimer <= 0))

        // Unconditionally adopt the advertised value into the shared group field.
        // OFF (timerSeconds <= 0) stores null (matches the user-OFF convention the picker reads).
        oldGroupModel.setDisappearingMessagesTimerSeconds(if (timerSeconds > 0) timerSeconds else null)

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
