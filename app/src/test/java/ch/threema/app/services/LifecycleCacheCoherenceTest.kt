package ch.threema.app.services

import ch.threema.app.services.MessageRowHarness.Companion.BASE_TIME
import ch.threema.app.services.MessageRowHarness.Companion.CONTACT_TABLE
import ch.threema.app.services.MessageRowHarness.Companion.GROUP_TABLE
import ch.threema.storage.MessageCacheCoherence
import ch.threema.storage.factories.MessageFactoryTestAccess
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.data.DisplayTag.DISPLAY_TAG_PINNED
import ch.threema.storage.models.data.DisplayTag.DISPLAY_TAG_SEND_FAILED_TERMINAL
import ch.threema.storage.models.data.DisplayTag.DISPLAY_TAG_STARRED
import ch.threema.storage.models.group.GroupMessageModel
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (sixth fork review, F6-01): a conditional lifecycle write must STAY written.
 *
 * The fifth review made the lifecycle transitions column-scoped and non-inserting, which stopped them from destroying
 * anything themselves. It did not stop anything else from destroying THEM. One row is represented by several live
 * objects at once - the service caches an incoming message from the moment it is created, the unread query hands out its
 * own instance, a timeline page hands out another - and four ordinary operations still persisted themselves by saving
 * the WHOLE row from whichever of those objects they happened to hold: an incoming edit, a group delivery receipt, and
 * the star and pin toggles. Each of them therefore wrote a pre-transition snapshot back over the top.
 *
 * Two halves are asserted here, in the order the fix applies them:
 *
 *  1. every one of those four operations now writes only the column or two it owns, conditionally, against the CURRENT
 *     row - so it cannot revert a field it never looked at, and cannot recreate a row expiry has claimed;
 *  2. a successful conditional write refreshes every cached instance of that row before releasing the lock the full-row
 *     save takes - so an instance that outlives the write carries the winner's values rather than its own.
 *
 * Each scenario is run in both orders, because "the transition wins the race" and "the ordinary operation wins the race"
 * have to end in the same place: both changes present, neither reverted.
 *
 * The controls named `legacy*` perform the old full-row upsert inline and show the field being lost or the row coming
 * back. They call no production code beyond the model itself.
 *
 * What is NOT covered here is the wiring: `MessageServiceImpl` cannot be constructed in a JVM unit test (its
 * constructor reaches `android.util.SparseIntArray` and the send-machine backoff, and the database is SQLCipher), so
 * that the four call sites build these updates is asserted narrowly against the source, and each such assertion was
 * proven red by removing the line it names.
 */
class LifecycleCacheCoherenceTest {
    private lateinit var harness: MessageRowHarness
    private lateinit var cache: MutableList<AbstractMessageModel>

    private val messageId = 1
    private val readAt = Date(BASE_TIME + 5_000L)

    @BeforeTest
    fun setUp() {
        harness = MessageRowHarness(CONTACT_TABLE, GROUP_TABLE)
        cache = mutableListOf()
    }

    @AfterTest
    fun tearDown() {
        harness.close()
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The reproducer: first read, then an incoming edit.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `an incoming edit cannot cancel the countdown the first read started`() {
        harness.insertContactRow(messageId, body = "original")
        val cache = cacheOf(harness.requireModel(CONTACT_TABLE, messageId))

        firstRead()
        reconcile()

        // The edit is resolved through the service cache, so it acts on the instance that was cached before the read.
        val edited = applyEdit(cache.single(), "edited")

        assertTrue(edited, "the edit must be stored")
        assertEquals("edited", harness.stringOf(CONTACT_TABLE, messageId, "body"))
        assertEquals(1L, harness.longOf(CONTACT_TABLE, messageId, "isRead"), "the read must survive the edit")
        assertEquals(readAt.time, harness.longOf(CONTACT_TABLE, messageId, "readAtUtc"))
        assertEquals(readAt.time, harness.longOf(CONTACT_TABLE, messageId, "expireStartedAtUtc"), "and so must its countdown")
        assertEquals(readAt.time + 30_000L, harness.longOf(CONTACT_TABLE, messageId, "expiresAtUtc"))
    }

    @Test
    fun `an incoming edit that lands first is not undone by the read that follows it`() {
        harness.insertContactRow(messageId, body = "original")
        val cache = cacheOf(harness.requireModel(CONTACT_TABLE, messageId))

        assertTrue(applyEdit(cache.single(), "edited"))
        reconcile()
        firstRead()

        assertEquals("edited", harness.stringOf(CONTACT_TABLE, messageId, "body"), "the reverse order must lose nothing either")
        assertEquals(1L, harness.longOf(CONTACT_TABLE, messageId, "isRead"))
        assertEquals(readAt.time, harness.longOf(CONTACT_TABLE, messageId, "expireStartedAtUtc"))
    }

    @Test
    fun legacyFullRowSaveOfACachedSnapshotCancelsTheCountdown() {
        harness.insertContactRow(messageId, body = "original")
        // The instance the cache handed the edit, captured BEFORE the read - which is the whole of the defect.
        val cachedSnapshot = harness.requireModel(CONTACT_TABLE, messageId)

        firstRead()

        cachedSnapshot.body = "edited"
        cachedSnapshot.editedAt = Date(BASE_TIME + 9_000L)
        harness.legacyFullRowUpsert(CONTACT_TABLE, cachedSnapshot)

        assertEquals(0L, harness.longOf(CONTACT_TABLE, messageId, "isRead"), "this is the defect: the message is unread again")
        assertNull(harness.longOf(CONTACT_TABLE, messageId, "expireStartedAtUtc"), "and its countdown has been cancelled")
        assertNull(harness.longOf(CONTACT_TABLE, messageId, "expiresAtUtc"))
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The reproducer: an outgoing group transition, then a group receipt.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a group receipt cannot revert the state and clock a resolved-reject transition established`() {
        harness.insertGroupRow(messageId, outbox = true, state = MessageState.FS_KEY_MISMATCH)
        val cachedSnapshot = harness.requireModel(GROUP_TABLE, messageId) as GroupMessageModel

        // The resolved-reject refresh loads its own model and applies the clock-aware SENT transition to it.
        val sentAt = Date(BASE_TIME + 1_000L)
        val fresh = harness.requireModel(GROUP_TABLE, messageId)
        val transition = OutgoingTransitionPlanner.plan(fresh, MessageState.SENT, sentAt, null, false)
        assertNotNull(transition)
        assertTrue(harness.apply(GROUP_TABLE, messageId, transition))

        // A receipt arrives afterwards and resolves the STALE cached instance.
        assertTrue(applyGroupReceipt(cachedSnapshot, "MEMBER01", MessageState.DELIVERED))

        assertEquals(MessageState.SENT.toString(), harness.stringOf(GROUP_TABLE, messageId, "state"))
        assertEquals(sentAt.time, harness.longOf(GROUP_TABLE, messageId, "expireStartedAtUtc"))
        assertEquals(sentAt.time + 30_000L, harness.longOf(GROUP_TABLE, messageId, "expiresAtUtc"))
        assertTrue(
            harness.stringOf(GROUP_TABLE, messageId, "groupMessageStates")!!.contains("MEMBER01"),
            "and the receipt itself must be recorded",
        )
    }

    @Test
    fun `a receipt that lands first is not undone by the transition that follows it`() {
        harness.insertGroupRow(messageId, outbox = true, state = MessageState.FS_KEY_MISMATCH)

        assertTrue(applyGroupReceipt(harness.requireModel(GROUP_TABLE, messageId), "MEMBER01", MessageState.READ))

        val sentAt = Date(BASE_TIME + 1_000L)
        val fresh = harness.requireModel(GROUP_TABLE, messageId)
        val transition = OutgoingTransitionPlanner.plan(fresh, MessageState.SENT, sentAt, null, false)
        assertNotNull(transition)
        assertTrue(harness.apply(GROUP_TABLE, messageId, transition))

        assertEquals(MessageState.SENT.toString(), harness.stringOf(GROUP_TABLE, messageId, "state"))
        assertTrue(harness.stringOf(GROUP_TABLE, messageId, "groupMessageStates")!!.contains("READ"))
    }

    @Test
    fun `a late DELIVERED never overwrites a READ from the same member`() {
        harness.insertGroupRow(messageId, outbox = true, state = MessageState.SENT)

        assertTrue(applyGroupReceipt(harness.requireModel(GROUP_TABLE, messageId), "MEMBER01", MessageState.READ))
        assertFalse(
            applyGroupReceipt(harness.requireModel(GROUP_TABLE, messageId), "MEMBER01", MessageState.DELIVERED),
            "a reordered delivery receipt must change nothing",
        )

        assertTrue(harness.stringOf(GROUP_TABLE, messageId, "groupMessageStates")!!.contains("READ"))
    }

    @Test
    fun legacyFullRowSaveOfAGroupReceiptRevertsTheTerminalState() {
        harness.insertGroupRow(messageId, outbox = true, state = MessageState.FS_KEY_MISMATCH)
        val cachedSnapshot = harness.requireModel(GROUP_TABLE, messageId) as GroupMessageModel

        val fresh = harness.requireModel(GROUP_TABLE, messageId)
        val transition = OutgoingTransitionPlanner.plan(fresh, MessageState.SENT, Date(BASE_TIME + 1_000L), null, false)
        assertTrue(harness.apply(GROUP_TABLE, messageId, transition!!))

        cachedSnapshot.groupMessageStates = mutableMapOf<String, Any>("MEMBER01" to MessageState.DELIVERED.toString())
        harness.legacyFullRowUpsert(GROUP_TABLE, cachedSnapshot)

        assertEquals(
            MessageState.FS_KEY_MISMATCH.toString(),
            harness.stringOf(GROUP_TABLE, messageId, "state"),
            "this is the defect: the message is marked rejected again",
        )
        assertNull(harness.longOf(GROUP_TABLE, messageId, "expireStartedAtUtc"), "and its countdown has been cancelled")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The reproducer: star and pin, against a transition and against expiry.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a delayed star toggle cannot revert a first read`() {
        harness.insertContactRow(messageId)
        // The timeline instance the user tapped on, loaded when the page was.
        val timelineInstance = harness.requireModel(CONTACT_TABLE, messageId)

        firstRead()
        assertTrue(toggleDisplayTag(timelineInstance, DISPLAY_TAG_STARRED))

        assertEquals(DISPLAY_TAG_STARRED.toLong(), harness.longOf(CONTACT_TABLE, messageId, "displayTags"))
        assertEquals(1L, harness.longOf(CONTACT_TABLE, messageId, "isRead"), "only the tag column may change")
        assertEquals(readAt.time, harness.longOf(CONTACT_TABLE, messageId, "expireStartedAtUtc"))
    }

    @Test
    fun `a star toggle that lands first is not undone by the read that follows it`() {
        harness.insertContactRow(messageId)

        assertTrue(toggleDisplayTag(harness.requireModel(CONTACT_TABLE, messageId), DISPLAY_TAG_STARRED))
        firstRead()

        assertEquals(DISPLAY_TAG_STARRED.toLong(), harness.longOf(CONTACT_TABLE, messageId, "displayTags"))
        assertEquals(1L, harness.longOf(CONTACT_TABLE, messageId, "isRead"))
    }

    @Test
    fun `a pin applied against a stale bitmask is refused and recomputed rather than clearing the star`() {
        harness.insertContactRow(messageId)
        val timelineInstance = harness.requireModel(CONTACT_TABLE, messageId)

        // Something else sets a bit first: the fork's terminal-failure marker, or a star from the starred-messages screen.
        assertTrue(toggleDisplayTag(harness.requireModel(CONTACT_TABLE, messageId), DISPLAY_TAG_STARRED))

        // The stale instance still reads 0, so its compare-and-set must fail...
        assertFalse(
            harness.apply(
                CONTACT_TABLE,
                messageId,
                MessageLifecycleUpdates.displayTags(DISPLAY_TAG_PINNED, timelineInstance.displayTags),
            ),
            "a toggle computed from a bitmask that has moved must not be applied",
        )
        // ...and the retry, which recomputes from the row, must compose the two bits.
        assertTrue(toggleDisplayTag(harness.requireModel(CONTACT_TABLE, messageId), DISPLAY_TAG_PINNED))

        assertEquals(
            (DISPLAY_TAG_STARRED or DISPLAY_TAG_PINNED).toLong(),
            harness.longOf(CONTACT_TABLE, messageId, "displayTags"),
        )
    }

    @Test
    fun `unstarring from the starred list clears the star and nothing else`() {
        harness.insertContactRow(messageId, displayTags = DISPLAY_TAG_STARRED or DISPLAY_TAG_SEND_FAILED_TERMINAL)

        assertTrue(clearDisplayTag(harness.requireModel(CONTACT_TABLE, messageId), DISPLAY_TAG_STARRED))

        assertEquals(
            DISPLAY_TAG_SEND_FAILED_TERMINAL.toLong(),
            harness.longOf(CONTACT_TABLE, messageId, "displayTags"),
            "assigning DISPLAY_TAG_NONE also cleared the terminal-failure marker, re-enrolling a dead message in the " +
                "auto-resend scan",
        )
    }

    @Test
    fun `a star toggle that loses a race with expiry cannot bring the message back`() {
        val startedAt = BASE_TIME
        val expiresAt = startedAt + 30_000L
        harness.insertContactRow(messageId, expireStartedAt = startedAt, expiresAt = expiresAt)
        val timelineInstance = harness.requireModel(CONTACT_TABLE, messageId)

        assertTrue(harness.claimIfStillDue(CONTACT_TABLE, messageId, startedAt, expiresAt, expiresAt + 1))
        assertFalse(toggleDisplayTag(timelineInstance, DISPLAY_TAG_STARRED), "there is no row left to tag")

        assertEquals(0, harness.rowCount(CONTACT_TABLE, messageId), "and above all the message must not come back")
    }

    @Test
    fun legacyFullRowSaveOfAStarToggleResurrectsAnExpiredMessage() {
        val startedAt = BASE_TIME
        val expiresAt = startedAt + 30_000L
        harness.insertContactRow(messageId, body = "secret", expireStartedAt = startedAt, expiresAt = expiresAt)
        val timelineInstance = harness.requireModel(CONTACT_TABLE, messageId)

        assertTrue(harness.claimIfStillDue(CONTACT_TABLE, messageId, startedAt, expiresAt, expiresAt + 1))

        timelineInstance.displayTags = DISPLAY_TAG_STARRED
        harness.legacyFullRowUpsert(CONTACT_TABLE, timelineInstance)

        assertEquals(1, harness.rowCount(CONTACT_TABLE, messageId), "this is the defect: the expired message is back")
        assertEquals("secret", harness.stringOf(CONTACT_TABLE, messageId, "body"), "with its body")
        assertTrue(
            MessageFactoryTestAccess.refusesReinsertion(messageId),
            "the shipped upsert now refuses that insert outright, whatever the caller does",
        )
    }

    @Test
    fun `a genuinely new model is still inserted`() {
        assertFalse(
            MessageFactoryTestAccess.refusesReinsertion(0),
            "a model with no id has never been persisted; refusing it would break every insert in the app",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The second half: cached instances are refreshed by the write, or evicted with the row.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `reconciliation refreshes every cached instance from the row that won`() {
        harness.insertContactRow(messageId)
        val cache = cacheOf(
            harness.requireModel(CONTACT_TABLE, messageId),
            harness.requireModel(CONTACT_TABLE, messageId),
        )

        firstRead()
        val refreshed = reconcile()

        assertEquals(2, refreshed, "every instance of the row, not just the one the caller happened to pass")
        cache.forEach { cached ->
            assertTrue(cached.isRead, "a refreshed instance must not go on claiming to be unread")
            assertEquals(readAt.time, cached.readAt?.time)
            assertEquals(readAt.time, cached.expireStartedAt)
            assertEquals(readAt.time + 30_000L, cached.expiresAt)
            assertEquals(30, cached.disappearingTimerSeconds)
        }
    }

    @Test
    fun `reconciliation evicts instances of a row that has gone`() {
        harness.insertContactRow(messageId)
        val cache = cacheOf(harness.requireModel(CONTACT_TABLE, messageId))
        harness.hardDelete(CONTACT_TABLE, messageId)

        assertEquals(1, MessageCacheCoherence.reconcile(cache, messageId, null))

        assertTrue(cache.isEmpty(), "a cached instance of a deleted row is how a later lookup found a deleted message")
    }

    @Test
    fun `reconciliation leaves other rows alone`() {
        harness.insertContactRow(messageId)
        harness.insertContactRow(2, body = "other")
        val other = harness.requireModel(CONTACT_TABLE, 2)
        val cache = cacheOf(harness.requireModel(CONTACT_TABLE, messageId), other)

        firstRead()
        reconcile()

        assertFalse(other.isRead, "a different message must not be marked read by another one's transition")
        assertEquals(2, cache.size)
    }

    @Test
    fun `an empty cache asks the database for nothing`() {
        harness.insertContactRow(messageId)

        assertFalse(
            MessageCacheCoherence.holds(mutableListOf<AbstractMessageModel>(), messageId),
            "the common case is that nothing is cached, and it must not cost a re-read",
        )
    }

    @Test
    fun `a group instance adopts the group columns too`() {
        harness.insertGroupRow(messageId, outbox = true, state = MessageState.SENDING)
        val cached = harness.requireModel(GROUP_TABLE, messageId) as GroupMessageModel
        val cache = cacheOf(cached)

        assertTrue(applyGroupReceipt(harness.requireModel(GROUP_TABLE, messageId), "MEMBER01", MessageState.DELIVERED))
        MessageCacheCoherence.reconcile(cache, messageId, harness.requireModel(GROUP_TABLE, messageId))

        assertEquals(
            MessageState.DELIVERED.toString(),
            cached.groupMessageStates?.get("MEMBER01"),
            "the per-member map is a group column, and the historic copyFrom did not carry the fork's columns at all",
        )
        assertEquals(7, cached.groupId)
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The four call sites use these updates.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the converted writers no longer full-row-save`() {
        val service = java.io.File("src/main/java/ch/threema/app/services/MessageServiceImpl.java").readText()
        val compose = java.io.File("src/main/java/ch/threema/app/fragments/composemessage/ComposeMessageFragment.java").readText()
        val starred = java.io.File("src/main/java/ch/threema/app/activities/starred/StarredMessagesViewModel.kt").readText()
        val reflected = java.io.File(
            "src/main/java/ch/threema/app/processors/reflectedmessageupdate/ReflectedIncomingMessageUpdateTask.kt",
        ).readText()

        // Seventh review, F7-03: the conditional write is now inside a transaction that also carries the history entry.
        assertTrue(
            service.contains("commitEditDurably(message, text, editedAt)"),
            "the edit must go through the conditional write",
        )
        assertFalse(bodyOf(service, "public boolean saveEditedMessageText(").contains("save(message);"))
        assertTrue(
            bodyOf(service, "public void addGroupMessageState(").contains("MessageLifecycleUpdates.groupReceipt("),
            "the group receipt must write only its own column",
        )
        assertFalse(bodyOf(service, "public void addGroupMessageState(").contains("save(messageModel);"))
        assertTrue(compose.contains("messageService.toggleDisplayTag(messageModel, DISPLAY_TAG_STARRED)"))
        assertTrue(compose.contains("messageService.toggleDisplayTag(messageModel, DISPLAY_TAG_PINNED)"))
        assertFalse(
            bodyOf(compose, "private void toggleStar(").contains("saveLocalModel"),
            "a full-row save of a timeline instance is the defect itself",
        )
        assertFalse(bodyOf(compose, "private void togglePin(").contains("saveLocalModel"))
        assertTrue(starred.contains("messageService.clearDisplayTag(abstractMessageModel, DISPLAY_TAG_STARRED)"))
        assertTrue(reflected.contains("messageService.markAsReadFromSync(abstractMessageModel, Date(readAt))"))
    }

    @Test
    fun `the conditional write and its cache reconciliation are one operation under the save lock`() {
        val service = java.io.File("src/main/java/ch/threema/app/services/MessageServiceImpl.java").readText()
        val applyBody = bodyOf(service, "private boolean applyRowUpdate(")

        assertTrue(
            applyBody.contains("synchronized (cache)"),
            "a full-row save that runs between the write and the refresh sees exactly the stale instance the refresh " +
                "exists to remove",
        )
        assertTrue(applyBody.contains("reconcileCache(model, cache)"))
        // Seventh review, F7-01: the full-row save picks its monitor through the same one place, cacheFor, so the two
        // provably take the SAME lock rather than two that happen to coincide today.
        val saveBody = bodyOf(service, "public boolean save(")
        assertTrue(
            saveBody.contains("cacheFor(messageModel)") && saveBody.contains("synchronized (cache)"),
            "and it has to be the SAME monitor the full-row save takes, or the two are not serialised at all",
        )
        assertTrue(
            applyBody.contains("cacheFor(model)"),
            "both sides must choose the monitor the same way",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------------------------------------------

    /** The service's per-type message cache: a real collection holding real models, as `CacheService` hands out. */
    private fun cacheOf(vararg models: AbstractMessageModel): MutableList<AbstractMessageModel> {
        cache.addAll(models)
        return cache
    }

    /** What a successful conditional write does before it releases the lock. */
    private fun reconcile(): Int =
        MessageCacheCoherence.reconcile(cache, messageId, harness.readModel(CONTACT_TABLE, messageId))

    /** The write `markReadDurably` performs, decided by the production rule from the row itself. */
    private fun firstRead() {
        val current = harness.requireModel(CONTACT_TABLE, messageId)
        val countdown = FirstReadDecision.countdownAtFirstRead(
            current.isOutbox,
            false,
            current.expireStartedAt,
            current.disappearingTimerSeconds,
            null,
            readAt.time,
        )
        val applied = harness.apply(
            CONTACT_TABLE,
            messageId,
            MessageLifecycleUpdates.firstRead(
                readAt,
                current.disappearingTimerSeconds,
                current.expireStartedAt,
                current.expiresAt,
                countdown,
            ),
        )
        assertTrue(applied, "the first read must be recorded")
    }

    private fun applyEdit(from: AbstractMessageModel, text: String): Boolean {
        // `writeEditDurably` recomputes the body from the row, not from the instance it was handed.
        val current = harness.readModel(CONTACT_TABLE, from.id) ?: return false
        val priorBody = current.body
        current.body = text
        return harness.apply(
            CONTACT_TABLE,
            from.id,
            MessageLifecycleUpdates.edit(current.body, current.caption, Date(BASE_TIME + 9_000L), priorBody),
        )
    }

    private fun applyGroupReceipt(from: AbstractMessageModel, identity: String, state: MessageState): Boolean {
        val current = harness.readModel(GROUP_TABLE, from.id) as? GroupMessageModel ?: return false
        val prior = MessageLifecycleUpdates.serialiseGroupMessageStates(current.groupMessageStates)
        val merged = MessageLifecycleUpdates.mergeGroupReceipt(current.groupMessageStates, identity, state) ?: return false
        return harness.apply(
            GROUP_TABLE,
            from.id,
            MessageLifecycleUpdates.groupReceipt(MessageLifecycleUpdates.serialiseGroupMessageStates(merged), prior),
        )
    }

    private fun toggleDisplayTag(from: AbstractMessageModel, tag: Int): Boolean = writeDisplayTag(from, tag, toggle = true)

    private fun clearDisplayTag(from: AbstractMessageModel, tag: Int): Boolean = writeDisplayTag(from, tag, toggle = false)

    private fun writeDisplayTag(from: AbstractMessageModel, tag: Int, toggle: Boolean): Boolean {
        val table = if (from is GroupMessageModel) GROUP_TABLE else CONTACT_TABLE
        val current = harness.readModel(table, from.id) ?: return false
        val priorTags = current.displayTags
        val newTags = if (toggle) priorTags xor tag else priorTags and tag.inv()
        if (newTags == priorTags) {
            return false
        }
        return harness.apply(table, from.id, MessageLifecycleUpdates.displayTags(newTags, priorTags))
    }

    /**
     * The text from [signature] to the end of its body, matched by brace depth. Crude, and deliberately so: it exists
     * only to keep an assertion about one method from being satisfied by an identical line in another.
     */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "this test's anchor has drifted: $signature")
        var depth = 0
        var seenOpen = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> {
                    depth++
                    seenOpen = true
                }

                '}' -> {
                    depth--
                    if (seenOpen && depth == 0) {
                        return source.substring(start, index + 1)
                    }
                }
            }
        }
        error("unbalanced braces after $signature")
    }
}
