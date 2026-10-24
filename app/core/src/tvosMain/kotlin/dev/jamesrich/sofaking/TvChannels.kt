package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AdBreakNow
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.ServerCapabilities
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The lineup and the live channel's session, as the Apple TV app watches them. */
interface TvChannels {
    fun watchState(onChange: (LineupState) -> Unit): Watch

    /** What the server offers beyond live TV; the Library tab shows when it serves libraries. */
    fun watchCapabilities(onChange: (ServerCapabilities) -> Unit): Watch

    fun watchSettings(onChange: (AppSettings) -> Unit): Watch

    /** Streams the server replaced under the held session; the player swaps to the new URL. */
    fun watchReplaced(onChange: (PlaybackStream) -> Unit): Watch

    /** Opens, or re-reads, a watch session for [channelId] and remembers it as the last channel. */
    @Throws(Exception::class)
    suspend fun watchChannel(channelId: String): PlaybackStream

    /** Releases the held session; [reopen] returns to it. */
    @Throws(Exception::class)
    suspend fun release()

    /**
     * Like an open, a reopen still running when the player leaves is canceled by
     * [stopWatching], so it holds nothing after.
     */
    @Throws(Exception::class)
    suspend fun reopen(): PlaybackStream?

    /**
     * Releases the held session for good, as leaving the player does. An open still running is canceled first, which
     * releases what it opened: Swift's task cancellation does not reach a Kotlin suspend call.
     */
    fun stopWatching()

    /**
     * The ad break the player shows, as the server places it from the segments AVPlayer fetched and [aheadMs], what it
     * has loaded past the picture; null outside one.
     */
    @Throws(Exception::class)
    suspend fun adBreak(aheadMs: Long): AdBreakNow?
}

internal class TvChannelCalls(private val parts: TvParts) : TvChannels {
    private val viewModel = parts.viewModel

    override fun watchState(onChange: (LineupState) -> Unit): Watch = parts.watch(viewModel.state, onChange)

    override fun watchCapabilities(onChange: (ServerCapabilities) -> Unit): Watch =
        parts.watch(viewModel.capabilities, onChange)

    override fun watchSettings(onChange: (AppSettings) -> Unit): Watch =
        parts.watch(viewModel.settings.filterNotNull(), onChange)

    override fun watchReplaced(onChange: (PlaybackStream) -> Unit): Watch =
        parts.watch(viewModel.sessions.replaced, onChange)

    @Throws(Exception::class)
    override suspend fun watchChannel(channelId: String): PlaybackStream = parts.opening {
        val stream = viewModel.sessions.tune(channelId, PlaybackIntent.WATCH)
        viewModel.setLastChannel(channelId)
        stream
    }

    @Throws(Exception::class)
    override suspend fun release() = viewModel.sessions.release()

    @Throws(Exception::class)
    override suspend fun reopen(): PlaybackStream? = parts.opening { viewModel.sessions.reopen() }

    override fun stopWatching() {
        val pending = parts.open
        parts.open = null
        parts.scope.launch {
            pending?.cancelAndJoin()
            viewModel.sessions.release(forget = true)
        }
    }

    @Throws(Exception::class)
    override suspend fun adBreak(aheadMs: Long): AdBreakNow? = viewModel.sessions.adBreak(aheadMs)
}
