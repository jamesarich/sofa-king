package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.contract.AdDateRange
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.MediaShelf
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.Program
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class SofaKingWebTest {
    private val news = Channel("ch1", 4.0, "News", logo = null, groups = listOf("News", "Local"))

    private fun program(channel: String, startMinutes: Int, lengthMinutes: Int, title: String): Program {
        val now = Clock.System.now()
        return Program(
            channel,
            now + startMinutes.minutes,
            now + (startMinutes + lengthMinutes).minutes,
            title,
            description = null,
        )
    }

    @Test
    fun `a ready lineup crosses with its channels and the programs from now on`() {
        val past = program("ch1", -90, 30, "Over")
        val onNow = program("ch1", -10, 30, "On now")
        val later = program("ch1", 20, 30, "Later")
        val tooFar = program("ch1", 600, 30, "Tomorrow")
        val lineup = Lineup(listOf(news), mapOf("ch1" to listOf(past, onNow, later, tooFar)))

        val web = lineupToWeb(LineupState.Ready(lineup))

        assertEquals("ready", web.state)
        val channel = web.channels.single()
        assertEquals("ch1", channel.id)
        assertEquals(4.0, channel.number)
        assertEquals(listOf("News", "Local"), channel.groups.toList())
        assertEquals(listOf("On now", "Later"), channel.programs.map { it.title })
        assertEquals(onNow.start.toEpochMilliseconds().toDouble(), channel.programs.first().startMs)
    }

    @Test
    fun `a lineup that is not ready names its state and carries no channels`() {
        assertEquals("loading", lineupToWeb(LineupState.Loading).state)
        assertEquals("unconfigured", lineupToWeb(LineupState.Unconfigured).state)
        val failed = lineupToWeb(LineupState.Failed("no route"))
        assertEquals("failed", failed.state)
        assertEquals("no route", failed.message)
        assertEquals(0, failed.channels.size)
    }

    @Test
    fun `a lineup the server turned away crosses as signed out`() {
        assertTrue(lineupToWeb(LineupState.Failed("401", signedOut = true)).signedOut)
        assertFalse(lineupToWeb(LineupState.Failed("no route")).signedOut)
        assertFalse(lineupToWeb(LineupState.Loading).signedOut)
    }

    @Test
    fun `a library home the server turned away crosses as signed out`() {
        val toShelf: (MediaShelf) -> WebShelf = { error("no shelves in a failure") }
        val signedOut = homeToWeb(LibraryHomeState.Failed("401", signedOut = true), toShelf)
        assertEquals("failed", signedOut.state)
        assertTrue(signedOut.signedOut)
        assertFalse(homeToWeb(LibraryHomeState.Failed("no route"), toShelf).signedOut)
        assertFalse(homeToWeb(LibraryHomeState.Loading, toShelf).signedOut)
    }

    @Test
    fun `a live stream crosses as live and an item as its mode`() {
        val live =
            PlaybackStream("ch1", "s1", "http://h/live.m3u8", "application/vnd.apple.mpegurl", 2, PlaybackIntent.WATCH)
        assertEquals("live", live.toWeb().mode)
        assertEquals(2, live.toWeb().epoch)

        val item = live.copy(itemId = "i1", mode = "direct", offsetMs = 5_000, seekable = true, itemDurationMs = 60_000)
        val web = item.toWeb()
        assertEquals("direct", web.mode)
        assertEquals(5_000.0, web.offsetMs)
        assertEquals(60_000.0, web.itemDurationMs)
        assertTrue(web.seekable)
    }

    @Test
    fun `the pairing fragment names the server that serves the page`() {
        val pairing = assertNotNull(WebText().parsePairingFragment("#pair=123456", "http://192.168.1.3:9193"))
        assertEquals("123456", pairing.code)
        assertEquals("http://192.168.1.3:9193", pairing.serverUrl)
        assertNull(WebText().parsePairingFragment("#pair=12345", "http://192.168.1.3:9193"))
        assertNull(WebText().parsePairingFragment("", "http://192.168.1.3:9193"))
    }

    @Test
    fun `break marks answer for the wall clock inside an ad date range`() {
        val marks = WebBreakMarks()
        marks.addLine(AdDateRange.tag("ad1", "2026-11-04T12:00:00.000Z", durationMs = 90_000))
        marks.addLine("#EXT-X-PROGRAM-DATE-TIME:2026-11-04T12:00:00.000Z")
        val start = 1_793_793_600_000.0

        assertNull(marks.at(start - 1))
        assertEquals(60_000.0, marks.at(start + 30_000)?.remainingMs)
        assertNull(marks.at(start + 90_000))
    }

    @Test
    fun `a break with no length yet has no remaining time`() {
        val marks = WebBreakMarks()
        marks.addLine(AdDateRange.tag("ad2", "2026-11-04T12:00:00.000Z"))
        val start = 1_793_793_600_000.0

        assertNull(marks.at(start + 1_000)?.remainingMs)
        assertNotNull(marks.at(start + 1_000))
    }
}
