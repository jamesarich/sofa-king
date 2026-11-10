package dev.jamesrich.sofaking.contract

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackSessionLeadTest {
    private val client = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val descriptor = PlaybackDescriptor("hls", "application/vnd.apple.mpegurl", "/media/sessions/s/1/t.m3u8", 1)

    @Test fun `a session's lead reads back as sent`() {
        val sent = PlaybackSession("s", "c", "watch", descriptor = descriptor, liveLeadMs = 7_000)
        assertEquals(7_000L, client.decodeFromString<PlaybackSession>(Json.encodeToString(sent)).liveLeadMs)
    }

    @Test fun `a session from a server that sends no lead has none`() {
        val older = """{"id":"s","channelId":"c","intent":"watch","descriptor":{"protocol":"hls","mimeType":"m",""" +
            """"url":"/u","epoch":1}}"""
        assertNull(client.decodeFromString<PlaybackSession>(older).liveLeadMs)
    }
}
