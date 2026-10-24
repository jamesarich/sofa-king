package dev.jamesrich.sofaking.data

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

// The live TV endpoints of [SofaKingApi]: the catalog and guide, and the playback sessions that tune a channel.

internal suspend fun SofaKingApi.catalog(etag: String?): CatalogResult = call {
    val response = http.get("channels") {
        if (etag != null) header(HttpHeaders.IfNoneMatch, etag)
    }
    if (response.status == HttpStatusCode.NotModified) return@call CatalogResult.NotModified
    CatalogResult.Changed(response.decode(), response.headers[HttpHeaders.ETag])
}

internal suspend fun SofaKingApi.guide(query: GuideQueryDto): GuideWindowDto = call {
    http.post("guide/query") { json(query) }.decode()
}

internal suspend fun SofaKingApi.openSession(
    channelId: String,
    intent: String,
    idempotencyKey: String,
): PlaybackSessionDto = call {
    http.post("playback-sessions") { json(OpenPlaybackDto(channelId, intent, idempotencyKey)) }.decode()
}

internal suspend fun SofaKingApi.session(id: String): PlaybackSessionDto = call {
    http.get(sessionPath(id)).decode()
}

/** Renews the lease; [intent] changes preview to watch (or back) without replacing the stream. */
internal suspend fun SofaKingApi.updateSession(id: String, intent: String?): PlaybackSessionDto = call {
    http.patch(sessionPath(id)) { json(UpdatePlaybackDto(intent)) }.decode()
}

/**
 * Whether the session's player shows an ad, for an engine that cannot read the playlist's marks; [aheadMs] is
 * what it loaded past the picture.
 */
internal suspend fun SofaKingApi.adBreak(id: String, aheadMs: Long): AdBreakStateDto = call {
    http.get(sessionPath(id) + "/ad-break") { parameter("aheadMs", aheadMs) }.decode()
}

/** What TMDB says about the program titled [title], through the server; [movie] when the guide files it as one. */
internal suspend fun SofaKingApi.programFacts(title: String, movie: Boolean): ProgramFactsDto = call {
    http.get("program-facts") {
        parameter("title", title)
        parameter("movie", movie)
    }.decode()
}

internal suspend fun SofaKingApi.releaseSession(id: String) = call {
    http.delete(sessionPath(id)).checkSuccess()
}
