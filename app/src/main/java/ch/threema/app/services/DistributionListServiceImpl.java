package ch.threema.app.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.ImageView;

import com.bumptech.glide.RequestManager;

import org.slf4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.app.eventbus.GlobalEventBuses;
import ch.threema.app.eventbus.events.DistributionListEvent;
import ch.threema.app.glide.AvatarOptions;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.messagereceiver.DistributionListMessageReceiver;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.services.avatarcache.AvatarCacheService;
import ch.threema.app.utils.NameUtil;
import ch.threema.app.utils.ShortcutUtil;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

import ch.threema.data.datatypes.ContactNameFormat;
import ch.threema.data.datatypes.ConversationVisibility;
import ch.threema.data.datatypes.DistributionListConversationId;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.storage.DatabaseService;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.DistributionListMemberModel;
import ch.threema.storage.models.DistributionListModel;
import ch.threema.data.datatypes.IdColor;

public class DistributionListServiceImpl implements DistributionListService {
    private static final Logger logger = getThreemaLogger("DistributionListServiceImpl");

    private final Context context;
    private final AvatarCacheService avatarCacheService;
    private final DatabaseService databaseService;
    private final ContactService contactService;
    private final @NonNull ConversationTagService conversationTagService;
    private final @NonNull PreferenceService preferenceService;
    private final @NonNull GlobalEventBuses globalEventBuses;

    public DistributionListServiceImpl(
        Context context,
        AvatarCacheService avatarCacheService,
        DatabaseService databaseService,
        ContactService contactService,
        @NonNull ConversationTagService conversationTagService,
        @NonNull PreferenceService preferenceService,
        @NonNull GlobalEventBuses globalEventBuses
    ) {
        this.context = context;
        this.avatarCacheService = avatarCacheService;
        this.databaseService = databaseService;
        this.contactService = contactService;
        this.conversationTagService = conversationTagService;
        this.preferenceService = preferenceService;
        this.globalEventBuses = globalEventBuses;
    }

    @Override
    @Nullable
    public DistributionListModel getById(long id) {
        return this.databaseService.getDistributionListModelFactory().getById(id);
    }

    @NonNull
    public List<DistributionListModel> getByIds(@NonNull List<Long> ids) {
        return this.databaseService.getDistributionListModelFactory().getByIds(ids);
    }

    @Override
    public DistributionListModel createDistributionList(
        @Nullable String name,
        @NonNull String[] memberIdentities
    ) {
        return createDistributionList(name, memberIdentities, false);
    }

    @Override
    public DistributionListModel createDistributionList(
        @Nullable String name,
        @NonNull String[] memberIdentities,
        boolean isAdHocDistributionList
    ) {
        // Create group model in database
        final Instant now = Instant.now();
        final DistributionListModel distributionListModel = new DistributionListModel()
            .setName(name)
            .setCreatedAt(now)
            .setLastUpdate(now)
            .setAdHocDistributionList(isAdHocDistributionList);
        this.databaseService.getDistributionListModelFactory().create(
            distributionListModel
        );

        // Add members to distribution list
        for (String identity : memberIdentities) {
            this.addMemberToDistributionList(distributionListModel, identity);
        }

        // Notify event bus
        globalEventBuses.getDistributionLists().emit(new DistributionListEvent.NewDistributionList(distributionListModel));

        return distributionListModel;
    }

    @Override
    public DistributionListModel updateDistributionList(final DistributionListModel distributionListModel, String name, String[] memberIdentities) {
        distributionListModel.setName(name);

        //create
        this.databaseService.getDistributionListModelFactory().update(
            distributionListModel
        );

        if (this.removeMembers(distributionListModel)) {
            for (String identity : memberIdentities) {
                this.addMemberToDistributionList(distributionListModel, identity);
            }
        }

        globalEventBuses.getDistributionLists().emit(new DistributionListEvent.DistributionListUpdated(distributionListModel));
        return distributionListModel;
    }

    @Nullable
    @Override
    public Bitmap getAvatar(@Nullable Long distributionListId, @Nullable AvatarOptions options) {
        return avatarCacheService.getDistributionListAvatarLow(distributionListId);
    }

    @Override
    public void loadAvatarIntoImage(
        @NonNull Long distributionListId,
        @NonNull ImageView imageView,
        @NonNull AvatarOptions options,
        @NonNull RequestManager requestManager
    ) {
        avatarCacheService.loadDistributionListAvatarIntoImage(distributionListId, imageView, options, requestManager);
    }

    @Override
    public @ColorInt int getAvatarColor(@Nullable Long distributionListId) {
        if (distributionListId == null) {
            return IdColor.invalid().getThemedColor(context);
        }
        final @Nullable DistributionListModel distributionList = getById(distributionListId);
        if (distributionList != null) {
            return distributionList.getIdColor().getThemedColor(context);
        }
        return IdColor.invalid().getThemedColor(context);
    }

    @Override
    public boolean addMemberToDistributionList(DistributionListModel distributionListModel, String identity) {
        DistributionListMemberModel distributionListMemberModel = this.databaseService.getDistributionListMemberModelFactory().getByDistributionListIdAndIdentity(
            distributionListModel.getId(),
            identity
        );
        if (distributionListMemberModel == null) {
            distributionListMemberModel = new DistributionListMemberModel();
        }
        distributionListMemberModel
            .setDistributionListId(distributionListModel.getId())
            .setIdentity(identity)
            .setActive(true);

        if (distributionListMemberModel.getId() > 0) {
            this.databaseService.getDistributionListMemberModelFactory().update(
                distributionListMemberModel
            );
        } else {
            this.databaseService.getDistributionListMemberModelFactory().create(
                distributionListMemberModel
            );
        }
        return true;
    }

    @Override
    public boolean remove(final DistributionListModel distributionListModel) {
        // Obtain some services through service manager
        //
        // Note: We cannot put these services in the constructor due to circular dependencies.
        ServiceManager serviceManager = ServiceManager.get();
        if (serviceManager == null) {
            logger.error("Missing serviceManager, cannot remove distribution list");
            return false;
        }
        final ConversationService conversationService = serviceManager.getConversationService();

        // Remove distribution list members
        if (!this.removeMembers(distributionListModel)) {
            return false;
        }

        // Delete shortcuts
        final @NonNull DistributionListConversationId distributionListConversationId = new DistributionListConversationId(
            distributionListModel.getId()
        );
        ShortcutUtil.deleteShareTargetShortcut(distributionListConversationId);
        ShortcutUtil.deletePinnedShortcut(distributionListConversationId);

        // Remove conversation
        conversationService.removeFromCache(distributionListModel);

        // Remove conversation tags
        conversationTagService.removeAll(distributionListConversationId, TriggerSource.LOCAL);

        // F1Whisper (fork review M-09): mirror the tag cleanup — permanently deleting the
        // distribution list must also drop its chat-folder memberships, otherwise recreating a
        // chat with the same conversation UID silently restores old folders.
        serviceManager.getChatFolderService().onConversationDeleted(
            distributionListConversationId.toDatabaseValue()
        );

        // Delete distribution list fully from database
        this.databaseService.getDistributionListModelFactory().delete(distributionListModel);

        // Notify event bus
        globalEventBuses.getDistributionLists().emit(new DistributionListEvent.DistributionListRemoved(distributionListModel));

        return true;
    }

    private boolean removeMembers(DistributionListModel distributionListModel) {
        //remove all members first
        this.databaseService.getDistributionListMemberModelFactory().deleteByDistributionListId(
            distributionListModel.getId());

        return true;
    }

    @Override
    public boolean removeAll() {
        //remove all members first
        this.databaseService.getDistributionListMemberModelFactory().deleteAll();

        //...  messages
        this.databaseService.getDistributionListMessageModelFactory().deleteAll();

        //.. remove lists
        this.databaseService.getDistributionListModelFactory().deleteAll();

        return true;
    }

    @Override
    public String[] getDistributionListIdentities(DistributionListModel distributionListModel) {
        List<DistributionListMemberModel> memberModels = this.getDistributionListMembers(distributionListModel);
        if (memberModels != null) {
            String[] res = new String[memberModels.size()];
            for (int n = 0; n < res.length; n++) {
                res[n] = memberModels.get(n).getIdentity();
            }
            return res;
        }

        return null;
    }


    @Override
    public List<DistributionListMemberModel> getDistributionListMembers(DistributionListModel distributionListModel) {
        return this.databaseService.getDistributionListMemberModelFactory().getByDistributionListId(
            distributionListModel.getId()
        );
    }

    @Override
    public List<DistributionListModel> getAll() {
        return this.getAll(null);
    }

    @Override
    public List<DistributionListModel> getAll(DistributionListFilter filter) {
        return this.databaseService.getDistributionListModelFactory().filter(
            filter
        );
    }

    @Override
    public List<ContactModel> getMembers(@Nullable DistributionListModel distributionListModel) {
        List<ContactModel> contactModels = new ArrayList<>();
        if (distributionListModel != null) {
            for (DistributionListMemberModel distributionListMemberModel : this.getDistributionListMembers(distributionListModel)) {
                ContactModel contactModel = this.contactService.getByIdentity(distributionListMemberModel.getIdentity());
                if (contactModel != null) {
                    contactModels.add(contactModel);
                }
            }
        }
        return contactModels;
    }

    @NonNull
    @Override
    public String getMembersString(@Nullable DistributionListModel distributionListModel) {
        StringBuilder builder = new StringBuilder();
        final @NonNull ContactNameFormat contactNameFormat = preferenceService.getContactNameFormat();
        for (final ContactModel contactModel : getMembers(distributionListModel)) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(
                NameUtil.getContactDisplayNameOrNickname(
                    contactModel,
                    true,
                    contactNameFormat
                )
            );
        }
        return builder.toString();
    }

    @Override
    @Nullable
    public DistributionListMessageReceiver createReceiver(long distributionListId) {
        final @Nullable DistributionListModel distributionListModel = getById(distributionListId);
        if (distributionListModel != null) {
            return createReceiver(distributionListModel);
        } else {
            return null;
        }
    }

    @Override
    @NonNull
    public DistributionListMessageReceiver createReceiver(@NonNull DistributionListModel distributionListModel) {
        return new DistributionListMessageReceiver(
            this.databaseService,
            this.contactService,
            distributionListModel,
            this
        );
    }

    @Override
    public void archive(@NonNull DistributionListModel distributionListModel) {
        if (distributionListModel.isArchived()) {
            return;
        }

        setConversationVisibility(distributionListModel, ConversationVisibility.ARCHIVED);
    }

    @Override
    public void unarchive(@NonNull DistributionListModel distributionListModel) {
        if (!distributionListModel.isArchived()) {
            return;
        }

        setConversationVisibility(distributionListModel, ConversationVisibility.NORMAL);
    }

    @Override
    public void pin(@NonNull DistributionListModel distributionListModel) {
        if (distributionListModel.getConversationVisibility() == ConversationVisibility.PINNED) {
            return;
        }

        setConversationVisibility(distributionListModel, ConversationVisibility.PINNED);
    }

    @Override
    public void unpin(@NonNull DistributionListModel distributionListModel) {
        if (distributionListModel.getConversationVisibility() != ConversationVisibility.PINNED) {
            return;
        }

        setConversationVisibility(distributionListModel, ConversationVisibility.NORMAL);
    }

    @Override
    public void setConversationVisibility(@NonNull DistributionListModel distributionListModel, @NonNull ConversationVisibility conversationVisibility) {
        if (distributionListModel.getConversationVisibility() == conversationVisibility) {
            return;
        }

        distributionListModel.setConversationVisibility(conversationVisibility);
        save(distributionListModel);

        globalEventBuses.getDistributionLists().emit(new DistributionListEvent.DistributionListUpdated(distributionListModel));
    }

    @Override
    public void bumpLastUpdate(@NonNull DistributionListModel distributionListModel) {
        distributionListModel.setLastUpdate(Instant.now());
        save(distributionListModel);
        globalEventBuses.getDistributionLists().emit(new DistributionListEvent.DistributionListUpdated(distributionListModel));
    }

    private void save(DistributionListModel distributionListModel) {
        this.databaseService.getDistributionListModelFactory().createOrUpdate(
            distributionListModel
        );
    }
}
