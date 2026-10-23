package dev.jamesrich.sofaking.ui.theme

import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType.Companion.EvenOdd
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The player's icons that the core Material icon set lacks, copied from Material Icons 1.7.3 (Apache 2.0). The
 * extended set is a dependency the Kotlin/JS production build cannot shrink in a reasonable time: minifying it took
 * the web distribution from one minute to over twenty, so the icons the player and the app's tabs need live here instead.
 */
internal object SofaIcons {
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

    val Fullscreen: ImageVector by lazy {
        pathIcon(
            "Filled.Fullscreen",
            "M7,14L5,14v5h5v-2L7,17v-3zM5,10h2L7,7h3L10,5L5,5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2L19,5h-5z",
        )
    }

    val FullscreenExit: ImageVector by lazy {
        pathIcon(
            "Filled.FullscreenExit",
            "M5,16h3v3h2v-5L5,14v2zM8,8L5,8v2h5L10,5L8,5v3zM14,19h2v-3h3v-2h-5v5zM16,5v3h3v2h-5L14,5h2z",
        )
    }

    val LiveTv: ImageVector by lazy {
        pathIcon(
            "Filled.LiveTv",
            "M21,6h-7.59l3.29,-3.29L16,2l-4,4 -4,-4 -0.71,0.71L10.59,6L3,6c-1.1,0 -2,0.89 -2,2v12c0,1.1 0.9,2 2,2" +
                "h18c1.1,0 2,-0.9 2,-2L23,8c0,-1.11 -0.9,-2 -2,-2zM21,20L3,20L3,8h18v12zM9,10v8l7,-4z",
        )
    }

    val VideoLibrary: ImageVector by lazy {
        pathIcon(
            "Filled.VideoLibrary",
            "M4,6L2,6v14c0,1.1 0.9,2 2,2h14v-2L4,20L4,6z" +
                "M20,2L8,2c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2L22,4c0,-1.1 -0.9,-2 -2,-2z" +
                "M12,14.5v-9l6,4.5 -6,4.5z",
        )
    }

    /** The Recordings tab's record mark. */
    val Recordings: ImageVector by lazy {
        pathIcon(
            "Filled.RadioButtonChecked",
            "M12,7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5 5,-2.24 5,-5 -2.24,-5 -5,-5z" +
                "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                "M12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z",
        )
    }

    val VolumeUp: ImageVector by lazy {
        pathIcon(
            "Filled.VolumeUp",
            "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z" +
                "M14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77" +
                "s-2.99,-7.86 -7,-8.77z",
        )
    }

    val VolumeOff: ImageVector by lazy {
        pathIcon(
            "Filled.VolumeOff",
            "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63z" +
                "M19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77" +
                "v2.06c2.89,0.86 5,3.54 5,6.71z" +
                "M4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06" +
                "c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z",
        )
    }

    /** The players' step back, which is 10 seconds. */
    val Replay10: ImageVector by lazy {
        pathIcon(
            "Filled.Replay10",
            "M11.99,5V1l-5,5l5,5V7c3.31,0,6,2.69,6,6s-2.69,6-6,6s-6-2.69-6-6h-2c0,4.42,3.58,8,8,8s8-3.58,8-8" +
                "S16.41,5,11.99,5zM10.89,16h-0.85v-3.26l-1.01,0.31v-0.69l1.77-0.63h0.09V16z" +
                "M15.17,14.24c0,0.32-0.03,0.6-0.1,0.82s-0.17,0.42-0.29,0.57s-0.28,0.26-0.45,0.33s-0.37,0.1-0.59,0.1 " +
                "s-0.41-0.03-0.59-0.1s-0.33-0.18-0.46-0.33s-0.23-0.34-0.3-0.57s-0.11-0.5-0.11-0.82V13.5" +
                "c0-0.32,0.03-0.6,0.1-0.82 s0.17-0.42,0.29-0.57s0.28-0.26,0.45-0.33s0.37-0.1,0.59-0.1" +
                "s0.41,0.03,0.59,0.1c0.18,0.07,0.33,0.18,0.46,0.33 s0.23,0.34,0.3,0.57s0.11,0.5,0.11,0.82V14.24z" +
                "M14.32,13.38c0-0.19-0.01-0.35-0.04-0.48s-0.07-0.23-0.12-0.31 s-0.11-0.14-0.19-0.17" +
                "s-0.16-0.05-0.25-0.05s-0.18,0.02-0.25,0.05s-0.14,0.09-0.19,0.17s-0.09,0.18-0.12,0.31 " +
                "s-0.04,0.29-0.04,0.48v0.97c0,0.19,0.01,0.35,0.04,0.48s0.07,0.24,0.12,0.32s0.11,0.14,0.19,0.17" +
                "s0.16,0.05,0.25,0.05 s0.18-0.02,0.25-0.05s0.14-0.09,0.19-0.17s0.09-0.19,0.11-0.32" +
                "s0.04-0.29,0.04-0.48V13.38z",
        )
    }

    /** The players' step forward, which is 30 seconds. */
    val Forward30: ImageVector by lazy {
        pathIcon(
            "Filled.Forward30",
            "M18,13c0,3.31-2.69,6-6,6s-6-2.69-6-6s2.69-6,6-6v4l5-5l-5-5v4c-4.42,0-8,3.58-8,8c0,4.42,3.58,8,8,8" +
                "s8-3.58,8-8H18z" +
                "M10.06,15.38c-0.29,0-0.62-0.17-0.62-0.54H8.59c0,0.97,0.9,1.23,1.45,1.23c0.87,0,1.51-0.46,1.51-1.25 " +
                "c0-0.66-0.45-0.9-0.71-1c0.11-0.05,0.65-0.32,0.65-0.92c0-0.21-0.05-1.22-1.44-1.22" +
                "c-0.62,0-1.4,0.35-1.4,1.16h0.85 c0-0.34,0.31-0.48,0.57-0.48c0.59,0,0.58,0.5,0.58,0.54" +
                "c0,0.52-0.41,0.59-0.63,0.59H9.56v0.66h0.45c0.65,0,0.7,0.42,0.7,0.64 " +
                "C10.71,15.11,10.5,15.38,10.06,15.38z" +
                "M13.85,11.68c-0.14,0-1.44-0.08-1.44,1.82v0.74c0,1.9,1.31,1.82,1.44,1.82c0.14,0,1.44,0.09,1.44-1.82" +
                "V13.5 C15.3,11.59,13.99,11.68,13.85,11.68z" +
                "M14.45,14.35c0,0.77-0.21,1.03-0.59,1.03c-0.38,0-0.6-0.26-0.6-1.03v-0.97 c0-0.75,0.22-1.01,0.59-1.01" +
                "c0.38,0,0.6,0.26,0.6,1.01V14.35z",
        )
    }

    /** Start over. */
    val Replay: ImageVector by lazy {
        pathIcon(
            "Filled.Replay",
            "M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z",
        )
    }

    /** The sleep timer. */
    val Bedtime: ImageVector by lazy {
        pathIcon(
            "Filled.Bedtime",
            "M12.34,2.02C6.59,1.82,2,6.42,2,12c0,5.52,4.48,10,10,10c3.71,0,6.93-2.02,8.66-5.02" +
                "C13.15,16.73,8.57,8.55,12.34,2.02z",
        )
    }

    /** Next episode. */
    val SkipNext: ImageVector by lazy { pathIcon("Filled.SkipNext", "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z") }

    /** Playback speed, on the library player's bar. */
    val Speed: ImageVector by lazy {
        pathIcon(
            "Filled.Speed",
            "M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z",
        )
    }

    /** Skip a commercial break. */
    val FastForward: ImageVector by lazy { pathIcon("Filled.FastForward", "M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z") }

    /** Record, in the live player. */
    val Record: ImageVector by lazy { pathIcon("Filled.FiberManualRecord", "M12,4a8,8 0,1 0,0 16a8,8 0,1 0,0 -16z") }

    /** The previous channel, on the live bar. */
    val SwapHoriz: ImageVector by lazy {
        pathIcon("Filled.SwapHoriz", "M6.99 11L3 15l3.99 4v-3H14v-2H6.99v-3zM21 9l-3.99-4v3H10v2h7.01v3L21 9z")
    }

    /** Zoom, in both players. */
    val AspectRatio: ImageVector by lazy {
        pathIcon(
            "Filled.AspectRatio",
            "M19 12h-2v3h-3v2h5v-5z" +
                "M7 9h3V7H5v5h2V9zm14-6H3c-1.1 0-2 0.9-2 2v14c0 1.1 0.9 2 2 2h18c1.1 0 2-0.9 2-2V5c0-1.1-0.9-2-2-2z" +
                "m0 16.01H3V4.99h18v14.02z",
        )
    }

    /** A library item's Audio and subtitles. */
    val Subtitles: ImageVector by lazy {
        pathIcon(
            "Filled.Subtitles",
            "M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2z" +
                "M4 12h4v2H4v-2zm10 6H4v-2h10v2zm6 0h-4v-2h4v2zm0-4H10v-2h10v2z",
        )
    }

    /** The caption timing's minus; the core set has Add but not Remove. */
    val Remove: ImageVector by lazy { pathIcon("Filled.Remove", "M19,13H5v-2h14v2z") }

    /** A track row's mark: [Recordings] when picked, this ring when not. */
    val RadioButtonUnchecked: ImageVector by lazy {
        pathIcon(
            "Filled.RadioButtonUnchecked",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                "M12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z",
        )
    }

    /** An icon from its SVG path data, as the icon set's sources give it. */
    private fun pathIcon(name: String, pathData: String): ImageVector =
        materialIcon(name) { addPath(addPathNodes(pathData), fill = SolidColor(Color.Black)) }

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
