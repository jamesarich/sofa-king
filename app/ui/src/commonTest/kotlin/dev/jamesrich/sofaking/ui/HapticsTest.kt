package dev.jamesrich.sofaking.ui

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HapticsTest {
    private class Recorder : HapticFeedback {
        val performed = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            performed += hapticFeedbackType
        }
    }

    @Test
    fun touchPerformsEachKindOfFeedback() {
        val recorder = Recorder()
        val haptics = Haptics(recorder, enabled = true)
        haptics.longPress()
        haptics.release()
        haptics.step()
        assertEquals(
            listOf(HapticFeedbackType.LongPress, HapticFeedbackType.GestureEnd, HapticFeedbackType.SegmentTick),
            recorder.performed,
        )
    }

    @Test
    fun televisionPerformsNoFeedback() {
        val recorder = Recorder()
        val haptics = Haptics(recorder, enabled = false)
        haptics.longPress()
        haptics.release()
        haptics.step()
        assertTrue(recorder.performed.isEmpty())
    }
}
