package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.contract.*
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
        seed: Set<String> = emptySet(),
        covered: (GuideMapping) -> Boolean = { true },
    ): BuildResult = StationBuilder.build(
        BuildInput(
            policy, candidates, listing, providers,
            health ?: candidates.flatMap { it.offerings }.associate { it.id to HealthState(HealthStatus.HEALTHY) }, previous, now, seed, covered,
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
        health = PolicyHealth(requireVerified = false),
        providers = listOf(PolicyProvider("whale"), PolicyProvider("samsung"), PolicyProvider("vizio", enabled = false)),
        groups = PolicyGroups(map = mapOf("news" to "Headlines"), patterns = listOf(GroupPattern(name = "tennis", group = "Sports")), default = "Entertainment", builtIn = false),
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

    @Test fun `a category the policy leaves unmapped takes the shipped map, unless groups builtIn is off`() {
        val catalog = listOf(candidate("whale", "w9", "Crime Scene", "9", group = "Crime TV"), candidate("whale", "w8", "Odd One", "8", group = "Some Odd Bucket"))
        val on = build(policy.copy(groups = policy.groups.copy(builtIn = true)), catalog)
        assertEquals("Crime", on.station("crime scene").group, "the shipped map names Crime TV")
        assertEquals(listOf("whale" to "some odd bucket"), on.unmappedCategories.map { it.provider to it.category }, "only a category neither map knows is reported")
        val off = build(policy, catalog)
        assertEquals("Entertainment", off.station("crime scene").group, "without the shipped map the default applies")
        assertEquals(setOf("crime tv", "some odd bucket"), off.unmappedCategories.mapTo(HashSet()) { it.category })
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

    @Test fun `with numbering groups a station numbers in its group's block, then from the floor`() {
        val grouped = LineupPolicy(
            names = PolicyNames(version = 1),
            providers = listOf(PolicyProvider("a")),
            groups = PolicyGroups(map = mapOf("news" to "News", "sports" to "Sports"), default = "Entertainment"),
            numbering = PolicyNumbering(floor = 1000, keepProviderNumbers = true, groups = mapOf("News" to 100, "Sports" to 200), groupSize = 2),
            stations = mapOf("sport b" to StationOverride(number = "200"), "news four" to StationOverride(number = "55")),
        )
        val result = build(grouped, listOf(
            candidate("a", "n1", "News One", "5", group = "News"),
            candidate("a", "n2", "News Two", "6", group = "News"),
            candidate("a", "n3", "News Three", "7", group = "News"),
            candidate("a", "n4", "News Four", "4", group = "News"),
            candidate("a", "s1", "Sport A", "8", group = "Sports"),
            candidate("a", "s2", "Sport B", "10", group = "Sports"),
            candidate("a", "e1", "Show", "9", group = "Fun"),
        ))
        assertEquals(mapOf("news one" to "100", "news two" to "101", "news three" to "1000", "sport a" to "201", "show" to "1001"), result.assigned)
        assertEquals("55", result.station("news four").number, "a number in the file never moves")
        assertEquals("200", result.station("sport b").number)
        assertEquals("News", result.station("news three").group)
        assertTrue(validatePolicy(grouped.copy(numbering = PolicyNumbering(groups = mapOf("" to 0), groupSize = 0)), setOf("a")).let { errors ->
            errors.any { "numbering.groupSize" in it } && errors.any { "start must be between" in it } && errors.any { "must be 1 to 128 characters" in it }
        })
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
        assertEquals(emptyList(), overridden.station("bbc news").guideCandidates)
    }

    @Test fun `a station takes the first guide with programs ahead, and keeps the first when none has any`() {
        val guides = mapOf("bbc" to listOf("whale"), "s-bbc" to listOf("samsung"), "v-bbc" to listOf("epg"))
        val providers = setOf("whale", "samsung", "vizio")
        val all = listOf(GuideMapping("whale", "bbc"), GuideMapping("samsung", "s-bbc"), GuideMapping("epg", "v-bbc"))
        val empty = build(policy, catalog, listing = { guides[it].orEmpty() }, providers = providers, covered = { false })
        assertEquals(all, empty.station("bbc news").guideCandidates)
        assertEquals(GuideMapping("whale", "bbc"), empty.station("bbc news").guide)
        val fallback = build(policy, catalog, listing = { guides[it].orEmpty() }, providers = providers, covered = { it.guideId != "whale" })
        assertEquals(GuideMapping("samsung", "s-bbc"), fallback.station("bbc news").guide)
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
        val eligibleOn = build(policy.copy(health = PolicyHealth(enableOn = "eligible", requireVerified = false)), one, health = eligibleOnly, previous = mapOf("bob ross channel" to StationAvailability(null, autoDisabled = true)))
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

    @Test fun `with requireVerified a station stays off until a source has qualified healthy once`() {
        val verifying = policy.copy(health = PolicyHealth(requireVerified = true))
        val two = listOf(candidate("whale", "w1", "Bob Ross Channel", "15", offerings = 2))
        val unverified = mapOf("w1-o1" to HealthState(HealthStatus.UNVERIFIED), "w1-o2" to HealthState(HealthStatus.SUSPECT))
        val waiting = build(verifying, two, health = unverified)
        assertEquals("unverified", waiting.station("bob ross channel").disabledReason)
        assertNull(waiting.availability.getValue("bob ross channel").verifiedAt)
        assertEquals(mapOf("w1-o1" to 0, "w1-o2" to 1), probeTiers(waiting), "the first source of an unverified station is probed first")

        val required = build(verifying.copy(stations = verifying.stations + ("bob ross channel" to StationOverride(required = true))), two, health = unverified)
        assertEquals("unverified", required.station("bob ross channel").disabledReason, "a required station waits too, in the lineup")

        val qualified = build(verifying, two, health = unverified + ("w1-o2" to HealthState(HealthStatus.HEALTHY)))
        assertTrue(qualified.station("bob ross channel").enabled)
        assertEquals(now, qualified.availability.getValue("bob ross channel").verifiedAt)
        assertEquals(mapOf("w1-o1" to 2, "w1-o2" to 2), probeTiers(qualified))
        val once = build(verifying, two, health = mapOf("w1-o1" to HealthState(HealthStatus.SUSPECT, lastQualifiedAt = now.minusSeconds(60))))
        assertTrue(once.station("bob ross channel").enabled, "a source that qualified before counts")

        // Verified once, the station follows the availability rules even while no source is healthy.
        val remembered = mapOf("bob ross channel" to StationAvailability(verifiedAt = now.minus(Duration.ofDays(3))))
        val later = build(verifying, two, health = mapOf("w1-o1" to HealthState(HealthStatus.QUARANTINED), "w1-o2" to HealthState(HealthStatus.QUARANTINED)), previous = remembered)
        assertTrue(later.station("bob ross channel").enabled)
        assertEquals(now.minus(Duration.ofDays(3)), later.availability.getValue("bob ross channel").verifiedAt)
        val stale = mapOf("bob ross channel" to StationAvailability(unavailableSince = now.minus(Duration.ofHours(25)), verifiedAt = now.minus(Duration.ofDays(3))))
        assertEquals("unavailable", build(verifying, two, health = mapOf("w1-o1" to HealthState(HealthStatus.QUARANTINED)), previous = stale).station("bob ross channel").disabledReason)

        val seeded = build(verifying, two, health = unverified, seed = setOf("w1-o2"))
        assertTrue(seeded.station("bob ross channel").enabled, "a source the replaced lineup carried counts as verified")
        assertEquals(now, seeded.availability.getValue("bob ross channel").verifiedAt)

        val off = build(verifying.copy(stations = mapOf("bob ross channel" to StationOverride(enabled = false))), two, health = unverified)
        assertEquals(mapOf("w1-o1" to 2, "w1-o2" to 2), probeTiers(off), "a station the policy turns off is not probed first")
    }

    @Test fun `a group block ends where the next group's block starts`() {
        val ranged = policy.copy(
            stations = emptyMap(), groups = PolicyGroups(default = "Local"),
            numbering = PolicyNumbering(floor = 2000, groups = mapOf("Local" to 1, "News" to 3), groupSize = 100),
        )
        val three = listOf(candidate("whale", "a", "Alpha", "1"), candidate("whale", "b", "Bravo", "2"), candidate("whale", "c", "Charlie", "3"))
        assertEquals(listOf("1", "2", "2000"), build(ranged, three).stations.map { it.number }.sorted(), "Local takes 1 and 2; 3 is News's, so the third falls to the floor")
    }

    @Test fun `an unverified station takes a floor number that is not assigned, then its block number once verified`() {
        val blocked = LineupPolicy(
            names = PolicyNames(version = 1), providers = listOf(PolicyProvider("a")), groups = PolicyGroups(default = "Local"),
            numbering = PolicyNumbering(floor = 1000, groups = mapOf("Local" to 1), groupSize = 10),
        )
        val alpha = listOf(candidate("a", "a1", "Alpha", "5"))
        val waiting = build(blocked, alpha, health = mapOf("a1-o1" to HealthState(HealthStatus.UNVERIFIED)))
        assertEquals("unverified", waiting.station("alpha").disabledReason)
        assertEquals("1000", waiting.station("alpha").number)
        assertEquals(emptyMap(), waiting.assigned)

        val verified = build(blocked, alpha, health = mapOf("a1-o1" to HealthState(HealthStatus.HEALTHY)))
        assertEquals("1", verified.station("alpha").number)
        assertEquals(mapOf("alpha" to "1"), verified.assigned)
    }

    @Test fun `a station gone past keepMissingDays leaves, and the number it held is free for the next station`() {
        val numbered = LineupPolicy(
            names = PolicyNames(version = 1), health = PolicyHealth(requireVerified = false, keepMissingDays = 7),
            providers = listOf(PolicyProvider("a")), numbering = PolicyNumbering(floor = 1000),
            stations = mapOf("gone" to StationOverride(number = "5")),
        )
        val previous = mapOf("gone" to StationAvailability(lastNumber = "5", lastName = "Gone", missingSince = now.minus(Duration.ofDays(8))))
        val leaving = build(numbered, listOf(candidate("a", "a1", "Five", "5")), previous = previous)
        assertEquals(setOf("gone"), leaving.reclaimed)
        assertEquals("1000", leaving.station("five").number, "the file still holds 5 during the run that reclaims it")
        assertEquals(mapOf("five" to "1000"), leaving.assigned)

        val next = build(numbered.copy(stations = emptyMap()), listOf(candidate("a", "a1", "Five", "5")))
        assertEquals("5", next.station("five").number, "once the file no longer holds 5, the next station takes it")
        assertEquals(mapOf("five" to "5"), next.assigned)
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

    private val unmappedPolicy = LineupPolicy(
        names = PolicyNames(version = 1),
        health = PolicyHealth(requireVerified = false),
        providers = listOf(PolicyProvider("whale"), PolicyProvider("samsung")),
        groups = PolicyGroups(map = mapOf("news" to "Headlines"), patterns = listOf(GroupPattern(name = "tennis", group = "Sports")), default = "Entertainment", builtIn = false),
    )

    @Test fun `a raw category nothing maps is counted per provider, and mapped or pattern-matched ones are not`() {
        val result = build(unmappedPolicy, listOf(
            candidate("whale", "u1", "Alpha One", "1", group = "Lifestyle"),
            candidate("whale", "u2", "Bravo", "2", group = "Lifestyle"),
            candidate("samsung", "u3", "Charlie", "3", group = "Arts"),
            candidate("whale", "u4", "Delta News", "4", group = "News"),
            candidate("whale", "u5", "Tennis Live", "5", group = "Hobbies"),
        ))
        assertEquals(
            listOf(PolicyUnmappedCategory("whale", "lifestyle", 2, "Alpha One"), PolicyUnmappedCategory("samsung", "arts", 1, "Charlie")),
            result.unmappedCategories,
        )
        assertTrue("groups: 2 categories have no mapping (3 stations); see unmappedCategories" in result.findings)
    }

    @Test fun `a station with a group override is not counted as unmapped`() {
        val result = build(
            unmappedPolicy.copy(stations = mapOf("charlie" to StationOverride(group = "Arts"))),
            listOf(candidate("samsung", "u3", "Charlie", "3", group = "Arts")),
        )
        assertEquals(emptyList(), result.unmappedCategories)
        assertTrue(result.findings.none { it.startsWith("groups:") }, "no summary finding when nothing is unmapped")
    }

    private val votingPolicy = LineupPolicy(
        names = PolicyNames(version = 2),
        health = PolicyHealth(requireVerified = false),
        providers = listOf(PolicyProvider("whale"), PolicyProvider("samsung"), PolicyProvider("vizio"), PolicyProvider("lg")),
        groups = PolicyGroups(
            map = mapOf(
                "movies and series" to "Movies", "reality" to "Lifestyle", "daytime" to "Lifestyle", "comedy" to "Comedy",
                "tv" to "Entertainment", "news" to "News", "latino" to "En Español", "vizio:sitcoms" to "Comedy", "sitcoms" to "Entertainment",
            ),
            weak = listOf("movies and series", "tv", "latino"),
            patterns = listOf(GroupPattern(name = "\\bnoticias\\b", group = "News"), GroupPattern(rawGroup = "^game", group = "Game Shows")),
            default = "Entertainment",
        ),
    )

    @Test fun `the group is the one most of a station's providers vote for, not the leading channel's`() {
        val result = build(votingPolicy, listOf(
            candidate("whale", "w1", "Nosey", "1", group = "Movies and Series"),
            candidate("samsung", "s1", "Nosey", "2", group = "Reality"),
            candidate("vizio", "v1", "Nosey", "3", group = "Daytime"),
            candidate("lg", "l1", "Nosey", "4", group = "Game Time"),
        ))
        assertEquals("Lifestyle", result.station("nosey").group, "two Lifestyle votes beat one Game Shows vote, and the weak lead abstains")
    }

    @Test fun `a provider votes once per group, and a tie goes to the earliest vote`() {
        val result = build(votingPolicy, listOf(
            candidate("samsung", "s1", "Conners", "1", group = "Reality"),
            candidate("samsung", "s2", "Conners", "2", group = "Reality"),
            candidate("vizio", "v1", "Conners", "3", group = "Comedy"),
        ))
        assertEquals("Lifestyle", result.station("conners").group, "two samsung channels are one vote, and samsung comes first")
    }

    @Test fun `weak categories vote only when nothing else does`() {
        val result = build(votingPolicy, listOf(
            candidate("whale", "w1", "Drama Box", "1", group = "Movies and Series"),
            candidate("vizio", "v1", "Drama Box", "2", group = "TV"),
            candidate("lg", "l1", "Drama Box", "3", group = "Movies and Series"),
            candidate("whale", "w2", "Plain", "4", group = "Movies and Series"),
            candidate("vizio", "v2", "Plain", "5", group = "News"),
            candidate("samsung", "s1", "Nothing Known", "6", group = "Latino"),
        ))
        assertEquals("Movies", result.station("dramabox").group, "only weak votes: their plurality decides")
        assertEquals("News", result.station("plain").group, "one strong vote beats a weak lead")
        assertEquals("En Español", result.station("nothingknown").group)
        assertEquals(emptyList(), result.unmappedCategories, "weak categories are mapped or abstain, never unmapped")
    }

    @Test fun `a provider-scoped category wins over the plain one`() {
        val result = build(votingPolicy, listOf(
            candidate("vizio", "v1", "Hit Sitcoms", "1", group = "Sitcoms"),
            candidate("lg", "l1", "Sitcom Hour", "2", group = "Sitcoms"),
        ))
        assertEquals("Comedy", result.station("hitsitcoms").group)
        assertEquals("Entertainment", result.station("sitcomhour").group)
    }

    @Test fun `a name pattern wins over every vote, and a raw group pattern votes like a map entry`() {
        val result = build(votingPolicy, listOf(
            candidate("samsung", "s1", "Telemundo Noticias", "1", group = "Reality"),
            candidate("vizio", "v1", "Telemundo Noticias", "2", group = "Reality"),
            candidate("lg", "l1", "Quiz Night", "3", group = "Game Time"),
        ))
        assertEquals("News", result.station("telemundonoticias").group)
        assertEquals("Game Shows", result.station("quiznight").group)
    }

    @Test fun `an entry's group groups the stations it leads and no other`() {
        val regional = votingPolicy.copy(providers = votingPolicy.providers + PolicyProvider("samsung-gb", group = "UK"))
        val result = build(regional, listOf(
            candidate("samsung-gb", "g1", "Pointless", "1", group = "Entertainment"),
            candidate("samsung-gb", "g2", "Wicked Tuna", "2", group = "Reality"),
            candidate("whale", "w1", "Wicked Tuna", "3", group = "Unmapped Bucket"),
        ))
        assertEquals("UK", result.station("pointless").group)
        assertEquals("Lifestyle", result.station("wickedtuna").group, "whale leads, so the regional entry's group does not apply; its genre still votes")
        assertEquals(listOf("whale" to "unmapped bucket"), result.unmappedCategories.map { it.provider to it.category }, "a station its entry groups lists no unmapped category")
        assertTrue(validatePolicy(regional.copy(providers = listOf(PolicyProvider("whale", group = " "))), setOf("whale")).contains("providers[0]: group must be 1 to 128 characters"))
    }

    @Test fun `every source's unmapped category is reported, not only the leading one's`() {
        val result = build(votingPolicy, listOf(
            candidate("whale", "w1", "Bravo", "1", group = "News"),
            candidate("samsung", "s1", "Bravo", "2", group = "Arts"),
            candidate("samsung", "s2", "Charlie", "3", group = "Arts"),
            candidate("samsung", "s3", "Telemundo Noticias", "4", group = "Arts"),
        ))
        assertEquals("News", result.station("bravo").group)
        assertEquals(listOf(PolicyUnmappedCategory("samsung", "arts", 2, "Bravo")), result.unmappedCategories, "a name pattern settles its station")
        assertTrue("groups: 1 categories have no mapping (2 stations); see unmappedCategories" in result.findings)
    }

    @Test fun `weak keys must be lowercase and trimmed`() {
        val errors = validatePolicy(votingPolicy.copy(groups = PolicyGroups(weak = listOf("TV", " us"))), setOf("whale", "samsung", "vizio", "lg"))
        assertEquals(listOf("groups.weak: \"TV\" must be lowercase and trimmed", "groups.weak: \" us\" must be lowercase and trimmed"), errors)
    }

    @Test fun `followGroup moves server-numbered stations into their group's block while it has room`() {
        val grouped = LineupPolicy(
            names = PolicyNames(version = 1),
            health = PolicyHealth(requireVerified = false),
            providers = listOf(PolicyProvider("a")),
            groups = PolicyGroups(map = mapOf("news" to "News", "sports" to "Sports")),
            numbering = PolicyNumbering(
                floor = 1000, groups = mapOf("News" to 100, "Sports" to 200), groupSize = 2, followGroup = true,
                assigned = listOf("news one", "news two", "news three", "sport a"),
            ),
            stations = mapOf(
                "news one" to StationOverride(number = "200"), "news two" to StationOverride(number = "201"),
                "news three" to StationOverride(number = "1000"), "sport a" to StationOverride(number = "1001"),
                "news hand" to StationOverride(number = "203"),
            ),
        )
        val catalog = listOf(
            candidate("a", "n1", "News One", "1", group = "News"),
            candidate("a", "n2", "News Two", "2", group = "News"),
            candidate("a", "n3", "News Three", "3", group = "News"),
            candidate("a", "n4", "News Hand", "4", group = "News"),
            candidate("a", "s1", "Sport A", "5", group = "Sports"),
        )
        val result = build(grouped, catalog)
        assertEquals(mapOf("news one" to "100", "news two" to "101"), result.renumbered)
        assertEquals("1000", result.station("news three").number, "the block is full, so it keeps its number")
        assertEquals("203", result.station("news hand").number, "a number set by hand never moves")
        assertEquals("1001", result.station("sport a").number, "numbers freed this run stay reserved until the next")
        assertEquals(emptyMap(), build(grouped.copy(numbering = grouped.numbering.copy(followGroup = false)), catalog).renumbered)
    }
}
