package ch.threema.app.fragments.composemessage

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.threema.app.BuildConfig
import ch.threema.app.messagereceiver.ContactMessageReceiver
import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.services.MessageService
import ch.threema.app.utils.DispatcherProvider
import ch.threema.app.utils.SingleLiveEvent
import ch.threema.base.utils.getThreemaLogger
import ch.threema.data.datatypes.AvailabilityStatus
import ch.threema.data.repositories.ContactModelRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val logger = getThreemaLogger("ComposeMessageViewModel")

class ComposeMessageViewModel(
    private val messageService: MessageService,
    private val dispatcherProvider: DispatcherProvider,
    private val contactModelRepository: ContactModelRepository,
) : ViewModel() {

    private val _events = SingleLiveEvent<ComposeMessageEvent>()
    val events: LiveData<ComposeMessageEvent> = _events

    private val _contactAvailabilityStatus = MutableLiveData<AvailabilityStatus>(AvailabilityStatus.None)
    val contactAvailabilityStatus: LiveData<AvailabilityStatus> = _contactAvailabilityStatus

    private var getContactAvailabilityStatusJob: Job? = null

    fun onResume(messageReceiver: MessageReceiver<*>) {
        @Suppress("KotlinConstantConditions")
        if (BuildConfig.AVAILABILITY_STATUS_ENABLED) {
            refreshAvailabilityStatus(messageReceiver)
        }
    }

    private fun refreshAvailabilityStatus(messageReceiver: MessageReceiver<*>) {
        getContactAvailabilityStatusJob?.cancel()
        if (messageReceiver is ContactMessageReceiver) {
            getContactAvailabilityStatusJob = viewModelScope.launch(dispatcherProvider.io) {
                val availabilityStatus = contactModelRepository.getByIdentity(messageReceiver.contact.identity)
                    ?.data
                    ?.availabilityStatus
                if (isActive) {
                    _contactAvailabilityStatus.postValue(
                        availabilityStatus ?: AvailabilityStatus.None,
                    )
                }
            }
        } else {
            _contactAvailabilityStatus.value = AvailabilityStatus.None
        }
    }

    fun loadNextRecords(
        messageReceiver: MessageReceiver<*>,
        filter: MessageService.MessageFilter,
        generation: Int,
    ) {
        viewModelScope.launch {
            withContext(dispatcherProvider.io) {
                // F1Whisper (fifth fork review, F5-10): exactly ONE terminal result, whatever happens.
                //
                // The fragment acquires a single-load slot before dispatching this and releases it only from the event
                // below. When the query threw, nothing was emitted, so the slot stayed owned for the life of the
                // process: the refresh indicator kept spinning and every later page request was rejected until the
                // conversation was reset. Both outcomes now carry the generation that acquired the slot.
                val event = try {
                    val messageModels = messageService.getMessagesForReceiver(messageReceiver, filter)
                    ComposeMessageEvent.NextRecordsLoaded(
                        messageModels = messageModels,
                        hasMoreRecords = messageModels.size >= filter.pageSize,
                        generation = generation,
                    )
                } catch (e: Exception) {
                    logger.error("Could not load the next records", e)
                    ComposeMessageEvent.NextRecordsFailed(generation = generation)
                }
                _events.postValue(event)
            }
        }
    }
}
