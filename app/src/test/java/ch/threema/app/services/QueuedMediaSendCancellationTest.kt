package ch.threema.app.services

import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.services.MessageRowHarness.Companion.CONTACT_TABLE
import ch.threema.app.services.MessageRowHarness.Companion.GROUP_TABLE
import ch.threema.app.tasks.PersistentTaskRowGate
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.group.GroupMessageModel
import java.io.File
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (ninth follow-up review, F9-01): a message the user deleted may not upload.
 *
 * **The failure this reproduces.** Every media send goes through ONE worker, so sending two attachments over a slow
 * link leaves the second waiting - visible as PENDING, for as long as the first upload or its retry backoff takes.
 * Delete it in that interval and the deletion cancelled nothing: it looked for a send machine, which is created inside
 * `send()`, and for a registered uploader, which is registered in a later machine step, and a queued process owns
 * neither. The retained backoff future, kept for exactly this, was never asked. When the process finally started it
 * never asked whether it still had a message to send: it encrypted the file, discarded the `false` its own pre-upload
 * save returned, uploaded the content blob and the thumbnail blob, and only then met the guarded handoff. The peer got
 * nothing and the payload had already left the device - as a `persist=1` blob for a group, with nothing left to ask the
 * server to remove it. The window is the length of the first upload, not a thread race.
 *
 * **The three shipped layers, each closing the window the next cannot see.**
 *
 * - the deletion cancels the queued future first, keyed by the message's own uid, so a process that has not started
 *   never starts, and a sibling in a multi-recipient batch is untouched;
 * - the process asks the ROW at entry, before the send machine, the encryption and any uploader, so it is stopped even
 *   when there was no future to cancel (`addToQueue` registers it just after submitting it) or when it was already
 *   running;
 * - a refused write is terminal rather than discarded, in the media pipeline's pre-upload save and in the save that
 *   ends preprocessing.
 *
 * **What is executed here and what is asserted from source.** The queue is real: these tests drive the shipped
 * [MessageSendingServiceExponentialBackOff] over the shipped single-thread worker, so "a cancelled queued process never
 * runs its body" is demonstrated rather than described. What the body would have done is recorded by a fake, because
 * `MessageServiceImpl` and the receivers cannot be constructed in a JVM unit test (see [MessageRowHarness]); that the
 * real body's uploads and handoff live INSIDE `send()`, after the gate, is asserted from source below.
 */
class QueuedMediaSendCancellationTest {

    private val harness = MessageRowHarness(CONTACT_TABLE, GROUP_TABLE)
    private val state = RecordingState()
    private val service = MessageSendingServiceExponentialBackOff(state)
    private val occupied = CountDownLatch(1)
    private val release = CountDownLatch(1)

    @AfterTest
    fun tearDown() {
        // Nothing may be left holding the shared worker: it is a static single thread for the whole JVM.
        release.countDown()
        harness.close()
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The reported sequence: a second attachment, deleted while it waits behind the first.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a contact media process deleted while it waits never uploads anything`() {
        val first = occupyTheWorker()
        val deleted = RecordingProcess(contactModel(41))
        val afterwards = RecordingProcess(contactModel(43))

        service.addToQueue(deleted)
        service.addToQueue(afterwards)

        // What the deletion does now: `abortPendingSend` drops the queued future before it looks for a send machine or
        // an uploader, neither of which a queued process has.
        service.abort(deleted.messageModel.uid)

        drainTheQueue(afterwards)

        assertFalse(deleted.started, "the process must never have run")
        assertEquals(emptyList(), deleted.uploads, "no content blob, no thumbnail blob")
        assertEquals(0, deleted.contentTasks, "and no outgoing content task")
        assertEquals(0, deleted.completions, "no completion for a send that is not happening")
        assertEquals(0, deleted.listenerEvents, "and nothing published to the chat")
        assertEquals(1, first.runs, "the upload it was waiting behind is unaffected")
        assertTrue(state.failures.isEmpty(), "a message deleted on purpose must not report a send failure")
    }

    @Test
    fun `a group media process deleted while it waits requests no persistent blob`() {
        val first = occupyTheWorker()
        val deleted = RecordingProcess(groupModel(42))
        val afterwards = RecordingProcess(groupModel(44))

        service.addToQueue(deleted)
        service.addToQueue(afterwards)
        service.abort(deleted.messageModel.uid)

        drainTheQueue(afterwards)

        assertFalse(deleted.started)
        assertEquals(emptyList(), deleted.uploads, "a group upload is requested with persist=1 - see the source assertion below")
        assertEquals(0, deleted.contentTasks)
        assertEquals(1, first.runs)
    }

    @Test
    fun `the control - a queued process that was not deleted runs exactly once`() {
        val first = occupyTheWorker()
        val ordinary = RecordingProcess(contactModel(41))
        val afterwards = RecordingProcess(contactModel(43))

        service.addToQueue(ordinary)
        service.addToQueue(afterwards)

        drainTheQueue(afterwards)

        assertEquals(1, ordinary.runs, "an ordinary queued send still runs, and runs once")
        assertEquals(listOf(CONTENT_BLOB, THUMBNAIL_BLOB), ordinary.uploads)
        assertEquals(1, ordinary.contentTasks, "exactly one content task")
        assertEquals(1, ordinary.completions)
        assertEquals(1, first.runs)
    }

    @Test
    fun `deleting one message in a batch leaves its siblings sending`() {
        val first = occupyTheWorker()
        val deleted = RecordingProcess(contactModel(41))
        val sibling = RecordingProcess(contactModel(42))

        // The same media item to two recipients: one model, one uid, one queued process each.
        service.addToQueue(deleted)
        service.addToQueue(sibling)

        service.abort(deleted.messageModel.uid)

        // The sibling is the barrier here rather than a third process: it was queued AFTER the deleted one, so its
        // completion proves both that the deleted one has had its turn and that the cancellation did not take it too.
        drainTheQueue(sibling)

        assertFalse(deleted.started, "the deleted recipient's copy is dropped")
        assertEquals(1, sibling.runs, "and the other recipient's copy still sends, exactly once")
        assertEquals(1, sibling.contentTasks)
        assertEquals(1, first.runs)
    }

    @Test
    fun `cancelling a message that already finished changes nothing`() {
        val only = RecordingProcess(contactModel(41))
        service.addToQueue(only)
        assertTrue(only.finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "it must have run")

        service.abort(only.messageModel.uid)

        assertEquals(1, only.runs, "a completed send is not re-run, and not un-sent")
        assertTrue(state.failures.isEmpty())
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The second layer: a process that starts anyway asks the row, and the row refuses.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a process entering after a delete-for-everyone is refused before it uploads`() {
        harness.insertContactRow(messageId = 41, outbox = true, state = MessageState.PENDING)
        assertTrue(harness.deleteForEveryone(CONTACT_TABLE, 41, DELETED_AT))

        assertFalse(
            PersistentTaskRowGate.transmits(harness.readModel(CONTACT_TABLE, 41)),
            "the row the process reloads at entry is a tombstone, and it may not be sent from",
        )
        assertNull(harness.stringOf(CONTACT_TABLE, 41, "body"), "the tombstone is unchanged by the refusal")
        assertEquals(1, harness.rowCount(CONTACT_TABLE, 41), "and survives for the deletion-control task")
    }

    @Test
    fun `a process entering after a hard delete is refused too`() {
        harness.insertGroupRow(messageId = 42, state = MessageState.PENDING)
        harness.hardDelete(GROUP_TABLE, 42)

        assertFalse(
            PersistentTaskRowGate.transmits(harness.readModel(GROUP_TABLE, 42)),
            "a row that has gone and a row that was deleted are the same answer to a sender",
        )
    }

    @Test
    fun `the control - an undeleted row lets its queued process through`() {
        harness.insertContactRow(messageId = 41, outbox = true, state = MessageState.PENDING)

        assertTrue(
            PersistentTaskRowGate.transmits(harness.readModel(CONTACT_TABLE, 41)),
            "an ordinary pending media message must still be sendable when its turn comes",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The call sites. Source assertions, supplementing the behaviour above: MessageServiceImpl cannot be constructed
    // in a JVM unit test, so what is executed above is the queue and the row, and what is pinned here is the wiring.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `deleting a message drops it from the sending queue before anything else`() {
        val abort = methodBody("private void abortPendingSend(", "private boolean mayStillSend(")

        assertTrue(
            abort.contains("messageSendingService.abort(messageModel.getUid());"),
            "the queued future is what a not-yet-started process has, and it must be cancelled by its own uid",
        )
        assertTrue(
            abort.indexOf("messageSendingService.abort(") < abort.indexOf("getSendMachine("),
            "the queue comes first: a process that never starts needs none of the rest",
        )
        assertTrue(
            abort.indexOf("getSendMachine(") < abort.indexOf("cancelUploader("),
            "and the running process is stopped in the order it built its resources",
        )
    }

    @Test
    fun `both queued media processes ask the row before they encrypt or upload`() {
        val source = File(SERVICE).readText()
        // Each process body on its own, bounded by the statement that ends it, so that a gate belonging to the
        // surrounding method cannot stand in for the one that has to be inside `send()`.
        val bodies = Regex("public boolean send\\(\\) throws Exception \\{").findAll(source).map { entry ->
            source.substring(entry.range.first, source.indexOf("removeSendMachine(sendMachine);", entry.range.first))
        }.toList()

        assertEquals(2, bodies.size, "the first-send process and the resend process")
        bodies.forEach { body ->
            val gate = body.indexOf("mayStillSend(")
            val machine = body.indexOf("getSendMachine(")
            val encrypt = body.indexOf("encryptInplace(")
            val upload = body.indexOf("initUploader(")

            assertTrue(gate >= 0, "every queued media process must ask the row at entry")
            assertTrue(machine >= 0 && encrypt >= 0 && upload >= 0, "and must still be the process this pins")
            assertTrue(gate < machine, "before the send machine exists")
            assertTrue(gate < encrypt, "before the file is encrypted")
            assertTrue(gate < upload, "and before any blob leaves the device")
        }
    }

    @Test
    fun `preprocessing output is neither persisted nor queued for a row that has gone`() {
        val encryptAndSend = methodBody("private boolean encryptAndSend(", "private boolean createFileMessagesAndSetPending(")

        assertTrue(
            encryptAndSend.indexOf("if (!save(messageModel)) {") in 0 until encryptAndSend.indexOf("writeConversationMedia("),
            "the save that ends preprocessing decides whether the derived media is written at all",
        )
        assertTrue(
            encryptAndSend.contains("fileService.removeMessageFiles(messageModel, true);"),
            "and the output the losing operation already wrote goes with it",
        )
        assertTrue(
            encryptAndSend.indexOf("mayStillSend(") < encryptAndSend.indexOf("addToQueue("),
            "a deleted message is not queued in the first place",
        )
    }

    @Test
    fun `a refused pre-upload save stops the media machine instead of uploading`() {
        val encryptAndSend = methodBody("private boolean encryptAndSend(", "private boolean createFileMessagesAndSetPending(")
        val step = encryptAndSend.substring(
            encryptAndSend.indexOf("if (hasChanges && !save(messageModel)) {"),
            encryptAndSend.indexOf("initUploader("),
        )

        assertTrue(step.contains("sendMachine.abort();"), "the refusal is terminal, three steps before the handoff")
        assertFalse(
            encryptAndSend.contains("\n                                save(messageModel);\n"),
            "no writer in this pipeline may discard the answer again",
        )
    }

    @Test
    fun `a group blob is the persistent kind the queue test prevented`() {
        val decision = methodBody(
            "private boolean shouldPersistUploadForMessage(",
            "private String getLoaderKey(",
        )

        assertTrue(
            decision.contains("} else if (messageModel instanceof GroupMessageModel) {"),
            "the group branch must still exist",
        )
        assertTrue(
            decision.substring(decision.indexOf("instanceof GroupMessageModel")).contains("return !isNotesGroup;"),
            "a group upload asks the server to KEEP the blob, which is why an orphan one matters",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The scenario, written once.
    // -----------------------------------------------------------------------------------------------------------------------------

    /** The first attachment: it takes the single worker and holds it until [release], as a slow upload does. */
    private fun occupyTheWorker(): RecordingProcess {
        val first = RecordingProcess(contactModel(40), hold = true)
        service.addToQueue(first)
        assertTrue(occupied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the first process must hold the worker")
        return first
    }

    /**
     * Let the first upload finish and wait for a process queued AFTER the one under test.
     *
     * The worker is FIFO and single-threaded, so once [afterwards] has run, anything queued before it has had its turn.
     * That is what makes "it never ran" an assertion rather than a timeout.
     */
    private fun drainTheQueue(afterwards: RecordingProcess) {
        release.countDown()
        assertTrue(
            afterwards.finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "the queue must have drained past the process under test, and ${afterwards.messageModel.uid} must have run",
        )
    }

    private fun contactModel(messageId: Int): MessageModel = MessageModel().also {
        it.id = messageId
        it.uid = "uid-$messageId"
        it.isOutbox = true
        it.state = MessageState.PENDING
    }

    private fun groupModel(messageId: Int): GroupMessageModel = GroupMessageModel().also {
        it.id = messageId
        it.uid = "uid-$messageId"
        it.groupId = 7
        it.isOutbox = true
        it.state = MessageState.PENDING
    }

    private fun methodBody(from: String, to: String): String {
        val source = File(SERVICE).readText()
        val start = source.indexOf(from)
        val end = source.indexOf(to, start + 1)
        assertTrue(start >= 0 && end > start, "anchors '$from' and '$to' must both exist, in that order")
        return source.substring(start, end)
    }

    /** What a media send does once it starts, recorded rather than performed. */
    private inner class RecordingProcess(
        private val model: AbstractMessageModel,
        private val hold: Boolean = false,
    ) : MessageSendingService.MessageSendingProcess {
        @Volatile
        var started = false

        @Volatile
        var runs = 0
        val uploads = mutableListOf<String>()
        var contentTasks = 0
        var completions = 0
        var listenerEvents = 0
        val finished = CountDownLatch(1)

        override fun getReceiver(): MessageReceiver<AbstractMessageModel>? = null

        override fun getMessageModel(): AbstractMessageModel = model

        override fun send(): Boolean {
            started = true
            if (hold) {
                occupied.countDown()
                release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } else {
                // The real body's order: encrypt, upload the content blob, upload the thumbnail blob, hand off.
                uploads += CONTENT_BLOB
                uploads += THUMBNAIL_BLOB
                contentTasks++
                completions++
                listenerEvents++
            }
            runs++
            finished.countDown()
            return true
        }
    }

    private class RecordingState : MessageSendingService.MessageSendingServiceState {
        val failures = mutableListOf<String?>()

        override fun processingFailed(
            messageModel: AbstractMessageModel?,
            receiver: MessageReceiver<AbstractMessageModel>?,
            cause: Exception?,
        ) {
            failures += messageModel?.uid
        }

        override fun exception(x: Exception?, tries: Int) {
            failures += x?.message
        }
    }

    private companion object {
        const val SERVICE = "src/main/java/ch/threema/app/services/MessageServiceImpl.java"
        const val CONTENT_BLOB = "content"
        const val THUMBNAIL_BLOB = "thumbnail"
        const val TIMEOUT_SECONDS = 10L
        val DELETED_AT = Date(MessageRowHarness.BASE_TIME + 60_000).time
    }
}
