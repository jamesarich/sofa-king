package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.DataStoreSettingsStore
import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.data.ServerCapabilities
import dev.jamesrich.sofaking.data.onAppleTv
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import kotlin.time.Clock

/** A running subscription from [SofaKingTv]; Swift cancels it when its view goes away. */
class Watch internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

/** What a channel row shows: the program on now and the one after it. */
data class NowNext(val now: Programme?, val next: Programme?)

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

    val viewModel = LineupViewModel(store = DataStoreSettingsStore(storage.settings), lineupCache = storage.lineupCache, client = storage.client)

    /** The media libraries, playing through the same session as the channels. */
    val library = LibraryViewModel(viewModel.api, viewModel.sessions)

    /** The server's recordings, playing through the same session; a delete refreshes the guide's record marks. */
    val recordings = RecordingsViewModel(viewModel.api, viewModel.sessions, changed = viewModel.recordings::refresh)

    fun watchState(onChange: (LineupState) -> Unit): Watch = watch(viewModel.state, onChange)

    /** What the server offers beyond live TV; the Library tab shows when it serves libraries. */
    fun watchCapabilities(onChange: (ServerCapabilities) -> Unit): Watch = watch(viewModel.capabilities, onChange)

    fun watchSettings(onChange: (AppSettings) -> Unit): Watch = watch(viewModel.settings.filterNotNull(), onChange)

    /** Streams the server replaced under the held session; the player swaps to the new URL. */
    fun watchReplaced(onChange: (PlaybackStream) -> Unit): Watch = watch(viewModel.sessions.replaced, onChange)

    /** Opens, or re-reads, a watch session for [channelId] and remembers it as the last channel. */
    @Throws(Exception::class)
    suspend fun watchChannel(channelId: String): PlaybackStream {
        val stream = viewModel.sessions.tune(channelId, PlaybackIntent.WATCH)
        viewModel.setLastChannel(channelId)
        return stream
    }

    /** Releases the held session; [reopen] returns to it. */
    @Throws(Exception::class)
    suspend fun release() = viewModel.sessions.release()

    @Throws(Exception::class)
    suspend fun reopen(): PlaybackStream? = viewModel.sessions.reopen()

    /** Releases the held session for good, as leaving the player does. */
    fun stopWatching() {
        scope.launch { viewModel.sessions.release(forget = true) }
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

    fun openGrid(libraryId: String, sort: LibrarySort, unwatched: Boolean) = library.openGrid(libraryId, sort, unwatched)

    fun loadMore() = library.loadMore()

    fun setWatched(item: MediaItem, watched: Boolean) = library.setWatched(item, watched)

    /** Opens [item] on the session, from the start with [fromStart], else where the household stopped in it. */
    @Throws(Exception::class)
    suspend fun playItem(item: MediaItem, fromStart: Boolean): PlaybackStream = library.play(item, appleTvProfile, fromStart)

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
    suspend fun playRecording(recording: DvrRecording, fromStart: Boolean): PlaybackStream = recordings.play(recording, appleTvProfile, fromStart)

    /** The player is at [positionMs] in [recordingId]; [durationMs] is 0 while the length is unknown. */
    fun recordingProgress(recordingId: String, positionMs: Long, durationMs: Long) =
        recordings.progress(recordingId, positionMs, durationMs.takeIf { it > 0 })

    /** Playback of [recordingId] stopped at [positionMs]; saved now, watched near the end. */
    fun recordingStopped(recordingId: String, positionMs: Long, durationMs: Long) =
        recordings.stopped(recordingId, positionMs, durationMs.takeIf { it > 0 })

    /** Whether recordings jump over the commercial breaks the server found. */
    fun setSkipCommercials(enabled: Boolean) {
        viewModel.setSkipCommercials(enabled)
    }

    /** When [recording] aired, in milliseconds since the epoch. */
    fun recordingStartMs(recording: DvrRecording): Long = recording.startsAt.toEpochMilliseconds()

    /** When [recording]'s program ends, in milliseconds since the epoch. */
    fun recordingEndMs(recording: DvrRecording): Long = recording.endsAt.toEpochMilliseconds()

    /** The recording's description; the property clashes with `NSObject.description`, so Swift reads it here. */
    fun recordingSummary(recording: DvrRecording): String? = recording.description

    /** Claims a pairing [code] at [url] and saves the server it opens; null on success, else why it failed. */
    suspend fun pair(url: String, code: String): PairingFailure? = viewModel.pair(url, code)

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
    fun epochMs(programme: Programme): Long = programme.start.toEpochMilliseconds()

    /** The program's end in milliseconds since the epoch. */
    fun endEpochMs(programme: Programme): Long = programme.stop.toEpochMilliseconds()

    /** The program's description; the property clashes with `NSObject.description`, so Swift reads it here. */
    fun summary(programme: Programme): String? = programme.description

    private fun <T> watch(flow: Flow<T>, onChange: (T) -> Unit): Watch = Watch(scope.launch { flow.collect { onChange(it) } })

    private companion object {
        /**
         * What AVPlayer plays as it is, as the iOS app asks for: H.264 and HEVC tagged `hvc1` in MP4, which the
         * server copies HEVC into for HLS, and HDR10 and HLG, which an Apple TV 4K shows and tone-maps otherwise.
         */
        val appleTvProfile = PlaybackProfile(
            containers = listOf("mp4"),
            videoCodecs = listOf("h264", "hevc"),
            audioCodecs = listOf("aac", "ac3", "eac3", "mp3", "alac", "flac"),
            maxHeight = 2160,
            hdr = true,
        )
    }
}
