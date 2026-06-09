package ch.threema.app.usecases.availabilitystatus

import ch.threema.app.BuildConfig
import ch.threema.app.availabilitystatus.withTrimmedDescription
import ch.threema.app.multidevice.MultiDeviceManager
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.tasks.ReflectUserAvailabilityStatusTask
import ch.threema.app.utils.DispatcherProvider
import ch.threema.app.work.workproperties.WorkPropertiesClient
import ch.threema.base.utils.getThreemaLogger
import ch.threema.data.datatypes.AvailabilityStatus
import ch.threema.domain.taskmanager.TaskManager
import kotlinx.coroutines.withContext

private val logger = getThreemaLogger("UpdateUserAvailabilityStatusUseCase")

class UpdateUserAvailabilityStatusUseCase(
    private val dispatcherProvider: DispatcherProvider,
    private val preferenceService: PreferenceService,
    private val taskManager: TaskManager,
    private val multiDeviceManager: MultiDeviceManager,
    private val workPropertiesClient: WorkPropertiesClient,
) {

    suspend fun call(availabilityStatus: AvailabilityStatus): Result<Unit> {
        if (!BuildConfig.AVAILABILITY_STATUS_ENABLED) {
            logger.error("Cannot update the users availability status because this feature is not supported by the build")
            return Result.failure(
                exception = IllegalStateException("Availability status feature not supported"),
            )
        }

        val availabilityStatusTrimmed = availabilityStatus.withTrimmedDescription()

        return withContext(dispatcherProvider.io) {
            if (preferenceService.getAvailabilityStatus() == availabilityStatusTrimmed) {
                return@withContext Result.success(Unit)
            }

            // 1. Call user-properties endpoint
            workPropertiesClient.updateAvailabilityStatus(availabilityStatusTrimmed)
                .onFailure { throwable ->
                    logger.error("Work properties api failure", throwable)
                    // 2. If 1. failed: Abort
                    return@withContext Result.failure(throwable)
                }

            // 3. Persist status locally
            preferenceService.setAvailabilityStatus(availabilityStatusTrimmed)

            // 4. Create a persistent task that reflects the users status value at the time of execution
            if (multiDeviceManager.isMultiDeviceActive) {
                @Suppress("DeferredResultUnused")
                taskManager.schedule(ReflectUserAvailabilityStatusTask())
            }

            Result.success(Unit)
        }
    }
}
