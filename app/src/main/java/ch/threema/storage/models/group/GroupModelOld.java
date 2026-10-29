package ch.threema.storage.models.group;

import static ch.threema.common.StringExtensionsKt.truncateUTF8String;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import java.time.Instant;
import java.util.Objects;

import ch.threema.data.datatypes.ConversationVisibility;
import ch.threema.data.datatypes.IdColor;
import ch.threema.data.datatypes.GroupNotificationTriggerPolicyOverride;
import ch.threema.data.datatypes.GroupIdentity;
import ch.threema.domain.models.GroupId;
import ch.threema.domain.models.UserState;
import ch.threema.storage.models.ReceiverModel;

@Deprecated
public class GroupModelOld implements ReceiverModel {

    public static final int GROUP_NAME_MAX_LENGTH_BYTES = 256;

    public static final String TABLE = "m_group";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_API_GROUP_ID = "apiGroupId";
    public static final String COLUMN_NAME = "name";
    public static final String COLUMN_CREATOR_IDENTITY = "creatorIdentity";
    public static final String COLUMN_CREATED_AT = "createdAt";
    public static final String COLUMN_SYNCHRONIZED_AT = "synchronizedAt";
    public static final String COLUMN_LAST_UPDATE = "lastUpdateAt"; /* date when the conversation was last updated */
    public static final String COLUMN_CONVERSATION_VISIBILITY = "conversationVisibility";
    public static final String COLUMN_GROUP_DESC = "groupDesc";
    public static final String COLUMN_GROUP_DESC_CHANGED_TIMESTAMP = "changedGroupDescTimestamp";
    public static final String COLUMN_COLOR_INDEX = "colorIndex";
    public static final String COLUMN_USER_STATE = "userState";
    public static final String COLUMN_NOTIFICATION_TRIGGER_POLICY_OVERRIDE_POLICY = "notificationTriggerPolicyOverridePolicy";
    public static final String COLUMN_NOTIFICATION_TRIGGER_POLICY_OVERRIDE_EXPIRES_AT = "notificationTriggerPolicyOverrideExpiresAt";
    public static final String COLUMN_DISAPPEARING_MESSAGES_TIMER_SECONDS = "disappearingMessagesTimerSeconds"; /* F1Whisper: my own per-conversation disappearing-messages timer in seconds; null/0 = off */
    public static final String COLUMN_PEER_DISAPPEARING_TIMER_SECONDS = "peerDisappearingTimerSeconds"; /* F1Whisper: LEGACY, DEAD. Was the per-direction inbound timer; the shared-field LWW model made it redundant and no code reads it beyond the row round-trip. Kept only so the schema needs no migration. */

    private String groupDesc;
    private Instant changedGroupDescTimestamp;

    private int id;
    private GroupId apiGroupId;
    private String name;
    private String creatorIdentity;
    private Instant createdAt;
    private Instant synchronizedAt;
    @Nullable
    private Instant lastUpdate;
    @NonNull
    private ConversationVisibility conversationVisibility = ConversationVisibility.NORMAL;
    @NonNull
    private IdColor idColor = IdColor.invalid();
    @Nullable
    private UserState userState;
    @Nullable
    private GroupNotificationTriggerPolicyOverride notificationTriggerPolicyOverride;
    // F1Whisper: my own per-conversation disappearing-messages timer (seconds); null/0 = off.
    @Nullable
    private Integer disappearingMessagesTimerSeconds;
    // F1Whisper: legacy per-direction peer timer (seconds); no longer read, see COLUMN_PEER_DISAPPEARING_TIMER_SECONDS.
    @Nullable
    private Integer peerDisappearingTimerSeconds;

    @Nullable
    public String getName() {
        return this.name;
    }

    public GroupModelOld setName(@Nullable String name) {
        this.name = name != null
            ? truncateUTF8String(name, GROUP_NAME_MAX_LENGTH_BYTES)
            : null;
        return this;
    }

    public int getId() {
        return this.id;
    }

    public GroupModelOld setId(int id) {
        this.id = id;
        return this;
    }

    public GroupModelOld setApiGroupId(GroupId apiGroupId) {
        this.apiGroupId = apiGroupId;
        return this;
    }

    public @NonNull GroupId getApiGroupId() {
        return this.apiGroupId;
    }

    public String getCreatorIdentity() {
        return this.creatorIdentity;
    }

    public GroupModelOld setCreatorIdentity(String creatorIdentity) {
        this.creatorIdentity = creatorIdentity;
        return this;
    }

    public Instant getCreatedAt() {
        return this.createdAt;
    }

    public GroupModelOld setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    @Override
    public GroupModelOld setLastUpdate(@Nullable Instant lastUpdate) {
        this.lastUpdate = lastUpdate;
        return this;
    }

    @Override
    public @Nullable Instant getLastUpdate() {
        // Note: Never return null for groups, they should always be visible
        return this.lastUpdate == null ? Instant.EPOCH : this.lastUpdate;
    }

    public Instant getSynchronizedAt() {
        return this.synchronizedAt;
    }

    public GroupModelOld setSynchronizedAt(Instant synchronizedAt) {
        this.synchronizedAt = synchronizedAt;
        return this;
    }

    @Override
    public boolean isArchived() {
        return conversationVisibility == ConversationVisibility.ARCHIVED;
    }

    @NonNull
    public ConversationVisibility getConversationVisibility() {
        return conversationVisibility;
    }

    public GroupModelOld setConversationVisibility(@NonNull ConversationVisibility conversationVisibility) {
        this.conversationVisibility = conversationVisibility;
        return this;
    }

    @Override
    public boolean isHidden() {
        // Groups can't currently be hidden from the conversation list
        return false;
    }

    public IdColor getIdColor() {
        if (!idColor.isValid()) {
            idColor = IdColor.ofGroup(getGroupIdentity());
        }
        return idColor;
    }

    public GroupModelOld setColorIndex(int colorIndex) {
        this.idColor = new IdColor(colorIndex);
        return this;
    }

    public GroupModelOld setGroupDesc(String description) {
        groupDesc = description;
        return this;
    }

    public GroupModelOld setGroupDescTimestamp(Instant groupDescDate) {
        changedGroupDescTimestamp = groupDescDate;
        return this;
    }

    public String getGroupDesc() {
        return this.groupDesc;
    }

    public Instant getGroupDescTimestamp() {
        return this.changedGroupDescTimestamp;
    }

    @NonNull
    public GroupModelOld setUserState(@Nullable UserState userState) {
        this.userState = userState;
        return this;
    }

    @Nullable
    public UserState getUserState() {
        return userState;
    }

    @NonNull
    public GroupModelOld setNotificationTriggerPolicyOverride(
        @Nullable final GroupNotificationTriggerPolicyOverride notificationTriggerPolicyOverride
    ) {
        this.notificationTriggerPolicyOverride = notificationTriggerPolicyOverride;
        return this;
    }

    @Nullable
    public GroupNotificationTriggerPolicyOverride getNotificationTriggerPolicyOverride() {
        return notificationTriggerPolicyOverride;
    }

    /**
     * F1Whisper: the per-conversation default disappearing-messages timer in seconds.
     * {@code null} or {@code 0} means disappearing messages are off for this group.
     */
    @Nullable
    public Integer getDisappearingMessagesTimerSeconds() {
        return disappearingMessagesTimerSeconds;
    }

    @NonNull
    public GroupModelOld setDisappearingMessagesTimerSeconds(@Nullable Integer disappearingMessagesTimerSeconds) {
        this.disappearingMessagesTimerSeconds = disappearingMessagesTimerSeconds;
        return this;
    }

    /**
     * F1Whisper: legacy per-direction peer timer. Groups use {@link #getDisappearingMessagesTimerSeconds()} for both
     * directions; this column is still persisted (no migration) but no code reads it beyond the row round-trip.
     */
    @Nullable
    public Integer getPeerDisappearingTimerSeconds() {
        return peerDisappearingTimerSeconds;
    }

    @NonNull
    public GroupModelOld setPeerDisappearingTimerSeconds(@Nullable Integer peerDisappearingTimerSeconds) {
        this.peerDisappearingTimerSeconds = peerDisappearingTimerSeconds;
        return this;
    }

    public GroupIdentity getGroupIdentity() {
        return new GroupIdentity(
            creatorIdentity,
            apiGroupId.toLong()
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GroupModelOld)) return false;
        GroupModelOld that = (GroupModelOld) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
