package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.Airing
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.OnLaterKind
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.ProgramFacts

/** What the server says about the guide's programs beyond the lineup: TMDB's facts and On Later. */
interface TvGuide {
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

internal class TvGuideCalls(parts: TvParts) : TvGuide {
    private val viewModel = parts.viewModel

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
