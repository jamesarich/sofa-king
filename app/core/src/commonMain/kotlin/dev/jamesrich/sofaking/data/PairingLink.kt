package dev.jamesrich.sofaking.data

import io.ktor.http.parseUrl

/** A server to pair with, as the admin portal's QR code or a typed code names it. */
data class PairingRequest(val serverUrl: String, val code: String)

/**
 * Reads a `sofaking://pair?server=URL&code=DIGITS` link, which the admin portal's QR code carries, into a
 * [PairingRequest]; null for any other link, a server address Settings would refuse, or a code that is not six digits.
 */
fun parsePairingLink(link: String): PairingRequest? {
    val url = parseUrl(link.trim()) ?: return null
    if (url.protocol.name != "sofaking" || url.host != "pair") return null
    val server = url.parameters["server"]?.let(ServerAddress::normalizeUrl) ?: return null
    val code = url.parameters["code"]?.let(ServerAddress::normalizeCode) ?: return null
    return PairingRequest(server, code)
}
