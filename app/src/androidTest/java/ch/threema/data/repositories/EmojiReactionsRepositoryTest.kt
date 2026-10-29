package ch.threema.data.repositories

import ch.threema.app.TestMultiDeviceManager
import ch.threema.app.TestTaskManager
import ch.threema.app.testutils.TestHelpers
import ch.threema.data.ModelCache
import ch.threema.data.ModelTypeCache
import ch.threema.data.models.EmojiReactionData
import ch.threema.data.models.EmojiReactionsModel
import ch.threema.data.repositories.EmojiReactionsRepository.ReactionMessageIdentifier
import ch.threema.data.storage.EmojiReactionsDao
import ch.threema.data.storage.EmojiReactionsDaoImpl
import ch.threema.domain.helpers.UnusedTaskCodec
import ch.threema.domain.models.MessageId
import ch.threema.domain.types.Identity
import ch.threema.storage.factories.GroupMessageModelFactory
import ch.threema.storage.factories.MessageModelFactory
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.DistributionListMessageModel
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.group.GroupMessageModel
import ch.threema.test.TestDatabaseProvider
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

class EmojiReactionsRepositoryTest {
    private lateinit var multiDeviceManager: TestMultiDeviceManager
    private lateinit var taskManager: TestTaskManager
    private lateinit var messageModelFactory: MessageModelFactory
    private lateinit var groupMessageModelFactory: GroupMessageModelFactory
    private lateinit var databaseProvider: TestDatabaseProvider
    private lateinit var emojiReactionsRepository: EmojiReactionsRepository
    private lateinit var emojiReactionDao: EmojiReactionsDao

    private val reactedAt = Instant.ofEpochMilli(123456789)

    @BeforeTest
    fun before() {
        databaseProvider = TestDatabaseProvider()
        messageModelFactory = MessageModelFactory(databaseProvider)
        groupMessageModelFactory = GroupMessageModelFactory(databaseProvider)
        multiDeviceManager = TestMultiDeviceManager()
        taskManager = TestTaskManager(UnusedTaskCodec())

        val cache = ModelCache()
        emojiReactionDao = EmojiReactionsDaoImpl(databaseProvider)
        emojiReactionsRepository = EmojiReactionsRepository(
            cache = cache.emojiReaction,
            emojiReactionDao = emojiReactionDao,
            identityProvider = mockk {
                every { getIdentity() } returns Identity(TestHelpers.TEST_CONTACT.identity)
                every { getIdentityString() } returns TestHelpers.TEST_CONTACT.identity
            },
            multiDeviceManager = multiDeviceManager,
            taskManager = taskManager,
        )
    }

    @Test
    fun testEmojiReactionForeignKeyConstraint() {
        val contactMessage = MessageModel().enrich()

        assertFailsWith<EmojiReactionEntryCreateException> {
            emojiReactionsRepository.createEntry(
                targetMessage = contactMessage,
                senderIdentity = "ABCDEFGH",
                emojiSequence = "\uD83C\uDFC8",
                reactedAt = reactedAt,
            )
        }

        messageModelFactory.create(contactMessage)

        contactMessage.assertEmojiReactionSize(0)

        contactMessage.body = "reacted"

        emojiReactionsRepository.createEntry(
            targetMessage = contactMessage,
            senderIdentity = "ABCDEFGH",
            emojiSequence = "⚽",
            reactedAt = reactedAt,
        )
        messageModelFactory.update(contactMessage)

        contactMessage.assertEmojiReactionSize(1)

        messageModelFactory.delete(contactMessage)

        contactMessage.assertEmojiReactionSize(0)
    }

    @Test
    fun testGroupEmojiReactionForeignKeyConstraint() {
        val groupMessage = GroupMessageModel().enrich()

        assertFailsWith<EmojiReactionEntryCreateException> {
            emojiReactionsRepository.createEntry(
                targetMessage = groupMessage,
                senderIdentity = "ABCDEFGH",
                emojiSequence = "⚾",
                reactedAt = reactedAt,
            )
        }

        groupMessageModelFactory.create(groupMessage)

        groupMessage.assertEmojiReactionSize(0)

        groupMessage.body = "Reacted"

        emojiReactionsRepository.createEntry(
            targetMessage = groupMessage,
            senderIdentity = "ABCDEFGH",
            emojiSequence = "⚽",
            reactedAt = reactedAt,
        )
        groupMessageModelFactory.update(groupMessage)

        groupMessage.assertEmojiReactionSize(1)

        groupMessageModelFactory.delete(groupMessage)

        groupMessage.assertEmojiReactionSize(0)
    }

    /**
     * F1Whisper: reapplying a reaction is a no-op, not an error.
     *
     * This test used to assert the opposite - that the second insert raises - which is the behaviour the field logs
     * caught: six `EmojiReactionEntryCreateException`s from a `UNIQUE constraint failed` on a device with a linked
     * desktop. The table declares `ON CONFLICT REPLACE`; the insert overrode it with `CONFLICT_ROLLBACK`. A duplicate
     * arrives without any repeated user action, so it is now ignored - and specifically ignored rather than REPLACEd,
     * because the stored `reactedAt` orders the reaction UI, the web buckets and the backup export.
     */
    @Test
    fun testDuplicateEmojiReactionIsIgnoredAndKeepsTheOriginalTimestamp() {
        val message = MessageModel().enrich()
        messageModelFactory.create(message)

        message.assertEmojiReactionSize(0)
        message.body = "reacted"

        emojiReactionsRepository.createEntry(
            targetMessage = message,
            senderIdentity = "ABCDEFGH",
            emojiSequence = "⚽",
            reactedAt = reactedAt,
        )
        messageModelFactory.update(message)

        message.assertEmojiReactionSize(1)
        val originalReactedAt = emojiReactionDao.findAllByMessage(message).single().reactedAt

        // Two linked devices applying the same emoji, or a redelivered message, land here.
        emojiReactionsRepository.createEntry(
            targetMessage = message,
            senderIdentity = "ABCDEFGH",
            emojiSequence = "⚽",
            reactedAt = reactedAt,
        )

        message.assertEmojiReactionSize(1)
        assertEquals(
            originalReactedAt,
            emojiReactionDao.findAllByMessage(message).single().reactedAt,
            "the persisted reaction must keep the timestamp of the FIRST apply",
        )

        val reactions = emojiReactionsRepository.getReactionsByMessage(message)
        assertNotNull(reactions)

        val reaction = reactions.data!!.single()
        assertEquals("⚽", reaction.emojiSequence)
        assertEquals(
            originalReactedAt,
            reaction.reactedAt,
            "and the cache must publish that same timestamp, not the one the second attempt invented",
        )

        messageModelFactory.delete(message)
    }

    @Test
    fun testContactAndGroupReactionsNotMixedUp() {
        val contactMessage = MessageModel().enrich()
        val groupMessage = GroupMessageModel().enrich()

        messageModelFactory.create(contactMessage)
        groupMessageModelFactory.create(groupMessage)

        assertEquals(1, contactMessage.id)
        assertEquals(1, groupMessage.id)

        contactMessage.assertEmojiReactionSize(0)
        groupMessage.assertEmojiReactionSize(0)

        emojiReactionsRepository.createEntry(
            targetMessage = contactMessage,
            senderIdentity = "ABCD1234",
            emojiSequence = "⚾",
            reactedAt = reactedAt,
        )

        contactMessage.assertEmojiReactionSize(1)
        groupMessage.assertEmojiReactionSize(0)

        emojiReactionsRepository.createEntry(
            targetMessage = groupMessage,
            senderIdentity = "ABCD1234",
            emojiSequence = "⛵",
            reactedAt = reactedAt,
        )

        contactMessage.assertEmojiReactionSize(1)
        groupMessage.assertEmojiReactionSize(1)

        val contactReaction =
            emojiReactionsRepository.getReactionsByMessage(contactMessage)!!.data!![0]
        val groupReaction =
            emojiReactionsRepository.getReactionsByMessage(groupMessage)!!.data!![0]

        assertEquals("⚾", contactReaction.emojiSequence)
        assertEquals("⛵", groupReaction.emojiSequence)
    }

    @Test
    fun testContactAndGroupReactionsNotMixedUpWhenRemoved() {
        val reactionSequence = "⛵"

        val contactMessage = MessageModel().enrich()
        val groupMessage = GroupMessageModel().enrich()

        messageModelFactory.create(contactMessage)
        groupMessageModelFactory.create(groupMessage)

        assertEquals(1, contactMessage.id)
        assertEquals(1, groupMessage.id)

        contactMessage.assertEmojiReactionSize(0)
        groupMessage.assertEmojiReactionSize(0)

        emojiReactionsRepository.createEntry(
            targetMessage = contactMessage,
            senderIdentity = "ABCD1234",
            emojiSequence = reactionSequence,
            reactedAt = reactedAt,
        )

        contactMessage.assertEmojiReactionSize(1)
        groupMessage.assertEmojiReactionSize(0)

        emojiReactionsRepository.createEntry(
            targetMessage = groupMessage,
            senderIdentity = "ABCD1234",
            emojiSequence = reactionSequence,
            reactedAt = reactedAt,
        )

        contactMessage.assertEmojiReactionSize(1)
        groupMessage.assertEmojiReactionSize(1)

        emojiReactionsRepository.removeEntry(contactMessage, "ABCD1234", reactionSequence)

        contactMessage.assertEmojiReactionSize(0)
        groupMessage.assertEmojiReactionSize(1)

        emojiReactionsRepository.removeEntry(groupMessage, "ABCD1234", reactionSequence)

        contactMessage.assertEmojiReactionSize(0)
        groupMessage.assertEmojiReactionSize(0)
    }

    @Test
    fun testEmojiReactionsModelCaching() {
        val testEmojiCache = ModelTypeCache<ReactionMessageIdentifier, EmojiReactionsModel>()

        val contactMessage = MessageModel().enrich()
        messageModelFactory.create(contactMessage)

        // Test successful creation of reaction-message-identifier
        val reactionMessageIdentifier = ReactionMessageIdentifier.fromMessageModel(contactMessage)
        assertNotNull(reactionMessageIdentifier)

        // Test unsuccessful creation of reaction-message-identifier
        val reactionMessageIdentifierNull = ReactionMessageIdentifier.fromMessageModel(
            messageModel = DistributionListMessageModel(),
        )
        assertNull(reactionMessageIdentifierNull)

        // Test reading empty cache
        var cachedEntry: EmojiReactionsModel? = testEmojiCache.get(reactionMessageIdentifier)
        assertNull(cachedEntry)

        // Test reading empty cache but creating entity
        val emojiReactionData = EmojiReactionData(
            contactMessage.id,
            senderIdentity = "ABCD1234",
            emojiSequence = "⛵",
            reactedAt = Instant.now(),
        )
        val emojiReactionsModel = EmojiReactionsModel(
            data = listOf(emojiReactionData),
            multiDeviceManager = multiDeviceManager,
            taskManager = taskManager,
        )
        cachedEntry = testEmojiCache.getOrCreate(reactionMessageIdentifier) { emojiReactionsModel }
        assertContentEquals(listOf(emojiReactionData), cachedEntry!!.data)

        // Test should read the cached value
        testEmojiCache.getOrCreate(reactionMessageIdentifier) {
            fail("Should not call this miss() function")
        }
        assertNotNull(testEmojiCache.get(reactionMessageIdentifier))

        // Test removing from cache
        val removedEmojiReactionsModel = testEmojiCache.remove(reactionMessageIdentifier)
        assertContentEquals(listOf(emojiReactionData), removedEmojiReactionsModel!!.data)
        assertNull(testEmojiCache.get(reactionMessageIdentifier))
    }

    @Test
    fun testCacheCollision() {
        // arrange
        val testEmojiCache = ModelTypeCache<ReactionMessageIdentifier, EmojiReactionsModel>()
        val contactMessageId = 1
        val groupMessageId = 1
        val reactionMessageIdentifierContact = ReactionMessageIdentifier(
            messageId = contactMessageId,
            messageType = ReactionMessageIdentifier.TargetMessageType.ONE_TO_ONE,
        )
        val reactionMessageIdentifierGroup = ReactionMessageIdentifier(
            messageId = groupMessageId,
            messageType = ReactionMessageIdentifier.TargetMessageType.GROUP,
        )
        // Add only the emoji reaction of the 1:1 message to the cache
        val emojiReactionDataForContactMessage = EmojiReactionData(
            messageId = contactMessageId,
            senderIdentity = "ABCD1234",
            emojiSequence = "⛵",
            reactedAt = Instant.now(),
        )
        val emojiReactionsModelContact = EmojiReactionsModel(
            data = listOf(emojiReactionDataForContactMessage),
            multiDeviceManager = multiDeviceManager,
            taskManager = taskManager,
        )

        val cachedEntryContact =
            testEmojiCache.getOrCreate(reactionMessageIdentifierContact) { emojiReactionsModelContact }

        assertContentEquals(
            listOf(emojiReactionDataForContactMessage),
            cachedEntryContact!!.data,
        )
        assertNull(testEmojiCache.get(reactionMessageIdentifierGroup))

        testEmojiCache.remove(reactionMessageIdentifierGroup)
        assertNotNull(testEmojiCache.get(reactionMessageIdentifierContact))
    }

    private fun AbstractMessageModel.assertEmojiReactionSize(expectedSize: Int) {
        val actualSize = emojiReactionDao.findAllByMessage(this).size

        assertEquals(expectedSize, actualSize)
    }

    private fun <T : AbstractMessageModel> T.enrich(text: String = "Text"): T {
        messageId = MessageId.random()
        type = MessageType.TEXT
        uid = UUID.randomUUID().toString()
        body = text
        return this
    }
}
