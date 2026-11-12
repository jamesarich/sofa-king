package dev.jamesrich.sofaking.ui

import dev.jamesrich.sofaking.contract.Wire
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reads the Kotlin sources of `core` and `ui`: a code the contract names is its `Wire` constant, never a literal. */
class WireLiteralsTest {
    private fun constants(holder: Any): Set<String> = holder.javaClass.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.get(null) as String }
        .toSet()

    @Test
    fun `a code the contract names is never written as a string literal`() {
        val codes = constants(Wire.ErrorCode) + constants(Wire.RecordingReason)
        assertTrue(Wire.ErrorCode.CHANNEL_NOT_FOUND in codes && Wire.RecordingReason.MISSED in codes)
        val sources = mainSources(File("src")) + mainSources(File("../core/src"))
        assertTrue(sources.size > MIN_SOURCES, "only ${sources.size} sources from ${File("").absolutePath}")
        val literals = sources.flatMap { file ->
            file.readLines().withIndex()
                .filterNot { (_, line) -> COMMENT.containsMatchIn(line) }
                .flatMap { (index, line) ->
                    LITERAL.findAll(line).map { it.groupValues[1] }.filter { it in codes }
                        .map { "${file.name}:${index + 1} \"$it\"" }
                }
        }
        assertEquals(emptyList(), literals, "use the Wire constant")
    }

    private companion object {
        const val MIN_SOURCES = 300
        val COMMENT = Regex("""^\s*(\*|/\*|//)""")
        val LITERAL = Regex(""""([A-Z0-9_]+)"""")
    }
}
