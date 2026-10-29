package ch.threema.app.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper: two-peer convergence regression tests for the 1:1 disappearing timer.
 *
 * Each peer is a tiny in-test holder of the shared conversation timer, driven purely through
 * [DisappearingTimerConvergence]. No Android, no mocks, no `ServiceManager`, so the whole state machine is exercised on the
 * JVM.
 *
 * The sequences replay two defects reported from paired peer debug logs: the original 2026-07-29 report (peer1 `12:56:42`
 * OFF followed by `12:57:11` "mirroring peer timer 30s", plus the premature deletions at `peer2/debug_log.txt:3187` and
 * `:4425`), and Finding 2 from the re-test on the LWW build (`/tmp/lww/p1` `debug_log.txt:2579`).
 *
 * Design: `.claude/tasks/disappearing-timer-1to1-shared-lww.md`.
 */
class DisappearingTimerConvergenceScenarioTest {

    /**
     * One side of a 1:1 conversation.
     *
     * [shared] mirrors `ContactModel.disappearingMessagesTimerSeconds`: the ONE conversation timer (null = OFF) that
     * governs both freeze directions and backs the picker. There is no second field — `peerDisappearingTimerSeconds` is
     * dead, and modelling it here would misrepresent what the receive path actually does.
     */
    private class Peer(private val name: String) {
        var shared: Int? = null
            private set

        /**
         * The user moves the picker (mirrors `DisappearingMessageService.setConversationTimer`).
         * Returns the wire value that goes out in the `0x85`, which is the picker's raw value (0 = OFF).
         */
        fun setTimerLocally(seconds: Int): Int {
            shared = DisappearingTimerConvergence.toSharedField(seconds)
            return seconds
        }

        /**
         * The user sends a content message. This puts **nothing** on the wire: the piggyback re-assert was deleted, so the
         * only `0x85` this client emits is a genuine user change via [setTimerLocally].
         */
        fun sendContentMessage(): Int? = null

        /**
         * A duplicate `0x85` carrying the CURRENT shared timer, as still seen on the wire from an un-updated v6.4.3-37
         * peer's 5-minute re-assert or an at-least-once redelivery of a control message. This client no longer produces
         * one (see [sendContentMessage]); this models the other side. Returns null when the timer is off, matching the old
         * re-assert's bail-out.
         */
        fun duplicateAdvertisement(): Int? = shared?.takeIf { it > 0 }

        /**
         * Receive a `0x85` (mirrors `IncomingDisappearingTimerTask`). The adopt is UNCONDITIONAL; the returned flag is
         * whether a status row is inserted, which asks the separate question of whether this conversation's timer moved.
         */
        fun receiveAdvertisement(advertised: Int): Boolean {
            val statusRow = DisappearingTimerConvergence.changesSharedTimer(shared, advertised)
            shared = DisappearingTimerConvergence.toSharedField(advertised)
            return statusRow
        }

        /** The timer that governs BOTH freeze directions for this conversation. */
        fun governingTimer(): Int? = DisappearingTimerConvergence.governingTimerSeconds(shared)

        override fun toString(): String = "$name(shared=$shared)"
    }

    /**
     * What `ContactMessageReceiver.createLocalModel` effectively stamps onto every model it builds: it passes the raw
     * shared field to `DisappearingMessageService.freezeTimer`, which no-ops on null or a non-positive value. Written out
     * independently here so [inboundAndOutboundFreezeAgree] compares two real implementations rather than itself.
     */
    private fun stampedByCreateLocalModel(sharedField: Int?): Int? =
        if (sharedField == null || sharedField <= 0) null else sharedField

    /**
     * FINDING 2 — THE REPORTED BUG, replayed from the two-phone logs verbatim.
     *
     * User's words: *"when one changes the timer to off and it was not the setter it fails."* The build that gated the
     * adopt on "does this differ from what the peer last advertised?" dropped p2's genuine OFF, because p2's state had
     * moved 30 → OFF **without advertising in between** — it reached 30 by ADOPTING p1's 30, so its `0` merely coincided
     * with the `0` it had itself advertised two minutes earlier. Log proof, `p1/debug_log.txt:2579`:
     * `0s (previously advertised 0, shared was 30), genuineChange=false ... shared timer now 30`.
     *
     * With the adopt unconditional, the same four steps converge.
     */
    @Test
    fun peerOffConvergesAfterThePeerSilentlyAdoptedOurValue() {
        val p1 = Peer("p1")
        val p2 = Peer("p2")

        // 05:48:13 — p1 receives OFF from p2 and adopts it.
        p1.receiveAdvertisement(p2.setTimerLocally(0))
        assertNull(p1.shared, "p1 must be OFF after adopting p2's OFF, state was $p1")

        // 05:48:36 — p1's user sets 30. p2 adopts it; adopting never advertises, so p2 goes silent at 30.
        val statusRowForThirty = p2.receiveAdvertisement(p1.setTimerLocally(30))
        assertTrue(statusRowForThirty, "p2's timer moved off -> 30, so it must show a status row, state was $p2")
        assertEquals(30, p1.shared, "precondition: p1 holds 30, state was $p1")
        assertEquals(30, p2.shared, "precondition: p2 silently adopted 30, state was $p2")

        // 05:49:03 — p2's user turns the timer OFF. This is a genuine 30 -> OFF change and must land on p1.
        val statusRowForOff = p1.receiveAdvertisement(p2.setTimerLocally(0))
        assertTrue(statusRowForOff, "p1's timer moved 30 -> off, so it must show a status row, state was $p1")
        assertNull(p1.shared, "expected p1 to converge to OFF, but its shared timer was ${p1.shared}")
        assertNull(p1.governingTimer(), "expected p1 to have no governing timer, but it was ${p1.governingTimer()}")

        // 05:49:49 — the at-least-once redelivery of the same 0x85(0). Adopting is a no-op; no second status row.
        assertFalse(p1.receiveAdvertisement(0), "a redelivered OFF must not print a second status row, state was $p1")
        assertNull(p1.shared, "expected p1 to still be OFF after the redelivery, but it was ${p1.shared}")
    }

    /** OFF must win under last-writer-wins: A holds 30, B genuinely turns OFF, A adopts null on the next `0x85(0)`. */
    @Test
    fun genuinePeerOffTurnsUsOff() {
        val a = Peer("A")
        val b = Peer("B")

        b.receiveAdvertisement(a.setTimerLocally(30))
        assertEquals(30, b.shared, "B must have adopted A's 30s, state was $b")

        assertTrue(a.receiveAdvertisement(b.setTimerLocally(0)), "A must show a status row for B turning the timer off")
        assertNull(a.shared, "expected A's shared timer to be OFF after B turned it off, but it was ${a.shared}")
        assertNull(a.governingTimer(), "expected A to have no governing timer, but it was ${a.governingTimer()}")
        assertNull(b.shared, "expected B's shared timer to be OFF, but it was ${b.shared}")
    }

    /**
     * A peer that has never advertised anything turns OFF: it must converge, exactly like any other value. This was the
     * case the first gated build got wrong in the other direction — it read "never heard from" as "advertised OFF" and
     * dropped the change permanently, since nothing ever retried an OFF.
     */
    @Test
    fun peerFirstEverOffTurnsUsOff() {
        val a = Peer("A")
        val b = Peer("B")

        b.receiveAdvertisement(a.setTimerLocally(30))
        assertEquals(30, a.shared, "precondition: A holds 30, state was $a")
        assertEquals(30, b.shared, "precondition: B adopted 30, state was $b")

        assertTrue(
            a.receiveAdvertisement(b.setTimerLocally(0)),
            "A must show a status row: its timer really did move from 30 to off, state was $a",
        )
        assertNull(a.shared, "expected A to converge to OFF, but its shared timer was ${a.shared}")
        assertNull(b.shared, "expected B to be OFF, but its shared timer was ${b.shared}")
    }

    /**
     * FINDING 1 REGRESSION — a merely ADOPTED value must never go back on the wire.
     *
     * With the adopt unconditional, an echo of an adopted value is indistinguishable from a genuine change and destroys
     * the other side's OFF outright. That is not a receive-side bug to filter — it is why the piggyback re-assert had to
     * be deleted rather than gated. The first half replays the harm on a scratch pair, so this is a real guard rather than
     * a tautology; the second half asserts that sending messages now puts nothing on the wire, which is what makes the
     * harm unreachable.
     */
    @Test
    fun adoptedValueIsNeverReAdvertised() {
        // --- the harm: feeding an adopted value back destroys the OFF ---
        run {
            val a = Peer("A-scratch")
            val b = Peer("B-scratch")
            b.receiveAdvertisement(a.setTimerLocally(30))
            a.setTimerLocally(0)
            assertNull(a.shared, "scratch precondition: A is OFF, state was $a")

            val echoOfAnAdoptedValue = b.shared!! // exactly what the deleted piggyback would have put on the wire
            a.receiveAdvertisement(echoOfAnAdoptedValue)
            assertEquals(30, a.shared, "the defect: A's OFF is destroyed by an echo of a value neither user chose")
        }

        // --- the fix: B sending messages emits nothing, so that echo never exists ---
        val a = Peer("A")
        val b = Peer("B")
        b.receiveAdvertisement(a.setTimerLocally(30))
        assertEquals(30, b.shared, "B must have adopted A's 30 without ever choosing it, state was $b")

        a.setTimerLocally(0)
        assertNull(a.shared, "A's shared timer must be OFF after the local change, state was $a")

        repeat(3) { n ->
            assertNull(
                b.sendContentMessage(),
                "sending message ${n + 1} must not put B's adopted timer on the wire, state was $b",
            )
        }

        assertNull(a.shared, "expected A to stay OFF, but its shared timer was ${a.shared}")
        assertNull(a.governingTimer(), "expected A to have no governing timer, but it was ${a.governingTimer()}")
    }

    /**
     * THE ACCEPTED RESIDUAL, asserted so the trade-off is visible rather than assumed: an un-updated v6.4.3-37 peer keeps
     * piggybacking its unchanged timer every 5 minutes, and under unconditional adoption that value IS re-injected — a
     * local OFF is overwritten until the peer updates.
     *
     * This is deliberate. The gate that suppressed it needed to tell a stale re-assert from a genuine change, which needs
     * a logical clock the frozen 4-byte `0x85` body cannot carry; the attempt cost real changes on device
     * ([peerOffConvergesAfterThePeerSilentlyAdoptedOurValue]). It is the same residual groups have accepted since
     * v6.4.3-29, and it is what the server-side mandatory-update floor exists to close.
     */
    @Test
    fun unupdatedPeerReassertIsReInjected() {
        val b = Peer("B")
        val unupdatedPeerTimer = 30

        b.receiveAdvertisement(unupdatedPeerTimer)
        b.setTimerLocally(0)
        assertNull(b.shared, "B's shared timer must be OFF right after the local change, state was $b")

        assertTrue(
            b.receiveAdvertisement(unupdatedPeerTimer),
            "the re-assert moves B's timer off -> 30, so it does print a status row, state was $b",
        )
        assertEquals(
            unupdatedPeerTimer,
            b.shared,
            "ACCEPTED RESIDUAL: an un-updated peer's re-assert re-injects its timer until that peer updates. If this " +
                "assertion is what fails, the receive path grew an adopt gate again — re-read Finding 2 first.",
        )
    }

    /** A plain value change by the peer propagates: 30 -> 300. */
    @Test
    fun genuinePeerChangeIsAdopted() {
        val a = Peer("A")
        val b = Peer("B")

        b.receiveAdvertisement(a.setTimerLocally(30))
        assertEquals(30, b.shared, "B must have adopted 30s, state was $b")

        assertTrue(b.receiveAdvertisement(a.setTimerLocally(300)), "A's 30 -> 300 must print a status row, state was $b")
        assertEquals(300, b.shared, "expected B's shared timer to be 300, but it was ${b.shared}")
        assertEquals(300, b.governingTimer(), "expected B's governing timer to be 300, but it was ${b.governingTimer()}")
    }

    /**
     * From (30, 30) both sides turn OFF, interleaved with an un-updated peer's stale re-asserts. The TERMINAL state must
     * be (null, null) whatever the interleaving — four orderings, including the exact flip-flop from the supplied logs (a
     * stale re-assert of 30 arriving AFTER the local OFF).
     *
     * Only the terminal state is asserted. A stale re-assert can transiently resurrect a timer (see
     * [unupdatedPeerReassertIsReInjected]); what must hold is that the last genuine change still wins.
     */
    @Test
    fun bothPeersConvergeFromMutualOff() {
        // Ordering 1: A off, then B off.
        pairAtThirtySeconds().let { (a, b) ->
            b.receiveAdvertisement(a.setTimerLocally(0))
            a.receiveAdvertisement(b.setTimerLocally(0))
            assertConvergedOff(a, b, "ordering 1 (A off, then B off)")
        }

        // Ordering 2: a duplicate advertisement of the unchanged 30 lands first, then B off, then A off.
        pairAtThirtySeconds().let { (a, b) ->
            b.receiveAdvertisement(a.duplicateAdvertisement()!!)
            a.receiveAdvertisement(b.setTimerLocally(0))
            b.receiveAdvertisement(a.setTimerLocally(0))
            assertConvergedOff(a, b, "ordering 2 (A re-assert, B off, A off)")
        }

        // Ordering 3: B off first, then A off.
        pairAtThirtySeconds().let { (a, b) ->
            a.receiveAdvertisement(b.setTimerLocally(0))
            b.receiveAdvertisement(a.setTimerLocally(0))
            assertConvergedOff(a, b, "ordering 3 (B off, then A off)")
        }

        // Ordering 4: the reported flip-flop — B off, then a stale in-flight duplicate of A's 30 lands, then A off.
        pairAtThirtySeconds().let { (a, b) ->
            val staleReassert = a.duplicateAdvertisement()!!
            a.receiveAdvertisement(b.setTimerLocally(0))
            b.receiveAdvertisement(staleReassert)
            b.receiveAdvertisement(a.setTimerLocally(0))
            assertConvergedOff(a, b, "ordering 4 (B off, stale 30 re-assert, A off)")
        }
    }

    /**
     * A fresh contact whose peer has never advertised anything sends OFF: the advertisement is adopted like any other, but
     * the conversation's timer was already off, so no status row appears out of nowhere. This is why the status row is
     * gated on the shared field rather than printed on every receive.
     */
    @Test
    fun firstEverOffAdvertisementIsAdoptedButShowsNoStatusRow() {
        val fresh = Peer("fresh")

        assertFalse(fresh.receiveAdvertisement(0), "no status row may appear when the timer was already off, state was $fresh")
        assertNull(fresh.shared, "expected the fresh contact's timer to stay OFF, but it was ${fresh.shared}")
    }

    /**
     * Recovery when the original `0x85` never landed (lost to an FS reject, a reinstall or a Safe restore). The client no
     * longer re-asserts anything, so recovery comes from the control message being durable and server-queued with
     * at-least-once delivery — a redelivery must still be applied, which unconditional adoption guarantees.
     */
    @Test
    fun recoveryAfterMissedControlMessage() {
        val recipient = Peer("recipient")

        assertNull(recipient.shared, "precondition: the original 0x85 was never applied, state was $recipient")
        assertTrue(
            recipient.receiveAdvertisement(30),
            "the redelivered 0x85 moves the timer off -> 30, so it prints a status row, state was $recipient",
        )
        assertEquals(30, recipient.shared, "expected the recipient to recover to 30, but it was ${recipient.shared}")
    }

    /**
     * REGRESSION TEST FOR DEFECT B — inbound and outbound freeze read the same conversation timer, so an incoming message
     * can never be frozen at a value the sender did not advertise.
     *
     * Log proof of the old harm (peer2 = 8SHLADT0): the peer advertised OFF, yet peer2 started 30s countdowns on messages
     * received in exactly those windows — `peer2/debug_log.txt:3187` (`12:57:57 timer=30s`) and `:4425`
     * (`12:59:28 timer=30s`). The 30 was peer2's OWN local timer, stamped by `createLocalModel`, because the E1 receive
     * freeze that should have applied the peer's value was guarded by `== null` and could never fire. Messages were
     * hard-deleted against the sender's stated policy.
     */
    @Test
    fun inboundAndOutboundFreezeAgree() {
        listOf(null, 0, 30).forEach { shared ->
            val outbound = stampedByCreateLocalModel(shared)
            val inbound = DisappearingTimerConvergence.governingTimerSeconds(shared)
            assertEquals(
                outbound,
                inbound,
                "shared=$shared: the inbound freeze lookup returned $inbound but createLocalModel stamps $outbound — " +
                    "the two freeze directions must never diverge",
            )
        }
    }

    // ---- helpers ----

    /** A converged pair at 30s, each side having advertised 30 to the other. */
    private fun pairAtThirtySeconds(): Pair<Peer, Peer> {
        val a = Peer("A")
        val b = Peer("B")
        b.receiveAdvertisement(a.setTimerLocally(30))
        a.receiveAdvertisement(b.setTimerLocally(30))
        assertEquals(30, a.shared, "precondition: A must hold 30, state was $a")
        assertEquals(30, b.shared, "precondition: B must hold 30, state was $b")
        return a to b
    }

    private fun assertConvergedOff(a: Peer, b: Peer, ordering: String) {
        assertNull(a.shared, "$ordering: expected A's shared timer to be OFF, but it was ${a.shared}")
        assertNull(b.shared, "$ordering: expected B's shared timer to be OFF, but it was ${b.shared}")
        assertNull(a.governingTimer(), "$ordering: expected A to have no governing timer, but it was ${a.governingTimer()}")
        assertNull(b.governingTimer(), "$ordering: expected B to have no governing timer, but it was ${b.governingTimer()}")
    }
}
