package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.api.GuideMatchChoice
import dev.jamesrich.sofaking.backend.api.PolicyApplyResult
import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.Impact
import dev.jamesrich.sofaking.backend.catalog.Moved
import dev.jamesrich.sofaking.backend.catalog.PolicyService
import dev.jamesrich.sofaking.backend.catalog.applyAt
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.serialization.Serializable

// The lineup policy's writes, listed only to the admin token. Each is written and applied the way the admin API
// writes it, against the revision preview_policy showed, and logged in policy_changes with the cause "mcp".

private const val CAUSE = "mcp"
private const val REVISION = "revision"
private const val DRY_RUN = "dryRun"
private const val DECISION = "decision"
private const val ENTRY_ID = "entryId"

/** A write to the policy file, applied at once. */
private val POLICY_WRITE = ToolAnnotations(
    readOnlyHint = false,
    destructiveHint = true,
    idempotentHint = false,
    openWorldHint = false,
)

@Serializable internal class MovedView(val name: String, val before: String? = null, val after: String? = null)

/** What an edit does to the channels, each kind of change capped to the first of them with its total. */
@Serializable internal class ImpactView(
    val channelsOnBefore: Int,
    val channelsOnAfter: Int,
    val noChange: Boolean,
    val turnOff: Capped<MovedView>,
    val turnOn: Capped<MovedView>,
    val changeGroup: Capped<MovedView>,
    val changeNumber: Capped<MovedView>,
    val changeName: Capped<MovedView>,
    val joinLineup: Capped<String>,
    val leaveLineup: Capped<String>,
)

@Serializable internal class AppliedView(
    val revision: String,
    val changed: Boolean,
    val stations: Int,
    val enabledStations: Int,
    val changes: Int,
    val findings: Capped<String>,
)

/** A preview or a write: [revision] is the policy's now, for the next call. */
@Serializable internal class EditResult(
    val dryRun: Boolean,
    val revision: String,
    val impact: ImpactView,
    val applied: AppliedView? = null,
    val message: String,
)

@Serializable internal class GuideDecision(val accepted: Int, val applied: AppliedView? = null, val message: String)

internal fun Server.policyActionTools(context: McpContext) {
    applyTool(context)
    editTool(context)
    mergeTool(context)
    guideMatchTool(context)
}

private fun Server.applyTool(context: McpContext) {
    tool(
        "apply_policy",
        "Run the lineup policy now and write the lineup it builds, as the portal's Apply does: channels can turn on, " +
            "off or renumber. Preview first with preview_policy, tell the operator what changes, and pass its " +
            "revision. Needs confirm true; a stale revision answers POLICY_CHANGED.",
        schema(
            REVISION to string("The policy revision from preview_policy"),
            CONFIRM to boolean("Required true"),
            required = listOf(REVISION, CONFIRM),
        ),
        POLICY_WRITE,
    ) { arguments ->
        val revision = arguments.string(REVISION) ?: invalid("revision is required; call preview_policy")
        arguments.confirmed("the lineup would be rebuilt from the policy, and channels can turn off or renumber")
        val policy = policy(context)
        hinted { policy.applyAt(revision, CAUSE) }.view(policy)
    }
}

private fun Server.editTool(context: McpContext) {
    tool(
        "edit_station",
        "Change one station's number, name, group, on or off state or Gracenote id in the lineup policy, as the " +
            "channel page does, then apply. dryRun is true by default: it answers the impact (which channels turn " +
            "on or off, renumber or rename) and writes nothing. Preview, tell the operator, then call again with " +
            "dryRun false and the same revision. Takes an entryId from lineup and the revision from preview_policy.",
        schema(
            ENTRY_ID to string("The lineup entry's id, from lineup"),
            REVISION to string("The policy revision from preview_policy"),
            "number" to string("The channel number to set"),
            "name" to string("The name to set"),
            "group" to string("The group to set"),
            "clearGroup" to boolean("true removes the group set by hand, so the policy decides"),
            "enabled" to boolean("false turns the channel off, true on"),
            "gracenoteId" to string("The Gracenote station id to set"),
            DRY_RUN to boolean("true (the default) only previews; false writes and applies"),
            required = listOf(ENTRY_ID, REVISION),
        ),
        POLICY_WRITE,
    ) { arguments ->
        val id = arguments.string(ENTRY_ID) ?: invalid("entryId is required; call lineup for entry ids")
        val revision = arguments.string(REVISION) ?: invalid("revision is required; call preview_policy")
        val request = UpdateLineupEntry(
            number = arguments.string("number"),
            name = arguments.string("name"),
            group = arguments.string("group"),
            enabled = arguments.boolean("enabled"),
            gracenoteId = arguments.string("gracenoteId"),
            clearGroup = arguments.boolean("clearGroup") == true,
        )
        if (request == UpdateLineupEntry()) {
            invalid("name at least one of number, name, group, clearGroup, enabled or gracenoteId to change")
        }
        val policy = policy(context)
        val dryRun = arguments.boolean(DRY_RUN) ?: true
        decide(
            policy,
            dryRun,
            preview = { policy.edits.previewStation(id, request, revision) },
            write = { policy.edits.updateStation(id, request, revision, CAUSE) },
        )
    }
}

private fun Server.mergeTool(context: McpContext) {
    tool(
        "decide_merge",
        "Settle two station keys that probably name one station: accept makes a an alias of b, so they become one " +
            "station; never keeps them apart for good. The keys come from preview_policy's merges or " +
            "policy_findings' suggestedAliases. dryRun is true by default: it answers the impact and writes " +
            "nothing; call again with dryRun false and the same revision to write and apply.",
        schema(
            "a" to string("The station key that would become an alias, from preview_policy or policy_findings"),
            "b" to string("The station key it would join"),
            DECISION to choice("accept merges a into b, never keeps them apart", listOf("accept", "never")),
            REVISION to string("The policy revision from preview_policy"),
            DRY_RUN to boolean("true (the default) only previews; false writes and applies"),
            required = listOf("a", "b", DECISION, REVISION),
        ),
        POLICY_WRITE,
    ) { arguments ->
        val a = arguments.string("a") ?: invalid("a is required")
        val b = arguments.string("b") ?: invalid("b is required")
        val decision = arguments.choice(DECISION, listOf("accept", "never")) ?: invalid("decision is accept or never")
        val revision = arguments.string(REVISION) ?: invalid("revision is required; call preview_policy")
        val policy = policy(context)
        val edits = policy.edits
        val accept = decision == "accept"
        decide(
            policy,
            arguments.boolean(DRY_RUN) ?: true,
            preview = {
                if (accept) edits.previewAlias(a, b, revision) else edits.previewNeverMerge(a, b, revision)
            },
            write = {
                if (accept) edits.addAlias(a, b, revision, CAUSE) else edits.neverMerge(a, b, revision, CAUSE)
            },
        )
    }
}

private fun Server.guideMatchTool(context: McpContext) {
    tool(
        "decide_guide_match",
        "Settle a guide channel guide_matches proposes for a station: accept writes it as the station's guide, " +
            "dismiss never proposes it for the station again. Or acceptAll true accepts each station's best " +
            "proposal at minConfidence or more (else the policy's acceptAbove). Applies at once.",
        schema(
            "station" to string("The station key, from guide_matches"),
            "guideId" to string("The guide's id, from guide_matches"),
            "channelId" to string("The guide channel's id, from guide_matches"),
            DECISION to choice("accept writes the guide, dismiss stops proposing it", listOf("accept", "dismiss")),
            "acceptAll" to boolean("true accepts every station's best proposal instead of one choice"),
            "minConfidence" to number("With acceptAll: the lowest confidence to accept, 0 to 1", 0.0..1.0),
        ),
        POLICY_WRITE,
    ) { arguments ->
        val policy = policy(context)
        if (arguments.boolean("acceptAll") == true) {
            val result = policy.guideMatches.acceptAll(arguments.number("minConfidence"), CAUSE)
            GuideDecision(
                result.accepted,
                result.applied?.let { appliedView(it, policy) },
                "Accepted ${result.accepted} guide matches",
            )
        } else {
            val choice = GuideMatchChoice(
                arguments.string("station") ?: invalid("station is required; call guide_matches"),
                arguments.string("guideId") ?: invalid("guideId is required"),
                arguments.string("channelId") ?: invalid("channelId is required"),
            )
            val accept = when (arguments.choice(DECISION, listOf("accept", "dismiss"))) {
                "accept" -> true
                "dismiss" -> false
                else -> invalid("decision is accept or dismiss, or send acceptAll true")
            }
            val applied = if (accept) {
                policy.guideMatches.accept(choice, CAUSE)
            } else {
                policy.guideMatches.dismiss(choice, CAUSE)
            }
            GuideDecision(if (accept) 1 else 0, appliedView(applied, policy), if (accept) "Accepted" else "Dismissed")
        }
    }
}

/** A dry run answers the impact; a write answers it too, taken at the revision it then writes against. */
private suspend fun decide(
    policy: PolicyService,
    dryRun: Boolean,
    preview: suspend () -> Impact,
    write: suspend () -> Any?,
): EditResult {
    val impact = hinted { preview() }.view()
    if (dryRun) {
        val revision = policy.file.current().revision
        return EditResult(
            true,
            revision,
            impact,
            message = "Nothing was written; call again with dryRun false and revision $revision",
        )
    }
    val applied = when (val written = hinted { write() }) {
        is PolicyApplyResult -> appliedView(written, policy)
        else -> policy.lastApplied()?.result?.let { appliedView(it, policy) }
    }
    return EditResult(false, policy.file.current().revision, impact, applied, "Written and applied")
}

/** The refusal of a stale revision or an unknown entry, with the tool that fixes it. */
private suspend fun <T> hinted(block: suspend () -> T): T = try {
    block()
} catch (failure: ApiFailure) {
    val hint = when (failure.code) {
        "POLICY_CHANGED" -> "call preview_policy again for the current revision"
        "LINEUP_ENTRY_NOT_FOUND" -> "call lineup for entry ids"
        else -> throw failure
    }
    throw ApiFailure(failure.status, failure.code, "${failure.message}; $hint", failure.retryable, failure)
}

private fun PolicyApplyResult.view(policy: PolicyService) = appliedView(this, policy)

private fun appliedView(result: PolicyApplyResult, policy: PolicyService) = AppliedView(
    policy.file.current().revision,
    result.changed,
    result.stations,
    result.enabledStations,
    result.changes,
    result.findings.capped(MAX_PAGE),
)

private fun Impact.view() = ImpactView(
    enabledBefore,
    enabledAfter,
    isEmpty,
    turnedOff.shown(),
    turnedOn.shown(),
    group.shown(),
    number.shown(),
    name.shown(),
    added.capped(IMPACT_SHOWN),
    removed.capped(IMPACT_SHOWN),
)

private fun List<Moved>.shown() = map { MovedView(it.name, it.before, it.after) }.capped(IMPACT_SHOWN)

/** How many channels of each kind of change an impact lists before it counts the rest. */
private const val IMPACT_SHOWN = 25
