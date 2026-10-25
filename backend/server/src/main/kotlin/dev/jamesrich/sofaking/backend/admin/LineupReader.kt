package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.store.allHealth
import dev.jamesrich.sofaking.backend.store.recentObservations
import io.ktor.server.routing.get

/** The Channels pages' rows: each lineup entry with its policy station, guide state, health and override. */
internal class LineupReader(private val portal: Portal) {
    private val runtime get() = portal.runtime

    suspend fun rows(): List<StationRow> {
        val stations = runtime.policy?.lastApplied()?.stations.orEmpty().associateBy { it.id }
        val overrides = runtime.policy?.policy?.let { p ->
            p.stations.filter { (key, station) -> isOverride(key, station, p) }.keys
        }.orEmpty()
        val coverage = runtime.guides.coverage
        val health = runtime.store.allHealth()
        val now = portal.clock.instant()
        return runtime.lineup.entries().map { entry ->
            val station = stations[entry.id]
            StationRow(
                entry,
                station,
                guideStatus(entry, coverage, now),
                healthy = entry.offeringIds.count { health[it]?.status == HealthStatus.HEALTHY },
                overridden = station?.key?.let { it in overrides } == true,
            )
        }
    }

    /** The entries the newest run that changed anything touched, by lineup entry id. */
    private suspend fun changedInLastRun(): Set<String> {
        val changes = runtime.policy?.changes(CHANGES_LIMIT).orEmpty()
        val last = changes.firstOrNull()?.appliedAt ?: return emptySet()
        return changes.filter { it.appliedAt == last }.mapNotNullTo(HashSet()) { it.change.entryId }
    }

    fun groupChoices(rows: List<StationRow>): List<String> {
        val policy = runtime.policy?.policy
        val named = policy?.let {
            it.numbering.groups.keys + it.groups.map.values +
                it.groups.patterns.map { p -> p.group } + listOfNotNull(it.groups.default)
        }.orEmpty()
        return (rows.mapNotNull { it.entry.group } + named).distinct().sorted()
    }

    suspend fun lineupView(filter: LineupFilter): LineupView {
        val rows = rows()
        val changed = if (filter.changed) changedInLastRun() else null
        val matches = rows.filter {
            filter.admits(it) && (changed == null || it.entry.id in changed)
        }.let { if (filter.sort.isEmpty()) it else it.sortedWith(filter.order()) }
        val pages = maxOf(1, (matches.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val page = filter.page.coerceIn(1, pages)
        val groups = rows.mapNotNull { it.entry.group }.distinct().sorted()
        return LineupView(
            filter.copy(page = page),
            withWhy(matches.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)), matches.size, rows.size, pages,
            groups, runtime.policy?.document()?.revision, groupChoices(rows), rows.count { it.entry.enabled },
        )
    }

    suspend fun stationView(id: String, filter: LineupFilter, error: String? = null): StationView? {
        val rows = rows()
        val row = rows.firstOrNull { it.entry.id == id } ?: return null
        val guideName = row.entry.guide?.let {
            runtime.guides.channel(it.guideId, it.channelId)?.displayName?.let { name -> "$name (${it.guideId})" }
        }
        return StationView(
            row,
            sourceLines(row.entry),
            guideName,
            filter,
            runtime.policy?.document()?.revision,
            groupChoices(rows),
            error,
            probes = runtime.probes != null,
        )
    }

    /** [rows] with a Why on each red one, from its sources' newest probe results. */
    private suspend fun withWhy(rows: List<StationRow>): List<StationRow> {
        val red = rows.filter { it.tint == Tone.BAD }
        if (red.isEmpty()) return rows
        val health = runtime.store.allHealth()
        val recent = runtime.store.recentObservations(red.flatMap { it.entry.offeringIds }, RECENT_PROBES)
        return rows.map { row ->
            if (row.tint != Tone.BAD) {
                row
            } else {
                StationRow(
                    row.entry,
                    row.station,
                    row.guideStatus,
                    row.healthy,
                    row.overridden,
                    if (row.entry.offeringIds.none { runtime.feeds.published.offering(it) != null }) {
                        "the providers no longer list its sources"
                    } else {
                        stationWhy(row.entry.offeringIds, health, recent)
                    },
                )
            }
        }
    }

    private suspend fun sourceLines(entry: LineupEntryRecord): List<SourceLine> {
        val health = runtime.store.allHealth()
        val recent = runtime.store.recentObservations(entry.offeringIds, RECENT_PROBES)
        return entry.offeringIds.map { offeringId ->
            val offering = runtime.feeds.published.offering(offeringId)
            val state = health[offeringId]
            val probes = recent[offeringId].orEmpty()
            if (offering == null) {
                SourceLine(
                    offeringId,
                    "gone",
                    null,
                    "",
                    "WITHDRAWN",
                    state?.manuallyDisabled == true,
                    "The provider no longer lists it.",
                    probes,
                )
            } else {
                SourceLine(
                    offeringId,
                    offering.providerId,
                    runtime.feeds.published.channel(offering.channelId)?.name,
                    offering.protocol,
                    state?.status?.name ?: "UNVERIFIED",
                    state?.manuallyDisabled == true,
                    healthWhy(state, probes),
                    probes,
                )
            }
        }
    }
}

private const val PAGE_SIZE = 100
