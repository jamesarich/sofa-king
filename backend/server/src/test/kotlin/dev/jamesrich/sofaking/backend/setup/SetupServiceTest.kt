package dev.jamesrich.sofaking.backend.setup

import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.admin.applyPlaylistForm
import dev.jamesrich.sofaking.backend.admin.applyServicesForm
import dev.jamesrich.sofaking.backend.admin.SetupView
import dev.jamesrich.sofaking.backend.admin.servicesChanges
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.SourceDocument
import dev.jamesrich.sofaking.backend.catalog.loadPolicy
import dev.jamesrich.sofaking.backend.catalog.loadSources
import dev.jamesrich.sofaking.backend.catalog.readSourceDocument
import dev.jamesrich.sofaking.backend.providers.NativeServices
import io.ktor.http.parameters
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SetupServiceTest {
    private val token = "secret".repeat(8)

    private fun config(dir: java.nio.file.Path, sources: java.nio.file.Path? = null, policy: java.nio.file.Path? = null) =
        ServerConfig("127.0.0.1", 8080, dir, sources, token, policyFile = policy)

    @Test fun `the catalog names every native service once, in a known order`() {
        assertEquals(NativeServices.names, ServiceCatalog.all.mapTo(HashSet()) { it.service })
        assertEquals(ServiceCatalog.all.size, ServiceCatalog.all.map { it.service }.distinct().size)
        assertEquals("direct", ServiceCatalog.order.first(), "the broadcasters' own feeds lead the starter order")
    }

    @Test fun `the services form keeps, drops and adds providers, and refuses an option that does not fit`() {
        val document = SourceDocument(listOf(
            FeedConfig("pbs", type = "native", service = "pbs", options = mapOf("zip" to "50309", "stations" to "x")),
            FeedConfig("whale", type = "native", service = "whale"),
            FeedConfig("iptv", playlistUrl = "https://feeds.test/p.m3u"),
        ))
        val next = applyServicesForm(document, parameters {
            append("svc", "pbs"); append("svc", "pluto"); append("opt.pbs.zip", "10001")
        }).getOrThrow()
        assertEquals(listOf("pbs", "iptv", "pluto"), next.providers.map { it.id }, "the file's order holds; new services go last; an unticked one leaves")
        assertEquals(mapOf("zip" to "10001", "stations" to "x"), next.providers[0].options, "a form option replaces its key and keeps the rest")
        assertEquals("pluto", next.providers[2].service)
        assertEquals(listOf("Turns on Pluto TV", "Turns off Whale TV+; their channels leave the lineup's stations as sources", "Changes the options of PBS member stations"), servicesChanges(document, next))

        val bad = applyServicesForm(document, parameters { append("svc", "pbs"); append("opt.pbs.zip", "ABCDE"); append("svc", "hdhomerun") })
        val message = bad.exceptionOrNull()!!.message!!
        assertTrue("ZIP code does not look right" in message && "Tuner address is required" in message, message)

        val cleared = applyServicesForm(document, parameters { append("svc", "pbs"); append("opt.pbs.zip", "") }).getOrThrow()
        assertEquals(mapOf("stations" to "x"), cleared.providers[0].options, "an emptied option is removed")
    }

    @Test fun `the playlist form adds an M3U provider and refuses a taken id or a bad URL`() {
        val document = SourceDocument(listOf(FeedConfig("whale", type = "native", service = "whale")))
        val next = applyPlaylistForm(document, parameters { append("id", "iptv"); append("url", "https://feeds.test/p.m3u?token=t"); append("guide", "") }).getOrThrow()
        assertEquals(FeedConfig("iptv", playlistUrl = "https://feeds.test/p.m3u?token=t"), next.providers[1])
        assertTrue("already exists" in applyPlaylistForm(document, parameters { append("id", "whale"); append("url", "https://feeds.test/p.m3u") }).exceptionOrNull()!!.message!!)
        assertTrue("http or https" in applyPlaylistForm(document, parameters { append("id", "x"); append("url", "ftp://feeds.test/p.m3u") }).exceptionOrNull()!!.message!!)
    }

    @Test fun `replacing the sources validates like the start, writes the file where the server reads it and restarts once`() = runTest {
        val dir = Files.createTempDirectory("sofa-setup-")
        try {
            var restarts = 0
            val setup = SetupService(config(dir), restart = { restarts++ }, environment = mapOf("KEY" to "k"))
            assertFalse(setup.hasSources)
            assertEquals(SourceDocument(), setup.document(), "no file reads as an empty document")

            val refused = assertFailsWith<ApiFailure> { setup.replaceSources(SourceDocument(listOf(FeedConfig("bad", type = "native", service = "nope")))) }
            assertEquals("INVALID_SOURCES", refused.code)
            assertFalse(Files.exists(dir.resolve("sources.json")), "nothing is written for a document that would not load")
            assertEquals(0, restarts)

            val unset = assertFailsWith<ApiFailure> { setup.replaceSources(SourceDocument(listOf(FeedConfig("m", playlistUrl = "https://feeds.test/\${MISSING}.m3u")))) }
            assertTrue("MISSING" in unset.message, unset.message)

            val document = SourceDocument(listOf(
                FeedConfig("whale", type = "native", service = "whale"),
                FeedConfig("m", playlistUrl = "https://feeds.test/p.m3u", headers = mapOf("X-Key" to "\${KEY}")),
            ))
            val result = setup.replaceSources(document)
            assertEquals(dir.resolve("sources.json").toString(), result.path)
            assertTrue(result.restarting)
            assertEquals(1, restarts)
            assertTrue(setup.isRestarting)
            val written = Files.readString(dir.resolve("sources.json"))
            assertTrue("\${KEY}" in written && "\"k\"" !in written, "the reference is written, never the environment's value")
            assertEquals(document, readSourceDocument(dir.resolve("sources.json")))
            assertEquals("k", loadSources(dir.resolve("sources.json"), mapOf("KEY" to "k")).providers[1].headers["X-Key"])

            setup.replaceSources(document)
            assertEquals(1, restarts, "one restart serves every write before it")
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `the starter policy takes the configured services in order, with the shipped groups and blocks, and loads at start`() = runTest {
        val dir = Files.createTempDirectory("sofa-setup-")
        try {
            val sources = dir.resolve("sources.json")
            val providers = listOf(
                FeedConfig("pluto", type = "native", service = "pluto"),
                FeedConfig("localnow", type = "native", service = "localnow"),
                FeedConfig("antenna", type = "native", service = "hdhomerun", options = mapOf("url" to "http://192.0.2.1")),
                FeedConfig("vizio", type = "native", service = "vizio"),
                FeedConfig("iptv", playlistUrl = "https://feeds.test/p.m3u"),
            )
            var restarts = 0
            val none = SetupService(config(dir), restart = { restarts++ })
            assertEquals("NO_SOURCES", assertFailsWith<ApiFailure> { none.writeStarterPolicy() }.code)

            Files.writeString(sources, String(dev.jamesrich.sofaking.backend.catalog.sourceBytes(SourceDocument(providers))))
            val setup = SetupService(config(dir, sources), restart = { restarts++ })
            val result = setup.writeStarterPolicy()
            assertEquals(dir.resolve("policy.json").toString(), result.path)
            assertEquals(1, restarts)
            val loaded = loadPolicy(dir.resolve("policy.json"), providers.mapTo(HashSet()) { it.id })
            val policy = loaded.policy
            assertEquals(listOf("antenna", "vizio", "pluto", "localnow", "localnow", "localnow", "iptv"), policy.providers.map { it.id }, "the tuner first, then the catalog's order, then playlists")
            assertEquals(listOf("Local channels"), policy.providers[1].exclude?.groups)
            assertEquals(listOf("My City"), policy.providers[3].include?.groups)
            assertEquals(false, policy.providers[4].enabled)
            assertEquals("Entertainment", policy.groups.default)
            assertEquals("News", policy.groups.map["news and opinion"])
            assertFalse(policy.groups.builtIn, "the starter copies the shipped map, so it does not also fall back to it")
            assertEquals(1, policy.numbering.groups["Local"]); assertEquals(2600, policy.numbering.groups["Ambiance"])
            assertEquals(3000, policy.numbering.floor); assertEquals(400, policy.numbering.groupSize); assertTrue(policy.numbering.followGroup)
            assertTrue(policy.health.requireVerified)
            assertEquals(2, policy.names.version)
            assertEquals(setOf(), (policy.groups.map.values.toSet() + policy.groups.patterns.map { it.group }) - policy.numbering.groups.keys, "every group the map names has a block")

            assertEquals("POLICY_EXISTS", assertFailsWith<ApiFailure> { setup.writeStarterPolicy() }.code)
            assertEquals(1, restarts)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `a fresh server ticks the services that list channels without an option, and a server with a file ticks what the file has`() {
        val dir = Files.createTempDirectory("sofa-setup-")
        try {
            val fresh = SetupView.of(SourceDocument(), SetupService(config(dir), restart = {}))
            val on = fresh.services.filter { it.on }.map { it.info.service }
            assertTrue("whale" in on && "localnow" in on, on.toString())
            assertFalse("pbs" in on || "hdhomerun" in on, "a service that needs an option to list anything starts unticked")
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, "[]")
            val existing = SetupView.of(SourceDocument(listOf(FeedConfig("pbs", type = "native", service = "pbs", options = mapOf("zip" to "50309")))), SetupService(config(dir, sources), restart = {}))
            assertEquals(listOf("pbs"), existing.services.filter { it.on }.map { it.info.service })
            assertEquals("50309", existing.services.first { it.info.service == "pbs" }.options["zip"])
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `the shipped groups load lowercase and know the free services' categories`() {
        val groups = DefaultGroups.load()
        assertEquals("News", groups.groupOf("News And Opinion"))
        assertTrue(groups.isWeak("Featured"))
        assertNull(groups.groupOf("no such category"))
        assertTrue(groups.map.keys.all { it == it.trim().lowercase() })
    }
}
