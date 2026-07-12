package ch.threema.app.services

import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.data.DisplayTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper auto-resend: the DISPLAY_TAG_SEND_FAILED_TERMINAL marker bit must be orthogonal to the
 * STARRED and PINNED bits and toggle cleanly.
 */
class SendFailedTerminalTagTest {

    @Test
    fun `terminal bit defaults off`() {
        assertFalse(MessageModel().isSendFailedTerminal)
    }

    @Test
    fun `set then clear terminal bit`() {
        val m = MessageModel()
        m.setSendFailedTerminal(true)
        assertTrue(m.isSendFailedTerminal)
        m.setSendFailedTerminal(false)
        assertFalse(m.isSendFailedTerminal)
    }

    @Test
    fun `terminal bit does not touch starred or pinned`() {
        val m = MessageModel().apply {
            displayTags = DisplayTag.DISPLAY_TAG_STARRED or DisplayTag.DISPLAY_TAG_PINNED
        }
        m.setSendFailedTerminal(true)
        assertTrue(m.isStarred)
        assertTrue(m.isPinned)
        assertTrue(m.isSendFailedTerminal)

        m.setSendFailedTerminal(false)
        assertTrue(m.isStarred)
        assertTrue(m.isPinned)
        assertFalse(m.isSendFailedTerminal)
        // Only the two original bits remain.
        assertEquals(DisplayTag.DISPLAY_TAG_STARRED or DisplayTag.DISPLAY_TAG_PINNED, m.displayTags)
    }

    @Test
    fun `starred and pinned queries ignore the terminal bit`() {
        val m = MessageModel()
        m.setSendFailedTerminal(true)
        assertFalse(m.isStarred)
        assertFalse(m.isPinned)
    }

    // ---- Regression guard: task-layer terminal failure must set the bit ----

    /**
     * Reproduce the two mutations OutgoingCspMessageTask.saveWithStateFailed() now performs on a
     * terminal task-layer failure (state -> SENDFAILED AND terminal bit set). Only NON-network
     * exceptions reach that path, so the failure is terminal by construction and the resulting FILE
     * row must NOT be auto-resend eligible (otherwise the reconnect scan would silently re-upload the
     * blob and re-send on every reconnect for up to 24h).
     */
    @Test
    fun `task-layer terminal failure sets the bit so the row is not auto-resend eligible`() {
        val m = MessageModel().apply {
            isOutbox = true
            type = MessageType.FILE
            // saveWithStateFailed(): state = SENDFAILED; setSendFailedTerminal(true)
            state = MessageState.SENDFAILED
            setSendFailedTerminal(true)
        }
        assertTrue(m.isSendFailedTerminal)
        assertFalse(
            AutoResendService.isAutoResendEligible(m),
            "a terminal task-layer SENDFAILED FILE row must be excluded from auto-resend",
        )
    }

    /**
     * Guard-rail contrast: this is the buggy pre-fix shape (SENDFAILED WITHOUT the terminal bit) that
     * the auto-resend scan WOULD have re-sent forever. If this ever starts asserting the row is
     * ineligible, the eligibility gate changed and the terminal-bit invariant no longer protects the
     * task-layer path - re-verify the fix.
     */
    @Test
    fun `SENDFAILED FILE row without the terminal bit is (still) auto-resend eligible`() {
        val m = MessageModel().apply {
            isOutbox = true
            type = MessageType.FILE
            state = MessageState.SENDFAILED
            // no setSendFailedTerminal(true) - the pre-fix task-layer path left it clear
        }
        assertFalse(m.isSendFailedTerminal)
        assertTrue(AutoResendService.isAutoResendEligible(m))
    }
}
