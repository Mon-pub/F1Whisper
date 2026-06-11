package ch.threema.app

import ch.threema.app.multidevice.DesktopClientFlavor

sealed class BuildFlavor(
    val gradleName: String,
    val licenseType: LicenseType,
    val buildEnvironment: BuildEnvironment,
    private val displayName: String,
    val desktopClientFlavor: DesktopClientFlavor,
) {
    companion object {
        @JvmStatic
        val current: BuildFlavor by lazy {
            when (BuildConfig.FLAVOR) {
                None.gradleName -> None
                StoreGoogle.gradleName -> StoreGoogle
                StoreThreema.gradleName -> StoreThreema
                StoreGoogleWork.gradleName -> StoreGoogleWork
                Green.gradleName -> Green
                SandboxWork.gradleName -> SandboxWork
                OnPrem.gradleName -> OnPrem
                Blue.gradleName -> Blue
                Hms.gradleName -> Hms
                HmsWork.gradleName -> HmsWork
                Libre.gradleName -> Libre
                else -> throw IllegalStateException("Unhandled build flavor " + BuildConfig.FLAVOR)
            }
        }
    }

    enum class LicenseType {
        NONE,
        GOOGLE,
        SERIAL,
        GOOGLE_WORK,
        HMS,
        HMS_WORK,
        ONPREM,
        ;

        fun isOnPrem() =
            this == ONPREM

        fun isWork() =
            when (this) {
                GOOGLE_WORK,
                HMS_WORK,
                ONPREM,
                -> true
                else -> false
            }
    }

    enum class BuildEnvironment {
        LIVE,
        SANDBOX,
        ONPREM,
    }

    data object None : BuildFlavor(
        gradleName = "none",
        licenseType = LicenseType.NONE,
        buildEnvironment = BuildEnvironment.LIVE,
        displayName = "DEV",
        desktopClientFlavor = DesktopClientFlavor.Consumer,
    )

    data object StoreGoogle : BuildFlavor(
        gradleName = "store_google",
        licenseType = LicenseType.GOOGLE,
        buildEnvironment = BuildEnvironment.LIVE,
        displayName = "Google Play",
        desktopClientFlavor = DesktopClientFlavor.Consumer,
    )

    data object StoreThreema : BuildFlavor(
        gradleName = "store_threema",
        licenseType = LicenseType.SERIAL,
        buildEnvironment = BuildEnvironment.LIVE,
        displayName = "Threema Shop",
        desktopClientFlavor = DesktopClientFlavor.Consumer,
    )

    data object StoreGoogleWork : BuildFlavor(
        gradleName = "store_google_work",
        licenseType = LicenseType.GOOGLE_WORK,
        buildEnvironment = BuildEnvironment.LIVE,
        displayName = "Work",
        desktopClientFlavor = DesktopClientFlavor.Work,
    )

    data object Green : BuildFlavor(
        gradleName = "green",
        licenseType = LicenseType.NONE,
        buildEnvironment = BuildEnvironment.SANDBOX,
        displayName = "Green",
        desktopClientFlavor = DesktopClientFlavor.Green,
    )

    data object SandboxWork : BuildFlavor(
        gradleName = "sandbox_work",
        licenseType = LicenseType.GOOGLE_WORK,
        buildEnvironment = BuildEnvironment.SANDBOX,
        displayName = "Sandbox Work",
        desktopClientFlavor = DesktopClientFlavor.Blue,
    )

    data object OnPrem : BuildFlavor(
        gradleName = "onprem",
        licenseType = LicenseType.ONPREM,
        buildEnvironment = BuildEnvironment.ONPREM,
        displayName = "OnPrem",
        desktopClientFlavor = DesktopClientFlavor.OnPrem,
    )

    data object Blue : BuildFlavor(
        gradleName = "blue",
        licenseType = LicenseType.GOOGLE_WORK,
        buildEnvironment = BuildEnvironment.SANDBOX,
        displayName = "Blue",
        desktopClientFlavor = DesktopClientFlavor.Blue,
    )

    data object Hms : BuildFlavor(
        gradleName = "hms",
        licenseType = LicenseType.HMS,
        buildEnvironment = BuildEnvironment.LIVE,
        displayName = "HMS",
        desktopClientFlavor = DesktopClientFlavor.Consumer,
    )

    data object HmsWork : BuildFlavor(
        gradleName = "hms_work",
        licenseType = LicenseType.HMS_WORK,
        buildEnvironment = BuildEnvironment.LIVE,
        displayName = "HMS Work",
        desktopClientFlavor = DesktopClientFlavor.Work,
    )

    data object Libre : BuildFlavor(
        gradleName = "libre",
        licenseType = LicenseType.SERIAL,
        buildEnvironment = BuildEnvironment.LIVE,
        displayName = "Libre",
        desktopClientFlavor = DesktopClientFlavor.Consumer,
    )

    val fullDisplayName: String by lazy {
        displayName + if (BuildConfig.DEBUG) " (DEBUG)" else ""
    }

    /**
     * Return whether the self-updater is supported or not.
     *
     * F1Whisper: OnPrem is sideloaded (no app store), so it ships the same in-app self-updater as the
     * direct-download StoreThreema build. The latest-version advertisement (updateMessage/updateUrl)
     * is delivered by our check_license endpoint, which mirrors the latest GitHub release; the
     * downloaded APK is signature-verified by Android against the installed one (same release key).
     */
    val maySelfUpdate: Boolean
        get() = this is StoreThreema || this is OnPrem

    /**
     * Return whether this build flavor always uses Threema Push.
     *
     * F1Whisper: the self-hosted OnPrem backend has no FCM/GMS push server, so OnPrem must always
     * use the persistent "Threema Push" foreground socket on every device (GMS or not). This makes
     * FCM inert at runtime without removing the Firebase code, so it stays easy to re-enable later.
     */
    val forceThreemaPush: Boolean
        get() = this is Libre || this is OnPrem

    /**
     * Return whether this build flavor is "libre", meaning that it contains
     * no proprietary services.
     */
    val isLibre
        get() = this is Libre

    /**
     * Return whether this build flavor uses the sandbox build environment.
     */
    val isSandbox: Boolean
        get() = buildEnvironment == BuildEnvironment.SANDBOX

    val isWork: Boolean
        get() = licenseType.isWork()

    val isOnPrem: Boolean
        get() = licenseType.isOnPrem()
}
