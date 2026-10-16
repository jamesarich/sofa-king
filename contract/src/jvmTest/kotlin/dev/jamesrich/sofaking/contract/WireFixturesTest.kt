package dev.jamesrich.sofaking.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * The fixtures under `wire/` are real server responses, frozen per API version; the server's tests check that it
 * still sends every field in them. These tests check that the types still read them with a client's settings.
 */
class WireFixturesTest {
    private val client = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/wire/$name")) { "missing fixture $name" }.readText()

    @Test
    fun `every v1 fixture decodes with a client's settings`() {
        assertEquals(true, client.decodeFromString<Bootstrap>(fixture("v1/bootstrap.json")).capabilities.playback)
        assertEquals("available", client.decodeFromString<Catalog>(fixture("v1/catalog.json")).channels.single().guideStatus)
        assertEquals("Morning", client.decodeFromString<GuideWindow>(fixture("v1/guide-window.json")).channels.single().programmes.single().title)
        assertEquals("hls", client.decodeFromString<PlaybackSession>(fixture("v1/playback-session.json")).descriptor.protocol)
        assertEquals(Wire.ErrorCode.AUTH_REQUIRED, client.decodeFromString<ApiError>(fixture("v1/api-error.json")).code)
        assertEquals(64, client.decodeFromString<PairingResult>(fixture("v1/pairing-result.json")).apiToken.length)
        assertEquals("Living room TV", client.decodeFromString<ViewerPreferences>(fixture("v1/viewer-preferences.json")).name)
    }

    @Test
    fun `a response without the fields no client reads still decodes`() {
        val bootstrap = client.decodeFromString<Bootstrap>("""{"serverId":"s","serverTime":"t"}""")
        assertEquals(1, bootstrap.apiVersion)
        assertEquals(Capabilities(), bootstrap.capabilities)
        val catalog = client.decodeFromString<Catalog>("""{"channels":[{"id":"c","displayNumber":"2","name":"PBS"}]}""")
        assertEquals(Wire.GuideStatus.AVAILABLE, catalog.channels.single().guideStatus)
        val window = client.decodeFromString<GuideWindow>(
            """{"revision":"r","channels":[{"channelId":"c","programmes":[{"startsAt":"a","endsAt":"b","title":"News"}]}]}""",
        )
        assertEquals("News", window.channels.single().programmes.single().title)
        val session = client.decodeFromString<PlaybackSession>(
            """{"id":"s","channelId":"c","intent":"watch","descriptor":{"protocol":"hls","mimeType":"m","url":"/u","epoch":1}}""",
        )
        assertEquals("", session.expiresAt)
        assertEquals(false, client.decodeFromString<ApiError>("""{"code":"X","message":"m"}""").retryable)
        assertEquals(false, client.decodeFromString<Bootstrap>("""{"serverId":"s","serverTime":"t","capabilities":{"catalog":true}}""").capabilities.viewers)
        assertEquals("", client.decodeFromString<PairingResult>("""{"apiToken":"t"}""").serverId)
        assertEquals(ViewerPreferences(), client.decodeFromString<ViewerPreferences>("{}"))
    }

    @Test
    fun `a pairing claim carries the code and who claims it`() {
        val body = Json.encodeToString(PairingClaim.serializer(), PairingClaim("123456", "client-1", "Den", "android-tv"))
        assertEquals("""{"code":"123456","clientId":"client-1","name":"Den","platform":"android-tv"}""", body)
    }

    @Test
    fun `a client always sends the intent it opens a session with`() {
        val body = Json.encodeToString(OpenPlayback.serializer(), OpenPlayback("c", Wire.Intent.WATCH, "k"))
        assertEquals("""{"channelId":"c","intent":"watch","idempotencyKey":"k"}""", body)
    }
}
