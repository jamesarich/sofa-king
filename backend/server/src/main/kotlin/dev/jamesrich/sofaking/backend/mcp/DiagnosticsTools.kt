package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.store.clientCrashes
import io.modelcontextprotocol.kotlin.sdk.server.Server

private const val CRASH_LIMIT = 100
private const val DEFAULT_CRASHES = 10

/** The tools over what the apps reported about themselves. */
internal fun Server.diagnosticsTools(context: McpContext) {
    tool(
        "client_crashes",
        "The crashes the apps reported to this server, newest first: each device's name, platform, app and OS " +
            "version, the exception class, a scrubbed message and the stack frames. Nothing leaves the server.",
        schema("limit" to integer("How many crashes", 1..CRASH_LIMIT, DEFAULT_CRASHES)),
        READ_ONLY,
    ) { arguments ->
        context.runtime.store.clientCrashes(arguments.int("limit", DEFAULT_CRASHES, 1..CRASH_LIMIT))
    }
}
