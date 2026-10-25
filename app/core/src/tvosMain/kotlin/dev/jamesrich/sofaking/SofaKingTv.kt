package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.DataStoreSettingsStore
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.ServerConnection
import dev.jamesrich.sofaking.data.ViewerSync
import dev.jamesrich.sofaking.data.onAppleTv
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/** A running subscription from [SofaKingTv]; Swift cancels it when its view goes away. */
class Watch internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

/** What a channel row shows: the program on now and the one after it. */
data class NowNext(val now: Program?, val next: Program?)

/**
 * The shared data layer for the SwiftUI Apple TV app, which cannot use Compose: the one [LineupViewModel] and its
 * playback session, with flows handed to Swift as callbacks on the main thread and suspend calls as Swift `async`.
 * Create one per process, from the main thread. Its calls come by area, from [TvChannels] to [TvText].
 */
class SofaKingTv private constructor(private val parts: TvParts) :
    TvChannels by TvChannelCalls(parts),
    TvLibrary by TvLibraryCalls(parts),
    TvItemPlayback by TvItemCalls(parts),
    TvRecordings by TvRecordingCalls(parts),
    TvRecordingMarks by TvRecordingMarkCalls(parts),
    TvWatchlist by TvWatchlistCalls(parts),
    TvSettings by TvSettingCalls(parts),
    TvText by TvTextCalls {
    constructor() : this(TvParts())

    val viewModel: LineupViewModel get() = parts.viewModel

    /** The media libraries, playing through the same session as the channels. */
    val library: LibraryViewModel get() = parts.library

    /** The server's recordings, the same list the guide's record marks come from, playing through the same session. */
    val recordings: RecordingsViewModel get() = parts.recordings
}

/**
 * What every area of [SofaKingTv] works on: the app's storage, its ViewModels and the main-thread scope that hands
 * their flows to Swift.
 */
@OptIn(ExperimentalForeignApi::class)
internal class TvParts {
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
    val scope: CoroutineScope = MainScope()

    private val store = DataStoreSettingsStore(storage.settings)

    /** The server Settings names, its one playback session and the guide's record marks, for every ViewModel below. */
    private val connection = ServerConnection(store.settings, scope, viewerSync = ViewerSync(store, storage.client))

    val viewModel =
        LineupViewModel(
            store = store,
            lineupCache = storage.lineupCache,
            connection = connection,
            client = storage.client,
        )

    val library = LibraryViewModel(connection.api, connection.sessions, watchlist = connection.watchlist)

    val recordings = RecordingsViewModel(connection.recordings, connection.sessions)

    /** The channel or item open running now; touched only on the main thread, as every call here is. */
    var open: Job? = null

    /** Hands [flow] to [onChange] on the main thread until the [Watch] is canceled. */
    fun <T> watch(flow: Flow<T>, onChange: (T) -> Unit): Watch = Watch(
        scope.launch {
            flow.collect { onChange(it) }
        },
    )

    /** Runs the open [block] as [open], so leaving the player cancels it: Swift's cancellation does not reach it. */
    suspend fun <T> opening(block: suspend () -> T): T {
        val job = scope.async { block() }
        open = job
        try {
            return job.await()
        } finally {
            if (open === job) open = null
        }
    }
}

/**
 * What AVPlayer plays as it is, as the iOS app asks for: H.264 and HEVC tagged `hvc1` in MP4, which the server
 * copies HEVC into for HLS, HEVC Main 10, and HDR10 and HLG, which an Apple TV 4K shows and tone-maps otherwise. The
 * system scrubber seeks only within the playlist, so a converted item comes as a playlist of all of it.
 */
internal val appleTvProfile = PlaybackProfile(
    containers = listOf("mp4"),
    videoCodecs = listOf("h264", "hevc"),
    audioCodecs = listOf("aac", "ac3", "eac3", "mp3", "alac", "flac"),
    maxHeight = 2160,
    hdr = true,
    deepColor = listOf("hevc"),
    wholePlaylist = true,
)
