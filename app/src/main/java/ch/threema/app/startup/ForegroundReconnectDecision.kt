package ch.threema.app.startup

import ch.threema.domain.protocol.connection.ConnectionLiveness

/**
 * F1Whisper: whether a foreground resume should ask for a reconnect, given the connection's liveness
 * verdict.
 *
 * ## Why this is an explicit enumeration and not `!isVerifiedLive`
 *
 * `!verdict.isVerifiedLive` is the right guard for anything that must not *bless* an unhealthy
 * connection, and that is how the diagnostics report treats it. It is the **wrong** guard here,
 * because this decision does not bless anything, it triggers a teardown and reconnect. Two of the
 * non-live outcomes mean "something is already happening":
 *
 *  - [ConnectionLiveness.NOT_APPLICABLE] covers `CONNECTING`, `CONNECTED`, and `DISCONNECTED` with a
 *    restart already in flight;
 *  - [ConnectionLiveness.UNVERIFIED] means liveness could not be confirmed yet, not that it failed.
 *
 * Reconnecting on either would interrupt a connection that is mid-handshake or mid-backoff on every
 * single foreground event. That is precisely the defect this whole change exists to remove: the
 * shipped v6.4.3-35 check tore down provably healthy connections because it acted on a signal that
 * did not mean what it assumed. Acting on "cannot confirm" is the same mistake in a new costume.
 *
 * The asymmetry is deliberate. Reconnecting when we should not have is a regression that harms every
 * user on every resume; failing to reconnect when we could have leaves the existing wedge in place
 * until the next resume, network change, or `ensureConnection()`. The first is much worse, so this
 * errs towards inaction.
 *
 * ## Why `when` with no `else`
 *
 * The `when` below is exhaustive over [ConnectionLiveness] and deliberately has **no `else`**, so
 * adding a value to that enum is a **compile error here** rather than silently inheriting either
 * default. Neither fail-open nor fail-closed is right for every future outcome; a human has to
 * decide. This is a stronger guard than either.
 */
object ForegroundReconnectDecision {

    fun shouldRequestReconnect(liveness: ConnectionLiveness): Boolean = when (liveness) {
        // Healthy. Inbound traffic was observed within the awake-time budget.
        ConnectionLiveness.VERIFIED_LIVE -> false

        // CONNECTING / CONNECTED, or DISCONNECTED with a restart already in flight. Something is
        // already in progress; interrupting it is the v6.4.3-35 defect.
        ConnectionLiveness.NOT_APPLICABLE -> false

        // Liveness could not be confirmed, which is not the same as having failed. Wait rather than
        // tear down a connection that may be perfectly fine.
        ConnectionLiveness.UNVERIFIED -> false

        // LOGGEDIN but no inbound within the awake-time staleness budget: the socket is dead at the
        // far end and only a reconnect will notice.
        ConnectionLiveness.PRESUMED_DEAD -> true

        // The reported wedge: DISCONNECTED with nothing retrying. The old check gated on LOGGEDIN and
        // was structurally blind to exactly this.
        ConnectionLiveness.DOWN_NOT_RETRYING -> true

        // LOGGEDIN with no inbound stamp at all is a contradiction: the stamping path is broken, so
        // no liveness judgement can be trusted. Fail loud and re-establish, which re-seeds the stamp.
        ConnectionLiveness.STAMPING_BROKEN -> true
    }
}
