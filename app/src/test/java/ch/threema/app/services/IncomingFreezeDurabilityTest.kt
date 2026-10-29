package ch.threema.app.services

import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.group.GroupMessageModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (fourth fork review, F4-05): durability tests for the incoming sender-policy freeze.
 *
 * The defect: the incoming row was INSERTED first, carrying only the provisional local conversation timer that
 * `createLocalModel` stamps, and the timer the SENDER advertised was applied by a second write afterwards. Two writes, no
 * transaction. A process death between them left a stored message with the wrong policy, and the server's redelivery then hit
 * the duplicate-message guard, which returns success without ever reaching the freeze. The wrong value became permanent, and
 * nothing errored, logged or failed, because as far as the app was concerned the message had been delivered.
 *
 * The fix has two halves and both are tested here: the sender's timer is stamped BEFORE the first write, and the duplicate
 * path repairs a row that lacks the authoritative freeze.
 *
 * The "database" is an in-test map keyed by api message id, and the two write orderings are modelled exactly as the
 * production paths order them. What decides and mutates is production code either way -
 * [DisappearingFreezeDecision.resolveIncomingTimer] and [DisappearingMessageService.freezeIncomingTimer], which is precisely
 * what `MessageServiceImpl.freezeIncomingBeforeFirstWrite` composes - so what is asserted is the shipped rule.
 *
 * [legacyInsertThenFreezeMakesTheWrongTimerPermanent] is the control. It runs the OLD ordering, including the old duplicate
 * guard that repaired nothing, and shows the sender's policy being defeated.
 *
 * What this cannot cover, recorded rather than glossed: the real insert is `MessageModelFactory.create` on SQLCipher, the
 * real duplicate guard reads that table, and the real process death is a kill. Those stay on the device-matrix debt list.
 * What is covered is the ordering, which is the whole of the defect.
 */
class IncomingFreezeDurabilityTest {
    private val senderTimer = 30
    private val recipientLocalTimer = 3600

    /** Stands in for the message table: what survives a process death. */
    private val stored = mutableMapOf<String, AbstractMessageModel>()

    // -----------------------------------------------------------------------------------------------------------------------------
    // The two orderings, modelled as production orders them.
    // -----------------------------------------------------------------------------------------------------------------------------

    /** The fix: resolve and freeze on the model, THEN insert. One write. */
    private fun receiveFixed(model: AbstractMessageModel, advertisedBySender: Int?, dieAfterInsert: Boolean = false) {
        if (existing(model)?.also { repairOnRedelivery(it, advertisedBySender) } != null) {
            return
        }
        freezeOnModel(model, advertisedBySender)
        insert(model)
        if (dieAfterInsert) return
    }

    /** The defect: insert, THEN freeze as a second write. */
    private fun receiveLegacy(model: AbstractMessageModel, advertisedBySender: Int?, dieAfterInsert: Boolean = false) {
        if (existing(model) != null) {
            // The old duplicate guard: success, no repair.
            return
        }
        insert(model)
        if (dieAfterInsert) {
            return
        }
        freezeOnModel(model, advertisedBySender)
        insert(model)
    }

    /** Exactly what `MessageServiceImpl.freezeIncomingBeforeFirstWrite` does: resolve, then freeze on the model. */
    private fun freezeOnModel(model: AbstractMessageModel, advertisedBySender: Int?) {
        DisappearingMessageService.freezeIncomingTimer(
            model,
            DisappearingFreezeDecision.resolveIncomingTimer(advertisedBySender, recipientLocalTimer),
        )
    }

    /** Exactly what the duplicate branches now do: re-read the stored row and apply the same freeze to it. */
    private fun repairOnRedelivery(storedModel: AbstractMessageModel, advertisedBySender: Int?) {
        if (DisappearingMessageService.freezeIncomingTimer(
                storedModel,
                DisappearingFreezeDecision.resolveIncomingTimer(advertisedBySender, recipientLocalTimer),
            )
        ) {
            insert(storedModel)
        }
    }

    private fun insert(model: AbstractMessageModel) {
        stored[model.apiMessageId!!] = model
    }

    private fun existing(model: AbstractMessageModel) = stored[model.apiMessageId!!]

    private fun storedTimer(apiMessageId: String) = stored[apiMessageId]?.disappearingTimerSeconds

    // -----------------------------------------------------------------------------------------------------------------------------
    // Model builders. The provisional local timer is what createLocalModel stamps before anything network-supplied is applied.
    // -----------------------------------------------------------------------------------------------------------------------------

    private fun contactModel(id: String, type: MessageType = MessageType.TEXT) = MessageModel().apply {
        uid = "uid-$id"
        apiMessageId = id
        identity = "AAAAAAAA"
        isOutbox = false
        this.type = type
        disappearingTimerSeconds = recipientLocalTimer
    }

    private fun groupModel(id: String, type: MessageType = MessageType.TEXT) = GroupMessageModel().apply {
        uid = "uid-$id"
        apiMessageId = id
        identity = "AAAAAAAA"
        isOutbox = false
        this.type = type
        disappearingTimerSeconds = recipientLocalTimer
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Half one: the sender's policy is durable with the initial acceptance.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a contact message killed right after its insert still carries the sender's timer`() {
        receiveFixed(contactModel("m1"), advertisedBySender = senderTimer, dieAfterInsert = true)

        assertEquals(senderTimer, storedTimer("m1"), "the stored row must already hold the sender's policy")
    }

    @Test
    fun `a group message killed right after its insert still carries the sending member's timer`() {
        receiveFixed(groupModel("m2"), advertisedBySender = senderTimer, dieAfterInsert = true)

        assertEquals(senderTimer, storedTimer("m2"))
    }

    @Test
    fun `a FILE message killed right after its insert still carries the sender's timer`() {
        receiveFixed(contactModel("m3", MessageType.FILE), advertisedBySender = senderTimer, dieAfterInsert = true)

        assertEquals(senderTimer, storedTimer("m3"))
    }

    @Test
    fun `a sender that explicitly says OFF survives the insert as OFF, not as the recipient's timer`() {
        receiveFixed(contactModel("m4"), advertisedBySender = 0, dieAfterInsert = true)

        assertEquals(0, storedTimer("m4"), "an explicit sender OFF must not decay into the recipient's own setting")
    }

    @Test
    fun `a sender that advertises nothing leaves the recipient's own timer in place`() {
        receiveFixed(contactModel("m5"), advertisedBySender = null, dieAfterInsert = true)

        assertEquals(recipientLocalTimer, storedTimer("m5"), "a pre-timer client's message still follows the local setting")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Half two: a duplicate repairs a row that lacks the authoritative freeze.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `redelivery repairs a contact row left with the wrong timer by an earlier crash`() {
        // A row written by the old ordering, or by a build that crashed between the two writes.
        val crashed = contactModel("m6")
        insert(crashed)
        assertEquals(recipientLocalTimer, storedTimer("m6"), "precondition: the stored row has the wrong policy")

        receiveFixed(contactModel("m6"), advertisedBySender = senderTimer)

        assertEquals(senderTimer, storedTimer("m6"), "the redelivery is the only second chance and must take it")
    }

    @Test
    fun `redelivery repairs a group row left with the wrong timer by an earlier crash`() {
        insert(groupModel("m7"))

        receiveFixed(groupModel("m7"), advertisedBySender = senderTimer)

        assertEquals(senderTimer, storedTimer("m7"))
    }

    @Test
    fun `redelivery repairs a FILE row left with the wrong timer by an earlier crash`() {
        insert(contactModel("m8", MessageType.FILE))

        receiveFixed(contactModel("m8", MessageType.FILE), advertisedBySender = senderTimer)

        assertEquals(senderTimer, storedTimer("m8"))
    }

    @Test
    fun `an ordinary duplicate of an already-correct row changes nothing`() {
        receiveFixed(contactModel("m9"), advertisedBySender = senderTimer)
        val afterFirstDelivery = stored["m9"]
        assertNotNull(afterFirstDelivery)

        receiveFixed(contactModel("m9"), advertisedBySender = senderTimer)

        assertTrue(stored["m9"] === afterFirstDelivery, "a duplicate must not replace the stored row")
        assertEquals(senderTimer, storedTimer("m9"))
    }

    @Test
    fun `repair re-derives a countdown that had already started against the wrong timer`() {
        val startedAt = 1_700_000_000_000L
        val crashed = contactModel("m10").apply {
            // markAsRead won the race on the previous run and started counting against the recipient's timer.
            expireStartedAt = startedAt
            expiresAt = startedAt + recipientLocalTimer * 1000L
        }
        insert(crashed)

        receiveFixed(contactModel("m10"), advertisedBySender = senderTimer)

        assertEquals(senderTimer, storedTimer("m10"))
        assertEquals(
            startedAt + senderTimer * 1000L,
            stored["m10"]?.expiresAt,
            "the deadline has to be re-derived from the sender's timer, not left inconsistent with it",
        )
    }

    @Test
    fun `repair cancels a countdown when the sender said OFF`() {
        val startedAt = 1_700_000_000_000L
        insert(
            contactModel("m11").apply {
                expireStartedAt = startedAt
                expiresAt = startedAt + recipientLocalTimer * 1000L
            },
        )

        receiveFixed(contactModel("m11"), advertisedBySender = 0)

        assertEquals(0, storedTimer("m11"))
        assertNull(stored["m11"]?.expireStartedAt, "a message the sender did not time must stop counting down")
        assertNull(stored["m11"]?.expiresAt)
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Legacy control: the old ordering, written out inline.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun legacyInsertThenFreezeMakesTheWrongTimerPermanent() {
        // First delivery: the row lands, then the process dies before the second write.
        receiveLegacy(contactModel("x1"), advertisedBySender = senderTimer, dieAfterInsert = true)
        assertEquals(recipientLocalTimer, storedTimer("x1"))

        // The server redelivers. The old duplicate guard returns success and repairs nothing.
        receiveLegacy(contactModel("x1"), advertisedBySender = senderTimer)

        assertEquals(
            recipientLocalTimer,
            storedTimer("x1"),
            "this is the defect: the message is kept for an hour although the sender asked for thirty seconds, " +
                "and no further delivery will ever correct it",
        )
    }
}
