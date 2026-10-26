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
 * the episode the server names next, resumed the same way; for an album its first track. Music always plays from the
 * start. Null when there is nothing to play yet.
 */
fun primaryAction(detail: MediaItemDetail): PrimaryAction? {
    val target = when {
        detail.item.kind.playable -> detail.item
        detail.item.kind == ItemKind.ALBUM -> detail.children.firstOrNull { it.kind == ItemKind.TRACK }
        else -> detail.next?.takeIf { it.kind.playable }
    } ?: return null
    val progress = target.progress
    return if (progress != null && progress.inProgress && !target.kind.music) {
        PrimaryAction.Resume(target, progress.positionMs)
    } else {
        PrimaryAction.Play(target)
    }
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

/** The playable item after [id] in [children], a season's episodes in order; null when [id] is the last or absent. */
fun episodeAfter(children: List<MediaItem>, id: String): MediaItem? {
    val episodes = children.filter { it.kind.playable }
    val at = episodes.indexOfFirst { it.id == id }
    return if (at < 0) null else episodes.getOrNull(at + 1)
}

/** The season after [seasonId] among a show's [children], skipping Specials, which the viewer picks by hand. */
fun seasonAfter(children: List<MediaItem>, seasonId: String): MediaItem? {
    val seasons = children.filter { it.kind == ItemKind.SEASON }
    val at = seasons.indexOfFirst { it.id == seasonId }
    if (at < 0) return null
    return seasons.drop(at + 1).firstOrNull { it.season != 0 }
}

/**
 * Whether Hide spoilers keeps [item]'s overview back and blurs its still: an episode not started yet. One watched, or
 * stopped part way, shows as usual.
 */
fun hidesSpoilers(item: MediaItem): Boolean =
    item.kind == ItemKind.EPISODE && item.progress.let { it == null || (!it.watched && !it.inProgress) }

/**
 * What an item's page says about its file: the [sound] of the track it starts with, such as "Dolby Digital 5.1",
 * and the languages of its audio tracks and of its full subtitle tracks, by name, each once.
 */
data class FileFacts(val sound: String?, val audioLanguages: List<String>, val subtitleLanguages: List<String>)

fun fileFacts(detail: MediaItemDetail): FileFacts = FileFacts(
    sound = defaultAudio(detail)?.let { track ->
        listOfNotNull(codecName(track.codec), track.channels?.let(::channelLayout)).joinToString(" ")
    },
    audioLanguages = languageNames(detail.audio),
    subtitleLanguages = languageNames(detail.subtitles.filterNot { it.forced }),
)

/** The server's id for the row of items in progress and next episodes. */
const val CONTINUE_SHELF = "continue"
