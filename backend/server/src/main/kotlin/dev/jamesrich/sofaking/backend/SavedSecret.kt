package dev.jamesrich.sofaking.backend

/**
 * A secret the admin API and portal save into `secrets.json`, where its environment variable wins over the saved
 * value. [noun] is also the request field and form field name, [prefix] starts its error codes (`TMDB_REJECTED`) and
 * [effect] is what the restart page says follows.
 */
enum class SavedSecret(
    val key: String,
    val service: String,
    val noun: String,
    val prefix: String,
    val prompt: String,
    val invalid: String,
    val effect: String,
) {
    TMDB(
        SecretsFile.TMDB_TOKEN,
        "TMDB",
        "token",
        "TMDB",
        "TMDB API read access token",
        "A TMDB API read access token or API key has at least 32 characters and no spaces",
        "The libraries scan again once the server is back and fill in details and artwork.",
    ),
    FANART(
        SecretsFile.FANART_KEY,
        "fanart.tv",
        "key",
        "FANART",
        "fanart.tv API key",
        "A fanart.tv API key has 32 characters and no spaces",
        "Music libraries scan again once the server is back and fill in artists' pictures and backdrops.",
    ),
    LISTENBRAINZ(
        SecretsFile.LISTENBRAINZ_TOKEN,
        "ListenBrainz",
        "token",
        "LISTENBRAINZ",
        "ListenBrainz user token",
        "A ListenBrainz user token is at least 32 characters with no spaces",
        "Music libraries scan again once the server is back and fill in artists' most played songs.",
    ),
    ;

    val variable = "SOFA_${prefix}_${noun.uppercase()}"

    /** The portal section and form this secret has on the Library page. */
    val anchor = prefix.lowercase()
}
