package ch.threema.app.utils

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UnknownSizeStreamReadTest {
    private fun content(size: Int) = ByteArray(size) { (it % 251 + 1).toByte() }

    /** A stream that returns at most a few bytes per read, like a pipe. */
    private fun trickling(bytes: ByteArray): InputStream = object : InputStream() {
        private val source = ByteArrayInputStream(bytes)
        override fun read(): Int = source.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, minOf(len, 7))
    }

    @Test
    fun `small content gets the header room in front and nothing else`() {
        val data = content(100)

        val result = ByteArrayInputStream(data).readUnknownSizeWithHeaderRoom(headerBytes = 16, maxContentBytes = 1000, chunkBytes = 32)!!

        assertEquals(116, result.size)
        assertContentEquals(ByteArray(16), result.copyOfRange(0, 16))
        assertContentEquals(data, result.copyOfRange(16, 116))
    }

    @Test
    fun `content of exactly the limit is accepted across chunk boundaries`() {
        val data = content(96)

        val result = trickling(data).readUnknownSizeWithHeaderRoom(headerBytes = 16, maxContentBytes = 96, chunkBytes = 32)!!

        assertContentEquals(data, result.copyOfRange(16, result.size))
    }

    @Test
    fun `one byte over the limit is refused`() {
        assertNull(trickling(content(97)).readUnknownSizeWithHeaderRoom(headerBytes = 16, maxContentBytes = 96, chunkBytes = 32))
    }

    @Test
    fun `content of exactly a whole number of chunks has no trailing bytes`() {
        val data = content(64)

        val result = trickling(data).readUnknownSizeWithHeaderRoom(headerBytes = 16, maxContentBytes = 96, chunkBytes = 32)!!

        assertEquals(80, result.size)
        assertContentEquals(data, result.copyOfRange(16, 80))
    }

    @Test
    fun `a zero limit accepts empty content and refuses one byte`() {
        assertContentEquals(ByteArray(16), ByteArrayInputStream(ByteArray(0)).readUnknownSizeWithHeaderRoom(16, 0, 32))
        assertNull(ByteArrayInputStream(ByteArray(1)).readUnknownSizeWithHeaderRoom(16, 0, 32))
    }

    @Test
    fun `a read error is passed on`() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("broken")
        }

        assertFailsWith<IOException> { failing.readUnknownSizeWithHeaderRoom(16, 96, 32) }
    }

    @Test
    fun `empty content is only the header`() {
        assertContentEquals(ByteArray(16), ByteArrayInputStream(ByteArray(0)).readUnknownSizeWithHeaderRoom(16, 96, 32))
    }
}
