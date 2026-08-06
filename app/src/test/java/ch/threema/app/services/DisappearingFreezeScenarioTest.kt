package ch.threema.app.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * F1Whisper: end-to-end regression tests for the per-message disappearing timer, replaying the three reported scenarios
 * against the pure objects.
 *
 * Each side is a tiny in-test holder driven purely through [DisappearingFreezeDecision]: the sender advertises the timer
 * frozen on its own outgoing model, the message carries that value, and the recipient resolves what to freeze. No Android,
 * no mocks, no `ServiceManager`, so the whole decision runs on the JVM.
 *
 * The defect these replay: before this wave the recipient froze at ITS OWN conversation timer, read from its local
 * database at receive time, so a recipient could retroactively (and undetectably) cancel the sender's policy. Evidence in
 * `/tmp/oob/p2/debug_log.txt` — three texts froze at 300 s while `9d3d8a86491c8ac8` (line 4520), processed in the same
 * 06:46:24 burst immediately after the `0x85` OFF at lines 4404-4407, got no countdown at all.
 *
 * Design: `.claude/tasks/disappearing-per-message-timer-metadata.md`.
 */
class DisappearingFreezeScenarioTest {

    /**
     * One side of a conversation.
     *
     * [shared] mirrors `ContactModel.disappearingMessagesTimerSeconds` / `GroupModelOld`'s equivalent: the conversation
     * SETTING (null = off). It still decides what this side advertises on the messages it sends, and it is still the
     * fallback for a message from a peer that advertises nothing — but it no longer decides what an advertised incoming
     * message freezes at. That separation is the fix.
     */
    private class Peer {
        var shared: Int? = null
            private set

        /** The user moves the picker. */
        fun setTimerLocally(seconds: Int) {
            shared = DisappearingTimerConvergence.toSharedField(seconds)
        }

        /**
         * The user sends a content message. `createLocalModel` freezes the conversation timer onto the outgoing model, and
         * `OutgoingCspMessageTask` advertises exactly that frozen value — never null, so an absent field on the wire can
         * only mean "old client".
         */
        fun send(): SentMessage {
            val frozen = DisappearingTimerConvergence.governingTimerSeconds(shared)
            return SentMessage(advertised = DisappearingFreezeDecision.advertisedTimer(frozen))
        }

        /**
         * A message sent by a pre-v6.4.3-38 client, which has no metadata field at all.
         */
        fun sendAsOldClient(): SentMessage = SentMessage(advertised = null)

        /**
         * Receive a content message (mirrors `MessageServiceImpl.applyIncomingFreeze`). Returns the value frozen onto the
         * incoming model: null = store nothing, 0 = the sender said OFF, > 0 = freeze here.
         */
        fun receive(message: SentMessage): Int? =
            DisappearingFreezeDecision.resolveIncomingTimer(message.advertised, shared)
    }

    /** A content message in flight, carrying the sender's policy in its encrypted metadata. */
    private class SentMessage(val advertised: Int?)

    // ---- (a) THE REPORTED BUG: a recipient's OFF, applied while offline, must rescue nothing. ----

    @Test
    fun `offline OFF does not un-time the backlog the peer already sent`() {
        val alice = Peer()
        val bob = Peer()
        alice.setTimerLocally(30)
        bob.setTimerLocally(30)

        // Bob goes offline and turns the timer OFF. Alice, still on 30 s and unaware, keeps sending;
        // the three messages queue on the server carrying the policy in force when they were written.
        bob.setTimerLocally(0)
        val backlog = listOf(alice.send(), alice.send(), alice.send())

        assertEquals(
            listOf(30, 30, 30),
            backlog.map { it.advertised },
            "alice's timer was 30s when she wrote them, so all three must advertise 30",
        )
        assertNull(bob.shared, "bob's own conversation setting really is OFF — that is the whole point of the scenario")

        // Bob comes back online and drains the backlog.
        backlog.forEachIndexed { index, message ->
            assertEquals(
                30,
                bob.receive(message),
                "backlog message ${index + 1} must freeze at alice's 30s despite bob's local OFF; " +
                    "before this wave all three froze at OFF and were kept forever while alice believed they had expired",
            )
        }
    }

    @Test
    fun `the offline window confers no advantage however long it lasts`() {
        val alice = Peer()
        val bob = Peer()
        alice.setTimerLocally(300)
        bob.setTimerLocally(0)

        // 50 messages stands in for an unbounded offline window: the exposure no longer grows with it,
        // because nothing about the freeze depends on when bob happens to reconnect.
        val backlog = List(50) { alice.send() }
        val frozen = backlog.map { bob.receive(it) }.distinct()

        assertEquals(
            listOf(300),
            frozen,
            "every message in an arbitrarily long backlog must freeze at 300s; got distinct values $frozen",
        )
    }

    // ---- (b) THE ORDERING RACE observed at /tmp/oob/p2/debug_log.txt:4404-4520. ----

    @Test
    fun `a message advertised at 300s freezes at 300s whether it is processed before or after the OFF control message`() {
        val alice = Peer()
        alice.setTimerLocally(300)
        val message = alice.send()
        assertEquals(300, message.advertised, "alice must advertise the 300s frozen on her outgoing model")

        // The same burst carries alice's content message and a 0x85 OFF. Queue position decided the
        // outcome before this wave: a message processed after the OFF got no countdown at all.
        val processedBeforeTheOff = Peer().apply { setTimerLocally(300) }
        val processedAfterTheOff = Peer().apply {
            setTimerLocally(300)
            setTimerLocally(0)
        }

        assertEquals(
            300,
            processedBeforeTheOff.receive(message),
            "processed BEFORE the OFF: must freeze at the advertised 300s",
        )
        assertEquals(
            300,
            processedAfterTheOff.receive(message),
            "processed AFTER the OFF: must STILL freeze at the advertised 300s — this is p2:4520, " +
                "which previously got no countdown line at all because it landed after the 0x85 at p2:4404-4407",
        )
        assertEquals(
            processedBeforeTheOff.receive(message),
            processedAfterTheOff.receive(message),
            "queue position must no longer be observable in the freeze decision",
        )
    }

    // ---- (c) MIXED VERSION: a peer that advertises nothing degrades to today's behaviour. ----

    @Test
    fun `an old client advertising nothing falls back to the local shared field`() {
        val oldPeer = Peer()
        val bob = Peer().apply { setTimerLocally(30) }

        val message = oldPeer.sendAsOldClient()
        assertNull(message.advertised, "a pre-v6.4.3-38 client puts no timer in the metadata at all")
        assertEquals(
            30,
            bob.receive(message),
            "with nothing advertised the recipient falls back to its own 30s — exactly today's behaviour, " +
                "so disappearing messages keep working against every shipped v6.4.3-26..-37 peer",
        )
    }

    @Test
    fun `an old client message with the local timer off stores nothing at all`() {
        val oldPeer = Peer()
        val bob = Peer()

        assertNull(
            bob.receive(oldPeer.sendAsOldClient()),
            "nothing advertised and nothing set locally means nothing was asserted by anyone, so nothing may be stored; " +
                "storing 0 here would wrongly suppress the markAsRead fallback for an old-client peer",
        )
    }

    // ---- The two directions are independent: each message carries its own policy. ----

    @Test
    fun `a local OFF does not affect what that side sends`() {
        val alice = Peer().apply { setTimerLocally(30) }
        val bob = Peer().apply { setTimerLocally(0) }

        assertEquals(
            0,
            alice.receive(bob.send()),
            "bob advertised OFF, so alice must keep bob's messages — resolved as an explicit 0, not as a fallback to her 30",
        )
        assertEquals(
            0,
            bob.send().advertised,
            "a side whose timer is off advertises an explicit 0, never nothing",
        )
        assertEquals(
            30,
            bob.receive(alice.send()),
            "and in the same conversation alice's messages still expire on bob at 30s",
        )
    }

    // ---- The tri-state coupling to markAsRead, asserted directly. ----

    @Test
    fun `an explicit sender OFF resolves to a non-null zero so markAsRead cannot re-freeze it`() {
        val alice = Peer().apply { setTimerLocally(0) }
        val bob = Peer().apply { setTimerLocally(300) }

        val frozen = bob.receive(alice.send())

        assertNotNull(
            frozen,
            "an explicit sender OFF must resolve to a value, not to null: markAsRead falls back to the local " +
                "conversation timer on null, which would re-freeze this message at bob's 300s at READ time and " +
                "silently delete a message alice said to keep",
        )
        assertEquals(0, frozen, "and that value must be 0 (never expires), not bob's 300s")
    }

    // ---- The sender's advertised value is what it froze, normalised. ----

    @Test
    fun `advertisedTimer maps a null frozen timer to an explicit zero`() {
        assertEquals(
            0,
            DisappearingFreezeDecision.advertisedTimer(null),
            "a model with no frozen timer must advertise an explicit OFF, so that ABSENT keeps meaning 'old client'",
        )
    }

    @Test
    fun `advertisedTimer maps a zero frozen timer to an explicit zero`() {
        assertEquals(
            0,
            DisappearingFreezeDecision.advertisedTimer(0),
            "advertisedTimer(0) must be 0",
        )
    }

    @Test
    fun `advertisedTimer maps a negative frozen timer to an explicit zero`() {
        assertEquals(
            0,
            DisappearingFreezeDecision.advertisedTimer(-1),
            "a legacy negative timer must advertise OFF rather than a negative value",
        )
    }

    @Test
    fun `advertisedTimer keeps a live frozen timer unchanged`() {
        assertEquals(
            30,
            DisappearingFreezeDecision.advertisedTimer(30),
            "advertisedTimer(30) must be 30 — the value frozen at compose time is what goes on the wire",
        )
    }

    @Test
    fun `advertisedTimer caps an absurd frozen timer`() {
        assertEquals(
            DisappearingFreezeDecision.MAX_TIMER_SECONDS,
            DisappearingFreezeDecision.advertisedTimer(Int.MAX_VALUE),
            "the outgoing side clamps too, so this device can never put an unschedulable value on the wire",
        )
    }

    @Test
    fun `every picker preset survives a full send-receive round trip`() {
        // The picker's own values (DisappearingMessageUtil.DURATIONS_SECONDS) must be reproduced exactly
        // by the recipient — no clamping, no fallback, no drift.
        val presets = listOf(0, 30, 5 * 60, 60 * 60, 8 * 60 * 60, 24 * 60 * 60, 7 * 24 * 60 * 60, 4 * 7 * 24 * 60 * 60)
        val alice = Peer()
        val bob = Peer().apply { setTimerLocally(30) }

        for (preset in presets) {
            alice.setTimerLocally(preset)
            assertEquals(
                preset,
                bob.receive(alice.send()),
                "picker preset ${preset}s must round-trip to the recipient unchanged, whatever bob's own timer is",
            )
        }
    }
}
