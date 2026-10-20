package dev.jamesrich.sofaking.backend.store

import java.time.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.upsert

/** One value a native provider keeps across restarts: its device id, a session token. [expiresAt] null never expires. */
data class ProviderStateValue(val value: String, val expiresAt: Instant?, val updatedAt: Instant)

internal fun ResultRow.providerState() =
    ProviderStateValue(this[ProviderStates.value], this[ProviderStates.expiresAt]?.let(Instant::parse), Instant.parse(this[ProviderStates.updatedAt]))

/** Read from memory: the table is loaded when the store opens and every write below replaces its entry after the commit. */
fun BackendStore.providerState(providerId: String, key: String): ProviderStateValue? = providerStates.get()[providerId to key]

suspend fun BackendStore.saveProviderState(providerId: String, key: String, value: String, expiresAt: Instant?, now: Instant) = queryThen({
    ProviderStates.upsert {
        it[ProviderStates.providerId] = providerId
        it[ProviderStates.key] = key
        it[ProviderStates.value] = value
        it[ProviderStates.expiresAt] = expiresAt?.toString()
        it[updatedAt] = now.toString()
    }
    Unit
}) { providerStates.updateAndGet { it + ((providerId to key) to ProviderStateValue(value, expiresAt, now)) } }

suspend fun BackendStore.deleteProviderState(providerId: String, key: String) = queryThen({
    ProviderStates.deleteWhere { (ProviderStates.providerId eq providerId) and (ProviderStates.key eq key) }
    Unit
}) { providerStates.updateAndGet { it - (providerId to key) } }
