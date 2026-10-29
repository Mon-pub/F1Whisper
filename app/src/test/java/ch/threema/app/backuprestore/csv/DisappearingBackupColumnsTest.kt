package ch.threema.app.backuprestore.csv

import ch.threema.app.utils.CSVReader
import ch.threema.app.utils.CSVRow
import ch.threema.app.utils.CSVWriter
import java.io.StringReader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1Whisper: the CSV contract that carries a message's disappearing policy through a backup.
 *
 * Before these columns existed, a backup/restore round trip made every in-flight disappearing
 * message **permanent**: the restored row had no deadline, and both the expiry sweep and the alarm
 * select on a non-null deadline, so the engine could not see it again at all. A message whose sender
 * had asked for it to be deleted came back and stayed.
 *
 * The reader half of that fix is `RestoreService.optionalInteger` / `optionalLong`, which are
 * private to an Android `Service` and so not reachable from a JVM test. What IS reachable, and what
 * they are entirely built on, is the [CSVRow] contract they rely on - and every one of the three
 * properties asserted below is a way that contract could silently change and take the restore with
 * it. [aPreV38BackupHasNoSuchColumns] in particular reproduces the exact shape of an older backup.
 */
class DisappearingBackupColumnsTest {

    private val newHeader = arrayOf(
        Tags.TAG_MESSAGE_API_MESSAGE_ID,
        Tags.TAG_MESSAGE_IS_READ,
        Tags.TAG_MESSAGE_DISAPPEARING_TIMER,
        Tags.TAG_MESSAGE_EXPIRE_STARTED_AT,
        Tags.TAG_MESSAGE_EXPIRES_AT,
    )

    /** A backup written before v6.4.3-38: the same rows, without the three columns. */
    private val legacyHeader = arrayOf(
        Tags.TAG_MESSAGE_API_MESSAGE_ID,
        Tags.TAG_MESSAGE_IS_READ,
    )

    private fun roundTrip(header: Array<String>, write: (CSVRow) -> Unit): CSVRow {
        val out = StringWriter()
        CSVWriter(out, header).use { csv ->
            val row = csv.createRow()
            write(row)
            row.write()
        }
        val reader = CSVReader(StringReader(out.toString()), true)
        return requireNonNull(reader.readNextRow())
    }

    private fun <T : Any> requireNonNull(value: T?): T = value ?: error("expected a row")

    @Test
    fun aCountdownSurvivesTheRoundTrip() {
        val startedAt = 1_700_000_000_000L
        val expiresAt = startedAt + 300_000L
        val row = roundTrip(newHeader) {
            it.write(Tags.TAG_MESSAGE_API_MESSAGE_ID, "0011223344556677")
            it.write(Tags.TAG_MESSAGE_IS_READ, true)
            it.write(Tags.TAG_MESSAGE_DISAPPEARING_TIMER, 300 as Any?)
            it.write(Tags.TAG_MESSAGE_EXPIRE_STARTED_AT, startedAt as Any?)
            it.write(Tags.TAG_MESSAGE_EXPIRES_AT, expiresAt as Any?)
        }

        assertEquals(300, row.getInteger(Tags.TAG_MESSAGE_DISAPPEARING_TIMER))
        assertEquals(startedAt, row.getLong(Tags.TAG_MESSAGE_EXPIRE_STARTED_AT))
        assertEquals(expiresAt, row.getLong(Tags.TAG_MESSAGE_EXPIRES_AT))
    }

    @Test
    fun anExplicitSenderOffSurvivesAsZeroAndNotAsAbsent() {
        // The tri-state has to survive backup too: `0` is the sender's explicit "never expire", and
        // it must not come back as "the sender said nothing", which would fall back to the
        // recipient's own conversation timer and start deleting messages the sender said to keep.
        val row = roundTrip(newHeader) {
            it.write(Tags.TAG_MESSAGE_API_MESSAGE_ID, "0011223344556677")
            it.write(Tags.TAG_MESSAGE_IS_READ, true)
            it.write(Tags.TAG_MESSAGE_DISAPPEARING_TIMER, 0 as Any?)
            it.write(Tags.TAG_MESSAGE_EXPIRE_STARTED_AT, null as Any?)
            it.write(Tags.TAG_MESSAGE_EXPIRES_AT, null as Any?)
        }

        assertTrue(row.hasField(Tags.TAG_MESSAGE_DISAPPEARING_TIMER))
        assertEquals(0, row.getInteger(Tags.TAG_MESSAGE_DISAPPEARING_TIMER))
    }

    @Test
    fun aNullCountdownIsWrittenAsAnEmptyCellAndNotAsTheStringNull() {
        val row = roundTrip(newHeader) {
            it.write(Tags.TAG_MESSAGE_API_MESSAGE_ID, "0011223344556677")
            it.write(Tags.TAG_MESSAGE_IS_READ, false)
            it.write(Tags.TAG_MESSAGE_DISAPPEARING_TIMER, null as Any?)
            it.write(Tags.TAG_MESSAGE_EXPIRE_STARTED_AT, null as Any?)
            it.write(Tags.TAG_MESSAGE_EXPIRES_AT, null as Any?)
        }

        assertEquals("", row.getString(Tags.TAG_MESSAGE_EXPIRE_STARTED_AT))
        // And the typed getters are @NonNull, which is precisely why the restore cannot call them
        // directly for an optional column. If this ever stopped throwing, `optionalLong`'s empty-cell
        // guard would look redundant and be a tempting deletion.
        assertFailsWith<NumberFormatException> { row.getLong(Tags.TAG_MESSAGE_EXPIRE_STARTED_AT) }
    }

    @Test
    fun aPreV38BackupHasNoSuchColumns() {
        val row = roundTrip(legacyHeader) {
            it.write(Tags.TAG_MESSAGE_API_MESSAGE_ID, "0011223344556677")
            it.write(Tags.TAG_MESSAGE_IS_READ, true)
        }

        // hasField is the whole backward-compatibility mechanism: absent means "written before the
        // columns existed", and the message restores with no countdown, exactly as it did before.
        assertFalse(row.hasField(Tags.TAG_MESSAGE_DISAPPEARING_TIMER))
        assertFalse(row.hasField(Tags.TAG_MESSAGE_EXPIRE_STARTED_AT))
        assertFalse(row.hasField(Tags.TAG_MESSAGE_EXPIRES_AT))
        assertEquals(-1, row.getValuePosition(Tags.TAG_MESSAGE_EXPIRES_AT))
        // The pre-existing columns still read correctly, i.e. a legacy backup is not disturbed.
        assertEquals("0011223344556677", row.getString(Tags.TAG_MESSAGE_API_MESSAGE_ID))
    }

    @Test
    fun extraColumnsAreAddressedByNameSoAnOlderReaderIgnoresThem() {
        // Why RestoreSettings.CURRENT_VERSION is deliberately NOT bumped for these columns: a bump
        // makes isUnsupportedVersion() reject the whole backup on every already-shipped build. The
        // columns are safe to append instead, because a reader resolves them by name against the
        // file's own header and simply never looks up one it does not know.
        val out = StringWriter()
        CSVWriter(out, newHeader).use { csv ->
            val row = csv.createRow()
            row.write(Tags.TAG_MESSAGE_API_MESSAGE_ID, "0011223344556677")
            row.write(Tags.TAG_MESSAGE_IS_READ, true)
            row.write(Tags.TAG_MESSAGE_DISAPPEARING_TIMER, 300 as Any?)
            row.write(Tags.TAG_MESSAGE_EXPIRE_STARTED_AT, 1L as Any?)
            row.write(Tags.TAG_MESSAGE_EXPIRES_AT, 2L as Any?)
            row.write()
        }

        // An older reader knows only the legacy header, and the columns it does know are unmoved
        // because the new ones were appended rather than inserted.
        val reader = CSVReader(StringReader(out.toString()), true)
        val row = requireNonNull(reader.readNextRow())
        assertEquals(0, row.getValuePosition(Tags.TAG_MESSAGE_API_MESSAGE_ID))
        assertEquals(1, row.getValuePosition(Tags.TAG_MESSAGE_IS_READ))
        assertEquals("0011223344556677", row.getString(Tags.TAG_MESSAGE_API_MESSAGE_ID))
        assertTrue(row.getBoolean(Tags.TAG_MESSAGE_IS_READ))
    }

    @Test
    fun theContactAndGroupTimerColumnsAreOptionalTheSameWay() {
        val header = arrayOf(Tags.TAG_CONTACT_IDENTITY, Tags.TAG_CONTACT_DISAPPEARING_TIMER)
        val row = roundTrip(header) {
            it.write(Tags.TAG_CONTACT_IDENTITY, "ECHOECHO")
            it.write(Tags.TAG_CONTACT_DISAPPEARING_TIMER, null as Any?)
        }
        assertTrue(row.hasField(Tags.TAG_CONTACT_DISAPPEARING_TIMER))
        assertEquals("", row.getString(Tags.TAG_CONTACT_DISAPPEARING_TIMER))

        val legacy = roundTrip(arrayOf(Tags.TAG_CONTACT_IDENTITY)) {
            it.write(Tags.TAG_CONTACT_IDENTITY, "ECHOECHO")
        }
        assertFalse(legacy.hasField(Tags.TAG_CONTACT_DISAPPEARING_TIMER))
        assertNull(legacy.getString(Tags.TAG_CONTACT_IDENTITY).takeIf { it.isEmpty() })
    }
}
