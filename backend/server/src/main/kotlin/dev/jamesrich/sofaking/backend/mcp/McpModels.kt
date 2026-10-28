package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.api.GuideStatus
import dev.jamesrich.sofaking.backend.api.ProviderStatus
import dev.jamesrich.sofaking.backend.api.SourceStatus
import dev.jamesrich.sofaking.backend.update.UpdaterReport
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

/** The sources that are not healthy or are disabled by hand, [count] by state over all of them. */
@Serializable internal class SourceProblems(
    val total: Int,
    val count: Map<String, Int>,
    val manuallyDisabled: Int,
    val sources: List<SourceStatus>,
    val truncated: Boolean,
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
)

/** What an action did: [ok], and a line saying what happened. */
@Serializable internal class ActionResult(val ok: Boolean, val message: String)
