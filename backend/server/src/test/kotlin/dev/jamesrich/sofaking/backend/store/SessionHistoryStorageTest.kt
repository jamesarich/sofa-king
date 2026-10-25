package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.HistoryQuery
import dev.jamesrich.sofaking.backend.api.SessionStart
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.events.ServerEvents
import dev.jamesrich.sofaking.backend.playback.HistoryKind
import dev.jamesrich.sofaking.backend.playback.HistoryReason
import dev.jamesrich.sofaking.backend.playback.SessionHistory
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionHistoryStorageTest {
    private val start = Instant.parse("2026-10-01T20:00:00Z")

    private fun session(id: String, kind: String, at: Instant, clientId: String? = null) = SessionStart(
        id,
        kind,
        clientId,
        "192.0.2.7",
        channelId = if (kind == HistoryKind.LIVE) "news" else null,
        itemId = if (kind == HistoryKind.VOD) "item-1" else null,
        mode = if (kind == HistoryKind.LIVE) "watch" else "direct",
        startedAt = at.toString(),
    )

    @Test fun `history filters by time, kind and client and forgets rows past retention`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-history-test-")
        try {
            openStore(dir).use { store ->
                val clock = MutableClock(start)
                val history = SessionHistory(store, ServerEvents(clock), clock, Duration.ofDays(30))
                clock.now = start.plusMillis(1_500)
                history.ended(session("old", HistoryKind.LIVE, start, "tv-1"), HistoryReason.RELEASED, bytes = 9)
                clock.now = start.plus(Duration.ofDays(20))
                history.ended(session("movie", HistoryKind.VOD, clock.now.minusSeconds(60)), HistoryReason.EXPIRED)
                clock.now = start.plus(Duration.ofDays(25))
                history.ended(
                    session("new", HistoryKind.LIVE, clock.now.minusSeconds(5), "tv-1"),
                    HistoryReason.EVICTED,
                )

                val all = history.list(HistoryQuery())
                assertEquals(listOf("new", "movie", "old"), all.map { it.id }, "newest end first")
                val old = all.last()
                assertEquals("2026-10-01T20:00:01Z", old.endedAt, "whole seconds")
                assertEquals(9L, old.bytes)
                assertEquals(
                    listOf("new", "movie"),
                    history.list(HistoryQuery(since = start.plus(Duration.ofDays(10)))).map { it.id },
                )
                assertEquals(listOf("movie"), history.list(HistoryQuery(kind = HistoryKind.VOD)).map { it.id })
                assertEquals(listOf("new", "old"), history.list(HistoryQuery(client = "tv-1")).map { it.id })
                assertEquals(
                    listOf("new", "movie", "old"),
                    history.list(HistoryQuery(client = "192.0.2.7")).map { it.id },
                )
                assertEquals(listOf("new"), history.list(HistoryQuery(limit = 1)).map { it.id })

                clock.now = start.plus(Duration.ofDays(31))
                assertEquals(1, history.prune())
                assertEquals(listOf("new", "movie"), history.list(HistoryQuery()).map { it.id })
                clock.now = start.plus(Duration.ofDays(56))
                assertEquals(2, history.prune())
                assertEquals(0, history.list(HistoryQuery()).size)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
