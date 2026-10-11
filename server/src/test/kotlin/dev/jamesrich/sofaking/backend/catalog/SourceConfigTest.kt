package dev.jamesrich.sofaking.backend.catalog

import java.nio.file.Files
import kotlin.test.*

class SourceConfigTest {
    private fun load(text: String, environment: Map<String, String> = emptyMap()): SourceConfig {
        val file = Files.createTempFile("sofa-sources-", ".json")
        try {
            Files.writeString(file, text)
            return loadSources(file, environment)
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun `legacy provider array still loads and a guideUrl becomes an implicit guide`() {
        val config = load("""[{"id":"p","playlistUrl":"http://feeds.test/p.m3u","guideUrl":"http://feeds.test/p.xml","refreshSeconds":120}]""")
        assertEquals("p", config.providers.single().id)
        assertTrue(config.guides.isEmpty())
        val implicit = config.allGuides.single()
        assertEquals("p", implicit.id); assertEquals("http://feeds.test/p.xml", implicit.url); assertEquals(300, implicit.refreshSeconds)
    }

    @Test fun `object form carries independent guides and expands environment variables`() {
        val config = load(
            """{"providers":[{"id":"masq","playlistUrl":"http://192.168.1.3:3000/${'$'}{MASQ_PATH}.m3u","probe":true}],
               "guides":[{"id":"gracenote","url":"http://192.168.1.3:3000/x/epg/playlist.xml","refreshSeconds":3600},
                         {"id":"plex","url":"https://i.mjh.nz/Plex/us.xml.gz","headers":{"X-Key":"${'$'}{PLEX_KEY}"}}]}""",
            mapOf("MASQ_PATH" to "secret-token", "PLEX_KEY" to "k"),
        )
        assertEquals("http://192.168.1.3:3000/secret-token.m3u", config.providers.single().playlistUrl)
        assertEquals(listOf("gracenote", "plex"), config.guides.map { it.id })
        assertEquals(21_600, config.guides[1].refreshSeconds)
        assertEquals("k", config.guides[1].headers["X-Key"])
        assertEquals(config.guides, config.allGuides)
    }

    @Test fun `renewable query parameters are configurable per provider`() {
        val config = load("""[{"id":"p","playlistUrl":"http://feeds.test/p.m3u","renewableQuery":["Token","pl"]},{"id":"q","playlistUrl":"http://feeds.test/q.m3u"}]""")
        assertEquals(setOf("token", "pl"), config.providers[0].renewable)
        assertEquals(DEFAULT_RENEWABLE_QUERY, config.providers[1].renewable)
        assertFailsWith<IllegalArgumentException> { load("""[{"id":"p","playlistUrl":"http://feeds.test/p.m3u","renewableQuery":["a b"]}]""") }
    }

    @Test fun `unset variables duplicate ids and short guide intervals are rejected`() {
        assertFailsWith<IllegalArgumentException> { load("""[{"id":"p","playlistUrl":"http://feeds.test/${'$'}{MISSING}.m3u"}]""") }
        assertFailsWith<IllegalArgumentException> {
            load("""{"providers":[{"id":"p","playlistUrl":"http://feeds.test/p.m3u","guideUrl":"http://feeds.test/p.xml"}],"guides":[{"id":"p","url":"http://feeds.test/g.xml"}]}""")
        }
        assertFailsWith<IllegalArgumentException> { load("""{"guides":[{"id":"g","url":"http://feeds.test/g.xml","refreshSeconds":60}]}""") }
        assertFailsWith<IllegalArgumentException> { load("""{"guides":[{"id":"g","url":"file:///etc/passwd"}]}""") }
        assertFailsWith<IllegalArgumentException> { load(""""just a string"""") }
    }
}
