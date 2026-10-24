package dev.jamesrich.sofaking.backend.ads

import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.playback.AdBreaks
import dev.jamesrich.sofaking.backend.playback.AdClock
import dev.jamesrich.sofaking.backend.playback.AudioFollower
import dev.jamesrich.sofaking.backend.playback.SegmentAudio
import dev.jamesrich.sofaking.backend.playback.SessionManager
import dev.jamesrich.sofaking.backend.playback.SpliceReader
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
import dev.jamesrich.sofaking.backend.store.AdChannelRecord
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.adChannels
import dev.jamesrich.sofaking.backend.store.recordAdChannel
import dev.jamesrich.sofaking.backend.upstream.tsSyncOffset
import dev.jamesrich.sofaking.backend.upstream.withUpstreamResponse
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger(AdListener::class.java)

/**
 * Plays channels nobody is watching, headless, so the break library hears more ads than the sessions do and the
 * census covers channels nobody opened. Up to [slots] channels at once, each for [LISTEN] before its slot picks again
 * (see [pick]): channels watched lately that markers never covered come first, since that is where learned breaks pay
 * off, then channels watched lately with markers, whose breaks teach the library the ads the viewers see, then
 * channels never heard, then the least recently heard. A channel someone is watching is left to the session, and a
 * listener on it ends its turn when the viewer arrives. While anyone watches, at most [WHILE_WATCHING] listen, since
 * they share the household's connection with the viewers; [slots] is `SOFA_AD_LISTENERS`, 6 by default.
 *
 * A listener reads the lowest variant's media playlist (or its audio rendition) as a player would, through its own
 * [AdClock] in shadow, and hands its segments' audio to an [AudioFollower]: marked breaks are learned, and matches and
 * slates on unmarked channels are logged as `ADDETECT ... listener=true`. Nothing a listener finds marks a viewer's
 * playlist, since each session gets its own ads. It ends its turn with the channel's census. Encrypted and fMP4
 * playlists are skipped, and so is a provider that answers 403 or 429, or a channel whose turn played nothing, for
 * [COOLDOWN]. Pluto TV gives each device one channel at a time, so its channels are never listened to.
 */
class AdListener(
    private val http: HttpClient,
    private val feeds: FeedService,
    private val sessions: SessionManager,
    private val store: BackendStore,
    private val library: BreakLibrary,
    private val audio: SegmentAudio,
    private val permits: Semaphore,
    private val clock: Clock,
    /** The enabled channels of the lineup. */
    private val channels: () -> List<String>,
    /** How many channels to listen to at once; 0 stops listening. */
    private val slots: () -> Int,
    private val mode: () -> LearnedBreaks,
) {
    private val playing = ConcurrentHashMap<String, Job>()
    private val cooldown = ConcurrentHashMap<String, Instant>()

    /** Channels whose last turn played nothing, until when they wait. */
    private val resting = ConcurrentHashMap<String, Instant>()

    fun start(scope: CoroutineScope): Job = scope.launch(CoroutineName("ad-listener")) {
        // The server settles first: its guides, health and sessions come before background listening.
        delay(START_DELAY_MS)
        while (isActive) {
            runCatchingCancellable { reconcile(this) }.onFailure { log.warn("ad listener failed: {}", it.toString()) }
            delay(RECONCILE_MS)
        }
    }

    /** The channels being listened to now. */
    fun listening(): Set<String> = playing.keys.toSet()

    private suspend fun reconcile(scope: CoroutineScope) {
        playing.entries.removeIf { !it.value.isActive }
        val watching = sessions.watching()
        val wanted = wanted(mode(), watching, slots())
        playing.filterKeys { it in watching }.values.forEach { it.cancel() }
        playing.entries.drop(wanted).forEach { it.value.cancel() }
        val free = wanted - playing.count { it.value.isActive && it.key !in watching }
        if (free <= 0) return
        // A channel that cannot be listened to gives its turn to the next in line, so a run
        // of them never stalls the rest.
        val now = clock.instant()
        resting.values.removeIf { !it.isAfter(now) }
        val usable = { offering: Offering ->
            service(offering) !in NEVER &&
                cooldown[offering.providerId]?.isAfter(now) != true && offering.protocol == "hls"
        }
        for ((channel, offering) in sessions.listenable(
            pick(
                store.adChannels(),
                channels(),
                watching + playing.keys + resting.keys,
                now,
            ),
            free,
            usable,
        )) {
            playing[channel] = scope.launch(CoroutineName("ad-listener-$channel")) { listen(channel, offering) }
        }
    }

    private suspend fun listen(channel: String, offering: Offering) {
        val ads =
            AdClock(learning = {
                LearnedBreaks.SHADOW
            }, library = library, channelId = channel, providerId = offering.providerId) { found ->
                log.info(
                    "ADDETECT rule={} state={} marked=false lengthMs={} entry={} channel={} provider={} listener=true",
                    found.rule,
                    if (found.ended) "ended" else "started",
                    found.lengthMs,
                    found.entry ?: "-",
                    channel,
                    offering.providerId,
                )
            }
        log.info("ad listener plays channel={} provider={}", channel, offering.providerId)
        val until = clock.instant().plus(LISTEN)
        // The audio reader ends with the turn, rather than idling on after it.
        val reading =
            CoroutineScope(kotlin.coroutines.coroutineContext + SupervisorJob(kotlin.coroutines.coroutineContext[Job]))
        try {
            runCatchingCancellable { play(channel, offering, ads, until, reading) }.onFailure { e ->
                if (e is Refused) {
                    cooldown[offering.providerId] = clock.instant().plus(COOLDOWN)
                    log.info("ad listener backs off provider={} status={}", offering.providerId, e.status)
                } else {
                    log.info(
                        "ad listener channel={} provider={} stopped: {}",
                        channel,
                        offering.providerId,
                        e.toString(),
                    )
                }
            }
        } finally {
            reading.cancel()
            withContext(NonCancellable) {
                val census = ads.census()
                // A channel that played nothing would otherwise be first in line again at the next look.
                if (census.playedMs == 0L) resting[channel] = clock.instant().plus(COOLDOWN)
                if (census.playedMs > 0) {
                    log.info(
                        "ADCENSUS channel={} provider={} playedMs={} marked={} breaks={} learned={} listener=true",
                        channel,
                        offering.providerId,
                        census.playedMs,
                        census.marked,
                        census.breaks,
                        census.learned,
                    )
                    store.recordAdChannel(channel, offering.providerId, census, clock.instant(), listened = true)
                }
            }
        }
    }

    /** One turn's listening on [channel] until [until], or until someone watches or the stream says no. */
    private suspend fun play(
        channel: String,
        offering: Offering,
        ads: AdClock,
        until: Instant,
        reading: CoroutineScope,
    ) {
        val stream = feeds.playable(offering)
        val follower = AudioFollower(audio, ads, reading)
        val media = mediaPlaylist(stream) ?: return
        var last = Long.MIN_VALUE
        while (clock.instant().isBefore(until)) {
            // Someone started watching this channel: the turn ends now rather than at the next look.
            if (channel in sessions.watching()) return
            val (text, base) = fetchText(stream, media) ?: return
            if (text.contains("#EXT-X-KEY:METHOD=AES") || text.contains("#EXT-X-MAP:")) {
                log.info(
                    "ad listener skips channel={} provider={}: encrypted or fMP4 segments",
                    channel,
                    offering.providerId,
                )
                return
            }
            ads.annotate(text, base)
            val segments = AdBreaks.parse(text, base)?.segments.orEmpty()
            // A first look starts near the live edge, as a player does.
            val fresh = segments.filter { it.sequence > last }.let {
                if (last == Long.MIN_VALUE) {
                    it.takeLast(START_SEGMENTS)
                } else {
                    it
                }
            }
            for (segment in fresh) {
                val bytes = fetchBytes(stream, segment.url) ?: continue
                val cues = tsSyncOffset(bytes, 3)?.let { sync ->
                    SpliceReader().apply {
                        feed(
                            bytes,
                            sync,
                            bytes.size - sync,
                        )
                    }.finish()
                }
                ads.served(segment.url, cues)
                follower.offer(segment.url, bytes)
                last = segment.sequence
            }
            val target = segments.maxOfOrNull { it.durationMs } ?: POLL_MS.last
            delay((target / 2).coerceIn(POLL_MS))
        }
    }

    private class Refused(val status: Int) : Exception("upstream answered $status")

    /** The built-in service a native source plays from, such as `pluto`; null for a source file's own URL. */
    private fun service(offering: Offering): String? =
        if (offering.url.startsWith("svc://")) URI(offering.url).host else null

    /** The media playlist to follow: the lowest variant of a master, or its audio rendition when it has one. */
    private suspend fun mediaPlaylist(stream: ResolvedStream): String? {
        val (text, base) = fetchText(stream, stream.url) ?: return null
        return mediaOf(text, base)
    }

    private suspend fun fetchText(stream: ResolvedStream, url: String): Pair<String, URI>? =
        fetch(stream, url, MAX_PLAYLIST) {
                bytes,
                finalUrl,
            ->
            bytes.toString(Charsets.UTF_8) to URI(finalUrl)
        }

    private suspend fun fetchBytes(stream: ResolvedStream, url: String): ByteArray? = fetch(stream, url, MAX_SEGMENT) {
            bytes,
            _,
        ->
        bytes
    }

    private suspend fun <T> fetch(
        stream: ResolvedStream,
        url: String,
        limit: Long,
        read: (ByteArray, String) -> T,
    ): T? = permits.withPermit {
        http.withUpstreamResponse(url, stream.url, stream.headers, policy = feeds.upstream) { response ->
            val status = response.status
            if (status == HttpStatusCode.Forbidden || status == HttpStatusCode.TooManyRequests) {
                throw Refused(status.value)
            }
            if (status != HttpStatusCode.OK) return@withUpstreamResponse null
            val bytes = response.bodyAsChannel().readBuffer(limit + 1).readByteArray()
            if (bytes.size > limit) null else read(bytes, response.request.url.toString())
        }
    }

    companion object {
        /** How long a slot stays on one channel. */
        val LISTEN: Duration = Duration.ofMinutes(20)
        val COOLDOWN: Duration = Duration.ofHours(1)

        /** How recent a watch must be for a channel to come first. */
        val RECENT: Duration = Duration.ofDays(14)

        /** Built-in services whose channels are never listened to. */
        val NEVER = setOf("pluto")

        /** The most channels listened to while anyone watches. */
        const val WHILE_WATCHING = 2
        private const val RECONCILE_MS = 30_000L

        /** How long a listener waits between playlist reads: half the target segment length, within these bounds. */
        private val POLL_MS = 2_000L..6_000L
        private const val START_DELAY_MS = 300_000L
        private const val START_SEGMENTS = 3
        private const val MAX_PLAYLIST = 2L * 1024 * 1024
        private const val MAX_SEGMENT = 16L * 1024 * 1024

        /** The media playlist of [text], fetched from [base], to follow: see [AdListener]. */
        fun mediaOf(text: String, base: URI): String? {
            if (!text.contains("#EXT-X-STREAM-INF")) return base.toString()
            val lines = text.lines()
            val variant = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }
                .mapNotNull { i ->
                    lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.let {
                        lines[i] to
                            it.trim()
                    }
                }
                .minByOrNull { (info, _) ->
                    Regex("""BANDWIDTH=(\d+)""").find(info)?.groupValues?.get(1)?.toLongOrNull()
                        ?: Long.MAX_VALUE
                }
                ?: return null
            val group = Regex("""AUDIO="([^"]+)"""").find(variant.first)?.groupValues?.get(1)
            val rendition = group?.let { name ->
                lines.firstOrNull {
                    it.startsWith("#EXT-X-MEDIA:") && it.contains("TYPE=AUDIO") &&
                        it.contains("GROUP-ID=\"$name\"") && it.contains("URI=\"")
                }
                    ?.let { Regex("""URI="([^"]+)"""").find(it)?.groupValues?.get(1) }
            }
            return base.resolve(rendition ?: variant.second).toString()
        }

        /**
         * How many channels to listen to: [slots], at most [WHILE_WATCHING] while anyone is [watching], none with
         * learned breaks [LearnedBreaks.OFF].
         */
        fun wanted(mode: LearnedBreaks, watching: Set<String>, slots: Int): Int = when {
            mode == LearnedBreaks.OFF -> 0
            watching.isNotEmpty() -> minOf(slots, WHILE_WATCHING)
            else -> slots
        }

        /**
         * [lineup]'s channels in the order to listen to them, none of [busy]: watched within [RECENT] and never seen
         * marked first, the most recently watched first; then watched within [RECENT] with markers; then never heard;
         * then the least recently heard. Within the first two, a channel heard lately waits behind one that was not.
         */
        fun pick(
            known: List<AdChannelRecord>,
            lineup: List<String>,
            busy: Set<String>,
            now: Instant = Instant.now(),
        ): List<String> {
            val byChannel = known.associateBy { it.channelId }
            val since = now.minus(RECENT)
            fun recent(id: String) = byChannel[id]?.lastWatchedAt?.isAfter(since) == true
            fun heard(id: String) = byChannel[id]?.lastListenedAt ?: Instant.EPOCH
            val free = lineup.filter { it !in busy }
            val unmarked = free.filter { recent(it) && byChannel[it]?.markedAt == null }
                .sortedWith(
                    compareBy<String> {
                        heard(it) > now.minus(LISTEN)
                    }.thenByDescending { byChannel[it]?.lastWatchedAt },
                )
            val marked = free.filter { recent(it) && byChannel[it]?.markedAt != null }
                .sortedWith(
                    compareBy<String> {
                        heard(it) > now.minus(LISTEN)
                    }.thenByDescending { byChannel[it]?.lastWatchedAt },
                )
            val rest = free.filter { !recent(it) }.sortedBy { heard(it) }
            return unmarked + marked + rest
        }
    }
}
