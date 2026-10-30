package dev.jamesrich.sofaking.data

/** How many characters of an overview the spotlight keeps as its tagline. */
const val SPOTLIGHT_TAGLINE_LIMIT = 140

/** The server's id for a library's Recently added row. */
const val RECENT_SHELF = "recent"

/** What OK, or a tap, does on a card of the library home. */
enum class CardOk { OPEN_PAGE, PLAY }

/** Continue watching resumes what its card shows; every other row opens the item's page. */
fun cardOkFor(shelf: MediaShelf, item: MediaItem): CardOk =
    if (shelf.id == CONTINUE_SHELF && item.kind.playable) CardOk.PLAY else CardOk.OPEN_PAGE

/** Whether [shelf]'s cards feed the home's spotlight: Continue watching and the Recently added rows. */
fun feedsSpotlight(shelf: MediaShelf): Boolean = shelf.id == CONTINUE_SHELF || shelf.id == RECENT_SHELF

/**
 * The item the spotlight shows: [focusedId] while it is on a spotlight shelf, else the first item of Continue watching
 * or, without one, of the first Recently added row. Music has no backdrop worth showing and is skipped.
 */
fun spotlightItem(shelves: List<MediaShelf>, focusedId: String?): MediaItem? {
    val candidates = shelves.filter(::feedsSpotlight).flatMap { it.items }.filterNot { it.kind.music }
    return candidates.firstOrNull { it.id == focusedId } ?: candidates.firstOrNull()
}

/** True when the spotlight's item is in progress, so its button reads Resume rather than Play. */
fun resumes(item: MediaItem): Boolean = item.progress?.inProgress == true

/** The first sentence of [item]'s overview, kept to [limit] characters; null without an overview. */
fun spotlightTagline(item: MediaItem, limit: Int = SPOTLIGHT_TAGLINE_LIMIT): String? {
    val text = item.overview?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val end = text.indexOfFirst { it == '.' || it == '!' || it == '?' }
    val sentence = if (end in 0 until text.length - 1 && text[end + 1] == ' ') text.substring(0, end + 1) else text
    if (sentence.length <= limit) return sentence
    return sentence.take(limit - 1).trimEnd() + "…"
}
