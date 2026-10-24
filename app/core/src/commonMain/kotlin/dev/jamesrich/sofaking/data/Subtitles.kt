package dev.jamesrich.sofaking.data

/** One subtitle, shown from [startMs] to [endMs] in the item's time; [text] keeps its line breaks and drops markup. */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** A subtitle track's cues in time order, drawn over the picture by the item's position. */
class Subtitles(cues: List<SubtitleCue>) {
    val cues: List<SubtitleCue> = cues.sortedBy { it.startMs }

    /** Every cue showing at [positionMs], one under the other; null when none is. */
    fun textAt(positionMs: Long): String? {
        // The first cue starting after the position; the ones showing are among the few before it.
        var low = 0
        var high = cues.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= positionMs) low = mid + 1 else high = mid
        }
        val showing = ArrayList<String>(2)
        var i = low - 1
        while (i >= 0 && low - i <= OVERLAP_LOOKBACK) {
            val cue = cues[i]
            if (cue.endMs > positionMs) showing.add(0, cue.text)
            i--
        }
        return showing.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private companion object {
        /** How many earlier cues may still be on screen at once, as overlapping ASS lines can be. */
        const val OVERLAP_LOOKBACK = 16
    }
}

/**
 * The cues of a WebVTT document; the header and NOTE, STYLE and REGION blocks are skipped. FFmpeg writes a subtitle
 * file's `<br>` at the start or end of a line as a blank line inside the cue, which ends a WebVTT cue, so a block
 * with no timing line after a cue is read as more of that cue's text rather than dropped.
 */
fun parseWebVtt(document: String): Subtitles {
    val cues = ArrayList<SubtitleCue>()
    var previous: Triple<Long, Long, String>? = null
    fun finish() {
        previous?.let { (startMs, endMs, raw) ->
            val text = cleanCueText(raw)
            if (text.isNotEmpty() && endMs > startMs) cues += SubtitleCue(startMs, endMs, text)
        }
        previous = null
    }
    for (block in document.replace("\r\n", "\n").replace('\r', '\n').split(BLANK_LINES)) {
        val lines = block.lines()
        val timing = lines.indexOfFirst { "-->" in it }
        if (timing < 0) {
            val first = lines.first().trimStart()
            val open = previous
            if (open != null && first.isNotEmpty() && SKIPPED_BLOCKS.none { first.startsWith(it) }) {
                previous = open.copy(third = open.third + "\n" + block)
            } else {
                finish()
            }
            continue
        }
        finish()
        val (start, end) = lines[timing].split("-->", limit = 2).map { it.trim().substringBefore(' ') }
        val startMs = vttTime(start) ?: continue
        val endMs = vttTime(end) ?: continue
        previous = Triple(startMs, endMs, lines.drop(timing + 1).joinToString("\n"))
    }
    finish()
    return Subtitles(cues)
}

/** `hh:mm:ss.mmm` or `mm:ss.mmm` in milliseconds; null when it is neither. */
internal fun vttTime(value: String): Long? {
    val parts = value.split(':')
    if (parts.size !in 2..3) return null
    val seconds = parts.last().split('.')
    if (seconds.size != 2) return null
    val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0
    val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
    val whole = seconds[0].toLongOrNull() ?: return null
    val millis = seconds[1].padEnd(3, '0').take(3).toLongOrNull() ?: return null
    return ((hours * 60 + minutes) * 60 + whole) * 1000 + millis
}

/**
 * A cue's text without markup, one line per row. Subtitle files often carry their HTML escaped (`&lt;i&gt;`), so
 * the tags are stripped again once the entities are read; a `<br>` breaks the line even when its `>` is missing.
 * Only the tag names of WebVTT and of subtitle HTML count as tags, so `<<Previously>>` and `x<y` stay as text.
 * Some muxed tracks cut each line at a fixed length, mid-tag, and replace its last character; the cut-off tag
 * (`help<?`, `most<br /s`), a stray `>` it leaves at the end of a line (`Ma></i>`) and a row of only punctuation
 * are dropped, and a break that lost its `<` (`that.Br />fertile`) still breaks the line.
 */
private fun cleanCueText(raw: String): String {
    fun String.untagged() = replace(BREAK, "\n").replace(HEADLESS_BREAK, "\n").replace(TAG, "")
    return raw.replace(CUT_TAG, "").replace(CUT_BARE_BREAK, "$1").untagged()
        .replace(STRAY_CLOSE, "$1")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&lrm;", "").replace("&rlm;", "")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
        .replace("&amp;", "&")
        .untagged()
        .lines()
        .map { it.trim() }
        // A cut line can leave a row of nothing but its replaced punctuation; `♪` and `-t.` are kept.
        .filter { line -> line.any { it !in ASCII_PUNCTUATION && !it.isWhitespace() } }
        .joinToString("\n")
}

private val BLANK_LINES = Regex("\n[ \t]*\n+")

/** Blocks that are not cue text: the header and WebVTT's comments and settings. */
private val SKIPPED_BLOCKS = listOf("WEBVTT", "NOTE", "STYLE", "REGION")

/** A line break tag in any spelling, with or without its closing `>`. */
private val BREAK = Regex("""<\s*br\b(?:[^<>\n]*>)?""", RegexOption.IGNORE_CASE)

/**
 * A tag cut off at the end of a line: `<`, a few characters of `br`, `b`, `/` or spaces, and one replaced character.
 */
private val CUT_TAG = Regex("""<[bBrR/ ]{0,5}[^<>\n\d]?$""", RegexOption.MULTILINE)

/** A cut break that lost both brackets, after the sentence it ends: `her?R /.` */
private val CUT_BARE_BREAK = Regex("""([.?!,])[Bb]?[Rr] ?/.?$""", RegexOption.MULTILINE)

/** A `<br />` that lost its `<` and maybe more: `Br />`, `R />` or ` />`. */
private val HEADLESS_BREAK = Regex("""(?:\b[Bb]?[Rr])? ?/>""")

private const val ASCII_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

/** A `>` left at the end of a line after a word, by a cut tag. */
private val STRAY_CLOSE = Regex("""(\w)>[ \t]*$""", RegexOption.MULTILINE)

/** A WebVTT or subtitle HTML tag, opening or closing, or a karaoke timestamp. */
private val TAG = Regex(
    """</?(?:i|b|u|s|c|v|lang|ruby|rt|font|span|em|strong|p|div)(?:[\s.][^<>\n]*)?\s*>|<\d+(?::\d+)+\.\d+>""",
    RegexOption.IGNORE_CASE,
)
