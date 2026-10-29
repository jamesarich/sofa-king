package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.admin.apiReference
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents

// Documents an assistant reads whole: the API's OpenAPI description, the support snapshot and the lineup policy.
// Each is registered per server like the tools, so a read token is never told the policy exists.

private const val JSON = "application/json"
private const val OPENAPI_URI = "sofa-king://openapi.json"
private const val SNAPSHOT_URI = "sofa-king://support-snapshot"
private const val POLICY_URI = "sofa-king://policy"

private fun document(uri: String, text: String) =
    ReadResourceResult(listOf(TextResourceContents(text = text, uri = uri, mimeType = JSON)))

/** The resources the read token and the admin token both see. */
internal fun Server.sharedResources(context: McpContext) {
    addResource(
        OPENAPI_URI,
        "OpenAPI",
        "The OpenAPI description of the REST API the apps and the admin token use.",
        JSON,
    ) { document(OPENAPI_URI, apiReference.json) }
    val snapshot = context.supportSnapshot ?: return
    addResource(
        SNAPSHOT_URI,
        "Support snapshot",
        "What the About page copies for a bug report: status, server facts, every job and what needs attention. " +
            "It holds no tokens, stream URLs or request headers.",
        JSON,
    ) { document(SNAPSHOT_URI, scrub(snapshot())) }
}

/** The resources only the admin token sees: REST does not open the policy to a read token either. */
internal fun Server.adminResources(context: McpContext) {
    addResource(
        POLICY_URI,
        "Lineup policy",
        "The lineup policy document with its revision, the one edit_station and apply_policy take.",
        JSON,
    ) { document(POLICY_URI, scrub(toolJson.encodeToString(policy(context).document()))) }
}
