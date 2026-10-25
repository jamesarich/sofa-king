package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart

// The endpoints of [SofaKingApi] about the server and this device: what it offers, pairing, synced preferences.

/** The server's id, time and [CapabilitiesDto]: what it offers beyond live TV. */
internal suspend fun SofaKingApi.bootstrap(): BootstrapDto = call {
    http.get("bootstrap").decode()
}

/**
 * Claims a pairing code; the client built for this has no token yet. A wrong or spent code is a [SofaKingException]
 * with [Wire.ErrorCode.PAIRING_INVALID].
 */
internal suspend fun SofaKingApi.pair(claim: PairingClaimDto): PairingResultDto = call {
    http.post("pair") { json(claim) }.decode()
}

/** The preferences the server keeps for [clientId], or null when it has none (or is too old to keep any). */
internal suspend fun SofaKingApi.preferences(clientId: String): ViewerPreferencesDto? = call {
    val response = http.get("preferences/" + clientId.encodeURLPathPart())
    if (response.status == HttpStatusCode.NotFound) return@call null
    response.decode()
}

/** Replaces the server's copy of [clientId]'s preferences; the answer carries the server's stamp. */
internal suspend fun SofaKingApi.savePreferences(
    clientId: String,
    preferences: ViewerPreferencesDto,
): ViewerPreferencesDto = call {
    http.put("preferences/" + clientId.encodeURLPathPart()) { json(preferences) }.decode()
}

/** The household's shared favorites and hidden channels; an empty `updatedAt` before any device saved them. */
internal suspend fun SofaKingApi.householdPreferences(): HouseholdPreferencesDto = call {
    http.get("household/preferences").decode()
}

/** Replaces the household's shared copy; the answer carries the server's stamp. */
internal suspend fun SofaKingApi.saveHouseholdPreferences(
    preferences: HouseholdPreferencesDto,
): HouseholdPreferencesDto = call {
    http.put("household/preferences") { json(preferences) }.decode()
}
