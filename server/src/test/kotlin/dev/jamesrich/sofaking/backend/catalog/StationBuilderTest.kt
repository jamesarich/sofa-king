package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.test.*

class StationBuilderTest {
    private val now = Instant.parse("2026-10-11T12:00:00Z")

    private fun candidate(
        provider: String, id: String, name: String, number: String = "1", tvg: String? = null, group: String? = null,
        logo: String? = null, offerings: Int = 1,
    ) = FeedCandidate(
        Channel(id, number, name, logo, listOfNotNull(group), tvg), provider,
        if (offerings == 0) emptyList() else (1..offerings).map { Offering("$id-o$it", id, provider, "https://example.test/$id/$it", "hls") },
    )

    private fun build(
        policy: LineupPolicy,
        candidates: List<FeedCandidate>,
        health: Map<String, HealthState>? = null,
        previous: Map<String, StationAvailability> = emptyMap(),
        listing: (String) -> List<String> = { emptyList() },
        providers: Set<String> = candidates.mapTo(HashSet()) { it.providerId },
    ): BuildResult = StationBuilder.build(
        BuildInput(
            policy, candidates, listing, providers,
            health ?: candidates.flatMap { it.offerings }.associate { it.id to HealthState(HealthStatus.HEALTHY) }, previous, now,
        ),
    )

    private fun BuildResult.station(key: String): PolicyStation = stations.single { it.key == key }

    private val catalog = listOf(
        candidate("whale", "w1", "Bob Ross Channel", "15", tvg = "bob", group = "Lifestyle", logo = "https://logo.test/w1.png"),
        candidate("whale", "w2", "BBC News HD", "2", tvg = "bbc", group = "News"),
        candidate("samsung", "s1", "The Bob Ross Channel", "1001", tvg = "s-bob", group = "Arts"),
        candidate("samsung", "s2", "BBC News", "1002", tvg = "s-bbc", offerings = 2),
        candidate("samsung", "s3", "Tennis Channel", "1003"),
        candidate("samsung", "s4", "Tennis TV", "1004"),
        candidate("vizio", "v1", "BBC News", "20", tvg = "v-bbc"),
        candidate("vizio", "v2", "Ghost Channel", "21", offerings = 0),
        candidate("masq", "m1", "NFL Net Feed 3", "900", tvg = "NFLNetwork.us"),
        candidate("masq", "m2", "Some Other Thing", "901", tvg = "other"),
    )

    private val policy = LineupPolicy(
        names = PolicyNames(version = 1),
        providers = listOf(PolicyProvider("whale"), PolicyProvider("samsung"), PolicyProvider("vizio", enabled = false)),
        groups = PolicyGroups(map = mapOf("news" to "Headlines"), patterns = listOf(GroupPattern(name = "tennis", group = "Sports")), default = "Entertainment"),
        numbering = PolicyNumbering(floor = 1000),
        stations = mapOf(
            "nfl network" to StationOverride(number = "245", group = "Sports", required = true, sources = listOf(SourcePin("masq", "NFLNetwork.us", "NFL Network"))),
        ),
    )

    @Test fun `the same inputs in any order give the same lineup`() {
        val first = build(policy, catalog)
        repeat(20) { seed ->
            val shuffled = catalog.shuffled(Random(seed))
            val reordered = policy.copy(stations = policy.stations.entries.shuffled(Random(seed)).associate { it.key to it.value })
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
        assertEquals(listOf("w2-o1", "s2-o1", "s2-o2", "v1-o1"), bbc.offeringIds, "HD and plain spellings meet, in entry order")
        assertEquals("Headlines", bbc.group, "the group map reads the leading candidate's raw group")
        assertEquals("Entertainment", bob.group, "an unmapped raw group takes the default")
        assertEquals("Sports", result.station("tennis channel").group)
        assertNotEquals(result.station("tennis channel").id, result.station("tennis tv").id)

        val preferred = build(policy.copy(stations = policy.stations + ("bbc news" to StationOverride(prefer = listOf("vizio"), exclude = listOf("samsung")))), catalog)
        val reordered = preferred.station("bbc news")
        assertEquals(listOf("v1-o1", "w2-o1"), reordered.offeringIds, "prefer orders the unpinned sources and exclude drops a provider")
        assertEquals("BBC News HD", reordered.name, "prefer does not change the leading candidate")
        assertTrue(preferred.candidates.single { it.channelId == "s2" }.reason.contains("excluded"))
    }

    @Test fun `a pin claims its channel whatever its name, and only for its station`() {
        val result = build(policy, catalog)
        val nfl = result.station("nfl network")
        assertEquals(listOf("m1-o1"), nfl.offeringIds)
        assertEquals("245", nfl.number); assertEquals("Sports", nfl.group); assertTrue(nfl.required)
        assertEquals("NFL Net Feed 3", nfl.name, "the pinned channel leads when nothing else joins")
        assertTrue(result.stations.none { it.key == "nfl net feed 3" })
        val decision = result.candidates.single { it.channelId == "m1" }
        assertEquals("claimed", decision.outcome); assertEquals("nfl network", decision.station); assertEquals("nfl net feed 3", decision.key)
        assertEquals("no provider entry admits it", result.candidates.single { it.channelId == "m2" }.reason, "an unlisted provider contributes only through pins")

        val native = build(policy, catalog + candidate("whale", "w9", "NFL Network", "50") + candidate("whale", "w8", "NFL Net Feed 3", "51"))
        val joined = native.station("nfl network")
        assertEquals(listOf("m1-o1", "w9-o1"), joined.offeringIds, "pinned sources come first, then name-keyed ones")
        assertEquals("NFL Network", joined.name, "a provider entry outranks an unlisted provider for the lead")
        assertEquals(listOf("w8-o1"), native.station("nfl net feed 3").offeringIds, "an unpinned namesake of the pinned channel keys by its name")
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
        assertFalse(nfl.enabled); assertEquals("no-source", nfl.disabledReason); assertEquals("NFL Network", nfl.name)
        assertTrue("nfl network: required source missing: masq/tvg-id:NFLNetwork.us" in result.findings, "${result.findings}")
        assertTrue("nfl network: required station has no live source" in result.findings)

        val retained = build(policy, withoutPin + candidate("masq", "m1", "NFL Net Feed 3", "900", tvg = "NFLNetwork.us", offerings = 0))
        assertTrue(retained.findings.any { it.startsWith("nfl network: required source missing") && it.endsWith("retained channel m1 has no last known source left") })
    }

    @Test fun `a required pin left with only a retained channel keeps the sources it last resolved to while the feed has them`() {
        val first = build(policy, catalog)
        val remembered = first.availability.getValue("nfl network")
        assertEquals(mapOf("masq/tvg-id:NFLNetwork.us" to listOf("m1-o1")), remembered.pinOfferings)

        val ghost = candidate("masq", "m1", "NFL Net Feed 3", "900", tvg = "NFLNetwork.us", offerings = 0)
        // The provider moved the stream to a new channel under another tvg-id; its offering row survives.
        val moved = FeedCandidate(Channel("m7", "907", "NFL Moved", null, emptyList(), "NFLNetwork2.us"), "masq", listOf(Offering("m1-o1", "m7", "masq", "https://example.test/m1/1", "hls")))
        val base = catalog.filterNot { it.channel.id == "m1" } + ghost
        val kept = build(policy, base + moved, previous = mapOf("nfl network" to remembered))
        val nfl = kept.station("nfl network")
        assertEquals(listOf("m1-o1"), nfl.offeringIds); assertTrue(nfl.enabled)
        assertTrue(kept.findings.any { it == "nfl network: required source missing: masq/tvg-id:NFLNetwork.us; keeping 1 last known sources of retained channel m1" }, "${kept.findings}")
        assertEquals(remembered.pinOfferings, kept.availability.getValue("nfl network").pinOfferings, "the snapshot carries to the next run")

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

    @Test fun `numbers come from overrides first, then provider numbers in priority order, then the floor`() {
        val numbered = LineupPolicy(
            names = PolicyNames(version = 1),
            providers = listOf(PolicyProvider("a"), PolicyProvider("b")),
            numbering = PolicyNumbering(floor = 1000),
            stations = mapOf("reserved" to StationOverride(number = "5"), "pinned high" to StationOverride(number = "1000")),
        )
        val result = build(numbered, listOf(
            candidate("a", "a1", "Reserved", "77"),
            candidate("a", "a2", "Wants Five", "5"),
            candidate("a", "a3", "Seven", "7"),
            candidate("b", "b1", "Also Seven", "0007"),
            candidate("b", "b2", "Pinned High", "3"),
            candidate("b", "b3", "No Number", "abc"),
        ))
        assertEquals("5", result.station("reserved").number)
        assertEquals("1000", result.station("pinned high").number)
        assertEquals(mapOf("wants five" to "1001", "seven" to "7", "also seven" to "1002", "no number" to "1003"), result.assigned)
        assertEquals(listOf("reserved", "seven", "pinned high", "wants five", "also seven", "no number"), result.stations.map { it.key })

        val kept = build(numbered.copy(numbering = PolicyNumbering(floor = 10, keepProviderNumbers = false)), listOf(candidate("a", "a3", "Seven", "7")))
        assertEquals("10", kept.station("seven").number)
    }

    @Test fun `enabled follows the override, else the leading entry`() {
        val result = build(policy, catalog + candidate("vizio", "v9", "Vizio Only", "30"))
        assertEquals("policy", result.station("vizio only").disabledReason)
        assertTrue(result.station("bbc news").enabled, "a disabled entry's source does not disable a station another entry leads")
        val overridden = build(policy.copy(stations = policy.stations + ("vizio only" to StationOverride(enabled = true))), catalog + candidate("vizio", "v9", "Vizio Only", "30"))
        assertTrue(overridden.station("vizio only").enabled)
        val off = build(policy.copy(stations = policy.stations + ("bob ross channel" to StationOverride(enabled = false))), catalog)
        assertEquals("policy", off.station("bob ross channel").disabledReason)
    }

    @Test fun `the guide is the source provider's own, else one no other provider owns`() {
        val guides = mapOf("bob" to listOf("samsung", "whale"), "s-bob" to listOf("whale"), "bbc" to listOf("whale", "epg"), "s-bbc" to listOf("epg"))
        val result = build(policy, catalog, listing = { guides[it].orEmpty() })
        assertEquals(GuideMapping("whale", "bob"), result.station("bob ross channel").guide)
        assertEquals(GuideMapping("whale", "bbc"), result.station("bbc news").guide)

        val foreign = build(policy, listOf(candidate("samsung", "s1", "The Bob Ross Channel", tvg = "s-bob")), listing = { guides[it].orEmpty() }, providers = setOf("whale", "samsung"))
        assertNull(foreign.station("bob ross channel").guide, "another provider's guide names a different channel")
        val independent = build(policy, listOf(candidate("samsung", "s2", "BBC News", tvg = "s-bbc")), listing = { guides[it].orEmpty() }, providers = setOf("whale", "samsung"))
        assertEquals(GuideMapping("epg", "s-bbc"), independent.station("bbc news").guide)
        val overridden = build(policy.copy(stations = mapOf("bbc news" to StationOverride(guide = GuideMapping("custom", "x")))), catalog, listing = { guides[it].orEmpty() })
        assertEquals(GuideMapping("custom", "x"), overridden.station("bbc news").guide)
    }

    @Test fun `availability turns a station off after disableAfterHours and back on when a source is healthy`() {
        val one = listOf(candidate("whale", "w1", "Bob Ross Channel", "15"))
        val broken = mapOf("w1-o1" to HealthState(HealthStatus.QUARANTINED))
        val fresh = build(policy, one, health = broken)
        assertTrue(fresh.station("bob ross channel").enabled)
        assertEquals(StationAvailability(now, false, lastNumber = "15", lastName = "Bob Ross Channel"), fresh.availability["bob ross channel"])

        val since = now.minus(Duration.ofHours(25))
        val off = build(policy, one, health = broken, previous = mapOf("bob ross channel" to StationAvailability(since)))
        assertEquals("unavailable", off.station("bob ross channel").disabledReason)
        assertEquals(since, off.availability.getValue("bob ross channel").unavailableSince)

        val eligibleOnly = mapOf("w1-o1" to HealthState(HealthStatus.UNVERIFIED))
        val stillOff = build(policy, one, health = eligibleOnly, previous = mapOf("bob ross channel" to StationAvailability(null, autoDisabled = true)))
        assertEquals("unavailable", stillOff.station("bob ross channel").disabledReason, "enableOn healthy waits for a healthy source")
        val eligibleOn = build(policy.copy(health = PolicyHealth(enableOn = "eligible")), one, health = eligibleOnly, previous = mapOf("bob ross channel" to StationAvailability(null, autoDisabled = true)))
        assertTrue(eligibleOn.station("bob ross channel").enabled)
        val back = build(policy, one, previous = mapOf("bob ross channel" to StationAvailability(null, autoDisabled = true)))
        assertTrue(back.station("bob ross channel").enabled)
    }

    @Test fun `stations whose candidates vanish stay disabled for keepMissingDays`() {
        val previous = mapOf(
            "recent" to StationAvailability(lastNumber = "44", lastName = "Recent", missingSince = now.minus(Duration.ofDays(2))),
            "old" to StationAvailability(lastNumber = "45", lastName = "Old", missingSince = now.minus(Duration.ofDays(8))),
            "just gone" to StationAvailability(lastNumber = "46", lastName = "Just Gone"),
        )
        val result = build(policy, catalog, previous = previous)
        val recent = result.station("recent")
        assertFalse(recent.enabled); assertEquals("missing", recent.disabledReason); assertEquals("44", recent.number); assertEquals("Recent", recent.name)
        assertTrue(result.stations.none { it.key == "old" })
        assertEquals(now, result.availability.getValue("just gone").missingSince)
        assertFalse("old" in result.availability)
    }

    @Test fun `an alias joins two spellings into one station`() {
        val aliased = policy.copy(names = PolicyNames(version = 1, aliases = mapOf("espn 8" to "espn8")), stations = emptyMap())
        val result = build(aliased, listOf(candidate("whale", "e1", "ESPN 8", "8"), candidate("samsung", "e2", "ESPN8", "1008")))
        assertEquals(listOf("e1-o1", "e2-o1"), result.station("espn8").offeringIds)
        assertEquals(1, result.stations.size)
        assertTrue(result.suggestedAliases.isEmpty())

        val unaliased = build(aliased.copy(names = PolicyNames(version = 1)), listOf(candidate("whale", "e1", "ESPN 8", "8"), candidate("samsung", "e2", "ESPN8", "1008")))
        assertEquals(listOf("espn 8: possible alias of espn8"), unaliased.suggestedAliases)
    }

    @Test fun `version 2 keys join spellings that differ only by spaces without an alias`() {
        val v2 = policy.copy(names = PolicyNames(version = 2), stations = emptyMap())
        val result = build(v2, listOf(
            candidate("whale", "e1", "ESPN 8", "8"), candidate("samsung", "e2", "ESPN8", "1008"),
            candidate("whale", "n1", "NFL Network", "9"), candidate("samsung", "n2", "NFL Channel", "1009"),
        ))
        assertEquals(listOf("e1-o1", "e2-o1"), result.station("espn8").offeringIds)
        assertEquals(setOf("espn8", "nflnetwork", "nflchannel"), result.stations.map { it.key }.toSet())
        assertTrue(result.suggestedAliases.isEmpty())
    }
}
