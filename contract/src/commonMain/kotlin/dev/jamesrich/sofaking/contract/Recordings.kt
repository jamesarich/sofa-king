package dev.jamesrich.sofaking.contract

import kotlinx.serialization.Serializable

// The DVR under /api/v1/recordings and /api/v1/recording-rules; the same compatibility rules as LiveTv.kt apply.

/**
 * One recording, scheduled, running or kept. The programme fields are the guide's when it was scheduled;
 * [startsAt] and [endsAt] are the programme's own times, and the recording runs from [startPaddingSeconds] before
 * to [endPaddingSeconds] after them. [state] is a [Wire.RecordingState] value and [reason] says why one failed.
 * [path] is relative to the recordings folder once the file exists. [conflict] marks a scheduled recording that
 * overlaps more recordings than the server has playback slots. [ruleId] names the series rule that scheduled it.
 * [commercials] is a [Wire.CommercialScan] value once the server looks for commercial breaks, and [breaks] are the
 * ones it found, in the order they play.
 */
@Serializable data class Recording(
    val id: String,
    val channelId: String,
    val channelName: String = "",
    val title: String,
    val episodeTitle: String? = null,
    val description: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val movie: Boolean = false,
    val year: Int? = null,
    val image: String? = null,
    val startsAt: String,
    val endsAt: String,
    val startPaddingSeconds: Int = 0,
    val endPaddingSeconds: Int = 0,
    val state: String,
    val reason: String? = null,
    val ruleId: String? = null,
    val path: String? = null,
    val bytes: Long = 0,
    val protected: Boolean = false,
    val conflict: Boolean = false,
    val positionMs: Long = 0,
    val watched: Boolean = false,
    val commercials: String? = null,
    val breaks: List<RecordingBreak> = emptyList(),
)

/** A commercial break in a recording, from [startMs] to [endMs] after the start of the file. */
@Serializable data class RecordingBreak(val startMs: Long, val endMs: Long)

/** The recordings folder's disk: what it holds free and in all, and what kept recordings use of it. */
@Serializable data class RecordingStorage(val freeBytes: Long = 0, val totalBytes: Long = 0, val usedBytes: Long = 0)

/** `GET /api/v1/recordings`: every recording the server knows, soonest scheduled first, then newest kept. */
@Serializable data class RecordingList(
    val recordings: List<Recording> = emptyList(),
    val storage: RecordingStorage = RecordingStorage(),
)

/**
 * `POST /api/v1/recordings`: record the programme on [channelId] that starts at [startsAt]. With [endsAt], record
 * that window instead, whatever the guide says, under [title] (the channel's name when null); a channel without a
 * guide can be recorded only this way.
 */
@Serializable data class ScheduleRecording(
    val channelId: String,
    val startsAt: String,
    val endsAt: String? = null,
    val title: String? = null,
)

/** `PATCH /api/v1/recordings/{id}`: each set field replaces the stored one. */
@Serializable
data class UpdateRecording(val protected: Boolean? = null, val positionMs: Long? = null, val watched: Boolean? = null)

/**
 * A series rule: record every airing whose title is [title] (ignoring case), on [channelId] or on any channel when
 * null. [newOnly] skips airings the guide marks as repeats. [keep] is how many finished recordings the rule keeps,
 * newest first; 0 keeps all. When recordings conflict, a higher [priority] wins the slot.
 */
@Serializable data class RecordingRule(
    val id: String,
    val title: String,
    val channelId: String? = null,
    val newOnly: Boolean = true,
    val keep: Int = 0,
    val startPaddingSeconds: Int = 60,
    val endPaddingSeconds: Int = 120,
    val priority: Int = 0,
    val createdAt: String = "",
)

/** `GET /api/v1/recording-rules`. */
@Serializable data class RecordingRuleList(val rules: List<RecordingRule> = emptyList())

/** `POST /api/v1/recording-rules`; unset paddings take the server's defaults. */
@Serializable data class CreateRecordingRule(
    val title: String,
    val channelId: String? = null,
    val newOnly: Boolean = true,
    val keep: Int = 0,
    val startPaddingSeconds: Int? = null,
    val endPaddingSeconds: Int? = null,
    val priority: Int = 0,
)

/**
 * `PATCH /api/v1/recording-rules/{id}`: each set field replaces the stored one; [anyChannel] true clears the channel.
 */
@Serializable data class UpdateRecordingRule(
    val channelId: String? = null,
    val anyChannel: Boolean? = null,
    val newOnly: Boolean? = null,
    val keep: Int? = null,
    val startPaddingSeconds: Int? = null,
    val endPaddingSeconds: Int? = null,
    val priority: Int? = null,
)
