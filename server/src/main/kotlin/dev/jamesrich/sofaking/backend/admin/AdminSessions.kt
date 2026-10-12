package dev.jamesrich.sofaking.backend.admin

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** One signed-in browser: [id] travels in the cookie, [csrf] in every form it posts. */
class AdminSession(val id: String, val csrf: String, val expiresAt: Instant) {
    /** The notice the next page shows once, set by an action before it redirects. */
    internal val flash = java.util.concurrent.atomic.AtomicReference<Flash?>(null)
}

/**
 * Portal sessions, held in memory only, so a restart signs everyone out. Each lasts [lifetime] from sign-in; past
 * [limit] the session closest to expiry gives way to the new one.
 */
class AdminSessions(private val clock: Clock, private val lifetime: Duration = Duration.ofHours(12), private val limit: Int = 32) {
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, AdminSession>()

    fun open(): AdminSession {
        val now = clock.instant()
        sessions.values.removeIf { !it.expiresAt.isAfter(now) }
        while (sessions.size >= limit) sessions.values.minByOrNull { it.expiresAt }?.let { sessions.remove(it.id) } ?: break
        val session = AdminSession(token(), token(), now.plus(lifetime))
        sessions[session.id] = session
        return session
    }

    fun find(id: String?): AdminSession? {
        val session = id?.let { sessions[it] } ?: return null
        if (session.expiresAt.isAfter(clock.instant())) return session
        sessions.remove(session.id)
        return null
    }

    fun close(id: String) {
        sessions.remove(id)
    }

    private fun token(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}

/** Equal strings, compared in time that depends on neither's content nor length. */
internal fun sameSecret(presented: String?, expected: String): Boolean {
    if (presented == null) return false
    val digest = { value: String -> MessageDigest.getInstance("SHA-256").digest(value.toByteArray()) }
    return MessageDigest.isEqual(digest(presented), digest(expected))
}
