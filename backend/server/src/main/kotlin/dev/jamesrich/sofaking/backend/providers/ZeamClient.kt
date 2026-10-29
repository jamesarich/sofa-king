package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import java.time.Duration
import java.time.Instant

/**
 * Zeam (zeam.com), the free local-news and FAST service: the web guide page inlines the whole lineup as a
 * `var json=` document whose channels carry a public `schedule.json` each, and `StreamInfo` answers a master playlist
 * for a station. That playlist's token is bound to the caller's IP and lapses within about 90 seconds, and the
 * session's variant and key URLs answer only the User-Agent that fetched it, so the same one goes with every request.
 */
class ZeamClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "zeam"

    private val siteBase = (context.options["siteBase"] ?: SITE_BASE).trimEnd('/')

    private class Listed(val channel: ProviderChannel, val scheduleUrl: String?)

    private class Row(
        val title: String,
        val description: String?,
        val subTitle: String?,
        val start: Instant,
        val end: Instant,
    )

    override suspend fun channels(): List<ProviderChannel> = listed().map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed()
        val index = channels.map { it.channel.toGuideChannel() }
        val targets = channels.filter { it.channel.id in wanted && it.scheduleUrl != null }
        if (targets.isEmpty()) return ParsedGuide(index, emptyList())
        val now = context.clock.instant()
        val permits = Semaphore(SCHEDULE_WORKERS)
        val fetched = coroutineScope {
            targets.map { target ->
                async { permits.withPermit { target to schedule(target.scheduleUrl.orEmpty()) } }
            }.awaitAll()
        }
        // One schedule that cannot be read is that channel without a guide; all of them is the service being down.
        if (fetched.all { it.second.isFailure }) throw ProviderFailure("Zeam schedules are unavailable")
        val programs = fetched.flatMap { (target, rows) ->
            singleTimeline(rows.getOrNull().orEmpty().filter { it.end > now }).map {
                GuideProgram(target.channel.id, it.start, it.end, it.title, it.description, subTitle = it.subTitle)
            }
        }
        return ParsedGuide(index, programs)
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        context.findListed(CHANNELS) { listed().firstOrNull { it.channel.id == channelId } }
        val info = context.http.get(
            "$siteBase/api/services/StreamInfo?stationId=${urlEncoded(channelId)}",
            HEADERS,
        ).require("Zeam stream info").json()
        val url = httpOrNull(info.str("streamUrl")) ?: throw ProviderFailure("Zeam stream info has no stream")
        return ResolvedStream(url, STREAM_HEADERS, context.clock.instant().plus(TOKEN_LIFETIME))
    }

    private suspend fun listed(): List<Listed> = context.cached(CHANNELS, CACHED_FOR) { fetchChannels() }

    private suspend fun fetchChannels(): List<Listed> {
        val page = context.http.get("$siteBase/live", HEADERS).require("Zeam live page").text()
        val groups = inlineJson(page)["groups"].array
        val lineup = groups.firstOrNull { it.str("displayName") == ALL_CHANNELS } ?: groups.firstOrNull()
        val result = lineup["channelobjects"].array.mapNotNull(::listing).distinctBy { it.channel.id }
        requireChannels(result)
        return result
    }

    /** A listed channel; null when its station id or name is missing. */
    private fun listing(row: JsonElement): Listed? {
        val id = row["stationId"].long?.takeIf { it > 0 }?.toString() ?: return null
        val name = row.str("name") ?: return null
        val logo = row["images"].array.filter { it["typeId"].long == LOGO_IMAGE }.maxByOrNull { it["size"].long ?: 0 }
        val schedules = row["scheduleUrls"]
        return Listed(
            ProviderChannel(id, name, logo = logo.str("url")?.let(::absolute), tags = listOf("us")),
            httpOrNull(schedules.str("v4") ?: row.str("scheduleUrl")),
        )
    }

    /** The URL as an absolute one; the page lists its image hosts without a scheme. */
    private fun absolute(url: String): String? =
        httpOrNull(if (url.startsWith("http")) url else "https://${url.trimStart('/')}")

    private suspend fun schedule(url: String): Result<List<Row>> = runCatchingCancellable {
        val response = context.http.get(url, HEADERS)
        // A station can list a schedule file its CDN no longer serves; that is no guide, not a failure.
        if (response.status in GONE_STATUSES || response.refused) {
            emptyList()
        } else {
            response.require("Zeam schedule").json()["schedule"].array.mapNotNull(::row)
        }
    }

    /** A schedule row; null when its times or title are missing, it is blacked out, or it ends before it starts. */
    private fun row(row: JsonElement): Row? {
        val start = row["startTime"].long ?: return null
        val end = row["endTime"].long ?: row["duration"].long?.let { start + it } ?: return null
        val title = row.str("name") ?: return null
        if (end <= start || row["blackout"].truthy) return null
        return Row(
            title,
            row.str("description"),
            row.str("episodeTitle")?.takeIf { it != title },
            Instant.ofEpochSecond(start),
            Instant.ofEpochSecond(end),
        )
    }

    /** Some feeds nest their rows; a row that starts inside the previous one starts where it ends, or is dropped. */
    private fun singleTimeline(rows: List<Row>): List<Row> {
        val result = ArrayList<Row>()
        for (row in rows.sortedWith(compareBy({ it.start }, { it.end }))) {
            val last = result.lastOrNull()
            when {
                last == null || row.start >= last.end -> result += row
                row.end > last.end -> result += Row(row.title, row.description, row.subTitle, last.end, row.end)
            }
        }
        return result
    }

    companion object {
        private const val SITE_BASE = "https://zeam.com"
        private const val CHANNELS = "channels"
        private const val ALL_CHANNELS = "All Channels"
        private const val INLINE_MARKER = "var json="
        private const val LOGO_IMAGE = 1L
        private const val SCHEDULE_WORKERS = 6

        /** How long the listing is kept before it is fetched again. */
        private val CACHED_FOR: Duration = Duration.ofMinutes(10)

        /** The master playlist's token lapses in about 90 seconds. */
        private val TOKEN_LIFETIME: Duration = Duration.ofSeconds(90)

        /** The one User-Agent the master is fetched with and every variant and key request must repeat. */
        private val STREAM_HEADERS = mapOf("User-Agent" to BROWSER_USER_AGENT)
        private val HEADERS = STREAM_HEADERS + mapOf("Accept" to "*/*", "Referer" to "https://zeam.com/live")

        /** The document after `var json=` in [page], cut out by brace depth; the script around it need not be JSON. */
        private fun inlineJson(page: String): JsonElement {
            val start = page.indexOf(INLINE_MARKER).takeIf { it >= 0 }?.let { it + INLINE_MARKER.length }
                ?: throw ProviderFailure("Zeam live page has no channel data")
            val end = objectEnd(page, start) ?: throw ProviderFailure("Zeam live page's channel data is cut off")
            return try {
                lenientJson.parseToJsonElement(page.substring(start, end))
            } catch (e: SerializationException) {
                throw ProviderFailure("Zeam live page's channel data is not JSON", cause = e)
            }
        }

        /** Just past the `}` closing the object that opens at [start] in [text]; null if it does not open or close. */
        private fun objectEnd(text: String, start: Int): Int? {
            if (text.getOrNull(start) != '{') return null
            var depth = 0
            var quoted = false
            var escaped = false
            for (index in start until text.length) {
                val char = text[index]
                when {
                    escaped -> escaped = false
                    quoted && char == '\\' -> escaped = true
                    char == '"' -> quoted = !quoted
                    quoted -> Unit
                    char == '{' -> depth++
                    char == '}' -> if (--depth == 0) return index + 1
                }
            }
            return null
        }
    }
}
