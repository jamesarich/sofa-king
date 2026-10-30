package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.api.ActiveSession
import dev.jamesrich.sofaking.backend.api.GuideStatus
import dev.jamesrich.sofaking.backend.api.PlexDeviceStatus
import dev.jamesrich.sofaking.backend.api.PolicyCandidate
import dev.jamesrich.sofaking.backend.api.PolicyFieldChange
import dev.jamesrich.sofaking.backend.api.PolicyRegrouping
import dev.jamesrich.sofaking.backend.api.PolicyRemoval
import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.api.PolicyUnmappedCategory
import dev.jamesrich.sofaking.backend.api.ProviderStatus
import dev.jamesrich.sofaking.backend.api.SourcePin
import dev.jamesrich.sofaking.backend.update.UpdaterReport
import dev.jamesrich.sofaking.contract.Library
import dev.jamesrich.sofaking.contract.LibraryItem
import dev.jamesrich.sofaking.contract.RecordingStorage
import kotlinx.serialization.Serializable

// What the MCP tools answer where no API response fits. The API's own types serve the rest.

/** One item the portal's home page says needs the operator; [link] is the portal page that fixes it. */
@Serializable internal class McpAttention(
    val tone: String,
    val title: String,
    val detail: String? = null,
    val action: String,
    val link: String,
    val doc: String? = null,
)

/** The providers' and guides' imports, as `/api/v1/providers` and `/api/v1/admin/guides` list them. */
@Serializable internal class Imports(val providers: List<ProviderStatus>, val guides: List<GuideStatus>)

/**
 * One stream source. [sourceId] is what `source_health` takes; [providerChannelId] is the provider's channel it
 * belongs to, not a lineup entry; [entryIds] are the lineup entries that use it.
 */
@Serializable internal class SourceRow(
    val sourceId: String,
    val providerChannelId: String,
    val providerId: String,
    val status: String,
    val manuallyDisabled: Boolean,
    val lastQualifiedAt: String? = null,
    val nextCheckAt: String? = null,
    val entryIds: List<String> = emptyList(),
)

/** The sources that are not healthy or are disabled by hand, [count] by state over all of them. */
@Serializable internal class SourceProblems(
    val total: Int,
    val count: Map<String, Int>,
    val manuallyDisabled: Int,
    val matching: Int,
    val sources: List<SourceRow>,
    val truncated: Boolean,
)

/** One source's health: its state, the probes behind it and what it blocks. No URLs. */
@Serializable internal class SourceHealthDetail(
    val sourceId: String,
    val providerId: String,
    val providerChannelId: String,
    val protocol: String,
    val status: String,
    val manuallyDisabled: Boolean,
    val why: String? = null,
    val failures: List<ProbeResult>,
    val recentProbes: List<ProbeResult>,
    val quarantineAttempts: Int,
    val lastQualifiedAt: String? = null,
    val lastObservationAt: String? = null,
    val nextCheckAt: String? = null,
    val probationStartedAt: String? = null,
    val entryIds: List<String>,
)

/** One probe result: when, whether it qualified, and the failure class that sorts the evidence. */
@Serializable internal class ProbeResult(val at: String, val qualified: Boolean, val failureClass: String? = null)

/** One row of the lineup. */
@Serializable internal class LineupRow(
    val id: String,
    val number: String,
    val name: String,
    val group: String? = null,
    val enabled: Boolean,
    val guideStatus: String,
    val sourceCount: Int,
    val autoDisabledReason: String? = null,
    val stationKey: String? = null,
)

/** A page of the lineup: [total] match, this page starts at [offset]; [nextOffset] is set when more follow. */
@Serializable internal class LineupPage(
    val total: Int,
    val offset: Int,
    val limit: Int,
    val entries: List<LineupRow>,
    val nextOffset: Int? = null,
)

/** A lineup entry with its sources and guide, as `channel` answers; [resolvedFrom] says which id matched. */
@Serializable internal class ChannelDetail(
    val id: String,
    val number: String,
    val name: String,
    val group: String? = null,
    val enabled: Boolean,
    val createdBy: String,
    val stationKey: String? = null,
    val disabledReason: String? = null,
    val autoDisabledReason: String? = null,
    val guide: GuideDetail? = null,
    val sources: List<SourceDetail>,
    val resolvedFrom: String,
    val otherEntryIds: List<String> = emptyList(),
)

/** One of an entry's sources; [status] is `WITHDRAWN` when no provider lists it now. */
@Serializable internal class SourceDetail(
    val sourceId: String,
    val providerId: String? = null,
    val providerChannelId: String? = null,
    val providerChannelName: String? = null,
    val protocol: String? = null,
    val status: String,
    val manuallyDisabled: Boolean,
    val why: String? = null,
)

/** The guide channel an entry takes its programs from; [status] is `available`, `stale` or `empty`. */
@Serializable internal class GuideDetail(
    val guideId: String,
    val channelId: String,
    val channelName: String? = null,
    val coverageEndsAt: String? = null,
    val status: String,
)

/** A guide's channel, as `guide_channels` lists it. */
@Serializable internal class GuideChannelRow(val guideId: String, val channelId: String, val displayName: String)

/** A capped list: [total] items exist, [items] holds the first of them, and [truncated] says some are left out. */
@Serializable internal class Capped<T>(val total: Int, val items: List<T>, val truncated: Boolean)

/** The newest [items] up to a limit, when the store does not count the rest; [truncated] says more exist. */
@Serializable internal class Listed<T>(val items: List<T>, val truncated: Boolean)

/** One shelf of the library home, with at most the first of its items. */
@Serializable internal class ShelfView(
    val id: String,
    val title: String,
    val libraryId: String? = null,
    val total: Int,
    val items: List<LibraryItem>,
    val truncated: Boolean,
)

/** The library home with each shelf capped. */
@Serializable internal class HomeView(val shelves: List<ShelfView>, val libraries: List<Library>)

/** The live streams playing now, at most [sessions]' cap of [total]. */
@Serializable internal class WatchingNow(val total: Int, val sessions: List<ActiveSession>, val truncated: Boolean)

/** One recording, without its image link or file path. */
@Serializable internal class RecordingRow(
    val id: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val episodeTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val movie: Boolean,
    val startsAt: String,
    val endsAt: String,
    val state: String,
    val reason: String? = null,
    val ruleId: String? = null,
    val bytes: Long,
    val protected: Boolean,
    val conflict: Boolean,
    val watched: Boolean,
    /** The commercial scan's state (`null` before one runs) and how many breaks it found. */
    val commercials: String? = null,
    val breaks: Int = 0,
)

/** A page of recordings with the recordings folder's disk. */
@Serializable internal class RecordingsPage(
    val total: Int,
    val offset: Int,
    val recordings: List<RecordingRow>,
    val nextOffset: Int? = null,
    val truncated: Boolean,
    val storage: RecordingStorage,
)

/** A page of the guide's now and next. */
@Serializable internal class GuidePage(
    val total: Int,
    val offset: Int,
    val limit: Int,
    val channels: List<NowNext>,
    val nextOffset: Int? = null,
)

/** A station's explanation with its lists capped; the station's logo link is left out. */
@Serializable internal class Explained(
    val key: String,
    val station: PolicyStation? = null,
    val pins: List<SourcePin>,
    val candidates: List<PolicyCandidate>,
    val findings: List<String>,
    val truncated: Boolean,
)

/** A policy run's open findings, each list capped to its first items with the full count beside it. */
@Serializable internal class FindingsView(
    val generatedAt: String,
    val revision: String,
    val findings: Capped<String>,
    val suggestedAliases: Capped<String>,
    val unmappedCategories: Capped<PolicyUnmappedCategory>,
)

/** What a policy run would change, each list capped to its first items with the full count beside it. */
@Serializable internal class PreviewView(
    val generatedAt: String,
    val revision: String,
    val stations: Int,
    val enabledStations: Int,
    val counts: Map<String, Int>,
    val devicesNeeded: Int? = null,
    val devicesConfigured: Int? = null,
    val added: Capped<String>,
    val removed: Capped<PolicyRemoval>,
    val changes: Capped<PolicyFieldChange>,
    val merges: Capped<PolicyRegrouping>,
    val splits: Capped<PolicyRegrouping>,
    val findings: Capped<String>,
    val suggestedAliases: Capped<String>,
    val unmappedCategories: Capped<PolicyUnmappedCategory>,
)

/** The last Plex sync, with its lists capped. */
@Serializable internal class PlexView(
    val syncMinutes: Int,
    val lastSyncAt: String? = null,
    val lastCause: String? = null,
    val lastError: String? = null,
    val dvrs: Capped<String>,
    val devices: Capped<PlexDeviceStatus>,
    val missingDevices: Capped<String>,
    val findings: Capped<String>,
)

/** One background job, as the portal's Jobs page shows it. */
@Serializable internal class JobInfo(
    val id: String,
    val label: String,
    val everyMinutes: Long? = null,
    val running: Boolean,
    val progress: String? = null,
    val lastStartedAt: String? = null,
    val lastDurationMs: Long? = null,
    val lastOk: Boolean? = null,
    val lastNote: String? = null,
    val nextDueAt: String? = null,
    val canRunNow: Boolean,
)

/** One line of the portal's Events page. */
@Serializable internal class LogLine(
    val at: String,
    val level: String,
    val logger: String,
    val message: String,
    val requestId: String? = null,
)

/** What runs, what is published, and what the host's updater last did, as the portal's About page shows it. */
@Serializable internal class UpdateInfo(
    val checking: Boolean,
    val runningRevision: String,
    val runningVersion: String? = null,
    val image: String? = null,
    val publishedRevision: String? = null,
    val publishedVersion: String? = null,
    val upToDate: Boolean? = null,
    val checkedAt: String? = null,
    val error: String? = null,
    val updater: UpdaterReport? = null,
    val requestedAt: String? = null,
)

/** What is on a channel now and next. */
@Serializable internal class NowNext(
    val channelId: String,
    val number: String,
    val name: String,
    val now: Airing? = null,
    val next: Airing? = null,
)

/** One program as the guide tools list it, with its channel when the list spans channels. */
@Serializable internal class Airing(
    val title: String,
    val startsAt: String,
    val endsAt: String,
    val subTitle: String? = null,
    val episode: String? = null,
    val isNew: Boolean = false,
    val categories: List<String> = emptyList(),
    val channel: String? = null,
    /** The lineup entry the program airs on, the id `lineup` and the recording tools take. */
    val channelId: String? = null,
)

/** What an action did: [ok], and a line saying what happened. */
@Serializable internal class ActionResult(val ok: Boolean, val message: String)
