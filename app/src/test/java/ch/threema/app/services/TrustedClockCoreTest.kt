package ch.threema.app.services

import ch.threema.app.services.TrustedClock.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper: the server-corrected clock. Seen in the field: one contact's phone runs a week ahead, another three
 * days behind, and both keep stamping messages with their own wrong time. Cause: once an offset had been saved, every
 * later server sample more than 24 h away was rejected forever, so a phone whose clock moved by days after a sync was
 * never corrected again.
 */
class TrustedClockCoreTest {

    private companion object {
        const val SERVER_NOW = 1_800_000_000_000L
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
    }

    private class FakeStore(var available: Boolean = true, var offset: Long? = null) : TrustedClockStore {
        val saved = mutableListOf<Long>()

        override fun loadOffsetMs(): Long? = offset

        override fun saveOffsetMs(offsetMs: Long) {
            saved += offsetMs
            offset = offsetMs
        }
    }

    /** Device wall clock and elapsed clock, both movable; the server clock is [serverNow]. */
    private inner class Harness(deviceSkewMs: Long, val store: FakeStore = FakeStore()) {
        var serverNow = SERVER_NOW
        var wall = SERVER_NOW + deviceSkewMs
        var elapsed = 5 * HOUR

        val clock = TrustedClockCore({ wall }, { elapsed }, { store.takeIf { it.available } })

        fun advance(ms: Long) {
            serverNow += ms
            wall += ms
            elapsed += ms
        }

        /** The user changes the device clock; real time does not move. */
        fun setDeviceSkew(skewMs: Long) {
            wall = serverNow + skewMs
        }

        fun httpSample() = clock.onSample(serverNow, elapsed, Source.HTTP)

        fun mediatorSample() = clock.onSample(serverNow, elapsed, Source.MEDIATOR)
    }

    @Test
    fun `a device that never synced adopts the first sample`() {
        val h = Harness(deviceSkewMs = 7 * DAY)
        assertEquals(h.wall, h.clock.nowMillis(), "before any sample: the device clock")

        h.httpSample()

        assertEquals(SERVER_NOW, h.clock.nowMillis())
        assertEquals(listOf(-7 * DAY), h.store.saved)
    }

    @Test
    fun `a week-ahead clock after an earlier sync is corrected once a second sample confirms it`() {
        // Synced earlier with a correct clock (offset 0, which still counts as synced), then the clock moved a week ahead.
        val h = Harness(deviceSkewMs = 7 * DAY, store = FakeStore(offset = 0L))

        h.httpSample()
        assertEquals(h.wall, h.clock.nowMillis(), "a single sample a week away is only held")

        h.advance(3 * HOUR)
        h.httpSample()

        assertEquals(h.serverNow, h.clock.nowMillis())
        assertEquals(-7 * DAY, h.store.offset)
    }

    @Test
    fun `a three-days-behind clock with a persisted nonzero offset is corrected the same way`() {
        val h = Harness(deviceSkewMs = -3 * DAY, store = FakeStore(offset = 2_000L))

        h.httpSample()
        assertEquals(h.wall + 2_000L, h.clock.nowMillis(), "held: still the persisted fallback")
        h.advance(10 * 60 * 1000)
        h.httpSample()

        assertEquals(h.serverNow, h.clock.nowMillis())
    }

    @Test
    fun `candidates that disagree are not adopted, the newer one is held instead`() {
        val h = Harness(deviceSkewMs = 7 * DAY, store = FakeStore(offset = 0L))
        h.httpSample()

        // A reading that disagrees with the held one by a day confirms nothing...
        h.advance(HOUR)
        h.clock.onSample(h.serverNow + DAY, h.elapsed, Source.HTTP)
        assertEquals(h.wall, h.clock.nowMillis(), "two disagreeing readings confirm nothing")

        // ...and replaced the held candidate, so a genuine reading now only re-holds...
        h.advance(HOUR)
        h.httpSample()
        assertEquals(h.wall, h.clock.nowMillis())

        // ...until the next genuine reading agrees with it.
        h.advance(HOUR)
        h.httpSample()
        assertEquals(h.serverNow, h.clock.nowMillis())
    }

    @Test
    fun `a clock change between two genuine readings does not stop them confirming each other`() {
        val h = Harness(deviceSkewMs = 7 * DAY, store = FakeStore(offset = 0L))
        h.httpSample()

        h.setDeviceSkew(8 * DAY)
        h.advance(HOUR)
        h.httpSample()

        assertEquals(h.serverNow, h.clock.nowMillis(), "both readings agree on server time; the device clock is irrelevant")
    }

    @Test
    fun `a candidate expires`() {
        val h = Harness(deviceSkewMs = 7 * DAY, store = FakeStore(offset = 0L))
        h.httpSample()

        h.advance(TrustedClockCore.CANDIDATE_TTL_MS + HOUR)
        h.httpSample()
        assertEquals(h.wall, h.clock.nowMillis(), "an expired candidate does not confirm a new one")

        h.advance(HOUR)
        h.httpSample()
        assertEquals(h.serverNow, h.clock.nowMillis())
    }

    @Test
    fun `a normal sample in between cancels the candidate`() {
        val h = Harness(deviceSkewMs = 0L, store = FakeStore(offset = 0L))
        // One stray reading a week off...
        h.clock.onSample(h.serverNow - 7 * DAY, h.elapsed, Source.HTTP)
        // ...then a normal one...
        h.advance(HOUR)
        h.httpSample()
        // ...then another stray agreeing with the first: still not adopted.
        h.advance(30_000L)
        h.clock.onSample(h.serverNow - 7 * DAY, h.elapsed, Source.HTTP)

        assertEquals(h.serverNow, h.clock.nowMillis())
    }

    @Test
    fun `one mediator login corrects immediately, however large the jump`() {
        val h = Harness(deviceSkewMs = 7 * DAY, store = FakeStore(offset = 0L))

        h.mediatorSample()

        assertEquals(h.serverNow, h.clock.nowMillis())
        assertEquals(-7 * DAY, h.store.offset)
    }

    @Test
    fun `after a sample, changing the device clock no longer moves the corrected time`() {
        val h = Harness(deviceSkewMs = 0L)
        h.httpSample()

        h.setDeviceSkew(7 * DAY)
        h.advance(HOUR)
        assertEquals(h.serverNow, h.clock.nowMillis())

        h.setDeviceSkew(-3 * DAY)
        h.advance(HOUR)
        assertEquals(h.serverNow, h.clock.nowMillis())
    }

    @Test
    fun `small jitter is smoothed by the median`() {
        val h = Harness(deviceSkewMs = 0L)
        h.httpSample()
        h.clock.onSample(h.serverNow + 900L, h.elapsed, Source.HTTP)
        h.clock.onSample(h.serverNow - 200L, h.elapsed, Source.HTTP)

        assertEquals(h.serverNow, h.clock.nowMillis())
    }

    @Test
    fun `an accepted downward correction releases the outgoing stamp latch`() {
        val h = Harness(deviceSkewMs = 7 * DAY, store = FakeStore(offset = 0L))
        val futureStamp = h.clock.stampNowMillis()
        assertEquals(h.wall, futureStamp, "stamped a week ahead before the correction")

        h.httpSample() // held only: the latch must stay
        assertTrue(h.clock.stampNowMillis() > futureStamp)

        h.advance(HOUR)
        h.httpSample() // confirmed: corrected a week back
        val afterCorrection = h.clock.stampNowMillis()

        assertEquals(h.serverNow, afterCorrection, "new messages get the corrected time, not 'after the future stamp'")
        assertTrue(h.clock.stampNowMillis() > afterCorrection, "and stay monotonic from there")
    }

    @Test
    fun `stamps stay monotonic across a small correction`() {
        val h = Harness(deviceSkewMs = 2_000L)
        val first = h.clock.stampNowMillis()
        h.httpSample() // pulls the clock back by 2 s, inside the latch tolerance

        assertTrue(h.clock.stampNowMillis() > first)
    }

    @Test
    fun `the corrected time is only trusted after a sample in this process`() {
        val h = Harness(deviceSkewMs = 7 * DAY, store = FakeStore(offset = -7 * DAY))
        assertEquals(h.serverNow, h.clock.nowMillis(), "the persisted offset is used for stamping")
        assertNull(h.clock.trustedNowMillisOrNull(), "but it is not trusted enough to override a sender")

        h.httpSample()

        assertEquals(h.serverNow, assertNotNull(h.clock.trustedNowMillisOrNull()))
    }

    @Test
    fun `a sample taken before preferences exist is kept, and a late load does not overwrite it`() {
        val store = FakeStore(available = false, offset = 0L)
        val h = Harness(deviceSkewMs = 7 * DAY, store = store)

        h.httpSample()
        assertEquals(h.serverNow, h.clock.nowMillis(), "never synced as far as this process knows: adopted")
        assertTrue(store.saved.isEmpty())

        store.available = true // the stale persisted 0 is now readable
        h.advance(1_000L)
        assertEquals(h.serverNow, h.clock.nowMillis(), "memory is newer than the store")
        h.clock.stampNowMillis()
        assertEquals(listOf(-7 * DAY), store.saved, "the pending offset is written once the store is available")
    }

    @Test
    fun `the receive delay is measured on the elapsed clock, so a clock change during it does not skew the sample`() {
        val h = Harness(deviceSkewMs = 0L)
        val receivedAtElapsed = h.elapsed
        val serverAtReceipt = h.serverNow

        // Processed 10 s later, and the user moved the device clock a week ahead meanwhile.
        h.advance(10_000L)
        h.setDeviceSkew(7 * DAY)
        h.clock.onSample(serverAtReceipt, receivedAtElapsed, Source.HTTP)

        assertEquals(h.serverNow, h.clock.nowMillis())
    }

    @Test
    fun `an invalid server time is ignored`() {
        val h = Harness(deviceSkewMs = 7 * DAY)
        h.clock.onSample(0L, h.elapsed, Source.MEDIATOR)

        assertEquals(h.wall, h.clock.nowMillis())
        assertNull(h.clock.trustedNowMillisOrNull())
    }
}
