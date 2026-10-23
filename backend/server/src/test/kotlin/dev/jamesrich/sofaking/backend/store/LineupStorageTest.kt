package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgramme
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineupStorageTest {
    private val t = Instant.parse("2026-10-10T00:00:00Z")

    @Test fun `lineup order offerings and mapping survive a reopen`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-lineup-store-")
        try {
            val entries = listOf(
                LineupEntryRecord(
                    "b0f6d2f4-0000-4000-8000-000000000002",
                    "7",
                    "Second",
                    null,
                    null,
                    false,
                    null,
                    listOf("o3"),
                ),
                LineupEntryRecord(
                    "b0f6d2f4-0000-4000-8000-000000000001",
                    "5",
                    "First",
                    "News",
                    "https://img.test/1.png",
                    true,
                    GuideMapping("g", "x"),
                    listOf("o2", "o1"),
                ),
                LineupEntryRecord(
                    "b0f6d2f4-0000-4000-8000-000000000003",
                    "9",
                    "Third",
                    enabled = false,
                    createdBy = "policy",
                    autoDisabledAt = t,
                    autoDisabledReason = "unavailable",
                ),
            )
            openStore(dir).use { it.saveLineup(entries, t) }
            openStore(dir).use { store ->
                assertEquals(entries, store.lineup())
                store.saveLineup(entries.drop(1), t)
                assertEquals(entries.drop(1), store.lineup())
            }
            openStore(dir).use { assertEquals(entries.drop(1), it.lineup()) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `guide programmes beyond the SQLite variable limit are saved queried and replaced`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-guide-store-")
        try {
            openStore(dir).use { store ->
                val programmes = (0 until 12_000).map { i ->
                    GuideProgramme(
                        "c${i % 3}",
                        t.plusSeconds(i * 1800L),
                        t.plusSeconds(i * 1800L + 1800),
                        "Show $i",
                        if (i % 2 == 0) {
                            "Desc $i"
                        } else {
                            null
                        },
                    )
                }
                store.saveGuide("g", t, listOf(GuideChannel("c0", "Zero"), GuideChannel("c1", "One")), programmes)
                val guide = store.guides().getValue("g")
                assertEquals(12_000, guide.programmes)
                assertEquals(setOf("c0", "c1"), guide.channels.map { it.id }.toSet())
                assertEquals(t.plusSeconds(11_999 * 1800L + 1800), guide.programmeEnds.getValue("c2"))
                val window = store.programmes("g", listOf("c0", "c1"), t.plusSeconds(1800), t.plusSeconds(7200))
                assertEquals(
                    listOf("Show 3"),
                    window.getValue("c0").map {
                        it.title
                    },
                    "Show 0 ends exactly at the window start and is excluded",
                )
                assertEquals(listOf("Show 1"), window.getValue("c1").map { it.title })
                assertEquals(
                    1,
                    window.getValue("c1").first().startsAt.compareTo(t),
                    "times round-trip as epoch seconds",
                )
                assertTrue(
                    store.programmes(
                        "g",
                        listOf("c2"),
                        t.plusSeconds(3600),
                        t.plusSeconds(7200),
                    ).getValue("c2").isNotEmpty(),
                )
                store.saveGuide("g", t.plusSeconds(60), listOf(GuideChannel("c0", "Zero")), programmes.take(1))
                assertEquals(1, store.guides().getValue("g").programmes)
                assertTrue(store.programmes("g", listOf("c1"), t, t.plusSeconds(86_400)).isEmpty())
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an import shows readers the old guide until it completes, and the next import clears a failed one's rows`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-guide-generation-")
            try {
                openStore(dir).use { store ->
                    val old = (0 until 45_000).map { i ->
                        GuideProgramme("c${i % 5}", t.plusSeconds(i * 60L), t.plusSeconds(i * 60L + 60), "Old $i", null)
                    }
                    store.saveGuide("g", t, listOf(GuideChannel("c0", "Zero")), old)
                    assertEquals(
                        45_000,
                        store.guides().getValue("g").programmes,
                        "an import over several transactions keeps every row",
                    )
                    // What an import that died after writing part of the next generation leaves behind.
                    store.query {
                        exec(
                            "INSERT INTO guide_channel(guide_id, generation, channel_id, display_name) VALUES ('g', " +
                                "2, 'c9', 'Partial')",
                        )
                        exec(
                            "INSERT INTO guide_programme(guide_id, generation, channel_id, starts_at, ends_at, " +
                                "title) VALUES ('g', 2, 'c0', ${t.epochSecond}, ${t.epochSecond + 60}, 'Partial')",
                        )
                    }
                    assertEquals(
                        listOf("Old 0"),
                        store.programmes("g", listOf("c0"), t, t.plusSeconds(60)).getValue("c0").map {
                            it.title
                        },
                        "rows of an unfinished import stay hidden",
                    )
                    assertEquals(listOf("c0"), store.guides().getValue("g").channels.map { it.id })
                    val fresh = listOf(GuideProgramme("c0", t, t.plusSeconds(60), "New", null))
                    store.saveGuide("g", t.plusSeconds(60), listOf(GuideChannel("c0", "Zero")), fresh)
                    assertEquals(
                        listOf("New"),
                        store.programmes("g", listOf("c0"), t, t.plusSeconds(60)).getValue("c0").map {
                            it.title
                        },
                    )
                    assertEquals(1, store.guides().getValue("g").programmes)
                    val stored = store.query {
                        exec("SELECT COUNT(*) FROM guide_programme") {
                            it.next()
                            it.getInt(1)
                        }
                    }
                    assertEquals(1, stored, "the old generation and the failed import's rows are deleted")
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
}
