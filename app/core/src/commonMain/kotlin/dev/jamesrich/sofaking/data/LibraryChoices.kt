package dev.jamesrich.sofaking.data

// Choices every library screen makes the same way, the Compose app's and the Apple TV app's.

/** What the details page's first button does. */
sealed interface PrimaryAction {
    val item: MediaItem

    /** Plays [item] from the start. */
    data class Play(override val item: MediaItem) : PrimaryAction

    /** Picks [item] up where it stopped. */
    data class Resume(override val item: MediaItem, val positionMs: Long) : PrimaryAction
}

/**
 * The page's first button: the item itself when it plays, resumed when it is in progress; for a show or a season
 * the episode the server names next, resumed the same way. Null when there is nothing to play yet.
 */
fun primaryAction(detail: MediaItemDetail): PrimaryAction? {
    val target = if (detail.item.kind.playable) detail.item else detail.next?.takeIf { it.kind.playable } ?: return null
    val progress = target.progress
    return if (progress != null && progress.inProgress) PrimaryAction.Resume(target, progress.positionMs) else PrimaryAction.Play(target)
}

/**
 * The season a show's page opens on: the one holding the next episode, else the first with something unwatched,
 * else the first. Null for a page without seasons.
 */
fun initialSeasonId(detail: MediaItemDetail): String? {
    val seasons = detail.children.filter { it.kind == ItemKind.SEASON }
    if (seasons.isEmpty()) return null
    val nextSeason = detail.next?.parentId
    return seasons.firstOrNull { it.id == nextSeason }?.id
        ?: seasons.firstOrNull { it.unwatched > 0 }?.id
        ?: seasons.first().id
}

/** The server's id for the row of items in progress and next episodes. */
const val CONTINUE_SHELF = "continue"
