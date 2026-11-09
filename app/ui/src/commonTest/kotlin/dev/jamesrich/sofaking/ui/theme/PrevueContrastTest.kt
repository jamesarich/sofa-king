package dev.jamesrich.sofaking.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

class PrevueContrastTest {
    private val scheme = SofaKingPrevueColors

    private fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color) =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun pairs(s: ColorScheme): Map<String, Pair<Color, Color>> = mapOf(
        "onBackground on background" to (s.onBackground to s.background),
        "onSurface on surface" to (s.onSurface to s.surface),
        "onSurface on surfaceVariant" to (s.onSurface to s.surfaceVariant),
        "onSurface on surfaceContainer" to (s.onSurface to s.surfaceContainer),
        "onSurface on surfaceContainerHigh" to (s.onSurface to s.surfaceContainerHigh),
        "onSurface on surfaceContainerHighest" to (s.onSurface to s.surfaceContainerHighest),
        "onSurface on primaryContainer (airing cell)" to (s.onSurface to s.primaryContainer),
        "onSurfaceVariant on surface" to (s.onSurfaceVariant to s.surface),
        "onSurfaceVariant on surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
        "onSurfaceVariant on surfaceContainerHighest" to (s.onSurfaceVariant to s.surfaceContainerHighest),
        "onPrimary on primary (focused cell)" to (s.onPrimary to s.primary),
        "onPrimaryContainer on primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "onSecondary on secondary" to (s.onSecondary to s.secondary),
        "onSecondaryContainer on secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
        "onTertiary on tertiary" to (s.onTertiary to s.tertiary),
        "onTertiaryContainer on tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
        "onError on error" to (s.onError to s.error),
        "onErrorContainer on errorContainer" to (s.onErrorContainer to s.errorContainer),
        "inverseOnSurface on inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
        "primary on surface (the now line and links)" to (s.primary to s.surface),
        "secondary on surface" to (s.secondary to s.surface),
        "error on surface" to (s.error to s.surface),
    )

    @Test
    fun `every text role in the Prevue look reads at 4_5 to 1 on its container`() {
        for ((name, pair) in pairs(scheme)) {
            val ratio = contrast(pair.first, pair.second)
            assertTrue(ratio >= 4.5, "$name is $ratio:1")
        }
    }

    @Test
    fun `the Prevue outline reads at 3 to 1 against its surfaces`() {
        for (surface in listOf(scheme.surface, scheme.surfaceVariant, scheme.surfaceContainer)) {
            assertTrue(contrast(scheme.outline, surface) >= 3.0)
        }
    }
}
