package dev.jamesrich.sofaking.data

/** One line of a song's lyrics, sung from [atMs] into the song; null when the lyrics are not synced. */
data class LyricLine(val atMs: Long?, val text: String)

/** A song's lyrics: timed [lines] in time order when [synced], else its lines as written. */
class Lyrics(lines: List<LyricLine>) {
    val synced: Boolean = lines.isNotEmpty() && lines.all { it.atMs != null }
    val lines: List<LyricLine> = if (synced) lines.sortedBy { it.atMs } else lines.map { LyricLine(null, it.text) }

    /** The index of the line being sung at [positionMs]; -1 before the first, or when the lyrics are not synced. */
    fun lineAt(positionMs: Long): Int {
        if (!synced) return -1
        var low = 0
        var high = lines.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (lines[mid].atMs!! <= positionMs) low = mid + 1 else high = mid
        }
        return low - 1
    }
}

/** A `[mm:ss.xx]` time tag; minutes may run past 59, and the fraction may have one to three digits. */
private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")

/** A metadata tag such as `[ar:Band]` or `[offset:+250]`. */
private val META_TAG = Regex("""^\[([a-z#]+):([^\]]*)\]\s*$""", RegexOption.IGNORE_CASE)

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L

/**
 * The lyrics of an LRC document, as the server sends them: lines starting with one or more time tags are sung at
 * each of those times, `[offset:±ms]` moves them all (a positive offset sings them sooner), and other metadata tags
 * are skipped. A document with no time tags is plain lyrics, read line by line.
 */
fun parseLrc(document: String): Lyrics {
    var offsetMs = 0L
    val timed = ArrayList<LyricLine>()
    val plain = ArrayList<String>()
    for (raw in document.lineSequence()) {
        val line = raw.trim()
        val meta = META_TAG.find(line)
        if (meta != null && TIME_TAG.find(line) == null) {
            if (meta.groupValues[1].equals("offset", ignoreCase = true)) {
                offsetMs = meta.groupValues[2].trim().toLongOrNull() ?: offsetMs
            }
            continue
        }
        var rest = line
        val times = ArrayList<Long>()
        while (true) {
            val tag = TIME_TAG.find(rest)?.takeIf { it.range.first == 0 } ?: break
            val (minutes, seconds, fraction) = tag.destructured
            val ms = fraction.takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.toLong() ?: 0L
            times += (minutes.toLong() * SECONDS_PER_MINUTE + seconds.toLong()) * MS_PER_SECOND + ms
            rest = rest.substring(tag.range.last + 1).trimStart()
        }
        if (times.isEmpty()) {
            plain += line
        } else {
            times.forEach { timed += LyricLine((it - offsetMs).coerceAtLeast(0), rest) }
        }
    }
    if (timed.isNotEmpty()) return Lyrics(timed)
    // Plain lyrics keep their blank lines between verses, but not at the start or end.
    return Lyrics(plain.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }.map { LyricLine(null, it) })
}
