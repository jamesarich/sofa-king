package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.api.PolicyChange
import dev.jamesrich.sofaking.backend.api.PolicyChangeEntry
import dev.jamesrich.sofaking.backend.api.PolicyFieldChange
import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.PolicyService
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.server.Server

// The lineup policy's results, read the way the admin API's policy routes answer them. Each answers
// POLICY_NOT_CONFIGURED on a server without SOFA_POLICY. A logo link a change or station carries is left out.

private const val LOGO = "logo"

internal fun Server.policyTools(context: McpContext) {
    findingsTools(context)
    changesTools(context)
    stationTools(context)
}

private val limitSchema = schema(LIMIT to integer("How many items of each list", 1..MAX_PAGE, DEFAULT_PAGE))

private fun Server.findingsTools(context: McpContext) {
    tool(
        "policy_findings",
        "Use when the lineup looks wrong: the open findings of a lineup policy run over the current feeds (problems " +
            "the policy reports), aliases it suggests for station keys that probably name one station, and " +
            "categories no group rule names. Each list is capped to limit with its total.",
        limitSchema,
        READ_ONLY,
    ) { arguments ->
        val limit = arguments.int(LIMIT, DEFAULT_PAGE, 1..MAX_PAGE)
        val findings = policy(context).findings()
        FindingsView(
            findings.generatedAt,
            findings.revision,
            findings.findings.capped(limit),
            findings.suggestedAliases.capped(limit),
            findings.unmappedCategories.capped(limit),
        )
    }
    tool(
        "preview_policy",
        "Use to see what applying the lineup policy would change, without changing anything: stations, what would " +
            "be added, removed, merged, split or changed (entry ids are lineup entry ids), the findings and counts. " +
            "Each list is capped to limit with its total.",
        limitSchema,
        READ_ONLY,
    ) { arguments ->
        val limit = arguments.int(LIMIT, DEFAULT_PAGE, 1..MAX_PAGE)
        val preview = policy(context).preview()
        PreviewView(
            preview.generatedAt, preview.revision, preview.stations, preview.enabledStations, preview.counts,
            preview.devicesNeeded, preview.devicesConfigured, preview.added.capped(limit),
            preview.removed.capped(limit), preview.changes.map { it.withoutLogo() }.capped(limit),
            preview.merges.capped(limit), preview.splits.capped(limit), preview.findings.capped(limit),
            preview.suggestedAliases.capped(limit), preview.unmappedCategories.capped(limit),
        )
    }
}

private fun Server.changesTools(context: McpContext) {
    tool(
        "policy_changes",
        "What the lineup policy changed and why, newest first: each logged change names the entry, the field, its " +
            "before and after and the reason. Use to explain why a channel turned off or moved.",
        schema(LIMIT to integer("How many changes", 1..MAX_PAGE, DEFAULT_PAGE)),
        READ_ONLY,
    ) { arguments ->
        val limit = arguments.int(LIMIT, DEFAULT_PAGE, 1..MAX_PAGE)
        val changes = policy(context).changes(limit + 1)
        Listed(changes.take(limit).map { it.withoutLogo() }, changes.size > limit)
    }
}

private fun Server.stationTools(context: McpContext) {
    tool(
        "explain_station",
        "Use to see why a station has the sources, number and state it has: the station, its pinned sources and " +
            "every feed channel the policy considered for it, with the outcome and reason of each. Takes a " +
            "stationKey from lineup. An unknown key answers no station and no candidates.",
        schema("key" to string("The station key, from lineup's stationKey"), required = listOf("key")),
        READ_ONLY,
    ) { arguments ->
        val key = arguments.string("key") ?: invalid("key is required; call lineup for station keys")
        val explained = policy(context).explain(key)
        Explained(
            explained.key,
            explained.station?.withoutLogo(),
            explained.pins.take(MAX_PAGE),
            explained.candidates.take(MAX_PAGE),
            explained.findings.take(MAX_PAGE),
            truncated = maxOf(explained.pins.size, explained.candidates.size, explained.findings.size) > MAX_PAGE,
        )
    }
    tool(
        "guide_matches",
        "Use to fix stations with no guide: the guide channels the policy proposes by name for stations no guide " +
            "lists, with a confidence from 0 to 1, best first. Applying one is a portal or API action.",
        schema(LIMIT to integer("How many proposals", 1..MAX_PAGE, DEFAULT_PAGE)),
        READ_ONLY,
    ) { arguments -> policy(context).guideMatches.proposals().capped(arguments.int(LIMIT, DEFAULT_PAGE, 1..MAX_PAGE)) }
}

private fun policy(context: McpContext): PolicyService = context.runtime.policy ?: throw ApiFailure(
    HttpStatusCode.NotFound,
    "POLICY_NOT_CONFIGURED",
    "No lineup policy is configured; set SOFA_POLICY",
)

private fun PolicyStation.withoutLogo() = copy(logo = null)

private fun PolicyFieldChange.withoutLogo() = if (field == LOGO) copy(before = null, after = null) else this

private fun PolicyChange.withoutLogo() = if (field == LOGO) copy(before = null, after = null) else this

private fun PolicyChangeEntry.withoutLogo() = copy(change = change.withoutLogo())
