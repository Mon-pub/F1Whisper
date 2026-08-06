package ch.threema.domain.protocol.connection

/**
 * F1Whisper: the liveness classification of a server connection.
 *
 * Only [VERIFIED_LIVE] means "this connection has been positively proven alive". Every other value
 * means "not proven alive", for a different reason. Consumers must branch on [isVerifiedLive] rather
 * than on "is it not PRESUMED_DEAD", so that a value added later fails closed instead of being
 * silently treated as healthy.
 */
enum class ConnectionLiveness {
    /**
     * The connection is LOGGEDIN and an inbound frame was observed within the awake-time staleness
     * window. This is the only value that asserts health.
     */
    VERIFIED_LIVE,

    /**
     * The connection is LOGGEDIN and inbound activity has been recorded, but the age cannot be
     * computed reliably (the awake clock appears to have gone backwards, which normally only happens
     * across a reboot). No claim is made in either direction.
     */
    UNVERIFIED,

    /**
     * The connection is LOGGEDIN but nothing has been received for longer than the awake-time
     * staleness window, so the socket is presumed dead.
     */
    PRESUMED_DEAD,

    /**
     * The connection is not LOGGEDIN but something is being done about it: a connect or a reconnect
     * is in flight. There is no established connection whose liveness could be judged yet, and none
     * is expected. This is the ordinary transient case.
     */
    NOT_APPLICABLE,

    /**
     * **The wedge.** The connection is DISCONNECTED and nothing is trying to change that: no
     * connection job is alive and no restart is in flight.
     *
     * This is a first-class named cause, not the absence of health, because it is the state the user
     * actually reported: DISCONNECTED with every network probe passing, no retry, and only a force
     * close restoring service. A report that renders this as "offline" has failed at its job.
     *
     * **NOT sufficient on its own to conclude a wedge, and a report must not present it as such.**
     * The app holds a `ConvertibleServerConnection` wrapper whose accessors read
     * `connection?.isRunning ?: false` and `connection?.connectionState ?: DISCONNECTED`, so an app
     * that has **never started** a connection (pre-unlock, no identity yet, or deliberately stopped)
     * satisfies this predicate too and is not defective. The discriminator is app-layer state and
     * deliberately lives there rather than in this pure object:
     *  - `LifetimeServiceImpl.active` true plus this value = the wedge; something was started and it
     *    gave up.
     *  - `LifetimeServiceImpl.active` false plus this value = never started or intentionally
     *    stopped, which is normal.
     *
     * Print that latch beside this verdict. This value says the app is not trying; it cannot say
     * whether it ever was, nor which latch stopped it.
     */
    DOWN_NOT_RETRYING,

    /**
     * The connection reports LOGGEDIN but carries no inbound-activity stamp at all. That is a
     * contradiction, because the stamp is seeded before the state is flipped to LOGGEDIN
     * (see [BaseServerConnection]). It means the stamping path is broken, which is strictly worse
     * than a dead socket, because every liveness answer derived from it is meaningless.
     */
    STAMPING_BROKEN,
    ;

    val isVerifiedLive: Boolean
        get() = this == VERIFIED_LIVE
}

/**
 * F1Whisper: a pure, Android-free liveness verdict for a server connection.
 *
 * ## Why this exists
 *
 * The app used to treat silence as health: the connection state alone was reported, and a connection
 * that said LOGGEDIN was believed. A diagnostics report that answers "what is the state" without
 * answering "why is it in that state" cannot tell a healthy idle connection from a wedged one, and
 * that gap cost two full investigations. This object is the single place where that judgement is
 * made, so the foreground lifecycle observer and the diagnostics report can never disagree.
 *
 * ## Why staleness is judged in awake time, not wall-clock time
 *
 * The client proves liveness with a ~60s echo request/reply heartbeat driven by
 * `kotlinx.coroutines.delay`, which rides `System.nanoTime()`. On Android that clock is the same one
 * behind `SystemClock.uptimeMillis()`, and it **halts while the device is suspended to RAM**. So in
 * a Doze window the heartbeat does not run and no inbound frame arrives, while wall-clock time keeps
 * advancing. Measured on the reporting device across 220 echo intervals: median 61s, p90 141s,
 * **max 403s**, all of them in wall-clock time. Critically, the socket was alive at the end of all
 * 34 windows that exceeded 120s. A wall-clock staleness threshold low enough to catch a real wedge
 * would therefore have torn down dozens of provably healthy connections. Awake time is the quantity
 * the heartbeat actually consumes, so it is the quantity staleness must be measured in.
 *
 * The wall-clock age is still carried in [wallClockAgeMillis] because it is useful in a report, but
 * **it is never used to reach a verdict**. Do not reintroduce a wall-clock threshold here.
 *
 * ## The `0L` rule, which is not "treat as fresh"
 *
 * [ServerConnection.getLastInboundActivityAtMillis] and
 * [ServerConnection.getLastInboundActivityAtAwakeMillis] are declared with a `= 0L` interface
 * default, so a future implementer that forgets to override them inherits `0L` silently. A verdict
 * that read `0L` as "no data yet, assume healthy" would bless exactly the wedge this object exists
 * to detect. The connection state is the discriminator:
 *
 *  - `0L` and not LOGGEDIN, then there is no connection to bless: [ConnectionLiveness.NOT_APPLICABLE].
 *    Never [ConnectionLiveness.VERIFIED_LIVE].
 *  - `0L` and LOGGEDIN, then this is a contradiction and we fail loud:
 *    [ConnectionLiveness.STAMPING_BROKEN].
 *
 * A bare `now - 0L` age is never computed. That value is roughly 56 years and merely *happens* to
 * exceed every threshold, which makes any code relying on it correct by accident rather than by
 * design.
 */
data class ConnectionLivenessVerdict(
    val liveness: ConnectionLiveness,
    /**
     * A short, English, developer-facing explanation. Contains no user data, so it is safe to put in
     * the diagnostics report.
     */
    val reason: String,
    /**
     * Age of the last inbound frame in awake time, or `null` when it is not computable (no stamp, or
     * the awake clock went backwards). This is the quantity the verdict is based on.
     */
    val awakeAgeMillis: Long?,
    /**
     * Age of the last inbound frame in wall-clock time, or `null` when it is not computable. Carried
     * for reporting only. **Never used to reach a verdict.**
     */
    val wallClockAgeMillis: Long?,
) {
    val isVerifiedLive: Boolean
        get() = liveness.isVerifiedLive

    companion object {
        /**
         * Default awake-time staleness window.
         *
         * Derivation, so nobody lowers this on a hunch. The echo cadence is
         * [ch.threema.domain.protocol.csp.ProtocolDefines.ECHO_REQUEST_INTERVAL_CSP] = 60s plus
         * [ch.threema.domain.protocol.csp.ProtocolDefines.ECHO_RESPONSE_TIMEOUT] = 10s, so a live
         * connection refreshes the stamp roughly every 60s of awake time. The worst measured
         * wall-clock gap on the reporting device was 403s, and the device was awake for at most
         * 17 to 26 percent of that window, so at most about 105s of awake time elapsed inside the
         * longest healthy gap ever observed. 150s sits above that with margin, which means **no
         * window in the measured artifact would have been flagged by this threshold**, while a real
         * wedge (no inbound at all while the device is awake and using the app) crosses it in under
         * three missed heartbeats.
         */
        const val DEFAULT_STALENESS_THRESHOLD_AWAKE_MILLIS: Long = 150_000L

        /**
         * Judge the liveness of a connection.
         *
         * Every clock reading is a parameter. This function calls no clock of its own and touches no
         * Android API, so every boundary is testable on the JVM.
         *
         * @param connectionState the current connection state
         * @param restartInFlight whether a connect or reconnect attempt is currently in flight, i.e.
         *        `ServerConnection.isRunning`. This is what separates an ordinary transient
         *        disconnection from the wedge, so it is a required parameter rather than an optional
         *        one: a caller that cannot answer it cannot distinguish the two, and the verdict
         *        would rather be told than guess.
         * @param lastInboundAtMillis wall-clock stamp of the last inbound frame, `0L` if never
         * @param lastInboundAtAwakeMillis awake-time stamp of the last inbound frame, `0L` if never
         * @param nowMillis the current wall-clock reading, from the same source as
         *        [lastInboundAtMillis]
         * @param nowAwakeMillis the current awake-time reading, from the same source as
         *        [lastInboundAtAwakeMillis]
         * @param stalenessThresholdAwakeMillis awake-time age past which a LOGGEDIN connection is
         *        presumed dead
         */
        @JvmStatic
        @JvmOverloads
        fun evaluate(
            connectionState: ConnectionState,
            restartInFlight: Boolean,
            lastInboundAtMillis: Long,
            lastInboundAtAwakeMillis: Long,
            nowMillis: Long,
            nowAwakeMillis: Long,
            stalenessThresholdAwakeMillis: Long = DEFAULT_STALENESS_THRESHOLD_AWAKE_MILLIS,
        ): ConnectionLivenessVerdict {
            val hasStamp = lastInboundAtMillis != 0L

            if (connectionState == ConnectionState.DISCONNECTED && !restartInFlight) {
                // The wedge, named. DISCONNECTED with nothing in flight means the app has stopped
                // trying, which no amount of working network will fix. Reported as its own cause so
                // a report cannot render it as ordinary offline.
                return ConnectionLivenessVerdict(
                    liveness = ConnectionLiveness.DOWN_NOT_RETRYING,
                    reason = "DISCONNECTED and no connection attempt is in flight; the app has " +
                        "stopped trying to reconnect",
                    awakeAgeMillis = null,
                    wallClockAgeMillis = null,
                )
            }

            if (connectionState != ConnectionState.LOGGEDIN) {
                // Not established yet, but something is being done about it. Ordinary transient case.
                return ConnectionLivenessVerdict(
                    liveness = ConnectionLiveness.NOT_APPLICABLE,
                    reason = "connection is $connectionState with an attempt in flight; " +
                        "liveness is not applicable yet",
                    awakeAgeMillis = null,
                    wallClockAgeMillis = null,
                )
            }

            if (!hasStamp || lastInboundAtAwakeMillis == 0L) {
                // LOGGEDIN without a stamp is impossible if the stamping path works:
                // BaseServerConnection seeds both stamps before flipping the state to LOGGEDIN, and
                // the happens-before edge is guaranteed twice over (the state field is @Volatile and
                // is also written under a lock, while the stamps are AtomicLongs). Observing this
                // therefore means the stamping path is broken, not that the socket is slow.
                return ConnectionLivenessVerdict(
                    liveness = ConnectionLiveness.STAMPING_BROKEN,
                    reason = "LOGGEDIN but no inbound-activity stamp recorded; the stamping path is broken",
                    awakeAgeMillis = null,
                    wallClockAgeMillis = null,
                )
            }

            val awakeAge = nowAwakeMillis - lastInboundAtAwakeMillis
            // Wall-clock can step backwards (an NTP correction), so a negative age is reported as
            // unknown rather than as a huge or negative number. It never influences the verdict.
            val wallClockAge = (nowMillis - lastInboundAtMillis).takeIf { it >= 0 }

            if (awakeAge < 0) {
                // The awake clock is monotonic within a boot, so this should be unreachable in a
                // single process. Degrade to "cannot tell" rather than inventing an answer.
                return ConnectionLivenessVerdict(
                    liveness = ConnectionLiveness.UNVERIFIED,
                    reason = "awake clock went backwards by ${-awakeAge} ms; staleness cannot be judged",
                    awakeAgeMillis = null,
                    wallClockAgeMillis = wallClockAge,
                )
            }

            return if (awakeAge > stalenessThresholdAwakeMillis) {
                ConnectionLivenessVerdict(
                    liveness = ConnectionLiveness.PRESUMED_DEAD,
                    reason = "LOGGEDIN but no inbound for $awakeAge ms of awake time " +
                        "(> $stalenessThresholdAwakeMillis ms); socket presumed dead",
                    awakeAgeMillis = awakeAge,
                    wallClockAgeMillis = wallClockAge,
                )
            } else {
                ConnectionLivenessVerdict(
                    liveness = ConnectionLiveness.VERIFIED_LIVE,
                    reason = "inbound $awakeAge ms ago in awake time " +
                        "(<= $stalenessThresholdAwakeMillis ms)",
                    awakeAgeMillis = awakeAge,
                    wallClockAgeMillis = wallClockAge,
                )
            }
        }
    }
}
