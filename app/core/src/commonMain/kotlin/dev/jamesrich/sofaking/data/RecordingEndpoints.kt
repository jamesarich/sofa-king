package dev.jamesrich.sofaking.data

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.encodeURLPathPart

// The recording endpoints of [SofaKingApi]: recordings and the series rules that schedule them.

/** Every recording but canceled ones, soonest scheduled first. */
internal suspend fun SofaKingApi.recordings(): RecordingListDto = call {
    http.get("recordings").decode()
}

/** Records the program on [ScheduleRecordingDto.channelId] that starts at [ScheduleRecordingDto.startsAt]. */
internal suspend fun SofaKingApi.scheduleRecording(request: ScheduleRecordingDto): RecordingDto = call {
    http.post("recordings") { json(request) }.decode()
}

/** Cancels a scheduled recording, stops a running one, or deletes a finished one and its file. */
internal suspend fun SofaKingApi.deleteRecording(id: String) = call {
    http.delete("recordings/" + id.encodeURLPathPart()).checkSuccess()
}

/** Saves where the household is in a recording, whether it is watched, or whether cleanup keeps it. */
internal suspend fun SofaKingApi.updateRecording(id: String, update: UpdateRecordingDto): RecordingDto = call {
    http.patch("recordings/" + id.encodeURLPathPart()) { json(update) }.decode()
}

internal suspend fun SofaKingApi.recordingRules(): RecordingRuleListDto = call {
    http.get("recording-rules").decode()
}

internal suspend fun SofaKingApi.createRecordingRule(request: CreateRecordingRuleDto): RecordingRuleDto = call {
    http.post("recording-rules") { json(request) }.decode()
}

/** Changes a series rule; the server schedules again from it. */
internal suspend fun SofaKingApi.updateRecordingRule(id: String, update: UpdateRecordingRuleDto): RecordingRuleDto =
    call {
        http.patch("recording-rules/" + id.encodeURLPathPart()) { json(update) }.decode()
    }

/** Deletes a series rule and the recordings it scheduled; the ones it already made stay. */
internal suspend fun SofaKingApi.deleteRecordingRule(id: String) = call {
    http.delete("recording-rules/" + id.encodeURLPathPart()).checkSuccess()
}
