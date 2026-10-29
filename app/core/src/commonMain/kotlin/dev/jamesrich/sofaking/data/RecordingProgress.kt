package dev.jamesrich.sofaking.data

import kotlin.time.Instant

/**
 * How much of the window from [startsAt] to [endsAt] has passed at [now], from 0 before it opens to 1 once it
 * closes; null for a window with no length.
 */
fun recordedFraction(startsAt: Instant, endsAt: Instant, now: Instant): Float? {
    val total = (endsAt - startsAt).inWholeMilliseconds
    if (total <= 0) return null
    return ((now - startsAt).inWholeMilliseconds.toFloat() / total).coerceIn(0f, 1f)
}

/** How much of a running recording is on disk at [now]; null for any other state. */
fun DvrRecording.recordedSoFar(now: Instant): Float? =
    if (state == RecordingState.RECORDING) recordedFraction(startsAt, endsAt, now) else null

/** How far the household got into a partly watched recording, against its program's length; null otherwise. */
val DvrRecording.resumeFraction: Float?
    get() {
        val total = (endsAt - startsAt).inWholeMilliseconds
        if (!inProgress || total <= 0) return null
        return (positionMs.toFloat() / total).coerceIn(0f, 1f)
    }

/**
 * What puts a canceled scheduled recording back, in the order to try: the program on its channel at its start, then
 * the same window under its title, for a recording made from a time window the guide has no program for.
 */
internal fun restoreRequests(recording: DvrRecording): List<ScheduleRecordingDto> = listOf(
    ScheduleRecordingDto(recording.channelId, recording.startsAt.toString()),
    ScheduleRecordingDto(
        recording.channelId,
        recording.startsAt.toString(),
        recording.endsAt.toString(),
        recording.title,
    ),
)
