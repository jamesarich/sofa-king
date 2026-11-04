package dev.jamesrich.sofaking.backend.providers.manifest

import dev.jamesrich.sofaking.backend.providers.ProviderFixture
import dev.jamesrich.sofaking.backend.providers.providerTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProviderManifestsTest {
    private val cw = ProviderFixture.resource("cw.json")
    private val feedPath = "/other/page_1/pagesize_75/device_web/apiversion_24/cacheversion_" +
        "${java.time.Instant.parse("2026-10-10T13:00:00Z").epochSecond / 900}"

    private fun directory(): Path = Files.createTempDirectory("sofa-manifests-")

    private fun parseFails(text: String): String =
        assertFailsWith<IllegalArgumentException> { ProviderManifests.parse(text.toByteArray(), "cw") }.message!!

    @Test fun `every shipped manifest validates, has a catalog block and is listed`() {
        val files = Files.list(Path.of("src", "main", "resources", "providers")).use { stream ->
            stream.map { it.fileName.toString().removeSuffix(".json") }.sorted().toList()
        }
        assertEquals(files, ProviderManifests.SHIPPED_NAMES.sorted(), "every file is in SHIPPED_NAMES")
        val table = Files.readAllLines(Path.of("..", "docs", "providers.md")).first { it.startsWith("| `service` |") }
        for ((service, loaded) in ProviderManifests.shipped) {
            assertEquals(emptyList(), loaded.manifest.validate(), service)
            assertEquals(service, loaded.manifest.service)
            assertNotNull(loaded.manifest.catalog, "$service has a catalog block")
            assertContains(table, "`$service`", message = "$service is in the configuration table of providers.md")
        }
    }

    @Test fun `an invalid data directory file is rejected and the shipped manifest stays`() {
        val dir = directory()
        Files.writeString(dir.resolve("cw.json"), "{ not json")
        val manifests = ProviderManifests(dir)
        assertContains(manifests.errors.getValue("cw"), "not a manifest")
        assertSame(ProviderManifests.shipped.getValue("cw"), manifests.current("cw"))
        Files.writeString(dir.resolve("hallmark.json"), "{}")
        manifests.reloadIfChanged()
        assertTrue("hallmark" in manifests.errors)
        assertSame(ProviderManifests.shipped.getValue("hallmark"), manifests.current("hallmark"))
    }

    @Test fun `an override takes effect after a reload and the old fetch is not reused`() = providerTest("sofa-cw-") {
        val dir = directory()
        val changed = mutableListOf<String>()
        val manifests = ProviderManifests(dir) { changed += it }
        val client = ManifestClient(context("cw"), manifests, "cw")
        val shippedBase = "https://data.cwtv.com/feed/app-2/landing/epg"
        assertContains(cw, shippedBase)
        // The shipped feed address is unreachable here, so first point the default at the fixture.
        Files.writeString(dir.resolve("cw.json"), cw.replace(shippedBase, "${fixture.base}/other"))
        manifests.reloadIfChanged()
        assertEquals(listOf("cw"), changed)
        fixture.json(feedPath, ProviderFixture.resource("cw-feed.json"))
        assertEquals(5, client.channels().size)
        client.channels()
        assertEquals(1, fixture.hits(feedPath), "the fetch is shared while the manifest is the same")
        Files.writeString(dir.resolve("cw.json"), cw.replace(shippedBase, "${fixture.base}/other") + "\n\n")
        manifests.reloadIfChanged()
        assertEquals(listOf("cw", "cw"), changed)
        client.channels()
        assertEquals(2, fixture.hits(feedPath), "a reload invalidates what the old manifest fetched")
        manifests.reloadIfChanged()
        assertEquals(2, changed.size, "an unchanged file is not read again")
    }

    @Test fun `an unknown op, an unknown name and a literal credential fail validation`() {
        assertContains(parseFails(cw.replace("\"op\": \"http\"", "\"op\": \"bogus\"")), "unknown op bogus")
        assertContains(parseFails(cw.replace("\${page}", "\${nope}")), "nope")
        val decoded = cw.replace("\"items\": \"channels\"", "\"items\": \"channels\", \"decode\": [\"bogus\"]")
        assertContains(parseFails(decoded), "unknown decode step")
        assertContains(
            parseFails(cw.replace("\"Accept\": \"application/json\"", "\"Authorization\": \"Bearer abc\"")),
            "never a literal value",
        )
        assertContains(parseFails(cw.replace("\"service\": \"cw\"", "\"service\": \"other\"")), "file name")
        assertContains(
            parseFails(cw.replace("\"from\": \"feed\"", "\"from\": \"missing\"")),
            "no request named missing",
        )
        assertContains(parseFails(cw.replace("\"as\": \"epochSeconds\"", "\"as\": \"weeks\"")), "unknown as weeks")
    }
}
