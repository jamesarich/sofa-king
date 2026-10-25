package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.TokenScope
import java.time.Instant

/** A token row that is not revoked; [secretHash] is the hex SHA-256 of the secret. */
data class StoredApiToken(
    val id: String,
    val label: String,
    val secretHash: String,
    val scope: TokenScope,
    val clientId: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
)
