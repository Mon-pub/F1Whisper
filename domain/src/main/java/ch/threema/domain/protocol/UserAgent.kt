package ch.threema.domain.protocol

private const val PRODUCT = "F1Whisper-Android"

/**
 * App version used by the no-argument [getUserAgent] overload.
 *
 * The `domain` module is a plain JVM library with no `BuildConfig`, so the app version cannot be
 * read here directly. It is threaded in once early in app startup via [setUserAgentVersion] (from
 * the same [ch.threema.domain.models.AppVersion] that `Version.versionString` is normally built
 * from), so the version-less provisioning/Safe paths that call [getUserAgent] still emit a
 * versioned User-Agent instead of a bare product token.
 */
@Volatile
private var appVersion: Version? = null

/**
 * Provide the app version for the no-argument [getUserAgent] overload. Must be called once early in
 * app startup so no request ever emits a bare, version-less product token.
 */
fun setUserAgentVersion(version: Version) {
    appVersion = version
}

fun getUserAgent(): String =
    appVersion?.let { "$PRODUCT/${it.userAgentVersionString()}" } ?: PRODUCT

fun getUserAgent(version: Version): String = "$PRODUCT/${version.userAgentVersionString()}"

/**
 * The version string used in the HTTP `User-Agent`, with the single trailing build-flavor letter
 * (the platform code, e.g. the `A` in `6.4.3o-37A`) stripped. The server's UA parser tolerates the
 * letter for legacy compatibility, but new emissions follow the frozen schema and keep the header
 * clean; the letter still belongs in the JSON `version` body fields elsewhere in the client.
 */
private fun Version.userAgentVersionString(): String {
    val versionString = this.versionString
    return if (versionString.isNotEmpty() && versionString.last().isLetter()) {
        versionString.dropLast(1)
    } else {
        versionString
    }
}
