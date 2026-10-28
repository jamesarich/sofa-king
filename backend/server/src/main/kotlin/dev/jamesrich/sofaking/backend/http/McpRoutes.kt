package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.mcp.McpServers
import dev.jamesrich.sofaking.contract.ApiError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport

/** The Model Context Protocol endpoint, for AI assistants that set up and look after the server. */
internal const val MCP_PATH = "/api/v1/mcp"

/** Set by the token guard when a `read` token opened the MCP endpoint, which then lists only the read tools. */
internal val McpReadOnlyKey = AttributeKey<Unit>("McpReadOnly")

/**
 * MCP over stateless Streamable HTTP: every POST is one JSON-RPC exchange on a session of its own, answered as JSON,
 * so nothing is kept between requests. A `read` token gets the read tools; the admin token the actions too. The token
 * guard has checked the token, and the Host and Origin a tokenless server needs, before this runs.
 */
internal fun Route.mcpRoutes(servers: McpServers) {
    route(MCP_PATH) {
        post {
            val configuration = StreamableHttpServerTransport.Configuration(
                enableJsonResponse = true,
                maxRequestBodySize = CONTROL_BODY_LIMIT,
            )
            val transport = StreamableHttpServerTransport(configuration).apply { setSessionIdGenerator(null) }
            val server = if (call.attributes.contains(McpReadOnlyKey)) servers.reads else servers.admin
            val session = server.createSession(transport)
            try {
                transport.handleRequest(null, call)
            } finally {
                // One request per session, so the server's session registry never grows.
                session.close()
            }
        }
        // No server-sent stream to open and no session to end.
        get { methodNotAllowed() }
        delete { methodNotAllowed() }
    }
}

private suspend fun RoutingContext.methodNotAllowed() {
    call.response.header(HttpHeaders.Allow, HttpMethod.Post.value)
    call.respond(HttpStatusCode.MethodNotAllowed, ApiError("METHOD_NOT_ALLOWED", "The MCP endpoint takes POST", false))
}
