package ch.threema.app.services.messageplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceBannerLogicTest {
    @Test
    fun `formatRemaining shows remaining time while duration known`() {
        // 30s total, 5s elapsed -> 0:25 remaining
        assertEquals("0:25", VoiceBannerLogic.formatRemaining(durationMs = 30_000L, positionMs = 5_000L))
    }

    @Test
    fun `formatRemaining pads seconds to two digits`() {
        assertEquals("0:05", VoiceBannerLogic.formatRemaining(durationMs = 10_000L, positionMs = 5_000L))
    }

    @Test
    fun `formatRemaining renders minutes and seconds`() {
        // 3:05 total, 0:03 elapsed -> 3:02 remaining
        assertEquals("3:02", VoiceBannerLogic.formatRemaining(durationMs = 185_000L, positionMs = 3_000L))
    }

    @Test
    fun `formatRemaining clamps negative remaining to zero`() {
        // position past the end (rounding / drift) must never show a negative time
        assertEquals("0:00", VoiceBannerLogic.formatRemaining(durationMs = 10_000L, positionMs = 12_000L))
    }

    @Test
    fun `formatRemaining falls back to elapsed when duration unknown`() {
        // duration not yet known (0) -> show elapsed position instead of a blank/negative value
        assertEquals("0:07", VoiceBannerLogic.formatRemaining(durationMs = 0L, positionMs = 7_000L))
    }

    @Test
    fun `banner hidden when nothing is playing`() {
        assertFalse(
            VoiceBannerLogic.shouldShowBanner(
                hasPlayback = false,
                isListenOnce = false,
                playingChatUniqueId = "chat-a",
                openChatUniqueId = null,
            ),
        )
    }

    @Test
    fun `banner hidden for listen-once voice`() {
        assertFalse(
            VoiceBannerLogic.shouldShowBanner(
                hasPlayback = true,
                isListenOnce = true,
                playingChatUniqueId = "chat-a",
                openChatUniqueId = null,
            ),
        )
    }

    @Test
    fun `banner shown in conversation list while a normal voice plays`() {
        assertTrue(
            VoiceBannerLogic.shouldShowBanner(
                hasPlayback = true,
                isListenOnce = false,
                playingChatUniqueId = "chat-a",
                openChatUniqueId = null,
            ),
        )
    }

    @Test
    fun `banner hidden inside the chat the message belongs to`() {
        assertFalse(
            VoiceBannerLogic.shouldShowBanner(
                hasPlayback = true,
                isListenOnce = false,
                playingChatUniqueId = "chat-a",
                openChatUniqueId = "chat-a",
            ),
        )
    }

    @Test
    fun `banner shown inside a different chat than the playing one`() {
        assertTrue(
            VoiceBannerLogic.shouldShowBanner(
                hasPlayback = true,
                isListenOnce = false,
                playingChatUniqueId = "chat-a",
                openChatUniqueId = "chat-b",
            ),
        )
    }

    @Test
    fun `rebind resumes when still on this message and playing`() {
        assertEquals(
            RebindAction.RESUME,
            VoiceBannerLogic.reconcileRebind(mediaMatches = true, isPlaying = true, isEndedOrIdle = false),
        )
    }

    @Test
    fun `rebind pauses when still on this message but not playing`() {
        assertEquals(
            RebindAction.PAUSE,
            VoiceBannerLogic.reconcileRebind(mediaMatches = true, isPlaying = false, isEndedOrIdle = false),
        )
    }

    @Test
    fun `rebind stops when the shared player moved to a different message`() {
        assertEquals(
            RebindAction.STOP,
            VoiceBannerLogic.reconcileRebind(mediaMatches = false, isPlaying = true, isEndedOrIdle = false),
        )
    }

    @Test
    fun `rebind stops when playback has ended even if media still matches`() {
        assertEquals(
            RebindAction.STOP,
            VoiceBannerLogic.reconcileRebind(mediaMatches = true, isPlaying = false, isEndedOrIdle = true),
        )
    }

    @Test
    fun `kept player released on end only for the detached background message`() {
        assertTrue(VoiceBannerLogic.shouldReleaseKeptPlayerOnEnd(endedMessageId = 42, detachedMessageId = 42))
        assertFalse(VoiceBannerLogic.shouldReleaseKeptPlayerOnEnd(endedMessageId = 42, detachedMessageId = 7))
        // in-chat end (no detached session) must never trigger the targeted release
        assertFalse(VoiceBannerLogic.shouldReleaseKeptPlayerOnEnd(endedMessageId = 42, detachedMessageId = 0))
        assertFalse(VoiceBannerLogic.shouldReleaseKeptPlayerOnEnd(endedMessageId = 0, detachedMessageId = 0))
    }

    @Test
    fun `natural end is always treated as a playback end`() {
        assertTrue(VoiceBannerLogic.shouldTreatAsPlaybackEnd(isEnded = true, isIdle = false, hasDetachedSession = false))
        assertTrue(VoiceBannerLogic.shouldTreatAsPlaybackEnd(isEnded = true, isIdle = false, hasDetachedSession = true))
    }

    @Test
    fun `idle is a playback end only for a detached background session`() {
        // playback error out-of-chat -> STATE_IDLE with a detached session must be cleaned up
        assertTrue(VoiceBannerLogic.shouldTreatAsPlaybackEnd(isEnded = false, isIdle = true, hasDetachedSession = true))
        // in-chat / initial-connection idle (no detached session) must NOT be treated as an end
        assertFalse(VoiceBannerLogic.shouldTreatAsPlaybackEnd(isEnded = false, isIdle = true, hasDetachedSession = false))
    }

    @Test
    fun `other states are not a playback end`() {
        assertFalse(VoiceBannerLogic.shouldTreatAsPlaybackEnd(isEnded = false, isIdle = false, hasDetachedSession = true))
        assertFalse(VoiceBannerLogic.shouldTreatAsPlaybackEnd(isEnded = false, isIdle = false, hasDetachedSession = false))
    }

    private fun notificationController() = ConnectedControllerFlags(isMediaNotification = true, isDisconnecting = false)
    private fun realController(disconnecting: Boolean = false) =
        ConnectedControllerFlags(isMediaNotification = false, isDisconnecting = disconnecting)

    @Test
    fun `service stops when only the media-notification controller remains`() {
        // notification controller is always connected while the service runs; it must not keep it alive
        assertTrue(VoiceBannerLogic.shouldStopServiceOnDisconnect(listOf(notificationController())))
    }

    @Test
    fun `service stops when the leaving fragment is the only real controller`() {
        // nothing playing -> holder not connected; fragment (disconnecting) + notification -> stop
        assertTrue(
            VoiceBannerLogic.shouldStopServiceOnDisconnect(
                listOf(notificationController(), realController(disconnecting = true)),
            ),
        )
    }

    @Test
    fun `service keeps running while the background holder controller stays connected`() {
        // fragment disconnecting, but the app-scoped holder (a real, non-disconnecting controller) stays
        assertFalse(
            VoiceBannerLogic.shouldStopServiceOnDisconnect(
                listOf(notificationController(), realController(disconnecting = true), realController()),
            ),
        )
    }

    @Test
    fun `service stops when no controller remains at all`() {
        assertTrue(VoiceBannerLogic.shouldStopServiceOnDisconnect(emptyList()))
    }

    @Test
    fun `service keeps running for a single real non-disconnecting controller`() {
        assertFalse(VoiceBannerLogic.shouldStopServiceOnDisconnect(listOf(realController())))
    }
}
