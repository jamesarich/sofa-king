package dev.jamesrich.sofaking.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reads the module's imports: the packages under `ui` never depend on each other in a cycle. */
class PackageCyclesTest {
    @Test
    fun `no package under ui imports its way back to itself`() {
        val graph = imports()
        assertTrue(FEATURES.all { it in graph }, "found only ${graph.keys} under ${File("src").absolutePath}")
        assertEquals(emptyList(), graph.keys.sorted().mapNotNull { cycleFrom(it, graph) })
    }

    @Test
    fun `a cycle is reported with its path`() {
        val graph = mapOf("guide" to setOf("player"), "player" to setOf("preview"), "preview" to setOf("guide"))
        assertEquals("guide -> player -> preview -> guide", cycleFrom("guide", graph))
        assertEquals(null, cycleFrom("guide", mapOf("guide" to setOf("player"), "player" to emptySet())))
    }

    /** Each package under `ui` and the other ones its files import, across every source set but the tests. */
    private fun imports(): Map<String, Set<String>> {
        val graph = mutableMapOf<String, MutableSet<String>>()
        mainSources(File("src")).forEach { file ->
            val text = file.readText()
            val from = PACKAGE.find(text)?.groupValues?.get(1) ?: return@forEach
            val to = IMPORT.findAll(text).map { it.groupValues[1] }.filter { it != from }
            graph.getOrPut(from) { mutableSetOf() } += to
        }
        return graph
    }

    private fun cycleFrom(start: String, graph: Map<String, Set<String>>): String? {
        fun walk(at: String, path: List<String>): String? = graph[at].orEmpty().sorted().firstNotNullOfOrNull { next ->
            when (next) {
                start -> (path + next).joinToString(" -> ")
                in path -> null
                else -> walk(next, path + next)
            }
        }
        return walk(start, listOf(start))
    }

    private companion object {
        val FEATURES = listOf("guide", "library", "player", "preview", "recordings", "settings")
        val PACKAGE = Regex("""^package dev\.jamesrich\.sofaking\.ui\.([a-z]+)""", RegexOption.MULTILINE)
        val IMPORT = Regex("""^import dev\.jamesrich\.sofaking\.ui\.([a-z]+)\.""", RegexOption.MULTILINE)
    }
}

/** The Kotlin files of every source set under [src] whose name does not end in `Test`. */
internal fun mainSources(src: File): List<File> = src.listFiles { dir -> dir.isDirectory && !dir.name.endsWith("Test") }
    .orEmpty()
    .flatMap { set -> set.walkTopDown().filter { it.extension == "kt" } }
