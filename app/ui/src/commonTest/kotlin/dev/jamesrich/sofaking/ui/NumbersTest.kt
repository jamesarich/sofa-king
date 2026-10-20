package dev.jamesrich.sofaking.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class NumbersTest {
    @Test
    fun `channel numbers drop a whole number's decimal`() {
        assertEquals("7", 7.0.formatNumber())
        assertEquals("5.1", 5.1.formatNumber())
    }

    @Test
    fun `one decimal rounds to the nearest tenth and can drop a whole result's zero`() {
        assertEquals("59.9", oneDecimal(59.94))
        assertEquals("30.0", oneDecimal(29.97))
        assertEquals("4.2", oneDecimal(4.2))
        assertEquals("3", oneDecimal(3.0, trimZero = true))
        assertEquals("2.4", oneDecimal(2.44, trimZero = true))
        assertEquals("-0.5", oneDecimal(-0.5))
        assertEquals("0.0", oneDecimal(0.0))
    }

    @Test
    fun `sizes read in the largest unit with a decimal under ten`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("740 MB", formatBytes(740_000_000))
        assertEquals("2.4 GB", formatBytes(2_440_000_000))
        assertEquals("3 GB", formatBytes(3_000_000_000))
        assertEquals("12 TB", formatBytes(12_000_000_000_000))
    }
}
