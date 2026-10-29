package dev.jamesrich.sofaking.backend.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

// Static instructions that order the tools for a job. Both servers list them; a step only the admin token can take
// says so, so a read token's assistant stops there and tells the operator.

private const val CHANNEL = "channel"
private const val CHANNEL_LIMIT = 200

private const val ADMIN_NOTE =
    "Steps marked (admin) need the admin token; with a read token, stop before them and tell the operator what " +
        "you would do."

private val TRIAGE = """
    Triage this Sofa King server. Work in this order and stop when you find the cause.
    1. server_status for the counts: channels, providers, guides, sessions, the running revision.
    2. needs_attention for what the operator must fix, worst first.
    3. imports for each provider's and guide's last fetch. One with no channels or an old lastSuccessAt failed.
    4. jobs for the last note of the job behind it.
    5. recent_log with level error for the server's own reason.
    Then say what is wrong, what you checked and what the operator should do. $ADMIN_NOTE
""".trimIndent()

private val REVIEW = """
    Review a change to the lineup policy before it is written.
    1. preview_policy for the current revision and what applying it changes: stations added, removed, merged or
       renumbered.
    2. (admin) edit_station with dryRun true and that revision, for the impact of the edit on the channels.
    3. Tell the operator which channels turn on or off, renumber or rename, and wait for a yes.
    4. (admin) edit_station again with dryRun false and the same revision, then apply_policy with confirm true. A
       stale revision answers POLICY_CHANGED: call preview_policy again.
    $ADMIN_NOTE
""".trimIndent()

private fun diagnose(channel: String) = """
    Find out why the channel "$channel" plays badly or not at all. Work in this order and stop when you find the
    cause.
    1. lineup to find the channel's entry id, then channel for its sources and their health.
    2. source_health for each failing source: its state, the failure class and recent observations.
    3. recent_log with level error for the server's own reason.
    4. (admin) probe_source on the failing source to check it now; if another source of the channel is healthy,
       switch_session_source moves a playing session to it.
    Then say which source fails and why, what you did and what the operator should do. $ADMIN_NOTE
""".trimIndent()

internal fun Server.prompts() {
    addPrompt(
        "triage_server",
        "Find what is wrong with the server: status, attention items, imports, jobs, log.",
        emptyList(),
    ) { prompt("Triage the server", TRIAGE) }
    addPrompt(
        "diagnose_channel",
        "Find why one channel fails: lineup entry, source health, log, then probe or switch the source.",
        listOf(PromptArgument(CHANNEL, description = "The channel's name or number", required = true)),
    ) { request ->
        val channel = request.arguments?.get(CHANNEL)?.trim()?.take(CHANNEL_LIMIT)?.takeIf { it.isNotEmpty() }
            ?: invalid("channel is required")
        prompt("Diagnose $channel", scrub(diagnose(channel)))
    }
    addPrompt(
        "review_policy_change",
        "Preview a lineup policy change, tell the operator what moves, then apply it.",
        emptyList(),
    ) { prompt("Review a lineup policy change", REVIEW) }
}

private fun prompt(description: String, text: String) =
    GetPromptResult(listOf(PromptMessage(Role.User, TextContent(text))), description)
