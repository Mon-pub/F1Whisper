package ch.threema.app.processors.incomingcspmessage.groupcontrol

import ch.threema.app.managers.ServiceManager
import ch.threema.app.processors.incomingcspmessage.IncomingCspMessageSubTask
import ch.threema.app.processors.incomingcspmessage.ReceiveStepsResult
import ch.threema.app.services.DisappearingMessageService
import ch.threema.app.services.DisappearingTimerConvergence
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.protocol.csp.messages.DisappearingTimerMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.TriggerSource

private val logger = getThreemaLogger("IncomingDisappearingTimerTask")

/**
 * F1Whisper: handle an incoming 1:1 disappearing-timer control message (type 0x85).
 *
 * 1:1 convergence model (group parity — single shared field, last-writer-wins): a 1:1 conversation has ONE shared timer,
 * [ContactModel.disappearingMessagesTimerSeconds], governing BOTH the outgoing and the incoming freeze. Every incoming
 * advertisement overwrites it **unconditionally, OFF included** — so turning the timer off converges instead of being
 * silently re-adopted. This replaces the previous per-direction model, in which a user OFF stored `null` meaning "clear my
 * override / follow the peer", so any later advertisement silently re-enabled the timer.
 *
 * The adopt is deliberately NOT gated. An earlier build gated it on "does this differ from what the peer itself last
 * advertised?" (keeping [ContactModel.peerDisappearingTimerSeconds] alive as a change-detector) to shield an updated client
 * from an un-updated v6.4.3-37 peer's 5-minute re-assert. That gate dropped genuine changes on device, because a peer's
 * state moves **without advertising** whenever it adopts ours — see the analysis on
 * [DisappearingTimerConvergence]. The peer column is now dead for 1:1 as well: nothing reads or writes it.
 *
 * The status row is still gated, on a different question — [DisappearingTimerConvergence.changesSharedTimer] asks whether
 * this conversation's timer actually moved — so a redelivered or re-asserted advertisement that lands on the value already
 * in force is applied silently rather than printing a row out of nowhere.
 *
 * On receive:
 * 1. Look up the contact — discard if unknown.
 * 2. Read the shared field before the write, to decide whether the timer really moves.
 * 3. Unconditionally adopt the advertised value into the shared conversation timer (OFF → null).
 * 4. Insert a local DISAPPEARING_STATUS status message ("X set disappearing messages to Y") only when the adopt actually
 *    moved the conversation's timer.
 * 5. Re-arm the disappearing alarm.
 *
 * Design and rationale: `.claude/tasks/disappearing-timer-1to1-shared-lww.md`.
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

        // Read the shared field BEFORE the write: it answers whether this conversation's timer actually moves, which is
        // what the status row is a statement about. It does NOT gate the adopt — see the class KDoc.
        val previousShared = contact.disappearingMessagesTimerSeconds
        val changesTimer = DisappearingTimerConvergence.changesSharedTimer(previousShared, timerSeconds)

        // 1. Shared-field last-writer-wins: unconditionally overwrite the ONE conversation timer that governs both freeze
        //    directions. OFF wins like any other value (stored as null). Identical to the group task.
        contact.setDisappearingMessagesTimerSeconds(DisappearingTimerConvergence.toSharedField(timerSeconds))

        databaseService.contactModelFactory.createOrUpdate(contact)

        logger.info(
            "Disappearing 1:1 timer from {}: {}s (shared was {}), changesSharedTimer={}, shared timer now {}",
            fromIdentity,
            timerSeconds,
            previousShared,
            changesTimer,
            contact.disappearingMessagesTimerSeconds,
        )

        // 2. Insert status message in the conversation — only when the conversation's timer actually moved.
        if (changesTimer) {
            try {
                val receiver = contactService.createReceiver(contact)
                messageService.createDisappearingStatus(receiver, fromIdentity, timerSeconds)
            } catch (e: Exception) {
                logger.warn("Could not insert disappearing status message for contact {}", fromIdentity, e)
            }
        } else {
            logger.info(
                "Disappearing: no status row for 1:1 timer from {} ({}s) — the conversation timer did not move",
                fromIdentity,
                timerSeconds,
            )
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
