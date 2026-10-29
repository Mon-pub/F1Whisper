package ch.threema.app.services;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.ContactsContract;
import android.widget.ImageView;

import com.bumptech.glide.RequestManager;

import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import androidx.annotation.AnyThread;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;
import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.eventbus.GlobalEventBuses;
import ch.threema.app.eventbus.events.ContactEvent;
import ch.threema.app.glide.AvatarOptions;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.messagereceiver.ContactMessageReceiver;
import ch.threema.app.multidevice.MultiDeviceManager;
import ch.threema.app.preference.service.SynchronizedSettingsService;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.routines.UpdateFeatureLevelRoutine;
import ch.threema.app.services.avatarcache.AvatarCacheService;
import ch.threema.app.stores.DatabaseContactStore;
import ch.threema.app.tasks.TaskCreator;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.ContactUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.base.ThreemaException;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

import ch.threema.data.datatypes.ConversationVisibility;
import ch.threema.data.models.ContactModelData;
import ch.threema.data.models.ModelDeletedException;
import ch.threema.data.repositories.ContactModelRepository;
import ch.threema.domain.fs.DHSession;
import ch.threema.domain.models.AcquaintanceLevel;
import ch.threema.domain.models.Contact;
import ch.threema.domain.models.IdentityState;
import ch.threema.domain.models.IdentityType;
import ch.threema.domain.models.ReadReceiptPolicy;
import ch.threema.domain.models.TypingIndicatorPolicy;
import ch.threema.domain.models.VerificationLevel;
import ch.threema.domain.protocol.ThreemaFeature;
import ch.threema.domain.protocol.api.APIConnector;
import ch.threema.domain.stores.IdentityStore;
import ch.threema.domain.taskmanager.ActiveTaskCodec;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.storage.DatabaseProvider;
import ch.threema.storage.DatabaseService;
import ch.threema.storage.DatabaseUtil;
import ch.threema.storage.QueryBuilder;
import ch.threema.storage.factories.ContactModelFactory;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.group.GroupMemberModel;
import ch.threema.storage.models.group.GroupModelOld;
import ch.threema.storage.models.access.AccessModel;
import ch.threema.data.datatypes.IdColor;

import static ch.threema.app.glide.AvatarOptions.DefaultAvatarPolicy.CUSTOM_AVATAR;

public class ContactServiceImpl implements ContactService {
    private static final Logger logger = getThreemaLogger("ContactServiceImpl");

    @NonNull
    private final Context context;
    private final AvatarCacheService avatarCacheService;
    private final DatabaseContactStore contactStore;
    @NonNull
    private final DatabaseService databaseService;
    @NonNull
    private final DatabaseProvider databaseProvider;
    private final UserService userService;
    private final IdentityStore identityStore;
    @NonNull
    private final PreferenceService preferenceService;
    @NonNull
    private final SynchronizedSettingsService synchronizedSettingsService;
    // NOTE: The contact model cache will become unnecessary once everything uses the new data
    // layer, since that data layer has caching built-in.
    private final Map<String, ContactModel> contactModelCache;
    @NonNull
    private final BlockedIdentitiesService blockedIdentitiesService;
    private final ProfilePictureRecipientsService profilePictureRecipientsService;
    private final FileService fileService;
    private final APIConnector apiConnector;
    @NonNull
    private final TaskCreator taskCreator;
    @NonNull
    private final MultiDeviceManager multiDeviceManager;
    @NonNull
    private final ContactModelRepository contactModelRepository;
    @NonNull
    private final GlobalEventBuses globalEventBuses;

    /**
     * F1Whisper: rate-limits the typing indicators this device SENDS, keyed by recipient. Deliberately separate from
     * {@link #typingIdentities} and {@link #typingTimerTasks} above, which hold the state of indicators RECEIVED.
     */
    private final TypingIndicatorAdmission<String> typingAdmission =
        new TypingIndicatorAdmission<>(this::sendAdmittedTypingIndicator);

    private ContactModel me;

    public ContactServiceImpl(
        @NonNull Context context,
        DatabaseContactStore contactStore,
        AvatarCacheService avatarCacheService,
        @NonNull DatabaseService databaseService,
        @NonNull DatabaseProvider databaseProvider,
        UserService userService,
        IdentityStore identityStore,
        @NonNull PreferenceService preferenceService,
        @NonNull SynchronizedSettingsService synchronizedSettingsService,
        @NonNull BlockedIdentitiesService blockedIdentitiesService,
        ProfilePictureRecipientsService profilePictureRecipientsService,
        FileService fileService,
        CacheService cacheService,
        APIConnector apiConnector,
        @NonNull ContactModelRepository contactModelRepository,
        @NonNull TaskCreator taskCreator,
        @NonNull MultiDeviceManager multiDeviceManager,
        @NonNull GlobalEventBuses globalEventBuses
    ) {

        this.context = context;
        this.avatarCacheService = avatarCacheService;
        this.contactStore = contactStore;
        this.databaseService = databaseService;
        this.databaseProvider = databaseProvider;
        this.userService = userService;
        this.identityStore = identityStore;
        this.preferenceService = preferenceService;
        this.synchronizedSettingsService = synchronizedSettingsService;
        this.blockedIdentitiesService = blockedIdentitiesService;
        this.profilePictureRecipientsService = profilePictureRecipientsService;
        this.fileService = fileService;
        this.apiConnector = apiConnector;
        this.contactModelRepository = contactModelRepository;
        this.taskCreator = taskCreator;
        this.multiDeviceManager = multiDeviceManager;
        this.globalEventBuses = globalEventBuses;
        this.contactModelCache = cacheService.getContactModelCache();
    }

    @Nullable
    @Deprecated
    private ContactModel getMe() {
        if (this.me == null && this.userService.getIdentity() != null) {
            this.me = ContactModel.create(
                this.userService.getIdentity(),
                this.userService.getPublicKey()
            );
            this.me.setPublicNickName(this.userService.getPublicNickname());
            this.me.setState(IdentityState.ACTIVE);
            this.me.setFirstName(context.getString(R.string.me_myself_and_i));
            this.me.verificationLevel = VerificationLevel.FULLY_VERIFIED;
            this.me.setFeatureMask(-1);
        }
        return this.me;
    }

    @Override
    @NonNull
    public List<ContactModel> getAllDisplayed(@NonNull ContactSelection contactSelection) {
        return this.find(new Filter() {
            @Override
            public IdentityState[] states() {
                if (preferenceService.showInactiveContacts()) {
                    switch (contactSelection) {
                        case EXCLUDE_INVALID:
                            return new IdentityState[]{
                                IdentityState.ACTIVE,
                                IdentityState.INACTIVE,
                            };
                        case INCLUDE_INVALID:
                        default:
                            return null;
                    }
                } else {
                    return new IdentityState[]{IdentityState.ACTIVE};
                }
            }

            @Override
            public Boolean fetchMissingFeatureLevel() {
                return null;
            }

            @Override
            public Boolean includeMyself() {
                return false;
            }

            @Override
            public Boolean includeHidden() {
                return false;
            }
        });
    }

    @Override
    @NonNull
    public List<ContactModel> getAll() {
        return find(null);
    }

    @Override
    @NonNull
    public List<ContactModel> find(@Nullable Filter filter) {
        final @NonNull ContactModelFactory contactModelFactory = this.databaseService.getContactModelFactory();
        final @NonNull QueryBuilder queryBuilder = new QueryBuilder();
        final @NonNull List<String> selectionArgs = new ArrayList<>();

        if (filter != null) {
            final IdentityState[] filterStates = filter.states();
            if (filterStates != null && filterStates.length > 0) {
                // dirty, add placeholder should be added to makePlaceholders
                final @NonNull String placeholders = DatabaseUtil.makePlaceholders(filterStates.length);
                queryBuilder.appendWhere(
                    ContactModelFactory.COLUMN_CONTACTS_STATE + " IN (" + placeholders + ")"
                );
                for (IdentityState identityState : filterStates) {
                    selectionArgs.add(identityState.toString());
                }
            }

            if (!filter.includeHidden()) {
                queryBuilder.appendWhere(ContactModelFactory.COLUMN_CONTACTS_ACQUAINTANCE_LEVEL + "=0");
            }

            if (!filter.includeMyself()) {
                final @Nullable String myIdentity = userService.getIdentity();
                if (myIdentity != null) {
                    queryBuilder.appendWhere(
                        ContactModelFactory.COLUMN_CONTACTS_IDENTITY + "!=?"
                    );
                    selectionArgs.add(myIdentity);
                }
            }

            if (filter.onlyWithReceiptSettings()) {
                queryBuilder.appendWhere(
                    ContactModelFactory.COLUMN_CONTACTS_TYPING_INDICATORS + " !=0 OR " + ContactModelFactory.COLUMN_CONTACTS_READ_RECEIPTS + " !=0"
                );
            }
        }

        @NonNull List<ContactModel> result = contactModelFactory.select(
            queryBuilder,
            selectionArgs.toArray(new String[0]),
            null
        );

        // sort
        final boolean sortOrderFirstName = preferenceService.isContactListSortingFirstName();

        Collections.sort(result, ContactUtil.getContactComparator(sortOrderFirstName));

        if (filter != null) {

            final Long feature = filter.requiredFeature();

            //update feature level routine call
            if (feature != null) {
                if (filter.fetchMissingFeatureLevel()) {
                    //do not filtering with sql
                    UpdateFeatureLevelRoutine routine = new UpdateFeatureLevelRoutine(
                        contactModelRepository,
                        userService,
                        this.apiConnector,
                        result
                            .stream()
                            .filter(Objects::nonNull)
                            .filter(model -> !ThreemaFeature.hasFeature(model.getFeatureMask(), feature))
                            .map(Contact::getIdentity)
                            .collect(Collectors.toList())
                    );
                    routine.run();
                }

                // Filter the result by the required feature
                result = result
                    .stream()
                    .map(outdatedModel -> getByIdentity(outdatedModel.getIdentity()))
                    .filter(model -> model != null && ThreemaFeature.hasFeature(model.getFeatureMask(), feature))
                    .collect(Collectors.toList());
            }

        }

        for (int n = 0; n < result.size(); n++) {
            synchronized (this.contactModelCache) {
                String identity = result.get(n).getIdentity();
                if (this.contactModelCache.containsKey(identity)) {
                    //replace selected model with the cached one
                    //but do not cache the result
                    result.set(n, this.contactModelCache.get(identity));
                }
            }
        }
        return result;
    }

    @Override
    @Nullable
    public ContactModel getByLookupKey(String lookupKey) {
        if (lookupKey == null) {
            return null;
        }

        return this.contactStore.getContactModelForLookupKey(lookupKey);
    }

    @Override
    @Nullable
    @Deprecated
    public ContactModel getByIdentity(@Nullable String identity) {
        if (identity == null) {
            return null;
        }

        // return me object
        final @Nullable ContactModel contactModelMe = getMe();
        if (contactModelMe != null && contactModelMe.getIdentity().equals(identity)) {
            return contactModelMe;
        }

        synchronized (this.contactModelCache) {
            // check cache first
            if (this.contactModelCache.containsKey(identity)) {
                return this.contactModelCache.get(identity);
            }
            // try to read from database
            final @Nullable ContactModel contactModel = contactStore.getContactForIdentity(identity);
            if (contactModel != null) {
                cache(contactModel);
            }
            return contactModel;
        }
    }

    @NonNull
    @Override
    @Deprecated
    public List<ContactModel> getByIdentities(@NonNull List<String> identities) {
        final @NonNull List<ContactModel> results = new ArrayList<>();
        if (identities.isEmpty()) {
            return results;
        }

        synchronized (contactModelCache) {
            final @NonNull List<String> identitiesMissingInCache = new ArrayList<>();
            final @Nullable ContactModel contactModelMe = getMe();

            // try to find all in cache
            for (String identity : identities) {

                if (contactModelMe != null && contactModelMe.getIdentity().equals(identity)) {
                    results.add(contactModelMe);
                    continue;
                }

                final @Nullable ContactModel contactModelCached = contactModelCache.get(identity);
                if (contactModelCached != null) {
                    results.add(contactModelCached);
                } else {
                    identitiesMissingInCache.add(identity);
                }
            }

            // happy case: all were found in cache
            if (identitiesMissingInCache.isEmpty()) {
                return results;
            }

            // try to read all missing models from database
            final @NonNull List<ContactModel> contactModelsFromDb = contactStore.getContactsForIdentities(identitiesMissingInCache);
            results.addAll(contactModelsFromDb);
            cacheAll(contactModelsFromDb);
        }

        return results;
    }

    /**
     * Make sure to <b>hold a lock</b> on {@code contactModelCache} while calling this
     */
    private void cacheAll(@NonNull List<ContactModel> contactModels) {
        for (ContactModel contactModel : contactModels) {
            cache(contactModel);
        }
    }

    /**
     * Make sure to <b>hold a lock</b> on {@code contactModelCache} the cache while calling this
     */
    private void cache(@NonNull ContactModel contactModel) {
        contactModelCache.put(contactModel.getIdentity(), contactModel);
    }

    @Override
    @NonNull
    public List<ContactModel> getAllDisplayedWork(@NonNull ContactSelection selection) {
        return getAllDisplayed(selection)
            .stream()
            .filter(ContactModel::isWorkVerified)
            .collect(Collectors.toList());
    }

    @Override
    @NonNull
    public List<ContactModel> getAllWork() {
        return getAll()
            .stream()
            .filter(ContactModel::isWorkVerified)
            .collect(Collectors.toList());
    }

    @Override
    public int countIsWork() {
        int count = 0;
        Cursor c = this.databaseProvider.getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM contacts " +
                "WHERE " + ContactModel.COLUMN_IS_WORK + " = 1 " +
                "AND " + ContactModel.COLUMN_ACQUAINTANCE_LEVEL + " = 0", null);

        if (c != null) {
            if (c.moveToFirst()) {
                count = c.getInt(0);
            }
            c.close();
        }
        return count;
    }

    @Override
    public List<ContactModel> getCanReceiveProfilePics() {
        return find(new Filter() {
            @Override
            public IdentityState[] states() {
                if (preferenceService.showInactiveContacts()) {
                    return null;
                }
                return new IdentityState[]{IdentityState.ACTIVE};
            }

            @Override
            public Boolean fetchMissingFeatureLevel() {
                return null;
            }

            @Override
            public Boolean includeMyself() {
                return false;
            }

            @Override
            public Boolean includeHidden() {
                return false;
            }
        })
            .stream()
            .filter(model -> !ContactUtil.isEchoEchoOrGatewayContact(model))
            .collect(Collectors.toList());
    }

    @Override
    @Nullable
    public List<String> getSynchronizedIdentities() {
        Cursor c = this.databaseProvider.getReadableDatabase().rawQuery("" +
                "SELECT identity FROM contacts " +
                "WHERE androidContactId IS NOT NULL AND androidContactId != ?",
            new String[]{""});

        if (c != null) {
            List<String> identities = new ArrayList<>();
            while (c.moveToNext()) {
                identities.add(c.getString(0));
            }
            c.close();
            return identities;
        }

        return null;
    }

    @Override
    @Nullable
    public List<String> getIdentitiesByVerificationLevel(VerificationLevel verificationLevel) {
        Cursor c = this.databaseProvider.getReadableDatabase().rawQuery("" +
                "SELECT identity FROM contacts " +
                "WHERE verificationLevel = ?",
            new String[]{String.valueOf(verificationLevel.getCode())});

        if (c != null) {
            List<String> identities = new ArrayList<>();
            while (c.moveToNext()) {
                identities.add(c.getString(0));
            }
            c.close();
            return identities;
        }

        return null;
    }

    @Override
    public void sendTypingIndicator(String toIdentity, boolean isTyping) {
        ContactModel contactModel = getByIdentity(toIdentity);
        if (contactModel == null) {
            logger.error("Cannot send typing indicator");
            return;
        }

        boolean sendTypingIndicator;
        switch (contactModel.getTypingIndicators()) {
            case ContactModel.SEND:
                sendTypingIndicator = true;
                break;
            case ContactModel.DONT_SEND:
                sendTypingIndicator = false;
                break;
            default:
                sendTypingIndicator = synchronizedSettingsService.isTypingIndicatorEnabled();
                break;
        }

        if (!sendTypingIndicator) {
            return;
        }

        // F1Whisper: the gate sits here rather than in the composer's watcher because the watcher is not the only
        // caller - legacy Web's IsTypingHandler calls this method directly. See TypingIndicatorAdmission.
        typingAdmission.admit(toIdentity, isTyping);
    }

    /**
     * F1Whisper: puts an admitted typing indicator on the wire. Called by {@link #typingAdmission}, possibly from its
     * scheduler thread once a coalesced start's window has closed, so it re-resolves the contact rather than closing
     * over one.
     */
    private void sendAdmittedTypingIndicator(@NonNull String toIdentity, boolean isTyping) {
        ContactModel contactModel = getByIdentity(toIdentity);
        if (contactModel == null) {
            logger.warn("Cannot send typing indicator: the contact is gone");
            return;
        }
        try {
            createReceiver(contactModel).sendTypingIndicatorMessage(isTyping);
        } catch (ThreemaException e) {
            logger.error("Could not send typing indicator", e);
        }
    }

    @Override
    public void setAcquaintanceLevel(
        @NonNull String identity,
        @NonNull AcquaintanceLevel acquaintanceLevel
    ) {
        final ch.threema.data.models.ContactModel contactModel =
            contactModelRepository.getByIdentity(identity);

        if (contactModel != null) {
            try {
                contactModel.setAcquaintanceLevelFromLocal(acquaintanceLevel);
            } catch (ModelDeletedException e) {
                logger.warn("Could not set acquaintance level because model has been deleted", e);
            }
        }
    }

    @Override
    public void unarchive(
        @NonNull String identity,
        @NonNull TriggerSource triggerSource
    ) {
        final ch.threema.data.models.ContactModel contactModel = contactModelRepository.getByIdentity(identity);
        if (contactModel == null) {
            logger.warn("Cannot unarchive '{}' because contact model is null", identity);
            return;
        }
        ContactModelData contactModelData = contactModel.getData();
        if (contactModelData == null) {
            logger.warn("Cannot unarchive '{}' because contact model data is null", identity);
            return;
        }
        if (contactModelData.conversationVisibility != ConversationVisibility.ARCHIVED) {
            logger.info("Not unarchiving '{}' as it is currently not archived", identity);
            return;
        }

        try {
            switch (triggerSource) {
                case LOCAL:
                case REMOTE:
                    contactModel.setConversationVisibilityFromLocalOrRemote(ConversationVisibility.NORMAL);
                    break;
                case SYNC:
                    contactModel.setConversationVisibilityFromSync(ConversationVisibility.NORMAL);
                    break;
            }
        } catch (ModelDeletedException e) {
            logger.warn("Could not unarchive contact {} because model has been deleted", identity, e);
        }
    }

    @Override
    public void bumpLastUpdate(@NonNull String identity) {
        logger.info("Bump last update for contact with identity {}", identity);
        if (getByIdentity(identity) != null) {
            Instant lastUpdate = Instant.now();
            invalidateCache(identity);
            databaseService.getContactModelFactory().setLastUpdate(identity, lastUpdate);
            globalEventBuses.getContacts().emit(ContactEvent.ContactUpdated.javaCreate(identity));
        } else {
            logger.warn(
                "Could not bump last update because the contact with identity {} is null",
                identity
            );
        }
    }

    @Nullable
    @Override
    public Instant getLastUpdate(@NonNull String identity) {
        ContactModel contactModel = getByIdentity(identity);
        if (contactModel != null) {
            return contactModel.getLastUpdate();
        } else {
            return null;
        }
    }

    @Override
    public void clearLastUpdate(@NonNull String identity) {
        if (getByIdentity(identity) != null) {
            invalidateCache(identity);
            databaseService.getContactModelFactory().setLastUpdate(identity, null);
            globalEventBuses.getContacts().emit(ContactEvent.ContactUpdated.javaCreate(identity));
        }
    }

    @Override
    public void persistForwardSecurityState(
        @NonNull String identity,
        @ContactModel.ForwardSecurityState int forwardSecurityState
    ) {
        databaseService.getContactModelFactory().setForwardSecurityState(identity, forwardSecurityState);
        invalidateCache(identity);
    }

    @NonNull
    @Override
    public AccessModel getAccess(@Nullable String identity) {
        if (identity == null) {
            return () -> false;
        } else {
            boolean isInGroup = false;
            Cursor c = this.databaseProvider.getReadableDatabase().rawQuery(
                DatabaseUtil.IS_GROUP_MEMBER_QUERY,
                identity
            );

            if (c != null) {
                if (c.moveToFirst()) {
                    isInGroup = c.getInt(0) == 1;
                }
                c.close();
            }

            if (isInGroup) {
                return new AccessModel() {
                    @Override
                    public boolean canDelete() {
                        return false;
                    }
                };
            }
        }

        return () -> true;
    }

    @AnyThread
    @Override
    public Bitmap getAvatar(@Nullable String identity, @NonNull AvatarOptions options) {
        if (identity == null) {
            return null;
        }

        // Note that we should not abort if no new contact model can be found as the new model does
        // not exist for the user itself whereas the old model may refer to the user. Therefore, we
        // may still get an avatar for the provided (old) model.

        // If the custom avatar is requested without default fallback and there is no avatar for
        // this contact, we can return null directly. Important: This is necessary to prevent glide
        // from logging an unnecessary error stack trace.
        if (options.defaultAvatarPolicy == CUSTOM_AVATAR && !hasAvatarOrContactPhoto(identity)) {
            return null;
        }

        return this.avatarCacheService.getIdentityAvatar(identity, options);
    }

    private boolean hasAvatarOrContactPhoto(@Nullable String identity) {
        if (identity == null) {
            return false;
        }

        return fileService.hasUserDefinedProfilePicture(identity) || fileService.hasContactDefinedProfilePicture(identity);
    }

    /**
     * See {@link AvatarService#getAvatarColor(Object)}
     * <p>
     * This respects the "isDefaultContactPictureColored" setting. If the setting is
     * set to `false`, the default (grey) color will be returned.
     */
    @Override
    public @ColorInt int getAvatarColor(@Nullable String identity) {
        if (this.preferenceService.isDefaultContactPictureColored() && identity != null) {
            return getIdentityColor(identity);
        }
        return IdColor.invalid().getThemedColor(this.context);
    }

    private @ColorInt int getIdentityColor(@NonNull String identity) {
        ContactModel contact = getByIdentity(identity);
        return contact != null
            ? contact.getIdColor().getThemedColor(context)
            : IdColor.ofIdentity(identity).getThemedColor(context);
    }

    @AnyThread
    @Override
    public void loadAvatarIntoImage(
        @NonNull String identity,
        @NonNull ImageView imageView,
        @NonNull AvatarOptions options,
        @NonNull RequestManager requestManager
    ) {
        avatarCacheService.loadIdentityAvatarIntoImage(identity, imageView, options, requestManager);
    }

    @Override
    @NonNull
    public ContactMessageReceiver createReceiver(ContactModel contact) {
        // Note that at this point we can assume that the service manager exists, as the contact
        // service is obviously created.
        ServiceManager serviceManager = ServiceManager.require();

        return new ContactMessageReceiver(
            contact,
            this,
            serviceManager,
            this.databaseService,
            this.identityStore,
            this.blockedIdentitiesService,
            serviceManager.getModelRepositories().getContacts()
        );
    }

    @Override
    @Nullable
    public ContactMessageReceiver createReceiver(@NonNull ch.threema.data.models.ContactModel contact) {
        return createReceiver(contact.getIdentity());
    }

    @Override
    @Nullable
    public ContactMessageReceiver createReceiver(@NonNull String identity) {
        ContactModel contactModel = getByIdentity(identity);
        if (contactModel != null) {
            return createReceiver(contactModel);
        } else {
            return null;
        }
    }

    @Override
    public void removeAllSystemContactLinks() {
        this.contactModelRepository.getAll()
            .stream()
            .filter(contactModel -> {
                ContactModelData contactModelData = contactModel.getData();
                return contactModelData != null && contactModelData.isLinkedToAndroidContact();
            })
            .forEach(ch.threema.data.models.ContactModel::removeAndroidContactLink);
    }

    @Override
    public boolean setUserDefinedProfilePicture(
        @Nullable final ContactModel contactModel,
        @Nullable File temporaryAvatarFile,
        @NonNull TriggerSource triggerSource
    ) {
        if (contactModel != null && temporaryAvatarFile != null) {
            if (this.fileService.writeUserDefinedProfilePicture(contactModel.getIdentity(), temporaryAvatarFile)) {
                if (triggerSource != TriggerSource.SYNC && multiDeviceManager.isMultiDeviceActive()) {
                    taskCreator.scheduleUserDefinedProfilePictureUpdate(contactModel.getIdentity());
                }
                return this.onUserDefinedProfilePictureSet(contactModel);
            }
        }
        return false;
    }

    @Override
    public boolean setUserDefinedProfilePicture(
        @NonNull String identity,
        @Nullable File temporaryAvatarFile,
        @NonNull TriggerSource triggerSource
    ) {
        ContactModel contactModel = getByIdentity(identity);
        return setUserDefinedProfilePicture(contactModel, temporaryAvatarFile, triggerSource);
    }

    @Override
    public boolean setUserDefinedProfilePicture(
        @NonNull final String identity,
        @Nullable byte[] avatar,
        @NonNull TriggerSource triggerSource
    ) {
        ContactModel contactModel = getByIdentity(identity);
        if (contactModel == null) {
            logger.error("Cannot set user defined profile for unknown identity {}", identity);
            return false;
        }

        if (avatar == null) {
            logger.error("Cannot set avatar that is null for identity {}", identity);
            return false;
        }

        try {
            fileService.writeUserDefinedProfilePicture(contactModel.getIdentity(), avatar);
        } catch (IOException e) {
            logger.error("Failed to write user defined profile picture");
            return false;
        }
        if (triggerSource != TriggerSource.SYNC && multiDeviceManager.isMultiDeviceActive()) {
            taskCreator.scheduleUserDefinedProfilePictureUpdate(contactModel.getIdentity());
        }
        return onUserDefinedProfilePictureSet(contactModel);
    }

    private boolean onUserDefinedProfilePictureSet(@NonNull final ContactModel contactModel) {
        if (this.userService.isMe(contactModel.getIdentity())) {
            logger.error("The users profile picture must not be set via contact service");
        } else {
            globalEventBuses.getContacts().emit(ContactEvent.ContactProfilePictureUpdated.javaCreate(contactModel.getIdentity()));
        }

        return true;
    }

    @Override
    public boolean removeUserDefinedProfilePicture(
        @NonNull final String identity,
        @NonNull TriggerSource triggerSource
    ) {
        if (userService.isMe(identity)) {
            logger.error("The user's profile picture cannot be removed using the contact service");
            return false;
        }

        if (this.fileService.removeUserDefinedProfilePicture(identity)) {
            if (triggerSource != TriggerSource.SYNC && multiDeviceManager.isMultiDeviceActive()) {
                taskCreator.scheduleUserDefinedProfilePictureUpdate(identity);
            }
            globalEventBuses.getContacts().emit(ContactEvent.ContactProfilePictureUpdated.javaCreate(identity));
            return true;
        }

        return false;
    }

    @Override
    @NonNull
    public ProfilePictureSharePolicy getProfilePictureSharePolicy() {
        ProfilePictureSharePolicy.Policy policy;

        switch (preferenceService.getProfilePicRelease()) {
            case PreferenceService.PROFILEPIC_RELEASE_EVERYONE:
                policy = ProfilePictureSharePolicy.Policy.EVERYONE;
                break;
            case PreferenceService.PROFILEPIC_RELEASE_ALLOW_LIST:
                policy = ProfilePictureSharePolicy.Policy.ALLOW_LIST;
                break;
            default:
                policy = ProfilePictureSharePolicy.Policy.NOBODY;
                break;
        }

        List<String> allowedIdentities = policy == ProfilePictureSharePolicy.Policy.ALLOW_LIST
            ? Arrays.asList(profilePictureRecipientsService.getAll())
            : Collections.emptyList();

        return new ProfilePictureSharePolicy(policy, allowedIdentities);
    }

    @Override
    public boolean isContactAllowedToReceiveProfilePicture(@NonNull String identity) {
        int profilePicRelease = preferenceService.getProfilePicRelease();
        return profilePicRelease == PreferenceService.PROFILEPIC_RELEASE_EVERYONE ||
            (profilePicRelease == PreferenceService.PROFILEPIC_RELEASE_ALLOW_LIST && profilePictureRecipientsService.has(identity));
    }

    @Override
    public boolean showIdentityTypeBadge(@Nullable ContactModel contactModel) {
        if (contactModel != null) {
            if (ConfigUtils.isWorkBuild()) {
                if (userService.isMe(contactModel.getIdentity())) {
                    return false;
                }
                return contactModel.getIdentityType() == IdentityType.REGULAR && !ContactUtil.isEchoEchoOrGatewayContact(contactModel);
            } else {
                return contactModel.getIdentityType() == IdentityType.WORK;
            }
        }
        return false;
    }

    @Override
    public boolean showIdentityTypeBadge(@NonNull ContactModelData contactModelData) {
        if (ConfigUtils.isWorkBuild()) {
            if (userService.isMe(contactModelData.identity)) {
                return false;
            }
            return contactModelData.identityType == IdentityType.REGULAR
                && !ContactUtil.isEchoEchoOrGatewayContact(contactModelData.identity);
        } else {
            return contactModelData.identityType == IdentityType.WORK;
        }
    }

    @Override
    public boolean showIdentityTypeBadge(@Nullable String identity) {
        return showIdentityTypeBadge(getByIdentity(identity));
    }

    /**
     * Get Android contact lookup key Uri in String representation to be used for Notification.Builder.addPerson()
     *
     * @param contactModel ContactModel to get Uri for
     * @return Uri of Android contact as a string or null if there's no linked contact or permission to access contacts has not been granted
     */
    @Override
    public @Nullable String getAndroidContactLookupUriString(ContactModel contactModel) {
        String contactLookupUri = null;
        if (ContextCompat.checkSelfPermission(ThreemaApplication.getAppContext(), Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            if (contactModel != null) {
                final String androidContactLookupKey = contactModel.getAndroidContactLookupKey();
                if (androidContactLookupKey != null) {
                    Uri lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, androidContactLookupKey);
                    if (lookupUri != null) {
                        contactLookupUri = lookupUri.toString();
                    }
                }
            }
        }
        return contactLookupUri;
    }

    @Override
    public void invalidateCache(@NonNull String identity) {
        synchronized (this.contactModelCache) {
            this.contactModelCache.remove(identity);
        }
    }

    @Override
    @WorkerThread
    public void resetReceiptsSettings() {
        List<ContactModel> contactModels = find(new Filter() {
            @Override
            public IdentityState[] states() {
                return new IdentityState[]{IdentityState.ACTIVE, IdentityState.INACTIVE};
            }

            @Override
            public Boolean fetchMissingFeatureLevel() {
                return null;
            }

            @Override
            public Boolean includeMyself() {
                return false;
            }

            @Override
            public Boolean includeHidden() {
                return true;
            }

            @Override
            public boolean onlyWithReceiptSettings() {
                return true;
            }
        });

        contactModels
            .stream()
            .map(contactModel -> contactModelRepository.getByIdentity(contactModel.getIdentity()))
            .forEach(contactModel -> {
                if (contactModel != null) {
                    contactModel.setReadReceiptPolicyFromLocal(ReadReceiptPolicy.DEFAULT);
                    contactModel.setTypingIndicatorPolicyFromLocal(TypingIndicatorPolicy.DEFAULT);
                }
            });
    }

    @Override
    @UiThread
    public void reportSpam(@NonNull final String identity, @Nullable Consumer<Void> onSuccess, @Nullable Consumer<String> onFailure) {
        new Thread(() -> {
            try {
                ch.threema.data.models.ContactModel spammerContactModel = contactModelRepository.getByIdentity(identity);
                if (spammerContactModel == null) {
                    logger.warn("No contact with identity {} found", identity);
                    return;
                }
                ContactModelData contactModelData = spammerContactModel.getData();
                if (contactModelData == null) {
                    logger.warn("Contact model data for identity {} is null", identity);
                    return;
                }

                apiConnector.reportJunk(identityStore, identity, contactModelData.nickname);

                spammerContactModel.setAcquaintanceLevelFromLocal(AcquaintanceLevel.GROUP_OR_DELETED);

                if (onSuccess != null) {
                    RuntimeUtil.runOnUiThread(() -> onSuccess.accept(null));
                }
            } catch (Exception e) {
                logger.error("Error reporting spam", e);
                if (onFailure != null) {
                    RuntimeUtil.runOnUiThread(() -> onFailure.accept(e.getMessage()));
                }
            }
        }).start();
    }

    @Nullable
    @Override
    public ForwardSecuritySessionState getForwardSecurityState(
        @NonNull ContactModel contactModel,
        @NonNull ActiveTaskCodec handle
    ) {
        if (!ThreemaFeature.canForwardSecurity(contactModel.getFeatureMask())) {
            return ForwardSecuritySessionState.unsupportedByRemote();
        }
        try {
            DHSession session = ServiceManager.require().getDhSessionStore()
                .getBestDHSession(
                    userService.getIdentity(),
                    contactModel.getIdentity(),
                    handle
                );
            if (session == null) {
                return ForwardSecuritySessionState.noSession();
            }
            DHSession.State dhState = session.getState();
            DHSession.DHVersions dhVersions = session.getCurrent4DHVersions();
            return ForwardSecuritySessionState.fromDHState(dhState, dhVersions);
        } catch (Exception e) {
            logger.error("Could not get forward security state", e);
            return null;
        }
    }

    @Override
    public void resetAllNotificationTriggerPolicyOverrideFromLocal() {
        contactModelRepository.getAll().stream().forEach(
            contactModel -> contactModel.setNotificationTriggerPolicyOverrideFromLocal(null)
        );
    }

    @Override
    @NonNull
    public Set<String> getRemovedContacts() {
        /*
            SELECT identity FROM contacts AS co WHERE acquaintanceLevel = 1 AND (
                NOT EXISTS (
                    SELECT 1 FROM group_member AS gm WHERE gm.identity = co.identity
                ) AND NOT EXISTS (
                    SELECT 1 FROM m_group AS g WHERE g.creatorIdentity = co.identity
                )
            );
         */
        final @NonNull String query = "SELECT " + ContactModel.COLUMN_IDENTITY + " FROM " + ContactModel.TABLE + " AS co WHERE "
            + ContactModel.COLUMN_ACQUAINTANCE_LEVEL + " = " + AcquaintanceLevel.GROUP_OR_DELETED.ordinal() + " AND ( "
            + "NOT EXISTS ("
            + " SELECT 1 FROM " + GroupMemberModel.TABLE + " AS gm WHERE"
            + " gm." + GroupMemberModel.COLUMN_IDENTITY + " = co." + ContactModel.COLUMN_IDENTITY
            + " ) AND NOT EXISTS ("
            + " SELECT 1 FROM " + GroupModelOld.TABLE + " AS g WHERE"
            + " g." + GroupModelOld.COLUMN_CREATOR_IDENTITY + " = co." + ContactModel.COLUMN_IDENTITY
            + " )"
            + ");";
        final @Nullable Cursor cursor = this.databaseProvider
            .getReadableDatabase()
            .rawQuery(query);
        if (cursor == null) {
            logger.error("Failed to query for deleted contacts");
            return new HashSet<>();
        }
        try (cursor) {
            final @NonNull Set<String> identities = new HashSet<>();
            while (cursor.moveToNext()) {
                identities.add(cursor.getString(cursor.getColumnIndexOrThrow(ContactModel.COLUMN_IDENTITY)));
            }
            return identities;
        } catch (Exception exception) {
            logger.error("Failed to query for deleted contacts", exception);
            return new HashSet<>();
        }
    }
}
