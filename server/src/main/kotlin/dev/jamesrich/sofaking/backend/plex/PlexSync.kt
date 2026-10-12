package dev.jamesrich.sofaking.backend.plex

import dev.jamesrich.sofaking.backend.PlexConfig
import dev.jamesrich.sofaking.backend.api.PlexDeviceStatus
import dev.jamesrich.sofaking.backend.api.PlexStatus
import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as Trigger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/** One of this server's HDHomeRun devices: its index, the DeviceID it reports, and the port Plex reaches it on. */
data class HdhrDevice(val index: Int, val id: String, val port: Int)

/** Plex rejects a longer channel-map request line; 35 KB fails where 25 KB saves. */
internal const val PLEX_URL_LIMIT = 30_000

private const val GRABBER = "device://tv.plex.grabbers.hdhomerun/"

private class PlexFailure(message: String) : Exception(message)

private class Dvr(val key: String, val lineup: String?, val devices: List<Device>)

private class Device(val key: String, val uuid: String)

private class Mapping(val deviceIdentifier: String, val lineupIdentifier: String, val channelKey: String)

/**
 * Keeps Plex Live TV's channel maps in step with the lineup: for each DVR device that is one of this server's tuners,
 * reads the channel map Plex proposes and saves it with every channel enabled, then reloads that DVR's guide. With
 * [PlexConfig.tunerBase] set it first adds devices no DVR holds to the first DVR that holds one of ours.
 */
class PlexSync(
    private val config: PlexConfig,
    private val http: HttpClient,
    private val clock: Clock,
    private val devices: suspend () -> List<HdhrDevice>,
    /** Quiet time after a lineup change before the sync, so a burst of changes is one sync. */
    private val debounceMillis: Long = 120_000,
    private val intervalMillis: Long = config.syncMinutes * 60_000L,
) {
    private val log = LoggerFactory.getLogger(PlexSync::class.java)
    private val mutex = Mutex()
    private val trigger = Trigger<String>(Trigger.CONFLATED)
    private val status = AtomicReference(PlexStatus(config.syncMinutes))

    fun status(): PlexStatus = status.get()

    /** Findings of the last sync, for the policy findings. */
    fun findings(): List<String> = status.get().findings

    /** Asks the loop to sync after the debounce; requests before it runs coalesce into one. */
    fun requestSync(cause: String) {
        trigger.trySend(cause)
    }

    fun start(scope: CoroutineScope): Job = scope.launch(CoroutineName("plex-sync")) {
        while (isActive) {
            val cause = select<String> {
                trigger.onReceive { it }
                onTimeout(intervalMillis) { "interval" }
            }
            if (cause != "interval") {
                delay(debounceMillis)
                trigger.tryReceive()
            }
            try { sync(cause) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log.warn("plex sync failed class={} message=\"{}\"", e.javaClass.simpleName, message(e))
            }
        }
    }

    /** One sync now; failures are recorded in the status, never thrown. */
    suspend fun sync(cause: String): PlexStatus = mutex.withLock {
        val started = clock.instant()
        val ours = devices()
        val errors = ArrayList<String>()
        val findings = ArrayList<String>()
        val mapped = ArrayList<PlexDeviceStatus>()
        val touched = LinkedHashSet<String>()
        var missing = emptyList<HdhrDevice>()
        try {
            val dvrs = dvrs()
            val present = ArrayList<Triple<Dvr, Device, HdhrDevice>>()
            for (dvr in dvrs) for (device in dvr.devices) ours.firstOrNull { device.uuid.endsWith("/${it.id}", ignoreCase = true) }?.let { present += Triple(dvr, device, it) }
            val absent = ours.filter { mine -> present.none { it.third == mine } }
            val added = HashSet<HdhrDevice>()
            val target = present.firstOrNull()?.first
            if (config.tunerBase != null && target != null) {
                for (device in absent) {
                    try {
                        present += Triple(target, add(target, device), device)
                        added += device
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { errors += "device ${device.id}: ${message(e)}" }
                }
            }
            missing = absent - added
            for (device in missing) findings += "plex: device ${device.id} (device ${device.index}) is not added to Plex"
            for ((dvr, device, mine) in present) {
                try {
                    val count = map(dvr, device, mine, findings)
                    if (count > 0) touched += dvr.key
                    mapped += PlexDeviceStatus(mine.id, mine.index, device.key, dvr.key, count, mine in added)
                } catch (e: CancellationException) { throw e } catch (e: Exception) { errors += "device ${mine.id}: ${message(e)}" }
            }
            for (dvr in touched) {
                try { send(HttpMethod.Post, "/livetv/dvrs/${dvr.encodeURLPathPart()}/reloadGuide") }
                catch (e: CancellationException) { throw e } catch (e: Exception) { errors += "DVR $dvr: ${message(e)}" }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            errors += message(e)
        }
        val error = errors.takeIf { it.isNotEmpty() }?.joinToString("; ")?.take(1000)
        if (error != null) findings += "plex: sync failed: $error"
        val next = PlexStatus(
            config.syncMinutes, clock.instant().toString(), cause, error, touched.toList(), mapped.sortedBy { it.device }, missing.map { it.id }, findings,
        )
        status.set(next)
        if (error != null) log.warn("plex sync cause={} error=\"{}\"", cause, error)
        log.info(
            "plex sync cause={} dvrs={} devices={} mapped={} missing={} durationMs={}",
            cause, touched.size, mapped.size, mapped.sumOf { it.mapped }, missing.size, Duration.between(started, clock.instant()).toMillis(),
        )
        next
    }

    private suspend fun dvrs(): List<Dvr> = (send(HttpMethod.Get, "/livetv/dvrs").container("Dvr")).map { dvr ->
        Dvr(dvr.text("key") ?: throw PlexFailure("a DVR has no key"), dvr.text("lineup"), dvr.list("Device").mapNotNull(::deviceOf))
    }

    private fun deviceOf(device: JsonObject): Device? {
        val key = device.text("key") ?: return null
        return Device(key, device.text("uuid") ?: return null)
    }

    /** Adds [device] at the tuner base to [dvr]; refuses a device that reports another DeviceID, before it joins the DVR. */
    private suspend fun add(dvr: Dvr, device: HdhrDevice): Device {
        val uri = "${config.tunerBase}:${device.port}"
        val created = send(HttpMethod.Post, "/media/grabbers/devices", listOf("uri" to uri)).container("Device").firstNotNullOfOrNull(::deviceOf)
            ?: throw PlexFailure("Plex added no device at port ${device.port}")
        if (!created.uuid.endsWith("/${device.id}", ignoreCase = true)) throw PlexFailure("port ${device.port} answers as ${created.uuid.removePrefix(GRABBER)}, not ${device.id}")
        send(HttpMethod.Put, "/livetv/dvrs/${dvr.key.encodeURLPathPart()}/devices/${created.key.encodeURLPathPart()}")
        log.info("plex added device id={} port={} dvr={} key={}", device.id, device.port, dvr.key, created.key)
        return created
    }

    /** Saves the channel map Plex proposes for [device] with every channel enabled; returns the channels saved. */
    private suspend fun map(dvr: Dvr, device: Device, mine: HdhrDevice, findings: MutableList<String>): Int {
        val lineup = dvr.lineup ?: throw PlexFailure("DVR ${dvr.key} has no lineup")
        val mappings = send(HttpMethod.Get, "/livetv/epg/channelmap", listOf("device" to device.uuid, "lineup" to lineup)).container("ChannelMapping").mapNotNull { m ->
            Mapping(m.text("deviceIdentifier") ?: return@mapNotNull null, m.text("lineupIdentifier") ?: return@mapNotNull null, m.text("channelKey") ?: return@mapNotNull null)
        }
        if (mappings.isEmpty()) return 0
        val parameters = ArrayList<Pair<String, String>>()
        parameters += "channelsEnabled" to mappings.joinToString(",") { it.deviceIdentifier }
        for (m in mappings) {
            parameters += "channelMappingByKey[${m.deviceIdentifier}]" to m.channelKey
            parameters += "channelMapping[${m.deviceIdentifier}]" to m.lineupIdentifier
        }
        val url = url("/media/grabbers/devices/${device.key.encodeURLPathPart()}/channelmap", parameters)
        if (url.length > PLEX_URL_LIMIT) {
            findings += "plex: device ${mine.id} has ${mappings.size} channels, too many for Plex to save; lower SOFA_HDHR_DEVICE_SIZE or add ports"
            return 0
        }
        request(HttpMethod.Put, url, "/media/grabbers/devices/${device.key}/channelmap")
        return mappings.size
    }

    private fun url(path: String, parameters: List<Pair<String, String>>): String =
        URLBuilder("${config.url}$path").apply { for ((name, value) in parameters) this.parameters.append(name, value) }.buildString()

    private suspend fun send(method: HttpMethod, path: String, parameters: List<Pair<String, String>> = emptyList()): JsonObject =
        request(method, url(path, parameters), path)

    /** The token goes in a header only, so no URL, log line or error message carries it. Redirects are not followed. */
    private suspend fun request(method: HttpMethod, url: String, path: String): JsonObject {
        val response = http.request(url) {
            this.method = method
            header("X-Plex-Token", config.token)
            accept(ContentType.Application.Json)
            timeout { requestTimeoutMillis = 60_000; socketTimeoutMillis = 60_000 }
        }
        if (!response.status.isSuccess()) throw PlexFailure("${method.value} $path answered ${response.status.value}")
        val body = response.bodyAsText()
        if (body.isBlank()) return JsonObject(emptyMap())
        return Json.parseToJsonElement(body) as? JsonObject ?: throw PlexFailure("${method.value} $path answered something other than a JSON object")
    }

    private fun message(e: Throwable): String {
        val text = if (e is PlexFailure) e.message.orEmpty() else "${e.javaClass.simpleName}: ${redactedMessage(e)}"
        return text.replace(config.token, "<token>")
    }
}

private fun JsonObject.container(name: String): List<JsonObject> = (this["MediaContainer"] as? JsonObject)?.list(name).orEmpty()

private fun JsonObject.list(name: String): List<JsonObject> = when (val value = this[name]) {
    is JsonArray -> value.filterIsInstance<JsonObject>()
    is JsonObject -> listOf(value)
    else -> emptyList()
}

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.takeIf { it.isNotEmpty() }
