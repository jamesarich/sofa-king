package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MusicQueue
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.Repeat

/** Music, as the Apple TV app plays it: core's [MusicQueue], and each song's session on the one playback session. */
interface TvMusic {
    /**
     * The songs among [tracks] from the one [startId] names, or the first when it is null; [shuffled], in a random
     * order with that one first. Null when nothing plays.
     */
    fun musicQueue(tracks: List<MediaItem>, startId: String?, shuffled: Boolean): MusicQueue?

    /** [queue] shuffled or back in its own order, playing on from the song playing now. */
    fun shuffleQueue(queue: MusicQueue, on: Boolean): MusicQueue

    /** [queue]'s repeat, which Swift cannot read by name: `repeat` is a Swift keyword. */
    fun repeatOf(queue: MusicQueue): Repeat

    /**
     * Opens [song]'s session at [startMs]: the file as it is when AVPlayer plays it, else its sound as AAC. It runs to
     * the end even when the Swift task that asked is canceled; [releaseSong] lets go of a session nobody wants.
     */
    @Throws(Exception::class)
    suspend fun playSong(song: MediaItem, startMs: Long): PlaybackStream

    /** Releases [itemId]'s session, and only that one, so a channel or movie that took over keeps its own. */
    fun releaseSong(itemId: String)
}

internal class TvMusicCalls(private val parts: TvParts) : TvMusic {
    override fun musicQueue(tracks: List<MediaItem>, startId: String?, shuffled: Boolean): MusicQueue? =
        MusicQueue.of(tracks, startId, shuffled)

    override fun shuffleQueue(queue: MusicQueue, on: Boolean): MusicQueue = queue.shuffle(on)

    override fun repeatOf(queue: MusicQueue): Repeat = queue.repeat

    @Throws(Exception::class)
    override suspend fun playSong(song: MediaItem, startMs: Long): PlaybackStream =
        parts.connection.sessions.play(song.id, appleTvProfile, startMs)

    override fun releaseSong(itemId: String) = parts.connection.sessions.releaseAsync(forget = true, itemId = itemId)
}
