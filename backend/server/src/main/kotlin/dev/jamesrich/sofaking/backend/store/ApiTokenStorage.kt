package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.TokenScope
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/** Every token that is not revoked, oldest first. A row with a scope this server does not know is left out. */
suspend fun BackendStore.activeApiTokens(): List<StoredApiToken> = read {
    ApiTokenRows.selectAll().where { ApiTokenRows.revokedAt.isNull() }.mapNotNull { row ->
        val scope = TokenScope.entries.firstOrNull { it.name.lowercase() == row[ApiTokenRows.scope] }
            ?: return@mapNotNull null
        StoredApiToken(
            row[ApiTokenRows.id],
            row[ApiTokenRows.label],
            row[ApiTokenRows.secretHash],
            scope,
            row[ApiTokenRows.clientId],
            Instant.parse(row[ApiTokenRows.createdAt]),
            row[ApiTokenRows.lastUsedAt]?.let(Instant::parse),
        )
    }.sortedBy { it.createdAt }
}

suspend fun BackendStore.saveApiToken(token: StoredApiToken) = query {
    ApiTokenRows.insert {
        it[id] = token.id
        it[label] = token.label
        it[secretHash] = token.secretHash
        it[scope] = token.scope.name.lowercase()
        it[clientId] = token.clientId
        it[createdAt] = token.createdAt.toString()
        it[lastUsedAt] = token.lastUsedAt?.toString()
    }
    Unit
}

/** Revokes the token [id] at [now]; false when it is unknown or already revoked. */
suspend fun BackendStore.revokeApiToken(id: String, now: Instant): Boolean = query {
    ApiTokenRows.update({ (ApiTokenRows.id eq id) and ApiTokenRows.revokedAt.isNull() }) {
        it[revokedAt] = now.toString()
    } > 0
}

/** Revokes every token paired to [clientId] at [now], and says how many there were. */
suspend fun BackendStore.revokeClientApiTokens(clientId: String, now: Instant): Int = query {
    ApiTokenRows.update({ (ApiTokenRows.clientId eq clientId) and ApiTokenRows.revokedAt.isNull() }) {
        it[revokedAt] = now.toString()
    }
}

suspend fun BackendStore.markApiTokenUsed(id: String, now: Instant) = query {
    ApiTokenRows.update({ ApiTokenRows.id eq id }) { it[lastUsedAt] = now.toString() }
    Unit
}
