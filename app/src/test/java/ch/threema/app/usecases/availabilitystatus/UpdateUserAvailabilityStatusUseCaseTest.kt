@file:Suppress("KotlinConstantConditions")

package ch.threema.app.usecases.availabilitystatus

import ch.threema.app.BuildConfig
import ch.threema.app.multidevice.MultiDeviceManager
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.test.unconfinedTestDispatcherProvider
import ch.threema.app.work.workproperties.WorkPropertiesClient
import ch.threema.data.datatypes.AvailabilityStatus
import ch.threema.domain.taskmanager.Task
import ch.threema.domain.taskmanager.TaskCodec
import ch.threema.domain.taskmanager.TaskManager
import ch.threema.libthreema.WorkPropertiesUpdateException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assume

class UpdateUserAvailabilityStatusUseCaseTest {

    @Test
    fun `skips everything if no effective change`() = runTest {
        // precondition
        Assume.assumeTrue(BuildConfig.AVAILABILITY_STATUS_ENABLED)

        // arrange
        val workPropertiesClientMock = mockk<WorkPropertiesClient>()
        val preferenceServiceMock = mockk<PreferenceService> {
            coEvery { getAvailabilityStatus() } returns AvailabilityStatus.Unavailable(
                description = "On vacation",
            )
        }
        val useCase = UpdateUserAvailabilityStatusUseCase(
            dispatcherProvider = unconfinedTestDispatcherProvider(),
            preferenceService = preferenceServiceMock,
            taskManager = mockk(),
            multiDeviceManager = mockk(),
            workPropertiesClient = workPropertiesClientMock,
        )

        // act
        val newAvailabilityStatus = AvailabilityStatus.Unavailable(
            description = "On vacation",
        )
        val result = useCase.call(newAvailabilityStatus)

        // assert
        assertTrue { result.isSuccess }
        coVerify(exactly = 0) {
            workPropertiesClientMock.updateAvailabilityStatus(newAvailabilityStatus)
        }
        verify(exactly = 0) {
            preferenceServiceMock.setAvailabilityStatus(newAvailabilityStatus)
        }
    }

    @Test
    fun `skips everything if no effective change after trimming`() = runTest {
        // precondition
        Assume.assumeTrue(BuildConfig.AVAILABILITY_STATUS_ENABLED)

        // arrange
        val workPropertiesClientMock = mockk<WorkPropertiesClient>()
        val preferenceServiceMock = mockk<PreferenceService> {
            coEvery { getAvailabilityStatus() } returns AvailabilityStatus.Unavailable(
                description = "On vacation",
            )
        }
        val useCase = UpdateUserAvailabilityStatusUseCase(
            dispatcherProvider = unconfinedTestDispatcherProvider(),
            preferenceService = preferenceServiceMock,
            taskManager = mockk(),
            multiDeviceManager = mockk(),
            workPropertiesClient = workPropertiesClientMock,
        )

        // act
        val newAvailabilityStatus = AvailabilityStatus.Unavailable(
            description = "   On vacation   ",
        )
        val result = useCase.call(newAvailabilityStatus)

        // assert
        assertTrue { result.isSuccess }
        coVerify(exactly = 0) {
            workPropertiesClientMock.updateAvailabilityStatus(newAvailabilityStatus)
        }
        verify(exactly = 0) {
            preferenceServiceMock.setAvailabilityStatus(newAvailabilityStatus)
        }
    }

    @Test
    fun `stores value locally on work-properties api success`() = runTest {
        // precondition
        Assume.assumeTrue(BuildConfig.AVAILABILITY_STATUS_ENABLED)

        // arrange
        val workPropertiesClientMock = mockk<WorkPropertiesClient> {
            coEvery { updateAvailabilityStatus(any()) } returns Result.success(Unit)
        }
        val preferenceServiceMock = mockk<PreferenceService> {
            coEvery { getAvailabilityStatus() } returns AvailabilityStatus.Unavailable(
                description = "On vacation until tomorrow",
            )
            coEvery { setAvailabilityStatus(any()) } just runs
        }
        val multiDeviceManagerMock = mockk<MultiDeviceManager> {
            every { isMultiDeviceActive } returns false
        }
        val useCase = UpdateUserAvailabilityStatusUseCase(
            dispatcherProvider = unconfinedTestDispatcherProvider(),
            preferenceService = preferenceServiceMock,
            taskManager = mockk(),
            multiDeviceManager = multiDeviceManagerMock,
            workPropertiesClient = workPropertiesClientMock,
        )

        // act
        val newAvailabilityStatus = AvailabilityStatus.Unavailable(
            description = "On vacation until next week",
        )
        val result = useCase.call(newAvailabilityStatus)

        // assert
        assertTrue { result.isSuccess }
        coVerify(exactly = 1) {
            workPropertiesClientMock.updateAvailabilityStatus(newAvailabilityStatus)
        }
        verify(exactly = 1) {
            preferenceServiceMock.setAvailabilityStatus(newAvailabilityStatus)
        }
    }

    @Test
    fun `does not store value locally on work-properties api failure`() = runTest {
        // precondition
        Assume.assumeTrue(BuildConfig.AVAILABILITY_STATUS_ENABLED)

        // arrange
        val workPropertiesClientMock = mockk<WorkPropertiesClient> {
            coEvery { updateAvailabilityStatus(any()) } returns Result.failure(
                exception = WorkPropertiesUpdateException.NetworkException(""),
            )
        }
        val preferenceServiceMock = mockk<PreferenceService> {
            coEvery { getAvailabilityStatus() } returns AvailabilityStatus.None
        }
        val useCase = UpdateUserAvailabilityStatusUseCase(
            dispatcherProvider = unconfinedTestDispatcherProvider(),
            preferenceService = preferenceServiceMock,
            taskManager = mockk(),
            multiDeviceManager = mockk(),
            workPropertiesClient = workPropertiesClientMock,
        )

        // act
        val newAvailabilityStatus = AvailabilityStatus.Unavailable(
            description = "On vacation",
        )
        val result = useCase.call(newAvailabilityStatus)

        // assert
        assertTrue { result.isFailure }
        coVerify(exactly = 1) {
            workPropertiesClientMock.updateAvailabilityStatus(newAvailabilityStatus)
        }
        verify(exactly = 0) {
            preferenceServiceMock.setAvailabilityStatus(newAvailabilityStatus)
        }
    }

    @Test
    fun `schedules md reflection task on work-properties api success`() = runTest {
        // precondition
        Assume.assumeTrue(BuildConfig.AVAILABILITY_STATUS_ENABLED)

        // arrange
        val workPropertiesClientMock = mockk<WorkPropertiesClient> {
            coEvery { updateAvailabilityStatus(any()) } returns Result.success(Unit)
        }
        val preferenceServiceMock = mockk<PreferenceService> {
            coEvery { getAvailabilityStatus() } returns AvailabilityStatus.None
            coEvery { setAvailabilityStatus(any()) } just runs
        }
        val multiDeviceManagerMock = mockk<MultiDeviceManager> {
            every { isMultiDeviceActive } returns true
        }
        val taskManagerMock = mockk<TaskManager> {
            every { schedule(any<Task<*, TaskCodec>>()) } returns CompletableDeferred()
        }
        val useCase = UpdateUserAvailabilityStatusUseCase(
            dispatcherProvider = unconfinedTestDispatcherProvider(),
            preferenceService = preferenceServiceMock,
            taskManager = taskManagerMock,
            multiDeviceManager = multiDeviceManagerMock,
            workPropertiesClient = workPropertiesClientMock,
        )

        // act
        val newAvailabilityStatus = AvailabilityStatus.Unavailable(
            description = "On vacation",
        )
        val result = useCase.call(newAvailabilityStatus)

        // assert
        assertTrue { result.isSuccess }
        coVerify(exactly = 1) {
            workPropertiesClientMock.updateAvailabilityStatus(newAvailabilityStatus)
        }
        verify(exactly = 1) {
            preferenceServiceMock.setAvailabilityStatus(newAvailabilityStatus)
        }
        verify(exactly = 1) {
            @Suppress("DeferredResultUnused")
            taskManagerMock.schedule<Result<Unit>>(any())
        }
    }

    @Test
    fun `returns failure if availability status feature not supported`() = runTest {
        // precondition
        Assume.assumeFalse(BuildConfig.AVAILABILITY_STATUS_ENABLED)

        // arrange
        val workPropertiesClientMock = mockk<WorkPropertiesClient>()
        val preferenceServiceMock = mockk<PreferenceService>()
        val multiDeviceManagerMock = mockk<MultiDeviceManager>()
        val taskManagerMock = mockk<TaskManager>()
        val useCase = UpdateUserAvailabilityStatusUseCase(
            dispatcherProvider = unconfinedTestDispatcherProvider(),
            preferenceService = preferenceServiceMock,
            taskManager = taskManagerMock,
            multiDeviceManager = multiDeviceManagerMock,
            workPropertiesClient = workPropertiesClientMock,
        )

        // act
        val newAvailabilityStatus = AvailabilityStatus.Unavailable(
            description = "On vacation",
        )
        val result = useCase.call(newAvailabilityStatus)

        // assert
        assertTrue { result.isFailure }
        confirmVerified(preferenceServiceMock)
        confirmVerified(workPropertiesClientMock)
        confirmVerified(multiDeviceManagerMock)
        confirmVerified(taskManagerMock)
    }

    @Test
    fun `trims leading and trailing spaces`() = runTest {
        // precondition
        Assume.assumeTrue(BuildConfig.AVAILABILITY_STATUS_ENABLED)

        // arrange
        val workPropertiesClientMock = mockk<WorkPropertiesClient> {
            coEvery { updateAvailabilityStatus(any()) } returns Result.success(Unit)
        }
        val preferenceServiceMock = mockk<PreferenceService> {
            coEvery { getAvailabilityStatus() } returns AvailabilityStatus.None
            coEvery { setAvailabilityStatus(any()) } just runs
        }
        val multiDeviceManagerMock = mockk<MultiDeviceManager> {
            every { isMultiDeviceActive } returns false
        }
        val useCase = UpdateUserAvailabilityStatusUseCase(
            dispatcherProvider = unconfinedTestDispatcherProvider(),
            preferenceService = preferenceServiceMock,
            taskManager = mockk(),
            multiDeviceManager = multiDeviceManagerMock,
            workPropertiesClient = workPropertiesClientMock,
        )

        // act
        val newAvailabilityStatus = AvailabilityStatus.Unavailable(
            description = "   On  vacation  ",
        )
        val result = useCase.call(newAvailabilityStatus)

        // assert
        assertTrue { result.isSuccess }
        coVerify(exactly = 1) {
            workPropertiesClientMock.updateAvailabilityStatus(
                AvailabilityStatus.Unavailable(description = "On  vacation"),
            )
        }
        verify(exactly = 1) {
            preferenceServiceMock.setAvailabilityStatus(
                AvailabilityStatus.Unavailable(description = "On  vacation"),
            )
        }
    }
}
