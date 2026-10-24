package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.SofaKingException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VodConverterWaitTest {
    private val state = VodPlayerState()
    private val busy = SofaKingException(503, Wire.ErrorCode.CAPACITY_EXHAUSTED, "Too many files are being converted")

    @Test
    fun `a busy server is asked again every 5 seconds until a converter is free`() = runTest {
        var tries = 0
        val opened = state.waitForConverter {
            tries++
            if (tries < 3) throw busy
            assertTrue(state.waitingForConverter, "the player says it is waiting")
            "stream"
        }
        assertEquals("stream", opened)
        assertEquals(3, tries)
        assertEquals(2 * CONVERTER_RETRY_MS, currentTime)
        assertFalse(state.waitingForConverter)
    }

    @Test
    fun `after 20 seconds of busy answers the failure shows`() = runTest {
        var tries = 0
        val thrown = assertFailsWith<SofaKingException> {
            state.waitForConverter<String> {
                tries++
                throw busy
            }
        }
        assertEquals(Wire.ErrorCode.CAPACITY_EXHAUSTED, thrown.code)
        assertEquals(5, tries)
        assertEquals(CONVERTER_WAIT_MS, currentTime)
        assertFalse(state.waitingForConverter)
    }

    @Test
    fun `any other failure shows at once`() = runTest {
        var tries = 0
        assertFailsWith<SofaKingException> {
            state.waitForConverter<String> {
                tries++
                throw SofaKingException(503, "TRANSCODER_UNAVAILABLE", "No converter")
            }
        }
        assertEquals(1, tries)
        assertEquals(0, currentTime)
    }
}
