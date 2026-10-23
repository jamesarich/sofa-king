package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AdBreakNow
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.DataStoreSettingsStore
import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.LiveBreaks
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.RecordingMark
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.data.RecordingsState
import dev.jamesrich.sofaking.data.SeriesRule
import dev.jamesrich.sofaking.data.ServerCapabilities
import dev.jamesrich.sofaking.data.ServerConnection
import dev.jamesrich.sofaking.data.Subtitles
import dev.jamesrich.sofaking.data.TopLevelTab
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.TrackChoice
import dev.jamesrich.sofaking.data.ViewerSync
import dev.jamesrich.sofaking.data.defaultAudio
import dev.jamesrich.sofaking.data.initialAudio
import dev.jamesrich.sofaking.data.initialSubtitle
import dev.jamesrich.sofaking.data.trackChoice
import dev.jamesrich.sofaking.data.isPictureSubtitle
import dev.jamesrich.sofaking.data.onAppleTv
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import kotlin.time.Clock

/** A running subscription from [SofaKingTv]; Swift cancels it when its view goes away. */
class Watch internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

/** What a channel row shows: the program on now and the one after it. */
data class NowNext(val now: Program?, val next: Program?)

/**
 * The shared data layer for the SwiftUI Apple TV app, which cannot use Compose: the one [LineupViewModel] and its
 * playback session, with flows handed to Swift as callbacks on the main thread and suspend calls as Swift `async`.
 * Create one per process, from the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
class SofaKingTv {
    // The settings live in NSUserDefaults, which tvOS keeps; the lineup cache in Caches, which it may purge.
    private val storage = AppStorage.onAppleTv(
        cachesDirectory = requireNotNull(
            NSFileManager.defaultManager.URLForDirectory(
                directory = NSCachesDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            )?.path,
        ) { "no Caches directory" },
    )
    private val scope: CoroutineScope = MainScope()

    private val store = DataStoreSettingsStore(storage.settings)

    /** The server Settings names, its one playback session and the guide's record marks, for every ViewModel below. */
    private val connection = ServerConnection(store.settings, scope, viewerSync = ViewerSync(store, storage.client))

    val viewModel = LineupViewModel(store = store, lineupCache = storage.lineupCache, connection = connection, client = storage.client)

    /** The media libraries, playing through the same session as the channels. */
    val library = LibraryViewModel(connection.api, connection.sessions)

    /** The server's recordings, the same list the guide's record marks come from, playing through the same session. */
    val recordings = RecordingsViewModel(connection.recordings, connection.sessions)

    fun watchState(onChange: (LineupState) -> Unit): Watch = watch(viewModel.state, onChange)

    /** What the server offers beyond live TV; the Library tab shows when it serves libraries. */
    fun watchCapabilities(onChange: (ServerCapabilities) -> Unit): Watch = watch(viewModel.capabilities, onChange)

    fun watchSettings(onChange: (AppSettings) -> Unit): Watch = watch(viewModel.settings.filterNotNull(), onChange)

    /** Streams the server replaced under the held session; the player swaps to the new URL. */
    fun watchReplaced(onChange: (PlaybackStream) -> Unit): Watch = watch(viewModel.sessions.replaced, onChange)

    /** Opens, or re-reads, a watch session for [channelId] and remembers it as the last channel. */
    @Throws(Exception::class)
    suspend fun watchChannel(channelId: String): PlaybackStream = opening {
        val stream = viewModel.sessions.tune(channelId, PlaybackIntent.WATCH)
        viewModel.setLastChannel(channelId)
        stream
    }

    /** Releases the held session; [reopen] returns to it. */
    @Throws(Exception::class)
    suspend fun release() = viewModel.sessions.release()

    /** Like an open, a reopen still running when the player leaves is canceled by [stopWatching], so it holds nothing after. */
    @Throws(Exception::class)
    suspend fun reopen(): PlaybackStream? = opening { viewModel.sessions.reopen() }

    /**
     * Releases the held session for good, as leaving the player does. An open still running is canceled first, which
     * releases what it opened: Swift's task cancellation does not reach a Kotlin suspend call.
     */
    fun stopWatching() {
        val pending = open
        open = null
        scope.launch {
            pending?.cancelAndJoin()
            viewModel.sessions.release(forget = true)
        }
    }

    /** The channel or item open running now; touched only on the main thread, as every call here is. */
    private var open: Job? = null

    private suspend fun <T> opening(block: suspend () -> T): T {
        val job = scope.async { block() }
        open = job
        try {
            return job.await()
        } finally {
            if (open === job) open = null
        }
    }

    fun watchLibraryHome(onChange: (LibraryHomeState) -> Unit): Watch = watch(library.home, onChange)

    /** The grid [openGrid] opened last, as its pages load. */
    fun watchLibraryGrid(onChange: (LibraryGrid?) -> Unit): Watch = watch(library.grid, onChange)

    /** [id]'s page, loaded on the first watch. */
    fun watchItem(id: String, onChange: (ItemDetailState) -> Unit): Watch = watch(library.detail(id), onChange)

    fun refreshLibrary() = library.refreshHome()

    fun refreshItem(id: String) {
        library.detail(id, refresh = true)
    }

    /** Opens [libraryId]'s grid in [sort] order, only what is not watched with [unwatched], only [genre] when given. */
    fun openGrid(libraryId: String, sort: LibrarySort, unwatched: Boolean, genre: String?) = library.openGrid(libraryId, sort, unwatched, genre)

    fun loadMore() = library.loadMore()

    fun setWatched(item: MediaItem, watched: Boolean) = library.setWatched(item, watched)

    /**
     * Opens [item] on the session, from the start with [fromStart], else where the household stopped in it, with the
     * tracks [initialAudio] and [initialSubtitle] pick: an audio track other than the default, and picture subtitles
     * drawn in.
     */
    @Throws(Exception::class)
    suspend fun playItem(item: MediaItem, fromStart: Boolean): PlaybackStream = opening {
        val state = withTimeoutOrNull(DETAIL_WAIT_MS) { library.detail(item.id).first { it !is ItemDetailState.Loading } }
        val detail = (state as? ItemDetailState.Ready)?.detail
        val choice = trackChoice(item)
        val audio = detail?.let { initialAudio(it, choice) }?.takeIf { it.index != detail.let(::defaultAudio)?.index }
        val picture = detail?.let { initialSubtitle(it, choice) }?.takeIf(::isPictureSubtitle)
        library.play(item, appleTvProfile, fromStart, audio?.index, picture?.index)
    }

    /** The tracks the viewer picked in [item]'s show, which its episodes start with; null for none or a movie. */
    fun trackChoice(item: MediaItem): TrackChoice? = item.showId?.let { viewModel.settings.value?.trackChoices?.get(it) }

    /** Keeps the audio track [audioIndex] and subtitle track [subtitleIndex] (negative for none) for [item]'s show. */
    fun rememberTracks(item: MediaItem, detail: MediaItemDetail, audioIndex: Int, subtitleIndex: Int) {
        val show = item.showId ?: return
        viewModel.setTrackChoice(show, trackChoice(detail, audioIndex.takeIf { it >= 0 }, subtitleIndex.takeIf { it >= 0 }))
    }

    /**
     * Plays [item] again from [positionMs] with the audio track [audioIndex] (the file's default when negative) and
     * the picture subtitle track [subtitleIndex] drawn in (none when negative).
     */
    @Throws(Exception::class)
    suspend fun switchItemTracks(item: MediaItem, positionMs: Long, audioIndex: Int, subtitleIndex: Int): PlaybackStream = opening {
        library.switchTracks(item, appleTvProfile, positionMs, audioIndex.takeIf { it >= 0 }, subtitleIndex.takeIf { it >= 0 })
    }

    /** [itemId]'s text subtitle track [index], or null when the server cannot give it. */
    @Throws(Exception::class)
    suspend fun subtitles(itemId: String, index: Int): Subtitles? = library.subtitles(itemId, index)

    /** The episode to offer when [item] ends, or null; see [LibraryViewModel.upNext]. */
    @Throws(Exception::class)
    suspend fun upNext(item: MediaItem): MediaItem? = library.upNext(item)

    /** Restarts a converted stream at [positionMs] in the item; a file played as it is comes back unchanged. */
    @Throws(Exception::class)
    suspend fun restartItemAt(positionMs: Long): PlaybackStream? = viewModel.sessions.restartAt(positionMs)

    /** The player is at [positionMs] in [itemId]; [durationMs] is 0 while the length is unknown. */
    fun itemProgress(itemId: String, positionMs: Long, durationMs: Long) = library.progress(itemId, positionMs, durationMs.takeIf { it > 0 })

    /** Playback of [itemId] stopped at [positionMs]; saved now, and the library's pages reload. */
    fun itemStopped(itemId: String, positionMs: Long, durationMs: Long) = library.stopped(itemId, positionMs, durationMs.takeIf { it > 0 })

    fun watchRecordings(onChange: (RecordingsState) -> Unit): Watch = watch(recordings.state, onChange)

    fun refreshRecordings() = recordings.refresh()

    /** Stops, cancels or deletes [recording]; false when the server refused or could not be reached. */
    @Throws(Exception::class)
    suspend fun deleteRecording(recording: DvrRecording): Boolean = recordings.delete(recording)

    /** Opens [recording] on the session, from the start with [fromStart], else where the household stopped in it. */
    @Throws(Exception::class)
    suspend fun playRecording(recording: DvrRecording, fromStart: Boolean): PlaybackStream =
        opening { recordings.play(recording, appleTvProfile, fromStart) }

    /** The player is at [positionMs] in [recordingId]; [durationMs] is 0 while the length is unknown. */
    fun recordingProgress(recordingId: String, positionMs: Long, durationMs: Long) =
        recordings.progress(recordingId, positionMs, durationMs.takeIf { it > 0 })

    /** Playback of [recordingId] stopped at [positionMs]; saved now, watched near the end. */
    fun recordingStopped(recordingId: String, positionMs: Long, durationMs: Long) =
        recordings.stopped(recordingId, positionMs, durationMs.takeIf { it > 0 })

    /** Which guide programs are set to record, on their own or by a series rule. */
    fun watchRecordingMarks(onChange: (RecordingMarks) -> Unit): Watch = watch(viewModel.recordings.marks, onChange)

    /** Whether [program] records, by [marks]. */
    fun recordingMark(marks: RecordingMarks, program: Program): RecordingMark = marks.of(program)

    /** True until [program] ends: only then can it still be recorded. */
    fun recordable(program: Program): Boolean = program.stop > Clock.System.now()

    /** Records [program], or cancels its recording; false when the server refused or could not be reached. */
    @Throws(Exception::class)
    suspend fun toggleRecording(program: Program): Boolean = viewModel.recordings.toggle(program)

    /** Records every airing of [program]'s title on its channel, or stops the series rule that takes it. */
    @Throws(Exception::class)
    suspend fun toggleSeries(program: Program): Boolean = viewModel.recordings.toggleSeries(program)

    /** Sets whether [rule] takes new episodes only and how many recordings it keeps (0 for all). */
    @Throws(Exception::class)
    suspend fun updateRule(rule: SeriesRule, newOnly: Boolean, keep: Int): Boolean = recordings.updateRule(rule, newOnly, keep)

    /** Stops [rule]: it and the recordings it scheduled go, what it recorded stays. */
    @Throws(Exception::class)
    suspend fun deleteRule(rule: SeriesRule): Boolean = recordings.deleteRule(rule)

    /** Whether recordings jump over the commercial breaks the server found. */
    fun setSkipCommercials(enabled: Boolean) {
        viewModel.setSkipCommercials(enabled)
    }

    /** Whether an episode's intro skips itself after a countdown on Skip intro. */
    fun setAutoSkipIntros(enabled: Boolean) {
        viewModel.setAutoSkipIntros(enabled)
    }

    /** Whether the break screen covers the ads a streaming channel marks, and whether their sound plays under it. */
    fun setLiveBreaks(mode: LiveBreaks) {
        viewModel.setLiveBreaks(mode)
    }

    /** Counts [ms] more of ads the break screen covered on [day], a local date as `2026-10-22`, toward Ads dodged today. */
    fun addAdsDodged(day: String, ms: Long) {
        viewModel.addAdsDodged(day, ms)
    }

    /**
     * The ad break the player shows, as the server places it from the segments AVPlayer fetched and [aheadMs], what it
     * has loaded past the picture; null outside one.
     */
    @Throws(Exception::class)
    suspend fun adBreak(aheadMs: Long): AdBreakNow? = viewModel.sessions.adBreak(aheadMs)

    /** Remembers [tab] as the one the app was last on, which the next start reopens. */
    fun setLastTab(tab: TopLevelTab) {
        viewModel.setLastTab(tab)
    }

    /** When [recording] aired, in milliseconds since the epoch. */
    fun recordingStartMs(recording: DvrRecording): Long = recording.startsAt.toEpochMilliseconds()

    /** When [recording]'s program ends, in milliseconds since the epoch. */
    fun recordingEndMs(recording: DvrRecording): Long = recording.endsAt.toEpochMilliseconds()

    /** The recording's description; the property clashes with `NSObject.description`, so Swift reads it here. */
    fun recordingSummary(recording: DvrRecording): String? = recording.description

    /** Claims a pairing [code] at [url] and saves the server it opens; null on success, else why it failed. */
    suspend fun pair(url: String, code: String): PairingFailure? = viewModel.pairAndWait(url, code)

    fun saveServer(url: String, apiToken: String) {
        viewModel.saveServer(url, apiToken)
    }

    fun refresh() = viewModel.refresh()

    fun toggleFavorite(guideId: String) {
        viewModel.toggleFavorite(guideId)
    }

    fun nowAndNext(lineup: Lineup, channel: Channel): NowNext =
        lineup.nowAndNext(channel, Clock.System.now()).let { (now, next) -> NowNext(now, next) }

    /** The program's start in milliseconds since the epoch, which Swift turns into a `Date`. */
    fun epochMs(program: Program): Long = program.start.toEpochMilliseconds()

    /** The program's end in milliseconds since the epoch. */
    fun endEpochMs(program: Program): Long = program.stop.toEpochMilliseconds()

    /** The program's description; the property clashes with `NSObject.description`, so Swift reads it here. */
    fun summary(program: Program): String? = program.description

    private fun <T> watch(flow: Flow<T>, onChange: (T) -> Unit): Watch = Watch(scope.launch { flow.collect { onChange(it) } })

    private companion object {
        /** How long playing an item waits for its page to learn whether it starts with drawn-in subtitles. */
        const val DETAIL_WAIT_MS = 5_000L

        /**
         * What AVPlayer plays as it is, as the iOS app asks for: H.264 and HEVC tagged `hvc1` in MP4, which the
         * server copies HEVC into for HLS, HEVC Main 10, and HDR10 and HLG, which an Apple TV 4K shows and tone-maps
         * otherwise. The system scrubber seeks only within the playlist, so a converted item comes as a playlist of all
         * of it.
         */
        val appleTvProfile = PlaybackProfile(
            containers = listOf("mp4"),
            videoCodecs = listOf("h264", "hevc"),
            audioCodecs = listOf("aac", "ac3", "eac3", "mp3", "alac", "flac"),
            maxHeight = 2160,
            hdr = true,
            deepColor = listOf("hevc"),
            wholePlaylist = true,
        )
    }
}
