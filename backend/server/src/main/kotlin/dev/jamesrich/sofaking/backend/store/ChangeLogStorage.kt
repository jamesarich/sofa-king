package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.PolicyChange
import dev.jamesrich.sofaking.backend.api.PolicyChangeEntry
import java.time.Instant
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

private val changeJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** Appends [changes] to the change log and keeps the newest [keep] rows. */
suspend fun BackendStore.recordPolicyChanges(changes: List<PolicyChange>, at: Instant, keep: Int = 10_000) {
    if (changes.isEmpty()) return
    query {
        PolicyChanges.batchInsert(changes, shouldReturnGeneratedValues = false) { change ->
            this[PolicyChanges.appliedAt] = at.toString()
            this[PolicyChanges.entryId] = change.entryId
            this[PolicyChanges.changeJson] = changeJson.encodeToString(change)
        }
        val count = PolicyChanges.id.count()
        val total = PolicyChanges.select(count).single()[count]
        if (total > keep) {
            val cutoff = PolicyChanges.select(PolicyChanges.id).orderBy(PolicyChanges.id to SortOrder.DESC).limit(1).offset(keep.toLong()).single()[PolicyChanges.id]
            PolicyChanges.deleteWhere { PolicyChanges.id lessEq cutoff }
        }
    }
}

/** The newest [limit] changes, newest first. */
suspend fun BackendStore.policyChanges(limit: Int): List<PolicyChangeEntry> = query {
    PolicyChanges.selectAll().orderBy(PolicyChanges.id to SortOrder.DESC).limit(limit).map {
        PolicyChangeEntry(it[PolicyChanges.id], it[PolicyChanges.appliedAt], changeJson.decodeFromString(it[PolicyChanges.changeJson]))
    }
}
