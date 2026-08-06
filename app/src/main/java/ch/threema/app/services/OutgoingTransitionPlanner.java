package ch.threema.app.services;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;

import java.util.Date;

import ch.threema.app.utils.MessageUtil;
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityMode;
import ch.threema.storage.MessageRowUpdate;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.MessageState;
import ch.threema.storage.models.group.GroupMessageModel;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

/**
 * F1Whisper (sixth fork review, F6-03): everything one outgoing state transition writes, decided in one place from one
 * row and one timestamp.
 *
 * <p><b>Why it is a class of its own.</b> The fifth review collapsed state, state timestamp, terminal-failure marker,
 * forward-security mode and the disappearing countdown into a single conditional write, which was right; but it left the
 * decision inline in a service that cannot be constructed in a unit test, so the only thing a test could reach was the
 * pure clock helper. The sixth review found the defect in exactly the gap that left: the helper was correct and the
 * argument passed to it was not. Here the whole decision - including the real
 * {@link MessageUtil#canChangeToState} gate - is executable against a row read from a real database.</p>
 *
 * <p><b>The defect itself.</b> A message can reach {@code DELIVERED} or {@code READ} before this device has recorded
 * that it was sent: a receipt overtakes the send confirmation, or another device reflects the state. The receipt starts
 * the countdown PROVISIONALLY, from the receipt's own observation time, because that is the earliest departure this
 * device can prove. When the authoritative send time arrives afterwards it is earlier, and the countdown must move back
 * to it - the sender committed to an interval starting when the message left, not when this device found out.</p>
 *
 * <p>That correction was unreachable. Refusing {@code DELIVERED -> SENT} as a display-state downgrade is correct and
 * stays, but the clock decision was then asked about the state the row had ENDED UP with, which after the refusal is
 * still {@code DELIVERED}, so it never saw a {@code SENT} to take the timestamp from. Authority and display are two
 * different questions of the same transition and are now asked separately: the countdown may only start when the
 * PERSISTED state proves the message left the device, and only the state being PROCESSED decides whose timestamp is
 * authoritative.</p>
 */
public final class OutgoingTransitionPlanner {
    private static final Logger logger = getThreemaLogger("OutgoingTransitionPlanner");

    private OutgoingTransitionPlanner() {
    }

    /**
     * Decide what {@code current}'s row must become, mutating {@code current} into the post-write values so the caller
     * can mirror them onto the instance it was given.
     *
     * @param current             a model read from the row this transition is about to write. Mutated.
     * @param state               the state being processed - NOT necessarily the state that ends up persisted.
     * @param transitionAt        the timestamp OF that transition, never "now".
     * @param forwardSecurityMode written in the same transition when the caller has it; {@code null} leaves the column
     *                            alone.
     * @param bypassStateGate     set by the two callers that deliberately record a state {@code canChangeToState} would
     *                            refuse: the group completion, which must stamp {@code postedAt} even when the outcome
     *                            is {@code FS_KEY_MISMATCH}, and the task-layer terminal failure, which must set the
     *                            non-retryable marker for a message in any state at all.
     * @return the write to perform, or {@code null} if this transition changes nothing.
     */
    @Nullable
    public static MessageRowUpdate plan(
        @NonNull AbstractMessageModel current,
        @NonNull MessageState state,
        @NonNull Date transitionAt,
        @Nullable ForwardSecurityMode forwardSecurityMode,
        boolean bypassStateGate
    ) {
        final MessageRowUpdate.Builder update = MessageRowUpdate.builder();
        boolean hasChanges = false;

        // Save date of state change
        switch (state) {
            case SENT:
                // Note that we do not check whether the posted at time already exists as this
                // value is already set when the message model has been created. We just update
                // it when the message actually has been sent.
                current.setPostedAt(transitionAt);
                update.set(AbstractMessageModel.COLUMN_POSTED_AT, transitionAt);
                hasChanges = true;
                break;
            case DELIVERED:
                if (current.getDeliveredAt() != null) {
                    logger.warn("'Delivered at' already set for message {}", current.getApiMessageId());
                }
                current.setDeliveredAt(transitionAt);
                update.set(AbstractMessageModel.COLUMN_DELIVERED_AT, transitionAt);
                hasChanges = true;
                break;
            case READ:
                if (current.getReadAt() != null) {
                    logger.warn("'Read at' already set for message {}", current.getApiMessageId());
                }
                current.setReadAt(transitionAt);
                update.set(AbstractMessageModel.COLUMN_READ_AT, transitionAt);
                hasChanges = true;
                break;
            case SENDFAILED:
            case FS_KEY_MISMATCH:
            case CONSUMED:
                hasChanges = true;
                break;
            default:
                break;
        }
        if (hasChanges) {
            current.setModifiedAt(transitionAt);
            update.set(AbstractMessageModel.COLUMN_MODIFIED_AT, transitionAt);
        }

        final MessageState priorState = current.getState();
        final Long priorStart = current.getExpireStartedAt();

        // Change the state only if it is possible
        final boolean stateChanges = bypassStateGate
            || MessageUtil.canChangeToState(priorState, state, current instanceof GroupMessageModel);
        if (stateChanges) {
            current.setState(state);
            update.set(AbstractMessageModel.COLUMN_STATE, state.toString());
            hasChanges = true;

            // F1Whisper auto-resend: the terminal-failure marker is maintained centrally here
            // because every transition INTO SENDFAILED is terminal by construction - the media
            // pipeline's transient connectivity failures never reach SENDFAILED (they go through
            // markConnectivityPending instead). So set the terminal bit whenever we enter
            // SENDFAILED (so the reconnect scan skips it and the nag still fires), and clear it
            // whenever the message leaves the failed state (manual/auto resend -> SENDING/
            // PENDING, or a success state), so a later transient failure stays auto-eligible.
            if (state == MessageState.SENDFAILED) {
                if (!current.isSendFailedTerminal()) {
                    current.setSendFailedTerminal(true);
                    update.set(AbstractMessageModel.COLUMN_DISPLAY_TAGS, current.getDisplayTags());
                }
            } else if (current.isSendFailedTerminal()) {
                current.setSendFailedTerminal(false);
                update.set(AbstractMessageModel.COLUMN_DISPLAY_TAGS, current.getDisplayTags());
            }
        } else {
            logger.warn(
                "State transition from {} to {}, ignoring",
                priorState, state
            );
        }

        if (forwardSecurityMode != null && forwardSecurityMode != current.getForwardSecurityMode()) {
            current.setForwardSecurityMode(forwardSecurityMode);
            update.set(AbstractMessageModel.COLUMN_FORWARD_SECURITY_MODE, forwardSecurityMode.getValue());
            hasChanges = true;
        }

        // F1Whisper (fourth fork review, F4-04 / fifth, F5-06 / sixth, F6-03): the countdown starts when, and only when,
        // the state on the row says the message has left the device - and it is persisted in the SAME write as that
        // state, from the SAME timestamp. Not "now": a SENT update reflected from another device carries an
        // authoritative send time, and discarding it extended the message's life by the reflection delay. The state
        // being PROCESSED decides whose timestamp is authoritative, which is why a refused DELIVERED -> SENT downgrade
        // can still shorten the countdown while leaving the higher display state alone.
        final Long resolvedStart = OutgoingClockDecision.resolveStart(
            state,
            current.getState(),
            transitionAt.getTime(),
            priorStart
        );
        final Long deadline = resolvedStart == null
            ? null
            : OutgoingClockDecision.deadlineFor(resolvedStart, current.getDisappearingTimerSeconds());
        if (deadline != null) {
            current.setExpireStartedAt(resolvedStart);
            current.setExpiresAt(deadline);
            update.set(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT, resolvedStart)
                .set(AbstractMessageModel.COLUMN_EXPIRES_AT, deadline);
            // The start may only ever move earlier, so the row must still hold the start this decision saw.
            update.expect(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT, priorStart);
            hasChanges = true;
        }

        if (!hasChanges) {
            return null;
        }
        final MessageRowUpdate built = update.build();
        if (built.isEmpty()) {
            // A state the switch does not stamp and the gate refused: nothing to persist.
            return null;
        }
        return built;
    }
}
