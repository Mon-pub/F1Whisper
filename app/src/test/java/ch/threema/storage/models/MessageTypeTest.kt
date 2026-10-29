package ch.threema.storage.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageTypeTest {

    @Test
    fun `serialize message types`() {
        assertEquals(0, MessageType.TEXT.serializedValue)
        assertEquals(4, MessageType.LOCATION.serializedValue)
        assertEquals(6, MessageType.STATUS.serializedValue)
        assertEquals(7, MessageType.POLL.serializedValue)
        assertEquals(8, MessageType.FILE.serializedValue)
        assertEquals(9, MessageType.VOIP_STATUS.serializedValue)
        assertEquals(10, MessageType.DATE_SEPARATOR.serializedValue)
        assertEquals(11, MessageType.GROUP_CALL_STATUS.serializedValue)
        assertEquals(12, MessageType.FORWARD_SECURITY_STATUS.serializedValue)
        assertEquals(13, MessageType.GROUP_STATUS.serializedValue)
        // F1Whisper: fork-only type, stored as 14
        assertEquals(14, MessageType.DISAPPEARING_STATUS.serializedValue)
    }

    @Test
    fun `deserialize message types`() {
        assertEquals(MessageType.TEXT, MessageType.deserialize(0))
        assertEquals(MessageType.LOCATION, MessageType.deserialize(4))
        assertEquals(MessageType.STATUS, MessageType.deserialize(6))
        assertEquals(MessageType.POLL, MessageType.deserialize(7))
        assertEquals(MessageType.FILE, MessageType.deserialize(8))
        assertEquals(MessageType.VOIP_STATUS, MessageType.deserialize(9))
        assertEquals(MessageType.DATE_SEPARATOR, MessageType.deserialize(10))
        assertEquals(MessageType.GROUP_CALL_STATUS, MessageType.deserialize(11))
        assertEquals(MessageType.FORWARD_SECURITY_STATUS, MessageType.deserialize(12))
        assertEquals(MessageType.GROUP_STATUS, MessageType.deserialize(13))
        assertEquals(MessageType.DISAPPEARING_STATUS, MessageType.deserialize(14))

        // Former types that don't exist anymore
        assertNull(MessageType.deserialize(1)) // IMAGE
        assertNull(MessageType.deserialize(2)) // VIDEO
        assertNull(MessageType.deserialize(3)) // VOICEMESSAGE
        assertNull(MessageType.deserialize(5)) // CONTACT

        // Types that don't exist (yet)
        assertNull(MessageType.deserialize(15))
    }

    @Test
    fun `only text and file message types can be edited`() {
        assertTrue(MessageType.TEXT.canBeEdited)
        assertTrue(MessageType.FILE.canBeEdited)

        assertFalse(MessageType.LOCATION.canBeEdited)
        assertFalse(MessageType.STATUS.canBeEdited)
        assertFalse(MessageType.POLL.canBeEdited)
        assertFalse(MessageType.VOIP_STATUS.canBeEdited)
        assertFalse(MessageType.DATE_SEPARATOR.canBeEdited)
        assertFalse(MessageType.GROUP_CALL_STATUS.canBeEdited)
        assertFalse(MessageType.FORWARD_SECURITY_STATUS.canBeEdited)
        assertFalse(MessageType.GROUP_STATUS.canBeEdited)
    }

    @Test
    fun `all non-status message types require a message id`() {
        assertTrue(MessageType.TEXT.requiresMessageId)
        assertTrue(MessageType.FILE.requiresMessageId)
        assertTrue(MessageType.LOCATION.requiresMessageId)
        assertTrue(MessageType.POLL.requiresMessageId)

        assertFalse(MessageType.STATUS.requiresMessageId)
        assertFalse(MessageType.VOIP_STATUS.requiresMessageId)
        assertFalse(MessageType.DATE_SEPARATOR.requiresMessageId)
        assertFalse(MessageType.GROUP_CALL_STATUS.requiresMessageId)
        assertFalse(MessageType.FORWARD_SECURITY_STATUS.requiresMessageId)
        assertFalse(MessageType.GROUP_STATUS.requiresMessageId)
    }
}
