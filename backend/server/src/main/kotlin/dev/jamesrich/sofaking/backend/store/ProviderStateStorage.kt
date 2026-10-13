package dev.jamesrich.sofaking.backend.store

import java.time.Instant
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/** One value a native provider keeps across restarts: its device id, a session token. [expiresAt] null never expires. */
data class ProviderStateValue(val value: String, val expiresAt: Instant?, val updatedAt: Instant)

suspend fun BackendStore.providerState(providerId: String, key: String): ProviderStateValue? = query {
    ProviderStates.selectAll().where { (ProviderStates.providerId eq providerId) and (ProviderStates.key eq key) }.singleOrNull()?.let { row ->
        ProviderStateValue(row[ProviderStates.value], row[ProviderStates.expiresAt]?.let(Instant::parse), Instant.parse(row[ProviderStates.updatedAt]))
    }
}

suspend fun BackendStore.saveProviderState(providerId: String, key: String, value: String, expiresAt: Instant?, now: Instant) = query {
    ProviderStates.upsert {
        it[ProviderStates.providerId] = providerId
        it[ProviderStates.key] = key
        it[ProviderStates.value] = value
        it[ProviderStates.expiresAt] = expiresAt?.toString()
        it[updatedAt] = now.toString()
    }
    Unit
}

suspend fun BackendStore.deleteProviderState(providerId: String, key: String) = query {
    ProviderStates.deleteWhere { (ProviderStates.providerId eq providerId) and (ProviderStates.key eq key) }
    Unit
}
