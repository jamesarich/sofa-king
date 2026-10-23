package dev.jamesrich.sofaking.contract

import kotlinx.serialization.Serializable

// Pairing and viewer preferences; the same compatibility rules as LiveTv.kt apply.

/**
 * What a client sends to `POST /api/v1/pair`, which takes no bearer token: the code the admin portal shows, and
 * who is claiming it. [clientId] is the client's own stable id (see [ViewerPreferences]); [name] and [platform]
 * only label the viewer in the portal.
 */
@Serializable
data class PairingClaim(val code: String, val clientId: String = "", val name: String = "", val platform: String = "")

/** A claimed code's answer: the API token the client stores, and the id of the server it opens. */
@Serializable data class PairingResult(val apiToken: String, val serverId: String = "")

/**
 * One client's preferences as the server keeps them under `/api/v1/preferences/{clientId}`, so a reinstall or a
 * second device of the same viewer can take them over. [hidden] are channel ids the client leaves out,
 * [favorites] the starred channel ids in the viewer's order, and [recent] the channels watched last, newest first.
 * The server sets [updatedAt] on every write; a client compares it to decide whose copy is newer.
 */
@Serializable data class ViewerPreferences(
    val hidden: List<String> = emptyList(),
    val favorites: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
    val name: String = "",
    val platform: String = "",
    val updatedAt: String = "",
)
