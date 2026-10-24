package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.LineupService
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.deleteRecordingRule
import dev.jamesrich.sofaking.backend.store.lineup
import dev.jamesrich.sofaking.backend.store.recording
import dev.jamesrich.sofaking.backend.store.recordingRule
import dev.jamesrich.sofaking.backend.store.recordingRules
import dev.jamesrich.sofaking.backend.store.recordings
import dev.jamesrich.sofaking.backend.store.saveRecordingRule
import dev.jamesrich.sofaking.contract.CreateRecordingRule
import dev.jamesrich.sofaking.contract.RecordingRule
import dev.jamesrich.sofaking.contract.UpdateRecordingRule
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Duration
import java.util.UUID

private const val TITLE_LIMIT = 200

/** A rule's episodes to keep, 0 for all of them, and its priority over the other rules. */
private val KEEP_RANGE = 0..1000
private val PRIORITY_RANGE = -100..100

/** The series rules: which titles to record, on which channels, how much to keep. A change runs [changed]. */
class DvrRules internal constructor(
    private val store: BackendStore,
    private val lineup: LineupService,
    private val clock: Clock,
    private val changed: () -> Unit,
) {
    suspend fun list(): List<RecordingRule> = store.recordingRules().map { it.toWire() }

    suspend fun create(request: CreateRecordingRule): RecordingRule {
        val title = request.title.trim()
        if (title.isEmpty() || title.length > TITLE_LIMIT) {
            throw ApiFailure(
                HttpStatusCode.BadRequest,
                "INVALID_REQUEST",
                "A rule needs a title of 1 to $TITLE_LIMIT characters",
            )
        }
        requireChannel(request.channelId)
        val rule = StoredRule(
            UUID.randomUUID().toString(), title, request.channelId, request.newOnly, keep(request.keep),
            padding(request.startPaddingSeconds, Dvr.DEFAULT_START_PADDING),
            padding(request.endPaddingSeconds, Dvr.DEFAULT_END_PADDING),
            priority(request.priority), clock.instant(),
        )
        store.saveRecordingRule(rule)
        changed()
        return rule.toWire()
    }

    suspend fun update(id: String, update: UpdateRecordingRule): RecordingRule {
        val rule =
            store.recordingRule(id)
                ?: throw ApiFailure(HttpStatusCode.NotFound, "RULE_NOT_FOUND", "Unknown recording rule")
        requireChannel(update.channelId)
        val next = rule.copy(
            channelId = if (update.anyChannel == true) null else update.channelId ?: rule.channelId,
            newOnly = update.newOnly ?: rule.newOnly,
            keep = update.keep?.let(::keep) ?: rule.keep,
            startPadding = update.startPaddingSeconds?.let { padding(it, rule.startPadding) } ?: rule.startPadding,
            endPadding = update.endPaddingSeconds?.let { padding(it, rule.endPadding) } ?: rule.endPadding,
            priority = update.priority?.let(::priority) ?: rule.priority,
        )
        store.saveRecordingRule(next)
        changed()
        return next.toWire()
    }

    /** Deletes the rule and its scheduled recordings; what it recorded stays. */
    suspend fun delete(id: String) {
        if (!store.deleteRecordingRule(id, listOf(Wire.RecordingState.SCHEDULED, CANCELED))) {
            throw ApiFailure(HttpStatusCode.NotFound, "RULE_NOT_FOUND", "Unknown recording rule")
        }
    }

    /** Refuses a [channelId] the lineup does not have; no channel means any channel. */
    private suspend fun requireChannel(channelId: String?) {
        if (channelId != null && lineup.entry(channelId) == null) {
            throw ApiFailure(HttpStatusCode.NotFound, Wire.ErrorCode.CHANNEL_NOT_FOUND, "Unknown channel")
        }
    }
}

private fun keep(value: Int): Int = value.takeIf { it in KEEP_RANGE }
    ?: throw ApiFailure(
        HttpStatusCode.BadRequest,
        "INVALID_REQUEST",
        "keep must be ${KEEP_RANGE.first} to ${KEEP_RANGE.last}",
    )

private fun priority(value: Int): Int = value.takeIf { it in PRIORITY_RANGE }
    ?: throw ApiFailure(
        HttpStatusCode.BadRequest,
        "INVALID_REQUEST",
        "priority must be ${PRIORITY_RANGE.first} to ${PRIORITY_RANGE.last}",
    )

/** [seconds] of padding, or [default] without; refused outside 0 to [Dvr.MAX_PADDING_SECONDS]. */
private fun padding(seconds: Int?, default: Duration): Duration = when (seconds) {
    null -> default

    in 0..Dvr.MAX_PADDING_SECONDS -> Duration.ofSeconds(seconds.toLong())

    else -> throw ApiFailure(
        HttpStatusCode.BadRequest,
        "INVALID_REQUEST",
        "Padding must be 0 to ${Dvr.MAX_PADDING_SECONDS} seconds",
    )
}
