package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.Airing
import dev.jamesrich.sofaking.data.BreakFact
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.ProgramFacts
import dev.jamesrich.sofaking.data.airingsOf
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import dev.jamesrich.sofaking.data.breakFacts as breakFactsOf

/**
 * What Swift reads off the guide's and the recordings' values: times as milliseconds since the epoch, which it turns
 * into a `Date`, and descriptions, whose property clashes with `NSObject.description`.
 */
interface TvText {
    fun nowAndNext(lineup: Lineup, channel: Channel): NowNext

    /** The facts the break screen turns through while [now] is on, before [next]; see [breakFacts]. */
    fun breakFacts(now: Program?, next: Program?, facts: ProgramFacts?): List<BreakFact>

    /** The other airings of [program] in [lineup], from the one on now onwards, soonest first. */
    fun airings(lineup: Lineup, program: Program): List<Airing>

    /** The program's start in milliseconds since the epoch, which Swift turns into a `Date`. */
    fun epochMs(program: Program): Long

    /** The program's end in milliseconds since the epoch. */
    fun endEpochMs(program: Program): Long

    /** The program's description; the property clashes with `NSObject.description`, so Swift reads it here. */
    fun summary(program: Program): String?

    /** When [recording] aired, in milliseconds since the epoch. */
    fun recordingStartMs(recording: DvrRecording): Long

    /** When [recording]'s program ends, in milliseconds since the epoch. */
    fun recordingEndMs(recording: DvrRecording): Long

    /** The recording's description; the property clashes with `NSObject.description`, so Swift reads it here. */
    fun recordingSummary(recording: DvrRecording): String?
}

internal object TvTextCalls : TvText {
    override fun nowAndNext(lineup: Lineup, channel: Channel): NowNext =
        lineup.nowAndNext(channel, Clock.System.now()).let { (now, next) -> NowNext(now, next) }

    override fun breakFacts(now: Program?, next: Program?, facts: ProgramFacts?): List<BreakFact> =
        breakFactsOf(now, next, facts)

    override fun airings(lineup: Lineup, program: Program): List<Airing> = lineup.airingsOf(program, Clock.System.now())

    override fun epochMs(program: Program): Long = program.start.toEpochMilliseconds()

    override fun endEpochMs(program: Program): Long = program.stop.toEpochMilliseconds()

    override fun summary(program: Program): String? = program.description

    override fun recordingStartMs(recording: DvrRecording): Long = recording.startsAt.toEpochMilliseconds()

    override fun recordingEndMs(recording: DvrRecording): Long = recording.endsAt.toEpochMilliseconds()

    override fun recordingSummary(recording: DvrRecording): String? = recording.description
}
