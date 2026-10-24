package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.PolicyNames
import dev.jamesrich.sofaking.backend.api.StationOverride
import dev.jamesrich.sofaking.contract.Channel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the builder joins candidates into stations: leaders, pins, sources, guides and keys. */
class StationBuilderTest : StationBuilderCase() {
    @Test fun `the same inputs in any order give the same lineup`() {
        val first = build(policy, catalog)
        repeat(20) { seed ->
            val shuffled = catalog.shuffled(Random(seed))
            val reordered = policy.copy(
                stations = policy.stations.entries.shuffled(Random(seed)).associate {
                    it.key to
                        it.value
                },
            )
            val again = build(reordered, shuffled)
            assertEquals(first.stations, again.stations)
            assertEquals(first.findings, again.findings)
            assertEquals(first.candidates, again.candidates)
            assertEquals(first.availability, again.availability)
            assertEquals(first.assigned, again.assigned)
        }
    }

    @Test fun `the earliest provider entry leads and its sources come first`() {
        val result = build(policy, catalog)
        val bob = result.station("bob ross channel")
        assertEquals("Bob Ross Channel", bob.name, "whale is the first entry, so it leads")
        assertEquals("https://logo.test/w1.png", bob.logo)
        assertEquals(listOf("w1-o1", "s1-o1"), bob.offeringIds)
        assertEquals(stationId("bob ross channel"), bob.id)
        val bbc = result.station("bbc news")
        assertEquals(
            listOf("w2-o1", "s2-o1", "s2-o2", "v1-o1"),
            bbc.offeringIds,
            "HD and plain spellings meet, in entry order",
        )
        assertEquals("Headlines", bbc.group, "the group map reads the leading candidate's raw group")
        assertEquals("Entertainment", bob.group, "an unmapped raw group takes the default")
        assertEquals("Sports", result.station("tennis channel").group)
        assertNotEquals(result.station("tennis channel").id, result.station("tennis tv").id)

        val preferred = build(
            policy.copy(
                stations =
                policy.stations + (
                    "bbc news" to StationOverride(
                        prefer = listOf("vizio"),
                        exclude = listOf("samsung"),
                    )
                    ),
            ),
            catalog,
        )
        val reordered = preferred.station("bbc news")
        assertEquals(
            listOf("v1-o1", "w2-o1"),
            reordered.offeringIds,
            "prefer orders the unpinned sources and exclude drops a provider",
        )
        assertEquals("BBC News HD", reordered.name, "prefer does not change the leading candidate")
        assertTrue(preferred.candidates.single { it.channelId == "s2" }.reason.contains("excluded"))
    }

    @Test fun `a pin claims its channel whatever its name, and only for its station`() {
        val result = build(policy, catalog)
        val nfl = result.station("nfl network")
        assertEquals(listOf("m1-o1"), nfl.offeringIds)
        assertEquals("245", nfl.number)
        assertEquals("Sports", nfl.group)
        assertTrue(nfl.required)
        assertEquals("NFL Net Feed 3", nfl.name, "the pinned channel leads when nothing else joins")
        assertTrue(result.stations.none { it.key == "nfl net feed 3" })
        val decision = result.candidates.single { it.channelId == "m1" }
        assertEquals("claimed", decision.outcome)
        assertEquals("nfl network", decision.station)
        assertEquals("nfl net feed 3", decision.key)
        assertEquals(
            "no provider entry admits it",
            result.candidates.single {
                it.channelId == "m2"
            }.reason,
            "an unlisted provider contributes only through pins",
        )

        val native = build(
            policy,
            catalog + candidate("whale", "w9", "NFL Network", "50") + candidate("whale", "w8", "NFL Net Feed 3", "51"),
        )
        val joined = native.station("nfl network")
        assertEquals(listOf("m1-o1", "w9-o1"), joined.offeringIds, "pinned sources come first, then name-keyed ones")
        assertEquals("NFL Network", joined.name, "a provider entry outranks an unlisted provider for the lead")
        assertEquals(
            listOf("w8-o1"),
            native.station("nfl net feed 3").offeringIds,
            "an unpinned namesake of the pinned channel keys by its name",
        )
    }

    @Test fun `ghost channels join nothing`() {
        val result = build(policy, catalog)
        assertTrue(result.stations.none { it.key == "ghost channel" })
        assertEquals("no current offering", result.candidates.single { it.channelId == "v2" }.reason)
    }

    @Test fun `a required station whose pin is gone stays, disabled, and says so`() {
        val withoutPin = catalog.filterNot { it.channel.id == "m1" }
        val result = build(policy, withoutPin)
        val nfl = result.station("nfl network")
        assertFalse(nfl.enabled)
        assertEquals("no-source", nfl.disabledReason)
        assertEquals("NFL Network", nfl.name)
        assertTrue(
            "nfl network: required source missing: masq/tvg-id:NFLNetwork.us" in result.findings,
            "${result.findings}",
        )
        assertTrue("nfl network: required station has no live source" in result.findings)

        val retained = build(
            policy,
            withoutPin + candidate("masq", "m1", "NFL Net Feed 3", "900", tvg = "NFLNetwork.us", offerings = 0),
        )
        assertTrue(
            retained.findings.any {
                it.startsWith("nfl network: required source missing") &&
                    it.endsWith("retained channel m1 has no last known source left")
            },
        )
    }

    @Test
    fun `a required pin left with only a retained channel keeps its last sources while the feed has them`() {
        val first = build(policy, catalog)
        val remembered = first.availability.getValue("nfl network")
        assertEquals(mapOf("masq/tvg-id:NFLNetwork.us" to listOf("m1-o1")), remembered.pinOfferings)

        val ghost = candidate("masq", "m1", "NFL Net Feed 3", "900", tvg = "NFLNetwork.us", offerings = 0)
        // The provider moved the stream to a new channel under another tvg-id; its offering row survives.
        val moved =
            FeedCandidate(
                Channel("m7", "907", "NFL Moved", null, emptyList(), "NFLNetwork2.us"),
                "masq",
                listOf(Offering("m1-o1", "m7", "masq", "https://example.test/m1/1", "hls")),
            )
        val base = catalog.filterNot { it.channel.id == "m1" } + ghost
        val kept = build(policy, base + moved, previous = mapOf("nfl network" to remembered))
        val nfl = kept.station("nfl network")
        assertEquals(listOf("m1-o1"), nfl.offeringIds)
        assertTrue(nfl.enabled)
        assertTrue(
            kept.findings.any {
                it ==
                    "nfl network: required source missing: masq/tvg-id:NFLNetwork.us; keeping 1 last known sources " +
                    "of retained channel m1"
            },
            "${kept.findings}",
        )
        assertEquals(
            remembered.pinOfferings,
            kept.availability.getValue("nfl network").pinOfferings,
            "the snapshot carries to the next run",
        )

        val gone = build(policy, base, previous = mapOf("nfl network" to remembered))
        assertTrue(gone.station("nfl network").offeringIds.isEmpty())
        assertTrue(gone.findings.any { it.endsWith("retained channel m1 has no last known source left") })
    }

    @Test fun `the source cap keeps pins and the best sources and reports the rest`() {
        val many = (1..4).map { candidate("samsung", "c$it", "Cap Station", "${100 + it}") }
        val capped = policy.copy(maxSourcesPerStation = 2)
        val result = build(capped, many)
        assertEquals(listOf("c1-o1", "c2-o1"), result.station("cap station").offeringIds)
        assertTrue("cap station: 2 sources over the cap of 2 dropped" in result.findings, "${result.findings}")
        assertEquals(2, result.candidates.count { it.reason == "over the source cap of 2" })
    }

    @Test fun `enabled follows the override, else the leading entry`() {
        val result = build(policy, catalog + candidate("vizio", "v9", "Vizio Only", "30"))
        assertEquals("policy", result.station("vizio only").disabledReason)
        assertTrue(
            result.station("bbc news").enabled,
            "a disabled entry's source does not disable a station another entry leads",
        )
        val overridden = build(
            policy.copy(
                stations =
                policy.stations + ("vizio only" to StationOverride(enabled = true)),
            ),
            catalog + candidate("vizio", "v9", "Vizio Only", "30"),
        )
        assertTrue(overridden.station("vizio only").enabled)
        val off = build(
            policy.copy(
                stations =
                policy.stations + ("bob ross channel" to StationOverride(enabled = false)),
            ),
            catalog,
        )
        assertEquals("policy", off.station("bob ross channel").disabledReason)
    }

    @Test fun `the guide is the source provider's own, else one no other provider owns`() {
        val guides = mapOf(
            "bob" to listOf("samsung", "whale"),
            "s-bob" to listOf("whale"),
            "bbc" to listOf("whale", "epg"),
            "s-bbc" to listOf("epg"),
        )
        val result = build(policy, catalog, listing = { guides[it].orEmpty() })
        assertEquals(GuideMapping("whale", "bob"), result.station("bob ross channel").guide)
        assertEquals(GuideMapping("whale", "bbc"), result.station("bbc news").guide)

        val foreign =
            build(policy, listOf(candidate("samsung", "s1", "The Bob Ross Channel", tvg = "s-bob")), listing = {
                guides[it].orEmpty()
            }, providers = setOf("whale", "samsung"))
        assertNull(foreign.station("bob ross channel").guide, "another provider's guide names a different channel")
        val independent =
            build(policy, listOf(candidate("samsung", "s2", "BBC News", tvg = "s-bbc")), listing = {
                guides[it].orEmpty()
            }, providers = setOf("whale", "samsung"))
        assertEquals(GuideMapping("epg", "s-bbc"), independent.station("bbc news").guide)
        val overridden =
            build(
                policy.copy(
                    stations = mapOf("bbc news" to StationOverride(guide = GuideMapping("custom", "x"))),
                ),
                catalog,
                listing = {
                    guides[it].orEmpty()
                },
            )
        assertEquals(GuideMapping("custom", "x"), overridden.station("bbc news").guide)
        assertEquals(emptyList(), overridden.station("bbc news").guideCandidates)
    }

    @Test fun `a station takes the first guide with programs ahead, and keeps the first when none has any`() {
        val guides = mapOf("bbc" to listOf("whale"), "s-bbc" to listOf("samsung"), "v-bbc" to listOf("epg"))
        val providers = setOf("whale", "samsung", "vizio")
        val all = listOf(GuideMapping("whale", "bbc"), GuideMapping("samsung", "s-bbc"), GuideMapping("epg", "v-bbc"))
        val empty =
            build(policy, catalog, listing = { guides[it].orEmpty() }, providers = providers, covered = { false })
        assertEquals(all, empty.station("bbc news").guideCandidates)
        assertEquals(GuideMapping("whale", "bbc"), empty.station("bbc news").guide)
        val fallback =
            build(policy, catalog, listing = { guides[it].orEmpty() }, providers = providers, covered = {
                it.guideId != "whale"
            })
        assertEquals(GuideMapping("samsung", "s-bbc"), fallback.station("bbc news").guide)
    }

    @Test fun `an alias joins two spellings into one station`() {
        val aliased = policy.copy(
            names = PolicyNames(version = 1, aliases = mapOf("espn 8" to "espn8")),
            stations = emptyMap(),
        )
        val result =
            build(aliased, listOf(candidate("whale", "e1", "ESPN 8", "8"), candidate("samsung", "e2", "ESPN8", "1008")))
        assertEquals(listOf("e1-o1", "e2-o1"), result.station("espn8").offeringIds)
        assertEquals(1, result.stations.size)
        assertTrue(result.suggestedAliases.isEmpty())

        val unaliased =
            build(
                aliased.copy(names = PolicyNames(version = 1)),
                listOf(candidate("whale", "e1", "ESPN 8", "8"), candidate("samsung", "e2", "ESPN8", "1008")),
            )
        assertEquals(listOf("espn 8: possible alias of espn8"), unaliased.suggestedAliases)
    }

    @Test fun `version 2 keys join spellings that differ only by spaces without an alias`() {
        val v2 = policy.copy(names = PolicyNames(version = 2), stations = emptyMap())
        val result = build(
            v2,
            listOf(
                candidate("whale", "e1", "ESPN 8", "8"),
                candidate("samsung", "e2", "ESPN8", "1008"),
                candidate("whale", "n1", "NFL Network", "9"),
                candidate("samsung", "n2", "NFL Channel", "1009"),
            ),
        )
        assertEquals(listOf("e1-o1", "e2-o1"), result.station("espn8").offeringIds)
        assertEquals(setOf("espn8", "nflnetwork", "nflchannel"), result.stations.map { it.key }.toSet())
        assertTrue(result.suggestedAliases.isEmpty())
    }
}
