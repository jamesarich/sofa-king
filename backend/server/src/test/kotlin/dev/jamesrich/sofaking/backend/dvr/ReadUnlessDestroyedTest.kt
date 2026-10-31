package dev.jamesrich.sofaking.backend.dvr

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReadUnlessDestroyedTest {
    private val closed = object : InputStream() {
        override fun read(): Int = throw IOException("Stream closed")
    }

    @Test fun `a read cut short after the process was destroyed is the end of its output`() {
        assertEquals(-1, closed.readUnlessDestroyed(ByteArray(8), AtomicBoolean(true)))
    }

    @Test fun `a read that fails while the process lives is a failure`() {
        assertFailsWith<IOException> { closed.readUnlessDestroyed(ByteArray(8), AtomicBoolean(false)) }
    }
}
