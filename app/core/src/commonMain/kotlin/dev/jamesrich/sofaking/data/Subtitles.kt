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

/** The cues of a WebVTT document; blocks without a timing line (the header, NOTE, STYLE) are skipped. */
fun parseWebVtt(document: String): Subtitles {
    val cues = document.replace("\r\n", "\n").replace('\r', '\n').split(BLANK_LINES).mapNotNull { block ->
        val lines = block.lines()
        val timing = lines.indexOfFirst { "-->" in it }
        if (timing < 0) return@mapNotNull null
        val (start, end) = lines[timing].split("-->", limit = 2).map { it.trim().substringBefore(' ') }
        val startMs = vttTime(start) ?: return@mapNotNull null
        val endMs = vttTime(end) ?: return@mapNotNull null
        val text = lines.drop(timing + 1).joinToString("\n") { cleanCueText(it) }.trim()
        if (text.isEmpty() || endMs <= startMs) null else SubtitleCue(startMs, endMs, text)
    }
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

private fun cleanCueText(line: String): String = line
    .replace(TAG, "")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&lrm;", "").replace("&rlm;", "")
    .replace("&amp;", "&")

private val BLANK_LINES = Regex("\n[ \t]*\n+")
private val TAG = Regex("<[^>]*>")
