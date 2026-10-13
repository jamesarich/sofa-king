package dev.jamesrich.sofaking.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceStringsTest {
    private val values = File("src/commonMain/composeResources/values")

    @Test
    fun `strings carry no Android escapes since Compose resources show them literally`() {
        val files = values.listFiles { file -> file.extension == "xml" }.orEmpty()
        assertTrue(files.isNotEmpty(), "no string resources under ${values.absolutePath}")
        val escaped = files.flatMap { file ->
            file.readLines().filter { "\\'" in it || "\\\"" in it }.map { "${file.name}: ${it.trim()}" }
        }
        assertEquals(emptyList(), escaped)
    }
}
