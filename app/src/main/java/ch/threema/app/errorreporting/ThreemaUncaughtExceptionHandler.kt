package ch.threema.app.errorreporting

import android.content.Context
import ch.threema.app.BuildConfig
import ch.threema.base.utils.getThreemaLogger
import ch.threema.common.TimeProvider
import ch.threema.common.UUIDGenerator

private val logger = getThreemaLogger("ThreemaUncaughtExceptionHandler")

class ThreemaUncaughtExceptionHandler(
    appContext: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler(),
    // F1Whisper: injectable only so a test can make the reporting step fail and prove the delegation below
    // still happens. Production always uses the default.
    private val reportUnhandledException: (Throwable) -> Unit = { e ->
        @Suppress("KotlinConstantConditions", "SimplifyBooleanWithConstants")
        if (BuildConfig.ERROR_REPORTING_SUPPORTED && BuildConfig.SENTRY_PUBLIC_API_KEY.isNotEmpty()) {
            storeExceptionForErrorReporting(appContext, e)
        }
    },
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        // F1Whisper: the delegation below is what actually ENDS the process - `defaultHandler` is the
        // platform's RuntimeInit.KillApplicationHandler, which kills in a finally block. It must run
        // even if our own logging or error storage fails, hence the `finally`. A handler that returns
        // without terminating leaves the process alive with a dead main looper: ART detaches the main
        // thread, enters JII::DestroyJavaVM and blocks in ThreadList::WaitForOtherNonDaemonThreadsToExit,
        // so the app becomes a zombie with a frozen UI and a live network stack instead of restarting. Worse,
        // the failure would be silent - Thread::HandleUncaughtExceptions discards an exception thrown
        // by the handler itself. Keep the termination guarantee explicit, not incidental.
        try {
            logger.error("Uncaught exception", e)
            reportUnhandledException(e)
        } finally {
            defaultHandler?.uncaughtException(t, e)
        }
    }
}

private fun storeExceptionForErrorReporting(appContext: Context, e: Throwable) {
    // Intentionally not using Koin here, as it might not be initialized yet at this point
    ErrorRecordStore(
        recordsDirectory = ErrorRecordStore.getRecordsDirectory(appContext),
        timeProvider = TimeProvider.default,
        uuidGenerator = UUIDGenerator.default,
    )
        .storeErrorForUnhandledException(e)
}
