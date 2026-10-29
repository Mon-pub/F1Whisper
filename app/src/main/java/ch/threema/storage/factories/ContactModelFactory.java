package ch.threema.storage.factories;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;

import org.slf4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.app.BuildConfig;
import ch.threema.data.IdentityProvider;
import ch.threema.base.crypto.NaCl;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import static ch.threema.common.JavaCompat.isNullOrEmpty;
import static ch.threema.storage.DatabaseExtensionsKt.exists;
import static ch.threema.storage.DatabaseExtensionsKt.existsByBlob;

import ch.threema.data.datatypes.AvailabilityStatus;
import ch.threema.data.datatypes.ContactNotificationTriggerPolicyOverride;
import ch.threema.data.datatypes.ContactNotificationTriggerPolicyOverridePolicy;
import ch.threema.data.datatypes.ConversationVisibility;
import ch.threema.data.datatypes.PredefinedContact;
import ch.threema.domain.models.AcquaintanceLevel;
import ch.threema.domain.models.IdentityState;
import ch.threema.domain.models.IdentityType;
import ch.threema.domain.models.VerificationLevel;
import ch.threema.domain.protocol.csp.ProtocolDefines;
import ch.threema.storage.CursorHelper;
import ch.threema.storage.DatabaseCreationProvider;
import ch.threema.storage.DatabaseProvider;
import ch.threema.storage.DatabaseUtil;
import ch.threema.storage.DbAvailabilityStatus;
import ch.threema.storage.QueryBuilder;
import ch.threema.storage.models.ContactModel;

public class ContactModelFactory extends ModelFactory {

    private static final Logger logger = getThreemaLogger("ContactModelFactory");

    public static final String COLUMN_CONTACTS_IDENTITY = ContactModel.TABLE + "." + ContactModel.COLUMN_IDENTITY;
    public static final String COLUMN_CONTACTS_STATE = ContactModel.TABLE + "." + ContactModel.COLUMN_STATE;
    public static final String COLUMN_CONTACTS_ACQUAINTANCE_LEVEL = ContactModel.TABLE + "." + ContactModel.COLUMN_ACQUAINTANCE_LEVEL;
    public static final String COLUMN_CONTACTS_TYPING_INDICATORS = ContactModel.TABLE + "." + ContactModel.COLUMN_TYPING_INDICATORS;
    public static final String COLUMN_CONTACTS_READ_RECEIPTS = ContactModel.TABLE + "." + ContactModel.COLUMN_READ_RECEIPTS;
    private static final String COLUMN_CONTACTS_LOOKUP_KEY = ContactModel.TABLE + "." + ContactModel.COLUMN_ANDROID_CONTACT_LOOKUP_KEY;
    private static final String COLUMN_AVAILABILITY_STATUS_IDENTITY = DbAvailabilityStatus.TABLE + "." + DbAvailabilityStatus.COLUMN_IDENTITY;
    private static final String COLUMN_AVAILABILITY_STATUS_CATEGORY = DbAvailabilityStatus.TABLE + "." + DbAvailabilityStatus.COLUMN_CATEGORY;
    private static final String COLUMN_AVAILABILITY_STATUS_DESCRIPTION = DbAvailabilityStatus.TABLE + "." + DbAvailabilityStatus.COLUMN_DESCRIPTION;

    @NonNull
    private final IdentityProvider identityProvider;

    public ContactModelFactory(
        @NonNull DatabaseProvider databaseProvider,
        @NonNull IdentityProvider identityProvider
    ) {
        super(databaseProvider, ContactModel.TABLE);
        this.identityProvider = identityProvider;
    }

    /**
     * Select one contact by the given identity
     *
     * <pre>{@code
     * SELECT contacts.*,
     *        contact_availability_status.category,
     *        contact_availability_status.description
     * FROM contacts
     * LEFT JOIN contact_availability_status
     *        ON contacts.identity = contact_availability_status.identity
     * WHERE contacts.identity = ?;
     * }</pre>
     */
    @Nullable
    public ContactModel getByIdentity(@NonNull String identity) {
        final String query =
            "SELECT " + ContactModel.TABLE + ".*, " + COLUMN_AVAILABILITY_STATUS_CATEGORY + ", " + COLUMN_AVAILABILITY_STATUS_DESCRIPTION +
                " FROM " + ContactModel.TABLE + " LEFT JOIN " + DbAvailabilityStatus.TABLE +
                " ON " + COLUMN_CONTACTS_IDENTITY + " = " + COLUMN_AVAILABILITY_STATUS_IDENTITY +
                " WHERE " + COLUMN_CONTACTS_IDENTITY + " = ?;";
        final Object[] bindArgs = new String[]{identity};
        final @Nullable Cursor cursor = getReadableDatabase().query(query, bindArgs);
        return getFirstOrNull(cursor);
    }

    /**
     * Select multiple contacts by the given identities
     *
     * <pre>{@code
     * SELECT contacts.*,
     *        contact_availability_status.category,
     *        contact_availability_status.description
     * FROM contacts
     * LEFT JOIN contact_availability_status
     *        ON contacts.identity = contact_availability_status.identity
     * WHERE contacts.identity IN (?, ?, ?, ...);
     * }</pre>
     */
    @NonNull
    public List<ContactModel> getByIdentities(@NonNull List<String> identities) {
        if (identities.isEmpty()) {
            return new ArrayList<>();
        }
        final @NonNull String placeholders = DatabaseUtil.makePlaceholders(identities.size());
        final String query =
            "SELECT " + ContactModel.TABLE + ".*, " + COLUMN_AVAILABILITY_STATUS_CATEGORY + ", " + COLUMN_AVAILABILITY_STATUS_DESCRIPTION +
                " FROM " + ContactModel.TABLE + " LEFT JOIN " + DbAvailabilityStatus.TABLE +
                " ON " + COLUMN_CONTACTS_IDENTITY + " = " + COLUMN_AVAILABILITY_STATUS_IDENTITY +
                " WHERE " + COLUMN_CONTACTS_IDENTITY + " IN (" + placeholders + ");";
        final Object[] bindArgs = identities.toArray(String[]::new);
        final @Nullable Cursor cursor = getReadableDatabase().query(query, bindArgs);
        return convertList(cursor);
    }

    /**
     * Select one contact by the given lookupKey
     *
     * <pre>{@code
     * SELECT contacts.*,
     *        contact_availability_status.category,
     *        contact_availability_status.description
     * FROM contacts
     * LEFT JOIN contact_availability_status
     *        ON contacts.identity = contact_availability_status.identity
     * WHERE contacts.androidContactId = ?;
     * }</pre>
     */
    @Nullable
    public ContactModel getByLookupKey(@NonNull String lookupKey) {
        final String query =
            "SELECT " + ContactModel.TABLE + ".*, " + COLUMN_AVAILABILITY_STATUS_CATEGORY + ", " + COLUMN_AVAILABILITY_STATUS_DESCRIPTION +
                " FROM " + ContactModel.TABLE + " LEFT JOIN " + DbAvailabilityStatus.TABLE +
                " ON " + COLUMN_CONTACTS_IDENTITY + " = " + COLUMN_AVAILABILITY_STATUS_IDENTITY +
                " WHERE " + COLUMN_CONTACTS_LOOKUP_KEY + " = ?;";
        final Object[] bindArgs = new String[]{lookupKey};
        final @Nullable Cursor cursor = getReadableDatabase().query(query, bindArgs);
        return getFirstOrNull(cursor);
    }

    /**
     * Table join:
     * <pre>{@code
     * contacts
     *   LEFT JOIN contact_availability_status
     *          ON (contacts.identity = contact_availability_status.identity)
     * }</pre>
     */
    @NonNull
    public List<ContactModel> select(
        @NonNull QueryBuilder queryBuilder,
        @Nullable String[] selectionArgs,
        @Nullable String sortOrder
    ) {
        final String tablesJoined =
            ContactModel.TABLE + " LEFT JOIN " + DbAvailabilityStatus.TABLE +
                " ON (" + COLUMN_CONTACTS_IDENTITY + " = " + COLUMN_AVAILABILITY_STATUS_IDENTITY + ")";
        queryBuilder.setTables(tablesJoined);
        final @Nullable Cursor cursor = queryBuilder.query(
            getReadableDatabase(),
            new String[] { ContactModel.TABLE + ".*", COLUMN_AVAILABILITY_STATUS_CATEGORY, COLUMN_AVAILABILITY_STATUS_DESCRIPTION },
            null,
            selectionArgs,
            null,
            null,
            sortOrder
        );
        return convertList(cursor);
    }

    @NonNull
    private List<ContactModel> convertList(@Nullable Cursor cursor) {
        final @NonNull List<ContactModel> results = new ArrayList<>();
        if (cursor == null) {
            return results;
        }
        try (cursor) {
            while (cursor.moveToNext()) {
                final @NonNull CursorHelper cursorHelper = new CursorHelper(cursor, getColumnIndexCache());
                final @NonNull ContactModel contactModel = convert(cursorHelper);
                results.add(contactModel);
            }
        } catch (SQLiteException e) {
            logger.debug("Exception", e);
        }
        return results;
    }

    @NonNull
    private ContactModel convert(@NonNull CursorHelper cursorHelper) {
        final @NonNull ContactModel[] contactModels = new ContactModel[1];
        cursorHelper.current((CursorHelper.Callback) cHelper -> {
            ContactModel contactModel = ContactModel.createUnchecked(
                cHelper.getString(ContactModel.COLUMN_IDENTITY),
                cHelper.getBlob(ContactModel.COLUMN_PUBLIC_KEY)
            );

            Integer notificationTriggerPolicyOverridePolicy = cHelper.getInt(ContactModel.COLUMN_NOTIFICATION_TRIGGER_POLICY_OVERRIDE_POLICY);
            ContactNotificationTriggerPolicyOverride notificationTriggerPolicyOverride;
            if (notificationTriggerPolicyOverridePolicy == null) {
                notificationTriggerPolicyOverride = null;
            } else {
                ContactNotificationTriggerPolicyOverridePolicy policy = ContactNotificationTriggerPolicyOverridePolicy
                    .deserialize(notificationTriggerPolicyOverridePolicy);
                if (policy == null) {
                    notificationTriggerPolicyOverride = null;
                } else {
                    Long expiresAtLong = cHelper.getLong(ContactModel.COLUMN_NOTIFICATION_TRIGGER_POLICY_OVERRIDE_EXPIRES_AT);
                    Instant expiresAt = expiresAtLong != null ? Instant.ofEpochMilli(expiresAtLong) : null;

                    notificationTriggerPolicyOverride = new ContactNotificationTriggerPolicyOverride(
                        policy,
                        expiresAt
                    );
                }
            }

            int conversationVisibilityValue = cHelper.getInt(ContactModel.COLUMN_CONVERSATION_VISIBILITY);
            ConversationVisibility conversationVisibility = ConversationVisibility.deserialize(conversationVisibilityValue);
            if (conversationVisibility == null) {
                logger.error(
                    "Conversation visibility value of contact is out of range: {}. Falling back to {}.",
                    conversationVisibilityValue,
                    ConversationVisibility.NORMAL
                );
                conversationVisibility = ConversationVisibility.NORMAL;
            }

            contactModel
                .setName(
                    cHelper.getString(ContactModel.COLUMN_FIRST_NAME),
                    cHelper.getString(ContactModel.COLUMN_LAST_NAME)
                )
                .setPublicNickName(cHelper.getString(ContactModel.COLUMN_PUBLIC_NICK_NAME))
                .setState(IdentityState.valueOf(cHelper.getString(ContactModel.COLUMN_STATE)))
                .setAndroidContactLookupKey(cHelper.getString(ContactModel.COLUMN_ANDROID_CONTACT_LOOKUP_KEY))
                .setIsWork(cHelper.getInt(ContactModel.COLUMN_IS_WORK) == 1)
                .setIdentityType(
                    cHelper.getInt(ContactModel.COLUMN_TYPE) == 1
                        ? IdentityType.WORK
                        : IdentityType.REGULAR
                )
                .setFeatureMask(cHelper.getLong(ContactModel.COLUMN_FEATURE_MASK))
                .setIdColorIndex(cHelper.getInt(ContactModel.COLUMN_ID_COLOR_INDEX))
                .setAcquaintanceLevel(
                    cHelper.getInt(ContactModel.COLUMN_ACQUAINTANCE_LEVEL) == 1
                        ? AcquaintanceLevel.GROUP_OR_DELETED
                        : AcquaintanceLevel.DIRECT
                )
                .setLocalAvatarExpires(cHelper.getInstant(ContactModel.COLUMN_LOCAL_AVATAR_EXPIRES))
                .setProfilePicBlobID(cHelper.getBlob(ContactModel.COLUMN_PROFILE_PIC_BLOB_ID))
                .setDateCreated(cHelper.getInstant(ContactModel.COLUMN_CREATED_AT))
                .setLastUpdate(cHelper.getInstant(ContactModel.COLUMN_LAST_UPDATE_AT))
                .setIsRestored(cHelper.getInt(ContactModel.COLUMN_IS_RESTORED) == 1)
                .setConversationVisibility(conversationVisibility)
                .setReadReceipts(cHelper.getInt(ContactModel.COLUMN_READ_RECEIPTS))
                .setTypingIndicators(cHelper.getInt(ContactModel.COLUMN_TYPING_INDICATORS))
                .setForwardSecurityState(cHelper.getInt(ContactModel.COLUMN_FORWARD_SECURITY_STATE))
                .setJobTitle(cHelper.getString(ContactModel.COLUMN_JOB_TITLE))
                .setDepartment(cHelper.getString(ContactModel.COLUMN_DEPARTMENT))
                .setNotificationTriggerPolicyOverride(notificationTriggerPolicyOverride)
                // F1Whisper: nullable per-conversation disappearing-messages timers (getInt returns a
                // nullable Integer, so NULL is preserved rather than coerced to 0).
                .setDisappearingMessagesTimerSeconds(cHelper.getInt(ContactModel.COLUMN_DISAPPEARING_MESSAGES_TIMER_SECONDS))
                .setPeerDisappearingTimerSeconds(cHelper.getInt(ContactModel.COLUMN_PEER_DISAPPEARING_TIMER_SECONDS));

            // Convert state to enum
            switch (cHelper.getString(ContactModel.COLUMN_STATE)) {
                case "INACTIVE":
                    contactModel.setState(IdentityState.INACTIVE);
                    break;
                case "INVALID":
                    contactModel.setState(IdentityState.INVALID);
                    break;
                case "ACTIVE":
                case "TEMPORARY": // Legacy state, see !276
                default:
                    contactModel.setState(IdentityState.ACTIVE);
                    break;
            }

            switch (cHelper.getInt(ContactModel.COLUMN_VERIFICATION_LEVEL)) {
                case 1:
                    contactModel.verificationLevel = VerificationLevel.SERVER_VERIFIED;
                    break;
                case 2:
                    contactModel.verificationLevel = VerificationLevel.FULLY_VERIFIED;
                    break;
                default:
                    contactModel.verificationLevel = VerificationLevel.UNVERIFIED;
            }

            // Availability status
            if (BuildConfig.AVAILABILITY_STATUS_ENABLED) {
                final @Nullable Integer availabilityStatusCategoryRaw = cHelper.getInt(DbAvailabilityStatus.COLUMN_CATEGORY);
                final @Nullable String availabilityStatusDescription = cHelper.getString(DbAvailabilityStatus.COLUMN_DESCRIPTION);
                // Since both these values are joined via LEFT JOIN, they actually can be null, although they are defined as NOT NULL in their
                // dedicated table
                if (availabilityStatusCategoryRaw != null && availabilityStatusDescription != null) {
                    final @Nullable AvailabilityStatus availabilityStatus = AvailabilityStatus.fromDatabaseValues(
                        availabilityStatusCategoryRaw,
                        availabilityStatusDescription
                    );
                    if (availabilityStatus != null) {
                        contactModel.setAvailabilityStatus(availabilityStatus);
                    }
                }
            }

            contactModels[0] = contactModel;

            return false;
        });

        return contactModels[0];
    }

    /**
     * Use the new contact model instead. The usage of this is dangerous as it does not reflect the
     * contact model.
     *
     * <b>Caution:</b> This legacy implementation does <b>not</b> handle availability statuses. Meaning that when updating a contact model, any
     * availability status will stay as is. When creating a contact, any defined availability status in the given model will be ignored.
     * {@code SqliteDatabaseBackend} supports both use cases.
     */
    @Deprecated
    public void create(@NonNull ContactModel contactModel) {
        if (isNullOrEmpty(contactModel.getIdentity())) {
            logger.error("try to create or update a contact model without identity");
            return;
        }
        if (contactModel.getIdentity().length() != ProtocolDefines.IDENTITY_LEN) {
            logger.error("Cannot add a contact with an invalid identity: {}", contactModel.getIdentity());
            return;
        }
        if (contactModel.getPublicKey().length != NaCl.PUBLIC_KEY_BYTES) {
            logger.error("Cannot add a contact with a public key of length {}", contactModel.getPublicKey());
            return;
        }
        if (doesContactWithIdentityExist(contactModel.getIdentity())) {
            logger.error("Contact already exists");
            return;
        }
        if (doesContactWithPublicKeyExist(contactModel.getPublicKey())) {
            logger.error("Cannot add a contact with duplicate public key {}", contactModel.getPublicKey());
            return;
        }
        if (contactModel.getIdentity().equals(identityProvider.getIdentityString())) {
            logger.error("Cannot add user as contact");
            return;
        }
        PredefinedContact predefinedContact = PredefinedContact.getPredefinedContact(contactModel.getIdentity());
        if (predefinedContact != null) {
            if (!Arrays.equals(predefinedContact.getPublicKey(), contactModel.getPublicKey())) {
                logger.error("Cannot add predefined contact with different public key");
                return;
            }
            if (contactModel.verificationLevel != VerificationLevel.FULLY_VERIFIED) {
                logger.error("Cannot add predefined contact with verification level {}", contactModel.verificationLevel);
                return;
            }
        }

        ContentValues contentValues = new ContentValues();

        contentValues.put(ContactModel.COLUMN_IDENTITY, contactModel.getIdentity());
        contentValues.put(ContactModel.COLUMN_PUBLIC_KEY, contactModel.getPublicKey());
        contentValues.put(ContactModel.COLUMN_FIRST_NAME, contactModel.getFirstName());
        contentValues.put(ContactModel.COLUMN_LAST_NAME, contactModel.getLastName());
        contentValues.put(ContactModel.COLUMN_PUBLIC_NICK_NAME, contactModel.getPublicNickName());
        contentValues.put(ContactModel.COLUMN_VERIFICATION_LEVEL, contactModel.verificationLevel.ordinal());

        if (contactModel.getState() == null) {
            contactModel.setState(IdentityState.ACTIVE);
        }
        contentValues.put(ContactModel.COLUMN_STATE, contactModel.getState().toString());
        contentValues.put(ContactModel.COLUMN_ANDROID_CONTACT_LOOKUP_KEY, contactModel.getAndroidContactLookupKey());
        contentValues.put(ContactModel.COLUMN_FEATURE_MASK, contactModel.getFeatureMask());
        contentValues.put(ContactModel.COLUMN_ID_COLOR_INDEX, contactModel.getIdColor().getColorIndex());
        contentValues.put(ContactModel.COLUMN_LOCAL_AVATAR_EXPIRES, contactModel.getLocalAvatarExpires() != null ?
            contactModel.getLocalAvatarExpires().toEpochMilli()
            : null);
        contentValues.put(ContactModel.COLUMN_IS_WORK, contactModel.isWorkVerified());
        int identityTypeValue;
        final @Nullable IdentityType identityType = contactModel.getIdentityType();
        if (identityType == null) {
            logger.warn("Identity type is null. Using 'normal' as fallback.");
            identityTypeValue = 0;
        } else {
            switch (identityType) {
                case REGULAR:
                    identityTypeValue = 0;
                    break;
                case WORK:
                    identityTypeValue = 1;
                    break;
                default:
                    throw new IllegalStateException("Illegal enum variant for identity type");
            }
        }
        contentValues.put(ContactModel.COLUMN_TYPE, identityTypeValue);
        contentValues.put(ContactModel.COLUMN_PROFILE_PIC_BLOB_ID, contactModel.getProfilePicBlobID());
        contentValues.put(ContactModel.COLUMN_CREATED_AT, contactModel.getDateCreated() != null ? contactModel.getDateCreated().toEpochMilli() : null);
        contentValues.put(ContactModel.COLUMN_LAST_UPDATE_AT, contactModel.getLastUpdate() != null ? contactModel.getLastUpdate().toEpochMilli() : null);
        int acquaintanceLevelValue;
        switch (contactModel.getAcquaintanceLevel()) {
            case DIRECT:
                acquaintanceLevelValue = 0;
                break;
            case GROUP_OR_DELETED:
                acquaintanceLevelValue = 1;
                break;
            default:
                throw new IllegalStateException("Illegal enum variant for acquaintance level");
        }
        contentValues.put(ContactModel.COLUMN_ACQUAINTANCE_LEVEL, acquaintanceLevelValue);
        contentValues.put(ContactModel.COLUMN_IS_RESTORED, contactModel.isRestored());
        contentValues.put(ContactModel.COLUMN_CONVERSATION_VISIBILITY, contactModel.getConversationVisibility().getSerializedValue());
        contentValues.put(ContactModel.COLUMN_READ_RECEIPTS, contactModel.getReadReceipts());
        contentValues.put(ContactModel.COLUMN_TYPING_INDICATORS, contactModel.getTypingIndicators());
        contentValues.put(ContactModel.COLUMN_FORWARD_SECURITY_STATE, contactModel.getForwardSecurityState());
        contentValues.put(ContactModel.COLUMN_JOB_TITLE, contactModel.getJobTitle());
        contentValues.put(ContactModel.COLUMN_DEPARTMENT, contactModel.getDepartment());
        ContactNotificationTriggerPolicyOverride notificationTriggerPolicyOverride = contactModel.getNotificationTriggerPolicyOverride();
        Integer notificationTriggerPolicyOverridePolicy = notificationTriggerPolicyOverride != null
            ? notificationTriggerPolicyOverride.getPolicy().getSerializedValue()
            : null;
        contentValues.put(ContactModel.COLUMN_NOTIFICATION_TRIGGER_POLICY_OVERRIDE_POLICY, notificationTriggerPolicyOverridePolicy);
        Long expiresAt = notificationTriggerPolicyOverride != null
            ? (notificationTriggerPolicyOverride.getExpiresAt() != null
               ? notificationTriggerPolicyOverride.getExpiresAt().toEpochMilli()
               : null
        )
            : null;
        contentValues.put(ContactModel.COLUMN_NOTIFICATION_TRIGGER_POLICY_OVERRIDE_EXPIRES_AT, expiresAt);
        contentValues.put(ContactModel.COLUMN_DISAPPEARING_MESSAGES_TIMER_SECONDS, contactModel.getDisappearingMessagesTimerSeconds());
        contentValues.put(ContactModel.COLUMN_PEER_DISAPPEARING_TIMER_SECONDS, contactModel.getPeerDisappearingTimerSeconds());
        // Note: Sync state not implemented in "old model" anymore

        getWritableDatabase().insertOrThrow(
            this.getTableName(),
            null,
            contentValues
        );
    }

    private boolean doesContactWithIdentityExist(@NonNull String identity) {
        return exists(getReadableDatabase(), getTableName(), ContactModel.COLUMN_IDENTITY + "= ?", new String[]{ identity });
    }

    private boolean doesContactWithPublicKeyExist(@NonNull byte[] publicKey) {
        return existsByBlob(getReadableDatabase(), getTableName(), ContactModel.COLUMN_PUBLIC_KEY + "= ?", publicKey);
    }

    /**
     * Updates the last update flag of the given identity.
     */
    public void setLastUpdate(@NonNull String identity, @Nullable Instant lastUpdate) {
        final @Nullable Long lastUpdateTime = lastUpdate != null ? lastUpdate.toEpochMilli() : null;
        ContentValues contentValues = new ContentValues();
        contentValues.put(ContactModel.COLUMN_LAST_UPDATE_AT, lastUpdateTime);

        getWritableDatabase().update(
            ContactModel.TABLE,
            contentValues,
            ContactModel.COLUMN_IDENTITY + " = ?",
            new String[]{identity}
        );
    }

    /**
     * F1Whisper: persists the shared per-conversation disappearing-messages timer of the given
     * identity ({@code null} = off). Column-scoped and update-only: it never inserts, so a contact
     * that was deleted in the meantime is not resurrected.
     */
    public void setDisappearingMessagesTimerSeconds(
        @NonNull String identity,
        @Nullable Integer timerSeconds
    ) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(ContactModel.COLUMN_DISAPPEARING_MESSAGES_TIMER_SECONDS, timerSeconds);

        getWritableDatabase().update(
            ContactModel.TABLE,
            contentValues,
            ContactModel.COLUMN_IDENTITY + " = ?",
            new String[]{identity}
        );
    }

    /**
     * Updates the forward security state of the given identity.
     */
    public void setForwardSecurityState(
        @NonNull String identity,
        @ContactModel.ForwardSecurityState int forwardSecurityState
    ) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(ContactModel.COLUMN_FORWARD_SECURITY_STATE, forwardSecurityState);

        getWritableDatabase().update(
            ContactModel.TABLE,
            contentValues,
            ContactModel.COLUMN_IDENTITY + " = ?",
            new String[]{identity}
        );
    }

    @Nullable
    private ContactModel getFirstOrNull(final @Nullable Cursor cursor) {
        if (cursor == null) {
            return null;
        }
        try (cursor) {
            if (cursor.moveToFirst()) {
                final @NonNull CursorHelper cursorHelper = new CursorHelper(cursor, getColumnIndexCache());
                return convert(cursorHelper);
            }
        } catch (Exception e) {
            logger.error("Exception", e);
        }
        return null;
    }

    public static class Creator implements DatabaseCreationProvider {

        @Override
        @NonNull
        public String[] getCreationStatements() {
            return new String[]{
                "CREATE TABLE `" + ContactModel.TABLE + "` (" +
                    "`" + ContactModel.COLUMN_IDENTITY + "` VARCHAR ," +
                    "`" + ContactModel.COLUMN_PUBLIC_KEY + "` BLOB ," +
                    "`" + ContactModel.COLUMN_FIRST_NAME + "` VARCHAR ," +
                    "`" + ContactModel.COLUMN_LAST_NAME + "` VARCHAR ," +
                    "`" + ContactModel.COLUMN_PUBLIC_NICK_NAME + "` VARCHAR ," +
                    "`" + ContactModel.COLUMN_VERIFICATION_LEVEL + "` INTEGER ," +
                    "`" + ContactModel.COLUMN_STATE + "` VARCHAR DEFAULT 'ACTIVE' NOT NULL ," +
                    "`" + ContactModel.COLUMN_ANDROID_CONTACT_LOOKUP_KEY + "` VARCHAR ," +
                    "`" + ContactModel.COLUMN_FEATURE_MASK + "` INTEGER DEFAULT 0 NOT NULL ," +
                    "`" + ContactModel.COLUMN_ID_COLOR_INDEX + "` INTEGER ," +
                    "`" + ContactModel.COLUMN_LOCAL_AVATAR_EXPIRES + "` BIGINT," +
                    "`" + ContactModel.COLUMN_IS_WORK + "` TINYINT DEFAULT 0," +
                    "`" + ContactModel.COLUMN_TYPE + "` INT DEFAULT 0," +
                    "`" + ContactModel.COLUMN_PROFILE_PIC_BLOB_ID + "` BLOB DEFAULT NULL," +
                    "`" + ContactModel.COLUMN_CREATED_AT + "` BIGINT DEFAULT 0," +
                    "`" + ContactModel.COLUMN_LAST_UPDATE_AT + "` INTEGER," +
                    "`" + ContactModel.COLUMN_ACQUAINTANCE_LEVEL + "` TINYINT DEFAULT 0 NOT NULL," +
                    "`" + ContactModel.COLUMN_IS_RESTORED + "` TINYINT DEFAULT 0," +
                    "`" + ContactModel.COLUMN_CONVERSATION_VISIBILITY + "` INTEGER DEFAULT 0 NOT NULL," +
                    "`" + ContactModel.COLUMN_READ_RECEIPTS + "` TINYINT DEFAULT 0," +
                    "`" + ContactModel.COLUMN_TYPING_INDICATORS + "` TINYINT DEFAULT 0," +
                    "`" + ContactModel.COLUMN_FORWARD_SECURITY_STATE + "` TINYINT DEFAULT 0," +
                    "`" + ContactModel.COLUMN_SYNC_STATE + "` INTEGER NOT NULL DEFAULT 0," +
                    "`" + ContactModel.COLUMN_JOB_TITLE + "` VARCHAR DEFAULT NULL," +
                    "`" + ContactModel.COLUMN_DEPARTMENT + "` VARCHAR DEFAULT NULL," +
                    "`" + ContactModel.COLUMN_NOTIFICATION_TRIGGER_POLICY_OVERRIDE_POLICY + "` INTEGER DEFAULT NULL," +
                    "`" + ContactModel.COLUMN_NOTIFICATION_TRIGGER_POLICY_OVERRIDE_EXPIRES_AT + "` BIGINT DEFAULT NULL," +
                    "`" + ContactModel.COLUMN_WORK_LAST_FULL_SYNC_AT + "` DATETIME DEFAULT NULL," +
                    "`" + ContactModel.COLUMN_DISAPPEARING_MESSAGES_TIMER_SECONDS + "` INTEGER DEFAULT NULL," +
                    "`" + ContactModel.COLUMN_PEER_DISAPPEARING_TIMER_SECONDS + "` INTEGER DEFAULT NULL," +
                    "PRIMARY KEY (`" + ContactModel.COLUMN_IDENTITY + "`) );"
            };
        }
    }
}
