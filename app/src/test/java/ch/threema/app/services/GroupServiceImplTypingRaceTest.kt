package ch.threema.app.services

import io.mockk.mockk
import java.util.TimerTask
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F1Whisper (fork review M-11, follow-up review "Required Regression Coverage"): the group-typing
 * auto-reset race, exercised on a REAL [GroupServiceImpl]. The original defect: a stale reset
 * task that was already firing cleared a NEWER typing event unconditionally. The fix is a
 * per-(group, member) generation checked atomically under one lock; these tests capture the REAL
 * scheduled [TimerTask] and run it out of order — exactly the "old timeout races a newer typing
 * event" scenario the review requires.
 */
class GroupServiceImplTypingRaceTest {

    private fun newService(): GroupServiceImpl = GroupServiceImpl(
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
    )

    /** The REAL auto-reset task the service scheduled for (groupDatabaseId, identity). */
    private fun capturedResetTask(service: GroupServiceImpl, groupDatabaseId: Long, identity: String): TimerTask {
        val field = GroupServiceImpl::class.java.getDeclaredField("groupTypingTimerTasks")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val tasks = field.get(service) as Map<String, TimerTask>
        return requireNotNull(tasks["$groupDatabaseId:$identity"]) {
            "no reset task scheduled for $groupDatabaseId:$identity"
        }
    }

    @Test
    fun `stale reset task racing a newer typing event never clears the newer state`() {
        val service = newService()
        service.setMemberTyping(1L, "MEMBER01", true)
        val staleTask = capturedResetTask(service, 1L, "MEMBER01")

        // A newer typing event supersedes the first one (its generation is bumped)...
        service.setMemberTyping(1L, "MEMBER01", true)

        // ...and the STALE task fires anyway (TimerTask.cancel() cannot stop an already-firing
        // task — running it directly models exactly that window). The newer event must survive.
        staleTask.run()
        assertEquals(setOf("MEMBER01"), service.getTypingMembers(1L))
    }

    @Test
    fun `current reset task clears the member on genuine timeout`() {
        val service = newService()
        service.setMemberTyping(1L, "MEMBER01", true)
        capturedResetTask(service, 1L, "MEMBER01").run()
        assertEquals(emptySet(), service.getTypingMembers(1L))
    }

    @Test
    fun `stale reset task after an explicit stop leaves the state cleared and other members intact`() {
        val service = newService()
        service.setMemberTyping(1L, "MEMBER01", true)
        service.setMemberTyping(1L, "MEMBER02", true)
        val staleTask = capturedResetTask(service, 1L, "MEMBER01")

        service.setMemberTyping(1L, "MEMBER01", false)
        staleTask.run()

        assertEquals(setOf("MEMBER02"), service.getTypingMembers(1L))
    }
}
