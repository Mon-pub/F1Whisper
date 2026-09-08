package ch.threema.app.services

import ch.threema.app.services.MessageRowHarness.Companion.GROUP_TABLE
import ch.threema.app.utils.JsonUtil
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.group.GroupMessageModel
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F1Whisper (group receipt regression, RB-01): recording a receipt must survive stored text this code did not produce.
 *
 * **The defect.** The sixth review's `addGroupMessageState` conversion built its compare-and-set condition by
 * re-serialising the map the reload had parsed. On Android, `JSONObject` preserves the iteration order of the map it is
 * built from, and `HashMap` iteration order depends on table capacity: the stored column text was emitted from the
 * merge's copy-constructed map (capacity sized to its entries) while the reload parses into `JsonUtil`'s default
 * `HashMap` (capacity 16). Once the two orders diverge - reliably as the member map grows - the reconstructed
 * "expected" string never matches the stored text again, every retry rebuilds the same wrong string, and after three
 * attempts the receipt is dropped ("Gave up recording"): the row is permanently unable to record any further receipt,
 * so "Read by" / "Delivered to" in the message details froze at whatever had been recorded by luck. The same
 * construction sat in `clearMessageState`'s group-states condition, and the factory's full-row save disagreed with the
 * conditional writers about an EMPTY map (`"{}"` versus SQL NULL) - a second unmatchable stored form.
 *
 * **The fix, asserted here.** The condition is the stored column TEXT read back verbatim
 * (`GroupMessageModelFactory.getGroupMessageStatesRaw`), and the merge input is parsed from those same bytes
 * (`MessageLifecycleUpdates.parseGroupMessageStates`) - so no serialisation ambiguity can ever refuse the write, and a
 * row already wedged by the old code (or holding `"{}"`, or garbage) heals itself on the next receipt.
 *
 * **Why the sixth review's tests never caught it.** Not JSON semantics: these tests run the SAME implementation the
 * device does, because the `common` module vendors AOSP's org.json (a `LinkedHashMap`-backed `JSONObject` that
 * preserves the source map's iteration order) and it shadows the reference org.json on the unit-test classpath. The
 * divergence reproduces natively - under the shipped-before flow the sixteen-member sequence below is refused at the
 * THIRD receipt already, the stored text holding the merge map's order and the reconstruction holding the parse map's.
 * The sixth review's scenarios simply never grew the map past ONE member, and one key has only one order. The seeded
 * scenarios below additionally pin stored forms no current code emits at all (a rotated key order, `"{}"`, garbage),
 * independent of hash-table physics, with a permanent control showing the shipped-before construction refusing them on
 * every attempt.
 *
 * The behavioural scenarios run through [MessageRowHarness.applyGroupReceipt], the shared replica of the shipped
 * per-attempt flow; that the production writers implement that flow is pinned against the source at the bottom, each
 * pin proven red by reverting the line it names.
 */
class GroupReceiptRoundTripTest {
    private lateinit var harness: MessageRowHarness
    private val messageId = 1

    @BeforeTest
    fun setUp() {
        harness = MessageRowHarness(GROUP_TABLE)
        harness.insertGroupRow(messageId)
    }

    @AfterTest
    fun tearDown() {
        harness.close()
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The reproduction: stored text whose key order this code did not produce.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a receipt records against stored text whose key order this code did not produce`() {
        val members = (1..5).map { "MEMBER%02d".format(it) }
        seedColumn(divergentlyOrderedStates(members))

        assertTrue(
            harness.applyGroupReceipt(messageId, "MEMBER06", MessageState.DELIVERED),
            "the receipt must be recorded on the FIRST attempt - there is no contention here to retry against",
        )

        val stored = storedMap()
        assertEquals(6, stored.size, "the new member joins the map")
        members.forEach { member ->
            assertEquals("DELIVERED", stored[member], "no previously recorded receipt may be lost by the heal")
        }
        assertEquals("DELIVERED", stored["MEMBER06"])
    }

    @Test
    fun `a read upgrade records against divergently ordered stored text`() {
        val members = (1..5).map { "MEMBER%02d".format(it) }
        seedColumn(divergentlyOrderedStates(members))

        assertTrue(
            harness.applyGroupReceipt(messageId, "MEMBER01", MessageState.READ),
            "upgrading DELIVERED to READ is the receipt the user watched fail to appear",
        )

        val stored = storedMap()
        assertEquals("READ", stored["MEMBER01"])
        (2..5).map { "MEMBER%02d".format(it) }.forEach { member -> assertEquals("DELIVERED", stored[member]) }
    }

    @Test
    fun `the reserialised condition the sixth review shipped refuses that stored text forever`() {
        // The permanent control: the shipped-before flow, inline. Reverting the fix makes the scenarios above behave
        // exactly like this - three refusals and a dropped receipt.
        val seed = divergentlyOrderedStates((1..5).map { "MEMBER%02d".format(it) })
        seedColumn(seed)

        repeat(3) { attempt ->
            val current = harness.readModel(GROUP_TABLE, messageId) as GroupMessageModel
            val prior = MessageLifecycleUpdates.serialiseGroupMessageStates(current.groupMessageStates)
            assertNotEquals(
                seed,
                prior,
                "the reconstruction diverges from the stored text - this is the defect (attempt ${attempt + 1})",
            )
            val merged = assertNotNull(
                MessageLifecycleUpdates.mergeGroupReceipt(current.groupMessageStates, "MEMBER06", MessageState.DELIVERED),
            )
            assertFalse(
                harness.apply(
                    GROUP_TABLE,
                    messageId,
                    MessageLifecycleUpdates.groupReceipt(MessageLifecycleUpdates.serialiseGroupMessageStates(merged), prior),
                ),
                "the compare-and-set refuses the wrong condition, and the retry rebuilds it (attempt ${attempt + 1})",
            )
        }
        assertEquals(seed, storedStates(), "after every attempt the receipt is still unrecorded: 'Gave up recording'")
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The sequence the user watched break, and the wedged forms that must heal.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `sixteen members' delivery and read receipts are every one recorded`() {
        // On the device the old code failed partway through exactly this sequence (five receipts recorded, eighteen
        // refused). It fails here too: the vendored AOSP org.json preserves map iteration order, and under the
        // pre-RB-01 flow this sequence is refused at the third receipt already - see the class doc.
        val members = (1..16).map { "MEMBER%02d".format(it) }
        members.forEach { member ->
            assertTrue(
                harness.applyGroupReceipt(messageId, member, MessageState.DELIVERED),
                "the DELIVERED receipt from $member must be recorded",
            )
        }
        members.forEach { member ->
            assertTrue(
                harness.applyGroupReceipt(messageId, member, MessageState.READ),
                "the READ upgrade from $member must be recorded",
            )
        }

        val stored = storedMap()
        assertEquals(16, stored.size)
        members.forEach { member -> assertEquals("READ", stored[member]) }
    }

    @Test
    fun `a late DELIVERED behind a READ still changes nothing`() {
        assertTrue(harness.applyGroupReceipt(messageId, "MEMBER01", MessageState.READ))
        assertFalse(
            harness.applyGroupReceipt(messageId, "MEMBER01", MessageState.DELIVERED),
            "a reordered delivery receipt must not downgrade a recorded read",
        )
        assertEquals("READ", storedMap()["MEMBER01"])
    }

    @Test
    fun `an unparseable column heals on the next receipt instead of wedging the row`() {
        seedColumn("not json at all")

        assertTrue(
            harness.applyGroupReceipt(messageId, "MEMBER01", MessageState.DELIVERED),
            "garbage folds to an empty merge input while the byte-exact condition still matches it",
        )
        assertEquals(mapOf<String, Any?>("MEMBER01" to "DELIVERED"), storedMap())
    }

    @Test
    fun `an empty-object column heals on the next receipt`() {
        // The form the factory's full-row save used to write for an empty map, unmatchable by the old condition
        // (which serialised an empty map to NULL).
        seedColumn("{}")

        assertTrue(harness.applyGroupReceipt(messageId, "MEMBER01", MessageState.DELIVERED))
        assertEquals(mapOf<String, Any?>("MEMBER01" to "DELIVERED"), storedMap())
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // The production writers implement the flow the replica executes.
    // -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `both service writers condition on the stored bytes, never a reconstruction`() {
        val service = File("src/main/java/ch/threema/app/services/MessageServiceImpl.java").readText()

        val receipt = bodyOf(service, "public void addGroupMessageState(")
        assertTrue(receipt.contains("getGroupMessageStatesRaw("), "the receipt's condition must be the raw stored text")
        assertTrue(receipt.contains("parseGroupMessageStates(priorStates)"), "and the merge must start from those same bytes")
        assertFalse(
            receipt.contains("serialiseGroupMessageStates(current.getGroupMessageStates())"),
            "re-serialising the reloaded map as the condition is the defect itself",
        )

        val clear = bodyOf(service, "public void clearMessageState(")
        assertTrue(
            clear.contains("getGroupMessageStatesRaw("),
            "the reaction clear conditions on the same column and must use the same raw text",
        )
        assertTrue(clear.contains("parseGroupMessageStates(priorStates)"))
        assertFalse(
            clear.contains("priorStates = MessageLifecycleUpdates.serialiseGroupMessageStates("),
            "the pre-RB-01 prior construction",
        )
    }

    @Test
    fun `the factory writes NULL for an empty map and exposes the raw column read`() {
        val factory = File("src/main/java/ch/threema/storage/factories/GroupMessageModelFactory.java").readText()

        val fullRow = bodyOf(factory, "private void addGroupMessageStates(")
        assertTrue(
            fullRow.contains(".isEmpty()"),
            "an empty map stored as \"{}\" where every conditional writer stores NULL is a second unmatchable form",
        )

        val rawRead = bodyOf(factory, "public String getGroupMessageStatesRaw(")
        assertTrue(
            rawRead.contains("COLUMN_GROUP_MESSAGE_STATES"),
            "the raw read must select the stored text of exactly this column",
        )
    }

    // -----------------------------------------------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------------------------------------------

    private fun storedStates(): String? = harness.stringOf(GROUP_TABLE, messageId, "groupMessageStates")

    private fun storedMap(): Map<String, Any?> = JsonUtil.convertObject(assertNotNull(storedStates()))

    private fun seedColumn(text: String) {
        harness.db.prepareStatement("UPDATE `$GROUP_TABLE` SET `groupMessageStates` = ? WHERE `id` = ?").use { statement ->
            statement.setString(1, text)
            statement.setInt(2, messageId)
            statement.executeUpdate()
        }
    }

    /** What this JVM's parse-and-reserialise round trip makes of [text] - the "expected" string the old code rebuilt. */
    private fun canonicalise(text: String): String =
        assertNotNull(MessageLifecycleUpdates.serialiseGroupMessageStates(MessageLifecycleUpdates.parseGroupMessageStates(text)))

    /**
     * A stored string carrying every one of [identities] as DELIVERED whose key order is NOT the order this JVM's
     * round trip produces for the same entries - i.e. the on-device poisoned state: text whose byte order the running
     * code would never emit for that map. Built by rotating the entries until one rotation differs from the round
     * trip; the rotations are pairwise distinct, so at most one of them can coincide with it.
     */
    private fun divergentlyOrderedStates(identities: List<String>): String {
        val entries = identities.map { "\"$it\":\"DELIVERED\"" }
        val divergent = identities.indices
            .map { shift -> (entries.drop(shift) + entries.take(shift)).joinToString(",", prefix = "{", postfix = "}") }
            .firstOrNull { candidate -> candidate != canonicalise(candidate) }
        return assertNotNull(divergent, "at least one rotation must differ from the canonical round-trip order")
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
