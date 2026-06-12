package ch.threema.storage.models;

/**
 * A locally stored, not-yet-sent message that is scheduled to be sent at a future point in time.
 *
 * <p>This is a purely local, pre-send concept: the message does not exist as an
 * {@link AbstractMessageModel} until the scheduled time is reached and it is actually sent via the
 * {@code MessageService}. The receiver is identified by {@code receiverType} +
 * {@code receiverKey}, matching the keys consumed by
 * {@code ch.threema.app.utils.IntentDataUtil#getMessageReceiverFromExtras}.
 */
public class ScheduledMessageModel {
    public static final String TABLE = "scheduled_messages";
    public static final String COLUMN_ID = "id";
    /**
     * One of {@code MessageReceiver.Type_CONTACT}, {@code Type_GROUP},
     * {@code Type_DISTRIBUTION_LIST}.
     */
    public static final String COLUMN_RECEIVER_TYPE = "receiverType";
    /**
     * For a contact: the identity string. For a group: the group's database id as string. For a
     * distribution list: the distribution list id as string.
     */
    public static final String COLUMN_RECEIVER_KEY = "receiverKey";
    /**
     * The final plaintext to send (already quote-encoded if a quote was active).
     */
    public static final String COLUMN_BODY = "body";
    /**
     * Epoch milliseconds (wall clock) at which the message should be sent.
     */
    public static final String COLUMN_SCHEDULED_AT = "scheduledAt";
    public static final String COLUMN_CREATED_AT = "createdAt";

    private int id;
    private int receiverType;
    private String receiverKey;
    private String body;
    private long scheduledAt;
    private long createdAt;

    public ScheduledMessageModel() {
    }

    public int getId() {
        return this.id;
    }

    public ScheduledMessageModel setId(int id) {
        this.id = id;
        return this;
    }

    public int getReceiverType() {
        return this.receiverType;
    }

    public ScheduledMessageModel setReceiverType(int receiverType) {
        this.receiverType = receiverType;
        return this;
    }

    public String getReceiverKey() {
        return this.receiverKey;
    }

    public ScheduledMessageModel setReceiverKey(String receiverKey) {
        this.receiverKey = receiverKey;
        return this;
    }

    public String getBody() {
        return this.body;
    }

    public ScheduledMessageModel setBody(String body) {
        this.body = body;
        return this;
    }

    public long getScheduledAt() {
        return this.scheduledAt;
    }

    public ScheduledMessageModel setScheduledAt(long scheduledAt) {
        this.scheduledAt = scheduledAt;
        return this;
    }

    public long getCreatedAt() {
        return this.createdAt;
    }

    public ScheduledMessageModel setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
        return this;
    }
}
