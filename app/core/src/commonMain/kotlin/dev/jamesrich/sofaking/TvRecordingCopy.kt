package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.RecordingFailure
import dev.jamesrich.sofaking.data.RecordingGroup
import dev.jamesrich.sofaking.data.RecordingState
import dev.jamesrich.sofaking.data.SeriesRule

/** The Recordings tab's words in the SwiftUI Apple TV app; see [TvCopy]. */
object TvRecordingCopy {
    private val table = CopyTable()

    /** Resource name to the text this object holds for it. */
    val twins: Map<String, String> get() = table.twins

    private fun twin(key: String, text: String) = table.twin(key, text)

    val loading = twin("recordings_loading", "Sofa King close, loading recordings…")
    val unavailable = twin("recordings_unavailable", "This server doesn't record")
    val empty = twin("recordings_empty", "Nothing recorded yet")
    val emptyHint = twin("recordings_empty_hint", "Open a program's details in the guide and choose Record.")
    val changeFailed = twin("recordings_change_failed", "The server didn't take that change. Try again.")
    val fromStart = twin("recordings_from_start", "From start")
    val watched = twin("recordings_watched", "Watched")
    val series = twin("recordings_series", "Series")
    val skipCommercials = twin("recordings_skip_commercials", "Skip commercials")
    val skipCommercialsHelp = twin(
        "recordings_skip_commercials_help",
        "Automatically, playback jumps over the breaks the server found in a recording. With a button, Skip break " +
            "comes up and waits for a press. Off, breaks play.",
    )

    private val space = twin("recordings_space", "%1\$s free of %2\$s")
    private val until = twin("recordings_until", "Recording until %1\$s")
    private val keeps = twin("recordings_series_keeps", "Keeps %1\$d")
    private val keepButton = twin("recordings_series_keep_button", "Keep: %1\$s")
    private val newOnly = twin("recordings_series_new_only", "New episodes only")
    private val allAirings = twin("recordings_series_all_airings", "All airings")
    private val anyChannel = twin("recordings_series_any_channel", "Any channel")
    private val keepAll = twin("recordings_series_keep_all", "Keeps all")
    private val keepAllShort = twin("recordings_series_keep_all_short", "All")
    private val takeAll = twin("recordings_series_take_all", "Record repeats too")
    private val takeNew = twin("recordings_series_take_new", "New episodes only")
    val seriesStop = twin("recordings_series_stop", "Stop series")

    private val groupNow = twin("recordings_now", "Recording now")
    private val groupRecorded = twin("recordings_recorded", "Recorded")
    private val groupScheduled = twin("recordings_scheduled", "Scheduled")
    private val stop = twin("recordings_stop", "Stop")
    private val cancel = twin("recordings_cancel", "Cancel")
    private val delete = twin("recordings_delete", "Delete")

    private val failed = twin("recordings_failed", "Failed")
    private val failedMissed = twin(
        "recordings_failed_missed",
        "Missed: the server was off or busy with other recordings",
    )
    private val failedDiskFull = twin("recordings_failed_disk_full", "Failed: the recordings disk filled up")
    private val failedNoMedia = twin("recordings_failed_no_media", "Failed: the channel sent nothing to record")
    private val failedSource = twin("recordings_failed_source", "Failed: couldn't reach the channel's source")
    private val failedChannelGone = twin("recordings_failed_channel_gone", "Failed: the channel left the lineup")
    private val endedEarly = twin("recordings_ended_early", "Ended early")
    private val endedEarlyStopped = twin("recordings_ended_early_stopped", "Stopped early")
    private val endedEarlyDiskFull = twin(
        "recordings_ended_early_disk_full",
        "Ended early: the recordings disk filled up",
    )
    private val endedEarlySource = twin("recordings_ended_early_source", "Ended early: lost the channel's source")
    private val endedEarlyChannelGone = twin(
        "recordings_ended_early_channel_gone",
        "Ended early: the channel left the lineup",
    )

    /** Free and total disk space, already formatted, as the recordings list's footer shows them. */
    fun space(free: String, total: String): String = table.fill(space, free, total)

    /** The status of a running recording; [end] is its end time, formatted in the viewer's locale. */
    fun until(end: String): String = table.fill(until, end)

    fun groupTitle(group: RecordingGroup): String = when (group) {
        RecordingGroup.NOW -> groupNow
        RecordingGroup.RECORDED -> groupRecorded
        RecordingGroup.SCHEDULED -> groupScheduled
    }

    /** What ending a recording is called: a running one stops, a scheduled one is canceled, any other is deleted. */
    fun deleteLabel(state: RecordingState): String = when (state) {
        RecordingState.RECORDING -> stop
        RecordingState.SCHEDULED -> cancel
        else -> delete
    }

    fun failureLabel(failure: RecordingFailure?): String = when (failure) {
        RecordingFailure.MISSED -> failedMissed
        RecordingFailure.DISK_FULL -> failedDiskFull
        RecordingFailure.NO_MEDIA -> failedNoMedia
        RecordingFailure.SOURCE -> failedSource
        RecordingFailure.CHANNEL_GONE -> failedChannelGone
        RecordingFailure.STOPPED, RecordingFailure.OTHER, null -> failed
    }

    fun endedEarlyLabel(reason: RecordingFailure): String = when (reason) {
        RecordingFailure.STOPPED -> endedEarlyStopped
        RecordingFailure.DISK_FULL -> endedEarlyDiskFull
        RecordingFailure.SOURCE, RecordingFailure.NO_MEDIA -> endedEarlySource
        RecordingFailure.CHANNEL_GONE -> endedEarlyChannelGone
        RecordingFailure.MISSED, RecordingFailure.OTHER -> endedEarly
    }

    /** The button that flips a series rule between new episodes only and repeats too. */
    fun takeLabel(rule: SeriesRule): String = if (rule.newOnly) takeAll else takeNew

    /** The button that steps how many recordings a series rule keeps. */
    fun keepLabel(rule: SeriesRule): String =
        table.fill(keepButton, if (rule.keep == 0) keepAllShort else rule.keep.toString())

    /** A series rule in a line, such as "New episodes only · Keeps 5 · Any channel". */
    fun summary(rule: SeriesRule): String = listOfNotNull(
        if (rule.newOnly) newOnly else allAirings,
        if (rule.keep == 0) keepAll else table.fill(keeps, rule.keep),
        anyChannel.takeIf { rule.channelId == null },
    ).joinToString(" · ")
}
