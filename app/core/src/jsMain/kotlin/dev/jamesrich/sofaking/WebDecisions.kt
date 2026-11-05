package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.BreakMarks
import dev.jamesrich.sofaking.data.SofaKingException
import dev.jamesrich.sofaking.data.VodSeekPlan
import dev.jamesrich.sofaking.data.converterRetryDelayMs as converterRetryDelayMsOf
import dev.jamesrich.sofaking.data.parseBreakMark as parseBreakMarkOf
import dev.jamesrich.sofaking.data.parsePairingFragment as parsePairingFragmentOf
import dev.jamesrich.sofaking.data.reconnectDelayMs as reconnectDelayMsOf
import dev.jamesrich.sofaking.data.vodSeekPlan as vodSeekPlanOf

/** Where a seek lands: a conversion restarted at [ms] in the item when [restart], else [ms] in the stream playing. */
@JsExport
class WebSeek(val restart: Boolean, val ms: Double)

/** The ad break the picture is in; [remainingMs] is null while the playlist does not say how long it has left. */
@JsExport
class WebBreak(val remainingMs: Double?)

/** An ad break on the playlist's program date time clock; [endMs] is null while its length is unknown. */
@JsExport
class WebBreakMark(val id: String, val startMs: Double, val endMs: Double?, val planned: Boolean)

/** The ad marks of a live playlist's recent windows, which the player fills from hls.js's date ranges. */
@JsExport
class WebBreakMarks {
    private val marks = BreakMarks()

    /** Adds the mark an ad `#EXT-X-DATERANGE` [tag] holds; any other tag is ignored. */
    fun addLine(tag: String) = marks.addLines(listOf(tag))

    /** The break covering [wallClockMs], milliseconds since the epoch, or null outside every mark. */
    fun at(wallClockMs: Double): WebBreak? =
        marks.at(wallClockMs.toLong())?.let { WebBreak(it.remainingMs?.toDouble()) }
}

/** The decisions every client shares, which the page asks for instead of keeping a copy of its own. */
@JsExport
class WebDecisions internal constructor(private val parts: WebParts) {
    /** How long to wait before reconnect [attempt] (0-based) after a stream fails or ends, in milliseconds. */
    fun reconnectDelayMs(attempt: Int): Double = reconnectDelayMsOf(attempt).toDouble()

    /** True when [error], from an open or a restart, is the server saying every converter is busy. */
    fun isConverterBusy(error: Any?): Boolean = (error as? SofaKingException)?.code == Wire.ErrorCode.CAPACITY_EXHAUSTED

    /** How long to wait before asking again after every converter was busy, [waitedMs] in; null when to give up. */
    fun converterRetryDelayMs(waitedMs: Double): Double? = converterRetryDelayMsOf(waitedMs.toLong())?.toDouble()

    /**
     * Where a seek to [targetMs] in the item lands for the held item stream: [writtenMs] is the stream's own length so
     * far (null when unknown) and [itemDurationMs] the item's.
     */
    fun vodSeek(targetMs: Double, writtenMs: Double?, itemDurationMs: Double?): WebSeek {
        val stream = parts.viewModel.sessions.current.value ?: return WebSeek(false, targetMs)
        val plan = vodSeekPlanOf(stream, targetMs.toLong(), writtenMs?.toLong(), itemDurationMs?.toLong())
        return when (plan) {
            is VodSeekPlan.InStream -> WebSeek(restart = false, ms = plan.streamMs.toDouble())
            is VodSeekPlan.Restart -> WebSeek(restart = true, ms = plan.itemMs.toDouble())
        }
    }

    /** The mark an ad `#EXT-X-DATERANGE` [line] holds, or null when it is another tag. */
    fun parseBreakMark(line: String): WebBreakMark? = parseBreakMarkOf(line)?.let {
        WebBreakMark(it.id, it.startMs.toDouble(), it.endMs?.toDouble(), it.planned)
    }

    fun breakMarks(): WebBreakMarks = WebBreakMarks()
}

@JsExport
class WebPairing(val serverUrl: String, val code: String)

/** Reading what the page is given. */
@JsExport
class WebText {
    /**
     * The `#pair=DIGITS` fragment the admin portal's Open the web app adds to the page's address, served at
     * [pageOrigin]; null for any other fragment or a code that is not six digits.
     */
    fun parsePairingFragment(fragment: String, pageOrigin: String): WebPairing? =
        parsePairingFragmentOf(fragment, pageOrigin)?.let { WebPairing(it.serverUrl, it.code) }
}
