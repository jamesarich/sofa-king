package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.store.BackendStore
import java.time.*
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LiveSession(val id: String, val request: OpenPlayback, var offering: Offering, var expires: Instant) {
    var epoch = 1
    var intent = request.intent
    val resources = java.util.LinkedHashMap<String, String>(16, 0.75f, true)
    val requests = mutableSetOf<Job>()
    fun resource(url: String): String {
        resources.entries.find { it.value == url }?.key?.let { key -> resources[key]; return key }
        if (resources.size >= 4096) resources.remove(resources.keys.first())
        val extension = java.net.URI(url).path.substringAfterLast('.', "").lowercase()
        val suffix = if (extension in setOf("m3u8", "ts", "m4s", "mp4", "aac", "vtt", "webvtt", "key", "bin")) extension else "ts"
        return "${UUID.randomUUID()}.$suffix".also { resources[it] = url }
    }
    fun descriptor(): PlaybackSession {
        val token = resource(offering.url)
        return PlaybackSession(id, request.channelId, intent, expires.toString(), PlaybackDescriptor(offering.protocol,
            if (offering.protocol == "hls") "application/vnd.apple.mpegurl" else "video/mp2t", "/media/sessions/$id/$epoch/$token", epoch))
    }
    fun cancel() { requests.toList().forEach { it.cancel() }; requests.clear() }
}
data class MediaResource(val offering: Offering, val url: String)

class SessionManager(private val feeds: FeedService, private val store: BackendStore, private val clock: Clock, private val capacity: Int = 4) {
    private val mutex = Mutex()
    private val sessions = linkedMapOf<String, LiveSession>()
    private val keys = linkedMapOf<String, Pair<OpenPlayback, String>>()
    suspend fun open(request: OpenPlayback): PlaybackSession = mutex.withLock {
        reap()
        if (request.intent !in setOf("preview", "watch") || request.idempotencyKey.length !in 1..128)
            throw ApiFailure(400, "INVALID_REQUEST", "Invalid playback intent or idempotency key")
        keys[request.idempotencyKey]?.let { (original, id) ->
            if (original != request) throw ApiFailure(409, "IDEMPOTENCY_CONFLICT", "Key already used for another request")
            return@withLock sessions[id]?.descriptor() ?: throw ApiFailure(410, "SESSION_EXPIRED", "Session was released or expired")
        }
        if (sessions.size >= capacity) throw ApiFailure(429, "CAPACITY_EXHAUSTED", "Playback capacity exhausted", true)
        val offering = select(request.channelId)
        val session = LiveSession(UUID.randomUUID().toString(), request, offering, clock.instant().plusSeconds(90))
        sessions[session.id] = session
        keys[request.idempotencyKey] = request to session.id
        while (keys.size > 4096) keys.remove(keys.keys.first())
        session.descriptor()
    }
    suspend fun get(id: String): PlaybackSession = mutex.withLock {
        val session = active(id)
        val health = store.health(session.offering.id)
        val refreshed = feeds.offering(session.offering.id)
        if (refreshed == null || health == null || !eligible(health)) {
            session.cancel(); session.epoch++; session.resources.clear()
            session.offering = select(session.request.channelId)
        }
        if (refreshed != null && session.offering.id == refreshed.id && session.offering.url != refreshed.url) {
            session.cancel(); session.offering = refreshed; session.epoch++; session.resources.clear()
        }
        session.descriptor()
    }
    suspend fun update(id: String, update: UpdatePlayback): PlaybackSession = mutex.withLock {
        val session = active(id)
        if (update.intent != null && update.intent !in setOf("preview", "watch")) throw ApiFailure(400, "INVALID_REQUEST", "Invalid playback intent")
        update.intent?.let { session.intent = it }
        session.expires = clock.instant().plusSeconds(90)
        session.descriptor()
    }
    suspend fun release(id: String) = mutex.withLock { sessions.remove(id)?.cancel(); Unit }
    suspend fun healthChanged(offeringId: String) = mutex.withLock {
        if (store.health(offeringId)?.let(::eligible) != true) {
            sessions.values.filter { it.offering.id == offeringId }.forEach {
                it.cancel(); it.epoch++; it.resources.clear()
            }
        }
    }
    suspend fun expire() = mutex.withLock { reap() }
    suspend fun close() = mutex.withLock { sessions.values.forEach { it.cancel() }; sessions.clear() }
    suspend fun acquire(id: String, epoch: Int, token: String, job: Job): MediaResource = mutex.withLock {
        val session = active(id)
        if (epoch != session.epoch) throw ApiFailure(410, "STREAM_CHANGED", "Fetch the current session descriptor", true)
        if (feeds.offering(session.offering.id) == null || store.health(session.offering.id)?.let(::eligible) != true) {
            session.cancel()
            throw ApiFailure(503, "SOURCE_UNAVAILABLE", "Source is no longer eligible", true)
        }
        val url = session.resources[token] ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND", "Unknown media resource")
        if (session.requests.size >= 12) throw ApiFailure(429, "CAPACITY_EXHAUSTED", "Too many media requests", true)
        session.requests += job
        MediaResource(session.offering, url)
    }
    suspend fun finished(id: String, job: Job) = mutex.withLock { sessions[id]?.requests?.remove(job); Unit }
    suspend fun resourcePath(id: String, epoch: Int, url: String): String = mutex.withLock {
        val session = active(id)
        if (session.epoch != epoch) throw ApiFailure(410, "STREAM_CHANGED", "Fetch current descriptor", true)
        "/media/sessions/$id/$epoch/${session.resource(url)}"
    }
    private suspend fun select(channelId: String): Offering {
        val candidates = feeds.offerings().filter { it.channelId == channelId }
        if (candidates.isEmpty()) throw ApiFailure(404, "CHANNEL_NOT_FOUND", "Unknown channel")
        val states = store.allHealth()
        return candidates.sortedBy { selectionRank(states[it.id]?.status) }
            .firstOrNull { states[it.id]?.let(::eligible) == true }
            ?: throw ApiFailure(503, "SOURCE_UNAVAILABLE", "No eligible source", true)
    }
    private fun active(id: String): LiveSession {
        reap()
        return sessions[id] ?: throw ApiFailure(410, "SESSION_EXPIRED", "Session was released or expired")
    }
    private fun reap() {
        val now = clock.instant()
        val expired = sessions.values.filter { it.expires <= now }
        expired.forEach { it.cancel(); sessions.remove(it.id) }
    }
}
fun eligible(state: dev.jamesrich.sofaking.backend.health.HealthState): Boolean = !state.manuallyDisabled && state.status in setOf(HealthStatus.UNVERIFIED, HealthStatus.HEALTHY, HealthStatus.SUSPECT)

/** Healthy first, then unverified, then suspect; anything else is never selected. */
internal fun selectionRank(status: HealthStatus?): Int =
    when (status) {
        HealthStatus.HEALTHY -> 0
        HealthStatus.UNVERIFIED -> 1
        HealthStatus.SUSPECT -> 2
        else -> 3
    }
