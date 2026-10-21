package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.StoredAdminSession
import dev.jamesrich.sofaking.backend.store.adminSessions
import dev.jamesrich.sofaking.backend.store.deleteAdminSession
import dev.jamesrich.sofaking.backend.store.saveAdminSession
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One signed-in browser: [key] is the HMAC of the ID its cookie holds, [csrf] goes in every form it posts. */
class AdminSession(internal val key: String, val csrf: String, val expiresAt: Instant) {
    /** The notice the next page shows once, set by an action before it redirects. */
    internal val flash = java.util.concurrent.atomic.AtomicReference<Flash?>(null)
}

/** A session [open] just started, and the ID its cookie carries. */
class OpenedSession(val cookie: String, val session: AdminSession)

/**
 * Portal sessions. Each lasts [lifetime] from sign-in; past [limit] the session closest to expiry gives way to the new
 * one. With a [store] they are kept in the database too, by the hash of their ID, so a restart (which every setup save
 * makes) keeps everyone signed in; the database never holds an ID a cookie could be made from. The hash is keyed by the
 * admin token ([secret]), so a changed or removed token ends every session that the old one opened.
 */
class AdminSessions(
    private val clock: Clock,
    private val secret: String?,
    private val store: BackendStore? = null,
    private val lifetime: Duration = Duration.ofHours(12),
    private val limit: Int = 32,
) {
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, AdminSession>()
    private val loaded = Mutex()
    @Volatile private var ready = store == null

    /** Reads the stored sessions once, before the first lookup. */
    private suspend fun load() {
        if (ready) return
        loaded.withLock {
            if (ready) return
            for (stored in store!!.adminSessions(clock.instant())) sessions.putIfAbsent(stored.key, AdminSession(stored.key, stored.csrf, stored.expiresAt))
            ready = true
        }
    }

    suspend fun open(): OpenedSession {
        checkNotNull(secret) { "no admin token opens a session" }
        load()
        val now = clock.instant()
        for (expired in sessions.values.filter { !it.expiresAt.isAfter(now) }) remove(expired.key)
        while (sessions.size >= limit) sessions.values.minByOrNull { it.expiresAt }?.let { remove(it.key) } ?: break
        val id = token()
        val session = AdminSession(hash(id), token(), now.plus(lifetime))
        sessions[session.key] = session
        store?.saveAdminSession(StoredAdminSession(session.key, session.csrf, session.expiresAt))
        return OpenedSession(id, session)
    }

    suspend fun find(id: String?): AdminSession? {
        if (id.isNullOrEmpty() || secret == null) return null
        load()
        val session = sessions[hash(id)] ?: return null
        if (session.expiresAt.isAfter(clock.instant())) return session
        remove(session.key)
        return null
    }

    /** Ends the session whose cookie holds [id]. */
    suspend fun close(id: String) {
        if (secret == null) return
        load()
        remove(hash(id))
    }

    suspend fun close(session: AdminSession) = remove(session.key)

    private suspend fun remove(key: String) {
        sessions.remove(key)
        store?.deleteAdminSession(key)
    }

    private fun token(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun hash(id: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret!!.toByteArray(), "HmacSHA256")) }
        return HexFormat.of().formatHex(mac.doFinal(id.toByteArray()))
    }
}

/** Equal strings, compared in time that depends on neither's content nor length. */
internal fun sameSecret(presented: String?, expected: String): Boolean {
    if (presented == null) return false
    val digest = { value: String -> MessageDigest.getInstance("SHA-256").digest(value.toByteArray()) }
    return MessageDigest.isEqual(digest(presented), digest(expected))
}
