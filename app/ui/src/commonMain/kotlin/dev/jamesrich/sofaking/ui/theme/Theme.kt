package dev.jamesrich.sofaking.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.jamesrich.sofaking.ui.LocalWindowLayout
import dev.jamesrich.sofaking.ui.windowLayoutFor
import androidx.compose.ui.unit.sp

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

/** Material 3's type roles at the sizes the TV layout was tuned with, read from the sofa. */
private val SofaKingTypography: Typography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontSize = 36.sp, lineHeight = 44.sp),
        headlineSmall = base.headlineSmall.copy(fontSize = 32.sp, lineHeight = 40.sp),
        titleLarge = base.titleLarge.copy(fontSize = 26.sp, lineHeight = 34.sp),
        titleMedium = base.titleMedium.copy(fontSize = 22.sp, lineHeight = 30.sp),
        titleSmall = base.titleSmall.copy(fontSize = 20.sp, lineHeight = 28.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 22.sp, lineHeight = 30.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 20.sp, lineHeight = 28.sp),
        bodySmall = base.bodySmall.copy(fontSize = 18.sp, lineHeight = 26.sp),
        labelLarge = base.labelLarge.copy(fontSize = 20.sp, lineHeight = 26.sp),
        labelMedium = base.labelMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
    )
}

/** Material 3's own type scale, for a phone's or a small window's viewing distance. */
private val CompactTypography: Typography = Typography()

/**
 * Sofa King's dark Material 3 Expressive theme with expressive motion, the same on every platform. It measures
 * the space it is given into [LocalWindowLayout]; a compact window takes Material's handheld type scale instead
 * of the TV-sized one.
 */
@Composable
fun SofaKingTheme(content: @Composable () -> Unit) {
    BoxWithConstraints {
        val layout = windowLayoutFor(maxWidth, maxHeight)
        CompositionLocalProvider(LocalWindowLayout provides layout) {
            MaterialExpressiveTheme(
                colorScheme = SofaKingColors,
                typography = if (layout.isCompact) CompactTypography else SofaKingTypography,
                motionScheme = MotionScheme.expressive(),
                content = content,
            )
        }
    }
}
