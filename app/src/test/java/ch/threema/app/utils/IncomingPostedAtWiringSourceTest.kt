package ch.threema.app.utils

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1Whisper: pins the WIRING of [IncomingPostedAt]. [IncomingPostedAtTest] proves the rule; the six model-creation
 * sites and the two receive paths need the whole service graph and a live connection, so a future upstream merge that
 * puts `message.getTimestamp()` back would pass every other test. Checked in the source, comments stripped.
 */
class IncomingPostedAtWiringSourceTest {
    private val root = "src/main/java/ch/threema/app"

    private fun code(path: String): String =
        File("$root/$path").readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the four incoming text and location models take the clamped posted time`() {
        val source = code("services/MessageServiceImpl.java")
        val clampedCreate = Regex(
            """createLocalModel\(message\.getMessageId\(\), MessageType\.(TEXT|LOCATION), """ +
                """MessageContentsType\.(TEXT|LOCATION), IncomingPostedAt\.of\(message\)\)""",
        )
        assertEquals(4, clampedCreate.findAll(source).count())
        assertFalse(
            Regex("""createLocalModel\(message\.getMessageId\(\),[^;]*message\.getTimestamp\(\)\)""").containsMatchIn(source),
            "an incoming model is created with the raw sender time again",
        )
    }

    @Test
    fun `both incoming file tasks take the clamped posted time`() {
        for (task in listOf("IncomingContactFileMessageTask.kt", "IncomingGroupFileMessageTask.kt")) {
            val source = code("processors/incomingcspmessage/conversation/$task")
            assertTrue(source.contains("postedAt = IncomingPostedAt.of(message)"), "$task does not clamp postedAt")
            assertFalse(source.contains("postedAt = message.timestamp"), "$task uses the raw sender time again")
        }
    }

    @Test
    fun `missed-call and group-call-start rows take the clamped time`() {
        assertTrue(
            code("voip/services/VoipStateService.java")
                .contains("VoipCallEvent.Missed.javaCreate(callId, msg.getFromIdentity(), accepted, IncomingPostedAt.of(msg))"),
            "a missed call is stored with the raw caller time again",
        )
        assertTrue(
            code("voip/groupcall/GroupCallManagerImpl.kt").contains("if (isOutbox) message.timestamp else IncomingPostedAt.of(message)"),
            "an incoming group call start is stored with the raw caller time again",
        )
    }

    @Test
    fun `both receive paths record the trusted receive time before processing`() {
        val direct = code("processors/IncomingMessageTask.kt")
        val directSet = direct.indexOf("message.trustedReceivedAtMillis = TrustedClock.trustedNowMillisOrNull()")
        assertTrue(directSet >= 0, "direct receipt does not record the trusted receive time")
        assertTrue(directSet < direct.indexOf("getSubTaskFromMessage(message, TriggerSource.REMOTE"), "recorded too late")

        val reflected = code("processors/IncomingReflectedMessageTask.kt")
        val reflectedSet = reflected.indexOf("message.trustedReceivedAtMillis = reflectTimeMillisOrNull()")
        assertTrue(reflectedSet >= 0, "reflected receipt does not record the mediator reflect time")
        assertTrue(reflectedSet < reflected.indexOf("getSubTaskFromMessage(message, TriggerSource.SYNC"), "recorded too late")
    }
}
