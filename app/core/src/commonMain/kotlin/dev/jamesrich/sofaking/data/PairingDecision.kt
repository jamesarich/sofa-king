package dev.jamesrich.sofaking.data

import io.ktor.http.URLProtocol
import io.ktor.http.parseUrl

/** What the app does with a pairing link or fragment it received. */
enum class PairingDecision {
    /** Pair with the server the link names, with no question. */
    PAIR,

    /** The link names another server than the saved one: ask before this device stops using the saved one. */
    ASK,

    /** The link names no usable server. */
    IGNORE,
}

/**
 * What to do with [request] while [configuredServer] is the saved server address (empty when there is none). A device
 * with no server, or a link naming the saved server's origin, pairs at once; a link naming another server asks first,
 * so a web page, a QR code or another app cannot move the device to a server of its own choosing.
 */
fun decidePairing(configuredServer: String, request: PairingRequest): PairingDecision {
    val requested = parseOrigin(request.serverUrl) ?: return PairingDecision.IGNORE
    if (configuredServer.isBlank()) return PairingDecision.PAIR
    return if (parseOrigin(configuredServer) == requested) PairingDecision.PAIR else PairingDecision.ASK
}

/** [url] as the host and port a person reads, `sofa.lan:9193`; the port is left off when it is its scheme's own. */
fun serverLabel(url: String): String {
    val origin = parseOrigin(url) ?: return url
    return if (origin.port == origin.protocol.defaultPort) origin.host else "${origin.host}:${origin.port}"
}

/** The scheme, host and port that make a server one server, whatever its address spells out. */
private data class Origin(val protocol: URLProtocol, val host: String, val port: Int)

private fun parseOrigin(raw: String): Origin? {
    val address = ServerAddress.normalizeUrl(raw) ?: return null
    val url = parseUrl(address) ?: return null
    return Origin(url.protocol, url.host.lowercase(), url.port)
}
