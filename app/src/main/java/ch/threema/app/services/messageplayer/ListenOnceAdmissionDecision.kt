package ch.threema.app.services.messageplayer

/**
 * F1Whisper (twelfth fork review, F12-01): how `AudioMessagePlayer.open` routes a decrypted file, in one pure
 * decision so the collision-safety of the routing is executable on the JVM (the player itself needs a device).
 *
 * The order of the terms IS the fix:
 *
 * 1. **Applicability first.** A model that is not an incoming listen-once voice message
 *    ([ListenOnceGate.NOT_APPLICABLE]) is ADMITTED without the settling barrier ever being able to refuse it - let
 *    alone re-drive a burn against it. Under the old integer keying the barrier was consulted before any
 *    applicability check, so an ordinary audio message whose table-local id collided with a settling listen-once
 *    message in ANOTHER table was refused and then burned: consumed metadata written to its own row, its own media
 *    deleted. Identity keying ([ListenOnceMessageIdentity]) makes such a hit impossible; this ordering is the belt
 *    that keeps even a polluted registry from destroying an unrelated message.
 * 2. **The settling barrier before the durable gate.** The barrier exists precisely for the window in which the
 *    durable row is wrong: a fail-open session played without a written claim and its burn is still in flight or
 *    failed. So for an applicable message, settling dominates whatever the row says.
 * 3. **The durable gate.** Refused gates refuse; a playable unclaimed gate must claim before plaintext is released.
 */
enum class ListenOnceAdmission {
    /** Release the plaintext to the player; no listen-once restriction stands. */
    ADMIT,

    /** Refuse: this exact message's burn is settling (or failed and awaits retry). The caller re-drives the burn. */
    REFUSE_SETTLING,

    /** Refuse: the durable row refuses playback (claimed, consumed, or otherwise spent). */
    REFUSE_SPENT,

    /** Playable, but the durable claim must be written before the plaintext is released. */
    CLAIM_BEFORE_RELEASE,
}

object ListenOnceAdmissionDecision {

    /**
     * Route an open() for a message whose gate is [gate] while [burnSettling] says whether a burn for THIS message's
     * own identity is in flight in this process.
     */
    @JvmStatic
    fun decide(gate: ListenOnceGate, burnSettling: Boolean): ListenOnceAdmission {
        if (gate == ListenOnceGate.NOT_APPLICABLE) {
            // Not an incoming listen-once message: nothing here may refuse it and nothing here may burn it. A
            // settling hit for its identity would mean the registry was polluted, and the safe answer is still to
            // leave the unrelated message alone.
            return ListenOnceAdmission.ADMIT
        }
        if (burnSettling) {
            return ListenOnceAdmission.REFUSE_SETTLING
        }
        if (ListenOnceDecision.isPlaybackRefused(gate)) {
            return ListenOnceAdmission.REFUSE_SPENT
        }
        if (ListenOnceDecision.needsClaimBeforeRelease(gate)) {
            return ListenOnceAdmission.CLAIM_BEFORE_RELEASE
        }
        return ListenOnceAdmission.ADMIT
    }
}
