package dev.jamesrich.sofaking.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelNumberInputTest {
    @Test
    fun `commit hands over the digits and clears them first`() {
        val input = ChannelNumberInput()
        var committed: String? = null
        input.onCommit = {
            committed = it.digits
            assertFalse(input.hasDigits)
        }
        input.append(1)
        input.append(2)
        input.commit()
        assertEquals("12", committed)
    }

    @Test
    fun `commit with no digits does nothing`() {
        val input = ChannelNumberInput()
        var calls = 0
        input.onCommit = { calls++ }
        input.commit()
        assertEquals(0, calls)
    }

    @Test
    fun `the same miss twice counts twice so its notice restarts`() {
        val input = ChannelNumberInput()
        input.missed("99")
        input.missed("99")
        assertEquals("99", input.miss)
        assertEquals(2, input.misses)
        input.clearMiss()
        assertNull(input.miss)
        assertTrue(input.misses > 0)
    }
}
