package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.hash
import dev.jamesrich.sofaking.backend.store.IdentityStore
import dev.jamesrich.sofaking.backend.upstream.probeHeadersSafe
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.job
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

/**
 * The HDHomeRun a Plex server expects at the root: discovery and lineup JSON, the UPnP description, and one
 * MPEG-TS stream per channel number. Plex sends no credentials, so the only gate is the address allow-list.
 */
fun Route.hdhrRoutes(runtime: LiveRuntime, config: HdhrConfig, store: IdentityStore) {
    val allow = AddressAllowList(config.allow)
    val tuners = AtomicInteger()

    suspend fun guard(call: ApplicationCall): Boolean {
        if (allow.allows(call.request.origin.remoteAddress)) return true
        call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        return false
    }

    suspend fun deviceId(): String = config.deviceId ?: hash(store.serverId()).take(8).uppercase()

    fun lineup(base: String): List<LineupItem> {
        val seen = HashSet<String>()
        return runtime.lineup.entries().filter { it.enabled && seen.add(it.number) }.map { LineupItem(it.number, it.name, "$base/auto/v${it.number}") }
    }

    get("/discover.json") {
        if (!guard(call)) return@get
        val base = publicBase(call)
        call.respond(Discover(friendlyName = config.friendlyName, deviceId = deviceId(), baseUrl = base, lineupUrl = "$base/lineup.json", tunerCount = config.tuners))
    }
    get("/lineup.json") {
        if (!guard(call)) return@get
        call.respond(lineup(publicBase(call)))
    }
    get("/lineup_status.json") {
        if (!guard(call)) return@get
        call.respond(LineupStatus())
    }
    // Plex asks for a channel scan; the lineup is whatever the household curated, so there is nothing to scan.
    get("/lineup.post") { if (guard(call)) call.respond(HttpStatusCode.OK) }
    post("/lineup.post") { if (guard(call)) call.respond(HttpStatusCode.OK) }
    get("/device.xml") {
        if (!guard(call)) return@get
        val base = publicBase(call)
        call.respondText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
            <specVersion><major>1</major><minor>0</minor></specVersion>
            <URLBase>${xml(base)}</URLBase>
            <device>
            <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
            <friendlyName>${xml(config.friendlyName)}</friendlyName>
            <manufacturer>Silicondust</manufacturer>
            <modelName>HDTC-2US</modelName>
            <modelNumber>HDTC-2US</modelNumber>
            <serialNumber>${deviceId()}</serialNumber>
            <UDN>uuid:${xml(store.serverId())}</UDN>
            </device>
            </root>
            """.trimIndent(),
            ContentType.Application.Xml,
        )
    }
    get("/auto/v{number}") {
        if (!guard(call)) return@get
        val number = call.parameters["number"]!!
        val entry = runtime.lineup.entries().firstOrNull { it.enabled && it.number == number } ?: throw ApiFailure(404, "CHANNEL_NOT_FOUND", "Unknown channel number")
        if (tuners.incrementAndGet() > config.tuners) {
            tuners.decrementAndGet()
            tunersBusy(call)
            return@get
        }
        try {
            serveExternal(call, runtime, entry.id, shareHls = false) { session ->
                // FFmpeg reads the source itself, so this request, not media traffic, is what holds the session.
                runtime.sessions.attach(session.id, coroutineContext.job)
                val offering = runtime.sessions.offeringOf(session.id)
                runtime.feeds.upstream.check(URI(offering.url))
                val headers = runtime.feeds.config(offering.providerId).headers.takeIf(::probeHeadersSafe).orEmpty()
                runtime.remux.stream(call, offering.url, headers)
                false
            }
        } catch (e: ApiFailure) {
            // Plex understands a tuner that is busy; a session slot or a source that is gone looks the same to it.
            if (e.status == 429 || e.status == 503) tunersBusy(call) else throw e
        } finally {
            tuners.decrementAndGet()
        }
    }
}

private suspend fun tunersBusy(call: ApplicationCall) {
    call.response.header("X-HDHomeRun-Error", "805 All Tuners In Use")
    call.respondText("All tuners in use", status = HttpStatusCode.ServiceUnavailable)
}
