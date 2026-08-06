package ch.threema.app.services

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * F1Whisper (fourth fork review, F4-12): a destroyed self-update service instance must not still clear or publish shared
 * state.
 *
 * The defect: the download worker checked "am I still alive?" while holding the lifecycle lock, released it, and only then
 * performed the side effect. `onDestroy` could land in that gap, so a predecessor could pass the startup check, be torn
 * down, and then delete the final APK and clear the ready-state record its REPLACEMENT had already published; or pass the
 * publication check, be torn down, and then rename its own file over the shared final name and record it as ready after its
 * authority had ended. The app would show an update as ready whose file was missing or had been replaced, or a newer
 * instance's completed download would be erased. Interrupting the worker does not undo a filesystem or preference write
 * that is already past the check, and the operation-unique partial filenames protect the partials and nothing else.
 *
 * These tests drive the production [ApkUpdateLifecycleOwnership] through both orderings the review named - destroy before
 * the transition and destroy during it - including service recreation, with latches rather than sleeps.
 * [legacyCheckThenActLetsAPredecessorEraseItsSuccessor] is the control: it performs the old check-then-act inline and shows
 * the replacement's published state being erased.
 */
class ApkUpdateLifecycleOwnershipTest {
    private val timeoutSeconds = 5L
    private val blockedWindowMillis = 500L

    private val service = File("src/onprem/java/ch/threema/app/services/ApkUpdateDownloadService.java")

    /** Stands in for the process-wide update state: one final filename, one ready-state record. */
    private class SharedUpdateState {
        @Volatile
        var readyFile: String? = null

        @Volatile
        var readyVersionCode: Long? = null

        fun record(path: String, versionCode: Long) {
            readyFile = path
            readyVersionCode = versionCode
        }

        fun clear() {
            readyFile = null
            readyVersionCode = null
        }
    }

    private val shared = SharedUpdateState()

    // -----------------------------------------------------------------------------------------------------------------------------
    // Destroy before the transition.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a destroyed instance cannot clear its successor's ready state`() {
        val predecessor = ApkUpdateLifecycleOwnership()
        val successor = ApkUpdateLifecycleOwnership()

        // The replacement has already published an update.
        assertTrue(successor.runIfOwned { shared.record("/updates/F1Whisper.apk", 1186) })

        // The predecessor is torn down, then its worker reaches the startup cleanup.
        predecessor.destroy()
        val cleaned = predecessor.runIfOwned { shared.clear() }

        assertFalse(cleaned, "a destroyed instance has no authority over the shared state")
        assertEquals("/updates/F1Whisper.apk", shared.readyFile, "the successor's published update must survive")
        assertEquals(1186L, shared.readyVersionCode)
    }

    @Test
    fun `a destroyed instance cannot publish`() {
        val ownership = ApkUpdateLifecycleOwnership()
        ownership.destroy()

        val published = ownership.runIfOwned { shared.record("/updates/stale.apk", 1180) }

        assertFalse(published)
        assertNull(shared.readyFile, "nothing may be recorded as ready after teardown")
    }

    @Test
    fun `a live instance performs its transition`() {
        val ownership = ApkUpdateLifecycleOwnership()

        assertTrue(ownership.runIfOwned { shared.record("/updates/F1Whisper.apk", 1186) })

        assertEquals("/updates/F1Whisper.apk", shared.readyFile)
        assertFalse(ownership.isDestroyed())
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Destroy during the transition.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `destroy waits for a transition that is already running`() {
        val ownership = ApkUpdateLifecycleOwnership()
        val inTransition = CountDownLatch(1)
        val mayFinish = CountDownLatch(1)
        val destroyReturned = CountDownLatch(1)

        val publisher = Thread {
            ownership.runIfOwned {
                inTransition.countDown()
                mayFinish.await()
                shared.record("/updates/F1Whisper.apk", 1186)
            }
        }
        publisher.start()
        assertTrue(inTransition.await(timeoutSeconds, TimeUnit.SECONDS))

        val destroyer = Thread {
            ownership.destroy()
            destroyReturned.countDown()
        }
        destroyer.start()

        assertFalse(
            destroyReturned.await(blockedWindowMillis, TimeUnit.MILLISECONDS),
            "destroy must not return while a transition is mid-write; that is the whole point of the critical section",
        )

        mayFinish.countDown()
        publisher.join(timeoutSeconds * 1000)
        destroyer.join(timeoutSeconds * 1000)

        assertTrue(ownership.isDestroyed())
        assertEquals(
            "/updates/F1Whisper.apk",
            shared.readyFile,
            "a transition that had already started is completed, not left half-applied",
        )
    }

    @Test
    fun `a transition beginning after destroy is refused`() {
        val ownership = ApkUpdateLifecycleOwnership()
        val destroyed = CountDownLatch(1)
        var ran = true

        val worker = Thread {
            destroyed.await()
            ran = ownership.runIfOwned { shared.record("/updates/stale.apk", 1180) }
        }
        worker.start()

        ownership.destroy()
        destroyed.countDown()
        worker.join(timeoutSeconds * 1000)

        assertFalse(ran)
        assertNull(shared.readyFile)
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Service recreation.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a recreated instance owns the state its predecessor has lost`() {
        val predecessor = ApkUpdateLifecycleOwnership()
        predecessor.destroy()

        val recreated = ApkUpdateLifecycleOwnership()

        assertTrue(recreated.runIfOwned { shared.record("/updates/F1Whisper.apk", 1186) })
        assertFalse(predecessor.runIfOwned { shared.clear() })
        assertEquals("/updates/F1Whisper.apk", shared.readyFile)
    }

    @Test
    fun `a predecessor mid-publish cannot overwrite what the recreated instance publishes afterwards`() {
        val predecessor = ApkUpdateLifecycleOwnership()
        val recreated = ApkUpdateLifecycleOwnership()
        val inTransition = CountDownLatch(1)
        val mayFinish = CountDownLatch(1)

        val stale = Thread {
            predecessor.runIfOwned {
                inTransition.countDown()
                mayFinish.await()
                shared.record("/updates/stale.apk", 1180)
            }
        }
        stale.start()
        assertTrue(inTransition.await(timeoutSeconds, TimeUnit.SECONDS))

        // Teardown blocks until the predecessor's write finishes, so the recreated instance's own publish is strictly
        // after it and wins.
        mayFinish.countDown()
        predecessor.destroy()
        stale.join(timeoutSeconds * 1000)

        assertTrue(recreated.runIfOwned { shared.record("/updates/F1Whisper.apk", 1186) })

        assertEquals("/updates/F1Whisper.apk", shared.readyFile)
        assertEquals(1186L, shared.readyVersionCode)
        assertFalse(predecessor.runIfOwned { shared.clear() }, "and the predecessor can never come back for it")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Failures inside a transition.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a transition that throws is distinguishable from one that was refused`() {
        val ownership = ApkUpdateLifecycleOwnership()

        try {
            ownership.runIfOwned { throw IOException("could not move validated update into place") }
            fail("the transition's own failure must propagate rather than be reported as lost ownership")
        } catch (e: IOException) {
            assertEquals("could not move validated update into place", e.message)
        }

        assertTrue(ownership.runIfOwned { shared.record("/updates/F1Whisper.apk", 1186) }, "and must not wedge the monitor")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The service uses it for both transitions.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `both shared-state transitions in the service run under ownership`() {
        val source = service.readText()

        assertEquals(
            2,
            Regex("ownership\\.runIfOwned\\(").findAll(source).count(),
            "the startup cleanup/ready-clear and the publish rename/ready-record must each be one critical section",
        )
        assertTrue(
            source.contains("ownership.destroy();"),
            "onDestroy must end authority through the same monitor those transitions hold",
        )
        assertFalse(
            source.contains("private boolean destroyed"),
            "a bare flag under a lock is what allowed the check and the act to be split",
        )
        // The slow work must stay outside: a lock held across a download would block teardown for its duration.
        val publishAt = source.indexOf("ownership.runIfOwned(() -> {\n                if (!outFile.renameTo(finalFile))")
        val validateAt = source.indexOf("validateDownloadedApk(outFile)")
        assertTrue(publishAt >= 0 && validateAt >= 0, "this test's anchors have drifted")
        assertTrue(validateAt < publishAt, "validation must complete before the critical section, not inside it")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Legacy control: check, release, act.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun legacyCheckThenActLetsAPredecessorEraseItsSuccessor() {
        val lock = Object()
        var destroyed = false

        // The replacement has published an update.
        shared.record("/updates/F1Whisper.apk", 1186)

        // The old shape: check ownership under the lock...
        synchronized(lock) {
            if (destroyed) {
                fail("precondition: the predecessor is still alive at the moment of the check")
            }
        }
        // ...release it, and only then act. onDestroy lands right here.
        synchronized(lock) { destroyed = true }
        shared.clear()

        assertNull(
            shared.readyFile,
            "this is the defect: a torn-down predecessor erased the ready state its replacement had published",
        )
    }
}
