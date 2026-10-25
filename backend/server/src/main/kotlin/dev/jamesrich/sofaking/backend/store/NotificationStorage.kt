package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.NotificationDelivery
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/** The deliveries kept per target; older ones go as new ones arrive. */
internal const val NOTIFICATION_DELIVERIES_KEPT = 200

/** A notification target as stored, its [secret] included. */
data class StoredNotificationTarget(
    val id: String,
    val name: String,
    val kind: String,
    val url: String,
    val secret: String?,
    val events: List<String>,
    val enabled: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

/** Every notification target, in the order they were added. */
suspend fun BackendStore.notificationTargets(): List<StoredNotificationTarget> = read {
    NotificationTargets.selectAll().orderBy(NotificationTargets.createdAt to SortOrder.ASC).map { it.toTarget() }
}

/** Adds [target], or replaces the one with its id. */
suspend fun BackendStore.saveNotificationTarget(target: StoredNotificationTarget) {
    query {
        NotificationTargets.upsert {
            it[id] = target.id
            it[name] = target.name
            it[kind] = target.kind
            it[url] = target.url
            it[secret] = target.secret
            it[eventsJson] = Json.encodeToString(target.events)
            it[enabled] = target.enabled
            it[createdAt] = target.createdAt
            it[updatedAt] = target.updatedAt
        }
    }
}

/** Removes the target [id] and its deliveries; false when there was none. */
suspend fun BackendStore.deleteNotificationTarget(id: String): Boolean = query {
    val target = id
    NotificationDeliveries.deleteWhere { NotificationDeliveries.targetId eq target }
    NotificationTargets.deleteWhere { NotificationTargets.id eq target } > 0
}

/** Records [delivery] to [target] and drops what is older than the newest [NOTIFICATION_DELIVERIES_KEPT]. */
suspend fun BackendStore.addNotificationDelivery(target: String, delivery: NotificationDelivery) {
    query {
        // A target deleted while this delivery was under way keeps no row.
        if (NotificationTargets.selectAll().where { NotificationTargets.id eq target }.empty()) return@query
        NotificationDeliveries.insert {
            it[targetId] = target
            it[event] = delivery.event
            it[at] = delivery.at
            it[ok] = delivery.ok
            it[status] = delivery.status
            it[error] = delivery.error
            it[attempts] = delivery.attempts
        }
        // Ids grow across targets, so the kept rows are found by count, not by id arithmetic.
        val kept = NotificationDeliveries.selectAll().where { NotificationDeliveries.targetId eq target }
            .orderBy(NotificationDeliveries.id to SortOrder.DESC).limit(NOTIFICATION_DELIVERIES_KEPT)
            .map { it[NotificationDeliveries.id] }
        if (kept.size == NOTIFICATION_DELIVERIES_KEPT) {
            val oldest = kept.last()
            NotificationDeliveries.deleteWhere {
                (NotificationDeliveries.targetId eq target) and (NotificationDeliveries.id less oldest)
            }
        }
    }
}

/** The newest [limit] deliveries to [targetId], the newest first. */
suspend fun BackendStore.notificationDeliveries(
    targetId: String,
    limit: Int = NOTIFICATION_DELIVERIES_KEPT,
): List<NotificationDelivery> = read {
    NotificationDeliveries.selectAll().where { NotificationDeliveries.targetId eq targetId }
        .orderBy(NotificationDeliveries.id to SortOrder.DESC).limit(limit).map { it.toDelivery() }
}

/** Each target's newest delivery, by target id; a target without one is left out. */
suspend fun BackendStore.lastNotificationDeliveries(): Map<String, NotificationDelivery> = read {
    val newest = HashMap<String, Pair<Long, NotificationDelivery>>()
    // Only the kept rows exist, so this reads at most the kept count per target.
    for (row in NotificationDeliveries.selectAll()) {
        val id = row[NotificationDeliveries.id]
        val target = row[NotificationDeliveries.targetId]
        if ((newest[target]?.first ?: Long.MIN_VALUE) < id) newest[target] = id to row.toDelivery()
    }
    newest.mapValues { it.value.second }
}

private fun ResultRow.toTarget() = StoredNotificationTarget(
    this[NotificationTargets.id],
    this[NotificationTargets.name],
    this[NotificationTargets.kind],
    this[NotificationTargets.url],
    this[NotificationTargets.secret],
    runCatching { Json.decodeFromString<List<String>>(this[NotificationTargets.eventsJson]) }.getOrDefault(emptyList()),
    this[NotificationTargets.enabled],
    this[NotificationTargets.createdAt],
    this[NotificationTargets.updatedAt],
)

private fun ResultRow.toDelivery() = NotificationDelivery(
    this[NotificationDeliveries.event],
    this[NotificationDeliveries.at],
    this[NotificationDeliveries.ok],
    this[NotificationDeliveries.status],
    this[NotificationDeliveries.error],
    this[NotificationDeliveries.attempts],
)
