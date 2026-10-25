package dev.jamesrich.sofaking.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Surface and background share one color so a surface never differs from the window background.
 * Cards and focused rows use [SofaPlumRaised] instead.
 */
private val SofaKingColors: ColorScheme = darkColorScheme(
    primary = SofaAmber,
    onPrimary = SofaPlum,
    primaryContainer = SofaAmberDeep,
    onPrimaryContainer = SofaAmberSoft,
    inversePrimary = SofaAmberDeep,
    secondary = SofaLavender,
    onSecondary = SofaPlum,
    secondaryContainer = SofaLavenderDeep,
    onSecondaryContainer = SofaLavenderSoft,
    tertiary = SofaLavender,
    onTertiary = SofaPlum,
    background = SofaPlum,
    onBackground = SofaDusk,
    surface = SofaPlum,
    onSurface = SofaDusk,
    surfaceVariant = SofaPlumRaised,
    onSurfaceVariant = SofaMauve,
    surfaceContainer = SofaPlumRaised,
    surfaceContainerHigh = SofaPlumRaised,
    inverseSurface = SofaDusk,
    inverseOnSurface = SofaPlum,
    error = SofaError,
    onError = SofaPlum,
    errorContainer = SofaErrorDeep,
    onErrorContainer = SofaErrorSoft,
    outline = SofaOutline,
    outlineVariant = SofaOutline,
    scrim = SofaScrim,
)

/**
 * Sofa King's dark Material 3 Expressive theme with expressive motion and Material's standard type scale; the same on
 * every platform.
 */
@Composable
fun SofaKingTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = SofaKingColors,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
