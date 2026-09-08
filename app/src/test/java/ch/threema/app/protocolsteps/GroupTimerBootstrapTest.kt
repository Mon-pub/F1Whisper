package ch.threema.app.protocolsteps

import ch.threema.domain.models.MessageId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * F1Whisper (tenth fork review, F10-05): the group's current disappearing timer must reach a member who joins after it
 * was set.
 *
 * **The defect.** `0x95` is sent only when a user CHANGES the picker, to the membership that existed at that moment. A
 * member added later was never a recipient of that message and had no other way to learn the policy, so they defaulted
 * to OFF and sent under it. The supplied report shows exactly that: a new member arrives through setup `0x4A`, name
 * `0x4B` and profile-picture state with no `0x95` anywhere in the bundle, sends with `timer=nulls advertised=0` into a
 * 30-second group, and converges only when another member happens to change the timer later, at which point incoming
 * content freezes at 30 and the next outgoing message finally advertises it. Multi-device was disabled in that report,
 * so reflection cannot explain the gap. It was an omission in the original custom feature, present since `d5da42b5`,
 * and every prior review missed it.
 *
 * **What is executable here.** The message-id derivation, which is the part with a real correctness argument: it has to
 * be distinct from the four ids the bundle already uses and stable across retries and process restarts.
 *
 * **What is asserted against the source.** That both state-transfer paths now carry the control, that it is targeted
 * rather than broadcast, that an explicit OFF is sent, and that setup still precedes it. Running these steps needs the
 * whole outgoing service graph and a live task codec, so this is the same split, for the same reason, as
 * `MediaMetadataWriteTest`. Each source assertion was proven red by removing the line it names.
 */
class GroupTimerBootstrapTest {

    private val updateSteps = File("src/main/java/ch/threema/app/protocolsteps/ActiveGroupUpdateSteps.kt")
    private val resyncSteps = File("src/main/java/ch/threema/app/protocolsteps/ActiveGroupStateResyncSteps.kt")
    private val groupCreateTask = File("src/main/java/ch/threema/app/tasks/GroupCreateTask.kt")
    private val groupUpdateTask = File("src/main/java/ch/threema/app/tasks/GroupUpdateTask.kt")

    // -----------------------------------------------------------------------------------------------------------------
    // The fifth message id
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the timer control has an id of its own`() {
        val ids = PredefinedMessageIds.random()

        val timerId = ids.disappearingTimerMessageId

        // Reusing setup's, name's, picture's or the call's id would make the server treat two different messages as
        // one, and the recipient dedupe one of them away.
        assertNotEquals(ids.messageId1.messageIdLong, timerId.messageIdLong)
        assertNotEquals(ids.messageId2.messageIdLong, timerId.messageIdLong)
        assertNotEquals(ids.messageId3.messageIdLong, timerId.messageIdLong)
        assertNotEquals(ids.messageId4.messageIdLong, timerId.messageIdLong)
    }

    @Test
    fun `the timer id is distinct from the setup id by construction, not by luck`() {
        // XOR with a fixed non-zero constant is a bijection on 64 bits, so this holds for every possible setup id
        // rather than with high probability. The other three are random, so those collide only as any two random ids
        // would.
        for (raw in listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0x4631575F544D5231L)) {
            val ids = PredefinedMessageIds(
                messageId1 = MessageId(raw),
                messageId2 = MessageId.random(),
                messageId3 = MessageId.random(),
                messageId4 = MessageId.random(),
            )
            assertNotEquals(raw, ids.disappearingTimerMessageId.messageIdLong, "collision for setup id $raw")
        }
    }

    @Test
    fun `the timer id is stable across task retries and process restarts`() {
        // The four ids exist so a retried or restarted task re-sends the SAME messages rather than duplicates. The
        // fifth has to hold that property too, and it does so by derivation: reconstructing the task data from what
        // was archived reproduces the id exactly.
        val original = PredefinedMessageIds.random()
        val roundTripped = PredefinedMessageIds(
            messageId1 = MessageId(original.messageId1.messageIdLong),
            messageId2 = MessageId(original.messageId2.messageIdLong),
            messageId3 = MessageId(original.messageId3.messageIdLong),
            messageId4 = MessageId(original.messageId4.messageIdLong),
        )

        assertEquals(
            original.disappearingTimerMessageId.messageIdLong,
            roundTripped.disappearingTimerMessageId.messageIdLong,
        )
        // And repeated reads within one instance agree, so a retry inside a single run cannot drift either.
        assertEquals(
            original.disappearingTimerMessageId.messageIdLong,
            original.disappearingTimerMessageId.messageIdLong,
        )
    }

    @Test
    fun `the resync path derives its own fifth id the same way`() {
        val ids = PreGeneratedMessageIds(
            firstMessageId = MessageId.random(),
            secondMessageId = MessageId.random(),
            thirdMessageId = MessageId.random(),
            fourthMessageId = MessageId.random(),
        )

        val timerId = ids.disappearingTimerMessageId

        assertNotEquals(ids.firstMessageId.messageIdLong, timerId.messageIdLong)
        assertNotEquals(ids.secondMessageId.messageIdLong, timerId.messageIdLong)
        assertNotEquals(ids.thirdMessageId.messageIdLong, timerId.messageIdLong)
        assertNotEquals(ids.fourthMessageId.messageIdLong, timerId.messageIdLong)
        assertEquals(timerId.messageIdLong, ids.disappearingTimerMessageId.messageIdLong)
    }

    @Test
    fun `the archived task encodings are untouched, so no v6-4-3-38 task can be dropped`() {
        // Both task data classes carry a comment saying any modification of their fields requires a recovery handler,
        // and TaskArchiverImpl DROPS a task whose normal decode and recovery both fail. A fifth persisted Long would
        // have needed a migration for the exact four-Long shape already on devices; deriving the id means the
        // serialised shape does not change at all, so there is no migration to get wrong.
        for (file in listOf(groupCreateTask, groupUpdateTask)) {
            val source = file.readText()
            val declaration = source.substring(source.indexOf("class SerializablePredefinedMessageIds("))
            val parameters = declaration.substring(0, declaration.indexOf(") {"))
            assertTrue(parameters.contains("private val messageId1: Long,"))
            assertTrue(parameters.contains("private val messageId4: Long,"))
            assertFalse(
                source.contains("messageId5"),
                "${file.name} must keep its four-Long encoding; a fifth persisted field needs a recovery handler",
            )
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The bundles
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the group update bundle carries the current timer`() {
        val body = bodyOf(updateSteps.readText(), "suspend fun runActiveGroupUpdateSteps(")
        assertTrue(
            body.contains("createGroupDisappearingTimer("),
            "a member added after the timer was set had no way at all to learn it",
        )
    }

    @Test
    fun `the resync bundle carries the current timer`() {
        val body = bodyOf(resyncSteps.readText(), "suspend fun runActiveGroupStateResyncSteps(")
        assertTrue(
            body.contains("createDisappearingTimerMessageHandle("),
            "a resync transfers current state, and the timer is part of it",
        )
    }

    @Test
    fun `the bootstrap targets only the members being added`() {
        val body = bodyOf(updateSteps.readText(), "suspend fun runActiveGroupUpdateSteps(")
        val call = body.substring(body.indexOf("createGroupDisappearingTimer("))
        assertTrue(
            call.contains("addMembers.intersect(groupModelData.otherMembers)"),
            "existing members must not receive a bootstrap; broadcasting an adopted value back to them is the " +
                "piggyback re-assert the third review removed",
        )
    }

    @Test
    fun `setup is still sent before the timer`() {
        // The recipient has to be able to resolve the group before it can apply a 0x95 to it. The send steps run each
        // stage over the senders in list order, so list position IS wire order.
        val body = bodyOf(updateSteps.readText(), "suspend fun runActiveGroupUpdateSteps(")
        assertTrue(body.indexOf("createGroupSetup(") < body.indexOf("createGroupDisappearingTimer("))

        val resyncBody = bodyOf(resyncSteps.readText(), "suspend fun runActiveGroupStateResyncSteps(")
        assertTrue(
            resyncBody.indexOf("createSetupMessageHandle(") < resyncBody.indexOf("createDisappearingTimerMessageHandle("),
        )
    }

    @Test
    fun `an off timer is sent as an explicit zero, not omitted`() {
        // A re-added member may still hold a positive timer from before they were removed. Silence would leave them
        // counting down messages the group no longer expires, so OFF has to be stated.
        val body = bodyOf(updateSteps.readText(), "internal fun currentGroupDisappearingTimerSeconds(")
        assertTrue(body.contains("?: 0"), "a missing or non-positive timer must resolve to an explicit 0")
        assertTrue(
            body.contains("takeIf { it > 0 }"),
            "a stored 0 or negative must be normalised to OFF rather than advertised as a timer",
        )
    }

    @Test
    fun `the bootstrap does not write local state or announce to the group`() {
        // setConversationTimer mutates the local model, inserts a DISAPPEARING_STATUS row and announces to the FULL
        // current membership. Using it for state transfer would create a status message on the creator for something
        // the user did not do, and re-broadcast an adopted value to everyone.
        for (file in listOf(updateSteps, resyncSteps)) {
            val source = file.readText()
            assertFalse(
                source.contains("setConversationTimer("),
                "${file.name} must not mutate local timer state",
            )
            assertFalse(
                source.contains("createDisappearingStatus("),
                "${file.name} must not create a local status row",
            )
        }
    }

    /**
     * The body of the declaration starting with [signature], by brace matching from the first `{` after it.
     */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "signature not found: $signature" }
        var index = source.indexOf('{', start)
        require(index >= 0) { "no body for: $signature" }
        var depth = 0
        val body = StringBuilder()
        while (index < source.length) {
            val character = source[index]
            if (character == '{') depth++
            if (depth > 0) body.append(character)
            if (character == '}') {
                depth--
                if (depth == 0) break
            }
            index++
        }
        return body.toString()
    }
}
