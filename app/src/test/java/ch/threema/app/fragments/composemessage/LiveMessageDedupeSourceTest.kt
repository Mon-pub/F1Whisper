package ch.threema.app.fragments.composemessage

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper: an open chat must show a new incoming message even when the phone clock runs ahead.
 *
 * The live list skipped "already loaded" messages by comparing a message's createdAt (stamped on the server-corrected
 * clock) with the raw device clock at list load. On a phone ahead of the server, every new incoming message looked
 * older than the list and was dropped until the chat was reopened. The guard is now a row-id check, and day
 * separators follow the timeline date each row is sorted by. The method needs a live fragment, so this pins the
 * source (comments stripped).
 */
class LiveMessageDedupeSourceTest {

    private val source: String =
        File("src/main/java/ch/threema/app/fragments/composemessage/ComposeMessageFragment.java").readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    private fun bodyOf(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "`$signature` not found; this test's anchors have drifted")
        var depth = 0
        var i = source.indexOf('{', start)
        val open = i
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("unbalanced braces after `$signature`")
    }

    @Test
    fun `the live list has no clock watermark`() {
        assertFalse(source.contains("listInitializedAt"), "the device-clock watermark is back")
    }

    @Test
    fun `a new message is skipped only when its row is already listed, after the conversation check`() {
        val body = bodyOf("private boolean addMessageToList(AbstractMessageModel message)")
        val belongsAt = body.indexOf("isMessageBelongsToMe(message)")
        val dedupeAt = body.indexOf("if (isAlreadyListed(message))")
        assertTrue(belongsAt >= 0 && dedupeAt >= 0, "conversation check or row-id dedupe missing")
        assertTrue(belongsAt < dedupeAt, "the row-id dedupe must run on this conversation's rows only")
        assertFalse(Regex("""getCreatedAt\(\)\.isBefore""").containsMatchIn(body), "a time comparison decides listing again")

        val dedupe = bodyOf("private boolean isAlreadyListed(@NonNull AbstractMessageModel message)")
        assertTrue(dedupe.contains("existing.getId() == message.getId()"))
    }

    @Test
    fun `day separators use the timeline date, not the device clock`() {
        val add = bodyOf("private boolean addMessageToList(AbstractMessageModel message)")
        assertTrue(add.contains("Instant date = timelineDateOrNow(message);"))
        assertFalse(add.contains("Instant date = Instant.now();"))
        assertTrue(
            add.contains("if (date.isAfter(createdAt) && !isSameDayAs(date, createdAt))"),
            "a delayed live message must not open a day section that goes backwards",
        )

        val unread = File("src/main/java/ch/threema/app/services/MessageServiceImpl.java").readText()
        assertTrue(
            unread.contains("firstUnreadMessageModel.setCreatedAt(TimelineKeyset.effectiveSortDate(messageModels.get(insertIndex - 1)));"),
            "the unread divider must carry the timeline date of its message",
        )

        val insert = bodyOf("private int insertToList(")
        assertFalse(insert.contains(".getCreatedAt()"), "history load groups days by createdAt again")
        assertTrue(insert.contains("TimelineKeyset.effectiveSortDate(m)"))
    }
}
