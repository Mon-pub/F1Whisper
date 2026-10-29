package ch.threema.app.services;


import android.database.Cursor;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.slf4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.eventbus.GlobalEventBuses;
import ch.threema.app.eventbus.events.ConversationEvent;
import ch.threema.app.messagereceiver.ContactMessageReceiver;
import ch.threema.app.messagereceiver.DistributionListMessageReceiver;
import ch.threema.app.messagereceiver.GroupMessageReceiver;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.utils.ContactUtil;
import ch.threema.app.utils.MessageUtil;
import ch.threema.app.utils.TextUtil;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import static ch.threema.common.JavaCompat.isNullOrEmpty;

import ch.threema.data.datatypes.ContactNameFormat;
import ch.threema.data.datatypes.ContactConversationId;
import ch.threema.data.datatypes.ConversationVisibility;
import ch.threema.data.datatypes.DistributionListConversationId;
import ch.threema.data.models.ContactModelData;
import ch.threema.data.models.GroupModel;
import ch.threema.data.datatypes.GroupConversationId;
import ch.threema.data.models.GroupModelData;
import ch.threema.domain.models.IdentityState;
import ch.threema.data.datatypes.ConversationId;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.storage.DatabaseProvider;
import ch.threema.storage.DatabaseService;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.ConversationModel;
import ch.threema.storage.models.ConversationTag;
import ch.threema.storage.models.ConversationTagModel;
import ch.threema.storage.models.DistributionListMessageModel;
import ch.threema.storage.models.DistributionListModel;
import ch.threema.storage.models.group.GroupMessageModel;
import ch.threema.storage.models.group.GroupModelOld;
import ch.threema.storage.models.MessageModel;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.ReceiverModel;

public class ConversationServiceImpl implements ConversationService {
    private static final Logger logger = getThreemaLogger("ConversationServiceImpl");

    private final @NonNull List<ConversationModel> conversationCache;
    private final ConversationTagService conversationTagService;
    private final @NonNull DatabaseService databaseService;
    private final @NonNull DatabaseProvider databaseProvider;
    private final ContactService contactService;
    private final GroupService groupService;
    private final DistributionListService distributionListService;
    private final MessageService messageService;
    private final PreferenceService preferenceService;
    private final @NonNull ConversationCategoryService conversationCategoryService;
    private final BlockedIdentitiesService blockedIdentitiesService;
    private final @NonNull GlobalEventBuses globalEventBuses;
    private boolean initAllLoaded = false;

    static class ConversationResult {
        /**
         * The unique identifier of this conversation:
         * <p>
         * - Contacts: Identity
         * - Groups: DB primary key (id)
         * - Distribution lists: DB primary key (id)
         */
        public final @NonNull String identifier;

        /**
         * The message count in this conversation.
         */
        public final long messageCount;

        /**
         * Date of the last modification of this conversation.
         */
        public final @NonNull Instant lastUpdate;

        /**
         * The database ID of the latest message in this conversation.
         */
        public final @Nullable Integer latestMessageId;

        public final ConversationVisibility conversationVisibility;

        ConversationResult(
            @NonNull String identifier,
            long messageCount,
            @NonNull Instant lastUpdate,
            @Nullable Integer latestMessageId,
            @NonNull ConversationVisibility conversationVisibility
        ) {
            this.identifier = identifier;
            this.messageCount = messageCount;
            this.lastUpdate = lastUpdate;
            this.latestMessageId = latestMessageId;
            this.conversationVisibility = conversationVisibility;
        }
    }

    public ConversationServiceImpl(
        @NonNull CacheService cacheService,
        @NonNull DatabaseService databaseService,
        @NonNull DatabaseProvider databaseProvider,
        ContactService contactService,
        GroupService groupService,
        DistributionListService distributionListService,
        MessageService messageService,
        @NonNull ConversationCategoryService conversationCategoryService,
        @NonNull BlockedIdentitiesService blockedIdentitiesService,
        ConversationTagService conversationTagService,
        @NonNull PreferenceService preferenceService,
        @NonNull GlobalEventBuses globalEventBuses
    ) {
        this.databaseService = databaseService;
        this.databaseProvider = databaseProvider;
        this.contactService = contactService;
        this.groupService = groupService;
        this.distributionListService = distributionListService;
        this.messageService = messageService;
        this.conversationCategoryService = conversationCategoryService;
        this.blockedIdentitiesService = blockedIdentitiesService;
        this.conversationCache = cacheService.getConversationModelCache();
        this.conversationTagService = conversationTagService;
        this.preferenceService = preferenceService;
        this.globalEventBuses = globalEventBuses;
    }

    /**
     * Remove the conversation from the cache.
     * <p>
     * The event bus will be notified
     * <p>
     * Note: Because we can't guarantee that object IDs are always identical, we search for
     * all matching conversations (by {@code ConversationId}) and remove them all.
     */
    private void removeFromCache(@NonNull ConversationModel conversationModel) {
        synchronized (this.conversationCache) {
            final List<ConversationModel> conversationModels = conversationCache
                .stream()
                .filter(model -> model.getId().equals(conversationModel.getId()))
                .collect(Collectors.toList());
            this.conversationCache.removeAll(conversationModels);
        }

        globalEventBuses.getConversations().emit(new ConversationEvent.ConversationRemoved(conversationModel));
    }

    /**
     * Add the given {@code conversationModel} to the cache if it is not yet present.
     * Replace the old cached model with the given {@code conversationModel} if a model with
     * the same {@link ConversationModel#getId()} exists in cache.
     */
    private void cache(@NonNull ConversationModel conversationModel) {
        // If it exists in the cache, replace it
        final @NonNull ListIterator<ConversationModel> cacheIterator = conversationCache.listIterator();
        while (cacheIterator.hasNext()) {
            final @NonNull ConversationModel cachedModel = cacheIterator.next();
            if (cachedModel.getId().equals(conversationModel.getId())) {
                cacheIterator.set(conversationModel);
                return;
            }
        }
        // Add it to the cache, because it does not exist yet
        conversationCache.add(conversationModel);
    }

    @NonNull
    @Override
    public synchronized List<ConversationModel> getAll(boolean forceReloadFromDatabase) {
        return this.getAll(forceReloadFromDatabase, null);
    }

    @Override
    public synchronized @NonNull List<ConversationModel> getAll(
        boolean forceReloadFromDatabase,
        final @Nullable Filter filter
    ) {
        logger.debug("getAll forceReloadFromDatabase = {}", forceReloadFromDatabase);
        synchronized (this.conversationCache) {
            if (forceReloadFromDatabase || !this.initAllLoaded) {
                this.conversationCache.clear();
            }
            if (this.conversationCache.isEmpty()) {
                logger.debug("start selecting");
                for (ConversationModelParser<?, ?, ?> parser : new ConversationModelParser<?, ?, ?>[]{
                    new ContactConversationModelParser(),
                    new GroupConversationModelParser(),
                    new DistributionListConversationModelParser()
                }) {
                    parser.processAll();
                }

                this.updateTags();

                logger.debug("selection finished");
                this.initAllLoaded = true;
            }

            this.sort();

            // F1Whisper: belt-and-suspenders — enforce disappearing expiry on the latest message
            // of each conversation so no overdue message survives a conversation-list refresh.
            //
            // This MUST collect first and enforce afterwards. Do NOT "simplify" it back into a
            // `for (ConversationModel conv : this.conversationCache)` loop — that is the exact
            // shape that crash-looped 6.4.3o-37 with a ConcurrentModificationException at launch.
            //
            // Enforcing expiry DELETES a message, and listener dispatch is SYNCHRONOUS on this very
            // thread (the event dispatch), so the handler re-enters
            // this service and structurally modifies the cache before the enforce call returns:
            //   delete path: MessageService.remove -> GlobalListeners.onRemoved
            //     -> #refreshWithDeletedMessage (:506) -> ConversationModelParser#messageDeleted
            //     (:1179) -> #sort (:375) -> Collections.sort -> modCount++
            //   repair path: MessageService.save -> GlobalListeners.onModified -> #refresh (:397)
            //     -> ConversationModelParser#refresh (:1046) -> #sort (:375) and, on a cache miss,
            //     #cache (:176), which also changes the size
            // Neither this synchronized method nor the enclosing synchronized(conversationCache)
            // helps: the mutation arrives on the SAME thread and intrinsic locks are reentrant.
            // Nor would a try/catch — the exception is thrown afterwards by our own iterator, not
            // by #enforceIfExpired. See the ExpirySweep Javadoc for the full reasoning.
            //
            // F1Whisper (eleventh fork review, F11-07): the sweep extracts the CONVERSATION (a pure
            // read; a null latestMessage skips it) and the enforce phase runs
            // #sweepConversationPreview, which nulls the cached latestMessage the moment the
            // synchronous decision says the message is gone. Without that, the list returned below
            // kept showing the expired message as the conversation preview for as long as the
            // worker deletion was delayed or failing. The removal itself stays on the worker.
            ExpirySweep.collectThenEnforce(
                this.conversationCache,
                conv -> conv.latestMessage != null ? conv : null,
                DisappearingMessageService::sweepConversationPreview
            );

            if (filter != null) {
                boolean filteringApplied = false;
                Stream<ConversationModel> filtered = this.conversationCache.stream();
                if (filter.onlyUnread()) {
                    logger.debug("filter unread");
                    filteringApplied = true;
                    filtered = filtered.filter(ConversationModel::hasUnreadMessage);
                }

                if (filter.noDistributionLists()) {
                    logger.debug("filter distribution lists");
                    filteringApplied = true;
                    filtered = filtered.filter(conversationModel -> !conversationModel.isDistributionListConversation());
                }

                if (filter.noHiddenConversations()) {
                    logger.debug("filter hidden lists");
                    filteringApplied = true;
                    filtered = filtered.filter(conversationModel ->
                        !conversationCategoryService.isMarkedAsPrivate(conversationModel.messageReceiver.getConversationId())
                    );
                }

                if (filter.noInvalid()) {
                    logger.debug("filter chats with revoked contacts / left group that cannot receive messages");
                    filteringApplied = true;
                    filtered = filtered
                        .filter(conversationModel -> {
                            if (conversationModel.isContactConversation()) {
                                return conversationModel.getContact() != null && conversationModel.getContact().getState() != IdentityState.INVALID;
                            } else if (conversationModel.isGroupConversation()) {
                                return conversationModel.getGroup() != null && groupService.isGroupMember(conversationModel.getGroup());
                            }
                            return true;
                        });
                }

                if (filter.onlyPersonal()) {
                    logger.debug("filter non-personal chats such as channels/broadcasts or blocked chats");
                    filteringApplied = true;
                    filtered = filtered
                        .filter(conversationModel -> {
                            if (conversationModel.isContactConversation()) {
                                return !ContactUtil.isEchoEchoOrGatewayContact(conversationModel.getContact()) &&
                                    !blockedIdentitiesService.isBlocked(conversationModel.getContact().getIdentity());
                            }
                            return true;
                        });
                }

                if (!isNullOrEmpty(filter.filterQuery())) {
                    logger.debug("filter query");
                    filteringApplied = true;
                    final @NonNull ContactNameFormat contactNameFormat = preferenceService.getContactNameFormat();
                    filtered = filtered
                        .filter(conversationModel -> TextUtil.matchesQueryDiacriticInsensitive(
                            conversationModel.messageReceiver.getDisplayName(contactNameFormat),
                            filter.filterQuery()
                        ));
                }

                if (filteringApplied) {
                    // if any filtering was applied, we return here, but we must never leak conversationCache itself
                    return filtered.collect(Collectors.toList());
                }
            }

            return new ArrayList<>(conversationCache);
        }
    }

    /**
     * Return the model matching the passed {@code conversationId} or null if it does not exist in cache or database.
     *
     * @see ConversationModelParser#get
     */
    @Override
    public @Nullable ConversationModel get(@NonNull ConversationId conversationId) {
        if (conversationId instanceof ContactConversationId) {
            return new ContactConversationModelParser().get(((ContactConversationId) conversationId).identity);
        } else if (conversationId instanceof GroupConversationId) {
            return new GroupConversationModelParser().get((int) (((GroupConversationId) conversationId).groupDatabaseId));
        } else if (conversationId instanceof DistributionListConversationId) {
            return new DistributionListConversationModelParser().get(((DistributionListConversationId) conversationId).distributionListId);
        } else {
            return null;
        }
    }

    @Override
    @Nullable
    public ConversationModel getCached(@NonNull ConversationId conversationId) {
        return conversationCache
            .stream()
            .filter(cached -> cached.getId().equals(conversationId))
            .findFirst()
            .orElse(null);
    }

    @Override
    public @NonNull List<ConversationModel> getArchived() {
        return getArchived(null);
    }

    @Override
    public @NonNull List<ConversationModel> getArchived(@Nullable String searchQuery) {
        List<ConversationModel> conversationModels = new ArrayList<>();

        for (ConversationModelParser parser : new ConversationModelParser[]{
            new ContactConversationModelParser(),
            new GroupConversationModelParser(),
            new DistributionListConversationModelParser()
        }) {
            parser.processArchived(conversationModels, searchQuery);
        }

        Collections.sort(
            conversationModels,
            (c1, c2) -> c2.getSortDate().compareTo(c1.getSortDate())
        );

        return conversationModels;
    }

    @Override
    public long countArchived(@Nullable String searchQuery, boolean excludePrivateConversations) {
        return new ContactConversationModelParser().countArchived(searchQuery, excludePrivateConversations) +
            new GroupConversationModelParser().countArchived(searchQuery, excludePrivateConversations) +
            new DistributionListConversationModelParser().countArchived(searchQuery, excludePrivateConversations);
    }

    @Override
    public void updateTags() {
        final Set<ConversationId> unreadTaggedConversations = new HashSet<>();

        for (ConversationTagModel tagModel : conversationTagService.getAll()) {
            if (ConversationTag.MARKED_AS_UNREAD.value.equals(tagModel.getTag())) {
                unreadTaggedConversations.add(tagModel.getConversationId());
            }
        }

        synchronized (conversationCache) {
            for (ConversationModel conversationModel : conversationCache) {
                conversationModel.isUnreadTagged = unreadTaggedConversations.contains(conversationModel.getId());
            }
        }
    }

    @Override
    public void sort() {
        synchronized (this.conversationCache) {
            // Sort conversations in cache
            this.conversationCache.sort((c1, c2) -> {
                // Sorting: Pinned chats are at the top. Otherwise, sort by getSortDate.
                boolean conversation1pinned = c1.conversationVisibility == ConversationVisibility.PINNED;
                boolean conversation2pinned = c2.conversationVisibility == ConversationVisibility.PINNED;
                if (conversation1pinned == conversation2pinned) {
                    return c2.getSortDate().compareTo(c1.getSortDate());
                }
                return conversation2pinned ? 1 : -1;
            });

            // Set new position (field is only used by the web-client)
            int position = 0;
            for (ConversationModel conversationModel : this.conversationCache) {
                conversationModel.setPosition(position++);
            }
        }
    }

    @Override
    public synchronized ConversationModel get(AbstractMessageModel modifiedMessageModel) {
        ConversationModelParser parser = createParser(modifiedMessageModel);
        if (parser != null) {
            return parser.get(parser.getIdentifier(modifiedMessageModel));
        }
        return null;
    }

    @Override
    public synchronized void refreshForMessage(AbstractMessageModel messageModel, boolean isNewMessage) {
        ConversationModelParser parser = this.createParser(messageModel);
        if (parser != null && messageModel != null) {
            parser.refreshForMessage(messageModel, isNewMessage);
        }
    }

    @Override
    public synchronized void markConversationAsRead(@NonNull AbstractMessageModel messageModel) {
        ConversationModelParser parser = this.createParser(messageModel);
        if (parser != null) {
            parser.markConversationAsRead(messageModel);
        }
    }

    @Override
    public void markConversationAsRead(@NonNull MessageReceiver<?> messageReceiver) {
        if (messageReceiver instanceof GroupMessageReceiver) {
            new GroupConversationModelParser().markConversationAsRead(
                ((GroupMessageReceiver) messageReceiver).getGroup()
            );
        } else if (messageReceiver instanceof DistributionListMessageReceiver) {
            new DistributionListConversationModelParser().markConversationAsRead(
                ((DistributionListMessageReceiver) messageReceiver).getDistributionList()
            );
        } else if (messageReceiver instanceof ContactMessageReceiver) {
            ch.threema.data.models.ContactModel contactModel = ((ContactMessageReceiver) messageReceiver).getContactModel();
            if (contactModel != null) {
                new ContactConversationModelParser().markConversationAsRead(contactModel);
            } else {
                logger.error("Could not mark conversation as read because the contact model is null");
            }
        }
    }

    @Override
    public synchronized void updateContactConversation(@NonNull String identity) {
        synchronized (conversationCache) {
            for (int i = 0; i < conversationCache.size(); i++) {
                ConversationModel conversationModel = conversationCache.get(i);
                if (conversationModel.isContactConversation() && identity.equals(conversationModel.getContact().getIdentity())) {
                    ContactConversationModelParser conversationModelParser = new ContactConversationModelParser();
                    final List<ConversationResult> result = conversationModelParser.select(identity);
                    if (result.isEmpty() || result.get(0) == null) {
                        logger.warn("No result for updating identity {}", identity);
                        return;
                    }
                    ConversationModel updatedModel = conversationModelParser.parseResult(result.get(0), conversationModel, false);
                    if (updatedModel != null) {
                        updatedModel.conversationVisibility = conversationModel.conversationVisibility;
                        updatedModel.isUnreadTagged = conversationModel.isUnreadTagged;
                    }

                    conversationCache.set(i, updatedModel);
                    break;
                }
            }
        }
    }

    @Override
    public synchronized ConversationModel refresh(@Nullable ch.threema.data.models.ContactModel contactModel) {
        if (contactModel == null) {
            return null;
        }
        return new ContactConversationModelParser()
            .refresh(contactModel);
    }

    @Override
    public synchronized ConversationModel refresh(GroupModelOld groupModel) {
        return new GroupConversationModelParser()
            .refresh(groupModel);
    }

    @Override
    public synchronized ConversationModel refresh(DistributionListModel distributionListModel) {
        return new DistributionListConversationModelParser()
            .refresh(distributionListModel);
    }

    @Override
    public synchronized ConversationModel refresh(@NonNull MessageReceiver receiver) {
        switch (receiver.getType()) {
            case MessageReceiver.Type_CONTACT:
                return this.refresh(((ContactMessageReceiver) receiver).getContactModel());
            case MessageReceiver.Type_GROUP:
                return this.refresh(((GroupMessageReceiver) receiver).getGroup());
            case MessageReceiver.Type_DISTRIBUTION_LIST:
                return this.refresh(((DistributionListMessageReceiver) receiver).getDistributionList());
            default:
                throw new IllegalStateException("Got MessageReceiver with invalid receiver type!");
        }
    }

    @Override
    public synchronized void refreshWithDeletedMessage(AbstractMessageModel modifiedMessageModel) {
        ConversationModelParser parser = this.createParser(modifiedMessageModel);
        if (parser != null) {
            parser.messageDeleted(modifiedMessageModel);
        }
    }

    @Override
    public synchronized void archive(@NonNull ConversationModel conversationModel, @NonNull TriggerSource triggerSource) {
        this.conversationTagService.removeAll(conversationModel.getId(), triggerSource);

        conversationModel.setUnreadCount(0);
        conversationModel.conversationVisibility = ConversationVisibility.ARCHIVED;

        if (conversationModel.isContactConversation()) {
            ch.threema.data.models.ContactModel contactModel = conversationModel.getContactModel();
            if (contactModel == null) {
                logger.error("Contact model is null; cannot archive it");
                return;
            }
            if (triggerSource == TriggerSource.SYNC) {
                contactModel.setConversationVisibilityFromSync(ConversationVisibility.ARCHIVED);
            } else {
                contactModel.setConversationVisibilityFromLocalOrRemote(ConversationVisibility.ARCHIVED);
            }
        } else if (conversationModel.isGroupConversation()) {
            final @Nullable GroupModel groupModel = conversationModel.getGroupModel();
            if (groupModel == null) {
                logger.error("Cannot archive group conversation where group is null");
                return;
            }
            switch (triggerSource) {
                case LOCAL:
                case REMOTE:
                    groupModel.setConversationVisibilityFromLocalOrRemote(ConversationVisibility.ARCHIVED);
                    break;
                case SYNC:
                    groupModel.setConversationVisibilityFromSync(ConversationVisibility.ARCHIVED);
                    break;
            }
        } else if (conversationModel.isDistributionListConversation()) {
            //noinspection DataFlowIssue
            distributionListService.archive(conversationModel.getDistributionList());
        }

        // TODO(ANDR-4175):  Do not remove freshly archived conversation from cache
        this.removeFromCache(conversationModel);

        globalEventBuses.getConversations().emit(new ConversationEvent.ConversationArchived(conversationModel));
    }

    @Override
    public void unarchive(@NonNull List<ConversationModel> conversationModels, @NonNull TriggerSource triggerSource) {
        for (ConversationModel conversationModel : conversationModels) {
            if (conversationModel.isContactConversation()) {
                final @Nullable ch.threema.data.models.ContactModel contactModel = conversationModel.getContactModel();
                if (contactModel == null) {
                    logger.error("Contact model cannot be null while un-archiving contact conversation");
                    return;
                }
                contactService.unarchive(contactModel.getIdentity(), triggerSource);
            } else if (conversationModel.isGroupConversation()) {
                final @Nullable GroupModel groupModel = conversationModel.getGroupModel();
                if (groupModel == null) {
                    logger.error("Group model cannot be null while un-archiving group conversation");
                    return;
                }
                groupService.unarchive(
                    groupModel.getDatabaseId(),
                    triggerSource
                );
            } else if (conversationModel.isDistributionListConversation()) {
                final @Nullable DistributionListModel distributionListModel = conversationModel.getDistributionList();
                if (distributionListModel == null) {
                    logger.error("Distribution list model cannot be null while un-archiving distribution list conversation");
                    return;
                }
                distributionListService.unarchive(conversationModel.getDistributionList());
            }

            // TODO(ANDR-4175): They should emit ConversationUpdated rather than NewConversation
            // Note: Don't call the conversation event bus (NewConversation) here, that will be handled
            // already by the save() call in the contact/group/distributionlist-service.
        }
    }

    @WorkerThread
    @Override
    public void unarchiveByConversationIds(@NonNull List<ConversationId> conversationIds, @NonNull TriggerSource triggerSource) {
        // Unarchive all contact- and group-conversations
        for (ConversationId conversationId : conversationIds) {
            if (conversationId instanceof ContactConversationId) {
                contactService.unarchive(((ContactConversationId) conversationId).identity, triggerSource);
            } else if (conversationId instanceof GroupConversationId) {
                groupService.unarchive(((GroupConversationId) conversationId).groupDatabaseId, triggerSource);
            }
        }
        // Unarchive all distribution lists
        final @NonNull List<Long> distributionListIds = conversationIds
            .stream()
            .filter(conversationId -> conversationId instanceof DistributionListConversationId)
            .map(conversationId -> ((DistributionListConversationId) conversationId).distributionListId)
            .toList();
        if (!distributionListIds.isEmpty()) {
            final @NonNull List<DistributionListModel> distributionListModels = distributionListService.getByIds(distributionListIds);
            for (DistributionListModel distributionListModel : distributionListModels) {
                distributionListService.unarchive(distributionListModel);
            }
        }

        // TODO(ANDR-4175): They should emit ConversationUpdated rather than NewConversation
        // Note: Don't call the conversation event bus (NewConversation) here, that will be handled
        // already by the save() call in the contact/group/distributionlist-service.
    }

    @Override
    public void tag(@NonNull ConversationModel conversationModel, @NonNull ConversationTag conversationTag, @NonNull TriggerSource triggerSource) {
        final boolean tagAdded = conversationTagService.addTag(
            conversationModel.getId(),
            conversationTag,
            triggerSource
        );
        if (!tagAdded) {
            return;
        }
        if (conversationTag == ConversationTag.MARKED_AS_UNREAD) {
            conversationModel.isUnreadTagged = true;
        }
        cache(conversationModel);
        fireOnUpdatedConversation(conversationModel);
    }

    @Override
    public void untag(@NonNull ConversationModel conversationModel, @NonNull ConversationTag conversationTag, @NonNull TriggerSource triggerSource) {
        final boolean tagRemoved = conversationTagService.removeTag(
            conversationModel.getId(),
            conversationTag,
            triggerSource
        );
        if (!tagRemoved) {
            return;
        }
        if (conversationTag == ConversationTag.MARKED_AS_UNREAD) {
            conversationModel.isUnreadTagged = false;
        }
        cache(conversationModel);
        fireOnUpdatedConversation(conversationModel);
    }

    @Override
    public synchronized int empty(@NonNull MessageReceiver receiver) {
        // First refresh the receiver. Otherwise, it is possible that the conversation is null as it
        // does not yet exist (or is just not yet loaded) and then the chat won't be emptied.
        ConversationModel model = refresh(receiver);
        if (model != null) {
            return this.empty(model, true);
        } else {
            logger.error("Could not empty conversation as conversation model is null");
            return 0;
        }
    }

    @Override
    public synchronized int empty(@NonNull final ConversationModel conversation, boolean silentMessageUpdate) {
        // Remove all messages
        MessageReceiver<?> receiver = conversation.messageReceiver;
        final List<AbstractMessageModel> messages = this.messageService.getMessagesForReceiver(receiver);
        logger.info("Empty conversation with {} messages for {}", messages.size(), receiver.getConversationId());
        for (AbstractMessageModel m : messages) {
            this.messageService.remove(m, silentMessageUpdate);
        }

        this.conversationTagService.removeTag(
            conversation.getId(),
            ConversationTag.MARKED_AS_UNREAD,
            TriggerSource.LOCAL
        );

        // Update conversation
        conversation.latestMessage = null;
        conversation.messageCount = 0;
        conversation.setUnreadCount(0);
        fireOnUpdatedConversation(conversation);

        // Return number of removed messages
        return messages.size();
    }

    @Override
    public synchronized int empty(@NonNull String identity) {
        var parser = new ContactConversationModelParser();
        final ConversationModel cachedConversationModel = parser.getCached(identity);
        if (cachedConversationModel != null) {
            return this.empty(cachedConversationModel, true);
        }
        var conversationModel = parser.getSelected(identity);
        if (conversationModel != null) {
            return this.empty(conversationModel, true);
        }
        logger.warn("Contact conversation model is null, cannot empty");
        return 0;
    }

    @Override
    public synchronized int empty(@NonNull DistributionListModel distributionListModel) {
        var parser = new DistributionListConversationModelParser();
        final ConversationModel cachedConversationModel = parser.getCached(distributionListModel.getId());
        if (cachedConversationModel != null) {
            return this.empty(cachedConversationModel, true);
        }
        var conversationModel = parser.getSelected(distributionListModel.getId());
        if (conversationModel != null) {
            return this.empty(conversationModel, true);
        }
        logger.warn("DistributionList conversation model is null, cannot empty");
        return 0;
    }

    @Override
    public synchronized int delete(@NonNull String identity) {
        // Empty chat (if it isn't already empty)
        final int removedCount = this.empty(identity);

        // Clear lastUpdate
        this.contactService.clearLastUpdate(identity);

        // Remove from cache and notify the event bus
        final ConversationModel conversationModel = new ContactConversationModelParser().getCached(identity);
        if (conversationModel != null) {
            // Remove tags
            this.conversationTagService.removeAll(conversationModel.getId(), TriggerSource.LOCAL);
            this.removeFromCache(conversationModel);

            globalEventBuses.getConversations().emit(new ConversationEvent.ConversationDeleted(conversationModel));
        }

        // F1Whisper (fork review M-09): mirror the tag cleanup — permanently deleting the contact
        // conversation must also drop its chat-folder memberships, otherwise recreating a chat with
        // the same conversation UID silently restores old folders. The UID is deterministic, so this
        // runs even when the model was not cached. Hooked on the true deletion funnel only, NOT in
        // removeFromCache (which the archive path also uses — archiving keeps memberships).
        final ServiceManager serviceManager = ServiceManager.get();
        if (serviceManager != null) {
            serviceManager.getChatFolderService().onConversationDeleted(
                new ContactConversationId(identity).toDatabaseValue()
            );
        }

        return removedCount;
    }

    @Override
    public synchronized void removeFromCache(@NonNull GroupModelOld groupModel) {
        // Remove from cache and notify the event bus
        final ConversationModel conversationModel = new GroupConversationModelParser().getCached(groupModel.getId());
        if (conversationModel != null) {
            this.removeFromCache(conversationModel);
        }
    }

    @Override
    public synchronized void removeFromCache(@NonNull DistributionListModel distributionListModel) {
        // Remove from cache and notify the event bus
        final ConversationModel conversationModel = new DistributionListConversationModelParser().getCached(distributionListModel.getId());
        if (conversationModel != null) {
            this.removeFromCache(conversationModel);
        }
    }

    @Override
    public synchronized boolean reset() {
        synchronized (this.conversationCache) {
            this.conversationCache.clear();
            logger.debug("Conversation cache reset");
            this.initAllLoaded = false;
        }
        return true;
    }

    @Override
    public boolean hasConversations() {
        synchronized (this.conversationCache) {
            if (!this.conversationCache.isEmpty()) {
                return true;
            }
        }

        long count = this.databaseService.getDistributionListMessageModelFactory().count();
        if (count > 0) {
            return true;
        }

        count = this.databaseService.getMessageModelFactory().count();
        if (count > 0) {
            return true;
        }

        count = this.databaseService.getGroupMessageModelFactory().count();
        return count > 0;
    }

    private void fireOnNewConversation(@NonNull ConversationModel conversationModel) {
        globalEventBuses.getConversations().emit(new ConversationEvent.NewConversation(conversationModel));
    }

    private void fireOnUpdatedConversation(@NonNull ConversationModel conversationModel) {
        globalEventBuses.getConversations().emit(new ConversationEvent.ConversationUpdated(conversationModel));
    }

    private ConversationModelParser createParser(AbstractMessageModel m) {
        if (m instanceof GroupMessageModel) {
            return new GroupConversationModelParser();
        } else if (m instanceof DistributionListMessageModel) {
            return new DistributionListConversationModelParser();
        } else if (m instanceof MessageModel) {
            return new ContactConversationModelParser();
        }
        return null;
    }

    private abstract class ConversationModelParser<I, M extends AbstractMessageModel, R> {
        /**
         * Return whether the specified identifier belongs to the specified conversation.
         */
        public abstract boolean belongsTo(ConversationModel conversationModel, I identifier);

        /**
         * Create or update (and return) a {@link ConversationModel} from the specified
         * {@link ConversationResult}.
         *
         * @param result            The conversation result
         * @param conversationModel If not null, an existing conversation model to be updated
         * @param addToCache        Whether to add the conversation model to the cache
         * @return The created or updated conversation model, or null if something went wrong
         */
        public abstract @Nullable ConversationModel parseResult(
            @NonNull ConversationResult result,
            @Nullable ConversationModel conversationModel,
            boolean addToCache
        );

        /**
         * Query the database for conversation results matching the specified identifier.
         */
        public abstract @NonNull List<ConversationResult> select(@NonNull I identifier);

        /**
         * Query the database for all conversation results.
         */
        public abstract @NonNull List<ConversationResult> selectAll(boolean archived);

        /**
         * Return the conversation identifier for the specified message model.
         */
        protected abstract I getIdentifier(M messageModel);

        /**
         * Return the conversation identifier for the specified parent object.
         */
        protected abstract I getIdentifier(R receiverModel);

        /**
         * Get the last update flag from the receiver model
         */
        @Nullable
        protected abstract Instant getLastUpdate(@Nullable R receiverModel);

        /**
         * Return the cached conversation for the specified {@param messageModel}.
         */
        public final @Nullable ConversationModel getCached(final @NonNull M messageModel) {
            return this.getCached(this.getIdentifier(messageModel));
        }

        /**
         * Return the cached conversation for the specified {@param identifier}.
         */
        protected final @Nullable ConversationModel getCached(final @Nullable I identifier) {
            if (identifier == null) {
                return null;
            }
            synchronized (conversationCache) {
                return conversationCache.stream()
                    .filter(conversationModel -> belongsTo(conversationModel, identifier))
                    .findFirst()
                    .orElse(null);
            }
        }

        /**
         * Return the model from the cache matching the {@code identifier}.
         * On a cache-miss try to find it in database.
         */
        @Nullable
        protected final ConversationModel get(final @NonNull I identifier) {
            @Nullable ConversationModel model = getCached(identifier);
            if (model == null) {
                model = getSelected(identifier);
            }
            return model;
        }

        public final void processAll() {
            List<ConversationResult> res = this.selectAll(false);
            for (ConversationResult r : res) {
                this.parseResult(r, null, true);
            }
        }

        public final void processArchived(List<ConversationModel> conversationModels, @Nullable String searchQuery) {
            final List<ConversationResult> conversationResults = this.selectAll(true);

            if (!isNullOrEmpty(searchQuery)) {
                final @NonNull ContactNameFormat contactNameFormat = preferenceService.getContactNameFormat();
                for (ConversationResult conversationResult : conversationResults) {
                    final @Nullable ConversationModel conversationModel = this.parseResult(conversationResult, null, false);
                    if (
                        conversationModel != null &&
                            TextUtil.matchesQueryDiacriticInsensitive(
                                conversationModel.messageReceiver.getDisplayName(contactNameFormat),
                                searchQuery
                            )
                    ) {
                        conversationModels.add(conversationModel);
                    }
                }
            } else {
                for (ConversationResult conversationResult : conversationResults) {
                    final @Nullable ConversationModel conversationModel = this.parseResult(conversationResult, null, false);
                    if (conversationModel != null) {
                        conversationModels.add(conversationModel);
                    }
                }
            }
        }

        public final long countArchived(@Nullable String searchQuery, boolean excludePrivateConversations) {
            @NonNull Stream<ConversationModel> conversationModels = selectAll(true)
                .stream()
                .map(
                    conversationResult -> parseResult(conversationResult, null, false)
                )
                .filter(Objects::nonNull);
            if (excludePrivateConversations) {
                conversationModels = conversationModels.filter(
                    conversationModel -> !conversationCategoryService.isMarkedAsPrivate(conversationModel.getId())
                );
            }
            if (!isNullOrEmpty(searchQuery)) {
                final @NonNull ContactNameFormat contactNameFormat = preferenceService.getContactNameFormat();
                conversationModels = conversationModels.filter(
                    conversationModel -> TextUtil.matchesQueryDiacriticInsensitive(
                        conversationModel.messageReceiver.getDisplayName(contactNameFormat),
                        searchQuery
                    )
                );
            }
            return conversationModels.count();
        }

        @Nullable
        public final ConversationModel getSelected(final I identifier) {
            final List<ConversationResult> results = this.select(identifier);
            if (!results.isEmpty()) {
                return this.parseResult(results.get(0), null, true);
            }
            return null;
        }

        /**
         * Refresh the conversation data based on an updated receiver model (i.e. a contact,
         * a group or a distribution list).
         * <p>
         * Examples:
         * <p>
         * - When a contact name changes, re-calculate the distribution list name.
         * - When the "lastUpdateAt" timestamp of a group changes, update the conversation as well.
         */
        @Nullable
        public final ConversationModel refresh(R receiverModel) {
            final I identifier = this.getIdentifier(receiverModel);
            ConversationModel model = this.getCached(identifier);

            boolean newConversationModel = false;
            if (model == null) {
                newConversationModel = true;
                model = this.getSelected(identifier);
                //resort
                sort();
            } else {

                // Refresh lastUpdate
                model.lastUpdate = getLastUpdate(receiverModel);

                if (model.isGroupConversation() && receiverModel instanceof GroupModelOld && model.getGroup() != null) {
                    // Refresh notificationTriggerPolicyOverride
                    model.getGroup().setNotificationTriggerPolicyOverride(
                        ((GroupModelOld) receiverModel).getNotificationTriggerPolicyOverride()
                    );
                    GroupModel groupModel = model.getGroupModel();
                    GroupModelData groupModelData = groupModel != null ? groupModel.getData() : null;
                    if (groupModelData != null) {
                        model.conversationVisibility = groupModelData.conversationVisibility;
                    }
                } else if (model.isContactConversation() && receiverModel instanceof ch.threema.data.models.ContactModel && model.getContact() != null) {
                    // Refresh notificationTriggerPolicyOverride
                    final @NonNull ch.threema.data.models.ContactModel contactModel = ((ch.threema.data.models.ContactModel) receiverModel);
                    ContactModelData contactModelData = contactModel.getData();
                    if (contactModelData != null) {
                        model.getContact().setNotificationTriggerPolicyOverride(
                            contactModelData.notificationTriggerPolicyOverride
                        );

                        model.conversationVisibility = contactModelData.conversationVisibility;
                    }
                } else if (model.isDistributionListConversation() && receiverModel instanceof DistributionListModel && model.getDistributionList() != null) {
                    // Refresh distribution list name
                    DistributionListModel distributionListModel = (DistributionListModel) receiverModel;
                    model.getDistributionList().setName(distributionListModel.getName());
                    model.conversationVisibility = distributionListModel.getConversationVisibility();
                }
            }

            if (model == null) {
                return null;
            }
            if (newConversationModel) {
                logger.debug("refresh modified parent NEW");
                fireOnNewConversation(model);
            } else {
                logger.debug("refresh modified parent MODIFIED");
                fireOnUpdatedConversation(model);
                // The "cache" holds only conversations that are not archived. It therefore is more than just a cache. Therefore, we need to remove
                // the conversation model from the cache in case it has been archived.
                if (model.conversationVisibility == ConversationVisibility.ARCHIVED) {
                    removeFromCache(model);
                }
            }

            return model;
        }

        /**
         * Refresh the conversation data based on a newly created or update message model.
         * <p>
         * Examples:
         * <p>
         * - Update message count.
         * - Update unread status.
         * <p>
         * TODO(ANDR-4721): This method is confusing, possibly in the wrong place and does too many things.
         *   It should eventually be refactored. Ideally, we replace this entirely with a proper repository.
         */
        private void refreshForMessage(@NonNull M messageModel, boolean isNewMessage) {
            // Look up conversation in cache
            I index = this.getIdentifier(messageModel);
            ConversationModel conversationModel = this.getCached(index);

            // On cache miss, get the conversation from the DB
            boolean newConversationModel = false;
            if (conversationModel == null) {
                newConversationModel = true;
                conversationModel = this.getSelected(index);
            }

            // If conversation was not found, give up
            if (conversationModel == null) {
                logger.warn("Could not refresh conversation for message model, conversation not found");
                return;
            }

            boolean latestMessageUpdated = false;
            if ((conversationModel.latestMessage == null
                || conversationModel.latestMessage.getId() <= messageModel.getId())
                && (!messageModel.isStatusMessage() || messageModel.getType() == MessageType.GROUP_CALL_STATUS)
            ) {
                if (isNewMessage) {
                    conversationModel.messageCount += 1;
                }

                conversationModel.latestMessage = messageModel;
                latestMessageUpdated = true;
            }

            // Update read/unread state if necessary
            if (latestMessageUpdated) {
                if (MessageUtil.isUnread(messageModel)) {
                    conversationModel.setUnreadCount(conversationModel.messageReceiver.getUnreadMessagesCount());
                    // For the conversation tag marked-as-unread the trigger source does not matter as it isn't reflected
                    conversationTagService.removeTag(conversationModel.getId(), ConversationTag.MARKED_AS_UNREAD, TriggerSource.LOCAL);
                } else if (messageModel.isRead() && messageModel.getType() != MessageType.GROUP_CALL_STATUS) {
                    conversationModel.setUnreadCount(0);

                    // TODO(ANDR-3709): Removing the MARKED_AS_UNREAD tag here is not generally correct, e.g. it should not be removed
                    //  if this method was called because the message was edited by another user or because the message was marked as read
                    //  on a linked device.
                    // For the conversation tag marked-as-unread the trigger source does not matter as it isn't reflected
                    conversationTagService.removeTag(conversationModel.getId(), ConversationTag.MARKED_AS_UNREAD, TriggerSource.LOCAL);
                }
            }

            sort();

            if (newConversationModel) {
                fireOnNewConversation(conversationModel);
            } else {
                fireOnUpdatedConversation(conversationModel);
            }
        }

        public final void markConversationAsRead(@NonNull R receiverModel) {
            // Look up conversation in cache
            I index = this.getIdentifier(receiverModel);
            ConversationModel conversationModel = this.getCached(index);

            // On cache miss, get the conversation from the DB
            if (conversationModel == null) {
                conversationModel = this.getSelected(index);
            }

            // If conversation was not found, give up
            if (conversationModel == null) {
                logger.warn("Could not mark conversation as read for receiver model, conversation not found");
                return;
            }

            conversationModel.setUnreadCount(0);
            // For the conversation tag marked-as-unread the trigger source does not matter as it isn't reflected
            var updated = conversationTagService.removeTag(conversationModel.getId(), ConversationTag.MARKED_AS_UNREAD, TriggerSource.LOCAL);
            if (updated) {
                fireOnUpdatedConversation(conversationModel);
            }
        }

        public final void markConversationAsRead(@NonNull M message) {
            // Look up conversation in cache
            I index = this.getIdentifier(message);
            ConversationModel conversationModel = this.getCached(index);

            // On cache miss, get the conversation from the DB
            if (conversationModel == null) {
                conversationModel = this.getSelected(index);
            }

            // If conversation was not found, give up
            if (conversationModel == null) {
                logger.warn("Could not mark conversation as read for message model, conversation not found");
                return;
            }

            conversationModel.setUnreadCount(0);
            // For the conversation tag marked-as-unread the trigger source does not matter as it isn't reflected
            var updated = conversationTagService.removeTag(conversationModel.getId(), ConversationTag.MARKED_AS_UNREAD, TriggerSource.LOCAL);
            if (updated) {
                fireOnUpdatedConversation(conversationModel);
            }
        }

        public final void messageDeleted(@NonNull M messageModel) {
            final ConversationModel model = this.getCached(messageModel);
            //if the newest message is deleted, reload
            if (model != null && model.latestMessage != null) {
                if (model.latestMessage.getId() >= messageModel.getId()) {
                    updateLatestConversationMessageAfterDelete(model);
                    sort();
                    fireOnUpdatedConversation(model);
                }
            }
        }

        /**
         * See {@link #parse(String, String[])} for docs.
         */
        protected List<ConversationResult> parse(String query) {
            return parse(query, null);
        }

        /**
         * Run an SQL query and parse the resulting cursor.
         * <p>
         * The query must return rows with the following columns:
         * <p>
         * - Index 0: The conversation identifier (see {@link ConversationResult#identifier}
         * - Index 1: The message count (see {@link ConversationResult#messageCount}
         * - Index 2: The lastUpdate timestamp (see {@link ConversationResult#lastUpdate}
         * - Index 3: The nullable latest message ID (see {@link ConversationResult#latestMessageId}
         * - Index 4: The serialized conversationVisibility int from either contact-, group-, or distribution list model
         */
        protected List<ConversationResult> parse(String query, String[] args) {
            final List<ConversationResult> results = new ArrayList<>();

            try (Cursor cursor = databaseProvider.getReadableDatabase().rawQuery(query, args)) {
                if (cursor == null) {
                    return results;
                }
                while (cursor.moveToNext()) {
                    final String identifier = cursor.getString(0);
                    final long messageCount = cursor.getLong(1);
                    final Instant lastUpdate = Instant.ofEpochMilli(cursor.getLong(2));
                    final Integer latestMessageId = cursor.isNull(3) ? null : cursor.getInt(3);
                    final int conversationVisibilityValue = cursor.getInt(4);
                    ConversationVisibility conversationVisibility = ConversationVisibility.deserialize(conversationVisibilityValue);
                    if (conversationVisibility == null) {
                        logger.error("Could not deserialize conversation visibility from value {}", conversationVisibilityValue);
                        conversationVisibility = ConversationVisibility.NORMAL;
                    }
                    results.add(
                        new ConversationResult(
                            identifier,
                            messageCount,
                            lastUpdate,
                            latestMessageId,
                            conversationVisibility
                        )
                    );
                }
            }

            return results;
        }

        /**
         * If {@code conversationModel} is null, create a new {@link ConversationModel}, cache it if
         * requested, and return it.
         * Otherwise, update the {@code messageReceiver} in the existing {@code conversationModel} and return it.
         */
        protected @NonNull ConversationModel createOrUpdateConversationModel(
            @Nullable ConversationModel conversationModel,
            @NonNull ReceiverModel receiverModel,
            @NonNull MessageReceiver<?> messageReceiver,
            boolean addToCache
        ) {
            if (conversationModel != null) {
                conversationModel.messageReceiver = messageReceiver;
                return conversationModel;
            }

            final ConversationModel newConversationModel = new ConversationModel(messageReceiver);

            synchronized (conversationCache) {
                Optional<ConversationModel> optionalConversationModel = conversationCache.stream()
                    .filter(model -> model.getId().equals(newConversationModel.getId()))
                    .findFirst();

                if (optionalConversationModel.isPresent()) {
                    ConversationModel existingConversationModel = optionalConversationModel.get();
                    existingConversationModel.messageReceiver = messageReceiver;
                    logger.warn("The conversation already existed and was updated");
                    return existingConversationModel;
                }

                // Add to cache, but only for non-archived and non-hidden conversations
                if (addToCache && !receiverModel.isArchived() && !receiverModel.isHidden()) {
                    conversationCache.add(newConversationModel);
                }
            }

            return newConversationModel;
        }
    }

    private class ContactConversationModelParser extends ConversationModelParser<String, MessageModel, ch.threema.data.models.ContactModel> {
        @Override
        public boolean belongsTo(ConversationModel conversationModel, String identity) {
            return conversationModel.getContact() != null &&
                conversationModel.getContact().getIdentity().equals(identity);
        }

        /**
         * <pre>{@code
         * WITH message_info AS (
         *     SELECT
         *         identity,
         *         COUNT(*) AS messageCount,
         *         MAX(id)  AS latestMessageId
         *     FROM
         *         message
         *     WHERE
         *         isSaved             = 1
         *         AND isStatusMessage = 0
         *         AND identity        = ?
         *     GROUP BY
         *         identity
         * )
         * SELECT
         *     c.identity,
         *     IFNULL(m.messageCount, 0) AS messageCount,
         *     c.lastUpdateAt,
         *     m.latestMessageId,
         *     m.conversationVisibility
         * FROM
         *     contacts c
         * LEFT JOIN
         *     message_info m ON c.identity = m.identity
         * WHERE
         *     c.lastUpdateAt IS NOT NULL
         *     AND c.identity = ?
         * }</pre>
         */
        @Override
        public @NonNull List<ConversationResult> select(@NonNull String identity) {
            return this.parse(
                "WITH message_info AS (" +
                    "SELECT " + MessageModel.COLUMN_IDENTITY + ", COUNT(*) AS messageCount, MAX(id) AS latestMessageId " +
                    "FROM " + MessageModel.TABLE + " " +
                    "WHERE " + MessageModel.COLUMN_IS_SAVED + " = 1 AND " + MessageModel.COLUMN_IS_STATUS_MESSAGE + " = 0 " +
                    "  AND " + MessageModel.COLUMN_IDENTITY + " = ? " +
                    "GROUP BY " + MessageModel.COLUMN_IDENTITY +
                    ") " +
                    "SELECT c." + ContactModel.COLUMN_IDENTITY + ", IFNULL(m.messageCount, 0) AS messageCount, " +
                    "    c." + ContactModel.COLUMN_LAST_UPDATE_AT + ", m.latestMessageId, c." + ContactModel.COLUMN_CONVERSATION_VISIBILITY + " " +
                    "FROM " + ContactModel.TABLE + " c " +
                    "LEFT JOIN message_info m ON c." + ContactModel.COLUMN_IDENTITY + " = m." + MessageModel.COLUMN_IDENTITY + " " +
                    "WHERE c." + ContactModel.COLUMN_LAST_UPDATE_AT + " IS NOT NULL AND c." + ContactModel.COLUMN_IDENTITY + " = ?",
                new String[]{identity, identity}
            );
        }

        /**
         * <pre>{@code
         * WITH message_info AS (
         *     SELECT
         *         identity,
         *         COUNT(*) AS messageCount,
         *         MAX(id)  AS latestMessageId
         *     FROM
         *         message
         *     WHERE
         *         isSaved             = 1
         *         AND isStatusMessage = 0
         *     GROUP BY
         *         identity
         * )
         * SELECT
         *     c.identity,
         *     IFNULL(m.messageCount, 0) AS messageCount,
         *     c.lastUpdateAt,
         *     m.latestMessageId,
         *     c.conversationVisibility
         * FROM
         *     contacts c
         * LEFT JOIN
         *     message_info m ON c.identity = m.identity
         * WHERE
         *     c.lastUpdateAt          IS NOT NULL
         *     AND c.acquaintanceLevel      != 1
         *     AND c.conversationVisibility =/!= 1
         * }</pre>
         */
        @Override
        public @NonNull List<ConversationResult> selectAll(boolean archived) {
            String conversationVisibilityCondition;
            if (archived) {
                conversationVisibilityCondition = " = ";
            } else {
                conversationVisibilityCondition = " != ";
            }

            return this.parse(
                "WITH message_info AS (" +
                    "SELECT " + MessageModel.COLUMN_IDENTITY + ", COUNT(*) AS messageCount, MAX(id) AS latestMessageId " +
                    "FROM " + MessageModel.TABLE + " " +
                    "WHERE " + MessageModel.COLUMN_IS_SAVED + " = 1 AND " + MessageModel.COLUMN_IS_STATUS_MESSAGE + " = 0 " +
                    "GROUP BY " + MessageModel.COLUMN_IDENTITY +
                    ") " +
                    "SELECT c." + ContactModel.COLUMN_IDENTITY + ", IFNULL(m.messageCount, 0) AS messageCount, c." + ContactModel.COLUMN_LAST_UPDATE_AT + ", " +
                    "    m.latestMessageId, c." + ContactModel.COLUMN_CONVERSATION_VISIBILITY + " " +
                    "FROM " + ContactModel.TABLE + " c " +
                    "LEFT JOIN message_info m ON c." + ContactModel.COLUMN_IDENTITY + " = m." + MessageModel.COLUMN_IDENTITY + " " +
                    "WHERE c." + ContactModel.COLUMN_LAST_UPDATE_AT + " IS NOT NULL AND c." + ContactModel.COLUMN_ACQUAINTANCE_LEVEL + " != 1 " +
                    "    AND c." + ContactModel.COLUMN_CONVERSATION_VISIBILITY + conversationVisibilityCondition + ConversationVisibility.ARCHIVED.getSerializedValue()
            );
        }

        @Override
        public @Nullable ConversationModel parseResult(
            @NonNull ConversationResult result,
            @Nullable ConversationModel conversationModel,
            boolean addToCache
        ) {
            // Look up contact and create receiver
            final ContactModel contactModel = contactService.getByIdentity(result.identifier);
            if (contactModel == null) {
                logger.warn("ContactConversationModelParser: Contact with identity {} not found", result.identifier);
                return null;
            }
            final ContactMessageReceiver receiver = contactService.createReceiver(contactModel);

            // If no conversation model was passed in (to be updated), create a new model
            conversationModel = this.createOrUpdateConversationModel(
                conversationModel,
                contactModel,
                receiver,
                addToCache
            );

            // Update the rest of the conversation information
            conversationModel.messageCount = result.messageCount;
            conversationModel.lastUpdate = result.lastUpdate;
            if (result.messageCount > 0 && result.latestMessageId != null) {
                final @Nullable MessageModel latestMessage = messageService.getContactMessageModel(result.latestMessageId);
                conversationModel.latestMessage = latestMessage;
                if (MessageUtil.isUnread(latestMessage)) {
                    // Update unread message count only if the "newest" message is unread
                    conversationModel.setUnreadCount(receiver.getUnreadMessagesCount());
                }
            }
            conversationModel.conversationVisibility = result.conversationVisibility;

            return conversationModel;
        }

        @Override
        protected String getIdentifier(MessageModel messageModel) {
            return messageModel != null ? messageModel.getIdentity() : null;
        }

        @Override
        protected String getIdentifier(ch.threema.data.models.ContactModel contactModel) {
            return contactModel != null ? contactModel.getIdentity() : null;
        }

        @Nullable
        @Override
        protected Instant getLastUpdate(@Nullable ch.threema.data.models.ContactModel receiverModel) {
            if (receiverModel == null) {
                return null;
            }
            return contactService.getLastUpdate(receiverModel.getIdentity());
        }
    }

    private class GroupConversationModelParser extends ConversationModelParser<Integer, GroupMessageModel, GroupModelOld> {
        @Override
        public boolean belongsTo(ConversationModel conversationModel, Integer groupId) {
            return conversationModel.getGroup() != null &&
                conversationModel.getGroup().getId() == groupId;
        }

        @Override
        public @Nullable ConversationModel parseResult(
            @NonNull ConversationResult result,
            @Nullable ConversationModel conversationModel,
            boolean addToCache
        ) {
            // Look up group and create receiver
            final GroupModelOld groupModel = groupService.getById(Integer.parseInt(result.identifier));
            if (groupModel == null) {
                logger.warn("GroupConversationModelParser: Group with ID {} not found", result.identifier);
                return null;
            }
            final GroupMessageReceiver receiver = groupService.createReceiver(groupModel);

            // If no conversation model was passed in (to be updated), create a new model
            conversationModel = this.createOrUpdateConversationModel(
                conversationModel,
                groupModel,
                receiver,
                addToCache
            );

            // Update the rest of the conversation information
            conversationModel.messageCount = result.messageCount;
            conversationModel.lastUpdate = result.lastUpdate;
            if (result.messageCount > 0 && result.latestMessageId != null) {
                final @Nullable GroupMessageModel latestMessageModel = messageService.getGroupMessageModel(result.latestMessageId);
                conversationModel.latestMessage = latestMessageModel;
                if (MessageUtil.isUnread(latestMessageModel)) {
                    // Update unread message count only if the "newest" message is unread
                    conversationModel.setUnreadCount(receiver.getUnreadMessagesCount());
                }
            }
            conversationModel.conversationVisibility = result.conversationVisibility;

            return conversationModel;
        }

        @Override
        public @NonNull List<ConversationResult> select(@NonNull Integer groupId) {
            // Note: Don't exclude groups without last update, groups should always be visible
            return this.parse(
                "WITH message_info AS (" +
                    "SELECT " + GroupMessageModel.COLUMN_GROUP_ID + ", COUNT(*) as messageCount, MAX(id) as latestMessageId " +
                    "FROM " + GroupMessageModel.TABLE + " " +
                    "WHERE " + GroupMessageModel.COLUMN_IS_SAVED + " = 1 AND (" + GroupMessageModel.COLUMN_IS_STATUS_MESSAGE + " = 0 OR type = ?) " +
                    "GROUP BY " + GroupMessageModel.COLUMN_GROUP_ID +
                    ") " +
                    "SELECT g." + GroupModelOld.COLUMN_ID + ", IFNULL(m.messageCount, 0) AS messageCount, IFNULL(g." + GroupModelOld.COLUMN_LAST_UPDATE + ", 0), " +
                    "    m.latestMessageId, g." + GroupModelOld.COLUMN_CONVERSATION_VISIBILITY + " " +
                    "FROM " + GroupModelOld.TABLE + " g " +
                    "LEFT JOIN message_info m ON g." + GroupModelOld.COLUMN_ID + " = m." + GroupMessageModel.COLUMN_GROUP_ID + " " +
                    "WHERE g." + GroupModelOld.COLUMN_ID + " = ?",
                new String[]{String.valueOf(MessageType.GROUP_CALL_STATUS.serializedValue), String.valueOf(groupId)}
            );
        }

        @Override
        public @NonNull List<ConversationResult> selectAll(boolean archived) {
            String conversationVisibilityCondition;
            if (archived) {
                conversationVisibilityCondition = " = ";
            } else {
                conversationVisibilityCondition = " != ";
            }
            // Note: Don't exclude groups without last update, groups should always be visible
            return this.parse(
                "WITH message_info AS (" +
                    "SELECT " + GroupMessageModel.COLUMN_GROUP_ID + ", COUNT(*) as messageCount, MAX(id) as latestMessageId " +
                    "FROM " + GroupMessageModel.TABLE + " " +
                    "WHERE " + GroupMessageModel.COLUMN_IS_SAVED + " = 1 AND (" + GroupMessageModel.COLUMN_IS_STATUS_MESSAGE + " = 0 OR " + GroupMessageModel.COLUMN_TYPE + " = ?) " +
                    "GROUP BY " + GroupMessageModel.COLUMN_GROUP_ID +
                    ") " +
                    "SELECT g." + GroupModelOld.COLUMN_ID + ", IFNULL(m.messageCount, 0) AS messageCount, IFNULL(g." + GroupModelOld.COLUMN_LAST_UPDATE + ", 0), " +
                    "    m.latestMessageId, g." + GroupModelOld.COLUMN_CONVERSATION_VISIBILITY + " " +
                    "FROM " + GroupModelOld.TABLE + " g " +
                    "LEFT JOIN message_info m ON g." + GroupModelOld.COLUMN_ID + " = m." + GroupMessageModel.COLUMN_GROUP_ID + " " +
                    "WHERE g." + GroupModelOld.COLUMN_CONVERSATION_VISIBILITY + conversationVisibilityCondition + ConversationVisibility.ARCHIVED.getSerializedValue(),
                new String[]{String.valueOf(MessageType.GROUP_CALL_STATUS.serializedValue)}
            );
        }

        @Override
        protected Integer getIdentifier(GroupMessageModel messageModel) {
            return messageModel != null ? messageModel.getGroupId() : null;
        }

        @Override
        protected Integer getIdentifier(GroupModelOld groupModel) {
            return groupModel != null ? groupModel.getId() : null;
        }

        @Nullable
        @Override
        protected Instant getLastUpdate(@Nullable GroupModelOld receiverModel) {
            if (receiverModel == null) {
                return null;
            }
            return receiverModel.getLastUpdate();
        }
    }

    private class DistributionListConversationModelParser extends ConversationModelParser<Long, DistributionListMessageModel, DistributionListModel> {
        @Override
        public boolean belongsTo(ConversationModel conversationModel, Long distributionListId) {
            return conversationModel.getDistributionList() != null &&
                conversationModel.getDistributionList().getId() == distributionListId;
        }

        @Override
        public @Nullable ConversationModel parseResult(
            @NonNull ConversationResult result,
            @Nullable ConversationModel conversationModel,
            boolean addToCache
        ) {
            // Look up distribution list and create receiver
            final DistributionListModel distributionListModel = distributionListService.getById(Long.parseLong(result.identifier));
            if (distributionListModel == null) {
                logger.warn("DistributionListConversationModelParser: Distribution list with ID {} not found", result.identifier);
                return null;
            }
            final DistributionListMessageReceiver receiver = distributionListService.createReceiver(distributionListModel);

            // If no conversation model was passed in (to be updated), create a new model
            conversationModel = this.createOrUpdateConversationModel(
                conversationModel,
                distributionListModel,
                receiver,
                addToCache
            );

            // Update the rest of the conversation information
            conversationModel.messageCount = result.messageCount;
            conversationModel.lastUpdate = result.lastUpdate;
            if (result.messageCount > 0 && result.latestMessageId != null) {
                conversationModel.latestMessage = messageService.getDistributionListMessageModel(result.latestMessageId);
            }

            // Distribution lists cannot have unread messages
            conversationModel.setUnreadCount(0);

            conversationModel.conversationVisibility = result.conversationVisibility;

            return conversationModel;
        }

        @Override
        public @NonNull List<ConversationResult> select(@NonNull Long distributionListId) {
            // Note: Don't exclude distribution lists without last update, distribution lists should always be visible
            return this.parse(
                "WITH message_info AS (" +
                    "SELECT " + DistributionListMessageModel.COLUMN_DISTRIBUTION_LIST_ID + ", COUNT(*) as messageCount, MAX(id) as latestMessageId " +
                    "FROM " + DistributionListMessageModel.TABLE + " " +
                    "WHERE " + DistributionListMessageModel.COLUMN_IS_SAVED + " = 1 AND " + DistributionListMessageModel.COLUMN_IS_STATUS_MESSAGE + " = 0 " +
                    "GROUP BY " + DistributionListMessageModel.COLUMN_DISTRIBUTION_LIST_ID +
                    ") " +
                    "SELECT d." + DistributionListModel.COLUMN_ID + ", IFNULL(m.messageCount, 0) AS messageCount, " +
                    "    IFNULL(d." + DistributionListModel.COLUMN_LAST_UPDATE + ", 0), m.latestMessageId, d." + DistributionListModel.COLUMN_CONVERSATION_VISIBILITY + " " +
                    "FROM " + DistributionListModel.TABLE + " d " +
                    "LEFT JOIN message_info m ON d." + DistributionListModel.COLUMN_ID + " = m." + DistributionListMessageModel.COLUMN_DISTRIBUTION_LIST_ID + " " +
                    "WHERE d." + DistributionListModel.COLUMN_ID + " = ?",
                new String[]{String.valueOf(distributionListId)}
            );
        }

        @Override
        public @NonNull List<ConversationResult> selectAll(boolean archived) {
            String conversationVisibilityCondition;
            if (archived) {
                conversationVisibilityCondition = " = ";
            } else {
                conversationVisibilityCondition = " != ";
            }

            // Note: Don't exclude distribution lists without last update, distribution lists should always be visible
            return this.parse(
                "WITH message_info AS (" +
                    "SELECT " + DistributionListMessageModel.COLUMN_DISTRIBUTION_LIST_ID + ", COUNT(*) as messageCount, MAX(id) as latestMessageId " +
                    "FROM " + DistributionListMessageModel.TABLE + " " +
                    "WHERE " + DistributionListMessageModel.COLUMN_IS_SAVED + " = 1 AND " + DistributionListMessageModel.COLUMN_IS_STATUS_MESSAGE + " = 0 " +
                    "GROUP BY " + DistributionListMessageModel.COLUMN_DISTRIBUTION_LIST_ID + ") " +
                    "SELECT d." + DistributionListModel.COLUMN_ID + ", IFNULL(m.messageCount, 0) AS messageCount, " +
                    "    IFNULL(d." + DistributionListModel.COLUMN_LAST_UPDATE + ", 0), m.latestMessageId, d." + DistributionListModel.COLUMN_CONVERSATION_VISIBILITY + " " +
                    "FROM " + DistributionListModel.TABLE + " d " +
                    "LEFT JOIN message_info m ON d." + DistributionListModel.COLUMN_ID + " = m." + DistributionListMessageModel.COLUMN_DISTRIBUTION_LIST_ID + " " +
                    "WHERE d." + DistributionListModel.COLUMN_IS_ADHOC_DISTRIBUTION_LIST + " != 1 " +
                    "AND d." + DistributionListModel.COLUMN_CONVERSATION_VISIBILITY + conversationVisibilityCondition + ConversationVisibility.ARCHIVED.getSerializedValue()
            );
        }

        @Override
        protected Long getIdentifier(DistributionListMessageModel messageModel) {
            return messageModel != null ? messageModel.getDistributionListId() : null;
        }

        @Override
        protected Long getIdentifier(DistributionListModel distributionListModel) {
            return distributionListModel != null ? distributionListModel.getId() : null;
        }

        @Nullable
        @Override
        protected Instant getLastUpdate(@Nullable DistributionListModel receiverModel) {
            if (receiverModel == null) {
                return null;
            }
            return receiverModel.getLastUpdate();
        }
    }

    private void updateLatestConversationMessageAfterDelete(ConversationModel conversationModel) {
        AbstractMessageModel newestMessage;

        newestMessage = messageService.getMessagesForReceiver(
                conversationModel.messageReceiver,
                new MessageService.MessageFilter() {
                    @Override
                    public long getPageSize() {
                        return 1;
                    }

                    @Override
                    public Integer getPageReferenceId() {
                        return null;
                    }

                    @Override
                    public boolean withStatusMessages() {
                        return false;
                    }

                    @Override
                    public boolean withUnsaved() {
                        return false;
                    }

                    @Override
                    public boolean onlyUnread() {
                        return false;
                    }

                    @Override
                    public boolean onlyDownloaded() {
                        return true;
                    }

                    @Override
                    public MessageType[] types() {
                        return null;
                    }

                    @Override
                    public int[] contentTypes() {
                        return null;
                    }

                    @Override
                    public int[] displayTags() {
                        return null;
                    }
                }
            )
            .stream()
            .findFirst()
            .orElse(null);

        conversationModel.latestMessage = newestMessage;

        if (newestMessage == null || (newestMessage.isOutbox() || newestMessage.isRead())) {
            conversationModel.setUnreadCount(0);
        }

        if (newestMessage == null) {
            conversationModel.messageCount = 0;
        }
    }

    @Override
    public void calculateLastUpdateForAllConversations() {
        final SQLiteDatabase db = databaseProvider.getReadableDatabase();
        this.calculateLastUpdateContacts(db);
        this.calculateLastUpdateGroups(db);
        this.calculateLastUpdateDistributionLists(db);
    }

    private void calculateLastUpdateContacts(@NonNull SQLiteDatabase db) {
        logger.info("Calculate lastUpdate for contacts");

        db.execSQL(
            "UPDATE " + ContactModel.TABLE + " " +
                "SET " + ContactModel.COLUMN_LAST_UPDATE_AT + " = tmp.lastUpdateAt FROM ( " +
                "    SELECT m." + MessageModel.COLUMN_IDENTITY + ", max(m." + MessageModel.COLUMN_CREATED_AT + ") as lastUpdateAt " +
                "    FROM " + MessageModel.TABLE + " m " +
                "    WHERE m." + MessageModel.COLUMN_IS_SAVED + " = 1 " +
                "    GROUP BY m." + MessageModel.COLUMN_IDENTITY + " " +
                ") tmp " +
                "WHERE " + ContactModel.TABLE + "." + ContactModel.COLUMN_IDENTITY + " = tmp." + MessageModel.COLUMN_IDENTITY + ";"
        );
    }

    private void calculateLastUpdateGroups(@NonNull SQLiteDatabase db) {
        logger.info("Calculate lastUpdate for groups");

        // Set lastUpdate to the create date of the latest message if present
        db.execSQL(
            "UPDATE " + GroupModelOld.TABLE + " " +
                "SET " + GroupModelOld.COLUMN_LAST_UPDATE + " = tmp.lastUpdateAt FROM ( " +
                "    SELECT m." + GroupMessageModel.COLUMN_GROUP_ID + ", max(m." + GroupMessageModel.COLUMN_CREATED_AT + ") as lastUpdateAt " +
                "    FROM " + GroupMessageModel.TABLE + " m " +
                "    WHERE m." + GroupMessageModel.COLUMN_IS_SAVED + " = 1 " +
                "    GROUP BY m." + GroupMessageModel.COLUMN_GROUP_ID + " " +
                ") tmp " +
                "WHERE " + GroupModelOld.TABLE + "." + GroupModelOld.COLUMN_ID + " = tmp." + GroupMessageModel.COLUMN_GROUP_ID + ";"
        );

        // Set lastUpdate for groups without messages.
        db.execSQL(
            "UPDATE " + GroupModelOld.TABLE + " " +
                "SET " + GroupModelOld.COLUMN_LAST_UPDATE + " = " + GroupModelOld.COLUMN_CREATED_AT + " " +
                "WHERE " + GroupModelOld.COLUMN_LAST_UPDATE + " IS NULL;"
        );
    }

    private void calculateLastUpdateDistributionLists(@NonNull SQLiteDatabase db) {
        logger.info("Calculate lastUpdate for distribution lists");

        // Set lastUpdate to the create date of the latest message if present
        db.execSQL(
            "UPDATE " + DistributionListModel.TABLE + " " +
                "SET " + DistributionListModel.COLUMN_LAST_UPDATE + " = tmp.lastUpdateAt FROM ( " +
                "    SELECT m." + DistributionListMessageModel.COLUMN_DISTRIBUTION_LIST_ID + ", max(m." + DistributionListMessageModel.COLUMN_CREATED_AT + ") as lastUpdateAt " +
                "    FROM " + DistributionListMessageModel.TABLE + " m " +
                "    WHERE m." + DistributionListMessageModel.COLUMN_IS_SAVED + " = 1 " +
                "    GROUP BY m." + DistributionListMessageModel.COLUMN_DISTRIBUTION_LIST_ID + " " +
                ") tmp " +
                "WHERE " + DistributionListModel.TABLE + "." + DistributionListModel.COLUMN_ID + " = tmp." + DistributionListMessageModel.COLUMN_DISTRIBUTION_LIST_ID + ";"
        );

        // Set lastUpdate for distribution lists without messages.
        db.execSQL(
            "UPDATE " + DistributionListModel.TABLE + " " +
                "SET " + DistributionListModel.COLUMN_LAST_UPDATE + " = " + DistributionListModel.COLUMN_CREATED_AT + " " +
                "WHERE " + DistributionListModel.COLUMN_LAST_UPDATE + " IS NULL;"
        );
    }
}
