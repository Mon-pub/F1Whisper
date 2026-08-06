package ch.threema.app.services

import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.data.media.BallotDataModel

/**
 * F1Whisper (fourth fork review, F4-08): which expiring message, if any, governs the lifetime of a poll.
 *
 * The defect this exists to close: a poll is not stored in the message that shows it. The carrier message holds only a
 * pointer, while the question, choices, votes and the group/identity links live in their own tables, and the conversation's
 * open-poll surface ([ch.threema.app.ui.OpenBallotNoticeView]) queries those tables directly rather than the message list.
 * Expiry called the generic message removal, which deletes the message row and its files and nothing else, so an expired
 * poll lost its bubble and kept everything else: the question readable, the votes intact, and vote / results / close still
 * offered on the open-poll bar.
 *
 * The rule: **the message that introduced content owns that content's lifetime.** Only the SETUP carrier
 * ([BallotDataModel.Type.BALLOT_CREATED]) introduced the poll, so only it governs.
 *
 * A CLOSE carrier deliberately does not. The setup message may still be perfectly visible, and taking the poll out from
 * under it would be this same defect in reverse: a bubble pointing at a ballot that no longer exists. In practice the setup
 * carrier is the older row, so under any shared timer it expires first and takes the close carrier with it, since removing
 * the aggregate removes every message associated with the ballot.
 *
 * No Android imports, so the rule is unit-testable without a device.
 */
object BallotCarrierDecision {
    /**
     * Whether expiring [model] must also remove the ballot aggregate it points at.
     */
    @JvmStatic
    fun governsBallotAggregate(model: AbstractMessageModel): Boolean =
        governedBallotId(model) != null

    /**
     * The id of the ballot whose lifetime [model] governs, or `null` if it governs none.
     */
    @JvmStatic
    fun governedBallotId(model: AbstractMessageModel): Int? {
        if (model.type != MessageType.BALLOT) return null
        val ballotData: BallotDataModel = model.ballotData ?: return null
        if (ballotData.type != BallotDataModel.Type.BALLOT_CREATED) return null
        return ballotData.ballotId
    }
}
