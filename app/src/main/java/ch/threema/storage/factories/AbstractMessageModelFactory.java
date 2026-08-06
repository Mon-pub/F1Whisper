package ch.threema.storage.factories;

import static ch.threema.storage.models.data.DisplayTag.DISPLAY_TAG_STARRED;

import android.content.ContentValues;
import android.database.sqlite.SQLiteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import net.zetetic.database.sqlcipher.SQLiteStatement;

import org.slf4j.Logger;

import java.util.Iterator;
import java.util.List;

import ch.threema.app.services.MessageService;
import ch.threema.app.utils.TestUtil;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityMode;
import ch.threema.storage.CursorHelper;
import ch.threema.storage.DatabaseProvider;
import ch.threema.storage.DatabaseUtil;
import ch.threema.storage.MessageRowUpdate;
import ch.threema.storage.PageCursor;
import ch.threema.storage.QueryBuilder;
import ch.threema.storage.TimelineKeyset;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.MessageState;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.data.DisplayTag;
import ch.threema.storage.models.data.MessageContentsType;
import ch.threema.storage.models.data.media.AudioDataModel;
import ch.threema.storage.models.data.media.FileDataModel;
import ch.threema.storage.models.data.media.VideoDataModel;

abstract class AbstractMessageModelFactory extends ModelFactory {
    private static final Logger logger = getThreemaLogger("AbstractMessageModelFactory");

    // F1Whisper (fork review H-06): the ordering tuple and its pagination boundary have ONE
    // canonical definition in TimelineKeyset; these aliases exist so the three message factories
    // keep their established references.
    static final String EFFECTIVE_SORT_KEY_EXPR = TimelineKeyset.EFFECTIVE_SORT_KEY_EXPR;
    static final String TIMELINE_ORDER_BY = TimelineKeyset.TIMELINE_ORDER_BY;

    /**
     * F1Whisper (device report 2026-08-06, U-01): a row deleted for everyone, in SQL. One definition, because the
     * write side and the read side have to mean the same thing by it.
     */
    static final String NOT_SOFT_DELETED = AbstractMessageModel.COLUMN_DELETED_AT + " IS NULL";

    /**
     * F1Whisper (eighth fork review, H8-01): the WHERE clause of every full-row save, so that a full-row save carries
     * the same structural predicate a column-scoped one has carried since F5-04.
     *
     * <p><b>The defect this closes.</b> A media message can be deleted for everyone WHILE its content blob is still
     * uploading - the UI offers it for a PENDING, UPLOADING or SENDING message, and an upload lasts seconds to minutes,
     * so this needs no race worth the name. The deletion emptied the row and marked it, and then the upload finished
     * and the send handoff put the blob id, the encryption key, the body and the caption back into that same row: the
     * only predicate a full-row save had was {@code id = ?}, and the tombstone still has that id. The chat went on
     * saying the message was deleted while the database held the credentials to fetch it again.</p>
     *
     * <p><b>Why here.</b> {@link ch.threema.storage.MessageRowUpdate} refuses a deleted row structurally, and its
     * javadoc gives the reason in one line: deletion always wins. Every lifecycle transition was moved onto it across
     * the fifth to seventh reviews - but the full-row save, the one writer that carries the user's CONTENT rather than
     * bookkeeping, was the writer left with the weaker predicate. It has it now, so the rule is one rule: nothing may
     * write a row that has been deleted for everyone, and the write itself is what decides, atomically.</p>
     *
     * <p>Callers already distinguish a refusal from a success and must not schedule, cache or publish on {@code false}
     * (F7-01); this only widens what a refusal means from "the row is gone" to "the row is gone or deleted".</p>
     */
    static final String CONTENT_ROW_WHERE =
        AbstractMessageModel.COLUMN_ID + "=? AND " + NOT_SOFT_DELETED;

    /**
     * F1Whisper (device report 2026-08-06, U-01): which rows are unread. Incoming, saved, not yet read, not a status
     * message - AND not deleted for everyone.
     *
     * <p><b>The defect this closes.</b> A message deleted for everyone could never be marked read, so it was unread
     * forever. {@link ch.threema.storage.MessageRowUpdate} refuses a deleted row structurally, and the first-read
     * transition is one of the writes it refuses, so {@code markAsRead} reloaded the row, decided, wrote, matched zero
     * rows, read that as another thread winning the race, re-read the same tombstone and lost again, three times, and
     * gave up. {@code isRead} stayed 0. This predicate then kept handing the same row back: the in-chat divider
     * anchored at it on every open, and the conversation's unread count came back to one after every refresh, however
     * much of the conversation the user read. Reported from a device, with the same message id failing on three
     * consecutive opens - a race that never varies is not a race.</p>
     *
     * <p><b>Why the read side rather than the write side.</b> Letting the read state reach a deleted row would need an
     * opt-out on the structural predicate, and refusing the first opt-out is what that predicate is for. It would also
     * repair nothing already on a device, whereas a query fixes every existing tombstone the moment it ships. And
     * {@code isRead = 0} on a tombstone is TRUE: the user never did read it, it was deleted first. So the column is
     * not wrong, the question was: "unread" means "still wants the user's attention", and a message whose content is
     * gone does not.</p>
     */
    static final String UNREAD_ROW_WHERE =
        AbstractMessageModel.COLUMN_OUTBOX + "=0"
            + " AND " + AbstractMessageModel.COLUMN_IS_SAVED + "=1"
            + " AND " + AbstractMessageModel.COLUMN_IS_READ + "=0"
            + " AND " + AbstractMessageModel.COLUMN_IS_STATUS_MESSAGE + "=0"
            + " AND " + NOT_SOFT_DELETED;

    AbstractMessageModelFactory(DatabaseProvider databaseProvider, String tableName) {
        super(databaseProvider, tableName);
    }

    void convert(final AbstractMessageModel messageModel, CursorHelper cursorFactory) {
        cursorFactory.current(new CursorHelper.Callback() {
            @Override
            public boolean next(CursorHelper cursorFactory) {
                ForwardSecurityMode forwardSecurityMode = ForwardSecurityMode.NONE;
                Integer fsmValue = cursorFactory.getInt(AbstractMessageModel.COLUMN_FORWARD_SECURITY_MODE);
                if (fsmValue != null) {
                    forwardSecurityMode = ForwardSecurityMode.getByValue(fsmValue);
                }

                messageModel.setId(cursorFactory.getInt(AbstractMessageModel.COLUMN_ID));
                messageModel.setUid(cursorFactory.getString(AbstractMessageModel.COLUMN_UID));
                messageModel.setApiMessageId(cursorFactory.getString(AbstractMessageModel.COLUMN_API_MESSAGE_ID));
                messageModel.setIdentity(cursorFactory.getString(AbstractMessageModel.COLUMN_IDENTITY));
                messageModel.setOutbox(cursorFactory.getBoolean(AbstractMessageModel.COLUMN_OUTBOX));
                messageModel.setCorrelationId(cursorFactory.getString(AbstractMessageModel.COLUMN_CORRELATION_ID));
                messageModel.setBody(cursorFactory.getString(AbstractMessageModel.COLUMN_BODY));
                messageModel.setRead(cursorFactory.getBoolean(AbstractMessageModel.COLUMN_IS_READ));
                messageModel.setSaved(cursorFactory.getBoolean(AbstractMessageModel.COLUMN_IS_SAVED));
                messageModel.setPostedAt(cursorFactory.getDate(AbstractMessageModel.COLUMN_POSTED_AT));
                messageModel.setCreatedAt(cursorFactory.getDate(AbstractMessageModel.COLUMN_CREATED_AT));
                messageModel.setModifiedAt(cursorFactory.getDate(AbstractMessageModel.COLUMN_MODIFIED_AT));
                messageModel.setStatusMessage(cursorFactory.getBoolean(AbstractMessageModel.COLUMN_IS_STATUS_MESSAGE));
                messageModel.setCaption(cursorFactory.getString(AbstractMessageModel.COLUMN_CAPTION));
                messageModel.setQuotedMessageId(cursorFactory.getString(AbstractMessageModel.COLUMN_QUOTED_MESSAGE_API_MESSAGE_ID));
                messageModel.setMessageContentsType(cursorFactory.getInt(AbstractMessageModel.COLUMN_MESSAGE_CONTENTS_TYPE));
                messageModel.setMessageFlags(cursorFactory.getInt(AbstractMessageModel.COLUMN_MESSAGE_FLAGS));
                messageModel.setDeliveredAt(cursorFactory.getDate(AbstractMessageModel.COLUMN_DELIVERED_AT));
                messageModel.setReadAt(cursorFactory.getDate(AbstractMessageModel.COLUMN_READ_AT));
                messageModel.setEditedAt(cursorFactory.getDate(AbstractMessageModel.COLUMN_EDITED_AT));
                messageModel.setDeletedAt(cursorFactory.getDate(AbstractMessageModel.COLUMN_DELETED_AT));
                // F1Whisper disappearing messages: raw epoch-millis timestamps + the frozen per-message timer.
                messageModel.setExpiresAt(cursorFactory.getLong(AbstractMessageModel.COLUMN_EXPIRES_AT));
                messageModel.setExpireStartedAt(cursorFactory.getLong(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT));
                Long disappearingTimerSeconds = cursorFactory.getLong(AbstractMessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS);
                messageModel.setDisappearingTimerSeconds(disappearingTimerSeconds != null ? disappearingTimerSeconds.intValue() : null);
                messageModel.setForwardSecurityMode(forwardSecurityMode);
                messageModel.setDisplayTags(cursorFactory.getInt(AbstractMessageModel.COLUMN_DISPLAY_TAGS));

                String stateString = cursorFactory.getString(AbstractMessageModel.COLUMN_STATE);
                if (!TestUtil.isEmptyOrNull(stateString)) {
                    try {
                        messageModel.setState(MessageState.valueOf(stateString));
                    } catch (IllegalArgumentException e) {
                        logger.error("Invalid message state {} - ignore", stateString, e);
                    }
                }

                int type = cursorFactory.getInt(AbstractMessageModel.COLUMN_TYPE);
                MessageType[] types = MessageType.values();
                if (type >= 0 && type < types.length) {
                    messageModel.setType(types[type]);
                }
                return false;
            }
        });
    }

    ContentValues buildContentValues(AbstractMessageModel messageModel) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(AbstractMessageModel.COLUMN_UID, messageModel.getUid());
        contentValues.put(AbstractMessageModel.COLUMN_API_MESSAGE_ID, messageModel.getApiMessageId());
        contentValues.put(AbstractMessageModel.COLUMN_IDENTITY, messageModel.getIdentity());
        contentValues.put(AbstractMessageModel.COLUMN_OUTBOX, messageModel.isOutbox());
        contentValues.put(AbstractMessageModel.COLUMN_TYPE, messageModel.getType() != null ? messageModel.getType().ordinal() : null);
        contentValues.put(AbstractMessageModel.COLUMN_CORRELATION_ID, messageModel.getCorrelationId());
        contentValues.put(AbstractMessageModel.COLUMN_BODY, messageModel.getBody());
        contentValues.put(AbstractMessageModel.COLUMN_IS_READ, messageModel.isRead());
        contentValues.put(AbstractMessageModel.COLUMN_IS_SAVED, messageModel.isSaved());
        contentValues.put(AbstractMessageModel.COLUMN_STATE, messageModel.getState() != null ? messageModel.getState().toString() : null);
        contentValues.put(AbstractMessageModel.COLUMN_POSTED_AT, DatabaseUtil.getDateTimeContentValue(messageModel.getPostedAt()));
        contentValues.put(AbstractMessageModel.COLUMN_CREATED_AT, DatabaseUtil.getDateTimeContentValue(messageModel.getCreatedAt()));
        // F1Whisper message-ordering fix: immutable per-row sort key (formula + rationale in
        // TimelineKeyset.effectiveSortDate — the shared definition keeps this write in lockstep
        // with the ORDER BY and the pagination boundary).
        contentValues.put(AbstractMessageModel.COLUMN_SORT_AT,
            DatabaseUtil.getDateTimeContentValue(TimelineKeyset.effectiveSortDate(messageModel)));
        contentValues.put(AbstractMessageModel.COLUMN_MODIFIED_AT, DatabaseUtil.getDateTimeContentValue(messageModel.getModifiedAt()));
        contentValues.put(AbstractMessageModel.COLUMN_IS_STATUS_MESSAGE, messageModel.isStatusMessage());
        contentValues.put(AbstractMessageModel.COLUMN_CAPTION, messageModel.getCaption());
        contentValues.put(AbstractMessageModel.COLUMN_QUOTED_MESSAGE_API_MESSAGE_ID, messageModel.getQuotedMessageId());
        contentValues.put(AbstractMessageModel.COLUMN_MESSAGE_CONTENTS_TYPE, messageModel.getMessageContentsType());
        contentValues.put(AbstractMessageModel.COLUMN_MESSAGE_FLAGS, messageModel.getMessageFlags());
        contentValues.put(AbstractMessageModel.COLUMN_DELIVERED_AT, DatabaseUtil.getDateTimeContentValue(messageModel.getDeliveredAt()));
        contentValues.put(AbstractMessageModel.COLUMN_READ_AT, DatabaseUtil.getDateTimeContentValue(messageModel.getReadAt()));
        contentValues.put(AbstractMessageModel.COLUMN_EDITED_AT, DatabaseUtil.getDateTimeContentValue(messageModel.getEditedAt()));
        contentValues.put(AbstractMessageModel.COLUMN_DELETED_AT, DatabaseUtil.getDateTimeContentValue(messageModel.getDeletedAt()));
        // F1Whisper disappearing messages: epoch-millis timestamps stored raw + the frozen per-message timer (null = off).
        contentValues.put(AbstractMessageModel.COLUMN_EXPIRES_AT, messageModel.getExpiresAt());
        contentValues.put(AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT, messageModel.getExpireStartedAt());
        contentValues.put(AbstractMessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS, messageModel.getDisappearingTimerSeconds());
        contentValues.put(AbstractMessageModel.COLUMN_FORWARD_SECURITY_MODE, messageModel.getForwardSecurityMode() != null ? messageModel.getForwardSecurityMode().getValue() : null);
        contentValues.put(AbstractMessageModel.COLUMN_DISPLAY_TAGS, messageModel.getDisplayTags());

        return contentValues;
    }

    void appendFilter(QueryBuilder queryBuilder, @Nullable MessageService.MessageFilter filter, List<String> placeholders) {
        if (filter != null) {
            if (!filter.withStatusMessages()) {
                queryBuilder.appendWhere(AbstractMessageModel.COLUMN_IS_STATUS_MESSAGE + "=0");
            }
            if (filter.onlyUnread()) {
                queryBuilder.appendWhere(AbstractMessageModel.COLUMN_OUTBOX + "=0");
                queryBuilder.appendWhere(AbstractMessageModel.COLUMN_IS_READ + "=0");
                queryBuilder.appendWhere(AbstractMessageModel.COLUMN_IS_STATUS_MESSAGE + "=0");
                // F1Whisper (device report 2026-08-06, U-01): and not deleted for everyone. A tombstone can never be
                // marked read, so without this it stays unread for the rest of the conversation's life. The saved flag
                // is appended separately below, which is why this cannot simply be UNREAD_ROW_WHERE.
                queryBuilder.appendWhere(NOT_SOFT_DELETED);
            }

            if (!filter.withUnsaved()) {
                queryBuilder.appendWhere(AbstractMessageModel.COLUMN_IS_SAVED + "=1");
            }

            if (filter.types() != null && filter.types().length > 0) {
                queryBuilder.appendWhere(AbstractMessageModel.COLUMN_TYPE + " IN (" + DatabaseUtil.makePlaceholders(filter.types().length) + ")");
                for (MessageType f : filter.types()) {
                    placeholders.add(String.valueOf(f.ordinal()));
                }
            }

            if (filter.contentTypes() != null && filter.contentTypes().length > 0) {
                queryBuilder.appendWhere(AbstractMessageModel.COLUMN_MESSAGE_CONTENTS_TYPE + " IN (" + DatabaseUtil.makePlaceholders(filter.contentTypes().length) + ")");
                for (@MessageContentsType int f : filter.contentTypes()) {
                    placeholders.add(String.valueOf(f));
                }
            }

            // F1Whisper (fork review H-06, follow-up P0-6, second follow-up S2-05): keyset
            // pagination over the SAME tuple the timeline ORDER BY uses — (effective sort key,
            // id) — with the boundary SQL drawn from the single definition in TimelineKeyset.
            // The cursor is snapshotted from the filter EXACTLY ONCE into this local: the filter
            // may be backed by a concurrently replaced volatile field, and repeated calls could
            // observe different cursors mid-query (the torn-tuple defect). Everything below
            // derives only from this snapshot.
            final PageCursor pageCursor = filter.getPageCursor();
            if (pageCursor != null) {
                queryBuilder.appendWhere(TimelineKeyset.boundaryWhereClause(pageCursor.getSortKey()));
                for (String arg : TimelineKeyset.boundaryArgs(pageCursor.getSortKey(), pageCursor.getId())) {
                    placeholders.add(arg);
                }
            } else if (filter.getPageReferenceId() != null && filter.getPageReferenceId() > 0) {
                // Legacy id-only callers (the webclient wire protocol — unreachable in the onprem
                // build, where the webclient is disabled) fall back to a one-time lookup.
                final int referenceId = filter.getPageReferenceId();
                final ResolvedPageReference resolved = resolvePageReference(referenceId);
                if (resolved != null) {
                    queryBuilder.appendWhere(TimelineKeyset.boundaryWhereClause(resolved.effectiveSortKey));
                    for (String arg : TimelineKeyset.boundaryArgs(resolved.effectiveSortKey, referenceId)) {
                        placeholders.add(arg);
                    }
                } else {
                    // Legacy id-only cursor whose reference row no longer exists: there is no way
                    // to place the boundary on the ordering tuple, and the old "id < ?" guess
                    // silently omitted late-arrival rows forever (the original H-06 hole). Return
                    // an EMPTY page instead — visible to the caller and self-healing on reload.
                    logger.warn("Page reference row {} no longer exists and no sort key was carried; returning an empty page", referenceId);
                    queryBuilder.appendWhere("0=1");
                }
            }

            if (filter.displayTags() != null && filter.displayTags().length > 0) {
                for (@DisplayTag int f : filter.displayTags()) {
                    queryBuilder.appendWhere("(" + AbstractMessageModel.COLUMN_DISPLAY_TAGS + " & " + f + ") > 0");
                }
            }
        }
    }

    <T> void postFilter(List<T> input, @Nullable MessageService.MessageFilter filter) {
        if (filter != null && filter.onlyDownloaded()) {
            Iterator<T> i = input.iterator();
            while (i.hasNext()) {
                AbstractMessageModel m = (AbstractMessageModel) i.next();
                boolean remove = false;
                if (m.getType() == MessageType.VIDEO) {
                    VideoDataModel d = m.getVideoData();
                    remove = (d == null || !d.isDownloaded());
                } else if (m.getType() == MessageType.VOICEMESSAGE) {
                    AudioDataModel d = m.getAudioData();
                    remove = (d == null || !d.isDownloaded());
                } else if (m.getType() == MessageType.FILE) {
                    FileDataModel d = m.getFileData();
                    remove = (d == null || !d.isDownloaded());
                }

                if (remove) {
                    i.remove();
                }
            }
        }
    }

    /**
     * F1Whisper (follow-up review P0-6): resolve a legacy id-only page reference to its ordering
     * tuple, DISTINGUISHING "row not found" (returns null — the caller must not fabricate a
     * boundary) from "row found with a NULL sort key" (returns a holder with a null key — the
     * cursor is inside the NULL tail and the tail branch of the boundary applies).
     */
    @Nullable
    private ResolvedPageReference resolvePageReference(int messageId) {
        try (android.database.Cursor cursor = getReadableDatabase().rawQuery(
            "SELECT " + EFFECTIVE_SORT_KEY_EXPR + " FROM " + this.getTableName()
                + " WHERE " + AbstractMessageModel.COLUMN_ID + "=?",
            new String[]{String.valueOf(messageId)})) {
            if (cursor.moveToFirst()) {
                return new ResolvedPageReference(cursor.isNull(0) ? null : cursor.getLong(0));
            }
        } catch (SQLiteException e) {
            logger.error("Failed to look up the page-reference sort key", e);
        }
        return null;
    }

    /** Holder for {@link #resolvePageReference}: existence is the null-ness of the HOLDER, not of the key. */
    private static final class ResolvedPageReference {
        @Nullable
        final Long effectiveSortKey;

        ResolvedPageReference(@Nullable Long effectiveSortKey) {
            this.effectiveSortKey = effectiveSortKey;
        }
    }

    String limitFilter(@Nullable MessageService.MessageFilter filter) {
        if (filter != null && filter.getPageSize() > 0) {
            return "" + filter.getPageSize();
        }
        return null;
    }

    /**
     * Mark file messages that have not been uploaded completely yet as failed so that users can
     * retry sending them. This affects file messages with state pending (upload did not start yet)
     * and uploading (upload may have been started already). File messages with state sending do not
     * need to be set to failed as they have been uploaded completely and a persistent task has been
     * scheduled. Therefore, these files will get sent as soon as there is a chat server connection.
     */
    public void markUnscheduledFileMessagesAsFailed() {
        ContentValues values = new ContentValues();
        values.put(AbstractMessageModel.COLUMN_STATE, MessageState.SENDFAILED.toString());

        try {
            int updated = getWritableDatabase().update(
                this.getTableName(),
                values,
                AbstractMessageModel.COLUMN_TYPE + " =?"
                    + " AND " + AbstractMessageModel.COLUMN_STATE + " IN (?, ?)"
                    + " AND " + AbstractMessageModel.COLUMN_OUTBOX + " = 1",
                new String[]{
                    String.valueOf(MessageType.FILE.ordinal()),
                    MessageState.PENDING.toString(),
                    MessageState.UPLOADING.toString(),
                }
            );

            if (updated > 0) {
                logger.info("{} messages in sending status were updated to sendfailed.", updated);
            }
        } catch (Exception e) {
            logger.error("Exception", e);
        }
    }

    /**
     * F1Whisper auto-resend: the states in which a FILE/media message's blob-upload phase has NOT
     * completed and therefore NO persistent send task has been scheduled yet - so the message is
     * genuinely unsent and safe to re-drive. Deliberately EXCLUDES {@link MessageState#SENDING}:
     * once a message is SENDING its persistent {@code OutgoingCspMessageTask} owns delivery (it
     * survives process death and auto-retries connectivity via the task queue), so auto-resending it
     * would double-send. Also EXCLUDES {@link MessageState#TRANSCODING}: a half-transcoded video has
     * no uploadable blob to resume (upstream's own {@code markUnscheduledFileMessagesAsFailed}
     * recovers only PENDING/UPLOADING for the same reason). Text/location/ballot never need this scan
     * - they go straight to the task queue at creation - which is why the candidate query is
     * additionally pinned to {@link MessageType#FILE}: FILE is the only type whose send has a
     * separate, process-death-fragile blob-upload phase driven by
     * {@link ch.threema.app.services.MessageSendingServiceExponentialBackOff}.
     */
    private static final String UNSENT_MEDIA_STATE_IN =
        AbstractMessageModel.COLUMN_STATE + " IN ("
            + "'" + MessageState.PENDING + "',"
            + "'" + MessageState.UPLOADING + "',"
            + "'" + MessageState.SENDFAILED + "'"
            + ")";

    /** Restrict auto-resend to FILE messages (the only type using the fragile blob-upload backoff). */
    private static final String AUTO_RESEND_TYPE_FILE =
        AbstractMessageModel.COLUMN_TYPE + " = " + MessageType.FILE.ordinal();

    /**
     * F1Whisper auto-resend: the SQL WHERE clause selecting outgoing FILE messages that are eligible
     * for silent auto-resend once connectivity returns. A candidate is:
     *
     *  - outgoing ({@code outbox = 1}), not a status message, not deleted;
     *  - a FILE message (see {@link #AUTO_RESEND_TYPE_FILE});
     *  - in an unsent blob-phase state (see {@link #UNSENT_MEDIA_STATE_IN}) - NEVER SENDING;
     *  - NOT marked terminal (the {@code DISPLAY_TAG_SEND_FAILED_TERMINAL} bit is clear), so
     *    FS / cancelled / MessageTooLong failures are excluded;
     *  - younger than the age cutoff (Signal-parity 24h lifespan), passed as a bind arg.
     *
     * The state list + type are literals (from trusted enums), the age cutoff is a bind arg.
     */
    private static final String AUTO_RESEND_WHERE =
        AbstractMessageModel.COLUMN_OUTBOX + " = 1"
            + " AND " + AbstractMessageModel.COLUMN_IS_STATUS_MESSAGE + " = 0"
            + " AND " + AbstractMessageModel.COLUMN_DELETED_AT + " IS NULL"
            + " AND " + AUTO_RESEND_TYPE_FILE
            + " AND (" + AbstractMessageModel.COLUMN_DISPLAY_TAGS + " & " + DisplayTag.DISPLAY_TAG_SEND_FAILED_TERMINAL + ") = 0"
            + " AND " + UNSENT_MEDIA_STATE_IN
            + " AND " + AbstractMessageModel.COLUMN_CREATED_AT + " > ?";

    /**
     * F1Whisper auto-resend: order candidates oldest-first by their immutable compose time so a
     * batch resend preserves the original send order.
     */
    private static final String AUTO_RESEND_ORDER_BY =
        AbstractMessageModel.COLUMN_CREATED_AT + " ASC, " + AbstractMessageModel.COLUMN_ID + " ASC";

    /**
     * F1Whisper auto-resend: run the auto-resend candidate query for this factory's table and
     * return the raw cursor (oldest first). Callers wrap it with their typed {@code convertList}.
     *
     * @param minCreatedAtMillis exclusive lower bound on {@code createdAtUtc} (epoch millis); rows
     *                           older than this are excluded (the 24h lifespan cutoff).
     */
    @WorkerThread
    protected android.database.Cursor queryAutoResendCandidates(long minCreatedAtMillis) {
        return getReadableDatabase().query(
            this.getTableName(),
            null,
            AUTO_RESEND_WHERE,
            new String[]{String.valueOf(minCreatedAtMillis)},
            null,
            null,
            AUTO_RESEND_ORDER_BY
        );
    }

    /**
     * F1Whisper auto-resend: the WHERE clause selecting outgoing FILE messages that were
     * auto-eligible but have now exhausted the 24h auto-resend window WITHOUT ever succeeding - i.e.
     * still in an unsent blob-phase state, not terminal, and OLDER than the age cutoff. These are
     * handed to the nag path so the user is finally told once (Signal/Telegram give up silently
     * after the window; we surface it). Mirrors {@link #AUTO_RESEND_WHERE} but flips the age
     * comparison.
     */
    private static final String AGED_OUT_UNSENT_WHERE =
        AbstractMessageModel.COLUMN_OUTBOX + " = 1"
            + " AND " + AbstractMessageModel.COLUMN_IS_STATUS_MESSAGE + " = 0"
            + " AND " + AbstractMessageModel.COLUMN_DELETED_AT + " IS NULL"
            + " AND " + AUTO_RESEND_TYPE_FILE
            + " AND (" + AbstractMessageModel.COLUMN_DISPLAY_TAGS + " & " + DisplayTag.DISPLAY_TAG_SEND_FAILED_TERMINAL + ") = 0"
            + " AND " + UNSENT_MEDIA_STATE_IN
            + " AND " + AbstractMessageModel.COLUMN_CREATED_AT + " <= ?";

    /**
     * F1Whisper auto-resend: query the aged-out unsent messages for this table (see
     * {@link #AGED_OUT_UNSENT_WHERE}). Callers wrap it with their typed {@code convertList}.
     *
     * @param maxCreatedAtMillis inclusive upper bound on {@code createdAtUtc}; rows at or before
     *                           this are past the 24h window.
     */
    @WorkerThread
    protected android.database.Cursor queryAgedOutUnsentMessages(long maxCreatedAtMillis) {
        return getReadableDatabase().query(
            this.getTableName(),
            null,
            AGED_OUT_UNSENT_WHERE,
            new String[]{String.valueOf(maxCreatedAtMillis)},
            null,
            null,
            AUTO_RESEND_ORDER_BY
        );
    }

    @WorkerThread
    public int unstarAllMessages() {
        String query =
            "UPDATE " + this.getTableName() +
                " SET " + AbstractMessageModel.COLUMN_DISPLAY_TAGS +
                " = (" + AbstractMessageModel.COLUMN_DISPLAY_TAGS +
                " & ~" + DISPLAY_TAG_STARRED +
                ") WHERE (" + AbstractMessageModel.COLUMN_DISPLAY_TAGS + " & " + DISPLAY_TAG_STARRED + ") > 0";

        SQLiteStatement statement = getWritableDatabase().compileStatement(query);
        return statement.executeUpdateDelete();
    }

    /**
     * F1Whisper (fourth fork review, F4-06): the UPDATE the expiry repair pass runs, for {@code tableName}.
     *
     * <p>Package-visible and built here rather than inline so the executable regression test drives the EXACT statement
     * that ships, against a real database, instead of a copy of it that could drift.
     *
     * <p>Two properties matter and both are in this string. It touches ONLY the two expiry columns, so it cannot carry a
     * stale body, state or deletion timestamp back onto the row; and its WHERE clause is a compare-and-set that re-checks,
     * at write time, everything the candidate query checked at read time:
     *
     * <ul>
     *     <li>{@code id = ?} - the row still exists. A hard-deleted row matches nothing, so the repair cannot recreate it.
     *     <li>{@code deletedAtUtc IS NULL} - it has not been deleted for everyone since the read.
     *     <li>{@code disappearingTimerSeconds > 0} - it is still a timed message.
     *     <li>the same unreachable-countdown shape the candidate query selected - so a row that has meanwhile been given a
     *         proper countdown (a concurrent mark-as-read) is left exactly as it is.
     * </ul>
     *
     * <p>Bind order: expireStartedAt, expiresAt, id.
     */
    static String repairExpirySql(String tableName) {
        return "UPDATE `" + tableName + "`"
            + " SET `" + AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT + "` = ?,"
            + " `" + AbstractMessageModel.COLUMN_EXPIRES_AT + "` = ?"
            + " WHERE `" + AbstractMessageModel.COLUMN_ID + "` = ?"
            + " AND `" + AbstractMessageModel.COLUMN_DELETED_AT + "` IS NULL"
            + " AND `" + AbstractMessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS + "` > 0"
            + " AND ("
            + "(`" + AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT + "` IS NOT NULL"
            + " AND `" + AbstractMessageModel.COLUMN_EXPIRES_AT + "` IS NULL)"
            + " OR (`" + AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT + "` IS NULL"
            + " AND `" + AbstractMessageModel.COLUMN_IS_READ + "` = 1"
            + " AND `" + AbstractMessageModel.COLUMN_OUTBOX + "` = 0)"
            + ")";
    }

    /**
     * F1Whisper (fourth fork review, F4-06): stamp a repaired countdown onto one row, and nothing else.
     *
     * <p>The defect this replaces: the repair pass read whole detached models, changed two timer fields and handed the
     * result to {@code MessageService#save}, which is a full-row upsert. Between the read and that write the message could
     * be hard-deleted, in which case the upsert INSERTED the stale snapshot back as a new row, or deleted for everyone, in
     * which case the full-row write restored the old body and nulled the deletion timestamp. A maintenance pass that only
     * computes deadlines was able to recreate content the user had deleted.
     *
     * @return {@code true} if the row was repaired, {@code false} if it had gone or was no longer repairable. Never
     * inserts, whatever the answer.
     */
    @WorkerThread
    public boolean repairExpiry(int messageId, @Nullable Long expireStartedAt, @Nullable Long expiresAt) {
        SQLiteStatement statement = getWritableDatabase().compileStatement(repairExpirySql(this.getTableName()));
        try {
            bindNullableLong(statement, 1, expireStartedAt);
            bindNullableLong(statement, 2, expiresAt);
            statement.bindLong(3, messageId);
            return statement.executeUpdateDelete() > 0;
        } finally {
            statement.close();
        }
    }

    private static void bindNullableLong(SQLiteStatement statement, int index, @Nullable Long value) {
        if (value == null) {
            statement.bindNull(index);
        } else {
            statement.bindLong(index, value);
        }
    }

    /**
     * F1Whisper (fifth fork review, F5-04 / F5-06): run one conditional, column-scoped, non-inserting write against the row
     * with {@code messageId}. See {@link MessageRowUpdate} for why every volatile-content transition now goes through this
     * rather than through a full-row save.
     *
     * @return {@code true} if the row was updated; {@code false} if it had gone, had been deleted for everyone, or no
     * longer matched the caller's expected current values. Never inserts, whatever the answer.
     */
    @WorkerThread
    public boolean applyRowUpdate(int messageId, @NonNull MessageRowUpdate update) {
        SQLiteStatement statement = getWritableDatabase().compileStatement(update.toSql(this.getTableName()));
        try {
            bindAll(statement, update.bindArgs(messageId));
            return statement.executeUpdateDelete() > 0;
        } finally {
            statement.close();
        }
    }

    /**
     * F1Whisper (fifth fork review, F5-04): the SQL that CLAIMS an expired row by deleting it, for {@code tableName}.
     *
     * <p>Package-visible and built here so the executable test drives the exact statement that ships.</p>
     *
     * <p>The defect it removes: the three expiry paths (lazy enforcement, the alarm, the startup sweep) each selected a due
     * row, then deleted its files, its ballot aggregate and the row itself from that DETACHED snapshot. Between the
     * selection and the side effects the message could have had its timer turned off by a duplicate advertising an explicit
     * OFF, or had its deadline moved by a freeze re-deriving it from a corrected timer. The snapshot was still due, so the
     * content was destroyed anyway - and a deadline that had merely been repaired earlier was treated as authorisation for
     * an unconditional delete much later.</p>
     *
     * <p>Winning this DELETE is what makes a caller the owner: the row is gone, so no concurrent enforcement can claim it
     * too, and only the winner performs the file, cache and ballot side effects. Losing it means the row is no longer the
     * one that was due, and nothing is destroyed.</p>
     *
     * <p>Bind order: id, expireStartedAt, expiresAt, now.</p>
     */
    static String deleteIfStillDueSql(String tableName) {
        return "DELETE FROM `" + tableName + "`"
            + " WHERE `" + AbstractMessageModel.COLUMN_ID + "` = ?"
            + " AND `" + AbstractMessageModel.COLUMN_DELETED_AT + "` IS NULL"
            + " AND `" + AbstractMessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS + "` > 0"
            + " AND `" + AbstractMessageModel.COLUMN_EXPIRE_STARTED_AT + "` = ?"
            + " AND `" + AbstractMessageModel.COLUMN_EXPIRES_AT + "` = ?"
            + " AND `" + AbstractMessageModel.COLUMN_EXPIRES_AT + "` <= ?";
    }

    /**
     * F1Whisper (fifth fork review, F5-04): claim an expired row by deleting it, if it is STILL the row that was due.
     *
     * @param expireStartedAt the countdown start the caller decided from; a row whose start has moved is not the same row.
     * @param expiresAt       the deadline the caller decided from.
     * @param nowMillis       the instant the caller is enforcing at.
     * @return {@code true} if this caller now owns the removal of everything that row governed.
     * @see #deleteIfStillDueSql(String)
     */
    @WorkerThread
    public boolean deleteIfStillDue(int messageId, @Nullable Long expireStartedAt, @Nullable Long expiresAt, long nowMillis) {
        if (expireStartedAt == null || expiresAt == null) {
            // A row with no start or no deadline is not due by definition, and binding NULL into an `=` comparison would
            // silently match nothing anyway. Answer the question directly instead of asking the database a broken one.
            return false;
        }
        SQLiteStatement statement = getWritableDatabase().compileStatement(deleteIfStillDueSql(this.getTableName()));
        try {
            statement.bindLong(1, messageId);
            statement.bindLong(2, expireStartedAt);
            statement.bindLong(3, expiresAt);
            statement.bindLong(4, nowMillis);
            return statement.executeUpdateDelete() > 0;
        } finally {
            statement.close();
        }
    }

    /**
     * F1Whisper (sixth fork review, F6-01): whether a {@code createOrUpdate} that found no row must REFUSE to insert one.
     *
     * <p>The upsert decides "no row with this id, therefore create". For a model whose id was never assigned that is the
     * ordinary insert. For a model that HAS an id, it means the row was there when the caller loaded it and has gone
     * since - hard-deleted by the user, or claimed by the disappearing-message expiry - and inserting is the content
     * resurrection the whole fifth review was about, reached from any full-row writer that happens to be paused at the
     * wrong moment. It is not even a faithful resurrection: the insert allocates a NEW id, so the row that comes back is
     * not the row the caller had, and nothing can be relying on it.</p>
     *
     * <p>This is the backstop under the call-site work. Every lifecycle transition now writes conditionally, but any
     * full-row save added later would reopen the hole; here it cannot.</p>
     *
     * <p><b>F1Whisper (seventh fork review, F7-01).</b> The guard used to sit after a SELECT that asked whether the row
     * existed, and the row could disappear between that answer and the write. A positive id is now written by an UPDATE
     * ONLY - the existence decision IS the write, and its affected-row count is the answer - so there is no window at
     * all, and {@code create} is unreachable for a model that carries an id. This method survives as the place that
     * says why, and as the log line a caller needs when its save is refused: the answer is no longer approximate, so
     * every caller must treat {@code false} as "the row is gone", never as "saved".</p>
     *
     * <p><b>F1Whisper (eighth fork review, H8-01).</b> A full-row save now also reports {@code false} for a row that is
     * still there but has been deleted for everyone. See {@link #CONTENT_ROW_WHERE}.</p>
     */
    static boolean refusesReinsertion(int messageId) {
        if (messageId > 0) {
            logger.warn("Refusing to write message row {}: it existed when it was loaded and has since been deleted",
                messageId);
            return true;
        }
        return false;
    }

    private static void bindAll(SQLiteStatement statement, Object[] args) {
        for (int i = 0; i < args.length; i++) {
            final int index = i + 1;
            final Object value = args[i];
            if (value == null) {
                statement.bindNull(index);
            } else if (value instanceof Long) {
                statement.bindLong(index, (Long) value);
            } else if (value instanceof Double || value instanceof Float) {
                statement.bindDouble(index, ((Number) value).doubleValue());
            } else if (value instanceof byte[]) {
                statement.bindBlob(index, (byte[]) value);
            } else {
                statement.bindString(index, value.toString());
            }
        }
    }

    @WorkerThread
    public long countStarredMessages() throws SQLiteException {
        return DatabaseUtil.count(getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM " + this.getTableName()
                + " WHERE (" + AbstractMessageModel.COLUMN_DISPLAY_TAGS + " & " + DISPLAY_TAG_STARRED + ") > 0",
            null
        ));
    }
}
