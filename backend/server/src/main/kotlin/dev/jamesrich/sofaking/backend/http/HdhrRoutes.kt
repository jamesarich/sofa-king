package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.catalog.hash
import dev.jamesrich.sofaking.backend.catalog.stableId
import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.store.IdentityStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.job
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

@Serializable
internal data class Discover(
    @SerialName("FriendlyName") val friendlyName: String,
    @SerialName("Manufacturer") val manufacturer: String = "Silicondust",
    @SerialName("ManufacturerURL") val manufacturerUrl: String = "https://www.silicondust.com/",
    @SerialName("ModelNumber") val modelNumber: String = "HDTC-2US",
    @SerialName("FirmwareName") val firmwareName: String = "hdhomeruntc_atsc",
    @SerialName("FirmwareVersion") val firmwareVersion: String = "20200101",
    @SerialName("DeviceID") val deviceId: String,
    @SerialName("DeviceAuth") val deviceAuth: String = "sofa-king",
    @SerialName("BaseURL") val baseUrl: String,
    @SerialName("LineupURL") val lineupUrl: String,
    @SerialName("TunerCount") val tunerCount: Int,
)

@Serializable
internal data class LineupItem(
    @SerialName("GuideNumber") val guideNumber: String,
    @SerialName("GuideName") val guideName: String,
    @SerialName("URL") val url: String,
)

@Serializable
internal data class LineupStatus(
    @SerialName("ScanInProgress") val scanInProgress: Int = 0,
    @SerialName("ScanPossible") val scanPossible: Int = 0,
    @SerialName("Source") val source: String = "Cable",
    @SerialName("SourceList") val sourceList: List<String> = listOf("Cable"),
)

/** The enabled entries the tuners serve, in lineup order: the first entry of each number. */
internal fun hdhrChannels(entries: List<LineupEntryRecord>): List<LineupEntryRecord> {
    val seen = HashSet<String>()
    return entries.filter { it.enabled && seen.add(it.number) }
}

/**
 * The channels of [device] among [enabled]: by a stable hash of the entry ID, or [byRange] (with a policy) in
 * consecutive ranges of `deviceSize`, so a new channel moves only its own range; the overflow stays on the last device.
 */
internal fun deviceShare(
    enabled: List<LineupEntryRecord>,
    device: Int,
    config: HdhrConfig,
    byRange: Boolean,
): List<LineupEntryRecord> = if (!byRange) {
    enabled.filter { Math.floorMod(it.id.hashCode(), config.devices) == device }
} else {
    enabled.filterIndexed { index, _ -> minOf(index / config.deviceSize, config.devices - 1) == device }
}

/**
 * The HDHomeRun a Plex server expects at the root: discovery and lineup JSON, the UPnP description, and one
 * MPEG-TS stream per channel number. Plex sends no credentials, so the only gate is [access], the client's address.
 */
fun Route.hdhrRoutes(
    runtime: LiveRuntime,
    config: HdhrConfig,
    store: IdentityStore,
    access: HdhrAccess = runtime.hdhrAccess,
) {
    val hdhr = HdhrDevices(runtime, config, store, access)
    get("/discover.json") {
        if (hdhr.admits(call)) call.respond(hdhr.discover(publicBase(call), hdhr.device(call)))
    }
    get("/lineup.json") {
        if (hdhr.admits(call)) call.respond(hdhr.lineup(publicBase(call), hdhr.device(call)))
    }
    get("/lineup_status.json") { if (hdhr.admits(call)) call.respond(LineupStatus()) }
    // Plex asks for a channel scan; the lineup is built ahead of time, so there is nothing to scan.
    get("/lineup.post") { if (hdhr.admits(call)) call.respond(HttpStatusCode.OK) }
    post("/lineup.post") { if (hdhr.admits(call)) call.respond(HttpStatusCode.OK) }
    get("/device.xml") {
        if (hdhr.admits(call)) {
            call.respondText(hdhr.description(publicBase(call), hdhr.device(call)), ContentType.Application.Xml)
        }
    }
    get("/auto/v{number}") { if (hdhr.admits(call)) hdhr.tune(call, call.parameters["number"]!!) }
}

/** The emulated HDHomeRun devices: one on the main port, then one per extra port, each with its own tuners. */
private class HdhrDevices(
    private val runtime: LiveRuntime,
    private val config: HdhrConfig,
    private val store: IdentityStore,
    private val access: HdhrAccess,
) {
    private val tuners = Array(config.devices) { AtomicInteger() }
    private val overflowWarned = AtomicInteger()

    /** Whether [call] may reach the devices; answers it Forbidden when not. */
    suspend fun admits(call: ApplicationCall): Boolean {
        // Open to every address only on loopback, and then only to this machine, so a reverse proxy cannot expose it.
        val admitted = access.admits(call.request.origin.remoteAddress, call.request.path()) &&
            (!access.open || call.request.fromThisMachine())
        if (!admitted) call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        return admitted
    }

    /** The device a request reached: 0 on the main port, then one per extra port in order. */
    fun device(call: ApplicationCall): Int = config.extraPorts.indexOf(call.request.local.localPort) + 1

    private suspend fun deviceId(device: Int): String = hdhrDeviceId(config, store.serverId(), device)

    private fun name(device: Int): String =
        if (device == 0) config.friendlyName else "${config.friendlyName} ${device + 1}"

    suspend fun discover(base: String, device: Int) = Discover(
        friendlyName = name(device),
        deviceId = deviceId(device),
        baseUrl = base,
        lineupUrl = "$base/lineup.json",
        tunerCount = runtime.settings.current.hdhrTuners,
    )

    fun lineup(base: String, device: Int): List<LineupItem> {
        val enabled = hdhrChannels(runtime.lineup.entries())
        val byRange = runtime.policy != null
        val capacity = config.devices * config.deviceSize
        if (byRange && enabled.size > capacity && overflowWarned.getAndSet(enabled.size) != enabled.size) {
            log.warn(
                "HDHomeRun devices hold {} channels but {} are enabled; the rest go on the last device, add " +
                    "ports to SOFA_HDHR_EXTRA_PORTS",
                capacity,
                enabled.size,
            )
        }
        return deviceShare(enabled, device, config, byRange).map {
            LineupItem(it.number, it.name, "$base/auto/v${it.number}")
        }
    }

    /** The UPnP description of [device]. */
    suspend fun description(base: String, device: Int): String {
        val udn = if (device == 0) store.serverId() else stableId("${store.serverId()}:hdhr:$device")
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
            <specVersion><major>1</major><minor>0</minor></specVersion>
            <URLBase>${xml(base)}</URLBase>
            <device>
            <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
            <friendlyName>${xml(name(device))}</friendlyName>
            <manufacturer>Silicondust</manufacturer>
            <modelName>HDTC-2US</modelName>
            <modelNumber>HDTC-2US</modelNumber>
            <serialNumber>${deviceId(device)}</serialNumber>
            <UDN>uuid:${xml(udn)}</UDN>
            </device>
            </root>
        """.trimIndent()
    }

    /** Streams channel [number] as MPEG-TS on one of the device's tuners, or answers that they are all in use. */
    suspend fun tune(call: ApplicationCall, number: String) {
        val entry = runtime.lineup.entries().firstOrNull { it.enabled && it.number == number }
            ?: throw ApiFailure(HttpStatusCode.NotFound, "CHANNEL_NOT_FOUND", "Unknown channel number")
        val tuners = tuners[device(call)]
        try {
            if (tuners.incrementAndGet() > runtime.settings.current.hdhrTuners) {
                tunersBusy(call)
            } else {
                serveExternal(call, runtime, entry.id, shareHls = false) { session -> remux(call, session.id) }
            }
        } catch (e: ApiFailure) {
            // Plex understands a tuner that is busy; a session slot or a source that is gone looks the same to it.
            if (e.status != HttpStatusCode.TooManyRequests && e.status != HttpStatusCode.ServiceUnavailable) throw e
            tunersBusy(call)
        } finally {
            tuners.decrementAndGet()
        }
    }

    private suspend fun remux(call: ApplicationCall, sessionId: String): Boolean {
        // FFmpeg reads the source through the loopback relay, not the session, so this request is what holds the
        // session.
        val media = runtime.sessions.media
        media.attach(sessionId, coroutineContext.job)
        val stream = try {
            runtime.feeds.playable(media.offeringOf(sessionId))
        } catch (e: ProviderFailure) {
            throw ApiFailure(
                HttpStatusCode.ServiceUnavailable,
                "SOURCE_UNAVAILABLE",
                "Source could not be resolved",
                true,
                e,
            )
        }
        runtime.feeds.upstream.check(URI(stream.url))
        val bytes = media.bytesOf(sessionId)
        runtime.remux.stream(call, stream) { bytes.addAndGet(it.toLong()) }
        return false
    }

    private companion object {
        val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("dev.jamesrich.sofaking.backend.http.Hdhr")
    }
}

/**
 * The DeviceID Plex knows [device] by: the configured or server-derived ID for device 0, then one
 * derived from it per extra port.
 */
fun hdhrDeviceId(config: HdhrConfig, serverId: String, device: Int): String {
    val first = config.deviceId ?: hash(serverId).take(DEVICE_ID_DIGITS).uppercase()
    return if (device == 0) first else hash("$first:$device").take(DEVICE_ID_DIGITS).uppercase()
}

private suspend fun tunersBusy(call: ApplicationCall) {
    call.response.header("X-HDHomeRun-Error", "805 All Tuners In Use")
    call.respondText("All tuners in use", status = HttpStatusCode.ServiceUnavailable)
}

/** An HDHomeRun DeviceID is eight hex digits. */
private const val DEVICE_ID_DIGITS = 8
