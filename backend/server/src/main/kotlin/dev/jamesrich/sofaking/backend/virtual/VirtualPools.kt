package dev.jamesrich.sofaking.backend.virtual

import dev.jamesrich.sofaking.backend.MS_PER_SECOND
import dev.jamesrich.sofaking.backend.api.VirtualSource
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.library.ItemKind
import dev.jamesrich.sofaking.backend.library.MediaInfo
import dev.jamesrich.sofaking.backend.library.Snapshot
import dev.jamesrich.sofaking.backend.library.View
import dev.jamesrich.sofaking.backend.providers.seasonEpisode
import dev.jamesrich.sofaking.backend.providers.usRating
import java.nio.file.Path
import java.time.Instant

/** The kinds of [VirtualSource]. */
object SourceKind {
    const val LIBRARY = "library"
    const val SHOW = "show"
    const val GENRE = "genre"

    /** A live channel of the lineup, by its entry ID, airing for a timed slot's whole window. */
    const val CHANNEL = "channel"
    val all = setOf(LIBRARY, SHOW, GENRE, CHANNEL)
}

/** A movie, episode or video a virtual channel can air: its file, and its length in whole seconds, rounded up. */
internal class Playable(private val view: View, val file: Path, private val show: View?) {
    val id: String get() = view.record.id
    val media: MediaInfo? get() = view.record.media
    val durationMs: Long =
        checkNotNull(view.record.durationMs).let { (it + MS_PER_SECOND - 1) / MS_PER_SECOND * MS_PER_SECOND }

    val pool: PoolItem get() = PoolItem(id, durationMs)

    /** What the guide shows for it: an episode under its show's title, with its own as the subtitle. */
    fun program(channelId: String, startsAt: Instant, endsAt: Instant): GuideProgram {
        val record = view.record
        val episode = record.kind == ItemKind.EPISODE
        return GuideProgram(
            channelId,
            startsAt,
            endsAt,
            if (episode) show?.record?.title ?: view.item.showTitle ?: record.title else record.title,
            record.overview,
            subTitle = record.title.takeIf { episode },
            episodeNumbers = if (episode) {
                seasonEpisode(
                    record.season?.toLong(),
                    record.episode?.toLong(),
                )
            } else {
                emptyList()
            },
            categories = (show ?: view).item.genres,
            rating = usRating((show ?: view).item.contentRating),
            date = record.year?.toString(),
        )
    }

    /** The title an airing of it shows in the portal. */
    val title: String get() = if (view.record.kind == ItemKind.EPISODE) {
        listOfNotNull(show?.record?.title ?: view.item.showTitle, view.record.title).distinct().joinToString(" - ")
    } else {
        view.record.title
    }
}

/** The item [id] as a virtual channel airs it, or null when it is gone or has no file or length. */
internal fun Snapshot.playable(id: String): Playable? {
    val view = views[id] ?: return null
    if (view.record.kind !in AIRED || (view.record.durationMs ?: 0) < MIN_DURATION_MS) return null
    val file = fileOf(view) ?: return null
    return Playable(view, file, showOf(view))
}

/** Every item [source] names, in play order: titles in order, and a show's episodes in its own. */
internal fun Snapshot.playables(source: VirtualSource): List<Playable> {
    val tops = when (source.kind) {
        SourceKind.LIBRARY -> tops[source.value].orEmpty()

        SourceKind.SHOW -> listOfNotNull(views[source.value]?.takeIf { it.record.kind == ItemKind.SHOW })

        SourceKind.GENRE -> tops.values.flatten().filter { view ->
            view.item.genres.any { it.equals(source.value.trim(), ignoreCase = true) }
        }

        else -> emptyList()
    }
    return tops.sortedBy { it.record.sortTitle }
        .flatMap { if (it.record.kind == ItemKind.SHOW) episodes[it.record.id].orEmpty() else listOf(it) }
        .mapNotNull { playable(it.record.id) }
}

/** Whether [source] names something there is: a library or show by its ID; any genre is taken. */
internal fun Snapshot.exists(source: VirtualSource): Boolean = when (source.kind) {
    SourceKind.LIBRARY -> libraries.any { it.id == source.value }
    SourceKind.SHOW -> views[source.value]?.record?.kind == ItemKind.SHOW
    SourceKind.GENRE -> true
    else -> false
}

/** Every source there is to choose from, labeled: each library, each show, and each genre any title carries. */
internal fun Snapshot.sourceChoices(): List<Pair<VirtualSource, String>> {
    val all = tops.values.flatten()
    val shows = all.filter { it.record.kind == ItemKind.SHOW }.sortedBy { it.record.sortTitle }
    val genres = all.flatMap { it.item.genres }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    return libraries.map { VirtualSource(SourceKind.LIBRARY, it.id) to "Library: ${it.name}" } +
        shows.map { VirtualSource(SourceKind.SHOW, it.record.id) to "Show: ${it.record.title}" } +
        genres.map { VirtualSource(SourceKind.GENRE, it) to "Genre: $it" }
}

/** The show an episode belongs to, through its season when it has one. */
private fun Snapshot.showOf(view: View): View? {
    if (view.record.kind != ItemKind.EPISODE) return null
    val parent = view.record.parentId?.let { views[it] } ?: return null
    return if (parent.record.kind == ItemKind.SEASON) parent.record.parentId?.let { views[it] } else parent
}

private val AIRED = setOf(ItemKind.MOVIE, ItemKind.EPISODE, ItemKind.VIDEO)

/** Anything shorter than a second is not worth a start. */
private const val MIN_DURATION_MS = 1_000L
