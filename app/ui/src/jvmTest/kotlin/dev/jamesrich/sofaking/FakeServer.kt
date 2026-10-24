package dev.jamesrich.sofaking

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.contract.AdBreakState
import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.Bootstrap
import dev.jamesrich.sofaking.contract.Capabilities
import dev.jamesrich.sofaking.contract.Catalog
import dev.jamesrich.sofaking.contract.Channel
import dev.jamesrich.sofaking.contract.ChannelGuide
import dev.jamesrich.sofaking.contract.GuideQuery
import dev.jamesrich.sofaking.contract.GuideWindow
import dev.jamesrich.sofaking.contract.Library
import dev.jamesrich.sofaking.contract.LibraryHome
import dev.jamesrich.sofaking.contract.LibraryItem
import dev.jamesrich.sofaking.contract.LibraryItemDetail
import dev.jamesrich.sofaking.contract.LibraryPage
import dev.jamesrich.sofaking.contract.LibraryShelf
import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.OpenVodPlayback
import dev.jamesrich.sofaking.contract.PairingResult
import dev.jamesrich.sofaking.contract.PlayState
import dev.jamesrich.sofaking.contract.PlaybackDescriptor
import dev.jamesrich.sofaking.contract.PlaybackSession
import dev.jamesrich.sofaking.contract.Program
import dev.jamesrich.sofaking.contract.Recording
import dev.jamesrich.sofaking.contract.RecordingBreak
import dev.jamesrich.sofaking.contract.RecordingList
import dev.jamesrich.sofaking.contract.RecordingRuleList
import dev.jamesrich.sofaking.contract.UpdatePlayState
import dev.jamesrich.sofaking.contract.UpdatePlayback
import dev.jamesrich.sofaking.contract.VodSession
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A server on loopback with a lineup, a day of guide, a movie library and a show library, and, unless [recordings] is
 * false, a few finished recordings. Its playback sessions, live and library, point at streams that go nowhere, since
 * the tests have no decoder; it counts the guide queries and the sessions the app holds open.
 */
internal class FakeServer(private val recordings: Boolean = true) {
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url = "http://127.0.0.1:${http.address.port}"
    val guideQueries = AtomicInteger()
    val sessionsOpened = AtomicInteger()

    /** Each open live session's channel and intent, so a re-read or a renewal answers with what the app opened. */
    private val liveSessions: MutableMap<String, Pair<String, String>> = Collections.synchronizedMap(mutableMapOf())
    private val vodSessions: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())

    /** The live and library sessions the app holds open now. */
    val openSessions: Int get() = liveSessions.size + vodSessions.size

    /** Whether a live session is open to watch full screen rather than to preview. */
    val watching: Boolean get() = synchronized(liveSessions) {
        liveSessions.values.any { it.second == Wire.Intent.WATCH }
    }

    private val started = Clock.System.now()
    private val channels = List(CHANNELS) { i ->
        Channel(
            id = "ch$i",
            displayNumber = "${i + 2}",
            name = "Channel ${i + 2}",
            tags = listOf(GROUPS[i % GROUPS.size]),
            legacyGuideId = "ch$i",
            playbackEligible = true,
        )
    }
    private val movies = Library(MOVIES, "Movies", Wire.LibraryKind.MOVIES)
    private val shows = Library(SHOWS, "TV", Wire.LibraryKind.SHOWS)
    private val items: Map<String, LibraryItem> = buildMap {
        repeat(MOVIE_COUNT) { i ->
            put(
                "movie$i",
                item(
                    "movie$i",
                    MOVIES,
                    Wire.ItemKind.MOVIE,
                    "Movie ${i + 1}",
                    year = 1990 + i,
                    durationMs = 90.minutes.inWholeMilliseconds,
                    genres = listOf(
                        GENRES[
                            i %
                                GENRES.size,
                        ],
                    ),
                ),
            )
        }
        repeat(SHOW_COUNT) { s ->
            val show = "show$s"
            put(
                show,
                item(
                    show,
                    SHOWS,
                    Wire.ItemKind.SHOW,
                    "Show ${s + 1}",
                    year = 2010 + s,
                    children = SEASONS,
                    unwatched =
                    SEASONS * EPISODES,
                ),
            )
            repeat(SEASONS) { n ->
                val season = "$show-s${n + 1}"
                put(
                    season,
                    item(
                        season, SHOWS, Wire.ItemKind.SEASON, "Season ${n + 1}", parent = show, show = show,
                        season =
                        n + 1,
                        children = EPISODES, unwatched = EPISODES,
                    ),
                )
                repeat(EPISODES) { e ->
                    val episode = "$season-e${e + 1}"
                    put(
                        episode,
                        item(
                            episode, SHOWS, Wire.ItemKind.EPISODE, "Episode ${e + 1}", parent = season, show = show,
                            season =
                            n + 1,
                            episode = e + 1, durationMs = 30.minutes.inWholeMilliseconds,
                        ),
                    )
                }
            }
        }
    }

    init {
        http.createContext("/api/v1/") { exchange ->
            try {
                handle(exchange)
            } finally {
                exchange.close()
            }
        }
        http.start()
    }

    fun stop() = http.stop(0)

    private fun handle(exchange: HttpExchange) {
        val request = Request(exchange)
        if (!live(exchange, request) && !library(exchange, request)) exchange.notFound(request.path)
    }

    /** One request's path under `/api/v1/`, its query, method and body. */
    private class Request(exchange: HttpExchange) {
        val path: String = exchange.requestURI.path.removePrefix("/api/v1/")
        val query: Map<String, String> = exchange.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        val method: String = exchange.requestMethod
        val body: String = exchange.requestBody.readBytes().decodeToString()
    }

    /** The pairing, lineup, guide, preferences, recordings and live session routes; false for any other path. */
    private fun live(exchange: HttpExchange, request: Request): Boolean {
        val path = request.path
        when {
            path == "pair" -> exchange.json(200, PairingResult("f".repeat(64), "fuzz"))

            path == "bootstrap" -> exchange.json(200, bootstrap())

            path == "channels" -> exchange.json(200, Catalog("r1", channels))

            path == "guide/query" -> {
                guideQueries.incrementAndGet()
                exchange.json(200, guide(JSON.decodeFromString(request.body)))
            }

            path.startsWith("preferences/") -> exchange.text(200, if (request.method == "PUT") request.body else "{}")

            path == "recordings" && request.method == "GET" -> exchange.json(
                200,
                RecordingList(if (recordings) recordingList() else emptyList()),
            )

            path == "recording-rules" && request.method == "GET" -> exchange.json(200, RecordingRuleList())

            path == "playback-sessions" -> {
                val open = JSON.decodeFromString<OpenPlayback>(request.body)
                val id = UUID.randomUUID().toString()
                liveSessions[id] = open.channelId to open.intent
                sessionsOpened.incrementAndGet()
                exchange.json(201, session(id, open.channelId, open.intent))
            }

            path.endsWith("/ad-break") -> exchange.json(200, AdBreakState())

            path.startsWith("playback-sessions/") ->
                liveSession(exchange, path.removePrefix("playback-sessions/"), request)

            else -> return false
        }
        return true
    }

    private fun bootstrap() = Bootstrap(
        serverId = "fuzz",
        serverTime = Clock.System.now().toString(),
        capabilities = Capabilities(
            catalog = true,
            guide = true,
            playback = true,
            viewers = true,
            library = true,
            recordings = true,
        ),
    )

    /** Renews, retargets or releases the live session [id]. */
    private fun liveSession(exchange: HttpExchange, id: String, request: Request) {
        val open = liveSessions[id]
        when {
            request.method == "DELETE" -> {
                liveSessions -= id
                exchange.sendResponseHeaders(204, -1)
            }

            open == null -> exchange.json(410, ApiError(Wire.ErrorCode.SESSION_EXPIRED, "gone"))

            else -> {
                val now = JSON.decodeFromString<UpdatePlayback>(request.body.ifEmpty { "{}" }).intent
                    ?.let { open.first to it }
                    ?: open
                liveSessions[id] = now
                exchange.json(200, session(id, now.first, now.second))
            }
        }
    }

    /** The library and library session routes; false for any other path. */
    private fun library(exchange: HttpExchange, request: Request): Boolean {
        val path = request.path
        when {
            path == "library/home" -> exchange.json(200, home())

            path == "library/search" -> {
                val q = request.query["q"].orEmpty()
                exchange.json(
                    200,
                    LibraryPage(
                        items.values.filter { it.parentId == null && it.title.contains(q, ignoreCase = true) },
                    ),
                )
            }

            path.startsWith("libraries/") && path.endsWith("/items") -> {
                val library = path.removePrefix("libraries/").removeSuffix("/items")
                exchange.json(200, page(library, request.query))
            }

            path.startsWith("library/items/") && path.endsWith("/play-state") -> playState(exchange, request)

            path.startsWith("library/items/") -> detail(path.removePrefix("library/items/"))?.let {
                exchange.json(200, it)
            }
                ?: exchange.notFound(path)

            path == "vod-sessions" -> {
                val open = JSON.decodeFromString<OpenVodPlayback>(request.body)
                val id = UUID.randomUUID().toString()
                vodSessions[id] = open.itemId
                sessionsOpened.incrementAndGet()
                exchange.json(201, vod(id, open.itemId, open.startMs))
            }

            path.startsWith("vod-sessions/") -> vodSession(exchange, path.removePrefix("vod-sessions/"), request)

            else -> return false
        }
        return true
    }

    /** Saves an item's position and answers with the item as it now stands. */
    private fun playState(exchange: HttpExchange, request: Request) {
        val item = items[request.path.removePrefix("library/items/").removeSuffix("/play-state")]
        val update = JSON.decodeFromString<UpdatePlayState>(request.body)
        if (item == null) {
            exchange.notFound(request.path)
        } else {
            exchange.json(
                200,
                item.copy(
                    playState = PlayState(
                        update.positionMs,
                        update.durationMs,
                        update.watched ?: false,
                        Clock.System.now().toString(),
                    ),
                ),
            )
        }
    }

    /** Renews or releases the library session [id]. */
    private fun vodSession(exchange: HttpExchange, id: String, request: Request) {
        val item = vodSessions[id]
        when {
            request.method == "DELETE" -> {
                vodSessions -= id
                exchange.sendResponseHeaders(204, -1)
            }

            item == null -> exchange.json(410, ApiError(Wire.ErrorCode.SESSION_EXPIRED, "gone"))

            else -> exchange.json(200, vod(id, item, 0))
        }
    }

    /** Half-hour programs on every channel the query names, across its window. */
    private fun guide(query: GuideQuery): GuideWindow {
        val from = Instant.parse(query.from)
        val to = Instant.parse(query.to)
        val slots = generateSequence(from) { it + 30.minutes }.takeWhile { it < to }.toList()
        return GuideWindow(
            revision = "g1",
            from = query.from,
            to = query.to,
            channels = query.channelIds.map { id ->
                ChannelGuide(
                    channelId = id,
                    status = Wire.GuideStatus.AVAILABLE,
                    programs = slots.map { start ->
                        Program(
                            id = "$id-${start.epochSeconds}",
                            channelId = id,
                            startsAt = start.toString(),
                            endsAt = (start + 30.minutes).toString(),
                            title = "Show at ${start.epochSeconds % 86_400 / 1_800}",
                            description = "A program.",
                        )
                    },
                )
            },
        )
    }

    private fun session(id: String, channel: String, intent: String) = PlaybackSession(
        id = id,
        channelId = channel,
        intent = intent,
        expiresAt = (Clock.System.now() + 45.minutes).toString(),
        descriptor = PlaybackDescriptor("hls", HLS, "/media/sessions/$id/1/x.m3u8", 1),
    )

    private fun vod(id: String, item: String, startMs: Long) = VodSession(
        id = id,
        itemId = item,
        mode = Wire.VodMode.REMUX,
        descriptor = PlaybackDescriptor("hls", HLS, "/media/vod/$id/1/x/index.m3u8", 1),
        startMs = startMs,
        durationMs = items[item]?.durationMs,
        expiresAt = (Clock.System.now() + 5.minutes).toString(),
    )

    private fun recordingList() = List(3) { i ->
        val start = started - (i + 1).days
        Recording(
            id = "rec$i",
            channelId = channels[i].id,
            channelName = channels[i].name,
            title = "Recorded show ${i + 1}",
            startsAt = start.toString(),
            endsAt = (start + 1.hours).toString(),
            state = Wire.RecordingState.COMPLETED,
            bytes = 1_000_000,
            commercials = Wire.CommercialScan.DONE,
            breaks = listOf(RecordingBreak(600_000, 780_000)),
        )
    }

    private fun home() = LibraryHome(
        shelves = listOf(
            LibraryShelf(
                Wire.ShelfId.CONTINUE,
                "Continue watching",
                listOf(items.getValue("show0-s1-e2"), items.getValue("movie3")).map {
                    it.copy(playState = PlayState(600_000, it.durationMs))
                },
            ),
            LibraryShelf(Wire.ShelfId.RECENT, "Recently added in Movies", topLevel(MOVIES).take(SHELF), MOVIES),
            LibraryShelf(Wire.ShelfId.RECENT, "Recently added in TV", topLevel(SHOWS).take(SHELF), SHOWS),
        ),
        libraries = listOf(movies.copy(items = MOVIE_COUNT), shows.copy(items = SHOW_COUNT)),
    )

    /** One page of a library from the offset its cursor names, with the genre filter applied; the sort is ignored. */
    private fun page(library: String, query: Map<String, String>): LibraryPage {
        val all = topLevel(library).filter { item -> query["genre"]?.let { it in item.genres } ?: true }
        val from = query["cursor"]?.toIntOrNull() ?: 0
        val limit = query["limit"]?.toIntOrNull() ?: all.size
        val next = (from + limit).takeIf { it < all.size }?.toString()
        return LibraryPage(all.drop(from).take(limit), all.size, next, if (library == MOVIES) GENRES else emptyList())
    }

    private fun detail(id: String): LibraryItemDetail? {
        val item = items[id] ?: return null
        val children = items.values.filter { it.parentId == id }
        val next = when (item.kind) {
            Wire.ItemKind.SHOW -> items["$id-s1-e2"]
            else -> null
        }
        return LibraryItemDetail(item = item, children = children, next = next)
    }

    private fun topLevel(library: String) = items.values.filter { it.libraryId == library && it.parentId == null }

    private fun item(
        id: String,
        library: String,
        kind: String,
        title: String,
        parent: String? = null,
        show: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        durationMs: Long? = null,
        genres: List<String> = emptyList(),
        children: Int = 0,
        unwatched: Int = 0,
    ) = LibraryItem(
        id = id,
        libraryId = library,
        kind = kind,
        title = title,
        parentId = parent,
        showId = show,
        showTitle = show?.let { "Show ${it.removePrefix("show").toInt() + 1}" },
        year = year,
        season = season,
        episode = episode,
        durationMs = durationMs,
        addedAt = started.toString(),
        genres = genres,
        children = children,
        unwatched = unwatched,
    )

    private inline fun <reified T> HttpExchange.json(status: Int, value: T) = text(status, JSON.encodeToString(value))

    private fun HttpExchange.notFound(path: String) = json(404, ApiError("NOT_FOUND", path))

    private fun HttpExchange.text(status: Int, text: String) {
        val bytes = text.encodeToByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }

    private companion object {
        const val CHANNELS = 40
        const val MOVIES = "movies"
        const val SHOWS = "shows"
        const val MOVIE_COUNT = 30
        const val SHOW_COUNT = 3
        const val SEASONS = 2
        const val EPISODES = 4
        const val SHELF = 10
        const val HLS = "application/vnd.apple.mpegurl"
        val GROUPS = listOf("News", "Sports", "Movies", "Kids", "Music")
        val GENRES = listOf("Drama", "Comedy", "Action")
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
