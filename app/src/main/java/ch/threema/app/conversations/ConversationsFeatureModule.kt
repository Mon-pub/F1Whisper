package ch.threema.app.conversations

import ch.threema.app.conversation.ConversationRefreshMonitor
import ch.threema.app.conversation.GroupStatusMessageMonitor
import ch.threema.app.conversation.MessageViewElementFactory
import ch.threema.app.usecases.contacts.WatchAllMentionNamesUseCase
import ch.threema.app.usecases.contacts.WatchContactNameFormatSettingUseCase
import ch.threema.app.usecases.conversation.ExportConversationToFileUseCase
import ch.threema.app.usecases.conversations.EmptyOrDeleteConversationsUseCase
import ch.threema.app.usecases.conversations.WatchUnarchivedConversationListItemsUseCase
import ch.threema.app.usecases.conversations.WatchUnarchivedConversationsUseCase
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val conversationsFeatureModule = module {
    viewModel { parameters ->
        ConversationsViewModel(
            applicationContext = get(),
            dispatcherProvider = get(),
            conversationService = get(),
            conversationCategoryService = get(),
            chatFolderService = get(),
            preferenceService = get(),
            messageService = get(),
            notificationService = get(),
            exportConversationService = get(),
            distributionListService = get(),
            validContactsLookupSteps = get(),
            appRestrictions = get(),
            contactModelRepository = get(),
            groupModelRepository = get(),
            groupFlowDispatcher = get(),
            identityProvider = get(),
            globalEventFlows = get(),
            globalEventBuses = get(),
            exportConversationToFileUseCase = get(),
            getAndPrepareAvatarUseCase = get(),
            watchConversationListItemsUseCase = get(),
            watchContactNameFormatSettingUseCase = get(),
            emptyOrDeleteConversationsUseCase = get(),
            isMultiPaneEnabled = parameters[0],
            initiallyOpenedConversation = parameters[1],
        )
    }

    factoryOf(::EmptyOrDeleteConversationsUseCase)
    factoryOf(::ExportConversationToFileUseCase)
    factoryOf(::WatchUnarchivedConversationsUseCase)
    factoryOf(::WatchUnarchivedConversationListItemsUseCase)
    factoryOf(::WatchContactNameFormatSettingUseCase)
    factoryOf(::WatchAllMentionNamesUseCase)

    singleOf(::MessageViewElementFactory)
    singleOf(::ConversationRefreshMonitor)
    singleOf(::GroupStatusMessageMonitor)
}
