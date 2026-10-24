package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.dvr.StoredRecording
import dev.jamesrich.sofaking.contract.RecordingBreak
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.Instant

suspend fun BackendStore.recordings(): List<StoredRecording> = read { Recordings.selectAll().map { it.recording() } }

suspend fun BackendStore.recording(id: String): StoredRecording? = read {
    Recordings.selectAll().where {
        Recordings.id eq
            id
    }.singleOrNull()?.recording()
}

/** Adds [recording] unless one is already stored for the same channel and start; true when it was added. */
suspend fun BackendStore.addRecording(recording: StoredRecording): Boolean = query {
    Recordings.insertIgnore { it.write(recording) }.insertedCount > 0
}

/** Replaces the stored copy of [recording], matched by id. */
suspend fun BackendStore.saveRecording(recording: StoredRecording) = query {
    Recordings.update({ Recordings.id eq recording.id }) { it.write(recording) }
    Unit
}

suspend fun BackendStore.deleteRecording(id: String): Boolean = query {
    Recordings.deleteWhere { Recordings.id eq id } > 0
}

private fun UpdateBuilder<*>.write(r: StoredRecording) {
    this[Recordings.id] = r.id
    this[Recordings.ruleId] = r.ruleId
    this[Recordings.channelId] = r.channelId
    this[Recordings.channelName] = r.channelName
    this[Recordings.startsAt] = r.startsAt.epochSecond
    this[Recordings.endsAt] = r.endsAt.epochSecond
    this[Recordings.startPadding] = r.startPadding.seconds.toInt()
    this[Recordings.endPadding] = r.endPadding.seconds.toInt()
    this[Recordings.title] = r.title
    this[Recordings.episodeTitle] = r.episodeTitle
    this[Recordings.description] = r.description
    this[Recordings.season] = r.season
    this[Recordings.episode] = r.episode
    this[Recordings.movie] = r.movie
    this[Recordings.year] = r.year
    this[Recordings.image] = r.image
    this[Recordings.state] = r.state
    this[Recordings.reason] = r.reason
    this[Recordings.path] = r.path
    this[Recordings.bytes] = r.bytes
    this[Recordings.protected] = r.protected
    this[Recordings.positionMs] = r.positionMs
    this[Recordings.watched] = r.watched
    this[Recordings.createdAt] = r.createdAt.toString()
    this[Recordings.updatedAt] = r.updatedAt.toString()
    this[Recordings.commercials] = r.commercials
    this[Recordings.breaks] = r.breaks.takeIf { it.isNotEmpty() }?.joinToString(",") { "${it.startMs}-${it.endMs}" }
}

private fun ResultRow.recording() = StoredRecording(
    id = this[Recordings.id],
    ruleId = this[Recordings.ruleId],
    channelId = this[Recordings.channelId],
    channelName = this[Recordings.channelName],
    startsAt = Instant.ofEpochSecond(this[Recordings.startsAt]),
    endsAt = Instant.ofEpochSecond(this[Recordings.endsAt]),
    startPadding = Duration.ofSeconds(this[Recordings.startPadding].toLong()),
    endPadding = Duration.ofSeconds(this[Recordings.endPadding].toLong()),
    title = this[Recordings.title],
    episodeTitle = this[Recordings.episodeTitle],
    description = this[Recordings.description],
    season = this[Recordings.season],
    episode = this[Recordings.episode],
    movie = this[Recordings.movie],
    year = this[Recordings.year],
    image = this[Recordings.image],
    state = this[Recordings.state],
    reason = this[Recordings.reason],
    path = this[Recordings.path],
    bytes = this[Recordings.bytes],
    protected = this[Recordings.protected],
    positionMs = this[Recordings.positionMs],
    watched = this[Recordings.watched],
    createdAt = Instant.parse(this[Recordings.createdAt]),
    updatedAt = Instant.parse(this[Recordings.updatedAt]),
    commercials = this[Recordings.commercials],
    breaks = this[Recordings.breaks].orEmpty().split(',').mapNotNull { pair ->
        val (start, end) = pair.split('-').takeIf { it.size == 2 } ?: return@mapNotNull null
        RecordingBreak(start.toLongOrNull() ?: return@mapNotNull null, end.toLongOrNull() ?: return@mapNotNull null)
    },
)
