package dev.jamesrich.sofaking.backend.library

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Where the indexes of subtitle files beside a video start, after any stream index a file has. */
internal const val SIDECAR_BASE = 1000

/** ISO 639-1 and 639-2 codes. */
private val LANGUAGE_CODE_LENGTHS = 2..3

/** A subtitle file's codec by extension: text that FFmpeg reads, or pictures (`.sup` for PGS, `.idx` for VobSub). */
private val SIDECAR_CODECS = mapOf(
    "srt" to "subrip",
    "vtt" to "webvtt",
    "ass" to "ass",
    "ssa" to "ssa",
    "sup" to "hdmv_pgs_subtitle",
    "idx" to "dvd_subtitle",
)

/** Language names people put in subtitle file names, as ISO 639 codes. */
private val LANGUAGE_NAMES = mapOf(
    "english" to "eng", "french" to "fre", "francais" to "fre", "german" to "ger", "deutsch" to "ger",
    "spanish" to "spa", "espanol" to "spa", "italian" to "ita", "portuguese" to "por", "dutch" to "dut",
    "swedish" to "swe", "norwegian" to "nor", "danish" to "dan", "finnish" to "fin", "polish" to "pol",
    "russian" to "rus", "japanese" to "jpn", "korean" to "kor", "chinese" to "chi", "arabic" to "ara",
    "hebrew" to "heb", "hindi" to "hin", "turkish" to "tur", "greek" to "gre", "czech" to "cze", "hungarian" to "hun",
)

/** Name parts that mark a subtitle file for the deaf and hard of hearing. */
private val HEARING_IMPAIRED = setOf("sdh", "cc", "hi")

/**
 * A subtitle file beside a video, named as Plex and Jellyfin read them: the video's name, then optional parts such
 * as a language, `forced`, `default` or `sdh`, then the extension, as in `Movie (2020).en.forced.srt`.
 */
data class SidecarSubtitle(val file: Path, val stream: SubtitleStream)

/**
 * The subtitle files beside [video] in its folder, in name order, numbered from [SIDECAR_BASE]. A VobSub pair counts
 * once, by its `.idx`. A folder that cannot be read has none.
 */
internal fun sidecarSubtitles(video: Path): List<SidecarSubtitle> {
    val folder = video.parent ?: return emptyList()
    val base = video.fileName.toString().substringBeforeLast('.')
    val files = try {
        Files.list(folder).use { paths ->
            paths.filter { path ->
                val name = path.fileName.toString()
                name.startsWith("$base.") && name.substringAfterLast('.').lowercase() in SIDECAR_CODECS &&
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            }.toList()
        }
    } catch (_: IOException) {
        return emptyList()
    }
    return files.sortedBy { it.fileName.toString() }.mapIndexed { i, file ->
        SidecarSubtitle(file, sidecarStream(SIDECAR_BASE + i, file.fileName.toString().removePrefix("$base.")))
    }
}

/** What a subtitle file's name after the video's says about it, such as `en.forced.srt`; [index] numbers it. */
internal fun sidecarStream(index: Int, suffix: String): SubtitleStream {
    val parts = suffix.split('.')
    val codec = SIDECAR_CODECS.getValue(parts.last().lowercase())
    var language: String? = null
    var forced = false
    var default = false
    val title = mutableListOf<String>()
    for (part in parts.dropLast(1).filter { it.isNotBlank() }) {
        val word = part.lowercase()
        when {
            word == "forced" || word == "foreign" -> forced = true

            word == "default" -> default = true

            word in HEARING_IMPAIRED -> title += "SDH"

            language == null && word in LANGUAGE_NAMES -> language = LANGUAGE_NAMES.getValue(word)

            language == null && word.length in LANGUAGE_CODE_LENGTHS && word.all { it in 'a'..'z' } -> language = word

            // A region such as `pt-BR` keeps its language.
            language == null && REGIONAL.matches(word) -> language = word.substringBefore('-')

            else -> title += part
        }
    }
    return SubtitleStream(index, codec, language, title.joinToString(" ").ifEmpty { null }, default, forced)
}

private val REGIONAL = Regex("[a-z]{2,3}-[a-z0-9]{2,4}")
