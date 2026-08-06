package ch.threema.storage.factories

import android.content.ContentValues
import android.database.Cursor
import ch.threema.storage.ColumnIndexCache
import ch.threema.storage.CursorHelper
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.MessageState
import ch.threema.storage.models.MessageType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper (per-message disappearing timer): the persistence layer must round-trip a stored `0` as `0`, never coercing
 * it to `null`.
 *
 * `disappearingTimerSeconds` carries a TRI-state since this wave: `null` = the sender advertised nothing (a
 * pre-v6.4.3-38 client, fall back to the local conversation timer), `0` = the sender explicitly said OFF (never expires,
 * never fall back), `> 0` = the sender's timer. `MessageServiceImpl.markAsRead` branches on exactly that distinction, so
 * a storage layer that flattened `0` into `null` would re-freeze an explicitly-OFF message at the RECIPIENT's timer at
 * read time and silently delete a message the sender said to keep — the whole defect, reintroduced one layer down and
 * invisible to every test that only exercises the receive path.
 *
 * Both halves of the round trip are pinned here against REAL production code:
 * the write via [AbstractMessageModelFactory.buildContentValues], the read via the real [CursorHelper] over a mocked
 * `Cursor` (which is where the `null`-vs-`0` decision is actually made).
 *
 * Design: `.claude/tasks/disappearing-per-message-timer-metadata.md`.
 */
class DisappearingTimerColumnRoundTripTest {

    private val timerColumn = AbstractMessageModel.COLUMN_DISAPPEARING_TIMER_SECONDS

    // ---- WRITE: buildContentValues must emit the value, including a 0, and must emit null as null ----

    @Test
    fun `the production write path emits a stored 0 as 0, not as null and not as absent`() {
        val written = captureWriteSet(disappearingTimerSeconds = 0)

        assertTrue(
            timerColumn in written,
            "the write path must emit `$timerColumn` at all; dropping the column would leave the previous value in the row",
        )
        assertEquals(
            0,
            written[timerColumn],
            "a model holding an explicit sender OFF must be written as 0 — writing null would make it indistinguishable " +
                "from 'the sender advertised nothing' and re-open the fallback at read time",
        )
    }

    @Test
    fun `the production write path emits a null timer as null`() {
        val written = captureWriteSet(disappearingTimerSeconds = null)

        assertTrue(timerColumn in written, "the write path must emit `$timerColumn` even when it is null")
        assertNull(
            written[timerColumn],
            "'the sender advertised nothing' must stay null in the database, so the read-time fallback still applies",
        )
    }

    @Test
    fun `the production write path emits a positive timer unchanged`() {
        assertEquals(
            30,
            captureWriteSet(disappearingTimerSeconds = 30)[timerColumn],
            "a 30s frozen timer must be written as 30",
        )
    }

    // ---- READ: CursorHelper must distinguish a stored 0 from SQL NULL ----

    @Test
    fun `reading a stored 0 yields 0`() {
        assertEquals(
            0,
            readTimerColumn(storedValue = 0L, isNull = false),
            "a non-NULL 0 in the database must read back as Integer 0, not as null",
        )
    }

    @Test
    fun `reading a stored 30 yields 30`() {
        assertEquals(
            30,
            readTimerColumn(storedValue = 30L, isNull = false),
            "a non-NULL 30 in the database must read back as Integer 30",
        )
    }

    @Test
    fun `reading a SQL NULL yields null`() {
        assertNull(
            readTimerColumn(storedValue = 0L, isNull = true),
            "a SQL NULL must read back as null — this is the branch that keeps 'advertised nothing' distinguishable",
        )
    }

    @Test
    fun `a full write-then-read round trip preserves an explicit sender OFF`() {
        val written = captureWriteSet(disappearingTimerSeconds = 0)[timerColumn] as Int
        val readBack = readTimerColumn(storedValue = written.toLong(), isNull = false)

        assertEquals(
            0,
            readBack,
            "an explicit sender OFF must survive the write and the read as 0; if this ever returns null, markAsRead will " +
                "re-freeze such messages at the recipient's own timer and the wave is defeated",
        )
    }

    /**
     * Every `(column, value)` the REAL [AbstractMessageModelFactory.buildContentValues] emits for a message model holding
     * [disappearingTimerSeconds], captured through `mockkConstructor(ContentValues)` — the same mechanism
     * [DistributionListMessageSchemaRegressionTest] uses.
     */
    private fun captureWriteSet(disappearingTimerSeconds: Int?): Map<String, Any?> {
        val factory = mockk<MessageModelFactory>(relaxed = true)
        every { factory.buildContentValues(any()) } answers { callOriginal() }

        val captured = linkedMapOf<String, Any?>()
        mockkConstructor(ContentValues::class)
        try {
            every { anyConstructed<ContentValues>().put(any<String>(), anyNullable<String>()) } answers {
                captured[firstArg()] = secondArg<String?>()
            }
            every { anyConstructed<ContentValues>().put(any<String>(), anyNullable<Int>()) } answers {
                captured[firstArg()] = secondArg<Int?>()
            }
            every { anyConstructed<ContentValues>().put(any<String>(), anyNullable<Long>()) } answers {
                captured[firstArg()] = secondArg<Long?>()
            }
            every { anyConstructed<ContentValues>().put(any<String>(), anyNullable<Boolean>()) } answers {
                captured[firstArg()] = secondArg<Boolean?>()
            }

            val model = MessageModel().apply {
                uid = "uid-1"
                apiMessageId = "0011223344556677"
                identity = "AAAAAAAA"
                isOutbox = false
                type = MessageType.TEXT
                body = "hello"
                isRead = false
                isSaved = true
                state = MessageState.SENT
                postedAt = Date(1000L)
                createdAt = Date(900L)
                modifiedAt = Date(1100L)
                isStatusMessage = false
                messageContentsType = 1
                messageFlags = 0
                expiresAt = 1111L
                expireStartedAt = 2222L
                this.disappearingTimerSeconds = disappearingTimerSeconds
            }
            factory.buildContentValues(model)
        } finally {
            unmockkConstructor(ContentValues::class)
        }
        return captured
    }

    /**
     * The REAL read-side mapping from `AbstractMessageModelFactory.convert`, driven through the REAL [CursorHelper] over a
     * mocked `Cursor`.
     *
     * The two-line expression is mirrored rather than invoked, because `convert` is a package-private method that reads
     * two dozen other columns; what matters — and what is exercised for real here — is [CursorHelper]'s `null`-vs-value
     * decision, which is the only place the tri-state can be lost on the way in.
     */
    private fun readTimerColumn(storedValue: Long, isNull: Boolean): Int? {
        val columnIndex = 7
        val cursor = mockk<Cursor> {
            every { columnNames } returns Array(columnIndex + 1) { index -> if (index == columnIndex) timerColumn else "" }
            every { getColumnIndex(timerColumn) } returns columnIndex
            every { this@mockk.isNull(columnIndex) } returns isNull
            every { getLong(columnIndex) } returns storedValue
        }
        val cursorHelper = CursorHelper(cursor, ColumnIndexCache())
        val raw: Long? = cursorHelper.getLong(timerColumn)
        return if (raw != null) raw.toInt() else null
    }
}
