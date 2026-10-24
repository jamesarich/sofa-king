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
    val tuners = Array(config.devices) { AtomicInteger() }
    val log = org.slf4j.LoggerFactory.getLogger("dev.jamesrich.sofaking.backend.http.Hdhr")

    suspend fun guard(call: ApplicationCall): Boolean {
        // Open to every address only on loopback, and then only to this machine, so a reverse proxy cannot expose it.
        if (access.admits(call.request.origin.remoteAddress, call.request.path()) &&
            (!access.open || call.request.fromThisMachine())
        ) {
            return true
        }
        call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        return false
    }

    /** The device a request reached: 0 on the main port, then one per extra port in order. */
    fun device(call: ApplicationCall): Int = config.extraPorts.indexOf(call.request.local.localPort) + 1

    suspend fun deviceId(device: Int): String = hdhrDeviceId(config, store.serverId(), device)

    fun name(device: Int): String = if (device == 0) config.friendlyName else "${config.friendlyName} ${device + 1}"

    val overflowWarned = AtomicInteger()

    fun lineup(base: String, device: Int): List<LineupItem> {
        val enabled = hdhrChannels(runtime.lineup.entries())
        val byRange = runtime.policy != null
        if (byRange) {
            val capacity = config.devices * config.deviceSize
            if (enabled.size > capacity && overflowWarned.getAndSet(enabled.size) != enabled.size) {
                log.warn(
                    "HDHomeRun devices hold {} channels but {} are enabled; the rest go on the last device, add " +
                        "ports to SOFA_HDHR_EXTRA_PORTS",
                    capacity,
                    enabled.size,
                )
            }
        }
        return deviceShare(enabled, device, config, byRange).map {
            LineupItem(it.number, it.name, "$base/auto/v${it.number}")
        }
    }

    get("/discover.json") {
        if (!guard(call)) return@get
        val base = publicBase(call)
        val device = device(call)
        call.respond(
            Discover(
                friendlyName = name(device),
                deviceId = deviceId(device),
                baseUrl = base,
                lineupUrl = "$base/lineup.json",
                tunerCount = runtime.settings.current.hdhrTuners,
            ),
        )
    }
    get("/lineup.json") {
        if (!guard(call)) return@get
        call.respond(lineup(publicBase(call), device(call)))
    }
    get("/lineup_status.json") {
        if (!guard(call)) return@get
        call.respond(LineupStatus())
    }
    // Plex asks for a channel scan; the lineup is built ahead of time, so there is nothing to scan.
    get("/lineup.post") { if (guard(call)) call.respond(HttpStatusCode.OK) }
    post("/lineup.post") { if (guard(call)) call.respond(HttpStatusCode.OK) }
    get("/device.xml") {
        if (!guard(call)) return@get
        val base = publicBase(call)
        val device = device(call)
        val udn = if (device == 0) store.serverId() else stableId("${store.serverId()}:hdhr:$device")
        call.respondText(
            """
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
            """.trimIndent(),
            ContentType.Application.Xml,
        )
    }
    get("/auto/v{number}") {
        if (!guard(call)) return@get
        val number = call.parameters["number"]!!
        val entry =
            runtime.lineup.entries().firstOrNull { it.enabled && it.number == number }
                ?: throw ApiFailure(HttpStatusCode.NotFound, "CHANNEL_NOT_FOUND", "Unknown channel number")
        val tuners = tuners[device(call)]
        if (tuners.incrementAndGet() > runtime.settings.current.hdhrTuners) {
            tuners.decrementAndGet()
            tunersBusy(call)
            return@get
        }
        try {
            serveExternal(call, runtime, entry.id, shareHls = false) { session ->
                // FFmpeg reads the source through the loopback relay, not the session, so this
                // request is what holds the session.
                runtime.sessions.attach(session.id, coroutineContext.job)
                val offering = runtime.sessions.offeringOf(session.id)
                val stream = try {
                    runtime.feeds.playable(offering)
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
                val bytes = runtime.sessions.bytesOf(session.id)
                runtime.remux.stream(call, stream) { bytes.addAndGet(it.toLong()) }
                false
            }
        } catch (e: ApiFailure) {
            // Plex understands a tuner that is busy; a session slot or a source that is gone looks the same to it.
            if (e.status == HttpStatusCode.TooManyRequests ||
                e.status == HttpStatusCode.ServiceUnavailable
            ) {
                tunersBusy(call)
            } else {
                throw e
            }
        } finally {
            tuners.decrementAndGet()
        }
    }
}

/**
 * The DeviceID Plex knows [device] by: the configured or server-derived ID for device 0, then one
 * derived from it per extra port.
 */
fun hdhrDeviceId(config: HdhrConfig, serverId: String, device: Int): String {
    val first = config.deviceId ?: hash(serverId).take(8).uppercase()
    return if (device == 0) first else hash("$first:$device").take(8).uppercase()
}

private suspend fun tunersBusy(call: ApplicationCall) {
    call.response.header("X-HDHomeRun-Error", "805 All Tuners In Use")
    call.respondText("All tuners in use", status = HttpStatusCode.ServiceUnavailable)
}
