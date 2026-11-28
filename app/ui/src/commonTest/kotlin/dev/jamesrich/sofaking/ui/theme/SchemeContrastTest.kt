package dev.jamesrich.sofaking.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** WCAG 2.1 contrast of every role pair the app draws text with, in each of the three schemes. */
class SchemeContrastTest {
    private val schemes = mapOf(
        "dark" to SofaKingDarkColors,
        "light" to SofaKingLightColors,
        "Prevue" to SofaKingPrevueColors,
    )

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
        "onSurface on surfaceContainerLow" to (s.onSurface to s.surfaceContainerLow),
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
        "inverseOnSurface on inverseSurface (the focused button)" to (s.inverseOnSurface to s.inverseSurface),
        "inversePrimary on inverseSurface" to (s.inversePrimary to s.inverseSurface),
        "primary on surface (the now line and links)" to (s.primary to s.surface),
        "secondary on surface" to (s.secondary to s.surface),
        "tertiary on surface" to (s.tertiary to s.surface),
        "error on surface" to (s.error to s.surface),
        "onPrimaryFixed on primaryFixed" to (s.onPrimaryFixed to s.primaryFixed),
        "onPrimaryFixed on primaryFixedDim" to (s.onPrimaryFixed to s.primaryFixedDim),
        "onPrimaryFixedVariant on primaryFixed" to (s.onPrimaryFixedVariant to s.primaryFixed),
        "onPrimaryFixedVariant on primaryFixedDim" to (s.onPrimaryFixedVariant to s.primaryFixedDim),
        "onSecondaryFixed on secondaryFixed" to (s.onSecondaryFixed to s.secondaryFixed),
        "onSecondaryFixed on secondaryFixedDim" to (s.onSecondaryFixed to s.secondaryFixedDim),
        "onSecondaryFixedVariant on secondaryFixed" to (s.onSecondaryFixedVariant to s.secondaryFixed),
        "onSecondaryFixedVariant on secondaryFixedDim" to (s.onSecondaryFixedVariant to s.secondaryFixedDim),
        "onTertiaryFixed on tertiaryFixed" to (s.onTertiaryFixed to s.tertiaryFixed),
        "onTertiaryFixed on tertiaryFixedDim" to (s.onTertiaryFixed to s.tertiaryFixedDim),
        "onTertiaryFixedVariant on tertiaryFixed" to (s.onTertiaryFixedVariant to s.tertiaryFixed),
        "onTertiaryFixedVariant on tertiaryFixedDim" to (s.onTertiaryFixedVariant to s.tertiaryFixedDim),
    )

    @Test
    fun `every text role reads at 4_5 to 1 on its container in each scheme`() {
        for ((scheme, colors) in schemes) {
            for ((name, pair) in pairs(colors)) {
                val ratio = contrast(pair.first, pair.second)
                assertTrue(ratio >= 4.5, "$scheme: $name is $ratio:1")
            }
        }
    }

    @Test
    fun `the outline reads at 3 to 1 against the surfaces controls sit on in each scheme`() {
        for ((scheme, colors) in schemes) {
            for (surface in listOf(colors.surface, colors.surfaceVariant, colors.surfaceContainer)) {
                val ratio = contrast(colors.outline, surface)
                assertTrue(ratio >= 3.0, "$scheme: outline is $ratio:1")
            }
        }
    }

    @Test
    fun `text on the overlay scrim reads at 4_5 to 1 over a white picture and over a black one`() {
        val inks = mapOf(
            "onSurface" to SofaKingDarkColors.onSurface,
            "onSurfaceVariant" to SofaKingDarkColors.onSurfaceVariant,
        )
        for (scrim in listOf(SofaOverlayScrim, SofaOverlayScrimDense)) {
            for (picture in listOf(Color.White, Color.Black)) {
                val panel = over(scrim, picture)
                for ((name, ink) in inks) {
                    val ratio = contrast(ink, panel)
                    assertTrue(ratio >= 4.5, "$name over a scrim of ${scrim.alpha} is $ratio:1")
                }
            }
        }
    }

    @Test
    fun `no scheme leaves a role at Material's baseline purple`() {
        for ((scheme, colors) in schemes) {
            for (base in listOf(darkColorScheme(), lightColorScheme())) {
                assertNotEquals(base.primaryFixed, colors.primaryFixed, "$scheme: primaryFixed")
                assertNotEquals(base.secondaryFixed, colors.secondaryFixed, "$scheme: secondaryFixed")
                assertNotEquals(base.tertiaryFixed, colors.tertiaryFixed, "$scheme: tertiaryFixed")
                assertNotEquals(base.primary, colors.primary, "$scheme: primary")
            }
        }
    }

    /** [top], which is see-through, drawn over the opaque [under]. */
    private fun over(top: Color, under: Color): Color = Color(
        red = top.red * top.alpha + under.red * (1 - top.alpha),
        green = top.green * top.alpha + under.green * (1 - top.alpha),
        blue = top.blue * top.alpha + under.blue * (1 - top.alpha),
    )
}
