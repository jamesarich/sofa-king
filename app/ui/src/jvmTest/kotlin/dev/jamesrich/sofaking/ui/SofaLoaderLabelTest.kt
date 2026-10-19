package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SofaLoaderLabelTest {
    private fun sizeIn(widthDp: Int): IntSize {
        var size = IntSize.Zero
        ImageComposeScene(width = 1920, height = 200, density = Density(2f)) {
            SofaKingTheme {
                Box(Modifier.width(widthDp.dp)) {
                    SofaLoaderLabel("Refreshing…", Modifier.onSizeChanged { size = it })
                }
            }
        }.use { it.render() }
        return size
    }

    @Test
    fun showsOnlyTheLoaderWhereTheLabelWouldWrap() {
        val narrow = sizeIn(60)
        assertEquals(56, narrow.width)
        assertEquals(56, narrow.height)
    }

    @Test
    fun keepsTheLabelOnOneLineWhereItFits() {
        val wide = sizeIn(400)
        assertTrue(wide.width > 56 + 16, "label shown beside the loader: $wide")
        assertEquals(56, wide.height)
    }
}
