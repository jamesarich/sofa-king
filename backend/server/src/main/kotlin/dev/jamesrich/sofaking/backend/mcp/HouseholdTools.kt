package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.api.NotificationDelivery
import dev.jamesrich.sofaking.backend.api.NotificationTarget
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.http.validClientId
import dev.jamesrich.sofaking.backend.http.viewersWithTokens
import dev.jamesrich.sofaking.backend.notify.NotificationTargetsService
import dev.jamesrich.sofaking.backend.store.forgetViewer
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.serialization.Serializable

// The household actions, listed only to the admin token: the devices that watch and where notifications go. No tool
// here or elsewhere creates an API token; a secret made in a tool call would sit in the assistant's transcript.

private const val OP = "op"
private const val CLIENT = "clientId"
private const val TARGET = "targetId"
private val DEVICE_OPS = listOf("list", "pair", "forget")
private val NOTIFICATION_OPS = listOf("list", "test")

private val DEVICES = ToolAnnotations(
    readOnlyHint = false,
    destructiveHint = true,
    idempotentHint = false,
    openWorldHint = false,
)

@Serializable internal class DeviceRow(
    val clientId: String,
    val name: String,
    val platform: String,
    val firstSeenAt: String,
    val lastSeenAt: String,
    val tokenLabel: String? = null,
    val tokenCreatedAt: String? = null,
    val tokenLastUsedAt: String? = null,
)

@Serializable internal class DeviceList(val devices: Capped<DeviceRow>)

@Serializable internal class PairingIssued(val code: String, val expiresAt: String, val message: String)

@Serializable internal class TargetRow(
    val id: String,
    val name: String,
    val kind: String,
    /** Where it posts, without scheme or path: the path is the credential. */
    val host: String,
    val hasSecret: Boolean,
    val events: List<String>,
    val enabled: Boolean,
    val lastDelivery: NotificationDelivery? = null,
)

@Serializable internal class Targets(val targets: Capped<TargetRow>, val recent: Capped<NotificationDelivery>? = null)

internal fun Server.householdTools(context: McpContext) {
    devicesTool(context.runtime)
    context.runtime.notifications?.let { notificationsTool(it) }
}

private fun Server.devicesTool(runtime: LiveRuntime) {
    tool(
        "devices",
        "The devices that watch. list shows each paired or known device with its name, platform, when it was first " +
            "and last seen, and its own token's label and last use, never a secret. pair issues a six-digit " +
            "pairing code that a device types in to claim a token of its own; it expires within minutes. forget " +
            "removes a device and revokes its token, so it must pair again, and needs confirm true. Tokens are " +
            "made on the admin portal, not here.",
        schema(
            OP to choice("What to do", DEVICE_OPS),
            CLIENT to string("The device's clientId, from list, for forget"),
            CONFIRM to boolean("Required true for forget"),
            LIMIT to integer("How many devices for list", 1..MAX_PAGE, DEFAULT_PAGE),
            required = listOf(OP),
        ),
        DEVICES,
    ) { arguments ->
        when (arguments.choice(OP, DEVICE_OPS) ?: invalid("op is list, pair or forget")) {
            "list" -> listDevices(runtime, arguments.int(LIMIT, DEFAULT_PAGE, 1..MAX_PAGE)).asJson()

            "pair" -> runtime.pairing.issue().let {
                PairingIssued(
                    it.code,
                    it.expiresAt.toString(),
                    "Show the operator this code; the device claims it at its pairing screen before it expires",
                ).asJson()
            }

            else -> forgetDevice(runtime, arguments).asJson()
        }
    }
}

private suspend fun listDevices(runtime: LiveRuntime, limit: Int): DeviceList {
    val tokens = runtime.tokens.all().filter { it.clientId != null }.associateBy { it.clientId }
    val rows = runtime.viewersWithTokens().map {
        val token = tokens[it.clientId]
        DeviceRow(
            it.clientId,
            it.name,
            it.platform,
            it.firstSeenAt,
            it.lastSeenAt,
            token?.label,
            token?.createdAt,
            token?.lastUsedAt,
        )
    }
    return DeviceList(rows.capped(limit))
}

private suspend fun forgetDevice(runtime: LiveRuntime, arguments: Arguments): ActionResult {
    val clientId = validClientId(arguments.string(CLIENT) ?: invalid("clientId is required; call devices op list"))
    arguments.confirmed("the device $clientId would be forgotten and its token revoked, so it must pair again")
    val revoked = runtime.tokens.revokeClient(clientId)
    if (!runtime.store.forgetViewer(clientId) && revoked == 0) {
        throw ApiFailure(HttpStatusCode.NotFound, "VIEWER_NOT_FOUND", "Unknown viewer; call devices op list")
    }
    return ActionResult(true, "Forgot $clientId and revoked $revoked ${if (revoked == 1) "token" else "tokens"}")
}

private fun Server.notificationsTool(service: NotificationTargetsService) {
    tool(
        "notifications",
        "Where the server sends notifications. list shows each target with its kind, host (never the full URL, " +
            "whose path is the credential), events, whether it is on and its newest delivery; with targetId it " +
            "adds that target's recent deliveries. test sends one target a test notification now and answers how " +
            "it went. Targets are added and changed on the admin portal's Connect page.",
        schema(
            OP to choice("What to do", NOTIFICATION_OPS),
            TARGET to string("The target's id, from list, for test or to list its deliveries"),
            LIMIT to integer("How many targets or deliveries", 1..MAX_PAGE, DEFAULT_PAGE),
            required = listOf(OP),
        ),
        ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = true),
    ) { arguments ->
        val limit = arguments.int(LIMIT, DEFAULT_PAGE, 1..MAX_PAGE)
        val id = arguments.string(TARGET)
        when (arguments.choice(OP, NOTIFICATION_OPS) ?: invalid("op is list or test")) {
            "list" -> Targets(
                service.list().map { it.row() }.capped(limit),
                id?.let { service.deliveries(it).capped(limit) },
            ).asJson()

            else -> service.test(id ?: invalid("targetId is required; call notifications op list")).asJson()
        }
    }
}

private fun NotificationTarget.row() = TargetRow(
    id,
    name,
    kind,
    url.substringAfter("://").substringBefore('/'),
    hasSecret,
    events,
    enabled,
    lastDelivery,
)
