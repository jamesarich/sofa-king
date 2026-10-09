package dev.jamesrich.sofaking.ui.preview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTunerTest {
    private val a = "http://dispatcharr/proxy/ts/stream/a"
    private val b = "http://dispatcharr/proxy/ts/stream/b"

    @Test
    fun `first focus on a stream needs a tune`() {
        assertTrue(PreviewTuner().needsTune(a))
    }

    @Test
    fun `no focused stream never needs a tune`() {
        assertFalse(PreviewTuner().needsTune(null))
    }

    @Test
    fun `focus staying on the tuned stream does not need a tune`() {
        val tuner = PreviewTuner()
        tuner.markTuned(a)
        assertFalse(tuner.needsTune(a))
    }

    @Test
    fun `a different stream needs a tune`() {
        val tuner = PreviewTuner()
        tuner.markTuned(a)
        assertTrue(tuner.needsTune(b))
    }

    @Test
    fun `returning to the tuned stream after a detour does not need a tune`() {
        val tuner = PreviewTuner()
        tuner.markTuned(a)
        assertTrue(tuner.needsTune(b))
        assertFalse(tuner.needsTune(a))
    }
}
