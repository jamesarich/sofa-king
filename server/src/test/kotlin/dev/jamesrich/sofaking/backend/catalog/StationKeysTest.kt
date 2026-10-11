package dev.jamesrich.sofaking.backend.catalog

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class StationKeysTest {
    private val corpus: List<String> = String(Fixture.resource("policy/candidate-names.txt"), Charsets.UTF_8).lines().filter { it.isNotEmpty() }
    private val keys = StationKeys(corpus)

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

    @Test fun `a trailing tv or channel goes only when nothing else carries the remainder`() {
        assertEquals("bob ross", StationKeys(listOf("The Bob Ross Channel")).normalize("The Bob Ross Channel"))
        assertEquals("tennis channel", StationKeys(listOf("Tennis Channel", "Tennis TV")).normalize("Tennis Channel"))
        assertEquals("tennis", StationKeys(listOf("Tennis Channel")).normalize("Tennis Channel"))
        assertEquals("mlb channel", StationKeys(listOf("MLB Channel")).normalize("MLB Channel"), "a remainder under five characters keeps its suffix")
        assertEquals("cbs news channel", StationKeys(listOf("CBS News", "CBS News Channel")).normalize("CBS News Channel"), "the bare remainder counts as another suffix")
        assertEquals("hd", baseName("HD"), "a lone word is never dropped")
        assertEquals("the", baseName("The"))
        assertEquals("at and t plus", baseName("AT&T+"))
    }

    @Test fun `aliases apply after normalization`() {
        val aliased = StationKeys(listOf("ESPN 8", "ESPN8"), mapOf("espn 8" to "espn8"))
        assertEquals("espn8", aliased.key("ESPN 8"))
        assertEquals("espn8", aliased.key("ESPN8"))
        assertEquals("espn 8", aliased.normalize("ESPN 8"))
    }

    /** Any change to normalization shows up here as a diff of this file; delete it and rerun to accept one. */
    @Test fun `version 1 keys over the corpus are pinned`() {
        val rendered = corpus.joinToString("\n", postfix = "\n") { "$it\t${keys.key(it)}" }
        val golden = Path.of("src/test/resources/policy/keys-v1.txt")
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
