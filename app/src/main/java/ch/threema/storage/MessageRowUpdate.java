package ch.threema.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.threema.storage.models.AbstractMessageModel;

/**
 * F1Whisper (fifth fork review, F5-04 / F5-06): a conditional, column-scoped, NON-INSERTING write against one message row.
 *
 * <p><b>What it exists to stop.</b> Every volatile-content transition in this app - freezing the sender's disappearing
 * policy, starting a countdown at first read, claiming or burning a listen-once voice message, recording that a media
 * download finished, moving an outgoing message to its terminal state - used to be expressed as "mutate the model I am
 * holding, then call {@code MessageService#save}". That save builds the WHOLE row from the detached instance and routes it
 * through {@code createOrUpdate}, which INSERTS when the original id no longer exists. So each of those transitions could,
 * if the row was hard-deleted or deleted-for-everyone while it was deciding, resurrect the message with its old body, or
 * quietly overwrite a newer deletion, a newer read state or a newer countdown that it never looked at. F4-06 built one
 * bespoke statement for one of those paths; this is the general primitive the rest of them needed.</p>
 *
 * <p><b>Two predicates are structural</b>, not caller-supplied, because every caller needs both and forgetting either is
 * exactly the defect:</p>
 *
 * <ul>
 *     <li>{@code id = ?} - the write is an UPDATE against an existing row and matches nothing if the row has gone. It
 *     therefore cannot insert, whatever the caller does.</li>
 *     <li>{@code deletedAtUtc IS NULL} - a message deleted for everyone is out of bounds for lifecycle bookkeeping.
 *     Deletion always wins.</li>
 * </ul>
 *
 * <p><b>Expected-current-value conditions</b> supply the rest: compare-and-set for fields whose new value was DECIDED from
 * a value read a moment earlier. The canonical case is {@code body}, which carries the serialised file-data metadata for
 * listen-once and download state - two callers deciding from two different reads of it would otherwise silently discard
 * one another's flags. A caller reloads, decides, writes, and on a false answer reloads and decides again.</p>
 *
 * <p>Immutable and Android-free, so the exact SQL and bind order that ships is executable in a JVM test.</p>
 */
public final class MessageRowUpdate {

    /** Assignments, in insertion order. A {@code null} value writes SQL NULL. */
    @NonNull
    private final Map<String, Object> assignments;

    /** Expected current values, in insertion order. A {@code null} value means {@code IS NULL}. */
    @NonNull
    private final Map<String, Object> conditions;

    private MessageRowUpdate(@NonNull Map<String, Object> assignments, @NonNull Map<String, Object> conditions) {
        this.assignments = Collections.unmodifiableMap(assignments);
        this.conditions = Collections.unmodifiableMap(conditions);
    }

    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /** Whether this update would write anything at all. An empty update is a caller bug, not a no-op to be executed. */
    public boolean isEmpty() {
        return assignments.isEmpty();
    }

    @NonNull
    public Map<String, Object> getAssignments() {
        return assignments;
    }

    @NonNull
    public Map<String, Object> getConditions() {
        return conditions;
    }

    /**
     * The statement for {@code tableName}. Bind order is {@link #bindArgs(int)}: every assignment value, then the row id,
     * then every non-null condition value.
     */
    @NonNull
    public String toSql(@NonNull String tableName) {
        if (assignments.isEmpty()) {
            throw new IllegalStateException("A row update must assign at least one column");
        }
        final StringBuilder sql = new StringBuilder("UPDATE `").append(tableName).append("` SET ");
        boolean first = true;
        for (String column : assignments.keySet()) {
            if (!first) {
                sql.append(", ");
            }
            first = false;
            sql.append('`').append(column).append("` = ?");
        }
        sql.append(" WHERE `").append(AbstractMessageModel.COLUMN_ID).append("` = ?");
        // Structural, and deliberately not overridable: a lifecycle write may never contradict a delete-for-everyone.
        sql.append(" AND `").append(AbstractMessageModel.COLUMN_DELETED_AT).append("` IS NULL");
        for (Map.Entry<String, Object> condition : conditions.entrySet()) {
            sql.append(" AND `").append(condition.getKey()).append('`');
            if (condition.getValue() == null) {
                sql.append(" IS NULL");
            } else {
                sql.append(" = ?");
            }
        }
        return sql.toString();
    }

    /** The bind values for {@link #toSql(String)}, in order. */
    @NonNull
    public Object[] bindArgs(int messageId) {
        final List<Object> args = new ArrayList<>(assignments.size() + conditions.size() + 1);
        args.addAll(assignments.values());
        args.add((long) messageId);
        for (Object expected : conditions.values()) {
            if (expected != null) {
                args.add(expected);
            }
        }
        return args.toArray();
    }

    public static final class Builder {
        private final Map<String, Object> assignments = new LinkedHashMap<>();
        private final Map<String, Object> conditions = new LinkedHashMap<>();

        private Builder() {
        }

        /** Write {@code value} (which may be {@code null}) to {@code column}. */
        @NonNull
        public Builder set(@NonNull String column, @Nullable Object value) {
            assignments.put(column, normalise(value));
            return this;
        }

        /**
         * Require {@code column} to still hold {@code expected} at write time. Pass {@code null} to require SQL NULL.
         *
         * <p>This is the compare-and-set half. Use it for every column whose NEW value was derived from the value this
         * condition names, so a decision made against a stale read is refused rather than applied.</p>
         */
        @NonNull
        public Builder expect(@NonNull String column, @Nullable Object expected) {
            conditions.put(column, normalise(expected));
            return this;
        }

        @NonNull
        public MessageRowUpdate build() {
            return new MessageRowUpdate(new LinkedHashMap<>(assignments), new LinkedHashMap<>(conditions));
        }

        /**
         * Reduce the values callers naturally hold to what SQLite binds. Booleans become 1/0 (the encoding
         * {@code ContentValues} already uses for these columns), integers widen to long so a bind never depends on which
         * numeric box a caller happened to produce, and dates become epoch millis exactly as
         * {@code DatabaseUtil.getDateTimeContentValue} stores them.
         *
         * <p>Enums are deliberately NOT converted. {@code state} is stored as a string and {@code forwardSecurityMode} as
         * an int, so there is no single right answer and guessing one would put a wrong value in a column that no test
         * reads back through the same path. Callers pass the stored representation.</p>
         */
        @Nullable
        private static Object normalise(@Nullable Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Boolean) {
                return ((Boolean) value) ? 1L : 0L;
            }
            if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
                return ((Number) value).longValue();
            }
            if (value instanceof java.util.Date) {
                return ((java.util.Date) value).getTime();
            }
            if (value instanceof Enum) {
                throw new IllegalArgumentException(
                    "Pass the stored representation of " + value.getClass().getSimpleName() + ", not the enum constant"
                );
            }
            return value;
        }
    }
}
