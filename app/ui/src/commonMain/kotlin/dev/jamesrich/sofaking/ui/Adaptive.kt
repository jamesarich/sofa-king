package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Material 3's window size classes for one axis. */
internal enum class SizeClass { COMPACT, MEDIUM, EXPANDED }

/**
 * The window the app is laid out in, by Material 3's breakpoints: widths under 600dp are compact and from 840dp
 * expanded, heights under 480dp compact and from 900dp expanded. A 1080p TV (960x540dp) is expanded by medium;
 * a phone held sideways is compact in height, upright compact in width.
 */
@Immutable
internal data class WindowLayout(val width: SizeClass, val height: SizeClass) {
    /** Too narrow or too short for the guide's preview band beside the chips; the grid gets the room instead. */
    val showsPreview: Boolean get() = width != SizeClass.COMPACT && height != SizeClass.COMPACT

    /** A handheld-sized window, which takes Material's own type scale rather than the one read from the sofa. */
    val isCompact: Boolean get() = width == SizeClass.COMPACT || height == SizeClass.COMPACT

    /** Side margin, before the safe area: the TV's overscan unless the window is narrow. */
    val horizontalMargin: Dp get() = if (width == SizeClass.COMPACT) 16.dp else OVERSCAN_HORIZONTAL

    /** Top and bottom margin, before the safe area: the TV's overscan unless the window is short. */
    val verticalMargin: Dp get() = if (height == SizeClass.COMPACT) 12.dp else OVERSCAN_VERTICAL

    companion object {
        /** A 1080p TV, the layout every screen was tuned on. */
        val TV = WindowLayout(SizeClass.EXPANDED, SizeClass.MEDIUM)
    }
}

/** Overscan a TV may crop at each edge; screens keep their content inside it. */
internal val OVERSCAN_HORIZONTAL = 48.dp
internal val OVERSCAN_VERTICAL = 27.dp

internal fun windowLayoutFor(width: Dp, height: Dp): WindowLayout = WindowLayout(
    width = when {
        width < 600.dp -> SizeClass.COMPACT
        width < 840.dp -> SizeClass.MEDIUM
        else -> SizeClass.EXPANDED
    },
    height = when {
        height < 480.dp -> SizeClass.COMPACT
        height < 900.dp -> SizeClass.MEDIUM
        else -> SizeClass.EXPANDED
    },
)

/** The window's layout, provided by `SofaKingTheme` from the space the app is given. */
internal val LocalWindowLayout = staticCompositionLocalOf { WindowLayout.TV }

/**
 * The margin every screen keeps at [sides]: the larger of the window's margin and the safe area (system bars,
 * a display cutout, the soft keyboard). On a TV the safe area is empty and the overscan margin rules.
 */
@Composable
internal fun Modifier.screenMargins(sides: WindowInsetsSides = WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical): Modifier {
    val layout = LocalWindowLayout.current
    val margin = WindowInsets(layout.horizontalMargin, layout.verticalMargin, layout.horizontalMargin, layout.verticalMargin)
    return windowInsetsPadding(WindowInsets.safeDrawing.union(margin).only(sides))
}
