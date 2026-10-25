package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.Channel

/** The fewest channels a multiview shows. */
internal const val MULTIVIEW_MIN_TILES = 2

/**
 * Where tile [index] of [count] sits in the picture, in fractions of its width and height, as the server draws it:
 * two side by side across the middle, three or four in a grid, left to right and top to bottom.
 */
internal fun multiviewTile(count: Int, index: Int): Rect {
    val left = (index % 2) * HALF
    val top = if (count == MULTIVIEW_MIN_TILES) QUARTER else (index / 2) * HALF
    return Rect(left, top, left + HALF, top + HALF)
}

/** The tile of [count] at [x] and [y], fractions of the picture's width and height; null between and around them. */
internal fun multiviewTileAt(count: Int, x: Float, y: Float): Int? =
    (0 until count).firstOrNull { multiviewTile(count, it).contains(Offset(x, y)) }

/** The tile an arrow [key] moves the sound to from [from], of [count]; [from] itself when nothing lies that way. */
internal fun multiviewMove(count: Int, from: Int, key: Key): Int {
    val to = when (key) {
        Key.DirectionLeft -> if (from % 2 == 1) from - 1 else from
        Key.DirectionRight -> if (from % 2 == 0) from + 1 else from
        Key.DirectionUp -> if (count > MULTIVIEW_MIN_TILES && from >= 2) from - 2 else from
        Key.DirectionDown -> if (count > MULTIVIEW_MIN_TILES && from < 2) from + 2 else from
        else -> from
    }
    return to.takeIf { it < count } ?: from
}

/**
 * The channels the picker lists: those [picked] first, in the order they were picked, then the starred ones in
 * [favoritesOrder], then the [recent] ones, then the rest of [channels] in lineup order, each once.
 */
internal fun multiviewChoices(
    channels: List<Channel>,
    picked: List<String>,
    favoritesOrder: List<String>,
    recent: List<String>,
): List<Channel> {
    val byId = channels.associateBy { it.guideId }
    return (picked + favoritesOrder + recent + channels.map { it.guideId })
        .distinct()
        .mapNotNull { byId[it] }
}

/** [picked] with [guideId] added or taken out; a pick past [Wire.MULTIVIEW_MAX_TILES] is refused. */
internal fun togglePick(picked: List<String>, guideId: String): List<String> = when {
    guideId in picked -> picked - guideId
    picked.size >= Wire.MULTIVIEW_MAX_TILES -> picked
    else -> picked + guideId
}

private const val HALF = 0.5f
private const val QUARTER = 0.25f
