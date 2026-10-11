package dev.jamesrich.sofaking.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * Surface and background share one color so the TV Surface never differs from the window background.
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
    inverseSurface = SofaDusk,
    inverseOnSurface = SofaPlum,
    error = SofaError,
    onError = SofaPlum,
    errorContainer = SofaErrorDeep,
    onErrorContainer = SofaErrorSoft,
    border = SofaOutline,
    scrim = SofaScrim,
)

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

/** Sofa King's dark, TV-sized theme. Use in place of a bare tv-material [MaterialTheme]. */
@Composable
fun SofaKingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SofaKingColors,
        typography = SofaKingTypography,
        content = content,
    )
}
