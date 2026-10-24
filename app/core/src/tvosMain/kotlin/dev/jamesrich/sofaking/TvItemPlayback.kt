package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.SofaKingException
import dev.jamesrich.sofaking.data.TrackChoice
import dev.jamesrich.sofaking.data.defaultAudio
import dev.jamesrich.sofaking.data.initialAudio
import dev.jamesrich.sofaking.data.initialSubtitle
import dev.jamesrich.sofaking.data.isPictureSubtitle
import dev.jamesrich.sofaking.data.trackChoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** How long playing an item waits for its page to learn whether it starts with drawn-in subtitles. */
private const val DETAIL_WAIT_MS = 5_000L

/** A library item on the session, as the Apple TV app's player plays it. */
interface TvItemPlayback {
    /**
     * Opens [item] on the session, from the start with [fromStart], else where the household stopped in it, with the
     * tracks [initialAudio] and [initialSubtitle] pick: an audio track other than the default, and picture subtitles
     * drawn in.
     */
    @Throws(Exception::class)
    suspend fun playItem(item: MediaItem, fromStart: Boolean): PlaybackStream

    /** The tracks the viewer picked in [item]'s show, which its episodes start with; null for none or a movie. */
    fun trackChoice(item: MediaItem): TrackChoice?

    /** Keeps the audio track [audioIndex] and subtitle track [subtitleIndex] (negative for none) for [item]'s show. */
    fun rememberTracks(item: MediaItem, detail: MediaItemDetail, audioIndex: Int, subtitleIndex: Int)

    /**
     * Plays [item] again from [positionMs] with the audio track [audioIndex] (the file's default when negative) and
     * the picture subtitle track [subtitleIndex] drawn in (none when negative).
     */
    @Throws(Exception::class)
    suspend fun switchItemTracks(item: MediaItem, positionMs: Long, audioIndex: Int, subtitleIndex: Int): PlaybackStream

    /** True when [error], from an open, a restart or a track switch, is the server saying every converter is busy. */
    fun isConverterBusy(error: Throwable): Boolean

    /** Restarts a converted stream at [positionMs] in the item; a file played as it is comes back unchanged. */
    @Throws(Exception::class)
    suspend fun restartItemAt(positionMs: Long): PlaybackStream?

    /** The player is at [positionMs] in [itemId]; [durationMs] is 0 while the length is unknown. */
    fun itemProgress(itemId: String, positionMs: Long, durationMs: Long)

    /** Playback of [itemId] stopped at [positionMs]; saved now, and the library's pages reload. */
    fun itemStopped(itemId: String, positionMs: Long, durationMs: Long)
}

internal class TvItemCalls(private val parts: TvParts) : TvItemPlayback {
    private val library = parts.library
    private val viewModel = parts.viewModel

    @Throws(Exception::class)
    override suspend fun playItem(item: MediaItem, fromStart: Boolean): PlaybackStream = parts.opening {
        val state =
            withTimeoutOrNull(DETAIL_WAIT_MS) { library.detail(item.id).first { it !is ItemDetailState.Loading } }
        val detail = (state as? ItemDetailState.Ready)?.detail
        val choice = trackChoice(item)
        val audio = detail?.let { initialAudio(it, choice) }?.takeIf { it.index != detail.let(::defaultAudio)?.index }
        val picture = detail?.let { initialSubtitle(it, choice) }?.takeIf(::isPictureSubtitle)
        library.play(item, appleTvProfile, fromStart, audio?.index, picture?.index)
    }

    override fun trackChoice(item: MediaItem): TrackChoice? =
        item.showId?.let { viewModel.settings.value?.trackChoices?.get(it) }

    override fun rememberTracks(item: MediaItem, detail: MediaItemDetail, audioIndex: Int, subtitleIndex: Int) {
        val show = item.showId ?: return
        viewModel.setTrackChoice(
            show,
            trackChoice(
                detail,
                audioIndex.takeIf {
                    it >= 0
                },
                subtitleIndex.takeIf { it >= 0 },
            ),
        )
    }

    @Throws(Exception::class)
    override suspend fun switchItemTracks(
        item: MediaItem,
        positionMs: Long,
        audioIndex: Int,
        subtitleIndex: Int,
    ): PlaybackStream = parts.opening {
        library.switchTracks(
            item,
            appleTvProfile,
            positionMs,
            audioIndex.takeIf { it >= 0 },
            subtitleIndex.takeIf { it >= 0 },
        )
    }

    override fun isConverterBusy(error: Throwable): Boolean =
        (error as? SofaKingException)?.code == Wire.ErrorCode.CAPACITY_EXHAUSTED

    @Throws(Exception::class)
    override suspend fun restartItemAt(positionMs: Long): PlaybackStream? = viewModel.sessions.restartAt(positionMs)

    override fun itemProgress(itemId: String, positionMs: Long, durationMs: Long) =
        library.progress(itemId, positionMs, durationMs.takeIf { it > 0 })

    override fun itemStopped(itemId: String, positionMs: Long, durationMs: Long) =
        library.stopped(itemId, positionMs, durationMs.takeIf { it > 0 })
}
