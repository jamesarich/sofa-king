package dev.jamesrich.sofaking.data

// Choices every library screen makes the same way, the Compose app's and the Apple TV app's.

/** What the details page's first button does. */
sealed interface PrimaryAction {
    val item: MediaItem

    /** Plays [item] from the start. */
    data class Play(override val item: MediaItem) : PrimaryAction

    /** Picks [item] up where it stopped. */
    data class Resume(override val item: MediaItem, val positionMs: Long) : PrimaryAction
}

/**
 * The page's first button: the item itself when it plays, resumed when it is in progress; for a show or a season
 * the episode the server names next, resumed the same way. Null when there is nothing to play yet.
 */
fun primaryAction(detail: MediaItemDetail): PrimaryAction? {
    val target = if (detail.item.kind.playable) detail.item else detail.next?.takeIf { it.kind.playable } ?: return null
    val progress = target.progress
    return if (progress != null && progress.inProgress) {
        PrimaryAction.Resume(target, progress.positionMs)
    } else {
        PrimaryAction.Play(target)
    }
}

/**
 * The season a show's page opens on: the one holding the next episode, else the first with something unwatched,
 * else the first. Null for a page without seasons.
 */
fun initialSeasonId(detail: MediaItemDetail): String? {
    val seasons = detail.children.filter { it.kind == ItemKind.SEASON }
    if (seasons.isEmpty()) return null
    val nextSeason = detail.next?.parentId
    return seasons.firstOrNull { it.id == nextSeason }?.id
        ?: seasons.firstOrNull { it.unwatched > 0 }?.id
        ?: seasons.first().id
}

/** The playable item after [id] in [children], a season's episodes in order; null when [id] is the last or absent. */
fun episodeAfter(children: List<MediaItem>, id: String): MediaItem? {
    val episodes = children.filter { it.kind.playable }
    val at = episodes.indexOfFirst { it.id == id }
    return if (at < 0) null else episodes.getOrNull(at + 1)
}

/** The season after [seasonId] among a show's [children], skipping Specials, which the viewer picks by hand. */
fun seasonAfter(children: List<MediaItem>, seasonId: String): MediaItem? {
    val seasons = children.filter { it.kind == ItemKind.SEASON }
    val at = seasons.indexOfFirst { it.id == seasonId }
    if (at < 0) return null
    return seasons.drop(at + 1).firstOrNull { it.season != 0 }
}

/**
 * Whether Hide spoilers keeps [item]'s overview back and blurs its still: an episode not started yet. One watched, or
 * stopped part way, shows as usual.
 */
fun hidesSpoilers(item: MediaItem): Boolean =
    item.kind == ItemKind.EPISODE && item.progress.let { it == null || (!it.watched && !it.inProgress) }

/** The subtitle tracks the server serves as text, which a player can draw; picture tracks (PGS, DVD) are left out. */
fun textSubtitles(detail: MediaItemDetail): List<MediaTrackInfo> = detail.subtitles.filterNot(::isPictureSubtitle)

/**
 * True for a picture subtitle track (PGS, DVD, DVB), which has no text: the server draws it into the picture when
 * the session asks for it, so picking one plays the item again.
 */
fun isPictureSubtitle(track: MediaTrackInfo): Boolean = track.codec !in TEXT_SUBTITLE_CODECS

/**
 * Where the server numbers the subtitle files beside a video, such as the ones Bazarr
 * downloads, after the file's own streams.
 */
const val SUBTITLE_FILE_BASE = 1000

/** True for a subtitle file beside the video rather than a stream inside it. */
fun isSubtitleFile(track: MediaTrackInfo): Boolean = track.index >= SUBTITLE_FILE_BASE

/**
 * The subtitles on when an item starts: a forced track, which carries only the dialogue the audio does not, text
 * before pictures (which the server has to draw in), else none.
 */
fun initialSubtitle(detail: MediaItemDetail): MediaTrackInfo? =
    detail.subtitles.filter { it.forced }.minByOrNull { isPictureSubtitle(it) }

/** The audio track playing when none is picked: the file's default, else its first. */
fun defaultAudio(detail: MediaItemDetail): MediaTrackInfo? =
    detail.audio.firstOrNull { it.default } ?: detail.audio.firstOrNull()

/**
 * A track as a picker lists it: its language and its own title, with an audio track's codec and channels, such as
 * "English · Commentary · AC3 5.1", and "External" for a subtitle file beside the video. [number] names a track with
 * nothing else to go by.
 */
fun trackLabel(track: MediaTrackInfo, number: Int): String {
    val language = languageKey(track.language)?.let { LANGUAGES[it] ?: it.uppercase() }
    val title = track.title?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }
    val format = track.channels?.let { "${track.codec.uppercase()} ${channelLayout(it)}" }
    val parts = listOfNotNull(language, title, format, "External".takeIf { isSubtitleFile(track) })
    return if (parts.isEmpty()) "Track $number" else parts.joinToString(" · ")
}

private fun channelLayout(channels: Int): String = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels ch"
}

/**
 * What an item's page says about its file: the [sound] of the track it starts with, such as "Dolby Digital 5.1",
 * and the languages of its audio tracks and of its full subtitle tracks, by name, each once.
 */
data class FileFacts(val sound: String?, val audioLanguages: List<String>, val subtitleLanguages: List<String>)

fun fileFacts(detail: MediaItemDetail): FileFacts = FileFacts(
    sound = defaultAudio(detail)?.let { track ->
        listOfNotNull(codecName(track.codec), track.channels?.let(::channelLayout)).joinToString(" ")
    },
    audioLanguages = languageNames(detail.audio),
    subtitleLanguages = languageNames(detail.subtitles.filterNot { it.forced }),
)

/** Tags for no language, several or an unknown one, which name nothing worth listing. */
private val NO_LANGUAGE = setOf("und", "zxx", "mul", "mis")

private fun codecName(codec: String): String = when (codec.lowercase()) {
    "ac3" -> "Dolby Digital"
    "eac3" -> "Dolby Digital Plus"
    "truehd" -> "Dolby TrueHD"
    "opus" -> "Opus"
    else -> codec.uppercase()
}

private fun languageNames(tracks: List<MediaTrackInfo>): List<String> = tracks.mapNotNull { track ->
    val key = languageKey(track.language)?.takeIf { it !in NO_LANGUAGE } ?: return@mapNotNull null
    LANGUAGES[key] ?: key.uppercase()
}.distinct()

/**
 * The audio track an item starts with: one in the language the viewer picked in its show ([choice]), the file's
 * default among them first, else the file's default.
 */
fun initialAudio(detail: MediaItemDetail, choice: TrackChoice?): MediaTrackInfo? {
    val language = choice?.audioLanguage ?: return defaultAudio(detail)
    val matching = detail.audio.filter { sameLanguage(it.language, language) }
    return matching.firstOrNull { it.default } ?: matching.firstOrNull() ?: defaultAudio(detail)
}

/**
 * The subtitles on when an item starts: with subtitles on in the viewer's [choice] for its show, a full track in
 * that language (text before pictures, and a subtitle file before the file's own stream, since one beside the video
 * was usually fetched to replace it); otherwise as [initialSubtitle] decides.
 */
fun initialSubtitle(detail: MediaItemDetail, choice: TrackChoice?): MediaTrackInfo? {
    if (choice == null || !choice.subtitles) return initialSubtitle(detail)
    return detail.subtitles.filter { !it.forced && sameLanguage(it.language, choice.subtitleLanguage) }
        .minWithOrNull(compareBy<MediaTrackInfo> { isPictureSubtitle(it) }.thenBy { !isSubtitleFile(it) })
        ?: initialSubtitle(detail)
}

/**
 * What the viewer picked, by language: the audio track [audioIndex] and the subtitle track [subtitleIndex], or none.
 */
fun trackChoice(detail: MediaItemDetail, audioIndex: Int?, subtitleIndex: Int?): TrackChoice {
    val subtitle = subtitleIndex?.let { index -> detail.subtitles.firstOrNull { it.index == index } }
    return TrackChoice(
        audioLanguage = detail.audio.firstOrNull { it.index == audioIndex }?.language?.lowercase(),
        subtitles = subtitle != null,
        subtitleLanguage = subtitle?.language?.lowercase(),
    )
}

/** True when two language tags name the same language: `en`, `eng`, or `fre` and `fra`; two missing tags match. */
internal fun sameLanguage(a: String?, b: String?): Boolean = languageKey(a) == languageKey(b)

private fun languageKey(tag: String?): String? {
    val code = tag?.lowercase()?.substringBefore('-')?.ifEmpty { null } ?: return null
    return TWO_LETTER[code] ?: TERMINOLOGY[code] ?: code
}

/** ISO 639-1 codes, as subtitle file names carry them, as the ISO 639-2 codes files carry. */
private val TWO_LETTER = mapOf(
    "en" to "eng", "es" to "spa", "fr" to "fre", "de" to "ger", "it" to "ita", "pt" to "por", "ja" to "jpn",
    "zh" to "chi",
    "ko" to "kor", "ru" to "rus", "nl" to "dut", "sv" to "swe", "no" to "nor", "da" to "dan", "fi" to "fin",
    "pl" to "pol",
    "ar" to "ara", "he" to "heb", "hi" to "hin", "tr" to "tur", "el" to "gre", "cs" to "cze", "hu" to "hun",
)

/** ISO 639-2 terminology codes as the bibliographic ones. */
private val TERMINOLOGY = mapOf(
    "fra" to "fre",
    "deu" to "ger",
    "zho" to "chi",
    "nld" to "dut",
    "ces" to "cze",
    "ell" to "gre",
)

/** Subtitle codecs the server converts to WebVTT. */
private val TEXT_SUBTITLE_CODECS = setOf("subrip", "srt", "ass", "ssa", "mov_text", "webvtt", "text")

/** English names for the ISO 639-2 codes files carry most, by the bibliographic code [languageKey] answers. */
private val LANGUAGES = mapOf(
    "eng" to "English", "spa" to "Spanish", "fre" to "French", "ger" to "German", "ita" to "Italian",
    "por" to "Portuguese",
    "jpn" to "Japanese", "chi" to "Chinese", "kor" to "Korean", "rus" to "Russian", "dut" to "Dutch",
    "swe" to "Swedish",
    "nor" to "Norwegian", "dan" to "Danish", "fin" to "Finnish", "pol" to "Polish", "tur" to "Turkish",
    "ara" to "Arabic",
    "hin" to "Hindi", "heb" to "Hebrew", "gre" to "Greek", "cze" to "Czech", "hun" to "Hungarian", "tha" to "Thai",
    "vie" to "Vietnamese", "ukr" to "Ukrainian", "und" to "Unknown",
)

/** The server's id for the row of items in progress and next episodes. */
const val CONTINUE_SHELF = "continue"
