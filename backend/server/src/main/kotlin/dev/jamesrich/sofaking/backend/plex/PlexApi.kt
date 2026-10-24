package dev.jamesrich.sofaking.backend.plex

import dev.jamesrich.sofaking.backend.PlexConfig
import dev.jamesrich.sofaking.backend.api.PlexDeviceStatus
import dev.jamesrich.sofaking.backend.api.PlexStatus
import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.runCatchingCancellable
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
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel as Trigger

private const val GRABBER = "device://tv.plex.grabbers.hdhomerun/"

private class PlexFailure(message: String) : Exception(message)

internal class Dvr(val key: String, val lineup: String?, val devices: List<Device>)

internal class Device(val key: String, val uuid: String)

internal class Mapping(val deviceIdentifier: String, val lineupIdentifier: String, val channelKey: String)

/** The Plex Media Server API the sync uses, at [PlexConfig.url] with [PlexConfig.token]. */
internal class PlexApi(private val config: PlexConfig, private val http: HttpClient) {
    private val log = LoggerFactory.getLogger(PlexSync::class.java)

    suspend fun dvrs(): List<Dvr> = (send(HttpMethod.Get, "/livetv/dvrs").container("Dvr")).map { dvr ->
        Dvr(
            dvr.text("key") ?: throw PlexFailure("a DVR has no key"),
            dvr.text("lineup"),
            dvr.list("Device").mapNotNull(::deviceOf),
        )
    }

    /**
     * Adds [device] at the tuner base to [dvr]; refuses a device that reports another
     * DeviceID, before it joins the DVR.
     */
    suspend fun add(dvr: Dvr, device: HdhrDevice): Device {
        val uri = "${config.tunerBase}:${device.port}"
        val created =
            send(
                HttpMethod.Post,
                "/media/grabbers/devices",
                listOf("uri" to uri),
            ).container("Device").firstNotNullOfOrNull(::deviceOf)
                ?: throw PlexFailure("Plex added no device at port ${device.port}")
        if (!created.uuid.endsWith("/${device.id}", ignoreCase = true)) {
            throw PlexFailure(
                "port ${device.port} answers as ${created.uuid.removePrefix(GRABBER)}, not ${device.id}",
            )
        }
        send(HttpMethod.Put, "/livetv/dvrs/${dvr.key.encodeURLPathPart()}/devices/${created.key.encodeURLPathPart()}")
        log.info("plex added device id={} port={} dvr={} key={}", device.id, device.port, dvr.key, created.key)
        return created
    }

    /** Saves the channel map Plex proposes for [device] with every channel enabled; returns the channels saved. */
    suspend fun map(dvr: Dvr, device: Device, mine: HdhrDevice, findings: MutableList<String>): Int {
        val lineup = dvr.lineup ?: throw PlexFailure("DVR ${dvr.key} has no lineup")
        val mappings = send(
            HttpMethod.Get,
            "/livetv/epg/channelmap",
            listOf("device" to device.uuid, "lineup" to lineup),
        ).container("ChannelMapping").mapNotNull { m ->
            Mapping(
                m.text("deviceIdentifier") ?: return@mapNotNull null,
                m.text("lineupIdentifier") ?: return@mapNotNull null,
                m.text("channelKey") ?: return@mapNotNull null,
            )
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
            findings +=
                "plex: device ${mine.id} has ${mappings.size} channels, too many for Plex to save; lower " +
                "SOFA_HDHR_DEVICE_SIZE or add ports"
            return 0
        }
        request(HttpMethod.Put, url, "/media/grabbers/devices/${device.key}/channelmap")
        return mappings.size
    }

    /** Asks Plex to read [dvr]'s guide again, after its channel maps changed. */
    suspend fun reloadGuide(dvr: String) {
        send(HttpMethod.Post, "/livetv/dvrs/${dvr.encodeURLPathPart()}/reloadGuide")
    }

    private fun url(path: String, parameters: List<Pair<String, String>>): String =
        URLBuilder("${config.url}$path").apply {
            for ((name, value) in parameters) this.parameters.append(name, value)
        }.buildString()

    private suspend fun send(
        method: HttpMethod,
        path: String,
        parameters: List<Pair<String, String>> = emptyList(),
    ): JsonObject = request(method, url(path, parameters), path)

    /** The token goes in a header only, so no URL, log line or error message carries it. Redirects are not followed. */
    private suspend fun request(method: HttpMethod, url: String, path: String): JsonObject {
        val response = http.request(url) {
            this.method = method
            header("X-Plex-Token", config.token)
            accept(ContentType.Application.Json)
            timeout {
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
            }
        }
        if (!response.status.isSuccess()) throw PlexFailure("${method.value} $path answered ${response.status.value}")
        val body = response.bodyAsText()
        if (body.isBlank()) return JsonObject(emptyMap())
        return Json.parseToJsonElement(body) as? JsonObject
            ?: throw PlexFailure("${method.value} $path answered something other than a JSON object")
    }

    fun message(e: Throwable): String {
        val text = if (e is PlexFailure) e.message.orEmpty() else "${e.javaClass.simpleName}: ${redactedMessage(e)}"
        return text.replace(config.token, "<token>")
    }
}

private fun deviceOf(device: JsonObject): Device? {
    val key = device.text("key") ?: return null
    return Device(key, device.text("uuid") ?: return null)
}

private fun JsonObject.container(name: String): List<JsonObject> =
    (this["MediaContainer"] as? JsonObject)?.list(name).orEmpty()

private fun JsonObject.list(name: String): List<JsonObject> = when (val value = this[name]) {
    is JsonArray -> value.filterIsInstance<JsonObject>()
    is JsonObject -> listOf(value)
    else -> emptyList()
}

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless {
    it is JsonNull
}?.content?.takeIf { it.isNotEmpty() }
