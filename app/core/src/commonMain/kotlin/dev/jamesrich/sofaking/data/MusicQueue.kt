package dev.jamesrich.sofaking.data

import kotlin.random.Random

/** What happens at the end of a song: the queue plays on and stops after its last, starts over, or plays it again. */
enum class Repeat { OFF, ALL, ONE }

/**
 * The songs that play one after another: [songs] in the order they were picked (an album's, as its page lists them),
 * played in [order], which holds each song's index once and is shuffled or not. [position] is where in [order] the
 * queue is, so the playing song is [current].
 */
data class MusicQueue(
    val songs: List<MediaItem>,
    val order: List<Int> = songs.indices.toList(),
    val position: Int = 0,
    val repeat: Repeat = Repeat.OFF,
) {
    init {
        require(songs.isNotEmpty() && position in order.indices) { "a queue holds the song it plays" }
    }

    val current: MediaItem get() = songs[order[position]]

    val shuffled: Boolean get() = order != songs.indices.toList()

    /** True when [next] after a song has ended has a song to play. */
    val hasNext: Boolean get() = position < order.lastIndex || repeat != Repeat.OFF

    /**
     * The queue on the song after this one, or null after the last when nothing repeats. A song that [ended] by itself
     * plays again under [Repeat.ONE]; skipping one moves on whatever the repeat.
     */
    fun next(ended: Boolean = false): MusicQueue? = when {
        ended && repeat == Repeat.ONE -> this
        position < order.lastIndex -> copy(position = position + 1)
        repeat == Repeat.OFF -> null
        else -> copy(position = 0)
    }

    /** The queue on the song before this one: the last after the first when the queue repeats, else the first again. */
    fun previous(): MusicQueue = when {
        position > 0 -> copy(position = position - 1)
        repeat == Repeat.OFF -> this
        else -> copy(position = order.lastIndex)
    }

    /** The queue on [index] in [order], as a song picked in the queue's list. */
    fun at(index: Int): MusicQueue = copy(position = index.coerceIn(order.indices))

    /** Off, then the whole queue, then the one song, then off again. */
    fun nextRepeat(): MusicQueue = copy(repeat = Repeat.entries[(repeat.ordinal + 1) % Repeat.entries.size])

    /**
     * Shuffled or not, playing on from the song playing now: shuffled, it comes first and the rest follow in a [random]
     * order; unshuffled, the songs play on in their own order after it.
     */
    fun shuffle(on: Boolean, random: Random = Random): MusicQueue {
        val playing = order[position]
        return if (on) {
            copy(order = listOf(playing) + (songs.indices - playing).shuffled(random), position = 0)
        } else {
            copy(order = songs.indices.toList(), position = playing)
        }
    }

    companion object {
        /**
         * The songs among [tracks] (anything that does not play is left out) from the one [start] names, or the first;
         * [shuffled], the one [start] names comes first and the rest follow in a [random] order, or all of them do
         * without a start. Null when nothing plays.
         */
        fun of(
            tracks: List<MediaItem>,
            start: String? = null,
            shuffled: Boolean = false,
            random: Random = Random,
        ): MusicQueue? {
            val songs = tracks.filter { it.kind.playable }.ifEmpty { return null }
            val first = songs.indexOfFirst { it.id == start }
            return when {
                first >= 0 -> MusicQueue(songs, position = first).let { if (shuffled) it.shuffle(true, random) else it }
                shuffled -> MusicQueue(songs, order = songs.indices.shuffled(random))
                else -> MusicQueue(songs)
            }
        }
    }
}
