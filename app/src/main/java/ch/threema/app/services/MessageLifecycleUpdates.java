package ch.threema.app.services;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import ch.threema.storage.MessageRowUpdate;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.MessageState;
import ch.threema.storage.models.group.GroupMessageModel;

/**
 * F1Whisper (sixth fork review, F6-01): the exact {@link MessageRowUpdate} each converted writer performs.
 *
 * <p><b>Why these are here rather than inline.</b> The fifth review replaced the lifecycle transitions' full-row saves
 * with conditional column-scoped writes, but left four ordinary operations - an incoming edit, a group delivery receipt,
 * a star toggle and a pin toggle - still saving the WHOLE row from a model that had been loaded before the transition
 * ran. Each of them therefore wrote a pre-transition snapshot straight back: a started countdown cancelled, a read
 * message unread again, a terminal state regressed. Each one owns exactly one or two columns, and this is what each of
 * them now writes.</p>
 *
 * <p>Every update inherits the two structural predicates from {@link MessageRowUpdate}: the row must still exist (so a
 * message claimed by expiry or hard-deleted mid-operation is never recreated) and must not be deleted for everyone.
 * Where the new value was DERIVED from a value read a moment earlier - an edited body, a merged receipt map, a toggled
 * tag bitmask - the read value is also an expected-current-value condition, so a decision made against a stale read is
 * refused and recomputed rather than applied.</p>
 *
 * <p>Android-free on purpose: it is the only way the statement that actually ships can be executed against a real
 * database in a JVM test instead of being asserted as a string.</p>
 */
public final class MessageLifecycleUpdates {

    private MessageLifecycleUpdates() {
    }

    /**
     * Mark a message read, and start its countdown if this read starts one.
     *
     * @param countdown the countdown to persist, or {@code null} if reading starts none.
     */
    @NonNull
    public static MessageRowUpdate firstRead(
        @NonNull Date readAt,
        @Nullable Integer priorTimerSeconds,
        @Nullable Long priorStart,
        @Nullable Long priorExpiresAt,
        @Nullable FirstReadDecision.Countdown countdown
    ) {
        final MessageRowUpdate.Builder update = MessageRowUpdate.builder()
            .set(AbstractMessageModel.COLUMN_IS_READ, true)
            .set(AbstractMessageModel.COLUMN_READ_AT, readAt)
            .set(AbstractMessageModel.COLUMN_MODIFIED_AT, readAt)
            // Still unread, and the countdown fields still as the decision read them.
            .expect(AbstractMessageModel.COLUMN_IS_READ, false)
            .expect(AbstractMessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS, priorTimerSeconds)
            .expect(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT, priorStart)
            .expect(AbstractMessageModel.COLUMN_EXPIRES_AT, priorExpiresAt);
        if (countdown != null) {
            update.set(AbstractMessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS, countdown.timerSeconds)
                .set(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT, countdown.startedAt)
                .set(AbstractMessageModel.COLUMN_EXPIRES_AT, countdown.expiresAt);
        }
        return update.build();
    }

    /**
     * Store an edited body (and, for a file message, the caption that lives inside it).
     *
     * <p>The new body is computed from the row this update is conditional on, so an edit composes with a concurrent
     * write to the same body - a media download recording its arrival, a listen-once claim - instead of discarding it.</p>
     */
    @NonNull
    public static MessageRowUpdate edit(
        @Nullable String editedBody,
        @Nullable String editedCaption,
        @Nullable Date editedAt,
        @Nullable String priorBody
    ) {
        return MessageRowUpdate.builder()
            .set(AbstractMessageModel.COLUMN_BODY, editedBody)
            .set(AbstractMessageModel.COLUMN_CAPTION, editedCaption)
            .set(AbstractMessageModel.COLUMN_EDITED_AT, editedAt)
            .expect(AbstractMessageModel.COLUMN_BODY, priorBody)
            .build();
    }

    /**
     * Record what a media operation - a download completing, a listen-once claim, a burn, a consume - changed about a
     * message's serialised media metadata.
     *
     * <p>The media flags live INSIDE the body, so two operations deciding from two reads of it would otherwise discard
     * one another's flags; the body is therefore both the assignment and the compare-and-set condition. The state is a
     * condition too, so a message consumed or re-sent under the operation is not overwritten by a decision taken
     * before that.</p>
     *
     * @param captionChanged whether {@code newCaption} must be written. The legacy image format carries its caption in
     *                       the EXIF of the blob, so the download completion is the only moment it exists.
     * @param consumedAt     non-null to move the message to {@code CONSUMED} in the same statement.
     */
    @NonNull
    public static MessageRowUpdate mediaMetadata(
        @Nullable String newBody,
        @Nullable String priorBody,
        @Nullable MessageState priorState,
        boolean captionChanged,
        @Nullable String newCaption,
        @Nullable Date consumedAt
    ) {
        final MessageRowUpdate.Builder update = MessageRowUpdate.builder()
            .set(AbstractMessageModel.COLUMN_BODY, newBody)
            .expect(AbstractMessageModel.COLUMN_BODY, priorBody)
            .expect(AbstractMessageModel.COLUMN_STATE, priorState != null ? priorState.toString() : null);
        if (captionChanged) {
            update.set(AbstractMessageModel.COLUMN_CAPTION, newCaption);
        }
        if (consumedAt != null) {
            update.set(AbstractMessageModel.COLUMN_STATE, MessageState.CONSUMED.toString())
                .set(AbstractMessageModel.COLUMN_MODIFIED_AT, consumedAt);
        }
        return update.build();
    }

    /**
     * Record one member's delivery or read receipt in a group message's per-member state map.
     *
     * <p>The map is one serialised column, so two receipts arriving together are a lost-update race in their own right;
     * the merged value is therefore conditional on the value it was merged into.</p>
     */
    @NonNull
    public static MessageRowUpdate groupReceipt(@Nullable String mergedStates, @Nullable String priorStates) {
        return MessageRowUpdate.builder()
            .set(GroupMessageModel.COLUMN_GROUP_MESSAGE_STATES, mergedStates)
            .expect(GroupMessageModel.COLUMN_GROUP_MESSAGE_STATES, priorStates)
            .build();
    }

    /**
     * The serialised form of a group message's per-member state map, exactly as
     * {@code GroupMessageModelFactory.addGroupMessageStates} writes it, so a conditional write and a full-row save can
     * never disagree about what the same map looks like on disk.
     */
    @Nullable
    public static String serialiseGroupMessageStates(@Nullable Map<String, Object> states) {
        if (states == null || states.isEmpty()) {
            return null;
        }
        return new JSONObject(states).toString();
    }

    /**
     * Merge one member's receipt into {@code states}, or return {@code null} if it changes nothing.
     *
     * <p>A late {@code DELIVERED} must never overwrite a {@code READ} that has already arrived from the same member.</p>
     */
    @Nullable
    public static Map<String, Object> mergeGroupReceipt(
        @Nullable Map<String, Object> states,
        @NonNull String fromIdentity,
        @NonNull MessageState state
    ) {
        final Object existing = states != null ? states.get(fromIdentity) : null;
        if (MessageState.READ.toString().equals(existing) && state == MessageState.DELIVERED) {
            return null;
        }
        if (state.toString().equals(existing)) {
            return null;
        }
        final Map<String, Object> merged = states != null ? new HashMap<>(states) : new HashMap<>();
        merged.put(fromIdentity, state.toString());
        return merged;
    }

    /**
     * Set a message's display-tag bitmask (starred, pinned, the terminal-failure marker).
     *
     * <p>Conditional on the bitmask the toggle was computed from, so a star that is applied while a pin is being applied
     * is refused and recomputed rather than silently clearing the other bit.</p>
     */
    @NonNull
    public static MessageRowUpdate displayTags(int newTags, int priorTags) {
        return MessageRowUpdate.builder()
            .set(AbstractMessageModel.COLUMN_DISPLAY_TAGS, newTags)
            .expect(AbstractMessageModel.COLUMN_DISPLAY_TAGS, priorTags)
            .build();
    }

    /**
     * Store the timestamp at which an incoming message was received.
     *
     * <p>The sort key is written in the same statement because it is DERIVED from the created-at this writes (see
     * {@code TimelineKeyset.effectiveSortDate}); writing one without the other would leave the row ordered by a
     * timestamp it no longer carries.</p>
     */
    @NonNull
    public static MessageRowUpdate receivedTimestamp(@NonNull Date receivedAt, @Nullable Date sortAt) {
        return MessageRowUpdate.builder()
            .set(AbstractMessageModel.COLUMN_CREATED_AT, receivedAt)
            .set(AbstractMessageModel.COLUMN_SORT_AT, sortAt)
            .build();
    }

    /**
     * F1Whisper (seventh fork review, F7-05): put a message back to its underlying delivery state after its reaction
     * (the legacy {@code USERACK} / {@code USERDEC} encoding) has been withdrawn.
     *
     * <p>Conditional on the reaction state it was computed from, so a message that has meanwhile moved on - a later
     * receipt, a re-send - is left alone rather than being pushed backwards. It used to be a full-row save of the model
     * the reaction repository was holding, which is a timeline instance and therefore a snapshot from before whatever
     * the row had done since.</p>
     *
     * @param clearsGroupStates whether the per-member receipt map, from which this user's own entry has just been
     *                          removed, must be written too.
     */
    @NonNull
    public static MessageRowUpdate clearedReactionState(
        @NonNull MessageState newState,
        @Nullable MessageState priorState,
        boolean clearsGroupStates,
        @Nullable String mergedStates,
        @Nullable String priorStates
    ) {
        final MessageRowUpdate.Builder update = MessageRowUpdate.builder()
            .set(AbstractMessageModel.COLUMN_STATE, newState.toString())
            .expect(AbstractMessageModel.COLUMN_STATE, priorState != null ? priorState.toString() : null);
        if (clearsGroupStates) {
            update.set(GroupMessageModel.COLUMN_GROUP_MESSAGE_STATES, mergedStates)
                .expect(GroupMessageModel.COLUMN_GROUP_MESSAGE_STATES, priorStates);
        }
        return update.build();
    }

    /**
     * F1Whisper (seventh fork review, F7-05): store a location message's body after a reverse-geocoding lookup has
     * filled in its street address.
     *
     * <p>The lookup finishes long after the message was composed, on a UI handler, holding whichever timeline instance
     * the adapter had. Full-row-saving that instance wrote back every lifecycle column it had captured, including a
     * countdown and a terminal state the send task had established in the meantime. The address lives in the serialised
     * body, so the body is both the assignment and the compare-and-set condition.</p>
     */
    @NonNull
    public static MessageRowUpdate locationAddress(@Nullable String newBody, @Nullable String priorBody) {
        return MessageRowUpdate.builder()
            .set(AbstractMessageModel.COLUMN_BODY, newBody)
            .expect(AbstractMessageModel.COLUMN_BODY, priorBody)
            .build();
    }

    /**
     * F1Whisper (seventh fork review, F7-02): mark a row deleted for everyone and empty it, as ONE statement.
     *
     * <p>This is the ownership claim of a delete-for-everyone, and it has to happen BEFORE the files, the edit history
     * and the reactions are removed. The old order removed the files first and only then saved the emptied row, so a
     * download running concurrently could write its media and record its completion into a row that still looked
     * current, in the window after the only file cleanup had already run. The message then ended up deleted with its
     * media orphaned on disk and, with "save to gallery" on, exported in the clear.</p>
     *
     * <p>The structural {@code deletedAtUtc IS NULL} predicate makes this idempotent in the way that matters: a second
     * delete-for-everyone for the same row loses, so exactly one caller ever owns the cleanup.</p>
     *
     * @param clearsGroupStates whether the per-member receipt map must be cleared too, which is the case for a group
     *                          message and meaningless for any other.
     */
    @NonNull
    public static MessageRowUpdate deletedForEveryone(@NonNull Date deletedAt, boolean clearsGroupStates) {
        final MessageRowUpdate.Builder update = MessageRowUpdate.builder()
            .set(AbstractMessageModel.COLUMN_DELETED_AT, deletedAt)
            .set(AbstractMessageModel.COLUMN_BODY, null)
            .set(AbstractMessageModel.COLUMN_CAPTION, null)
            .set(AbstractMessageModel.COLUMN_STATE, null);
        if (clearsGroupStates) {
            update.set(GroupMessageModel.COLUMN_GROUP_MESSAGE_STATES, null);
        }
        return update.build();
    }

    /**
     * Mark a row saved, for the two legacy image handlers that used to end with a full-row {@code update()} whose only
     * purpose was that flag.
     */
    @NonNull
    public static MessageRowUpdate saved() {
        return MessageRowUpdate.builder()
            .set(AbstractMessageModel.COLUMN_IS_SAVED, true)
            .build();
    }
}
