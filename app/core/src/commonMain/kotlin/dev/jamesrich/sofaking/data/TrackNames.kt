package dev.jamesrich.sofaking.data

// How tracks and their languages are named on screen, the Compose app's and the Apple TV app's alike.

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

/** What a track with this many audio channels is called. */
private val CHANNEL_LAYOUTS = mapOf(1 to "Mono", 2 to "Stereo", 6 to "5.1", 8 to "7.1")

internal fun channelLayout(channels: Int): String = CHANNEL_LAYOUTS[channels] ?: "$channels ch"

/** Tags for no language, several or an unknown one, which name nothing worth listing. */
private val NO_LANGUAGE = setOf("und", "zxx", "mul", "mis")

internal fun codecName(codec: String): String = when (codec.lowercase()) {
    "ac3" -> "Dolby Digital"
    "eac3" -> "Dolby Digital Plus"
    "truehd" -> "Dolby TrueHD"
    "opus" -> "Opus"
    else -> codec.uppercase()
}

internal fun languageNames(tracks: List<MediaTrackInfo>): List<String> = tracks.mapNotNull { track ->
    val key = languageKey(track.language)?.takeIf { it !in NO_LANGUAGE } ?: return@mapNotNull null
    LANGUAGES[key] ?: key.uppercase()
}.distinct()

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
