package ch.threema.domain.protocol.api

/**
 * F1Whisper: a tiny module-boundary-safe sink for server-time samples harvested from the HTTP
 * `Date:` response header of our cert-pinned OnPrem API client ([HttpRequester]).
 *
 * The `domain/` Gradle module cannot reference `app/` classes (app depends on domain, not the
 * reverse), but the only trustworthy harvest point lives in [HttpRequester] (domain). This object
 * decouples the two: [HttpRequester] calls [report]; the app-side `TrustedClock` registers itself
 * as the [sink] at startup and consumes the samples.
 *
 * SECURITY: only the cert-pinned OnPrem client ([HttpRequester]) ever calls [report]. The unpinned
 * link-preview fetcher (which talks to arbitrary, attacker-controlled URLs) must NEVER feed this —
 * trusting an attacker's `Date:` header would be a trivial clock-poisoning attack.
 */
object ServerTimeReporter {
    /**
     * Set by the app layer at startup (see `TrustedClock`). Receives:
     * - `serverEpochMs`: the server's UTC time parsed from the response `Date:` header.
     * - `systemReceiveMs`: the device's `System.currentTimeMillis()` at response receipt.
     */
    @Volatile
    @JvmStatic
    var sink: ((serverEpochMs: Long, systemReceiveMs: Long) -> Unit)? = null

    /**
     * Called by [HttpRequester] after a successful response from our pinned OnPrem server.
     * Never throws (a bad sink must not break the HTTP path).
     */
    @JvmStatic
    fun report(serverEpochMs: Long, systemReceiveMs: Long) {
        try {
            sink?.invoke(serverEpochMs, systemReceiveMs)
        } catch (_: Throwable) {
            // Deliberately swallow: time harvesting must never affect the request path.
        }
    }
}
