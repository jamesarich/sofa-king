package dev.jamesrich.sofaking.backend.iptvorg

import dev.jamesrich.sofaking.backend.catalog.FeedCandidate
import dev.jamesrich.sofaking.backend.catalog.StationKeys
import dev.jamesrich.sofaking.backend.providers.ProviderScope
import dev.jamesrich.sofaking.backend.providers.providerTest
import dev.jamesrich.sofaking.contract.Channel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvOrgMatcherTest {
    private fun candidate(
        name: String,
        tvgId: String? = null,
        logo: String? = null,
        tags: List<String> = emptyList(),
    ) = FeedCandidate(Channel("id-$name", "1", name, logo, tags, tvgId), "provider", emptyList())

    private suspend fun ProviderScope.matcher(
        settings: IptvOrgSettings = IptvOrgSettings(metadata = true),
    ): IptvOrgMatcher {
        serveIptvOrg()
        val catalog = iptvOrgCatalog(Files.createTempDirectory("iptv-org-data"), { settings })
        catalog.refresh()
        return IptvOrgMatcher(catalog, store, { settings }, clock)
    }

    private val keys = StationKeys()

    @Test fun `a tvg id equal to an iptv-org id matches on its own and fills the logo and category`() =
        providerTest("iptv-org-") {
            val matcher = matcher()
            val enriched = matcher.enrich(listOf(candidate("Cable Thing", tvgId = "CNN.us")), keys)
            val channel = enriched.candidates.single().channel
            assertEquals("https://logos.example/cnn.png", channel.logo)
            assertEquals(listOf("News", "us"), channel.tags)
            assertTrue(matcher.findings().isEmpty())
        }

    @Test fun `an exact name in a tagged country matches on its own`() = providerTest("iptv-org-") {
        val matcher = matcher()
        val enriched = matcher.enrich(listOf(candidate("BBC One", tags = listOf("gb"))), keys)
        assertEquals("https://logos.example/bbcone.svg", enriched.candidates.single().channel.logo)
        assertTrue(matcher.findings().isEmpty())
    }

    @Test fun `a name that fits two countries is a finding and is not applied`() = providerTest("iptv-org-") {
        val matcher = matcher()
        val enriched = matcher.enrich(listOf(candidate("News 24")), keys)
        assertNull(enriched.candidates.single().channel.logo)
        assertEquals(emptyList(), enriched.candidates.single().channel.tags)
        val finding = matcher.findings().single()
        assertTrue(finding.startsWith("news 24: iptv-org candidates News24.us (name), News24.uk (name)"), finding)
        assertTrue(matcher.overview().proposals.all { it.ambiguous })
    }

    @Test fun `a name with no country is proposed and an alternative name is proposed too`() = providerTest(
        "iptv-org-",
    ) {
        val matcher = matcher()
        matcher.enrich(listOf(candidate("CNN"), candidate("BBC 1")), keys)
        val bases = matcher.overview().proposals.associate { it.station to it.basis }
        assertEquals(mapOf("cnn" to "name", "bbc 1" to "alt-name"), bases)
    }

    @Test fun `accepting a proposal applies it and dismissing one stops any use of the match`() = providerTest(
        "iptv-org-",
    ) {
        val matcher = matcher()
        val candidates = listOf(candidate("CNN"))
        assertNull(matcher.enrich(candidates, keys).candidates.single().channel.logo)
        matcher.decide(IptvOrgDecisionRequest("cnn", "accept", "CNN.us"))
        val accepted = matcher.enrich(candidates, keys)
        assertEquals("https://logos.example/cnn.png", accepted.candidates.single().channel.logo)
        assertTrue(matcher.findings().isEmpty())
        matcher.decide(IptvOrgDecisionRequest("cnn", "dismiss"))
        val dismissed = matcher.enrich(candidates, keys)
        assertNull(dismissed.candidates.single().channel.logo)
        assertTrue(matcher.findings().isEmpty(), "a dismissed station is not proposed again")
    }

    @Test fun `an explicit logo and category are never replaced`() = providerTest("iptv-org-") {
        val matcher = matcher()
        val enriched = matcher.enrich(
            listOf(
                candidate(
                    "Cable Thing",
                    tvgId = "CNN.us",
                    logo = "https://own.example/logo.png",
                    tags = listOf("Sports"),
                ),
            ),
            keys,
        )
        val channel = enriched.candidates.single().channel
        assertEquals("https://own.example/logo.png", channel.logo)
        assertEquals(listOf("Sports"), channel.tags)
    }

    @Test fun `a blocklisted match holds the station with the reason and reference until it is dismissed`() =
        providerTest("iptv-org-") {
            val matcher = matcher()
            val candidates = listOf(candidate("Blocked Channel", tvgId = "Blocked.us"))
            val held = matcher.enrich(candidates, keys)
            val finding = held.holds.getValue("blocked channel")
            assertTrue("dmca" in finding && "https://github.com/iptv-org/iptv/issues/1" in finding, finding)
            assertEquals("Blocked.us", matcher.overview().holds.single().channelId)
            assertEquals(1, held.candidates.size, "the candidate itself stays")
            matcher.decide(IptvOrgDecisionRequest("blocked channel", "dismiss"))
            assertTrue(matcher.enrich(candidates, keys).holds.isEmpty())
            matcher.decide(IptvOrgDecisionRequest("blocked channel", "clear"))
            assertEquals(1, matcher.enrich(candidates, keys).holds.size)
        }

    @Test fun `with the metadata off candidates pass through and nothing is proposed or held`() = providerTest(
        "iptv-org-",
    ) {
        val matcher = matcher(IptvOrgSettings(metadata = true))
        val off = IptvOrgMatcher(
            iptvOrgCatalog(Files.createTempDirectory("iptv-org-data"), { IptvOrgSettings() }),
            store,
            { IptvOrgSettings() },
            clock,
        )
        val candidates = listOf(candidate("Cable Thing", tvgId = "Blocked.us"))
        val result = off.enrich(candidates, keys)
        assertEquals(candidates, result.candidates)
        assertTrue(result.holds.isEmpty() && off.findings().isEmpty())
        assertEquals(1, matcher.enrich(candidates, keys).holds.size)
    }
}
