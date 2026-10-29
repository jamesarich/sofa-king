package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AUTO_SKIP_MS
import dev.jamesrich.sofaking.data.CommercialBreak
import dev.jamesrich.sofaking.data.IntroSpan
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.SegmentSkip
import dev.jamesrich.sofaking.data.Skips
import dev.jamesrich.sofaking.data.TimeSpan
import dev.jamesrich.sofaking.data.VodMarker
import dev.jamesrich.sofaking.data.VodMarkerKind
import dev.jamesrich.sofaking.data.VodSeekPlan
import dev.jamesrich.sofaking.data.breakEndAt as breakEndAtOf
import dev.jamesrich.sofaking.data.breakToSkip as breakToSkipOf
import dev.jamesrich.sofaking.data.converterRetryDelayMs as converterRetryDelayMsOf
import dev.jamesrich.sofaking.data.creditsRunToEnd as creditsRunToEndOf
import dev.jamesrich.sofaking.data.durationFromRanges as durationFromRangesOf
import dev.jamesrich.sofaking.data.loadedAheadMs as loadedAheadMsOf
import dev.jamesrich.sofaking.data.segmentToSkip as segmentToSkipOf
import dev.jamesrich.sofaking.data.vodSeekPlan as vodSeekPlanOf

/** Where a seek lands: a conversion restarted at [ms] in the item when [restart], else [ms] in the stream playing. */
data class TvSeek(val restart: Boolean, val ms: Long)

/** The library and recordings player's decisions, shared with every other client, which the Apple TV app asks for. */
interface TvVodDecisions {
    /** How many seconds Skip intro and Skip credits count down before skipping themselves, with auto skip on. */
    val autoSkipSeconds: Int

    /**
     * Where a seek to [targetMs] in the item lands for [stream]: [writtenMs] is the stream's own length so far (null
     * when unknown) and [itemDurationMs] the item's (null when unknown). A target short of the end and inside what
     * the server has converted seeks in the stream; anything else restarts the conversion there.
     */
    fun vodSeek(stream: PlaybackStream, targetMs: Long, writtenMs: Long?, itemDurationMs: Long?): TvSeek

    /**
     * How many milliseconds to wait before asking again after the server answered that every converter is busy, having
     * asked for [waitedMs] already; null once it is time to show the failure.
     */
    fun converterRetryDelayMs(waitedMs: Long): Long?

    /** The end of the break in [breaks] playback ran into between [previousMs] and [nowMs], to skip to; else null. */
    fun breakToSkip(breaks: List<CommercialBreak>, previousMs: Long, nowMs: Long): Long?

    /** The end of the break in [breaks] that [positionMs] is in; null outside them. */
    fun breakEndAt(breaks: List<CommercialBreak>, positionMs: Long): Long?

    /** The intro or credits [positionMs] is in, unless [skips] turns that kind off, with how [skips] meets it. */
    fun segmentToSkip(intro: IntroSpan?, credits: IntroSpan?, positionMs: Long, skips: Skips): SegmentSkip?

    /** Whether credits ending at [creditsEndMs] run to the end of an item [durationMs] long (0 while unknown). */
    fun creditsRunToEnd(creditsEndMs: Long, durationMs: Long): Boolean

    /** What is loaded past [nowSeconds], in milliseconds, given the player's loaded [ranges]; null outside them. */
    fun loadedAheadMs(nowSeconds: Double, ranges: List<TimeSpan>): Long?

    /** The item's length in milliseconds from the player's [durationSeconds] or else its [seekable] ranges. */
    fun durationFromRanges(durationSeconds: Double?, seekable: List<TimeSpan>): Long?
}

internal class TvVodDecisionCalls : TvVodDecisions {
    override val autoSkipSeconds: Int = (AUTO_SKIP_MS / MS_PER_SECOND).toInt()

    override fun vodSeek(stream: PlaybackStream, targetMs: Long, writtenMs: Long?, itemDurationMs: Long?): TvSeek =
        when (val plan = vodSeekPlanOf(stream, targetMs, writtenMs, itemDurationMs)) {
            is VodSeekPlan.InStream -> TvSeek(restart = false, ms = plan.streamMs)
            is VodSeekPlan.Restart -> TvSeek(restart = true, ms = plan.itemMs)
        }

    override fun converterRetryDelayMs(waitedMs: Long): Long? = converterRetryDelayMsOf(waitedMs)

    override fun breakToSkip(breaks: List<CommercialBreak>, previousMs: Long, nowMs: Long): Long? =
        breakToSkipOf(breaks.markers(), previousMs, nowMs)

    override fun breakEndAt(breaks: List<CommercialBreak>, positionMs: Long): Long? =
        breakEndAtOf(breaks.markers(), positionMs)

    override fun segmentToSkip(intro: IntroSpan?, credits: IntroSpan?, positionMs: Long, skips: Skips): SegmentSkip? =
        segmentToSkipOf(
            listOfNotNull(
                intro?.let { VodMarker(it.startMs, it.endMs, VodMarkerKind.INTRO) },
                credits?.let { VodMarker(it.startMs, it.endMs, VodMarkerKind.CREDITS) },
            ),
            positionMs,
            skips,
        )

    override fun creditsRunToEnd(creditsEndMs: Long, durationMs: Long): Boolean =
        creditsRunToEndOf(creditsEndMs, durationMs)

    override fun loadedAheadMs(nowSeconds: Double, ranges: List<TimeSpan>): Long? = loadedAheadMsOf(nowSeconds, ranges)

    override fun durationFromRanges(durationSeconds: Double?, seekable: List<TimeSpan>): Long? =
        durationFromRangesOf(durationSeconds, seekable)

    private fun List<CommercialBreak>.markers() = map { VodMarker(it.startMs, it.endMs, VodMarkerKind.BREAK) }
}

private const val MS_PER_SECOND = 1_000L
