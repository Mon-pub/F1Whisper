package ch.threema.storage

import android.content.Context
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import ch.threema.storage.databaseupdate.DatabaseUpdateToVersion90131
import ch.threema.storage.databaseupdate.DatabaseUpdateToVersion90132
import ch.threema.storage.databaseupdate.DatabaseUpdateToVersion90133
import ch.threema.storage.databaseupdate.DatabaseUpdateToVersion90134
import ch.threema.storage.databaseupdate.DatabaseUpdateToVersion90135
import ch.threema.storage.databaseupdate.DatabaseUpdateToVersion90136
import ch.threema.storage.databaseupdate.fieldExists
import ch.threema.storage.databaseupdate.tableExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook

/**
 * Runs the real database upgrade (SQLCipher engine, the production [DatabaseOpenHelper]) over one database per released schema
 * family and checks the result.
 *
 * Released databases record exactly one of these schema versions: 119, 121, 123, 124 or 127. For each of them the test builds a
 * database of that shape, fills it with every kind of state that shape can hold, opens it with a plain [DatabaseOpenHelper] so the
 * whole update sequence runs, and then asserts that
 *  - the stored version is [DatabaseUpdater.VERSION],
 *  - every seeded state survived in its new representation,
 *  - the leftovers of the removed scheduled-messaging feature are gone,
 *  - the schema postcondition holds,
 *  - a second open changes nothing (version, rows and schema),
 *  - every table and index equals the one of a database created fresh by [DatabaseOpenHelper].
 *
 * The JVM tests cannot cover this: the upstream updates that move archive and pin state, split the mute policy and convert legacy
 * media rows need SQLCipher cursors.
 *
 * Family shapes (what each one holds beyond the base schema):
 *  - 119: the base schema, no fork additions.
 *  - 121: base + chat folders + the removed scheduled-messages table.
 *  - 123: 121 + per-message expiry columns and the per-conversation timers.
 *  - 124: 123 + the immutable `sortAtUtc` message sort key.
 *  - 127: the schema of the last release before the upgrade: all fork columns on all three message tables, no scheduled messages.
 *
 * Every family still has the old archive, pin and mute representation (`isArchived`, the `star` conversation tag,
 * `notificationTriggerPolicyOverride`), which is what the upgrade converts.
 */
class ForkDatabaseUpgradeFamiliesTest {
    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    private val openedHelpers = mutableListOf<DatabaseOpenHelper>()
    private val createdDatabases = mutableListOf<String>()
    private var databaseCounter = 0

    private val freshSchema: SchemaShape by lazy {
        val helper = newHelper(databaseName = nextDatabaseName("fresh"))
        val shape = readSchema(helper.writableDatabase)
        helper.close()
        shape
    }

    @AfterTest
    fun tearDown() {
        openedHelpers.forEach { helper -> runCatching { helper.close() } }
        createdDatabases.forEach { name -> appContext.deleteDatabase(name) }
    }

    @Test
    fun family119UpgradesToCurrentSchema() = verifyFamily(LegacyFamily.V119)

    @Test
    fun family121UpgradesToCurrentSchema() = verifyFamily(LegacyFamily.V121)

    @Test
    fun family123UpgradesToCurrentSchema() = verifyFamily(LegacyFamily.V123)

    @Test
    fun family124UpgradesToCurrentSchema() = verifyFamily(LegacyFamily.V124)

    @Test
    fun family127UpgradesToCurrentSchema() = verifyFamily(LegacyFamily.V127)

    /**
     * A 127 database that lost the sort key of one message table (the other two keep deliberately non-formula values) is repaired
     * by the same upgrade: the column comes back for that table only and is initialised by the formula, the intact tables are
     * left alone.
     */
    @Test
    fun family127RepairsMissingGroupMessageSortKey() {
        val family = LegacyFamily.V127
        verifyFamily(
            family = family,
            prepareLegacyDatabase = { db ->
                db.execSQL("ALTER TABLE `m_group_message` DROP COLUMN `sortAtUtc`")
                assertFalse(db.fieldExists("m_group_message", "sortAtUtc"), "precondition: the sort key must be gone")
            },
            sortKeyFormulaTables = setOf("m_group_message"),
        )
    }

    // ---------------------------------------------------------------------------------------------------------------------
    // Scenario
    // ---------------------------------------------------------------------------------------------------------------------

    private fun verifyFamily(
        family: LegacyFamily,
        prepareLegacyDatabase: (SQLiteDatabase) -> Unit = {},
        sortKeyFormulaTables: Set<String> = if (family.hasSortKey) emptySet() else MESSAGE_TABLES,
    ) {
        val databaseName = nextDatabaseName("family-${family.version}")
        val untouchedBefore = createLegacyDatabase(databaseName, family, prepareLegacyDatabase)

        val upgradeHelper = newHelper(databaseName)
        val upgraded = upgradeHelper.writableDatabase
        assertEquals(family.version, upgradeHelper.oldVersion, "the upgrade must start from the recorded family version")
        assertEquals(DatabaseUpdater.VERSION, upgraded.version)

        assertSeededStateSurvived(upgraded, family, untouchedBefore)
        assertSortKeys(upgraded, family, sortKeyFormulaTables)
        assertScheduledMessagesGone(upgraded)
        assertEquals(
            emptyList(),
            ForkSchemaPostcondition.missing(upgraded::fieldExists),
            "the schema postcondition must hold after the upgrade",
        )
        val shapeAfterUpgrade = readSchema(upgraded)
        assertSchemaConverged(shapeAfterUpgrade)
        val rowsAfterUpgrade = snapshotAllTables(upgraded)
        replayForkUpdates(upgraded)
        assertEquals(rowsAfterUpgrade, snapshotAllTables(upgraded), "replaying the fork updates must not change any row")
        assertEquals(shapeAfterUpgrade, readSchema(upgraded), "replaying the fork updates must not change the schema")
        upgradeHelper.close()

        val reopenHelper = newHelper(databaseName)
        val reopened = reopenHelper.writableDatabase
        assertEquals(DatabaseUpdater.VERSION, reopenHelper.oldVersion, "the second open must find the upgraded version")
        assertEquals(DatabaseUpdater.VERSION, reopened.version)
        assertEquals(rowsAfterUpgrade, snapshotAllTables(reopened), "a second open must not change any row")
        assertEquals(shapeAfterUpgrade, readSchema(reopened), "a second open must not change the schema")
        reopenHelper.close()
    }

    /**
     * An upgrade that ends with an incomplete fork schema must fail as a whole: the exception carries the postcondition marker and
     * the file keeps its recorded version, its fork data and the pre-upgrade representation of everything the upstream updates would
     * have converted. `CREATE TABLE IF NOT EXISTS` cannot repair a column of an existing table, so a folder table without its
     * `position` column is such a case.
     */
    @Test
    fun family127WithBrokenFolderTableFailsAndRollsBackCompletely() {
        val family = LegacyFamily.V127
        val databaseName = nextDatabaseName("rollback")
        createLegacyDatabase(databaseName, family) { db ->
            db.execSQL("ALTER TABLE `chat_folder` DROP COLUMN `position`")
            assertFalse(db.fieldExists("chat_folder", "position"), "precondition: the folder position column must be gone")
        }
        val (rowsBefore, shapeBefore) = readWithoutUpgrade(databaseName, family.version)
        assertEquals(listOf(listOf("1", "Work", "$T0"), listOf("2", "Family", "${T0 + 1L}")), rowsBefore.getValue("chat_folder"))

        val failure = assertFailsWith<DatabaseUpdateException> {
            newHelper(databaseName).writableDatabase
        }
        assertEquals(ForkSchemaPostcondition.FAILURE_MARKER, failure.failedDatabaseUpdateVersion)

        val (rowsAfter, shapeAfter) = readWithoutUpgrade(databaseName, family.version)
        // Whole-file equality covers the rest, the named checks below say why it matters.
        assertEquals(rowsBefore, rowsAfter, "a failed upgrade must leave every row as it was")
        assertEquals(shapeBefore, shapeAfter, "a failed upgrade must leave the schema as it was")
        val recheck = openWithoutUpgrade(databaseName, family.version)
        val db = recheck.writableDatabase
        assertEquals(family.version, db.version, "a failed upgrade must not advance the recorded version")
        assertEquals(2, db.rows("SELECT id FROM chat_folder").size, "the folder rows must survive")
        assertTrue(db.fieldExists("contacts", "isArchived"), "the upstream 126 change must be rolled back")
        assertFalse(db.fieldExists("contacts", "conversationVisibility"), "the upstream 126 change must be rolled back")
        assertTrue(db.fieldExists("contacts", "notificationTriggerPolicyOverride"), "the upstream 127 change must be rolled back")
        assertEquals("1", db.single("SELECT isArchived FROM contacts WHERE identity = '$CONTACT_ARCHIVED'"))
        assertEquals(
            "1",
            db.single("SELECT COUNT(*) FROM conversation_tag WHERE tag = '$LEGACY_PINNED_TAG' AND conversationUid = 'i-$CONTACT_PINNED'"),
            "the upstream 126 tag deletion must be rolled back",
        )
        recheck.close()
    }

    // ---------------------------------------------------------------------------------------------------------------------
    // Fixture construction
    // ---------------------------------------------------------------------------------------------------------------------

    /**
     * Creates the database file of [family], seeds it and records [LegacyFamily.version] as its schema version.
     *
     * @return the rows of the tables the upgrade must not touch, to compare against afterwards
     */
    private fun createLegacyDatabase(
        databaseName: String,
        family: LegacyFamily,
        prepareLegacyDatabase: (SQLiteDatabase) -> Unit,
    ): Map<String, List<List<String?>>> {
        val baseStatements = readStatements(family.baseSchemaAsset)
        val helper = newHelper(databaseName = databaseName, getCreationStatements = { baseStatements.asSequence() })
        val db = helper.writableDatabase

        applyForkAdditions(db, family)
        seed(db, family)
        prepareLegacyDatabase(db)

        assertTrue(db.fieldExists("contacts", "isArchived"), "precondition: the legacy archive column must exist")
        assertFalse(db.fieldExists("contacts", "conversationVisibility"), "precondition: the new visibility column must not exist yet")
        assertEquals(family.hasScheduledMessages, db.tableExists("scheduled_messages"), "precondition: scheduled messages table")

        val untouched = UNTOUCHED_TABLES
            .filter { table -> db.tableExists(table) }
            .associateWith { table -> snapshotTable(db, table) }

        db.version = family.version
        assertEquals(family.version, db.version)
        helper.close()
        return untouched
    }

    /**
     * Brings the 119 base schema to the shape the fork's own updates gave a released database of [family]. The 127 schema already
     * is that shape.
     */
    private fun applyForkAdditions(db: SQLiteDatabase, family: LegacyFamily) {
        if (family == LegacyFamily.V127) {
            return
        }
        if (family.hasScheduledMessages) {
            SCHEDULED_MESSAGES_STATEMENTS.forEach { statement -> db.execSQL(statement) }
        }
        if (family.hasFolders) {
            DatabaseUpdateToVersion90131.STATEMENTS.forEach { statement -> db.execSQL(statement) }
        }
        if (family.hasTimers) {
            DatabaseUpdateToVersion90132.planMigration(db::fieldExists).forEach { statement -> db.execSQL(statement) }
            DatabaseUpdateToVersion90133.planMigration(db::fieldExists).forEach { statement -> db.execSQL(statement) }
        }
        if (family.hasSortKey) {
            DatabaseUpdateToVersion90134.planMigration(db::fieldExists).forEach { statement -> db.execSQL(statement) }
        }
    }

    private fun seed(db: SQLiteDatabase, family: LegacyFamily) {
        seedContacts(db, family)
        seedGroups(db, family)
        seedDistributionLists(db)
        seedConversationTags(db)
        seedMessages(db, family)
        seedChatFolders(db, family)
        seedScheduledMessages(db, family)
    }

    private fun seedContacts(db: SQLiteDatabase, family: LegacyFamily) {
        fun contact(
            identity: String,
            isArchived: Int = 0,
            mute: Long? = null,
            timers: Pair<Int, Int>? = null,
        ) = buildMap<String, Any?> {
            put("identity", identity)
            put("publicKey", ByteArray(32) { index -> (identity.hashCode() + index).toByte() })
            put("firstName", "First$identity")
            put("lastName", "Last$identity")
            put("verificationLevel", 1)
            put("state", "ACTIVE")
            put("featureLevel", 0)
            put("acquaintanceLevel", 0)
            put("isArchived", isArchived)
            put("notificationTriggerPolicyOverride", mute)
            if (family.hasTimers && timers != null) {
                put("disappearingMessagesTimerSeconds", timers.first)
                put("peerDisappearingTimerSeconds", timers.second)
            }
        }
        // Not muted (explicit 0), with a conversation timer that differs from the peer's.
        db.insertRow("contacts", contact(CONTACT_PLAIN, mute = 0L, timers = LOCAL_TIMER_SECONDS to PEER_TIMER_SECONDS))
        db.insertRow("contacts", contact(CONTACT_ARCHIVED, isArchived = 1))
        db.insertRow("contacts", contact(CONTACT_PINNED))
        db.insertRow("contacts", contact(CONTACT_MUTED_UNTIL, mute = MUTE_UNTIL))
        db.insertRow("contacts", contact(CONTACT_MUTED_FOREVER, mute = -1L))
        db.insertRow("contacts", contact(CONTACT_ARCHIVED_AND_PINNED, isArchived = 1))
    }

    private fun seedGroups(db: SQLiteDatabase, family: LegacyFamily) {
        fun group(
            id: Int,
            isArchived: Int = 0,
            mute: Long? = null,
            timers: Pair<Int, Int>? = null,
            negativeTimestamps: Boolean = false,
        ) = buildMap<String, Any?> {
            put("id", id)
            put("apiGroupId", "%016x".format(id))
            put("name", "Group $id")
            put("creatorIdentity", CONTACT_PLAIN)
            put("createdAt", if (negativeTimestamps) -5_000L else T0)
            put("lastUpdateAt", if (negativeTimestamps) -7L else T0)
            put("synchronizedAt", T0)
            put("isArchived", isArchived)
            put("colorIndex", 0)
            put("userState", 0)
            put("notificationTriggerPolicyOverride", mute)
            if (negativeTimestamps) {
                put("changedGroupDescTimestamp", -1L)
            }
            if (family.hasTimers && timers != null) {
                put("disappearingMessagesTimerSeconds", timers.first)
                put("peerDisappearingTimerSeconds", timers.second)
            }
        }
        db.insertRow("m_group", group(GROUP_PLAIN, timers = LOCAL_TIMER_SECONDS to PEER_TIMER_SECONDS, negativeTimestamps = true))
        db.insertRow("m_group", group(GROUP_ARCHIVED, isArchived = 1))
        db.insertRow("m_group", group(GROUP_PINNED))
        db.insertRow("m_group", group(GROUP_MENTIONS_ONLY, mute = -2L))
        db.insertRow("m_group", group(GROUP_MUTED_UNTIL, mute = MUTE_UNTIL))
        db.insertRow("m_group", group(GROUP_MUTED_FOREVER, mute = -1L))

        db.insertRow("group_member", mapOf("identity" to CONTACT_ARCHIVED, "groupId" to GROUP_PLAIN))
        db.insertRow(
            "m_group_request_sync_log",
            mapOf("apiGroupId" to "%016x".format(GROUP_PLAIN), "creatorIdentity" to CONTACT_PLAIN, "lastRequestAt" to -9L),
        )
    }

    private fun seedDistributionLists(db: SQLiteDatabase) {
        db.insertRow(
            "distribution_list",
            mapOf("id" to LIST_ARCHIVED, "name" to "Archived list", "createdAt" to -3L, "lastUpdateAt" to -4L, "isArchived" to 1, "isHidden" to 0),
        )
        db.insertRow(
            "distribution_list",
            mapOf("id" to LIST_PINNED, "name" to "Pinned list", "createdAt" to T0, "lastUpdateAt" to T0, "isArchived" to 0, "isHidden" to 0),
        )
    }

    /** `star` is the stored value of the legacy "pinned" tag; `unread` is a tag that must survive. */
    private fun seedConversationTags(db: SQLiteDatabase) {
        val tags = listOf(
            "i-$CONTACT_PINNED" to LEGACY_PINNED_TAG,
            "i-$CONTACT_ARCHIVED_AND_PINNED" to LEGACY_PINNED_TAG,
            "g-$GROUP_PINNED" to LEGACY_PINNED_TAG,
            "d-$LIST_PINNED" to LEGACY_PINNED_TAG,
            "i-$CONTACT_PLAIN" to UNREAD_TAG,
        )
        tags.forEach { (conversationUid, tag) ->
            db.insertRow("conversation_tag", mapOf("conversationUid" to conversationUid, "tag" to tag, "createdAt" to T0))
        }
    }

    private fun seedMessages(db: SQLiteDatabase, family: LegacyFamily) {
        fun message(
            id: Int,
            type: Int,
            outgoing: Boolean,
            body: String? = "text $id",
            apiMessageId: String? = "%016x".format(0xA000 + id),
            posted: Long? = T0 + id * 1_000L,
            created: Long = T0 + id * 1_000L + 500L,
            isStatus: Int = 0,
            extra: Map<String, Any?> = emptyMap(),
        ) = buildMap<String, Any?> {
            put("id", id)
            put("uid", "uid-$id")
            put("apiMessageId", apiMessageId)
            put("outbox", if (outgoing) 1 else 0)
            put("type", type)
            put("body", body)
            put("isRead", 1)
            put("isSaved", 1)
            put("isQueued", 0)
            put("state", if (outgoing) "SENT" else "READ")
            put("postedAtUtc", posted)
            put("createdAtUtc", created)
            put("modifiedAtUtc", created)
            put("isStatusMessage", isStatus)
            put("messageContentsType", 0)
            put("messageFlags", 0)
            if (family.hasSortKey) {
                put("sortAtUtc", SORT_KEY_BASE + id)
            }
            putAll(extra)
        }

        fun expiry(): Map<String, Any?> = mapOf(
            "expiresAtUtc" to EXPIRES_AT,
            "expireStartedAtUtc" to EXPIRE_STARTED_AT,
            "disappearingTimerSeconds" to MESSAGE_TIMER_SECONDS,
        )

        // 1:1 messages
        fun oneToOne(row: Map<String, Any?>) = db.insertRow("message", row + mapOf("identity" to CONTACT_PLAIN))
        oneToOne(message(id = 1, type = 0, outgoing = false, body = "hello", posted = T0 + 1_000L, created = T0 + 1_500L))
        oneToOne(message(id = 2, type = 0, outgoing = true, posted = T0 + 4_000L, created = T0 + 3_000L))
        oneToOne(message(id = 3, type = TYPE_IMAGE, outgoing = false, body = legacyImageBody(isDownloaded = true)))
        oneToOne(message(id = 4, type = TYPE_IMAGE, outgoing = false, body = legacyImageBody(isDownloaded = false)))
        oneToOne(message(id = 5, type = TYPE_CONTACT, outgoing = false, body = "ABCDEFGH"))
        // Needs a message id (type TEXT) but has none, and has no sender time: the sort key falls back to the creation time.
        oneToOne(
            message(id = 6, type = 0, outgoing = false, apiMessageId = null, posted = null, created = T0 + 6_500L),
        )
        oneToOne(message(id = 7, type = TYPE_STATUS, outgoing = false, body = "status", apiMessageId = ""))
        oneToOne(message(id = 9, type = TYPE_VOICEMESSAGE, outgoing = false, body = legacyAudioBody()))
        if (family.hasTimers) {
            oneToOne(message(id = 8, type = 0, outgoing = true, extra = expiry()))
            oneToOne(
                message(
                    id = 10,
                    type = TYPE_DISAPPEARING_STATUS,
                    outgoing = true,
                    body = "timer 60",
                    apiMessageId = null,
                    isStatus = 1,
                ),
            )
        }

        // Group messages
        fun inGroup(row: Map<String, Any?>) = db.insertRow("m_group_message", row + mapOf("groupId" to GROUP_PLAIN))
        inGroup(message(id = 1, type = 0, outgoing = false, extra = mapOf("identity" to CONTACT_ARCHIVED)))
        inGroup(
            message(
                id = 2,
                type = 0,
                outgoing = true,
                posted = T0 + 4_000L,
                created = T0 + 3_000L,
                extra = mapOf("identity" to MY_IDENTITY),
            ),
        )
        inGroup(
            message(
                id = 3,
                type = TYPE_IMAGE,
                outgoing = false,
                body = legacyImageBody(isDownloaded = true),
                extra = mapOf("identity" to CONTACT_ARCHIVED),
            ),
        )
        inGroup(
            message(
                id = 4,
                type = TYPE_VIDEO,
                outgoing = false,
                body = legacyVideoBody(),
                extra = mapOf("identity" to CONTACT_ARCHIVED),
            ),
        )
        inGroup(
            message(
                id = 5,
                type = TYPE_CONTACT,
                outgoing = false,
                body = "ABCDEFGH",
                extra = mapOf("identity" to CONTACT_ARCHIVED),
            ),
        )
        if (family.hasTimers) {
            inGroup(message(id = 6, type = 0, outgoing = true, extra = expiry() + mapOf("identity" to MY_IDENTITY)))
            inGroup(
                message(
                    id = 7,
                    type = TYPE_DISAPPEARING_STATUS,
                    outgoing = true,
                    body = "timer 60",
                    apiMessageId = null,
                    isStatus = 1,
                    extra = mapOf("identity" to MY_IDENTITY),
                ),
            )
        }

        // Distribution list messages: the expiry columns exist here only in the newest family.
        fun inList(row: Map<String, Any?>) = db.insertRow("distribution_list_message", row + mapOf("distributionListId" to LIST_ARCHIVED))
        inList(message(id = 1, type = TYPE_IMAGE, outgoing = true, body = null, extra = mapOf("identity" to MY_IDENTITY)))
        inList(
            message(
                id = 2,
                type = TYPE_CONTACT,
                outgoing = true,
                body = "ABCDEFGH",
                extra = mapOf("identity" to MY_IDENTITY),
            ),
        )
        if (family.hasDistributionListTimers) {
            inList(message(id = 3, type = 0, outgoing = true, extra = expiry() + mapOf("identity" to MY_IDENTITY)))
        }
    }

    private fun seedChatFolders(db: SQLiteDatabase, family: LegacyFamily) {
        if (!family.hasFolders) {
            return
        }
        db.insertRow("chat_folder", mapOf("id" to 1, "name" to "Work", "position" to 0, "createdAt" to T0))
        db.insertRow("chat_folder", mapOf("id" to 2, "name" to "Family", "position" to 1, "createdAt" to T0 + 1L))
        listOf(1 to "i-$CONTACT_PLAIN", 1 to "g-$GROUP_PLAIN", 2 to "i-$CONTACT_PINNED").forEach { (folderId, conversationUid) ->
            db.insertRow(
                "chat_folder_member",
                mapOf("folderId" to folderId, "conversationUid" to conversationUid, "createdAt" to T0),
            )
        }
    }

    private fun seedScheduledMessages(db: SQLiteDatabase, family: LegacyFamily) {
        if (!family.hasScheduledMessages) {
            return
        }
        db.insertRow(
            "scheduled_messages",
            mapOf(
                "receiverType" to 0,
                "receiverKey" to CONTACT_PLAIN,
                "body" to "a message that was still waiting",
                "scheduledAt" to T0 + 86_400_000L,
                "createdAt" to T0,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------------------------------------
    // Assertions on the upgraded database
    // ---------------------------------------------------------------------------------------------------------------------

    private fun assertSeededStateSurvived(
        db: SQLiteDatabase,
        family: LegacyFamily,
        untouchedBefore: Map<String, List<List<String?>>>,
    ) {
        assertConversationVisibility(db)
        assertMuteSplit(db)
        assertConversationTags(db)
        assertLegacyMediaRowsConverted(db)
        assertMessageIdsBackfilled(db)
        assertNegativeTimestampsCorrected(db)
        assertDisappearingState(db, family)
        assertUntouchedTables(db, untouchedBefore)
    }

    private fun assertConversationVisibility(db: SQLiteDatabase) {
        assertFalse(db.fieldExists("contacts", "isArchived"), "the legacy archive column must be gone from contacts")
        assertFalse(db.fieldExists("m_group", "isArchived"), "the legacy archive column must be gone from m_group")
        assertFalse(db.fieldExists("distribution_list", "isArchived"), "the legacy archive column must be gone from distribution_list")

        assertEquals(
            mapOf(
                CONTACT_PLAIN to "0",
                CONTACT_ARCHIVED to "1",
                CONTACT_PINNED to "2",
                CONTACT_MUTED_UNTIL to "0",
                CONTACT_MUTED_FOREVER to "0",
                // Archived wins over pinned, as the update applies archived last.
                CONTACT_ARCHIVED_AND_PINNED to "1",
            ),
            db.pairs("SELECT identity, conversationVisibility FROM contacts"),
        )
        assertEquals(
            mapOf(
                "$GROUP_PLAIN" to "0",
                "$GROUP_ARCHIVED" to "1",
                "$GROUP_PINNED" to "2",
                "$GROUP_MENTIONS_ONLY" to "0",
                "$GROUP_MUTED_UNTIL" to "0",
                "$GROUP_MUTED_FOREVER" to "0",
            ),
            db.pairs("SELECT id, conversationVisibility FROM m_group"),
        )
        assertEquals(
            mapOf("$LIST_ARCHIVED" to "1", "$LIST_PINNED" to "2"),
            db.pairs("SELECT id, conversationVisibility FROM distribution_list"),
        )
    }

    private fun assertMuteSplit(db: SQLiteDatabase) {
        assertFalse(db.fieldExists("contacts", "notificationTriggerPolicyOverride"), "the old mute column must be gone from contacts")
        assertFalse(db.fieldExists("m_group", "notificationTriggerPolicyOverride"), "the old mute column must be gone from m_group")

        // Contacts: policy 0 means "never notify"; the expiry is set only for a mute until a point in time.
        assertEquals(
            mapOf(
                CONTACT_PLAIN to null,
                CONTACT_ARCHIVED to null,
                CONTACT_PINNED to null,
                CONTACT_MUTED_UNTIL to "0",
                CONTACT_MUTED_FOREVER to "0",
                CONTACT_ARCHIVED_AND_PINNED to null,
            ),
            db.pairs("SELECT identity, notificationTriggerPolicyOverridePolicy FROM contacts"),
        )
        assertEquals(
            mapOf(
                CONTACT_PLAIN to null,
                CONTACT_ARCHIVED to null,
                CONTACT_PINNED to null,
                CONTACT_MUTED_UNTIL to "$MUTE_UNTIL",
                CONTACT_MUTED_FOREVER to null,
                CONTACT_ARCHIVED_AND_PINNED to null,
            ),
            db.pairs("SELECT identity, notificationTriggerPolicyOverrideExpiresAt FROM contacts"),
        )

        // Groups: policy 0 means "only when mentioned", policy 1 means "never notify".
        assertEquals(
            mapOf(
                "$GROUP_PLAIN" to null,
                "$GROUP_ARCHIVED" to null,
                "$GROUP_PINNED" to null,
                "$GROUP_MENTIONS_ONLY" to "0",
                "$GROUP_MUTED_UNTIL" to "1",
                "$GROUP_MUTED_FOREVER" to "1",
            ),
            db.pairs("SELECT id, notificationTriggerPolicyOverridePolicy FROM m_group"),
        )
        assertEquals(
            mapOf(
                "$GROUP_PLAIN" to null,
                "$GROUP_ARCHIVED" to null,
                "$GROUP_PINNED" to null,
                "$GROUP_MENTIONS_ONLY" to null,
                "$GROUP_MUTED_UNTIL" to "$MUTE_UNTIL",
                "$GROUP_MUTED_FOREVER" to null,
            ),
            db.pairs("SELECT id, notificationTriggerPolicyOverrideExpiresAt FROM m_group"),
        )
    }

    private fun assertConversationTags(db: SQLiteDatabase) {
        assertEquals("0", db.single("SELECT COUNT(*) FROM conversation_tag WHERE tag = '$LEGACY_PINNED_TAG'"), "the pin tag must be deleted")
        assertEquals(
            listOf(listOf("i-$CONTACT_PLAIN", UNREAD_TAG)),
            db.rows("SELECT conversationUid, tag FROM conversation_tag"),
            "tags other than the pin tag must survive",
        )
    }

    private fun assertLegacyMediaRowsConverted(db: SQLiteDatabase) {
        // 1:1: downloaded image, not yet downloaded image (keeps its nonce), audio.
        val downloadedImage = db.convertedFileRow("message", 3, contentsType = CONTENTS_TYPE_IMAGE)
        assertTrue(downloadedImage.startsWith("[\"$BLOB_ID_HEX\",\"$KEY_HEX\",\"image/jpeg\","), downloadedImage)
        assertFalse(downloadedImage.contains("_legacy_nonce"), downloadedImage)

        val pendingImage = db.convertedFileRow("message", 4, contentsType = CONTENTS_TYPE_IMAGE)
        assertTrue(pendingImage.startsWith("[\"$BLOB_ID_HEX\",\"$KEY_HEX\",\"image/jpeg\","), pendingImage)
        assertTrue(pendingImage.contains("\"_legacy_nonce\":\"$NONCE_HEX\""), pendingImage)

        val audio = db.convertedFileRow("message", 9, contentsType = CONTENTS_TYPE_AUDIO)
        assertTrue(audio.startsWith("[\"$BLOB_ID_HEX\",\"$KEY_HEX\",\"audio/aac\","), audio)
        assertTrue(audio.contains("\"d\":$AUDIO_DURATION_SECONDS"), audio)

        // Group: image and video.
        val groupImage = db.convertedFileRow("m_group_message", 3, contentsType = CONTENTS_TYPE_IMAGE)
        assertTrue(groupImage.startsWith("[\"$BLOB_ID_HEX\",\"$KEY_HEX\",\"image/jpeg\","), groupImage)

        val groupVideo = db.convertedFileRow("m_group_message", 4, contentsType = CONTENTS_TYPE_VIDEO)
        assertTrue(groupVideo.startsWith("[\"$BLOB_ID_HEX\",\"$KEY_HEX\",\"video/mpeg\",$VIDEO_FILE_SIZE,"), groupVideo)
        assertTrue(groupVideo.contains("\"d\":$VIDEO_DURATION_SECONDS"), groupVideo)

        // Distribution list: an image without a body still becomes a file message (with a fallback body).
        val listImage = db.convertedFileRow("distribution_list_message", 1, contentsType = CONTENTS_TYPE_IMAGE)
        assertTrue(listImage.contains("\"image/jpeg\""), listImage)

        // Contact messages become text messages in every table, and nothing else about the row is rewritten.
        for ((table, id) in listOf("message" to 5, "m_group_message" to 5, "distribution_list_message" to 2)) {
            assertEquals(
                listOf(listOf("$TYPE_TEXT", "ABCDEFGH", "0")),
                db.rows("SELECT type, body, messageContentsType FROM `$table` WHERE id = $id"),
                "$table.$id: a contact message becomes a text message",
            )
        }
        for (table in MESSAGE_TABLES) {
            assertEquals(
                "0",
                db.single("SELECT COUNT(*) FROM `$table` WHERE type IN ($TYPE_IMAGE, $TYPE_VIDEO, $TYPE_VOICEMESSAGE, $TYPE_CONTACT)"),
                "$table: no legacy media or contact rows may remain",
            )
        }
    }

    private fun SQLiteDatabase.convertedFileRow(table: String, id: Int, contentsType: Int): String {
        val row = rows("SELECT type, messageContentsType, body FROM `$table` WHERE id = $id").single()
        assertEquals("$TYPE_FILE", row[0], "$table.$id: converted media rows become file messages")
        assertEquals("$contentsType", row[1], "$table.$id: contents type")
        return assertNotNull(row[2], "$table.$id: a file body must be written")
    }

    private fun assertMessageIdsBackfilled(db: SQLiteDatabase) {
        val backfilled = assertNotNull(db.single("SELECT apiMessageId FROM message WHERE id = 6"), "a text message must get a message id")
        assertTrue(Regex("[0-9a-f]{16}").matches(backfilled), "backfilled message id: $backfilled")
        assertNull(db.single("SELECT apiMessageId FROM message WHERE id = 7"), "an empty message id of a status message becomes NULL")
        assertEquals("%016x".format(0xA001), db.single("SELECT apiMessageId FROM message WHERE id = 1"), "existing message ids stay")
        assertEquals("%016x".format(0xA002), db.single("SELECT apiMessageId FROM message WHERE id = 2"), "existing message ids stay")
    }

    /** The negative-timestamp corrections (upstream 124 and 125) apply to every family. */
    private fun assertNegativeTimestampsCorrected(db: SQLiteDatabase) {
        assertEquals(
            listOf(listOf("0", "0", "0")),
            db.rows("SELECT createdAt, lastUpdateAt, changedGroupDescTimestamp FROM m_group WHERE id = $GROUP_PLAIN"),
        )
        assertEquals(
            listOf(listOf("0", "0")),
            db.rows("SELECT createdAt, lastUpdateAt FROM distribution_list WHERE id = $LIST_ARCHIVED"),
        )
        assertEquals("0", db.single("SELECT lastRequestAt FROM m_group_request_sync_log"))
        assertEquals("$T0", db.single("SELECT createdAt FROM m_group WHERE id = $GROUP_ARCHIVED"), "non-negative timestamps stay")
    }

    private fun assertDisappearingState(db: SQLiteDatabase, family: LegacyFamily) {
        val expiryRow = { id: Int -> listOf("$id", "$EXPIRES_AT", "$EXPIRE_STARTED_AT", "$MESSAGE_TIMER_SECONDS") }
        val expirySql = { table: String ->
            "SELECT id, expiresAtUtc, expireStartedAtUtc, disappearingTimerSeconds FROM `$table` WHERE expiresAtUtc IS NOT NULL"
        }
        assertEquals(if (family.hasTimers) listOf(expiryRow(8)) else emptyList(), db.rows(expirySql("message")))
        assertEquals(if (family.hasTimers) listOf(expiryRow(6)) else emptyList(), db.rows(expirySql("m_group_message")))
        assertEquals(if (family.hasDistributionListTimers) listOf(expiryRow(3)) else emptyList(), db.rows(expirySql("distribution_list_message")))

        // The stored value 14 (disappearing status) is not touched by any update.
        val statusCount = if (family.hasTimers) "1" else "0"
        assertEquals(statusCount, db.single("SELECT COUNT(*) FROM message WHERE type = $TYPE_DISAPPEARING_STATUS"))
        assertEquals(statusCount, db.single("SELECT COUNT(*) FROM m_group_message WHERE type = $TYPE_DISAPPEARING_STATUS"))
        if (family.hasTimers) {
            assertEquals(listOf(listOf("timer 60", null)), db.rows("SELECT body, apiMessageId FROM message WHERE id = 10"))
            assertEquals(listOf(listOf("timer 60", null)), db.rows("SELECT body, apiMessageId FROM m_group_message WHERE id = 7"))
        }

        // Per-conversation timers: kept as they were, NULL (so no row matches) where the family had no such column.
        val timerRows = { id: String -> if (family.hasTimers) listOf(listOf(id, "$LOCAL_TIMER_SECONDS", "$PEER_TIMER_SECONDS")) else emptyList() }
        assertEquals(
            timerRows(CONTACT_PLAIN),
            db.rows(
                "SELECT identity, disappearingMessagesTimerSeconds, peerDisappearingTimerSeconds FROM contacts " +
                    "WHERE disappearingMessagesTimerSeconds IS NOT NULL OR peerDisappearingTimerSeconds IS NOT NULL",
            ),
        )
        assertEquals(
            timerRows("$GROUP_PLAIN"),
            db.rows(
                "SELECT id, disappearingMessagesTimerSeconds, peerDisappearingTimerSeconds FROM m_group " +
                    "WHERE disappearingMessagesTimerSeconds IS NOT NULL OR peerDisappearingTimerSeconds IS NOT NULL",
            ),
        )
    }

    /** Rows of tables the whole upgrade has no business changing (chat folders and their members, group members). */
    private fun assertUntouchedTables(db: SQLiteDatabase, untouchedBefore: Map<String, List<List<String?>>>) {
        for (table in UNTOUCHED_TABLES) {
            assertTrue(db.tableExists(table), "$table must exist after the upgrade")
            assertEquals(untouchedBefore[table].orEmpty(), snapshotTable(db, table), "$table must be unchanged")
        }
    }

    /**
     * Sort keys: in tables that already had the key it must keep its deliberately non-formula value, in tables that get it from the
     * upgrade it is initialised by the formula (outgoing: creation time, incoming: sender time, else creation time).
     */
    private fun assertSortKeys(db: SQLiteDatabase, family: LegacyFamily, formulaTables: Set<String>) {
        val expectedFormulaKeys = mapOf(
            "message" to mapOf("1" to T0 + 1_000L, "2" to T0 + 3_000L, "6" to T0 + 6_500L),
            "m_group_message" to mapOf("1" to T0 + 1_000L, "2" to T0 + 3_000L),
            "distribution_list_message" to mapOf("1" to T0 + 1_500L),
        )
        val seededIds = seededMessageIds(family)
        for (table in MESSAGE_TABLES) {
            val ids = seededIds.getValue(table)
            assertEquals("0", db.single("SELECT COUNT(*) FROM `$table` WHERE sortAtUtc IS NULL"), "$table: every row needs a sort key")
            // Exactly the seeded rows must exist: no row lost, none invented.
            assertEquals(
                ids.map { id -> "$id" },
                db.rows("SELECT id FROM `$table` ORDER BY id").map { row -> row[0] },
                "$table: every seeded message must survive",
            )
            if (table in formulaTables) {
                val expected = expectedFormulaKeys.getValue(table)
                assertEquals(
                    expected.mapValues { (_, value) -> "$value" },
                    db.pairs("SELECT id, sortAtUtc FROM `$table` WHERE id IN (${expected.keys.joinToString()})"),
                    "$table: the sort key must be initialised by the formula",
                )
            } else {
                assertEquals(
                    ids.associate { id -> "$id" to "${SORT_KEY_BASE + id}" },
                    db.pairs("SELECT id, sortAtUtc FROM `$table`"),
                    "$table: an existing sort key must not be overwritten",
                )
            }
        }
    }

    /** The ids [seedMessages] inserts into each message table for [family], in ascending order. */
    private fun seededMessageIds(family: LegacyFamily): Map<String, List<Int>> = mapOf(
        "message" to (listOf(1, 2, 3, 4, 5, 6, 7, 9) + if (family.hasTimers) listOf(8, 10) else emptyList()).sorted(),
        "m_group_message" to listOf(1, 2, 3, 4, 5) + if (family.hasTimers) listOf(6, 7) else emptyList(),
        "distribution_list_message" to listOf(1, 2) + if (family.hasDistributionListTimers) listOf(3) else emptyList(),
    )

    private fun assertScheduledMessagesGone(db: SQLiteDatabase) {
        assertFalse(db.tableExists("scheduled_messages"), "the scheduled messages table must be dropped")
        assertEquals(
            "0",
            db.single("SELECT COUNT(*) FROM sqlite_master WHERE tbl_name = 'scheduled_messages' OR name LIKE 'scheduledMessages%'"),
            "the scheduled messages indexes must be dropped with the table",
        )
    }

    private fun assertSchemaConverged(actual: SchemaShape) {
        val differences = actual.differencesFrom(freshSchema)
        assertTrue(differences.isEmpty(), "the upgraded schema must equal a fresh install:\n" + differences.joinToString("\n"))
    }

    // ---------------------------------------------------------------------------------------------------------------------
    // Database plumbing
    // ---------------------------------------------------------------------------------------------------------------------

    /**
     * Runs all fork updates again on an already upgraded database. They are written to be safe on a database where they applied
     * already, so nothing may change.
     */
    private fun replayForkUpdates(db: SQLiteDatabase) {
        listOf(
            DatabaseUpdateToVersion90131(db),
            DatabaseUpdateToVersion90132(db),
            DatabaseUpdateToVersion90133(db),
            DatabaseUpdateToVersion90134(db),
            DatabaseUpdateToVersion90135(db),
            DatabaseUpdateToVersion90136(db),
        ).forEach { update -> update.run() }
    }

    /** A helper that declares [version], so opening a file recorded at that version runs no create or upgrade step. */
    private fun openWithoutUpgrade(databaseName: String, version: Int): PermanentlyCloseableSQLiteOpenHelper =
        object : PermanentlyCloseableSQLiteOpenHelper(
            context = appContext,
            name = databaseName,
            password = PASSWORD.toByteArray(),
            version = version,
            errorHandler = null,
            databaseHook = object : SQLiteDatabaseHook {
                override fun preKey(connection: SQLiteConnection) {
                    connection.execute("PRAGMA cipher_default_kdf_iter = 1;", emptyArray(), null)
                }

                override fun postKey(connection: SQLiteConnection) {
                    connection.execute("PRAGMA kdf_iter = 1;", emptyArray(), null)
                }
            },
            enableWriteAheadLogging = true,
        ) {
            override fun onCreate(db: SQLiteDatabase) {
                error("The file must already exist")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("The file must already be at version $version, was $oldVersion")
            }
        }

    /** All rows and the schema of a file, read without running any update. */
    private fun readWithoutUpgrade(databaseName: String, version: Int): Pair<Map<String, List<List<String?>>>, SchemaShape> {
        val helper = openWithoutUpgrade(databaseName, version)
        val db = helper.writableDatabase
        assertEquals(version, db.version)
        val result = snapshotAllTables(db) to readSchema(db)
        helper.close()
        return result
    }

    private fun nextDatabaseName(label: String): String {
        val name = "fork-upgrade-$label-${System.nanoTime()}-${databaseCounter++}.db"
        createdDatabases += name
        return name
    }

    /** A new helper per open: closing a helper zeroes its password array, so the array cannot be shared. */
    private fun newHelper(
        databaseName: String,
        getCreationStatements: () -> Sequence<String> = DatabaseSchemaCreator::getCreationStatements,
    ): DatabaseOpenHelper =
        DatabaseOpenHelper(
            appContext = appContext,
            databaseName = databaseName,
            password = PASSWORD.toByteArray(),
            getCreationStatements = getCreationStatements,
            downgradeHelper = object : DatabaseDowngradeHelper {
                override fun onDowngrade(oldVersion: Int) {
                    error("The upgrade test must never downgrade, was at $oldVersion")
                }
            },
        ).also { helper -> openedHelpers += helper }

    private fun readStatements(assetPath: String): List<String> =
        InstrumentationRegistry.getInstrumentation().context.assets.open(assetPath).bufferedReader().use { reader -> reader.readText() }
            .lineSequence()
            .filterNot { line -> line.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { statement -> statement.trim() }
            .filter { statement -> statement.isNotEmpty() }

    private fun SQLiteDatabase.insertRow(table: String, values: Map<String, Any?>) {
        val columns = values.keys.joinToString(", ") { column -> "`$column`" }
        val placeholders = values.keys.joinToString(", ") { "?" }
        execSQL("INSERT INTO `$table` ($columns) VALUES ($placeholders)", values.values.toTypedArray())
    }

    private fun SQLiteDatabase.rows(sql: String): List<List<String?>> =
        rawQuery(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { column -> cursor.cellAsString(column) })
                }
            }
        }

    private fun SQLiteDatabase.single(sql: String): String? = rows(sql).single().single()

    /** First column to second column, for queries that return a unique key and a value. */
    private fun SQLiteDatabase.pairs(sql: String): Map<String, String?> =
        rows(sql).associate { row -> checkNotNull(row[0]) to row[1] }

    private fun Cursor.cellAsString(column: Int): String? =
        when (getType(column)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_BLOB -> getBlob(column).joinToString(separator = "") { byte -> "%02x".format(byte) }
            else -> getString(column)
        }

    private fun tableNames(db: SQLiteDatabase): List<String> =
        db.rows(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT GLOB 'sqlite_*' AND name != 'android_metadata' ORDER BY name",
        ).map { row -> checkNotNull(row[0]) }

    private fun snapshotTable(db: SQLiteDatabase, table: String): List<List<String?>> =
        db.rows("SELECT * FROM `$table` ORDER BY rowid")

    private fun snapshotAllTables(db: SQLiteDatabase): Map<String, List<List<String?>>> =
        tableNames(db).associateWith { table -> snapshotTable(db, table) }

    // ---------------------------------------------------------------------------------------------------------------------
    // Schema shape
    // ---------------------------------------------------------------------------------------------------------------------

    private data class ColumnShape(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val default: String?,
        val primaryKeyPosition: Int,
    )

    private data class IndexShape(val name: String, val unique: Boolean, val columns: List<String>)

    /** Column order and column ids are deliberately not part of the shape: ALTER-added columns always sit last. */
    private data class SchemaShape(
        val columns: Map<String, Set<ColumnShape>>,
        val indexes: Map<String, Set<IndexShape>>,
        val otherObjects: Set<String>,
    ) {
        fun differencesFrom(expected: SchemaShape): List<String> = buildList {
            (columns.keys - expected.columns.keys).forEach { table -> add("extra table $table") }
            (expected.columns.keys - columns.keys).forEach { table -> add("missing table $table") }
            for (table in columns.keys.intersect(expected.columns.keys)) {
                (columns.getValue(table) - expected.columns.getValue(table)).forEach { add("$table: unexpected column $it") }
                (expected.columns.getValue(table) - columns.getValue(table)).forEach { add("$table: missing column $it") }
                (indexes.getValue(table) - expected.indexes.getValue(table)).forEach { add("$table: unexpected index $it") }
                (expected.indexes.getValue(table) - indexes.getValue(table)).forEach { add("$table: missing index $it") }
            }
            (otherObjects - expected.otherObjects).forEach { add("extra object $it") }
            (expected.otherObjects - otherObjects).forEach { add("missing object $it") }
        }
    }

    private fun readSchema(db: SQLiteDatabase): SchemaShape {
        val tables = tableNames(db)
        val columns = tables.associateWith { table ->
            db.rows("SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('$table')")
                .map { row ->
                    ColumnShape(
                        name = checkNotNull(row[0]),
                        type = row[1].orEmpty(),
                        notNull = row[2] != "0",
                        default = row[3],
                        primaryKeyPosition = checkNotNull(row[4]).toInt(),
                    )
                }
                .toSet()
        }
        val indexes = tables.associateWith { table ->
            db.rows("SELECT name, \"unique\" FROM pragma_index_list('$table')")
                .map { row ->
                    val indexName = checkNotNull(row[0])
                    val indexColumns = db.rows("SELECT name FROM pragma_index_info('$indexName') ORDER BY seqno")
                        .map { columnRow -> columnRow[0].orEmpty() }
                    IndexShape(name = indexName, unique = row[1] != "0", columns = indexColumns)
                }
                .toSet()
        }
        val otherObjects = db.rows("SELECT type || ':' || name FROM sqlite_master WHERE type IN ('view', 'trigger')")
            .map { row -> checkNotNull(row[0]) }
            .toSet()
        return SchemaShape(columns = columns, indexes = indexes, otherObjects = otherObjects)
    }

    // ---------------------------------------------------------------------------------------------------------------------
    // Legacy bodies and family definitions
    // ---------------------------------------------------------------------------------------------------------------------

    /** The pre-120 image body: downloaded flag, key, blob id, nonce (all hex). */
    private fun legacyImageBody(isDownloaded: Boolean) = """[$isDownloaded,"$KEY_HEX","$BLOB_ID_HEX","$NONCE_HEX"]"""

    /** The pre-120 audio body: duration, downloaded flag, key, blob id. */
    private fun legacyAudioBody() = """[$AUDIO_DURATION_SECONDS,true,"$KEY_HEX","$BLOB_ID_HEX"]"""

    /** The pre-120 video body: duration, downloaded flag, key, blob id, file size. */
    private fun legacyVideoBody() = """[$VIDEO_DURATION_SECONDS,true,"$KEY_HEX","$BLOB_ID_HEX",$VIDEO_FILE_SIZE]"""

    private enum class LegacyFamily(
        val version: Int,
        val baseSchemaAsset: String,
        val hasFolders: Boolean,
        val hasTimers: Boolean,
        val hasSortKey: Boolean,
        val hasScheduledMessages: Boolean,
        val hasDistributionListTimers: Boolean,
    ) {
        V119(
            version = 119,
            baseSchemaAsset = "database/schema-119.sql",
            hasFolders = false,
            hasTimers = false,
            hasSortKey = false,
            hasScheduledMessages = false,
            hasDistributionListTimers = false,
        ),
        V121(
            version = 121,
            baseSchemaAsset = "database/schema-119.sql",
            hasFolders = true,
            hasTimers = false,
            hasSortKey = false,
            hasScheduledMessages = true,
            hasDistributionListTimers = false,
        ),
        V123(
            version = 123,
            baseSchemaAsset = "database/schema-119.sql",
            hasFolders = true,
            hasTimers = true,
            hasSortKey = false,
            hasScheduledMessages = true,
            hasDistributionListTimers = false,
        ),
        V124(
            version = 124,
            baseSchemaAsset = "database/schema-119.sql",
            hasFolders = true,
            hasTimers = true,
            hasSortKey = true,
            hasScheduledMessages = true,
            hasDistributionListTimers = false,
        ),
        V127(
            version = 127,
            baseSchemaAsset = "database/schema-127.sql",
            hasFolders = true,
            hasTimers = true,
            hasSortKey = true,
            hasScheduledMessages = false,
            hasDistributionListTimers = true,
        ),
    }

    private companion object {
        const val PASSWORD = "fork-upgrade-families-test-key"

        const val T0 = 1_700_000_000_000L
        const val MUTE_UNTIL = 4_102_444_800_000L
        const val EXPIRES_AT = T0 + 100_000L
        const val EXPIRE_STARTED_AT = T0 + 40_000L
        const val MESSAGE_TIMER_SECONDS = 60
        const val LOCAL_TIMER_SECONDS = 3_600
        const val PEER_TIMER_SECONDS = 60

        /** Far from both branches of the sort key formula, so an overwritten value cannot go unnoticed. */
        const val SORT_KEY_BASE = 9_000_000L

        const val MY_IDENTITY = "MYIDENT1"
        const val CONTACT_PLAIN = "AAAAAAAA"
        const val CONTACT_ARCHIVED = "BBBBBBBB"
        const val CONTACT_PINNED = "CCCCCCCC"
        const val CONTACT_MUTED_UNTIL = "DDDDDDDD"
        const val CONTACT_MUTED_FOREVER = "EEEEEEEE"
        const val CONTACT_ARCHIVED_AND_PINNED = "FFFFFFFF"

        const val GROUP_PLAIN = 1
        const val GROUP_ARCHIVED = 2
        const val GROUP_PINNED = 3
        const val GROUP_MENTIONS_ONLY = 4
        const val GROUP_MUTED_UNTIL = 5
        const val GROUP_MUTED_FOREVER = 6

        const val LIST_ARCHIVED = 1
        const val LIST_PINNED = 2

        /** The value the legacy "pinned" conversation tag was stored under. */
        const val LEGACY_PINNED_TAG = "star"
        const val UNREAD_TAG = "unread"

        // Stored message types.
        const val TYPE_TEXT = 0
        const val TYPE_IMAGE = 1
        const val TYPE_VIDEO = 2
        const val TYPE_VOICEMESSAGE = 3
        const val TYPE_CONTACT = 5
        const val TYPE_STATUS = 6
        const val TYPE_FILE = 8
        const val TYPE_DISAPPEARING_STATUS = 14

        const val CONTENTS_TYPE_IMAGE = 2
        const val CONTENTS_TYPE_VIDEO = 3
        const val CONTENTS_TYPE_AUDIO = 4

        val KEY_HEX = "11".repeat(32)
        val BLOB_ID_HEX = "22".repeat(16)
        val NONCE_HEX = "33".repeat(24)
        const val AUDIO_DURATION_SECONDS = 7
        const val VIDEO_DURATION_SECONDS = 12
        const val VIDEO_FILE_SIZE = 4_096

        val MESSAGE_TABLES = setOf("message", "m_group_message", "distribution_list_message")

        /** Tables whose rows the upgrade must carry over verbatim. */
        val UNTOUCHED_TABLES = listOf("chat_folder", "chat_folder_member", "group_member")

        /** The table and indexes of the removed scheduled-messaging feature, exactly as it created them. */
        val SCHEDULED_MESSAGES_STATEMENTS = listOf(
            """
            CREATE TABLE IF NOT EXISTS `scheduled_messages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `receiverType` INTEGER NOT NULL,
                `receiverKey` VARCHAR NOT NULL,
                `body` VARCHAR NOT NULL,
                `scheduledAt` BIGINT NOT NULL,
                `createdAt` BIGINT NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `scheduledMessagesScheduledAt` ON `scheduled_messages` ( `scheduledAt` )",
            "CREATE INDEX IF NOT EXISTS `scheduledMessagesReceiver` ON `scheduled_messages` ( `receiverType`, `receiverKey` )",
        )
    }
}
