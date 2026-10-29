package dev.jamesrich.sofaking.ui.library

import dev.jamesrich.sofaking.data.CONTINUE_SHELF
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaShelf

/** The server's id for a library's Recently added row. */
internal const val RECENT_SHELF = "recent"

/** What OK, or a tap, does on a card of the library home. */
internal enum class CardOk { OPEN_PAGE, PLAY }

/** Continue watching resumes what its card shows; every other row opens the item's page. */
internal fun cardOkFor(shelf: MediaShelf, item: MediaItem): CardOk =
    if (shelf.id == CONTINUE_SHELF && item.kind.playable) CardOk.PLAY else CardOk.OPEN_PAGE

/** Whether [shelf]'s cards feed the home's spotlight: Continue watching and the Recently added rows. */
internal fun feedsSpotlight(shelf: MediaShelf): Boolean = shelf.id == CONTINUE_SHELF || shelf.id == RECENT_SHELF

/**
 * The item the spotlight shows: [focusedId] while it is on a spotlight shelf, else the first item of Continue watching
 * or, without one, of the first Recently added row. Music has no backdrop worth showing and is skipped.
 */
internal fun spotlightItem(shelves: List<MediaShelf>, focusedId: String?): MediaItem? {
    val candidates = shelves.filter(::feedsSpotlight).flatMap { it.items }.filterNot { it.kind.music }
    return candidates.firstOrNull { it.id == focusedId } ?: candidates.firstOrNull()
}

/** True when the spotlight's item is in progress, so its button reads Resume rather than Play. */
internal fun resumes(item: MediaItem): Boolean = item.progress?.inProgress == true

/** The first sentence of [item]'s overview, kept to [limit] characters; null without an overview. */
internal fun spotlightTagline(item: MediaItem, limit: Int = TAGLINE_LIMIT): String? {
    val text = item.overview?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val end = text.indexOfFirst { it == '.' || it == '!' || it == '?' }
    val sentence = if (end in 0 until text.length - 1 && text[end + 1] == ' ') text.substring(0, end + 1) else text
    if (sentence.length <= limit) return sentence
    return sentence.take(limit - 1).trimEnd() + "…"
}

/** The card styles of the placeholder rows shown while the home loads: Continue watching's, then a poster row. */
internal fun skeletonStyles(): List<CardStyle> = listOf(
    cardStyleFor(MediaShelf(CONTINUE_SHELF, "", emptyList())),
    cardStyleFor(MediaShelf(RECENT_SHELF, "", emptyList())),
)

private const val TAGLINE_LIMIT = 140
