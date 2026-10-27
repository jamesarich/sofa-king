package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.ads.BreakLibrary
import dev.jamesrich.sofaking.backend.api.ActiveSession
import dev.jamesrich.sofaking.backend.api.SessionStart
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.CatalogService
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.catalog.eligible
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.health
import dev.jamesrich.sofaking.contract.AdBreakState
import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.PlaybackDescriptor
import dev.jamesrich.sofaking.contract.PlaybackSession
import dev.jamesrich.sofaking.contract.SessionHealth
import dev.jamesrich.sofaking.contract.UpdatePlayback
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private val RESOURCE_SUFFIXES = setOf("m3u8", "ts", "m4s", "mp4", "aac", "vtt", "webvtt", "key", "bin")
private val INTENTS = setOf(Wire.Intent.PREVIEW, Wire.Intent.WATCH)

/** [external] sessions belong to players that never renew: the lease runs from their last media request. */
internal class LiveSession(
    val id: String,
    val request: OpenPlayback,
    var offering: Offering,
    var expires: Instant,
    val external: Boolean = false,
    /**
     * Whether a second external player may join it; a recording's session ends with its attempt, so it is never shared.
     */
    val shareable: Boolean = external,
    /** When the session opened, for the portal's Watching now. */
    val startedAt: Instant = expires,
    /** The address of the player that opened it, or of its last media request. */
    @Volatile var client: String? = null,
    /** The paired device whose token opened it; null for the shared token and players outside the app. */
    val clientId: String? = null,
    /**
     * The app's own sessions mark the ads in their playlists and say where the player is among them; players outside
     * the app get the source's playlists as they are.
     */
    val ads: AdClock? = if (external) null else AdClock(),
) {
    var epoch = 1
    var intent = request.intent

    /** Sources that failed to resolve for this session; it never goes back to them. */
    val failed = HashSet<String>()

    /** What the relay saw of the session's media, for the portal's Watching now. */
    val stats = SessionStats(startedAt)
    val bytes: AtomicLong get() = stats.bytes

    /** Sources the operator switched the session away from; Switch source passes over them until none is left. */
    val switchedFrom = HashSet<String>()
    val busy: Boolean get() = requests.any { it.isActive }

    /** Token to URL for every mapped resource; playlists are pinned, segments and keys share a 4,096-entry LRU. */
    val resources = ResourceMap()
    val requests = mutableSetOf<Job>()
    val playlists = SteadyPlaylists()

    /** Which of the session's media playlists stopped adding segments, to fail over from a stalled source. */
    val stalls = StallWatch()

    fun resource(url: String, suffix: String? = null): String {
        resources.tokenFor(url)?.let { return it }
        return resourceToken(url, suffix).also {
            resources.put(
                it,
                url,
                pinned =
                it.endsWith(".m3u8") || url == offering.url,
            )
        }
    }

    /**
     * The root token's suffix and MIME follow the offering's protocol, never the upstream path. It carries the
     * session's health as the relay has seen it up to [now].
     */
    fun descriptor(now: Instant): PlaybackSession {
        val hls = offering.protocol == "hls"
        val token = resource(offering.url, if (hls) "m3u8" else "ts")
        return PlaybackSession(
            id,
            request.channelId,
            intent,
            expires.toString(),
            PlaybackDescriptor(
                offering.protocol,
                if (hls) "application/vnd.apple.mpegurl" else "video/mp2t",
                "/media/sessions/$id/$epoch/$token",
                epoch,
            ),
            offering.providerId,
            SessionHealth(stats.bitrateKbps(now), stats.stalls.get(), stats.switches.get()),
        )
    }

    /** How the history names its mode: the app's intent, or a player outside the app's, or a recording's. */
    fun mode(): String = when {
        !external -> intent
        shareable -> "external"
        else -> "recording"
    }

    /** The session as the history records its start. */
    fun start(): SessionStart = SessionStart(
        id,
        HistoryKind.LIVE,
        clientId,
        client,
        channelId = request.channelId,
        providerId = offering.providerId,
        mode = mode(),
        startedAt = startedAt.toString(),
    )

    fun cancel(except: Job? = null) {
        requests.filter { it != except }.forEach { it.cancel() }
        requests.retainAll { it == except }
    }

    /** Stops the session's requests but [except] and moves it to a new epoch, whose resources map afresh. */
    fun restart(except: Job? = null) {
        cancel(except)
        epoch++
        resources.clear()
    }
}

/** A new opaque token for [url]: random, with [suffix] or the URL's own media extension, else `ts`. */
internal fun resourceToken(url: String, suffix: String? = null): String {
    val extension = java.net.URI(url).path.substringAfterLast('.', "").lowercase()
    return "${UUID.randomUUID()}.${suffix ?: if (extension in RESOURCE_SUFFIXES) extension else "ts"}"
}

/**
 * O(1) in both directions. Pinned entries (root and playlists) never leave; the rest is a
 * bounded LRU of segments and keys.
 */
internal class ResourceMap(private val segmentLimit: Int = 4096) {
    private val pinned = HashMap<String, String>()
    private val segments = object : java.util.LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            if (size <= segmentLimit) return false
            tokens.remove(eldest.value)
            return true
        }
    }
    private val tokens = HashMap<String, String>()
    val size: Int get() = pinned.size + segments.size
    operator fun get(token: String): String? = pinned[token] ?: segments[token]
    fun tokenFor(url: String): String? = tokens[url]?.also { token -> segments[token] }
    fun put(token: String, url: String, pinned: Boolean) {
        tokens[url] = token
        if (pinned) this.pinned[token] = url else segments[token] = url
    }
    fun clear() {
        pinned.clear()
        segments.clear()
        tokens.clear()
    }
}

/**
 * [shared] marks the live MPEG-TS root, which one upstream connection fans out to every session on the source;
 * [stats] counts what the relay sends the player on this session. [ads] is the session's [AdClock], which marks the
 * playlists and notes the segments fetched, on the app's own sessions only.
 */
data class MediaResource(
    val offering: Offering,
    val url: String,
    val shared: Boolean = false,
    val stats: SessionStats = SessionStats(Instant.EPOCH),
    val ads: AdClock? = null,
    val playlists: SteadyPlaylists = SteadyPlaylists(),
    val stalls: StallWatch = StallWatch(),
)

/**
 * The playback sessions: the app's, opened and renewed through the API, and through [external] those of players that
 * send no API calls. [media] serves their requests and [sources] picks what they play.
 */
class SessionManager(
    private val catalog: CatalogService,
    private val store: BackendStore,
    private val clock: Clock,
    capacity: Int = 4,
) {
    private val table = SessionTable(clock, capacity)

    /** Which of a channel's sources a session plays. */
    val sources = SourceChoice(catalog, store)

    /** The media requests of the sessions. */
    val media = SessionMedia(table, catalog, store, clock, sources)

    /** The sessions of players that send no API calls. */
    val external = ExternalSessions(table, clock, sources)

    /** How many sessions may be open at once; the runtime settings change it without a restart. */
    var capacity: Int
        get() = table.capacity
        set(value) {
            table.capacity = value
        }

    /**
     * What the app's sessions do with the breaks they learn (see [AdClock]); the runtime
     * settings change it without a restart.
     */
    @Volatile var learnedBreaks: LearnedBreaks = LearnedBreaks.SHADOW

    /** The break library the app's sessions learn into and match against; none until the runtime sets it. */
    @Volatile var library: BreakLibrary? = null

    /**
     * Gets what each app session that played found on its channel, as it ends: the
     * channel, its provider and the census.
     */
    var onCensus: (String, String, AdCensus) -> Unit
        get() = table.onCensus
        set(value) {
            table.onCensus = value
        }

    /** Where each session's start and end is recorded; none until the runtime sets it. */
    var history: SessionHistory?
        get() = table.history
        set(value) {
            table.history = value
        }

    private val sessions get() = table.sessions
    private val keys = linkedMapOf<String, Pair<OpenPlayback, String>>()

    /** [from] is the player opening the session: its address, shown to the operator, and its paired device. */
    suspend fun open(request: OpenPlayback, from: PlaybackClient? = null): PlaybackSession = table.mutex.withLock {
        table.reap()
        if (request.intent !in INTENTS || request.idempotencyKey.length !in 1..MAX_KEY_LENGTH) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid playback intent or idempotency key")
        }
        keys[request.idempotencyKey]?.let { (original, id) ->
            if (original != request) {
                throw ApiFailure(
                    HttpStatusCode.Conflict,
                    "IDEMPOTENCY_CONFLICT",
                    "Key already used for another request",
                )
            }
            return@withLock sessions[id]?.descriptor(clock.instant())
                ?: throw ApiFailure(
                    HttpStatusCode.Gone,
                    Wire.ErrorCode.SESSION_EXPIRED,
                    "Session was released or expired",
                )
        }
        // Chosen first, so a channel that cannot play never costs another viewer their slot.
        val offering = sources.select(request.channelId)
        table.makeRoom()
        val now = clock.instant()
        val id = UUID.randomUUID().toString()
        val ads =
            AdClock(
                learning = { learnedBreaks },
                library = library,
                channelId = request.channelId,
                providerId = offering.providerId,
            ) { found ->
                learnedLog.info(
                    "ADDETECT rule={} state={} marked={} lengthMs={} entry={} channel={} provider={} session={}",
                    found.rule, if (found.ended) "ended" else "started", found.marked, found.lengthMs,
                    found.entry ?: "-", request.channelId, offering.providerId, id,
                )
            }
        val session =
            LiveSession(
                id,
                request,
                offering,
                now.plus(lease(request.intent)),
                startedAt = now,
                client = from?.address,
                clientId = from?.clientId,
                ads = ads,
            )
        table.add(session)
        keys[request.idempotencyKey] = request to session.id
        while (keys.size > REMEMBERED_KEYS) keys.remove(keys.keys.first())
        session.descriptor(clock.instant())
    }

    suspend fun count(): Int = table.mutex.withLock {
        table.reap()
        sessions.size
    }

    suspend fun snapshot(): List<ActiveSession> = table.mutex.withLock {
        table.reap()
        val now = clock.instant()
        sessions.values.map {
            ActiveSession(
                it.id, it.request.channelId, it.intent, it.external, it.busy, it.offering.providerId,
                it.expires.toString(), it.startedAt.toString(), it.client, it.bytes.get(),
                offeringId = it.offering.id,
                bitrateKbps = it.stats.bitrateKbps(now),
                stalls = it.stats.stalls.get(),
                segmentsServed = it.stats.segments.get(),
                sourceSwitches = it.stats.switches.get(),
            )
        }
    }

    /** The session's descriptor, on a source it moves to when its own is gone, ineligible or has a new URL. */
    suspend fun get(id: String): PlaybackSession = table.mutex.withLock {
        val session = table.active(id)
        val health = store.health(session.offering.id)
        val refreshed = catalog.offering(session.offering.id)
        if (refreshed == null || health == null || !eligible(health)) {
            session.restart()
            session.offering = sources.select(session.request.channelId, session.failed)
            session.stats.switches.incrementAndGet()
        }
        if (refreshed != null && session.offering.id == refreshed.id && session.offering.url != refreshed.url) {
            session.restart()
            session.offering = refreshed
        }
        session.descriptor(clock.instant())
    }

    suspend fun update(id: String, update: UpdatePlayback): PlaybackSession = table.mutex.withLock {
        val session = table.active(id)
        if (update.intent != null && update.intent !in INTENTS) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid playback intent")
        }
        update.intent?.let { session.intent = it }
        val now = clock.instant()
        session.expires = now.plus(lease(session.intent))
        session.descriptor(now)
    }

    /** Where the session's player is among the ads, given [aheadMs] of media it has loaded past the picture. */
    suspend fun adBreak(id: String, aheadMs: Long): AdBreakState =
        table.mutex.withLock { table.active(id) }.ads?.at(aheadMs) ?: AdBreakState()

    /** Ends the session; true when it was open. */
    suspend fun release(id: String): Boolean = table.mutex.withLock {
        sessions[id]?.also { table.end(it, HistoryReason.RELEASED) } !=
            null
    }

    suspend fun healthChanged(offeringId: String) = table.mutex.withLock {
        if (store.health(offeringId)?.let(::eligible) != true) {
            sessions.values.filter { it.offering.id == offeringId }.forEach { it.restart() }
        }
    }

    suspend fun expire() = table.mutex.withLock { table.reap() }

    suspend fun close() = table.mutex.withLock {
        sessions.values.toList().forEach { table.end(it, HistoryReason.STOPPED) }
    }

    /** The channels sessions are playing now. */
    suspend fun watching(): Set<String> = table.mutex.withLock {
        sessions.values.mapTo(HashSet()) { it.request.channelId }
    }

    companion object {
        /** How long a player may go without a media request before its session is released. */
        val EXTERNAL_IDLE: Duration = Duration.ofSeconds(60)

        private val WATCH_LEASE: Duration = Duration.ofSeconds(90)
        private val PREVIEW_LEASE: Duration = Duration.ofSeconds(45)

        /** The longest idempotency key a player may send, and how many recent ones are kept. */
        private const val MAX_KEY_LENGTH = 128
        private const val REMEMBERED_KEYS = 4096

        /** Leases outlast the app's 15-second renewal several times over; abandoned previews free their slot sooner. */
        private fun lease(intent: String): Duration = if (intent == Wire.Intent.WATCH) WATCH_LEASE else PREVIEW_LEASE
    }
}
