package dev.jamesrich.sofaking.data

import kotlin.random.Random

/** Songs that play one after another, in [items] order: an album's, as its page lists them, or shuffled. */
data class PlayQueue(val items: List<MediaItem>) {
    /** The song after [id], or null when [id] is the last or not in the queue. */
    fun after(id: String): MediaItem? =
        items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { items.getOrNull(it + 1) }

    companion object {
        /** [tracks] in their order, or in a [random] one when [shuffled]; anything that does not play is left out. */
        fun of(tracks: List<MediaItem>, shuffled: Boolean = false, random: Random = Random): PlayQueue {
            val songs = tracks.filter { it.kind.playable }
            return PlayQueue(if (shuffled) songs.shuffled(random) else songs)
        }
    }
}
