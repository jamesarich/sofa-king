package dev.jamesrich.sofaking.backend.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A music file's tags as FFprobe names them, from the file and from its audio stream (where Ogg and Opus keep them);
 * [track] and [disc] are the numbers before any `/total`, and the gains ReplayGain's in dB.
 */
@Serializable
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val date: String? = null,
    val genres: List<String> = emptyList(),
    val compilation: Boolean = false,
    val trackGain: Double? = null,
    val albumGain: Double? = null,
)

/** The tags of an audio-only file: the container's, then its audio stream's for any the container lacks. */
internal fun audioTags(format: JsonObject?, stream: JsonObject?): AudioTags? {
    val tags = HashMap<String, String>()
    for (source in listOfNotNull(stream, format)) {
        val own = source["tags"] as? JsonObject ?: continue
        for ((key, value) in own) {
            val text = (value as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } ?: continue
            tags[TAG_ALIASES[key.lowercase()] ?: key.lowercase()] = text
        }
    }
    val found = AudioTags(
        title = tags["title"],
        artist = tags["artist"],
        albumArtist = tags["album_artist"],
        album = tags["album"],
        track = tags["track"]?.let(::leadingNumber),
        disc = tags["disc"]?.let(::leadingNumber),
        date = tags["date"],
        genres = tags["genre"]?.split(';', '\u0000')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
            .orEmpty(),
        compilation = tags["compilation"].let { it == "1" || it.equals("true", ignoreCase = true) },
        trackGain = tags["replaygain_track_gain"]?.let(::decibels),
        albumGain = tags["replaygain_album_gain"]?.let(::decibels),
    )
    return found.takeUnless { it == AudioTags() }
}

/** The names some taggers use for the tags FFprobe usually reports under the right-hand name. */
private val TAG_ALIASES = mapOf(
    "albumartist" to "album_artist",
    "album artist" to "album_artist",
    "tracknumber" to "track",
    "discnumber" to "disc",
    "year" to "date",
    "tcmp" to "compilation",
)

/** `3` of `3/12` or `03`; null when it does not start with a positive number. */
private fun leadingNumber(text: String): Int? = text.takeWhile { it.isDigit() }.toIntOrNull()?.takeIf { it > 0 }

/** `-6.54` of `-6.54 dB`. */
private fun decibels(text: String): Double? = text.removeSuffix("dB").trim().toDoubleOrNull()
