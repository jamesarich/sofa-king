package dev.jamesrich.sofaking.ui.visualizer

import kotlin.test.Test
import kotlin.test.assertContentEquals

class BgraBytesTest {
    @Test
    fun `an opaque pixel is written blue first`() {
        val orange = 0xFFFF8000.toInt()
        val bytes = bgraBytes(intArrayOf(orange, 0xFF0000FF.toInt()))
        assertContentEquals(
            byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0xFF.toByte()),
            bytes,
        )
    }
}
