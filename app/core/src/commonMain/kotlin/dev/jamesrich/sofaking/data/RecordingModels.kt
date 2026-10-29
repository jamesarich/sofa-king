package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire
import kotlin.time.Instant

/** Where a recording is; [OTHER] stands for a state a newer server added. */
enum class RecordingState(internal val wire: String?) {
    SCHEDULED(Wire.RecordingState.SCHEDULED),
    RECORDING(Wire.RecordingState.RECORDING),
    COMPLETED(Wire.RecordingState.COMPLETED),
    FAILED(Wire.RecordingState.FAILED),
    OTHER(null),
    ;

    internal companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wire == value } ?: OTHER
    }
}

/**
 * Why a recording failed or ended early, from the server's reason code; [OTHER] stands for a code this app doesn't
 * know, which includes a newer server's and an unexpected error's.
 */
enum class RecordingFailure {
    /** Its window passed before it started: the server was off, or busy with other recordings. */
    MISSED,

    /** The recordings disk filled up. */
    DISK_FULL,

    /** The channel sent nothing to record. */
    NO_MEDIA,

    /** The channel's source couldn't be reached, or dropped and never came back. */
    SOURCE,

    /** The channel left the lineup. */
    CHANNEL_GONE,

    /** Someone stopped it, or the server shut down, before its end. */
    STOPPED,

    OTHER,
    ;

    internal companion object {
        fun of(reason: String?): RecordingFailure = when (reason) {
            "MISSED" -> MISSED

            "DISK_FULL" -> DISK_FULL

            "NO_MEDIA" -> NO_MEDIA

            "SOURCE_UNAVAILABLE", "SESSION_ENDED", "UPSTREAM_UNAVAILABLE", "UPSTREAM_STATUS", "NO_SOURCES",
            "SOURCE_NOT_FOUND", "NO_OTHER_SOURCE",
            -> SOURCE

            "CHANNEL_NOT_FOUND", "LINEUP_ENTRY_NOT_FOUND" -> CHANNEL_GONE

            "STOPPED" -> STOPPED

            else -> OTHER
        }
    }
}

/** A commercial break the server found, from [startMs] to [endMs] after the start of the file. */
data class CommercialBreak(val startMs: Long, val endMs: Long)

/** A program the server recorded, is recording or will record, as the guide had it when it was scheduled. */
data class DvrRecording(
    val id: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val episodeTitle: String?,
    val description: String?,
    val season: Int?,
    val episode: Int?,
    val imageUrl: String?,
    val startsAt: Instant,
    val endsAt: Instant,
    val state: RecordingState,
    /** Why it failed or was cut short. */
    val reason: String?,
    val bytes: Long,
    /** Cleanup never deletes it. */
    val protected: Boolean,
    /** Where the household stopped in it. */
    val positionMs: Long,
    val watched: Boolean,
    val breaks: List<CommercialBreak>,
    /** A scheduled recording that loses its slot to others: it will not record unless one frees up. */
    val conflict: Boolean = false,
) {
    /** True once something is written: a running recording plays as it grows. */
    val playable: Boolean get() = state == RecordingState.RECORDING || state == RecordingState.COMPLETED

    /** Why it failed, once it has; null in every other state. */
    val failure: RecordingFailure? get() = if (state == RecordingState.FAILED) RecordingFailure.of(reason) else null

    /** Why a kept recording ended before its program did; null when it ran to the end, or isn't finished. */
    val endedEarly: RecordingFailure?
        get() = if (state == RecordingState.COMPLETED && reason != null) RecordingFailure.of(reason) else null

    /** Started and not finished: playing resumes rather than starts. */
    val inProgress: Boolean get() = positionMs > 0 && !watched
}

/**
 * A series rule: every airing of [title], on [channelId] or on any channel when null. [newOnly] skips repeats, and
 * [keep] is how many finished recordings it keeps, newest first, 0 for all. A rule for a team or keywords has its
 * [kind] (`Wire.RuleMatchKind`) and [terms]; [title] then only names it.
 */
data class SeriesRule(
    val id: String,
    val title: String,
    val channelId: String?,
    val newOnly: Boolean,
    val keep: Int,
    val kind: String = Wire.RuleMatchKind.TITLE,
    val terms: List<String> = emptyList(),
)

internal fun RecordingRuleDto.toModel() = SeriesRule(
    id,
    title,
    channelId,
    newOnly,
    keep,
    match?.kind ?: Wire.RuleMatchKind.TITLE,
    match?.terms.orEmpty(),
)

/** What the recordings folder's disk holds free and in all. */
data class RecordingSpace(val freeBytes: Long, val totalBytes: Long)

internal fun RecordingDto.toModel(): DvrRecording? {
    val start = Instant.parseOrNull(startsAt) ?: return null
    val end = Instant.parseOrNull(endsAt) ?: return null
    return DvrRecording(
        id = id,
        channelId = channelId,
        channelName = channelName,
        title = title,
        episodeTitle = episodeTitle?.takeIf { it.isNotBlank() },
        description = description?.takeIf { it.isNotBlank() },
        season = season,
        episode = episode,
        imageUrl = image?.takeIf { it.isNotBlank() },
        startsAt = start,
        endsAt = end,
        state = RecordingState.fromWire(state),
        reason = reason?.takeIf { it.isNotBlank() },
        bytes = bytes,
        protected = protected,
        positionMs = positionMs,
        watched = watched,
        breaks = breaks.filter {
            it.endMs > it.startMs
        }.sortedBy { it.startMs }.map { CommercialBreak(it.startMs, it.endMs) },
        conflict = conflict,
    )
}
