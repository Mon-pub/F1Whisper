package ch.threema.app.services.messageplayer

import ch.threema.app.services.FileService
import ch.threema.app.services.MediaConsumeOutcome
import ch.threema.app.services.MessageService
import ch.threema.domain.protocol.csp.messages.file.FileData
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.data.media.FileDataModel
import ch.threema.storage.models.group.GroupMessageModel
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * F1Whisper (twelfth fork review, F12-01): a table-local id collision must not burn unrelated audio.
 *
 * **The defect.** All three listen-once registries were keyed by the integer row id. Contact, group and
 * distribution-list messages live in separate tables with separate AUTOINCREMENT sequences, so equal ids across the
 * namespaces are normal - and `AudioMessagePlayer.open` consulted the burn barrier BEFORE asking whether the current
 * model was a listen-once message at all. On a collision the ordinary message was refused and then handed to
 * `ListenOnceEnforcer.burn`, whose metadata write selects the unrelated model's own table by runtime subtype and
 * whose file removal uses the unrelated model's UID: an ordinary audio message was permanently marked consumed and
 * lost its media because a listen-once message elsewhere shared its number.
 *
 * **What these tests execute.** The review's required proof, on the production path the JVM can reach: the REAL
 * `ListenOnceEnforcer.burn` (worker held) raises the REAL barrier for a group listen-once row with id N; the REAL
 * `gateOf` + `ListenOnceMessageIdentity` + `ListenOnceAdmissionDecision` - exactly what `open()` now consults, pinned
 * in [ListenOnceBurnBarrierTest] - then admit an ordinary contact audio row with the same id, and the mock services
 * prove no metadata mutation and no file removal ever names the ordinary row. Role-reversed, the exact barred message
 * stays refused and its retry targets only itself. The player shell around this routing needs a device (media3
 * controller); the routing itself is what changed and what runs here.
 */
class ListenOnceIdentityCollisionTest {

    private val collidingId = 47

    private val messageService = mockk<MessageService>()
    private val fileService = mockk<FileService>(relaxed = true)

    private val worker = ManualWorker()
    private lateinit var productionWorker: Executor

    /** Every model the durable write was asked to touch, in order. */
    private val mutatedModels = mutableListOf<AbstractMessageModel>()

    /** Every model whose files were removed, in order. */
    private val removedFilesOf = mutableListOf<AbstractMessageModel>()

    @BeforeTest
    fun setUp() {
        ListenOnceBurnBarrier.forgetAll()
        ListenOnceOwnership.forgetAll()
        productionWorker = ListenOnceEnforcer.burnWorker
        ListenOnceEnforcer.burnWorker = worker
        every { messageService.consumeAndUpdateMediaMetadata(capture(mutatedModels), any()) } returns MediaConsumeOutcome.APPLIED
        every {
            fileService.removeMessageFiles(capture(removedFilesOf), any<Boolean>())
        } returns true
    }

    @AfterTest
    fun tearDown() {
        ListenOnceEnforcer.burnWorker = productionWorker
        ListenOnceBurnBarrier.forgetAll()
        ListenOnceOwnership.forgetAll()
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Fixtures: two real model namespaces, one integer id
    // -----------------------------------------------------------------------------------------------------------------

    private fun groupListenOnce(): GroupMessageModel =
        GroupMessageModel().apply {
            id = collidingId
            uid = "11111111-aaaa-aaaa-aaaa-111111111111"
            type = MessageType.FILE
            isOutbox = false
            fileData = audioFileData(listenOnce = true)
        }

    private fun contactOrdinaryAudio(): MessageModel =
        MessageModel().apply {
            id = collidingId
            uid = "22222222-bbbb-bbbb-bbbb-222222222222"
            type = MessageType.FILE
            isOutbox = false
            fileData = audioFileData(listenOnce = false)
        }

    private fun contactListenOnce(): MessageModel =
        MessageModel().apply {
            id = collidingId
            uid = "33333333-cccc-cccc-cccc-333333333333"
            type = MessageType.FILE
            isOutbox = false
            fileData = audioFileData(listenOnce = true)
        }

    private fun groupOrdinaryAudio(): GroupMessageModel =
        GroupMessageModel().apply {
            id = collidingId
            uid = "44444444-dddd-dddd-dddd-444444444444"
            type = MessageType.FILE
            isOutbox = false
            fileData = audioFileData(listenOnce = false)
        }

    private fun audioFileData(listenOnce: Boolean): FileDataModel =
        FileDataModel(
            /* mimeType = */
            "audio/aac",
            /* thumbnailMimeType = */
            null,
            /* fileSize = */
            2048L,
            /* fileName = */
            null,
            /* renderingType = */
            FileData.RENDERING_MEDIA,
            /* caption = */
            null,
            /* isDownloaded = */
            true,
            /* metaData = */
            if (listenOnce) mutableMapOf(FileDataModel.METADATA_KEY_LISTEN_ONCE to true) else mutableMapOf(),
        )

    /** Exactly what `open()` consults for [model], per the wiring pin in [ListenOnceBurnBarrierTest]. */
    private fun admissionOf(model: AbstractMessageModel): ListenOnceAdmission =
        ListenOnceAdmissionDecision.decide(
            ListenOnceEnforcer.gateOf(model),
            ListenOnceBurnBarrier.isSettling(ListenOnceMessageIdentity.of(model)),
        )

    // -----------------------------------------------------------------------------------------------------------------
    // The identity itself
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `equal row ids in different tables are different identities`() {
        assertNotEquals(
            ListenOnceMessageIdentity.of(groupListenOnce()),
            ListenOnceMessageIdentity.of(contactOrdinaryAudio()),
            "the collision is exactly two tables sharing an integer; the identity must keep them apart",
        )
        // And the fallback for a model with no UID yet is still namespaced, so it cannot alias either.
        assertNotEquals(
            ListenOnceMessageIdentity.of(GroupMessageModel().apply { id = collidingId }),
            ListenOnceMessageIdentity.of(MessageModel().apply { id = collidingId }),
        )
    }

    @Test
    fun `two derivations from the same message are the same registry key`() {
        val model = groupListenOnce()
        assertEquals(
            ListenOnceMessageIdentity.of(model),
            ListenOnceMessageIdentity.of(model),
            "value semantics are what let raise() and isSettling() meet on separately derived keys",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The review's proof, group settling vs contact ordinary
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a settling group burn does not bar an ordinary contact audio row with the same id`() {
        val settling = groupListenOnce()
        ListenOnceEnforcer.burn(settling, messageService, fileService, false)
        assertTrue(ListenOnceBurnBarrier.isSettling(ListenOnceMessageIdentity.of(settling)), "the burn is in flight")

        val admission = admissionOf(contactOrdinaryAudio())

        assertEquals(
            ListenOnceAdmission.ADMIT,
            admission,
            "an ordinary audio message in another table must be admitted normally while the burn settles",
        )
    }

    @Test
    fun `the ordinary row receives no metadata mutation and no file removal`() {
        val settling = groupListenOnce()
        ListenOnceEnforcer.burn(settling, messageService, fileService, false)
        worker.runNext()

        assertTrue(mutatedModels.isNotEmpty(), "the settling burn itself must have written")
        for (model in mutatedModels) {
            assertTrue(model === settling, "every metadata write must target the settling message, nothing else")
        }
        for (model in removedFilesOf) {
            assertTrue(model === settling, "every file removal must target the settling message, nothing else")
        }
    }

    @Test
    fun `a settling group burn does not bar an unplayed listen-once contact row with the same id`() {
        // The strongest collision, where the applicability belt cannot help: BOTH messages are listen-once, so only
        // the identity keying keeps them apart. Under integer keys the unplayed contact message was refused as
        // "settling" and the refusal's re-drive then burned it - destroying a listen-once recording that had never
        // been played.
        val settling = groupListenOnce()
        ListenOnceEnforcer.burn(settling, messageService, fileService, false)

        assertEquals(
            ListenOnceAdmission.CLAIM_BEFORE_RELEASE,
            admissionOf(contactListenOnce()),
            "an unplayed listen-once message in another table must go to its own first playback, not to a burn",
        )
    }

    @Test
    fun `role-reversed - a settling contact burn does not bar an ordinary group audio row`() {
        val settling = contactListenOnce()
        ListenOnceEnforcer.burn(settling, messageService, fileService, false)

        assertEquals(ListenOnceAdmission.ADMIT, admissionOf(groupOrdinaryAudio()))

        worker.runNext()
        for (model in mutatedModels + removedFilesOf) {
            assertTrue(model === settling, "the reversed roles must not change who is written")
        }
    }

    @Test
    fun `the exact barred message remains refused and its retry targets only itself`() {
        val settling = groupListenOnce()
        ListenOnceEnforcer.burn(settling, messageService, fileService, false)

        assertEquals(
            ListenOnceAdmission.REFUSE_SETTLING,
            admissionOf(settling),
            "the barred message itself is exactly who the barrier must refuse",
        )

        // The refusal path re-drives the burn with the same model it decided for (pinned at the source); execute
        // that retry and prove it still only touches the barred message.
        ListenOnceEnforcer.burn(settling, messageService, fileService, false)
        worker.runNext()
        worker.runNext()
        assertTrue(mutatedModels.isNotEmpty())
        for (model in mutatedModels + removedFilesOf) {
            assertTrue(model === settling, "the retry must target the barred message and nothing else")
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The applicability belt and the preserved dominance order
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a polluted registry still cannot refuse or burn a message that is not listen-once`() {
        // No production path can put an ordinary message's own identity into the barrier; if one ever did, the
        // admission must STILL leave the unrelated message alone - applicability comes first.
        val ordinary = contactOrdinaryAudio()
        ListenOnceBurnBarrier.raise(ListenOnceMessageIdentity.of(ordinary))

        assertEquals(ListenOnceAdmission.ADMIT, admissionOf(ordinary))
    }

    @Test
    fun `for an applicable message the settling barrier still dominates the durable gate`() {
        // F11-05's ordering, preserved through the restructure: the barrier exists for the window in which the
        // durable row is WRONG (fail-open playback, burn in flight), so it must win over whatever the row says.
        assertEquals(
            ListenOnceAdmission.REFUSE_SETTLING,
            ListenOnceAdmissionDecision.decide(ListenOnceGate.PLAYABLE, burnSettling = true),
            "a row that still reads playable is exactly the window the barrier closes",
        )
        assertEquals(
            ListenOnceAdmission.REFUSE_SETTLING,
            ListenOnceAdmissionDecision.decide(ListenOnceGate.BLOCKED_CONSUMED, burnSettling = true),
        )
        assertEquals(
            ListenOnceAdmission.REFUSE_SETTLING,
            ListenOnceAdmissionDecision.decide(ListenOnceGate.BLOCKED_BURN_PENDING, burnSettling = true),
        )
    }

    @Test
    fun `without a settling burn the durable gate decides, unchanged`() {
        assertEquals(
            ListenOnceAdmission.CLAIM_BEFORE_RELEASE,
            ListenOnceAdmissionDecision.decide(ListenOnceGate.PLAYABLE, burnSettling = false),
        )
        assertEquals(
            ListenOnceAdmission.REFUSE_SPENT,
            ListenOnceAdmissionDecision.decide(ListenOnceGate.BLOCKED_BURN_PENDING, burnSettling = false),
        )
        assertEquals(
            ListenOnceAdmission.REFUSE_SPENT,
            ListenOnceAdmissionDecision.decide(ListenOnceGate.BLOCKED_CONSUMED, burnSettling = false),
        )
        assertEquals(
            ListenOnceAdmission.ADMIT,
            ListenOnceAdmissionDecision.decide(ListenOnceGate.NOT_APPLICABLE, burnSettling = false),
        )
    }

    @Test
    fun `ownership keyed by identity refuses only the same message, not a colliding id`() {
        // The same collision class in the ownership registry: a playing contact message with id N used to make an
        // unrelated group message with id N look owned, refusing its playback and suppressing its repair burn.
        val token = Any()
        assertTrue(ListenOnceOwnership.acquire(ListenOnceMessageIdentity.of(contactListenOnce()), token))

        assertFalse(
            ListenOnceOwnership.isActive(ListenOnceMessageIdentity.of(groupListenOnce())),
            "a session in one table must not read as a live owner of a message in another",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------------------------------------------------

    private class ManualWorker : Executor {
        private val queue = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queue.addLast(command)
        }

        fun runNext() {
            queue.removeFirst().run()
        }
    }
}
