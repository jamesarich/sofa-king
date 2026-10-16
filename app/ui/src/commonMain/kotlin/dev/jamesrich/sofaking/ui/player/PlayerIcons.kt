package dev.jamesrich.sofaking.ui.player

import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.PathFillType.Companion.EvenOdd
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The player's icons that the core Material icon set lacks, copied from Material Icons 1.7.3 (Apache 2.0). The
 * extended set is a dependency the Kotlin/JS production build cannot shrink in a reasonable time: minifying it took
 * the web distribution from one minute to over twenty, so the five icons the player needs live here instead.
 */
internal object PlayerIcons {
    val Audiotrack: ImageVector by lazy {
        materialIcon(name = "Filled.Audiotrack") {
            materialPath {
                moveTo(12.0f, 3.0f)
                verticalLineToRelative(9.28f)
                curveToRelative(-0.47f, -0.17f, -0.97f, -0.28f, -1.5f, -0.28f)
                curveTo(8.01f, 12.0f, 6.0f, 14.01f, 6.0f, 16.5f)
                reflectiveCurveTo(8.01f, 21.0f, 10.5f, 21.0f)
                curveToRelative(2.31f, 0.0f, 4.2f, -1.75f, 4.45f, -4.0f)
                horizontalLineTo(15.0f)
                verticalLineTo(6.0f)
                horizontalLineToRelative(4.0f)
                verticalLineTo(3.0f)
                horizontalLineToRelative(-7.0f)
                close()
            }
        }
    }

    val ClosedCaption: ImageVector by lazy {
        materialIcon(name = "Filled.ClosedCaption") {
            materialPath {
                moveTo(19.0f, 4.0f)
                lineTo(5.0f, 4.0f)
                curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(21.0f, 6.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(11.0f, 11.0f)
                lineTo(9.5f, 11.0f)
                verticalLineToRelative(-0.5f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(2.0f)
                lineTo(9.5f, 13.0f)
                lineTo(11.0f, 13.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                lineTo(7.0f, 15.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineToRelative(-4.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                horizontalLineToRelative(3.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(1.0f)
                close()
                moveTo(18.0f, 11.0f)
                horizontalLineToRelative(-1.5f)
                verticalLineToRelative(-0.5f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(2.0f)
                lineTo(16.5f, 13.0f)
                lineTo(18.0f, 13.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineToRelative(-3.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineToRelative(-4.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                horizontalLineToRelative(3.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(1.0f)
                close()
            }
        }
    }

    val ClosedCaptionOff: ImageVector by lazy {
        materialIcon(name = "Filled.ClosedCaptionOff") {
            materialPath {
                moveTo(19.5f, 5.5f)
                verticalLineToRelative(13.0f)
                horizontalLineToRelative(-15.0f)
                verticalLineToRelative(-13.0f)
                horizontalLineToRelative(15.0f)
                close()
                moveTo(19.0f, 4.0f)
                lineTo(5.0f, 4.0f)
                curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(21.0f, 6.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(11.0f, 11.0f)
                lineTo(9.5f, 11.0f)
                verticalLineToRelative(-0.5f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(2.0f)
                lineTo(9.5f, 13.0f)
                lineTo(11.0f, 13.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                lineTo(7.0f, 15.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineToRelative(-4.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                horizontalLineToRelative(3.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(1.0f)
                close()
                moveTo(18.0f, 11.0f)
                horizontalLineToRelative(-1.5f)
                verticalLineToRelative(-0.5f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(2.0f)
                lineTo(16.5f, 13.0f)
                lineTo(18.0f, 13.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineToRelative(-3.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineToRelative(-4.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                horizontalLineToRelative(3.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(1.0f)
                close()
            }
        }
    }

    val GridView: ImageVector by lazy {
        materialIcon(name = "Filled.GridView") {
            materialPath(pathFillType = EvenOdd) {
                moveTo(3.0f, 3.0f)
                verticalLineToRelative(8.0f)
                horizontalLineToRelative(8.0f)
                lineTo(11.0f, 3.0f)
                lineTo(3.0f, 3.0f)
                close()
                moveTo(9.0f, 9.0f)
                lineTo(5.0f, 9.0f)
                lineTo(5.0f, 5.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(3.0f, 13.0f)
                verticalLineToRelative(8.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(-8.0f)
                lineTo(3.0f, 13.0f)
                close()
                moveTo(9.0f, 19.0f)
                lineTo(5.0f, 19.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(13.0f, 3.0f)
                verticalLineToRelative(8.0f)
                horizontalLineToRelative(8.0f)
                lineTo(21.0f, 3.0f)
                horizontalLineToRelative(-8.0f)
                close()
                moveTo(19.0f, 9.0f)
                horizontalLineToRelative(-4.0f)
                lineTo(15.0f, 5.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(13.0f, 13.0f)
                verticalLineToRelative(8.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(-8.0f)
                horizontalLineToRelative(-8.0f)
                close()
                moveTo(19.0f, 19.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(4.0f)
                close()
            }
        }
    }

    val Pause: ImageVector by lazy {
        materialIcon(name = "Filled.Pause") {
            materialPath {
                moveTo(6.0f, 19.0f)
                horizontalLineToRelative(4.0f)
                lineTo(10.0f, 5.0f)
                lineTo(6.0f, 5.0f)
                verticalLineToRelative(14.0f)
                close()
                moveTo(14.0f, 5.0f)
                verticalLineToRelative(14.0f)
                horizontalLineToRelative(4.0f)
                lineTo(18.0f, 5.0f)
                horizontalLineToRelative(-4.0f)
                close()
            }
        }
    }

    /** What the icon set's own internal builder does: a 24dp vector on a 24-unit viewport. */
    private inline fun materialIcon(name: String, block: ImageVector.Builder.() -> ImageVector.Builder): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).block().build()
}
