package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.CurationAction
import dev.jamesrich.sofaking.backend.api.CurationChange
import dev.jamesrich.sofaking.backend.api.CurationRule
import dev.jamesrich.sofaking.backend.api.CurationSettings
import java.time.Instant
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

private val curationJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
private const val SETTINGS_KEY = "curation:settings"

suspend fun BackendStore.curationSettings(): CurationSettings? = query {
    Metadata.selectAll().where { Metadata.key eq SETTINGS_KEY }.singleOrNull()?.let { curationJson.decodeFromString<CurationSettings>(it[Metadata.value]) }
}

suspend fun BackendStore.saveCurationSettings(settings: CurationSettings) = query {
    val encoded = curationJson.encodeToString(settings)
    Metadata.deleteWhere { Metadata.key eq SETTINGS_KEY }
    Metadata.insert { it[key] = SETTINGS_KEY; it[value] = encoded }
    Unit
}

/** Every rule in position order, ties by id. */
suspend fun BackendStore.curationRules(): List<CurationRule> = query {
    CurationRules.selectAll().orderBy(CurationRules.position to SortOrder.ASC, CurationRules.id to SortOrder.ASC)
        .map { curationJson.decodeFromString<CurationRule>(it[CurationRules.ruleJson]) }
}

suspend fun BackendStore.saveCurationRule(rule: CurationRule) = query {
    val encoded = curationJson.encodeToString(rule)
    CurationRules.deleteWhere { CurationRules.id eq rule.id }
    CurationRules.insert {
        it[id] = rule.id
        it[kind] = rule.kind
        it[position] = rule.position
        it[ruleJson] = encoded
    }
    Unit
}

suspend fun BackendStore.deleteCurationRule(id: String): Boolean = query { CurationRules.deleteWhere { CurationRules.id eq id } > 0 }

suspend fun BackendStore.unavailableSince(): Map<String, Instant> = query {
    CurationEntryStates.selectAll().associate { it[CurationEntryStates.entryId] to Instant.parse(it[CurationEntryStates.unavailableSince]) }
}

/** Writes the rows in [updates]; a null value deletes the row. */
suspend fun BackendStore.saveUnavailableSince(updates: Map<String, Instant?>) {
    if (updates.isEmpty()) return
    query {
        CurationEntryStates.deleteWhere { CurationEntryStates.entryId inList updates.keys }
        val rows = updates.entries.filter { it.value != null }
        CurationEntryStates.batchInsert(rows) { (entryId, since) ->
            this[CurationEntryStates.entryId] = entryId
            this[CurationEntryStates.unavailableSince] = since.toString()
        }
    }
}

suspend fun BackendStore.adoptedChannels(): Set<String> = query { CurationAdopted.selectAll().mapTo(HashSet()) { it[CurationAdopted.channelId] } }

suspend fun BackendStore.markAdopted(channelIds: Map<String, String>, at: Instant) {
    if (channelIds.isEmpty()) return
    query {
        CurationAdopted.batchInsert(channelIds.entries, ignore = true) { (channelId, entryId) ->
            this[CurationAdopted.channelId] = channelId
            this[CurationAdopted.entryId] = entryId
            this[CurationAdopted.adoptedAt] = at.toString()
        }
    }
}

/** Appends [actions] to the change log and keeps the newest [keep] rows. */
suspend fun BackendStore.recordCurationChanges(actions: List<CurationAction>, at: Instant, keep: Int = 10_000) {
    if (actions.isEmpty()) return
    query {
        CurationChanges.batchInsert(actions) { action ->
            this[CurationChanges.appliedAt] = at.toString()
            this[CurationChanges.entryId] = action.entryId
            this[CurationChanges.actionJson] = curationJson.encodeToString(action)
        }
        val count = CurationChanges.id.count()
        val total = CurationChanges.select(count).single()[count]
        if (total > keep) {
            val cutoff = CurationChanges.select(CurationChanges.id).orderBy(CurationChanges.id to SortOrder.DESC).limit(1).offset(keep.toLong()).single()[CurationChanges.id]
            CurationChanges.deleteWhere { CurationChanges.id lessEq cutoff }
        }
    }
}

/** The newest [limit] changes, newest first. */
suspend fun BackendStore.curationChanges(limit: Int): List<CurationChange> = query {
    CurationChanges.selectAll().orderBy(CurationChanges.id to SortOrder.DESC).limit(limit).map {
        CurationChange(it[CurationChanges.id], it[CurationChanges.appliedAt], curationJson.decodeFromString(it[CurationChanges.actionJson]))
    }
}
