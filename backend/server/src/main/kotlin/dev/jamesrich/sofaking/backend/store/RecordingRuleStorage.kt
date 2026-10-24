package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.dvr.StoredRule
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.name
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Duration
import java.time.Instant

suspend fun BackendStore.recordingRules(): List<StoredRule> = read {
    RecordingRules.selectAll().map { it.rule() }.sortedBy { it.createdAt }
}

suspend fun BackendStore.recordingRule(id: String): StoredRule? = read {
    RecordingRules.selectAll().where {
        RecordingRules.id eq
            id
    }.singleOrNull()?.rule()
}

/** Inserts or replaces [rule]. */
suspend fun BackendStore.saveRecordingRule(rule: StoredRule) = query {
    RecordingRules.upsert {
        it[id] = rule.id
        it[title] = rule.title
        it[channelId] = rule.channelId
        it[newOnly] = rule.newOnly
        it[keep] = rule.keep
        it[startPadding] = rule.startPadding.seconds.toInt()
        it[endPadding] = rule.endPadding.seconds.toInt()
        it[priority] = rule.priority
        it[createdAt] = rule.createdAt.toString()
    }
    Unit
}

/** Deletes the rule; its recordings keep their files but no longer name it, and its scheduled ones go. */
suspend fun BackendStore.deleteRecordingRule(id: String, scheduledStates: Collection<String>): Boolean = query {
    val deleted = RecordingRules.deleteWhere { RecordingRules.id eq id } > 0
    Recordings.deleteWhere { (Recordings.ruleId eq id) and (Recordings.state inList scheduledStates) }
    Recordings.update({ Recordings.ruleId eq id }) { it[ruleId] = null }
    deleted
}

private fun ResultRow.rule() = StoredRule(
    this[RecordingRules.id], this[RecordingRules.title], this[RecordingRules.channelId], this[RecordingRules.newOnly],
    this[RecordingRules.keep],
    Duration.ofSeconds(
        this[RecordingRules.startPadding].toLong(),
    ),
    Duration.ofSeconds(this[RecordingRules.endPadding].toLong()),
    this[RecordingRules.priority], Instant.parse(this[RecordingRules.createdAt]),
)
