package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.health.FailureClass
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.health.Observation
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class CurationServiceTest {
    private class Rig(
        val fixture: Fixture, val store: BackendStore, val clock: MutableClock, val feeds: FeedService, val guides: GuideService,
        val lineup: LineupService, val catalog: CatalogService, val curation: CurationService,
    ) {
        fun channel(provider: String, name: String? = null): Channel = feeds.channels(provider).single { name == null || it.name == name }
        fun sourceOf(channel: Channel): String = feeds.offeringsOf(channel.id).single().id
        suspend fun entry(id: String): LineupEntryRecord = lineup.entry(id)!!
        suspend fun strike(offeringId: String, incident: String) {
            store.recordObservation(offeringId, Observation(incident, clock.now, false, FailureClass.OFFERING), clock.now)
        }
        suspend fun qualify(offeringId: String, incident: String) {
            store.recordObservation(offeringId, Observation(incident, clock.now, true), clock.now)
        }
    }

    private fun rig(feeds: (Fixture) -> List<FeedConfig>, guides: (Fixture) -> List<GuideConfig> = { emptyList() }, block: suspend (Rig) -> Unit): Unit = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-curation-")
        try {
            Fixture().use { fixture -> HttpClient(CIO) { followRedirects = false }.use { http -> openStore(dir).use { store ->
                val clock = MutableClock()
                val feedService = FeedService(feeds(fixture), store, http, clock, UpstreamPolicy(allowLoopback = true))
                for (config in feedService.configs) feedService.refresh(config)
                lateinit var lineup: LineupService
                val guideService = GuideService(guides(fixture), store, http, clock, UpstreamPolicy(allowLoopback = true)) { lineup.wanted() }
                guideService.initialize()
                lineup = LineupService(store, clock, feedService, guideService)
                lineup.initialize()
                val curation = CurationService(store, clock, feedService, guideService, lineup)
                curation.initialize()
                val catalog = CatalogService(feedService, guideService, lineup, store, clock, curation::retiredLogoHosts)
                for (guide in guideService.configs) guideService.refresh(guide)
                block(Rig(fixture, store, clock, feedService, guideService, lineup, catalog, curation))
            } } }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `group and correction rules change unpinned fields only, and a preview changes nothing`() = rig({ listOf(it.feed) }) { r ->
        val entry = r.lineup.create(CreateLineupEntry(channelId = r.channel("fixture").id))
        assertEquals("News", entry.group)
        r.curation.createRule(CurationRule("news", "group", match = RuleMatch(group = "news"), set = RuleSet(group = "Headlines")))
        r.curation.createRule(CurationRule("fix-station", "correction", match = RuleMatch(name = "station"), set = RuleSet(name = "Station HD")))
        val preview = r.curation.preview()
        assertEquals(listOf("regroup", "rename"), preview.actions.map { it.kind }.sorted())
        assertEquals(mapOf("regroup" to 1, "rename" to 1), preview.counts)
        assertFalse(preview.enabled)
        assertEquals("News", r.entry(entry.id).group, "a preview changes nothing")
        assertFalse(r.curation.run().enabled)
        assertEquals("News", r.entry(entry.id).group, "with automation off a run changes nothing either")
        assertTrue(r.curation.changes(10).isEmpty())

        r.curation.updateSettings(CurationSettings(enabled = true))
        val applied = r.curation.run()
        assertTrue(applied.enabled); assertEquals(2, applied.actions.size)
        assertEquals("Headlines", r.entry(entry.id).group); assertEquals("Station HD", r.entry(entry.id).name)
        assertEquals(setOf("regroup", "rename"), r.curation.changes(10).map { it.action.kind }.toSet())
        assertEquals(entry.id, r.curation.changes(10).first().action.entryId)
        assertTrue(r.curation.run().actions.isEmpty(), "a second run has nothing left to do")
        assertEquals(listOf("Headlines"), r.catalog.catalog().channels.single().tags)

        r.lineup.update(entry.id, UpdateLineupEntry(group = "Mine"))
        r.curation.updateRule("news", CurationRule(kind = "group", match = RuleMatch(group = "news"), set = RuleSet(group = "Elsewhere")))
        assertTrue(r.curation.run().actions.isEmpty(), "a pinned group is the household's")
        assertEquals("Mine", r.entry(entry.id).group)
        r.lineup.update(entry.id, UpdateLineupEntry(unpin = listOf("group")))
        assertEquals("regroup", r.curation.run().actions.single().kind)
        assertEquals("Elsewhere", r.entry(entry.id).group)

        assertEquals("INVALID_REQUEST", assertFailsWith<ApiFailure> { r.curation.createRule(CurationRule(kind = "group", match = RuleMatch(group = "x"))) }.code)
        assertFailsWith<ApiFailure> { r.curation.createRule(CurationRule(kind = "nope")) }
        assertFailsWith<ApiFailure> { r.curation.createRule(CurationRule(kind = "group", match = RuleMatch(groupPattern = "("), set = RuleSet(group = "x"))) }
        assertFailsWith<ApiFailure> { r.curation.createRule(CurationRule(kind = "adopt", match = RuleMatch(providerId = "nope"))) }
        assertEquals("RULE_EXISTS", assertFailsWith<ApiFailure> { r.curation.createRule(CurationRule("news", "group", match = RuleMatch(group = "y"), set = RuleSet(group = "z"))) }.code)
        assertEquals("RULE_NOT_FOUND", assertFailsWith<ApiFailure> { r.curation.deleteRule("missing") }.code)
        assertFailsWith<ApiFailure> { r.curation.updateSettings(CurationSettings(enableOn = "sometimes")) }
        assertFailsWith<ApiFailure> { r.curation.updateSettings(CurationSettings(intervalSeconds = 1)) }
    }

    @Test fun `equivalent candidates attach on name and guide evidence, and a name alone needs a rule or the setting`() = rig({ listOf(it.feed, it.altFeed, it.twinFeed) }) { r ->
        r.curation.updateSettings(CurationSettings(enabled = true))
        val station = r.channel("fixture"); val alt = r.channel("alt"); val twin = r.channel("twin", "STATION")
        val entry = r.lineup.create(CreateLineupEntry(channelId = station.id))
        val attach = r.curation.run().actions.single()
        assertEquals("attach", attach.kind); assertEquals(listOf(r.sourceOf(alt)), attach.offeringIds)
        assertEquals(listOf(r.sourceOf(station), r.sourceOf(alt)), r.entry(entry.id).offeringIds, "the equivalent source is appended as a backup")
        assertTrue(r.curation.run().actions.isEmpty(), "the same name under another tvg-id is no evidence")

        r.curation.createRule(CurationRule("twin", "equivalent", match = RuleMatch(entryId = entry.id, name = "Station")))
        val byRule = r.curation.run().actions.single()
        assertEquals("twin", byRule.ruleId); assertEquals(listOf(r.sourceOf(twin)), byRule.offeringIds)
        assertEquals(3, r.entry(entry.id).offeringIds.size)

        val sports = r.lineup.create(CreateLineupEntry(number = "50", name = "Twin Sports", offerings = emptyList()))
        r.curation.updateSettings(CurationSettings(enabled = true, equivalentEvidence = "name"))
        assertTrue(r.curation.run().actions.isEmpty(), "pinned offerings are the household's")
        r.lineup.update(sports.id, UpdateLineupEntry(unpin = listOf("offerings")))
        assertEquals("attach", r.curation.run().actions.single().kind)
        assertEquals(listOf(r.sourceOf(r.channel("twin", "Twin Sports"))), r.entry(sports.id).offeringIds)
    }

    @Test fun `a candidate matching several entries joins the one enabled entry, and stays a finding when that is not unique`() = rig({ listOf(it.twinFeed) }) { r ->
        r.curation.updateSettings(CurationSettings(enabled = true, equivalentEvidence = "name"))
        val stale = r.lineup.create(CreateLineupEntry(number = "50", name = "Twin Sports", enabled = false, offerings = emptyList()))
        val live = r.lineup.create(CreateLineupEntry(number = "51", name = "Twin Sports", offerings = emptyList()))
        r.lineup.update(stale.id, UpdateLineupEntry(unpin = listOf("offerings")))
        r.lineup.update(live.id, UpdateLineupEntry(unpin = listOf("offerings")))
        val preview = r.curation.preview()
        assertEquals(live.id, preview.actions.single { it.kind == "attach" }.entryId)
        assertTrue(preview.findings.none { "Twin Sports" in it })

        r.lineup.update(stale.id, UpdateLineupEntry(enabled = true))
        val tied = r.curation.preview()
        assertTrue(tied.actions.none { it.kind == "attach" })
        assertTrue(tied.findings.any { "Twin Sports (twin) matches 2 entries" in it })
    }

    @Test fun `an equivalent rule shares a candidate another entry already carries`() = rig({ listOf(it.twinFeed) }) { r ->
        r.curation.updateSettings(CurationSettings(enabled = true))
        val sports = r.channel("twin", "Twin Sports")
        val holder = r.lineup.create(CreateLineupEntry(channelId = sports.id))
        val other = r.lineup.create(CreateLineupEntry(number = "60", name = "Sports Too", offerings = emptyList()))
        r.lineup.update(other.id, UpdateLineupEntry(unpin = listOf("offerings")))
        assertTrue(r.curation.run().actions.none { it.kind == "attach" }, "without a rule a carried candidate stays where it is")
        r.curation.createRule(CurationRule("share", "equivalent", match = RuleMatch(entryId = other.id, name = "Twin Sports", providerId = "twin")))
        val shared = r.curation.run().actions.single { it.kind == "attach" }
        assertEquals(other.id, shared.entryId); assertEquals("share", shared.ruleId)
        assertEquals(listOf(r.sourceOf(sports)), r.entry(other.id).offeringIds)
        assertEquals(listOf(r.sourceOf(sports)), r.entry(holder.id).offeringIds, "the holder keeps it")
        assertTrue(r.curation.run().actions.isEmpty())
    }

    @Test fun `adopt rules create disabled entries numbered by the policy and never adopt a candidate twice`() = rig({ listOf(it.tsFeed, it.twinFeed) }) { r ->
        r.curation.updateSettings(CurationSettings(enabled = true, numberFloor = 100))
        r.curation.createRule(CurationRule("sports", "group", match = RuleMatch(namePattern = "sport|espn"), set = RuleSet(group = "Sports")))
        r.curation.createRule(CurationRule("us-news", "group", match = RuleMatch(group = "US: News"), set = RuleSet(group = "News")))
        r.curation.createRule(CurationRule("twin", "adopt", match = RuleMatch(providerId = "twin")))
        r.lineup.create(CreateLineupEntry(number = "5", name = "Manual Five"))
        val plan = r.curation.run()
        assertEquals(listOf("adopt", "adopt"), plan.actions.map { it.kind })
        val station = r.lineup.entries().single { it.name == "STATION" }
        val sports = r.lineup.entries().single { it.name == "Twin Sports" }
        assertFalse(station.enabled, "an adopted candidate is off unless the rule says otherwise")
        assertEquals("rule:twin", station.createdBy)
        assertEquals("100", station.number, "5 is taken, so the first free number at the floor")
        assertEquals("News", station.group)
        assertEquals("12", sports.number); assertEquals("Sports", sports.group, "the name pattern applies when no exact group matches")
        assertTrue(r.lineup.entries().none { it.name == "TS Station" }, "no rule adopts the ts provider")
        assertTrue(r.catalog.catalog().channels.none { it.name == "STATION" })

        r.lineup.delete(station.id)
        assertTrue(r.curation.run().actions.isEmpty(), "a deleted adopted entry is not adopted again")
        r.curation.createRule(CurationRule("ts", "adopt", match = RuleMatch(providerId = "ts"), set = RuleSet(enabled = true, group = "Live")))
        val adopted = r.curation.run().actions.single()
        val ts = r.entry(adopted.entryId!!)
        assertTrue(ts.enabled); assertEquals("Live", ts.group); assertEquals("6", ts.number); assertEquals("rule:ts", ts.createdBy)
    }

    @Test fun `one adoption run joins the same station from a later rule's provider as a fallback, the first rule's source leading`() = rig({ listOf(it.feed, it.twinFeed) }) { r ->
        r.curation.updateSettings(CurationSettings(enabled = true, equivalentEvidence = "name"))
        r.curation.createRule(CurationRule("twin-first", "adopt", position = 0, match = RuleMatch(providerId = "twin"), set = RuleSet(enabled = true)))
        r.curation.createRule(CurationRule("fixture-second", "adopt", position = 1, match = RuleMatch(providerId = "fixture"), set = RuleSet(enabled = true)))
        val adopted = r.curation.run().actions.filter { it.kind == "adopt" }
        assertEquals(2, adopted.size, "Station once and Twin Sports, not Station twice")
        val station = r.lineup.entries().single { it.name.equals("station", ignoreCase = true) }
        assertEquals(listOf(r.sourceOf(r.channel("twin", "STATION")), r.sourceOf(r.channel("fixture"))), station.offeringIds)
        assertTrue(r.curation.run().actions.none { it.kind == "adopt" })
    }

    @Test fun `without name evidence an adoption run keeps stations with different guide ids apart`() = rig({ listOf(it.feed, it.twinFeed) }) { r ->
        r.curation.updateSettings(CurationSettings(enabled = true))
        r.curation.createRule(CurationRule("twin-first", "adopt", position = 0, match = RuleMatch(providerId = "twin")))
        r.curation.createRule(CurationRule("fixture-second", "adopt", position = 1, match = RuleMatch(providerId = "fixture")))
        assertEquals(3, r.curation.run().actions.count { it.kind == "adopt" })
    }

    @Test fun `an entry with no eligible source for a day is disabled and restored when a source is healthy, and household flags win`() = rig({ listOf(it.feed, it.altFeed, it.tsFeed) }) { r ->
        r.curation.updateSettings(CurationSettings(enabled = true))
        val station = r.channel("fixture"); val alt = r.channel("alt"); val ts = r.channel("ts")
        val entry = r.lineup.create(CreateLineupEntry(channelId = station.id))
        val pinnedOn = r.lineup.create(CreateLineupEntry(channelId = alt.id, number = "6", enabled = true))
        val householdOff = r.lineup.create(CreateLineupEntry(channelId = ts.id, enabled = false))
        assertEquals(setOf("enabled"), pinnedOn.pinned + householdOff.pinned - setOf("number"))
        val source = r.sourceOf(station); val altSource = r.sourceOf(alt)
        for (round in 0..2) {
            if (round > 0) r.clock.now += Duration.ofMinutes(5)
            r.strike(source, "s$round"); r.strike(altSource, "a$round")
        }
        assertEquals(HealthStatus.QUARANTINED, r.store.health(source)!!.status)
        assertTrue(r.curation.run().actions.isEmpty(), "quarantine alone does not disable")
        r.clock.now += Duration.ofHours(23)
        assertTrue(r.curation.run().actions.isEmpty())
        r.clock.now += Duration.ofHours(1)
        val disabled = r.curation.run().actions
        assertEquals(listOf("disable"), disabled.map { it.kind }); assertEquals(entry.id, disabled.single().entryId)
        val off = r.entry(entry.id)
        assertFalse(off.enabled); assertEquals(r.clock.now, off.autoDisabledAt); assertEquals("unavailable", off.autoDisabledReason)
        assertTrue(r.entry(pinnedOn.id).enabled, "a pinned enabled flag is the household's")
        assertTrue(r.catalog.catalog().channels.none { it.id == entry.id })

        r.qualify(source, "ok1")
        assertEquals(HealthStatus.PROBATION, r.store.health(source)!!.status)
        assertTrue(r.curation.run().actions.isEmpty(), "probation is not eligible")
        r.clock.now += Duration.ofMinutes(10)
        r.qualify(source, "ok2")
        assertEquals(HealthStatus.HEALTHY, r.store.health(source)!!.status)
        val restored = r.curation.run().actions.single()
        assertEquals("enable", restored.kind)
        val on = r.entry(entry.id)
        assertTrue(on.enabled); assertNull(on.autoDisabledAt); assertNull(on.autoDisabledReason)

        r.qualify(r.sourceOf(ts), "ok-ts")
        assertTrue(r.curation.run().actions.isEmpty())
        assertFalse(r.entry(householdOff.id).enabled, "an entry the household disabled stays disabled")

        // The household taking the flag back ends automation's hold on it.
        r.lineup.update(entry.id, UpdateLineupEntry(enabled = false))
        val own = r.entry(entry.id)
        assertTrue(own.pinned("enabled")); assertNull(own.autoDisabledAt)
        assertTrue(r.curation.run().actions.isEmpty())
    }

    @Test fun `an alias rule merges the alias's sources into the canonical entry and disables the alias until the rule goes`() = rig({ listOf(it.feed, it.altFeed) }) { r ->
        r.curation.updateSettings(CurationSettings(enabled = true))
        val station = r.channel("fixture"); val alt = r.channel("alt")
        val a = r.lineup.create(CreateLineupEntry(channelId = station.id))
        val b = r.lineup.create(CreateLineupEntry(channelId = alt.id, number = "6"))
        val duplicates = r.curation.run()
        assertTrue(duplicates.actions.isEmpty())
        assertTrue(duplicates.findings.any { "share the name and a guide identity" in it }, duplicates.findings.toString())
        r.curation.createRule(CurationRule("b-to-a", "alias", match = RuleMatch(entryId = b.id), set = RuleSet(canonicalEntryId = a.id)))
        val merged = r.curation.run()
        assertEquals(listOf("attach", "disable"), merged.actions.map { it.kind })
        assertEquals(listOf(r.sourceOf(station), r.sourceOf(alt)), r.entry(a.id).offeringIds)
        val alias = r.entry(b.id)
        assertFalse(alias.enabled); assertEquals("alias", alias.autoDisabledReason); assertEquals(listOf(r.sourceOf(alt)), alias.offeringIds, "the alias keeps its identity and sources")
        assertTrue(r.curation.run().actions.isEmpty())
        assertEquals(listOf(a.id), r.catalog.catalog().channels.map { it.id })

        r.curation.deleteRule("b-to-a")
        assertEquals("enable", r.curation.run().actions.single().kind)
        assertTrue(r.entry(b.id).enabled)
        assertFailsWith<ApiFailure> { r.curation.createRule(CurationRule(kind = "alias", match = RuleMatch(entryId = a.id), set = RuleSet(canonicalEntryId = a.id))) }
        assertFailsWith<ApiFailure> { r.curation.createRule(CurationRule(kind = "alias", match = RuleMatch(entryId = a.id), set = RuleSet(canonicalEntryId = "missing"))) }
    }

    @Test fun `retired logo hosts count as no logo at once and are cleared from unpinned entries, and the guide icon is the last fallback`() = rig({ listOf(it.feed, it.logoFeed) }, { listOf(it.gzipGuide) }) { r ->
        val station = r.channel("fixture")
        val entry = r.lineup.create(CreateLineupEntry(channelId = station.id))
        assertEquals(GuideMapping("gz", "station"), entry.guide)
        assertEquals("${r.fixture.base}/station.png", r.catalog.logoSource(entry), "the guide icon stands in when no source has a logo")
        val retired = "http://logos.retired.test:9192/logos/station.png"
        r.lineup.update(entry.id, UpdateLineupEntry(logo = retired))
        assertEquals(retired, r.catalog.logoSource(r.entry(entry.id)))
        val before = r.catalog.catalogRevision()
        r.curation.updateSettings(CurationSettings(enabled = true, retiredLogoHosts = listOf("Logos.Retired.test:9192")))
        assertEquals("${r.fixture.base}/station.png", r.catalog.logoSource(r.entry(entry.id)), "a retired host counts as no logo")
        assertNotEquals(before, r.catalog.catalogRevision(), "the channels ETag follows the retired hosts")
        assertTrue(r.curation.run().actions.isEmpty(), "the household set that logo")
        r.lineup.update(entry.id, UpdateLineupEntry(unpin = listOf("logo")))
        val cleared = r.curation.run().actions.single()
        assertEquals("clear-logo", cleared.kind); assertEquals(retired, cleared.before); assertNull(cleared.after)
        assertNull(r.entry(entry.id).logo)
        r.lineup.update(entry.id, UpdateLineupEntry(offerings = listOf(r.sourceOf(station), r.sourceOf(r.channel("logo")))))
        assertEquals(r.fixture.logoUrl, r.catalog.logoSource(r.entry(entry.id)), "any source's logo comes before the guide icon")
        assertTrue(onRetiredHost("http://old.host/x.png", setOf("old.host")))
        assertTrue(onRetiredHost("http://old.host:9192/x.png", setOf("old.host")), "a bare host covers every port")
        assertFalse(onRetiredHost("http://old.host/x.png", setOf("old.host:9192")))
        assertFalse(onRetiredHost("not a url", setOf("old.host")))
    }

    @Test fun `retired guides move unpinned enabled entries to a guide their sources list, the provider's own first`() = rig({ listOf(it.feed) }, { listOf(it.gzipGuide, GuideConfig("fixture", it.feed.guideUrl!!)) }) { r ->
        val entry = r.lineup.create(CreateLineupEntry(channelId = r.channel("fixture").id))
        assertEquals(GuideMapping("gz", "station"), entry.guide)
        r.curation.updateSettings(CurationSettings(enabled = true, retiredGuides = listOf("gz")))
        val remap = r.curation.run().actions.single()
        assertEquals("remap-guide", remap.kind); assertEquals("gz/station", remap.before); assertEquals("fixture/station", remap.after)
        assertEquals(GuideMapping("fixture", "station"), r.entry(entry.id).guide)
        assertTrue(r.curation.run().actions.isEmpty())

        r.curation.updateSettings(CurationSettings(enabled = true, retiredGuides = listOf("gz", "fixture")))
        assertTrue(r.curation.run().actions.isEmpty(), "with every listing guide retired the mapping stays")

        r.lineup.update(entry.id, UpdateLineupEntry(guide = GuideMapping("gz", "station")))
        r.curation.updateSettings(CurationSettings(enabled = true, retiredGuides = listOf("gz")))
        assertTrue(r.curation.run().actions.isEmpty(), "a pinned guide is the household's")
        r.lineup.update(entry.id, UpdateLineupEntry(enabled = false, unpin = listOf("guide")))
        assertTrue(r.curation.run().actions.isEmpty(), "disabled entries keep their mapping")
        r.lineup.update(entry.id, UpdateLineupEntry(enabled = true, clearGuide = true, unpin = listOf("guide")))
        assertEquals("unassigned", r.curation.run().actions.single().reason)
        assertEquals(GuideMapping("fixture", "station"), r.entry(entry.id).guide)

        r.curation.updateSettings(CurationSettings(enabled = true))
        r.lineup.update(entry.id, UpdateLineupEntry(guide = GuideMapping("gz", "gone"), unpin = listOf("guide")))
        assertEquals("guide gz does not list gone", r.curation.run().actions.single().reason)
        assertEquals(GuideMapping("fixture", "station"), r.entry(entry.id).guide, "a loaded guide that lacks the channel counts as no mapping")
        assertEquals("INVALID_REQUEST", assertFailsWith<ApiFailure> { r.curation.updateSettings(CurationSettings(retiredGuides = listOf("bad id"))) }.code)
    }

    @Test fun `the import translates the pipeline's files into rules that survive a restart`() = rig({ listOf(it.feed, it.altFeed) }) { r ->
        val a = r.lineup.create(CreateLineupEntry(channelId = r.channel("fixture").id))
        val b = r.lineup.create(CreateLineupEntry(channelId = r.channel("alt").id, number = "6"))
        val request = CurationImport(
            groupMap = mapOf("us: sports" to "Sports", "news & opinion" to "News"),
            aliases = mapOf(b.id to a.id, "11111111-1111-4111-8111-111111111111" to a.id),
            equivalents = listOf(EquivalentRecord(a.id, "Station"), EquivalentRecord(null, "Nowhere")),
            sportsCorrections = listOf(CorrectionRecord(1, "Ball Network", "Ball Channel"), CorrectionRecord(2, null, null)),
            corrections = JsonObject(mapOf("relabelled" to JsonPrimitive(1))),
            dryRun = true,
        )
        val dry = r.curation.import(request)
        assertEquals(5, dry.created); assertEquals(2, dry.skipped); assertEquals(0, dry.existing)
        assertEquals(2, dry.items.count { it.outcome == "applied-by-import" })
        assertTrue(r.curation.rules().isEmpty(), "a dry run creates nothing")
        val applied = r.curation.import(request.copy(dryRun = false))
        assertEquals(5, applied.created)
        val rules = r.curation.rules()
        assertEquals(listOf("group", "group", "alias", "equivalent", "correction"), rules.map { it.kind })
        assertEquals((0..4).toList(), rules.map { it.position })
        assertEquals(RuleMatch(group = "us: sports"), rules.first().match); assertEquals(RuleSet(group = "Sports"), rules.first().set)
        val again = r.curation.import(request.copy(dryRun = false))
        assertEquals(0, again.created); assertEquals(5, again.existing)

        r.curation.updateSettings(CurationSettings(enabled = true, retiredLogoHosts = listOf("Old.Host:9192")))
        val reloaded = CurationService(r.store, r.clock, r.feeds, r.guides, r.lineup)
        reloaded.initialize()
        assertEquals(rules, reloaded.rules())
        assertEquals(listOf("old.host:9192"), reloaded.settings().retiredLogoHosts); assertTrue(reloaded.settings().enabled)
        assertEquals("Ball Channel", reloaded.preview().let { plan -> plan.actions.firstOrNull { it.kind == "rename" }?.after ?: "Ball Channel" }, "a correction with no matching candidate plans nothing")
    }
}
