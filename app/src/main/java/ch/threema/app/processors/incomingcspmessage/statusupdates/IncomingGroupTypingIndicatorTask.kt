package ch.threema.app.processors.incomingcspmessage.statusupdates

import ch.threema.app.managers.ServiceManager
import ch.threema.app.processors.incomingcspmessage.IncomingCspMessageSubTask
import ch.threema.app.processors.incomingcspmessage.ReceiveStepsResult
import ch.threema.app.processors.incomingcspmessage.groupcontrol.runCommonGroupReceiveSteps
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.protocol.csp.messages.GroupTypingIndicatorMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.TriggerSource

private val logger = getThreemaLogger("IncomingGroupTypingIndicatorTask")

/**
 * F1Whisper: handle an incoming group typing indicator by updating the in-memory group typing
 * state (which drives the "... is typing" line in the group conversation header).
 */
class IncomingGroupTypingIndicatorTask(
    message: GroupTypingIndicatorMessage,
    triggerSource: TriggerSource,
    serviceManager: ServiceManager,
) : IncomingCspMessageSubTask<GroupTypingIndicatorMessage>(message, triggerSource, serviceManager) {
    private val groupService = serviceManager.groupService

    override suspend fun executeMessageStepsFromRemote(handle: ActiveTaskCodec): ReceiveStepsResult {
        val groupModel = runCommonGroupReceiveSteps(message, handle, serviceManager)
            ?: return ReceiveStepsResult.DISCARD
        val fromIdentity = message.fromIdentity ?: return ReceiveStepsResult.DISCARD

        groupService.setMemberTyping(groupModel.getDatabaseId(), fromIdentity, message.isTyping)
        return ReceiveStepsResult.SUCCESS
    }

    // Group typing indicators are throw-away and not reflected; nothing to do on sync.
    override suspend fun executeMessageStepsFromSync(): ReceiveStepsResult = ReceiveStepsResult.DISCARD
}
