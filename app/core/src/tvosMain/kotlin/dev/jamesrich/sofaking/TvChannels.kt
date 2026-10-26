package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AdBreakNow
import dev.jamesrich.sofaking.data.Airing
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.OnLaterKind
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.ProgramFacts
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

    /** What TMDB says about [program], through the server, for the break screen; null when nothing is known. */
    @Throws(Exception::class)
    suspend fun programFacts(program: Program): ProgramFacts?

    /**
     * On Later: the programs of [kind] coming up today and tomorrow on the channels [lineup] shows, soonest first, as
     * the server picks them; null when the server could not be read.
     */
    @Throws(Exception::class)
    suspend fun onLater(kind: OnLaterKind, lineup: Lineup): List<Airing>?
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

    @Throws(Exception::class)
    override suspend fun programFacts(program: Program): ProgramFacts? = viewModel.programFacts(program)

    @Throws(Exception::class)
    override suspend fun onLater(kind: OnLaterKind, lineup: Lineup): List<Airing>? {
        // Only the channels the viewer keeps; the server lists every enabled one.
        val channels = lineup.channels.associateBy { it.guideId }
        return viewModel.onLater(kind)?.mapNotNull { program ->
            channels[program.channelId]?.let { Airing(it, program) }
        }
    }
}
