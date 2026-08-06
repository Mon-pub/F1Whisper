package ch.threema.app.services.messageplayer

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper (fourth fork review, F4-10): a live listen-once playback must not be mistaken for an abandoned claim.
 *
 * The defect: the durable claim records only that plaintext was released to a player at least once. It does not say whether
 * that player is still going, so a live claim and one abandoned by a process death were indistinguishable - and the normal
 * first-play path reaches them in this order:
 *
 * ```
 * open()      -> claim written
 * STATE_READY -> markAsConsumed() -> save + onModified -> the bubble rebinds
 * rebind      -> claimed-and-unconsumed -> BLOCKED_BURN_PENDING -> burn()
 * prepared()  -> play()                                   <- only after that refresh chain returns
 * ```
 *
 * The bubble finished the burn before playback had begun: the file was deleted and the controls collapsed under a message
 * the user had just started, leaving it permanently unavailable. Not the accepted failed-playback tradeoff; this was the
 * active first playback.
 *
 * These tests drive the production registry through that exact sequence, through a second session's attempt to take over,
 * and through a process death. [legacyDurableClaimAloneBurnsTheLivePlayback] is the control: it applies the old rule inline
 * - claimed and not consumed means abandoned - and shows the live playback being burned.
 *
 * What this cannot cover, recorded rather than glossed: Media3's callbacks, the adapter bind and the real burn need a
 * device. The ownership rule they consult is what is pinned here, plus the fact that both repair sites consult it.
 */
class ListenOnceOwnershipTest {
    private val messageId = 42
    private val otherMessageId = 43

    private val player = File("src/main/java/ch/threema/app/services/messageplayer/AudioMessagePlayer.java")
    private val decorator = File("src/main/java/ch/threema/app/adapters/decorators/AudioChatAdapterDecorator.java")

    @BeforeTest
    fun setUp() = ListenOnceOwnership.forgetAll()

    @AfterTest
    fun tearDown() = ListenOnceOwnership.forgetAll()

    /** The repair rule as both sites now apply it: burn only a claim that nothing is actively playing. */
    private fun wouldRepairBurn(messageId: Int, isClaimed: Boolean, isConsumed: Boolean): Boolean {
        val gate = ListenOnceDecision.evaluate(
            isOutbox = false,
            isFileMessage = true,
            isListenOnce = true,
            isClaimed = isClaimed,
            isConsumed = isConsumed,
        )
        return gate == ListenOnceGate.BLOCKED_BURN_PENDING && !ListenOnceOwnership.isActive(messageId)
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The first-play sequence.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the rebind between claim and playback does not burn the live session`() {
        val session = Any()

        // open(): ownership first, then the claim.
        assertTrue(ListenOnceOwnership.acquire(messageId, session))
        val claimed = true
        val consumed = false

        // STATE_READY -> markAsConsumed -> onModified -> the bubble rebinds, before prepared() has called play().
        assertFalse(
            wouldRepairBurn(messageId, claimed, consumed),
            "the claim is live; burning here deletes the audio the user just started",
        )
    }

    @Test
    fun `the burn still runs once the session ends`() {
        val session = Any()
        ListenOnceOwnership.acquire(messageId, session)

        // Playback ended: the session hands off the burn and stops owning the message.
        ListenOnceOwnership.release(messageId, session)

        assertTrue(
            wouldRepairBurn(messageId, isClaimed = true, isConsumed = false),
            "an interrupted burn must still be finished once nothing is playing",
        )
    }

    @Test
    fun `a consumed message is never repair-burned again whether or not it is owned`() {
        assertFalse(wouldRepairBurn(messageId, isClaimed = true, isConsumed = true))

        ListenOnceOwnership.acquire(messageId, Any())

        assertFalse(wouldRepairBurn(messageId, isClaimed = true, isConsumed = true))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // One message, one playback.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a second session is refused while the first is playing`() {
        val first = Any()
        val second = Any()

        assertTrue(ListenOnceOwnership.acquire(messageId, first))
        assertFalse(ListenOnceOwnership.acquire(messageId, second), "a second playback must not start")
        assertTrue(ListenOnceOwnership.isActive(messageId))
    }

    @Test
    fun `the owning session may re-enter its own playback`() {
        val session = Any()

        assertTrue(ListenOnceOwnership.acquire(messageId, session))
        assertTrue(
            ListenOnceOwnership.acquire(messageId, session),
            "a rebind that re-opens the same session must not be refused",
        )
    }

    @Test
    fun `a non-owner cannot release the message`() {
        val owner = Any()
        val intruder = Any()
        ListenOnceOwnership.acquire(messageId, owner)

        ListenOnceOwnership.release(messageId, intruder)

        assertTrue(ListenOnceOwnership.isActive(messageId), "only the owner may give up ownership")
        assertFalse(wouldRepairBurn(messageId, isClaimed = true, isConsumed = false))
    }

    @Test
    fun `a second session may play once the first has finished`() {
        val first = Any()
        ListenOnceOwnership.acquire(messageId, first)
        ListenOnceOwnership.release(messageId, first)

        assertTrue(ListenOnceOwnership.acquire(messageId, Any()))
    }

    @Test
    fun `ownership is per message`() {
        ListenOnceOwnership.acquire(messageId, Any())

        assertFalse(ListenOnceOwnership.isActive(otherMessageId))
        assertTrue(ListenOnceOwnership.acquire(otherMessageId, Any()))
    }

    @Test
    fun `exactly one of many concurrent sessions wins`() {
        val contenders = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(contenders)
        val winners = AtomicInteger(0)

        repeat(contenders) {
            Thread {
                start.await()
                if (ListenOnceOwnership.acquire(messageId, Any())) {
                    winners.incrementAndGet()
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals(1, winners.get(), "one message, one playback, whatever the interleaving")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Process death.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a claim abandoned by a process death is repaired again`() {
        ListenOnceOwnership.acquire(messageId, Any())
        assertFalse(wouldRepairBurn(messageId, isClaimed = true, isConsumed = false))

        // The process dies mid-playback. Ownership is in memory only, so it goes with it.
        ListenOnceOwnership.forgetAll()

        assertTrue(
            wouldRepairBurn(messageId, isClaimed = true, isConsumed = false),
            "this is what the repair was written for and it must keep working",
        )
        assertTrue(ListenOnceOwnership.acquire(messageId, Any()), "and the message is claimable again")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Both repair sites consult ownership.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `both repair sites check for a live owner before burning`() {
        for (file in listOf(player, decorator)) {
            val source = file.readText()
            val burnAt = source.indexOf("BLOCKED_BURN_PENDING")
            assertTrue(burnAt >= 0, "${file.name}: this test's anchor has drifted")
            assertTrue(
                source.contains("ListenOnceOwnership.isActive("),
                "${file.name} finishes an interrupted burn without checking whether the claim is live",
            )
        }
    }

    @Test
    fun `ownership is taken before the claim is written`() {
        val source = player.readText()
        val acquireAt = source.indexOf("ListenOnceOwnership.acquire(")
        val claimAt = source.indexOf("ListenOnceEnforcer.claim(")

        assertTrue(acquireAt >= 0 && claimAt >= 0, "this test's anchors have drifted")
        assertTrue(
            acquireAt < claimAt,
            "no callback may be able to observe the claim without also being able to observe its owner",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Legacy control: the old rule, written out inline.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun legacyDurableClaimAloneBurnsTheLivePlayback() {
        val session = Any()
        ListenOnceOwnership.acquire(messageId, session)

        // The old rule: claimed and not consumed means abandoned, full stop.
        val gate = ListenOnceDecision.evaluate(
            isOutbox = false,
            isFileMessage = true,
            isListenOnce = true,
            isClaimed = true,
            isConsumed = false,
        )
        val legacyWouldBurn = gate == ListenOnceGate.BLOCKED_BURN_PENDING

        assertTrue(
            legacyWouldBurn,
            "this is the defect: the rebind that happens between the claim and the first audible frame burns the " +
                "message the user has just started playing",
        )
        assertTrue(ListenOnceOwnership.isActive(messageId), "while the session that claimed it is still running")
    }
}
