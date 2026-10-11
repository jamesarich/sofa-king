package dev.jamesrich.sofaking.backend.catalog

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class StationKeysTest {
    private val corpus: List<String> = String(Fixture.resource("policy/candidate-names.txt"), Charsets.UTF_8).lines().filter { it.isNotEmpty() }
    private val keys = StationKeys()

    private fun assertDistinct(vararg names: String) {
        val mapped = names.associateWith { keys.key(it) }
        assertEquals(names.size, mapped.values.toSet().size, "expected distinct keys: $mapped")
    }

    private fun assertMerged(vararg names: String) {
        val mapped = names.associateWith { keys.key(it) }
        assertEquals(1, mapped.values.toSet().size, "expected one key: $mapped")
    }

    @Test fun `the corpus is names only, sorted and distinct`() {
        assertTrue(corpus.size > 3000)
        assertEquals(corpus.sorted(), corpus)
        assertEquals(corpus.distinct(), corpus)
        assertTrue(corpus.none { "://" in it || '\t' in it })
    }

    @Test fun `near-namesakes keep their own keys`() {
        assertDistinct("Tennis Channel", "Tennis TV", "Tennis+")
        assertDistinct("Bloomberg TV", "Bloomberg TV+")
        assertDistinct("Pet Collective", "Pet Collective +")
        assertDistinct("MLB", "MLB Channel")
        assertDistinct("NFL Channel", "NFL Network")
        assertDistinct("CBS News", "CBS News 24/7")
    }

    @Test fun `articles, HD and spelling variants meet`() {
        assertMerged("The Bob Ross Channel", "Bob Ross Channel")
        assertMerged("BBC News HD", "BBC News")
        assertMerged("SWAC TV", "swac tv", "SWAC-TV", "SWAC  TV.")
        assertMerged("Café Pop", "CAFE POP", "Cafe_Pop")
    }

    @Test fun `letters of every script survive`() {
        val key = keys.key("МАТЧ! БОЕЦ Russia")
        assertEquals("матч боец russia", key)
        assertNotEquals("russia", key)
    }

    @Test fun `a trailing tv or channel always stays, so a key depends on the name alone`() {
        assertEquals("bob ross channel", keys.normalize("The Bob Ross Channel"))
        assertEquals("tennis channel", keys.normalize("Tennis Channel"))
        assertEquals("mlb channel", keys.normalize("MLB Channel"))
        assertEquals("bloomberg tv", keys.normalize("Bloomberg TV"))
        assertEquals("hd", baseName("HD"), "a lone word is never dropped")
        assertEquals("the", baseName("The"))
        assertEquals("at and t plus", baseName("AT&T+"))
    }

    @Test fun `aliases apply after normalization`() {
        val aliased = StationKeys(mapOf("espn 8" to "espn8"))
        assertEquals("espn8", aliased.key("ESPN 8"))
        assertEquals("espn8", aliased.key("ESPN8"))
        assertEquals("espn 8", aliased.normalize("ESPN 8"))
    }

    @Test fun `suggested aliases pair a bare name with its tv or channel spelling and spellings that differ by spaces`() {
        val suggested = suggestedAliases(listOf("bob ross", "bob ross channel", "espn 8", "espn8", "grio", "thegrio", "cheddar tv", "tennis channel", "tennis tv", "cbs news"))
        assertEquals(
            listOf("bob ross channel: possible alias of bob ross", "espn 8: possible alias of espn8", "grio: possible alias of thegrio"),
            suggested,
        )
        assertTrue(suggestedAliases(listOf("tennis channel", "tennis tv", "tennis plus")).isEmpty(), "two suffixes without the bare name suggest nothing")
    }

    @Test fun `version 2 drops every space, so spellings that differ only by spaces meet`() {
        val v2 = StationKeys(version = 2)
        assertEquals(v2.key("WeatherSpy"), v2.key("Weather Spy"))
        assertEquals(v2.key("123GO!"), v2.key("123 GO!"))
        assertEquals("bobrosschannel", v2.key("The Bob Ross Channel"))
        fun distinct(vararg names: String) = assertEquals(names.size, names.map(v2::key).toSet().size, names.joinToString())
        distinct("Tennis Channel", "Tennis TV", "Tennis+")
        distinct("Bloomberg TV", "Bloomberg TV+")
        distinct("MLB", "MLB Channel")
        distinct("NFL Network", "NFL Channel")
        val merged = corpus.groupBy(v2::key).filterValues { names -> names.map(keys::key).toSet().size > 1 }
        assertTrue(merged.size > 20, "the corpus has spaces-only variants: ${merged.size}")
    }

    @Test fun `version 1 keys over the corpus are pinned`() = assertGolden(keys, "keys-v1.txt")

    @Test fun `version 2 keys over the corpus are pinned`() = assertGolden(StationKeys(version = 2), "keys-v2.txt")

    /** Any change to normalization shows up here as a diff of the golden file; delete it and rerun to accept one. */
    private fun assertGolden(keys: StationKeys, file: String) {
        val rendered = corpus.joinToString("\n", postfix = "\n") { "$it\t${keys.key(it)}" }
        val golden = Path.of("src/test/resources/policy/$file")
        if (!Files.exists(golden)) {
            Files.writeString(golden, rendered)
            fail("Wrote ${golden.toAbsolutePath()}; review it and run again")
        }
        val expected = Files.readString(golden)
        if (expected != rendered) {
            val before = expected.lines().toSet()
            val changed = rendered.lines().filter { it !in before }.take(20)
            fail("Normalization changed ${changed.size}+ keys, first: $changed")
        }
    }
}
