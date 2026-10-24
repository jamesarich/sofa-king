package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.PlexStatus
import dev.jamesrich.sofaking.backend.api.PolicyApplyResult
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.store.ignoredAttention
import dev.jamesrich.sofaking.backend.store.library
import io.ktor.server.routing.get
import io.ktor.server.routing.host
import java.time.Duration
import java.time.Instant

/** The home page's picture: what needs attention, the tiles' counts and what is playing now. */
internal class OverviewReader(private val portal: Portal) {
    private val runtime get() = portal.runtime

    suspend fun read(): OverviewView {
        val now = portal.clock.instant()
        val entries = runtime.lineup.entries()
        val policy = runtime.policy
        val applied = policy?.lastApplied()
        val coverage = runtime.guides.coverage
        val enabled = entries.filter { it.enabled }
        val byGuide = enabled.groupingBy { guideStatus(it, coverage, now) }.eachCount()
        val guide = GuideCoverage(
            byGuide["available"] ?: 0,
            byGuide["stale"] ?: 0,
            byGuide["empty"] ?: 0,
            byGuide["unassigned"] ?: 0,
        )
        val providers = runtime.feeds.statuses
        val plex = portal.plexStatus?.status()
        val stations = applied?.stations
        val requiredOff = stations?.count { it.required && !it.enabled }
        val all = (
            sourceAttention(now) + deviceAttention(plex) + tunerAttention(applied?.result, requiredOff) +
                lineupAttention(applied?.result, guide)
            ).sortedBy { listOf(Tone.BAD, Tone.WARN, Tone.OK, Tone.OFF).indexOf(it.tone) }
        // An ignored item stays out while its text is what it was when ignored; a change brings it back.
        val ignored = runtime.store.ignoredAttention()
        val (hidden, attention) = all.partition { ignored[it.key] == it.fingerprint }
        return OverviewView(
            attention = attention, ignored = hidden,
            stations = entries.size, enabled = enabled.size, guide = guide,
            required = stations?.count { it.required }, requiredOff = requiredOff,
            suggestions = applied?.result?.suggestedAliases?.size, findings = applied?.result?.findings?.size,
            unverified = stations?.count { it.disabledReason == "unverified" },
            providersOk = providers.count { it.errorCode == null && it.lastSuccessAt != null },
            providersTotal = providers.size,
            plex = plex?.let {
                PlexSummary(
                    it.lastSyncAt,
                    it.lastError,
                    it.devices.size,
                    it.devices.sumOf { d -> d.mapped },
                    it.missingDevices.size,
                    it.syncMinutes,
                )
            },
            sessions = sessionRows(now),
            playing = playingRows(),
            lastRun = applied?.result, policy = policy != null, setup = runtime.setup,
        )
    }

    /** Imports that failed, or that the server still waits for. */
    private fun sourceAttention(now: Instant): List<Attention> = buildList {
        val failed = runtime.feeds.statuses.filter { it.errorCode != null }
        if (failed.isNotEmpty()) {
            add(
                Attention(
                    failingTone(failed.map { it.lastSuccessAt }, now),
                    if (failed.size == 1) {
                        "${failed[0].id} ${failingFor(failed[0].lastSuccessAt, now) ?: "failed its last import"}"
                    } else {
                        "${failed.size} providers failed their last import"
                    },
                    failed.joinToString { failure(it.id, it.errorCode, it.lastSuccessAt, now) },
                    "/admin/providers#imports",
                    "Sources",
                    "providers-failed",
                    "troubleshooting.md#channels-and-sources",
                ),
            )
        }
        // Imports start at once, so a wait is worth a word only once the server has been up a while.
        val waiting = runtime.feeds.statuses.filter { it.errorCode == null && it.lastSuccessAt == null }
        if (waiting.isNotEmpty() && Duration.between(runtime.startedAt, now) > IMPORT_GRACE) {
            add(
                Attention(
                    Tone.WARN,
                    "Waiting for the first import of ${waiting.joinToString { it.id }}",
                    null,
                    "/admin/providers#imports",
                    "Sources",
                    "providers-waiting",
                    "live-tv.md#providers",
                ),
            )
        }
        val badGuides = runtime.guides.statuses.filter { it.errorCode != null }
        if (badGuides.isNotEmpty()) {
            add(
                Attention(
                    failingTone(badGuides.map { it.lastSuccessAt }, now),
                    if (badGuides.size == 1) {
                        "Guide ${badGuides[0].id} " +
                            "${failingFor(badGuides[0].lastSuccessAt, now) ?: "failed its last import"}"
                    } else {
                        "${badGuides.size} guides failed their last import"
                    },
                    badGuides.joinToString { failure(it.id, it.errorCode, it.lastSuccessAt, now) },
                    "/admin/providers#guides",
                    "Sources",
                    "guides-failed",
                    "troubleshooting.md#guide",
                ),
            )
        }
    }

    private fun failure(id: String, errorCode: String?, lastSuccessAt: String?, now: Instant): String =
        "$id ($errorCode${failingFor(lastSuccessAt, now)?.let { f -> ", $f" } ?: ""})"

    /** Refused tuner addresses, an unwritable recordings folder and a failing Plex sync. */
    private fun deviceAttention(plex: PlexStatus?): List<Attention> = buildList {
        val refused = if (portal.hdhr.enabled) runtime.hdhrAccess.refusals() else emptyList()
        if (refused.isNotEmpty()) {
            add(
                Attention(
                    Tone.WARN,
                    "The tuners refused ${plural(refused.size, "address", "addresses")}",
                    refused.joinToString { it.address } + ". Allow Plex's address on the Plex page.",
                    "/admin/plex#access",
                    "Plex",
                    "hdhr-refused",
                    "live-tv.md#plex-live-tv",
                ),
            )
        }
        val dvr = runtime.dvr
        if (dvr != null && !dvr.files.writable()) {
            add(
                Attention(
                    Tone.BAD,
                    "The server cannot write the recordings folder",
                    "${dvr.root}: every recording fails until the user the container runs as owns it. On the " +
                        "host: sudo chown -R UID:GID ./recordings, with the UID and GID from .env (1000 by " +
                        "default).",
                    "/admin/recordings",
                    "Recordings",
                    "recordings-unwritable",
                    "troubleshooting.md#recordings",
                ),
            )
        }
        if (plex?.lastError != null) {
            add(
                Attention(
                    Tone.BAD,
                    "Plex sync failed",
                    plex.lastError,
                    "/admin/plex#sync",
                    "Plex",
                    "plex-failed",
                    "troubleshooting.md#plex",
                ),
            )
        }
        if (!plex?.missingDevices.isNullOrEmpty()) {
            add(
                Attention(
                    Tone.WARN,
                    "${plural(plex.missingDevices.size, "tuner")} not in Plex",
                    plex.missingDevices.joinToString(),
                    "/admin/plex#tuners",
                    "Plex",
                    "plex-missing",
                    "live-tv.md#keep-plex-in-step",
                ),
            )
        }
    }

    /** Too few tuner devices for the lineup, and required channels that are off. */
    private fun tunerAttention(result: PolicyApplyResult?, requiredOff: Int?): List<Attention> = buildList {
        val needed = result?.devicesNeeded
        val configured = result?.devicesConfigured
        if (needed != null && configured != null && needed > configured) {
            add(
                Attention(
                    Tone.BAD,
                    "The lineup needs $needed tuners; $configured are configured",
                    "Add ports to SOFA_HDHR_EXTRA_PORTS, then add the devices in Plex.",
                    "/admin/plex#tuners",
                    "Plex",
                    "tuners-short",
                    "lineup-policy.md#hdhomerun-devices",
                ),
            )
        }
        if (requiredOff != null && requiredOff > 0) {
            add(
                Attention(
                    Tone.BAD,
                    "${plural(requiredOff, "required channel")} off",
                    null,
                    "/admin/required",
                    "Required",
                    "required-off",
                    "lineup-policy.md#required-stations",
                ),
            )
        }
    }

    /**
     * Merges to settle, unmapped categories and channels without programs. Run findings and stations waiting for a
     * probe are normal operation: the Merges and Groups tabs and the Sources tile carry them, so the home page does
     * not nag; unmapped categories show quietly, since their stations fall to the default group.
     */
    private fun lineupAttention(result: PolicyApplyResult?, guide: GuideCoverage): List<Attention> = buildList {
        val suggestions = result?.suggestedAliases?.size ?: 0
        if (suggestions > 0) {
            add(
                Attention(
                    Tone.WARN,
                    "${plural(suggestions, "merge suggestion")} to settle",
                    "Pairs of stations that are probably one channel.",
                    "/admin/merges#suggested",
                    "Merges",
                    "merges",
                    "lineup-policy.md#learned-aliases",
                ),
            )
        }
        val unmapped = result?.unmappedCategories?.size ?: 0
        if (unmapped > 0) {
            add(
                Attention(
                    Tone.OFF,
                    "${plural(unmapped, "category", "categories")} map to no group",
                    "Their stations fall to the default group; map one to choose.",
                    "/admin/groups#unmapped",
                    "Groups",
                    "unmapped",
                    "lineup-policy.md#unmapped-categories",
                ),
            )
        }
        val ended = guide.stale + guide.empty
        if (ended > 0) {
            add(
                Attention(
                    Tone.WARN,
                    "${plural(ended, "on-air channel")} show no programs",
                    "Their guide ended or holds nothing ahead.",
                    "/admin/lineup?state=enabled&guide=missing",
                    "Channels",
                    "guide-ended",
                    "troubleshooting.md#guide",
                ),
            )
        }
    }

    /** The library files and recordings being played, named as their pages name them. */
    suspend fun playingRows(): List<PlayingRow> {
        val active = runtime.vod?.snapshot().orEmpty()
        if (active.isEmpty()) return emptyList()
        val views = runtime.libraries?.catalog?.snapshot()?.views.orEmpty()
        return active.map { activity ->
            val title = activity.recordingId?.let { id ->
                runCatchingCancellable {
                    runtime.dvr?.recording(id)
                }.getOrNull()?.let { r -> listOfNotNull(r.title, r.episodeTitle).joinToString(" · ") }
                    ?: "Recording"
            } ?: views[activity.itemId]?.item?.let { item ->
                val episode = item.season?.let { s -> item.episode?.let { e -> "S${s}E$e" } }
                listOfNotNull(item.showTitle, episode, item.title).joinToString(" · ")
            } ?: "Removed item"
            PlayingRow(activity, title)
        }
    }

    /** The live sessions with their channel's name and the program it airs now, read once per guide. */
    private suspend fun sessionRows(now: Instant): List<SessionRow> {
        val active = runtime.sessions.snapshot()
        if (active.isEmpty()) return emptyList()
        val entries = active.associate { it.channelId to runtime.lineup.entry(it.channelId) }
        val onNow = HashMap<String, String>()
        val byGuide = entries.values.filterNotNull()
            .mapNotNull { entry -> entry.guide?.let { it.guideId to it.channelId } }
            .groupBy({ it.first }, { it.second })
        for ((guideId, group) in byGuide) {
            for ((channelId, programs) in runtime.guides.programs(guideId, group, now, now.plusSeconds(1))) {
                programs.firstOrNull()?.let { onNow["$guideId/$channelId"] = it.title }
            }
        }
        return active.map { session ->
            val entry = entries[session.channelId]
            SessionRow(session, entry?.name, entry?.guide?.let { onNow["${it.guideId}/${it.channelId}"] })
        }
    }
}

/** How long the server waits for a first import before the home page mentions it. */
private val IMPORT_GRACE = Duration.ofMinutes(10)

internal fun guideStatus(entry: LineupEntryRecord, coverage: Map<String, Map<String, Instant>>, now: Instant): String {
    val guide = entry.guide ?: return "unassigned"
    val ends = coverage[guide.guideId]?.get(guide.channelId) ?: return "empty"
    return if (ends > now) "available" else "stale"
}

/**
 * The tone of a failing import: warn while every last success is under a day old, bad after
 * that or with no success at all.
 */
internal fun failingTone(lastSuccesses: List<String?>, now: Instant): Tone = if (lastSuccesses.all {
        it != null && Duration.between(Instant.parse(it), now) < Duration.ofDays(1)
    }
) {
    Tone.WARN
} else {
    Tone.BAD
}

/**
 * "has failed for 3 days" once a day has passed since [lastSuccessAt]; "has never imported"
 * without one; null under a day.
 */
internal fun failingFor(lastSuccessAt: String?, now: Instant): String? {
    val since = lastSuccessAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "has never imported"
    val days = Duration.between(since, now).toDays()
    return if (days < 1) null else "has failed for ${plural(days.toInt(), "day")}"
}
