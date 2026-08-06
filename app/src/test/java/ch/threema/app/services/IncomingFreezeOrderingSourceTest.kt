package ch.threema.app.services

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * F1Whisper (fourth fork review, F4-05): pins the ORDER of the incoming freeze against the incoming insert.
 *
 * [IncomingFreezeDurabilityTest] proves the rule; this proves the wiring. The defect was not a wrong decision, it was a
 * correct decision applied one write too late, and that ordering lives inside methods which need the whole service graph, a
 * real SQLCipher database and a real process kill to exercise. Nothing else in the JVM suite can observe it, so moving the
 * freeze back below the insert would reintroduce the defect silently and every gate would stay green.
 *
 * So it is checked where it is visible: in the source. A source assertion is a blunt instrument and is used here only because
 * the alternative is no coverage at all. It deliberately asserts one narrow thing per site - freeze before first persist -
 * rather than trying to describe the methods.
 */
class IncomingFreezeOrderingSourceTest {
    private val messageServiceImpl = File("src/main/java/ch/threema/app/services/MessageServiceImpl.java")
    private val contactFileTask =
        File("src/main/java/ch/threema/app/processors/incomingcspmessage/conversation/IncomingContactFileMessageTask.kt")
    private val groupFileTask =
        File("src/main/java/ch/threema/app/processors/incomingcspmessage/conversation/IncomingGroupFileMessageTask.kt")

    /** The signature line of every incoming path that builds a model and writes it for the first time. */
    private val incomingSaveMethods = listOf(
        "private MessageModel saveBoxMessage(\n        @NonNull TextMessage message,",
        "private MessageModel saveBoxMessage(\n        @NonNull ImageMessage message,",
        "private MessageModel saveBoxMessage(\n        @NonNull LocationMessage message,",
        "private GroupMessageModel saveGroupMessage(GroupTextMessage message,",
        "private GroupMessageModel saveGroupMessage(GroupImageMessage message,",
        "private GroupMessageModel saveGroupMessage(GroupLocationMessage message,",
        "private AbstractMessageModel createNewBallotMessage(",
    )

    private val persistCalls = listOf(
        ".create(messageModel)",
        ".saveLocalModel(messageModel)",
        ".saveLocalModel(model)",
    )

    @Test
    fun `every incoming save method freezes the sender's timer before its first write`() {
        val source = messageServiceImpl.readText()

        for (signature in incomingSaveMethods) {
            val body = bodyOf(source, signature)
            val freezeAt = body.indexOf("freezeIncomingBeforeFirstWrite(")
            val persistAt = persistCalls.mapNotNull { call ->
                body.indexOf(call).takeIf { it >= 0 }
            }.minOrNull()

            if (persistAt == null) {
                fail("no persist call found in the method starting `$signature`; this test's anchors have drifted")
            }
            assertTrue(freezeAt >= 0, "`$signature` writes an incoming row without freezing the sender's timer first")
            assertTrue(
                freezeAt < persistAt,
                "`$signature` writes the incoming row before applying the sender's timer; a process death between the " +
                    "two makes the wrong policy permanent, because the duplicate guard then returns success",
            )
        }
    }

    @Test
    fun `both incoming file tasks freeze the sender's timer before saving`() {
        for (task in listOf(contactFileTask, groupFileTask)) {
            val source = task.readText()
            val freezeAt = source.indexOf("freezeIncomingDisappearingPolicyBeforeFirstWrite(messageModel")
            val saveAt = source.indexOf("messageService.save(messageModel)")

            assertTrue(freezeAt >= 0, "${task.name} does not use the before-first-write freeze")
            assertTrue(saveAt >= 0, "${task.name}: this test's anchor has drifted")
            assertTrue(
                freezeAt < saveAt,
                "${task.name} saves the incoming row before applying the sender's timer",
            )
        }
    }

    @Test
    fun `every duplicate-message branch repairs the sender's timer before returning success`() {
        // Fifth review, F5-05: the repair is now NARROWED to an explicitly advertised value, because an absent one was
        // being re-resolved against the conversation timer as it stands now - re-freezing an old message at a setting
        // chosen long after it arrived. The branches still repair; what they repair FROM is restricted. The restriction
        // itself is covered by DuplicateFreezeProvenanceTest.
        val impl = messageServiceImpl.readText()
        assertTrue(
            impl.contains("repairDuplicateIncomingFreeze(savedMessageModel, message.getDisappearingTimerSeconds());"),
            "the 1:1 duplicate branch must repair a row that lacks the authoritative freeze",
        )
        assertTrue(
            impl.contains("repairDuplicateIncomingFreeze(existingModel, message.getDisappearingTimerSeconds());"),
            "the group duplicate branch must repair a row that lacks the authoritative freeze",
        )
        for (task in listOf(contactFileTask, groupFileTask)) {
            assertTrue(
                task.readText().contains("messageService.freezeIncomingDisappearingPolicy(this, message.disappearingTimerSeconds)"),
                "${task.name}'s already-exists branch must repair the row instead of returning success unchanged",
            )
        }
    }

    /** The text of the method whose signature starts at [signature], by brace matching from its opening brace. */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        if (start < 0) {
            fail("could not find `$signature` in ${messageServiceImpl.name}; this test's anchors have drifted")
        }
        var i = source.indexOf('{', start + signature.length)
        if (i < 0) {
            fail("no method body found for `$signature`")
        }
        val bodyStart = i
        var depth = 0
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(bodyStart, i + 1)
                    }
                }
            }
            i++
        }
        fail("unbalanced braces after `$signature`")
    }
}
