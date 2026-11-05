package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.DataStoreSettingsStore
import dev.jamesrich.sofaking.data.HouseholdSync
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.ServerConnection
import dev.jamesrich.sofaking.data.ViewerSync
import dev.jamesrich.sofaking.data.inBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A running subscription from [SofaKingWeb]; the page cancels it when its view goes away. */
@JsExport
class Watch internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

/**
 * The shared data layer for the web client in `app/web`, which plays through hls.js and draws with the DOM: the one
 * [LineupViewModel] and its playback session, with flows handed to TypeScript as callbacks and calls as `Promise`s.
 * Create one per page. Its calls come by area, from [WebChannels] to [WebText], on the properties below.
 */
@JsExport
class SofaKingWeb {
    private val parts = WebParts()

    val channels = WebChannels(parts)
    val guide = WebGuide(parts)
    val library = WebLibrary(parts)
    val item = WebItemPlayback(parts)
    val settings = WebSettings(parts)
    val decisions = WebDecisions(parts)
    val text = WebText()
}

/** What every area of [SofaKingWeb] works on: the browser's storage, the ViewModels and the page's scope. */
internal class WebParts {
    private val storage = AppStorage.inBrowser()
    val scope: CoroutineScope = MainScope()

    private val store = DataStoreSettingsStore(storage.settings)

    val connection = ServerConnection(
        store.settings,
        scope,
        playbackProtocols = listOf(Wire.Protocol.HLS),
        viewerSync = ViewerSync(store, storage.client),
        householdSync = HouseholdSync(store),
    )

    val viewModel = LineupViewModel(
        store = store,
        lineupCache = storage.lineupCache,
        connection = connection,
        client = storage.client,
    )

    val library = LibraryViewModel(
        connection.api,
        connection.sessions,
        watchlist = connection.watchlist,
        kidsMode = store.settings.map { it.kidsMode },
    )

    /** The items the page has been handed, by id, so a call names an item by its id and not a copy of it. */
    private val items = HashMap<String, MediaItem>()

    fun remember(item: MediaItem): MediaItem {
        items[item.id] = item
        return item
    }

    fun item(id: String): MediaItem = items[id] ?: error("Unknown item $id")

    /** The channel or item opening now; canceled when the player leaves, which releases what it opened. */
    var open: Job? = null

    /** Runs the open [block] as [open], so a stop can cancel it. */
    suspend fun <T> opening(block: suspend () -> T): T {
        val job = scope.async { block() }
        open = job
        try {
            return job.await()
        } finally {
            if (open === job) open = null
        }
    }

    /** Hands [flow] to [onChange] until the [Watch] is canceled. */
    fun <T> watch(flow: Flow<T>, onChange: (T) -> Unit): Watch = Watch(
        scope.launch {
            flow.collect { onChange(it) }
        },
    )
}

/**
 * What a current browser's `<video>` plays as it is: MP4 and WebM with H.264, VP8 or VP9. The server converts or
 * copies anything else into HLS, which hls.js plays.
 */
internal val webProfile = PlaybackProfile(
    containers = listOf("mp4", "webm"),
    videoCodecs = listOf("h264", "vp8", "vp9"),
    audioCodecs = listOf("aac", "mp3", "opus", "vorbis", "flac"),
    maxHeight = 2160,
)
