package dev.jamesrich.sofaking.ui.theme

import dev.jamesrich.sofaking.ui.mainSources
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reads the Kotlin sources of `ui`: a screen takes its colors, shapes, type and motion from the theme and keeps its
 * spacing on the grid, as docs/design-system.md says. Each rule lists the files it leaves alone and why.
 */
class DesignSystemTest {
    private val sources = mainSources(File("src"))
    private val themeDir = File("src/commonMain/kotlin/dev/jamesrich/sofaking/ui/theme")
    private val palette = PALETTE_NAME.findAll(File(themeDir, "Color.kt").readText()).map { it.groupValues[1] }.toSet()

    private fun File.inTheme() = parentFile.name == "theme"

    /** The lines of every source outside [skipped] that are not comments or imports, as `File.kt:12` and the line. */
    private fun code(skipped: (File) -> Boolean = { false }): List<Pair<String, String>> = sources
        .filterNot(skipped)
        .flatMap { file ->
            file.readLines().withIndex()
                .filterNot { (_, line) -> COMMENT.containsMatchIn(line) || line.startsWith("import ") }
                .map { (index, line) -> "${file.name}:${index + 1}" to line }
        }

    private fun offenders(pattern: Regex, skipped: (File) -> Boolean = { false }): List<String> =
        code(skipped).filter { (_, line) -> pattern.containsMatchIn(line) }.map { (at, line) -> "$at ${line.trim()}" }

    @Test
    fun `the sources and the palette are where this test looks for them`() {
        assertTrue(sources.size > MIN_SOURCES, "only ${sources.size} sources from ${File("").absolutePath}")
        assertTrue("SofaPlum" in palette && "PrevueBlue" in palette && OVER_PICTURE.all { it in palette }, "$palette")
    }

    @Test
    fun `a color value is written only in the palette`() {
        assertEquals(emptyList(), offenders(COLOR_LITERAL) { it.name == "Color.kt" && it.inTheme() })
    }

    @Test
    fun `outside the theme only the over-picture colors are named`() {
        // The break screen paints the logo's own colors: the sofa's lavender, hue-turned at each bounce.
        val named = code { it.inTheme() || it.name == "BreakScreen.kt" }.flatMap { (at, line) ->
            WORD.findAll(line).map { it.value }.filter { it in palette && it !in OVER_PICTURE }.map { "$at $it" }
        }
        assertEquals(emptyList(), named, "use MaterialTheme.colorScheme, or add an over-picture color to Color.kt")
    }

    @Test
    fun `black and white come from the over-picture colors`() {
        // Alpha masks, an icon's path fill and the caption standard's own colors are not the app's inks.
        val allowed = setOf("FadingEdges.kt", "ItemSummary.kt", "SofaIcons.kt", "Captions.kt", "Captions.android.kt")
        assertEquals(emptyList(), offenders(NAMED_COLOR) { it.name in allowed }, "use SofaScrim or SofaOnPicture")
    }

    @Test
    fun `the schemes and the theme are built only in the theme`() {
        assertEquals(emptyList(), offenders(THEME_BUILDER) { it.inTheme() }, "use SofaKingTheme or OverVideo")
    }

    @Test
    fun `corners come from the theme's shapes`() {
        assertEquals(emptyList(), offenders(CORNER_SHAPE), "use MaterialTheme.shapes or a component's default shape")
    }

    @Test
    fun `text sizes come from the type scale`() {
        // Captions and subtitles draw at the size the stream or the viewer's caption settings give.
        assertEquals(emptyList(), offenders(SP_LITERAL), "use MaterialTheme.typography")
        assertEquals(emptyList(), offenders(TEXT_STYLE) { it.name == "Captions.kt" }, "copy a typography style")
    }

    @Test
    fun `a duration has a name`() {
        assertEquals(emptyList(), offenders(DURATION_LITERAL), "use MaterialTheme.motionScheme, or name the constant")
    }

    @Test
    fun `spacing stays on the grid`() {
        val strays = code().flatMap { (at, line) ->
            SPACING_CALL.findAll(line).flatMap { call ->
                DP.findAll(arguments(line, call.range.last)).map { it.groupValues[1] }
                    .filterNot(::onGrid)
                    .map { "$at ${it}dp" }
            }
        }
        assertEquals(emptyList(), strays, "padding and gaps are a multiple of 4dp, or 1, 2 or 6dp in a badge or pill")
    }

    @Test
    fun `the grid takes its steps and refuses the ones between`() {
        assertTrue(listOf("0", "1", "2", "4", "6", "8", "12", "16", "24", "28", "48").all(::onGrid))
        assertTrue(listOf("3", "5", "10", "14", "18", "22", "2.5").none(::onGrid))
        val line = "x.padding(horizontal = 16.dp, vertical = 6.dp).size(3.dp)"
        assertEquals("horizontal = 16.dp, vertical = 6.dp", arguments(line, line.indexOf('(')))
    }

    @Test
    fun `the Apple apps wear the palette's own values`() {
        fun values(pattern: Regex, file: File) = pattern.findAll(file.readText()).map { it.groupValues[1].uppercase() }
        val values = values(COLOR_VALUE, File(themeDir, "Color.kt")).toSet()
        val swift = values(SWIFT_HEX, File("../appleApp/Shared/Brand.swift")).toList()
        assertTrue(swift.size >= MIN_SWIFT_COLORS, "found only $swift in Brand.swift")
        assertEquals(emptyList(), swift.filterNot { it in values }, "Brand.swift copies ui/theme/Color.kt")
    }

    private fun onGrid(dp: String): Boolean {
        val value = dp.toIntOrNull() ?: return false
        return value in DENSE_STEPS || value % GRID == 0
    }

    /** The text between the parenthesis at [open] in [line] and the one that closes it, or the line's end. */
    private fun arguments(line: String, open: Int): String {
        var depth = 0
        for (index in open until line.length) {
            when (line[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return line.substring(open + 1, index)
            }
        }
        return line.substring(open + 1)
    }

    private companion object {
        const val MIN_SOURCES = 200
        const val MIN_SWIFT_COLORS = 8
        const val GRID = 4
        val DENSE_STEPS = setOf(1, 2, 6)
        val OVER_PICTURE = setOf(
            "SofaScrim",
            "SofaOverlayScrim",
            "SofaOverlayScrimDense",
            "SofaOverlayTrack",
            "SofaOnPicture",
            "SofaOnPictureAccent",
        )
        val COMMENT = Regex("""^\s*(\*|/\*|//)""")
        val WORD = Regex("""\b[A-Z][A-Za-z]+\b""")
        val PALETTE_NAME = Regex("""^val ([A-Z][A-Za-z]+) = """, RegexOption.MULTILINE)
        val COLOR_VALUE = Regex("""Color\(0xFF([0-9A-Fa-f]{6})\)""")
        val SWIFT_HEX = Regex("""hex: 0x([0-9A-Fa-f]{6})\)""")
        val COLOR_LITERAL = Regex("""\bColor\(\s*\d""")
        val NAMED_COLOR =
            Regex("""\bColor\.(Black|White|Red|Green|Blue|Gray|DarkGray|LightGray|Yellow|Cyan|Magenta)\b""")
        val THEME_BUILDER = Regex("""\b(MaterialTheme|MaterialExpressiveTheme|darkColorScheme|lightColorScheme)\(""")
        val CORNER_SHAPE = Regex("""\b(Absolute)?(Rounded|Cut)CornerShape\(""")
        val SP_LITERAL = Regex("""\b\d+(\.\d+)?\.sp\b""")
        val TEXT_STYLE = Regex("""\bTextStyle\(""")
        val DURATION_LITERAL = Regex("""\b(tween|snap)(<[^>]*>)?\(\s*\d|\b(durationMillis|delayMillis)\s*=\s*\d""")
        val SPACING_CALL = Regex("""\b(padding|absolutePadding|spacedBy|PaddingValues|Spacer)\(""")
        val DP = Regex("""(\d+(?:\.\d+)?)\.dp\b""")
    }
}
