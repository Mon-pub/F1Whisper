package ch.threema.domain.protocol.blob

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import okio.Buffer

class BlobLoaderReadKnownLengthTest {
    private fun read(source: Buffer, length: Long, isCancelled: () -> Boolean = { false }, progress: MutableList<Int> = mutableListOf()) =
        BlobLoader.readKnownLengthBlob(source, length, isCancelled, onProgress = { progress.add(it) })

    @Test
    fun `reads a blob larger than one chunk exactly`() {
        val content = ByteArray(20_000) { (it % 251).toByte() }
        val progress = mutableListOf<Int>()

        val blob = read(Buffer().write(content), content.size.toLong(), progress = progress)

        assertContentEquals(content, blob)
        assertEquals(100, progress.last())
    }

    @Test
    fun `an empty blob of known length zero is empty`() {
        assertContentEquals(ByteArray(0), read(Buffer(), 0))
    }

    @Test
    fun `fewer bytes than announced fail`() {
        assertFailsWith<IOException> { read(Buffer().write(ByteArray(10)), 11) }
    }

    @Test
    fun `more bytes than announced fail`() {
        assertFailsWith<IOException> { read(Buffer().write(ByteArray(12)), 11) }
    }

    @Test
    fun `an impossible length fails`() {
        assertFailsWith<IOException> { read(Buffer(), Int.MAX_VALUE.toLong() + 1) }
        assertFailsWith<IOException> { read(Buffer(), -2) }
    }

    @Test
    fun `cancellation returns null`() {
        var reads = 0
        val blob = read(Buffer().write(ByteArray(50_000)), 50_000, isCancelled = { reads++ > 0 })

        assertNull(blob)
    }
}
