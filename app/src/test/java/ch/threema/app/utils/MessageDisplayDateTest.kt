package ch.threema.app.utils

import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper: an outgoing bubble shows when it was written, not when its last receipt arrived. Seen in the
 * field: a delivery or read receipt from a phone days ahead moved our own bubble's time days ahead, because the
 * outgoing display time was the receipt-driven `modifiedAt`, which carries the other phone's clock.
 */
class MessageDisplayDateTest {

    private val written = Instant.ofEpochMilli(1_800_000_000_000L)
    private val sent = written.plusSeconds(30)

    @Test
    fun `an outgoing message shows its creation time`() {
        assertEquals(written, MessageUtil.getDisplayDate(sent, true, written))
        assertEquals(written, MessageUtil.getDisplayInstant(sent, true, written))
    }

    @Test
    fun `an outgoing message without a creation time falls back to the posted time`() {
        assertEquals(sent, MessageUtil.getDisplayDate(sent, true, null))
    }

    @Test
    fun `an incoming message shows its posted time`() {
        assertEquals(sent, MessageUtil.getDisplayDate(sent, false, written))
        assertNull(MessageUtil.getDisplayDate(null, false, written))
    }

    @Test
    fun `no display caller passes the receipt-driven modified time any more`() {
        val root = "src/main/java/ch/threema/app"
        val callers = listOf(
            "adapters/decorators/ChatAdapterDecorator.java",
            "adapters/decorators/DeletedChatAdapterDecorator.kt",
            "compose/conversation/ConversationListItem.kt",
            "activities/MediaViewerActivity.java",
            "adapters/UserListAdapter.java",
            "widget/WidgetViewsFactory.kt",
        )
        val call = Regex("""MessageUtil\.getDisplay(Date|Instant)\(([^;]*?)\n\s*\)""", RegexOption.DOT_MATCHES_ALL)
        var calls = 0
        for (path in callers) {
            val source = File("$root/$path").readText()
            for (match in call.findAll(source)) {
                calls++
                assertTrue(
                    !match.value.contains("modifiedAt", ignoreCase = true),
                    "$path still shows the receipt time: ${match.value}",
                )
            }
        }
        // ChatAdapterDecorator 2, the others 1 each, WidgetViewsFactory 2.
        assertEquals(8, calls, "this test's anchors have drifted")

        val listItem = File("$root/compose/conversation/ConversationListItem.kt").readText()
        assertTrue(listItem.contains("        /* createdAt = */\n        createdAt,"), "the chat list does not pass createdAt")
    }
}
