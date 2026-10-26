package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import dev.jamesrich.sofaking.data.ScrubTiles
import kotlin.math.roundToInt

/** How wide a scrub thumbnail is drawn; its height follows the file's shape. */
private val SCRUB_THUMBNAIL_WIDTH = 200.dp
private val SCRUB_THUMBNAIL_GAP = 12.dp

internal const val SCRUB_THUMBNAIL_TAG = "scrub-thumbnail"

/**
 * The thumbnail of the file at [targetMs]: its tile cut from the sheet it is on. The whole sheet is decoded, at its
 * own size, so a scrub across it draws from the one bitmap; nothing is drawn until it has loaded.
 */
@Composable
internal fun ScrubThumbnail(tiles: ScrubTiles, targetMs: Long, modifier: Modifier = Modifier) {
    val tile = tiles.tileAt(targetMs) ?: return
    val context = LocalPlatformContext.current
    val request = remember(tile.url, context) {
        ImageRequest.Builder(context).data(tile.url).size(coil3.size.Size.ORIGINAL).build()
    }
    val painter = rememberAsyncImagePainter(request)
    val height = SCRUB_THUMBNAIL_WIDTH * tiles.tileHeight / tiles.tileWidth
    Canvas(
        modifier
            .testTag(SCRUB_THUMBNAIL_TAG)
            .size(SCRUB_THUMBNAIL_WIDTH, height)
            .clip(MaterialTheme.shapes.small)
            .background(Color.Black),
    ) {
        // The tile filter pads the last sheet to the full grid, so every sheet is columns by rows tiles.
        val sheet = Size(size.width * tiles.columns, size.height * tiles.rows)
        clipRect {
            translate(-tile.column * size.width, -tile.row * size.height) {
                with(painter) { draw(sheet) }
            }
        }
    }
}

/**
 * The thumbnail over a seek bar as wide as this box, centered on [targetMs] of [lengthMs] and kept inside the bar's
 * ends, standing [SCRUB_THUMBNAIL_GAP] above it; it takes no room of its own.
 */
@Composable
internal fun BoxScope.ScrubThumbnailOverBar(tiles: ScrubTiles, targetMs: Long, lengthMs: Long) {
    val fraction = targetMs.coerceIn(0, lengthMs).toFloat() / lengthMs
    Box(
        Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val thumbnail = measurable.measure(Constraints())
                val barWidth = constraints.maxWidth
                val x = (barWidth * fraction - thumbnail.width / 2f).roundToInt().coerceIn(
                    0,
                    (barWidth - thumbnail.width).coerceAtLeast(0),
                )
                layout(barWidth, 0) { thumbnail.place(x, -thumbnail.height - SCRUB_THUMBNAIL_GAP.roundToPx()) }
            },
    ) {
        ScrubThumbnail(tiles, targetMs)
    }
}
