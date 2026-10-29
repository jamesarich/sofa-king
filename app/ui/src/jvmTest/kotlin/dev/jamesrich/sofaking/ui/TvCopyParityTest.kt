package dev.jamesrich.sofaking.ui

import dev.jamesrich.sofaking.TvCopy
import dev.jamesrich.sofaking.TvRecordingCopy
import dev.jamesrich.sofaking.TvSearchCopy
import dev.jamesrich.sofaking.TvSpokenCopy
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvCopyParityTest {
    private val values = File("src/commonMain/composeResources/values")

    /** Resource name to why the Apple TV app words it differently on purpose. */
    private val deliberate = mapOf(
        "guide_favorites_none" to "The Siri Remote has a touch surface where the other apps have OK",
        "settings_pairing_help" to "No QR scan on tvOS, and the address field is on the same page",
    )

    private val twins get() = TvCopy.twins + TvRecordingCopy.twins + TvSearchCopy.twins + TvSpokenCopy.twins

    private fun resources(): Map<String, String> = values.listFiles { file -> file.extension == "xml" }.orEmpty()
        .flatMap { file -> STRING.findAll(file.readText()).map { it.groupValues[1] to unescape(it.groupValues[2]) } }
        .toMap()

    private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    @Test
    fun `every copy entry names a Compose string`() {
        val resources = resources()
        assertTrue(resources.isNotEmpty(), "no string resources under ${values.absolutePath}")
        assertEquals(emptyList(), twins.keys.filter { it !in resources })
    }

    @Test
    fun `copy entries read the same as their Compose strings`() {
        val resources = resources()
        val drifted = twins.filter { (key, text) -> key !in deliberate && resources[key] != text }
            .map { (key, text) -> "$key: Compose says '${resources[key]}', the Apple TV app '$text'" }
        assertEquals(emptyList(), drifted)
    }

    @Test
    fun `every deliberate difference still differs`() {
        val resources = resources()
        val copy = twins
        val stale = deliberate.keys.filter { key -> copy[key] == null || copy[key] == resources[key] }
        assertEquals(emptyList(), stale)
    }

    private companion object {
        val STRING = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    }
}
