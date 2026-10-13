package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.jamesrich.sofaking.ui.formatTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CueStringTest {
    private val yellow = SpanStyle(color = Color.Yellow)

    @Test
    fun `every line is padded and boxed and a blank line keeps its row unboxed`() {
        val text = buildAnnotatedString { append("\nHELLO\nWORLD") }

        val shown = cueString(text, CaptionAppearance.DEFAULT)

        assertEquals("\n HELLO \n WORLD ", shown.text)
        val boxes = shown.spanStyles.filter { it.item.background == Color.Black }.map { it.start to it.end }
        assertEquals(listOf(1 to 8, 9 to 16), boxes)
    }

    @Test
    fun `stream styles move with the padding onto the right line`() {
        val text = buildAnnotatedString {
            append("ONE\nTW")
            withStyle(yellow) { append("O") }
        }

        val shown = cueString(text, CaptionAppearance.DEFAULT)

        val colored = shown.spanStyles.single { it.item.color == Color.Yellow }
        assertEquals("O", shown.text.substring(colored.start, colored.end))
    }

    @Test
    fun `the high-contrast style drops stream colors and keeps its box`() {
        val text = buildAnnotatedString { withStyle(yellow) { append("LOUD") } }

        val shown = cueString(text, CaptionAppearance.LARGE_HIGH_CONTRAST)

        assertTrue(shown.spanStyles.none { it.item.color == Color.Yellow })
        assertTrue(shown.spanStyles.any { it.item.background == Color.Black })
    }

    @Test
    fun `templates fill numbered placeholders and leave unknown ones`() {
        assertEquals("News 12", formatTemplate("%1\$s %2\$d", "News", 12))
        assertEquals("Today 1:00 PM - 2:30 PM", formatTemplate("%1\$s %2\$s - %3\$s", "Today", "1:00 PM", "2:30 PM"))
        assertEquals("a %2\$s", formatTemplate("%1\$s %2\$s", "a"))
    }
}
