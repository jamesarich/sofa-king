package dev.jamesrich.sofaking.data

/**
 * The tracks a viewer picked in a show, by language, so its next episode starts with them, as Plex remembers them:
 * [audioLanguage] (null for the file's default), and [subtitleLanguage] when [subtitles] is on (null for a track
 * with no language).
 */
data class TrackChoice(
    val audioLanguage: String? = null,
    val subtitles: Boolean = false,
    val subtitleLanguage: String? = null,
) {
    internal fun encode(): String = listOf(
        audioLanguage.orEmpty(),
        if (subtitles) "1" else "0",
        subtitleLanguage.orEmpty(),
    ).joinToString(FIELD.toString())

    internal companion object {
        /** How many shows' choices are kept; the oldest go first. */
        const val KEPT = 200
        const val FIELD = '|'

        /** How many [FIELD]-separated fields an encoded choice has. */
        private const val FIELDS = 3

        fun decode(text: String): TrackChoice? = text.split(FIELD).takeIf { it.size == FIELDS }
            ?.let { (audio, subtitles, language) ->
                TrackChoice(audio.ifEmpty { null }, subtitles == "1", language.ifEmpty { null })
            }
    }
}
