package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.DataStoreSettingsStore
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.Programme
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
    // tvOS may purge any file while the app is closed and only Caches is guaranteed writable.
    private val storage = AppStorage.inDirectory(
        requireNotNull(
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

    val viewModel = LineupViewModel(store = DataStoreSettingsStore(storage.settings), cacheStore = storage.lineupCache)

    fun watchState(onChange: (LineupState) -> Unit): Watch = watch(viewModel.state, onChange)

    fun watchSettings(onChange: (AppSettings) -> Unit): Watch = watch(viewModel.settings.filterNotNull(), onChange)

    fun watchLoading(onChange: (Boolean) -> Unit): Watch = watch(viewModel.loading, onChange)

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

    fun saveServer(url: String, apiToken: String) {
        viewModel.saveServer(url, apiToken)
    }

    fun refresh() = viewModel.refresh()

    fun nowAndNext(lineup: Lineup, channel: Channel): NowNext =
        lineup.nowAndNext(channel, Clock.System.now()).let { (now, next) -> NowNext(now, next) }

    /** Milliseconds since the epoch, which Swift turns into a `Date`. */
    fun epochMs(programme: Programme): Long = programme.start.toEpochMilliseconds()

    fun stopEpochMs(programme: Programme): Long = programme.stop.toEpochMilliseconds()

    private fun <T> watch(flow: Flow<T>, onChange: (T) -> Unit): Watch = Watch(scope.launch { flow.collect { onChange(it) } })
}
