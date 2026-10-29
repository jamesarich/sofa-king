package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.RuleMatch
import dev.jamesrich.sofaking.contract.Wire
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// What the app asks of the server beyond "record this": padding, a window on a channel and a keyword rule.

private const val MINUTE = 60
private const val FIVE = 5 * MINUTE
private const val FIFTEEN = 15 * MINUTE
private const val HALF_HOUR = 30 * MINUTE

/**
 * How long a series rule records before and after each program. [DEFAULT] is the server's own, which a new rule
 * leaves unset; [OVERRUN] is for live sports that run long.
 */
enum class RecordingPadding(val startSeconds: Int, val endSeconds: Int) {
    DEFAULT(MINUTE, 2 * MINUTE),
    NONE(0, 0),
    FIVE_MINUTES(FIVE, FIVE),
    FIFTEEN_MINUTES(FIFTEEN, FIFTEEN),
    OVERRUN(MINUTE, HALF_HOUR),
    ;

    /** The next choice in the list, back to the first after the last. */
    fun next(): RecordingPadding = entries[(ordinal + 1) % entries.size]

    companion object {
        /** The choice that pads by exactly these seconds, or null for a padding set elsewhere. */
        fun of(startSeconds: Int, endSeconds: Int): RecordingPadding? =
            entries.firstOrNull { it.startSeconds == startSeconds && it.endSeconds == endSeconds }
    }
}

/** What a new series rule takes besides the title: its [padding] and whether it records on [anyChannel]. */
data class SeriesOptions(val padding: RecordingPadding = RecordingPadding.DEFAULT, val anyChannel: Boolean = false)

/** A series rule for [program]'s title; a default padding is left to the server. */
internal fun seriesRule(program: Program, options: SeriesOptions): CreateRecordingRuleDto {
    val padded = options.padding != RecordingPadding.DEFAULT
    return CreateRecordingRuleDto(
        title = program.title.trim(),
        channelId = program.channelId.takeUnless { options.anyChannel },
        startPaddingSeconds = options.padding.startSeconds.takeIf { padded },
        endPaddingSeconds = options.padding.endSeconds.takeIf { padded },
    )
}

/** How long "record this channel" runs from now, for a channel with no guide to pick a program from. */
enum class RecordingWindow(val length: Duration) {
    HALF_HOUR(30.minutes),
    ONE_HOUR(1.hours),
    TWO_HOURS(2.hours),
}

/** Records [channelId] from [now] for [window], whatever the guide says; the server names it after the channel. */
internal fun windowRecording(channelId: String, window: RecordingWindow, now: Instant): ScheduleRecordingDto =
    ScheduleRecordingDto(channelId, now.toString(), (now + window.length).toString())

/** The longest term the server takes. */
private const val MAX_TERM = 100

/** A keyword shorter than this would match nearly every program. */
private const val MIN_TERM = 2

/**
 * [query] as a keyword rule's one term: trimmed, spaces collapsed and cut to what the server takes; null when it is
 * too short to be a keyword.
 */
fun keywordTerm(query: String): String? =
    query.trim().split(Regex("\\s+")).joinToString(" ").take(MAX_TERM).trim().takeIf { it.length >= MIN_TERM }

/** A rule that records every program naming [term] as a word or phrase in its title, episode title or description. */
internal fun keywordRule(term: String): CreateRecordingRuleDto =
    CreateRecordingRuleDto(title = term, match = RuleMatch(Wire.RuleMatchKind.KEYWORD, listOf(term)))
