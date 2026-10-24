package dev.jamesrich.sofaking.backend.store

import java.time.Instant

/**
 * One value a native provider keeps across restarts: its device id, a session token. [expiresAt] null never expires.
 */
data class ProviderStateValue(val value: String, val expiresAt: Instant?, val updatedAt: Instant)
