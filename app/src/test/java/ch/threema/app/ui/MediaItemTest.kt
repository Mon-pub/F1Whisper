package ch.threema.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F1Whisper: guards the [MediaItem.quotedMessageId] carrier used to thread a reply-quote through the
 * media/voice send flow (Signal-style "reply with any type"). Parcel round-trip is Android-only and
 * therefore covered on-device, not here.
 */
class MediaItemTest {

    @Test
    fun testQuotedMessageIdDefaultsToNull() {
        val item = MediaItem(null, MediaItem.TYPE_VOICEMESSAGE)
        assertNull(item.quotedMessageId)
    }

    @Test
    fun testQuotedMessageIdSetAndGet() {
        val item = MediaItem(null, MediaItem.TYPE_IMAGE)
        item.quotedMessageId = "0011223344556677"
        assertEquals("0011223344556677", item.quotedMessageId)

        item.quotedMessageId = null
        assertNull(item.quotedMessageId)
    }
}
