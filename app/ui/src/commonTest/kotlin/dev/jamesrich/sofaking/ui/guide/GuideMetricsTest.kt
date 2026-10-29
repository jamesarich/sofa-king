package dev.jamesrich.sofaking.ui.guide

import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.GuideDensity
import dev.jamesrich.sofaking.ui.LayoutProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class GuideMetricsTest {
    private val phone = LayoutProfile(tv = false, compactWidth = true, compactHeight = false)

    @Test
    fun `a TV keeps its row heights at any font scale`() {
        assertEquals(ROW_HEIGHT, rowHeight(GuideDensity.COMFORTABLE, LayoutProfile.Tv, 2f))
        assertEquals(COMPACT_ROW_HEIGHT, rowHeight(GuideDensity.COMPACT, LayoutProfile.Tv, 1.5f))
    }

    @Test
    fun `a window off a TV grows its rows with large text`() {
        assertEquals(96.dp, rowHeight(GuideDensity.COMFORTABLE, phone, 2f))
        assertEquals(60.dp, rowHeight(GuideDensity.COMPACT, phone, 1.5f))
    }

    @Test
    fun `small text never shrinks a row`() {
        assertEquals(ROW_HEIGHT, rowHeight(GuideDensity.COMFORTABLE, phone, 0.85f))
        assertEquals(HEADER_HEIGHT, scaledForText(HEADER_HEIGHT, phone, 1f))
    }
}
