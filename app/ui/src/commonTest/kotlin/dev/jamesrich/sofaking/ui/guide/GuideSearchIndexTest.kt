package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.Program
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class GuideSearchIndexTest {
    private val now = Instant.parse("2026-10-12T18:00:00Z")
    private val names = listOf("Télé Québec", "Local News", "Cinéma", "Sports 5", "Jeopardy Channel", "Café TV")
    private val titles = listOf("Jeopardy!", "Evening News", "Crème Brûlée", "Le Cafe", "Match", "Çà et là")

    private val channels = (0 until 60).map { i ->
        Channel("c$i", i + 1.0 + (i % 3) / 2.0, "${names[i % names.size]} $i", null, listOf("All"))
    }
    private val guide: Map<String, List<Program>> = channels.associate { channel ->
        val seed = channel.guideId.drop(1).toInt()
        channel.guideId to (-2..20).map { h ->
            Program(
                channel.guideId,
                now + h.hours,
                now + (h + 1).hours,
                titles[(seed + h + 2) % titles.size],
                null,
                subTitle = if (h % 4 == 0) titles[(seed + h) % titles.size] else null,
            )
        }
    }
    private val lineup = Lineup(channels, guide)
    private val queries =
        listOf("", "  ", "j", "jeop", "TELE", "café", "cafe", "5", "1", "2.5", "brul", "news", "zzz", "ca")

    @Test
    fun `indexed search returns what the plain search returns`() {
        val index = GuideSearchIndex(channels, guide)
        for (query in queries) {
            val needle = GuideSearchIndex.needle(query)
            for (at in listOf(now, now + 30.minutes, now + 5.hours)) {
                val expected = channels.filter {
                    GuideSearch.matches(query, it.number, it.name, lineup.nowAndNext(it, at).first?.title)
                }
                assertEquals(expected, channels.filter { index.matches(needle, it, at) }, "matches for '$query'")
            }
            for (channel in channels) {
                val expected = GuideSearch.upcoming(query, guide.getValue(channel.guideId), now, now + 24.hours)
                assertEquals(expected, index.upcoming(needle, channel, now, now + 24.hours), "upcoming for '$query'")
            }
        }
    }

    @Test
    fun `typing more queries adds no normalization of the guide`() {
        var calls = 0
        val counting: (String) -> String = {
            calls++
            GuideSearch.normalize(it)
        }
        val index = GuideSearchIndex(channels, guide, counting)
        val built = calls
        repeat(10) { n ->
            val needle = GuideSearchIndex.needle("jeopardy".take(n + 1), counting)
            channels.forEach {
                index.matches(needle, it, now)
                index.upcoming(needle, it, now, now + 24.hours)
            }
        }
        assertEquals(built + 10, calls)
    }
}
