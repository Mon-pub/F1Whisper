package ch.threema.app.services.messageplayer

import java.util.concurrent.ConcurrentHashMap

/**
 * F1Whisper (eleventh fork review, F11-05): which listen-once messages have a burn IN FLIGHT in this process.
 *
 * **The window this closes.** When a session with audible playback ends, the burn is queued on a worker and playback
 * ownership is released immediately. In the fail-open case - the durable claim write failed, so playback proceeded
 * without a claim on the row - the row still reads playable until the worker's consumed-metadata write lands. In that
 * window nothing refused a second playback: the durable gate said playable, no owner was registered, and the same
 * player's token is deliberately re-entrant. If the burn write itself failed too, the window stayed open
 * indefinitely, and one incoming listen-once recording could be played again and again in the same process.
 *
 * The barrier is raised synchronously by [ListenOnceEnforcer.burn] BEFORE the worker hop, so there is no instant at
 * which a settling burn is invisible, and it is cleared only once the burn's settlement is durably decided. A FAILED
 * settlement retains the barrier, and [AudioMessagePlayer.open] both refuses while it is up and re-drives the burn,
 * which is the retry that eventually clears it.
 *
 * F1Whisper (twelfth fork review, F12-01): keyed by [ListenOnceMessageIdentity], never by the table-local integer
 * row id. Contact, group and distribution-list messages have separate AUTOINCREMENT sequences, so equal integer ids
 * across the tables are normal; an integer-keyed barrier refused - and then BURNED - whichever model happened to
 * share the number, which destroyed unrelated audio messages.
 *
 * Deliberately NOT durable, same as [ListenOnceOwnership] and for the same reason: it describes work in flight in
 * THIS process. Process death empties it, and with it dies the fail-open session whose burn it was guarding; the
 * durable claim, when it was written, still governs what a new process may do.
 */
object ListenOnceBurnBarrier {
    private val settling = ConcurrentHashMap.newKeySet<ListenOnceMessageIdentity>()

    /** Mark [identity]'s burn as in flight. Idempotent. */
    @JvmStatic
    fun raise(identity: ListenOnceMessageIdentity) {
        settling.add(identity)
    }

    /** The burn's durable settlement is decided; playback admission may again be decided from the row. */
    @JvmStatic
    fun clear(identity: ListenOnceMessageIdentity) {
        settling.remove(identity)
    }

    /**
     * Whether a burn for [identity] is queued, running, or has FAILED durably and awaits its retry. While true, no
     * plaintext of this message may be released and the bubble presents it as spent.
     */
    @JvmStatic
    fun isSettling(identity: ListenOnceMessageIdentity): Boolean = settling.contains(identity)

    /** Test seam: forget every settling burn, as a process death would. */
    @JvmStatic
    fun forgetAll() {
        settling.clear()
    }
}
