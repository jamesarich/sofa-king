package dev.jamesrich.sofaking.backend.store

import java.time.Instant

/** A portal session as stored: [key] is the SHA-256 of the cookie's session ID. */
data class StoredAdminSession(val key: String, val csrf: String, val expiresAt: Instant)
