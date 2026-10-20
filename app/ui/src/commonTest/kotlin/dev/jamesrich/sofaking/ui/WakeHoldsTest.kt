package dev.jamesrich.sofaking.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class WakeHoldsTest {
    private val events = mutableListOf<String>()
    private val holds = WakeHolds(acquire = { events += "acquire" }, release = { events += "release" })

    @Test
    fun `the display is held from the first holder until the last lets go`() {
        val leaving = holds.hold()
        val arriving = holds.hold()
        leaving.close()
        assertEquals(listOf("acquire"), events)
        arriving.close()
        assertEquals(listOf("acquire", "release"), events)
    }

    @Test
    fun `closing a hold twice releases once`() {
        val first = holds.hold()
        val second = holds.hold()
        first.close()
        first.close()
        assertEquals(listOf("acquire"), events)
        second.close()
        holds.hold()
        assertEquals(listOf("acquire", "release", "acquire"), events)
    }
}
