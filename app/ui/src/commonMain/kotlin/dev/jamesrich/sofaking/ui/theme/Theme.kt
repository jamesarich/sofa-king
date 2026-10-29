package dev.jamesrich.sofaking.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.jamesrich.sofaking.data.Appearance
import dev.jamesrich.sofaking.ui.rememberIsTelevision

/**
 * Surface and background share one color so a surface never differs from the window background.
 * Cards and focused rows use [SofaPlumRaised] instead.
 */
private val SofaKingDarkColors: ColorScheme = darkColorScheme(
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
    tertiaryContainer = SofaLavenderDeep,
    onTertiaryContainer = SofaLavenderSoft,
    background = SofaPlum,
    onBackground = SofaDusk,
    surface = SofaPlum,
    onSurface = SofaDusk,
    surfaceVariant = SofaPlumRaised,
    onSurfaceVariant = SofaMauve,
    surfaceTint = SofaAmber,
    surfaceDim = SofaPlum,
    surfaceBright = SofaPlumHighest,
    surfaceContainerLowest = SofaPlum,
    surfaceContainerLow = SofaPlumLow,
    surfaceContainer = SofaPlumRaised,
    surfaceContainerHigh = SofaPlumRaised,
    surfaceContainerHighest = SofaPlumHighest,
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

/** The same roles in daylight tones; Color.kt has the contrast of each pair. */
private val SofaKingLightColors: ColorScheme = lightColorScheme(
    primary = SofaAmberInk,
    onPrimary = SofaOnAccent,
    primaryContainer = SofaAmberPale,
    onPrimaryContainer = SofaAmberDark,
    inversePrimary = SofaAmber,
    secondary = SofaLavenderInk,
    onSecondary = SofaOnAccent,
    secondaryContainer = SofaLavenderPale,
    onSecondaryContainer = SofaLavenderDark,
    tertiary = SofaLavenderInk,
    onTertiary = SofaOnAccent,
    tertiaryContainer = SofaLavenderPale,
    onTertiaryContainer = SofaLavenderDark,
    background = SofaPaper,
    onBackground = SofaInk,
    surface = SofaPaper,
    onSurface = SofaInk,
    surfaceVariant = SofaPaperRaised,
    onSurfaceVariant = SofaInkMuted,
    surfaceTint = SofaAmberInk,
    surfaceDim = SofaPaperHighest,
    surfaceBright = SofaPaperWhite,
    surfaceContainerLowest = SofaPaperWhite,
    surfaceContainerLow = SofaPaperLow,
    surfaceContainer = SofaPaperRaised,
    surfaceContainerHigh = SofaPaperRaised,
    surfaceContainerHighest = SofaPaperHighest,
    inverseSurface = SofaInk,
    inverseOnSurface = SofaPaper,
    error = SofaErrorInk,
    onError = SofaOnAccent,
    errorContainer = SofaErrorPale,
    onErrorContainer = SofaErrorDark,
    outline = SofaInkOutline,
    outlineVariant = SofaInkOutline,
    scrim = SofaScrim,
)

/**
 * Whether the app draws dark: a TV always does, otherwise [appearance] decides, with SYSTEM following the platform's
 * dark mode ([systemDark]).
 */
internal fun useDarkTheme(appearance: Appearance, television: Boolean, systemDark: Boolean): Boolean = when {
    television -> true
    appearance == Appearance.DARK -> true
    appearance == Appearance.LIGHT -> false
    else -> systemDark
}

/** [useDarkTheme] for this device: whether it is a TV and what its dark mode is. */
@Composable
fun rememberDarkTheme(appearance: Appearance): Boolean =
    useDarkTheme(appearance, rememberIsTelevision(), isSystemInDarkTheme())

/**
 * Sofa King's Material 3 Expressive theme with expressive motion and Material's standard type scale, dark or light by
 * [useDarkTheme]; the default [appearance] is dark, so a preview or test draws as the TV does.
 */
@Composable
fun SofaKingTheme(appearance: Appearance = Appearance.DARK, content: @Composable () -> Unit) {
    val dark = rememberDarkTheme(appearance)
    MaterialExpressiveTheme(
        colorScheme = if (dark) SofaKingDarkColors else SofaKingLightColors,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/**
 * Keeps [content] dark in a light app: the players and whatever draws over their picture, which sit on a black video
 * and the dark overlay scrims in either scheme. While [dark] is false the surrounding scheme stays, and the content
 * keeps its place in the composition either way, so a switch does not reset its state or focus.
 */
@Composable
fun OverVideo(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = if (dark) SofaKingDarkColors else MaterialTheme.colorScheme,
        motionScheme = MotionScheme.expressive(),
    ) {
        val ink = if (dark) MaterialTheme.colorScheme.onSurface else LocalContentColor.current
        CompositionLocalProvider(LocalContentColor provides ink, content = content)
    }
}
