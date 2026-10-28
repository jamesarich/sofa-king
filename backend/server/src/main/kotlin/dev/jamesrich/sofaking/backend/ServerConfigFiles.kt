package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.library.TmdbConfig
import java.nio.file.Files
import java.nio.file.Path

/** A database in [legacy], where earlier images kept it, and none in [directory] stops the start. */
internal fun requireNoLegacyDatabase(directory: Path, legacy: Path) {
    val legacyDatabase = legacy.resolve("backend.sqlite")
    require(
        directory.toAbsolutePath().normalize() == legacy.toAbsolutePath().normalize() ||
            Files.exists(directory.resolve("backend.sqlite")) || !Files.isRegularFile(legacyDatabase),
    ) {
        "$legacyDatabase exists but SOFA_DATA_DIR ($directory) holds no database. " +
            "Move the contents of $legacy into $directory, or set SOFA_DATA_DIR=$legacy"
    }
}

/** The files [ServerConfig.load] takes from the data [directory] when their variable is unset. */
internal class DataDirectoryFiles(private val directory: Path, private val log: (String) -> Unit) {
    /** The variables whose file was found in [directory]. */
    val found = mutableSetOf<String>()

    /** The file [name] [set], else [fileName] in [directory] when it exists; [what] names it in the log. */
    fun pick(name: String, set: Path?, fileName: String, what: String): Path? {
        val candidate = directory.resolve(fileName)
        return when {
            set != null -> set.also { log("config: $what file=$it from=$name") }

            Files.isRegularFile(candidate) -> candidate.also {
                found += name
                log("config: $what file=$it from=SOFA_DATA_DIR, $name is unset")
            }

            else -> null.also { log("config: no $what file; set $name or add $fileName to $directory") }
        }
    }
}

/** A token [SecretsFile] keeps: its key there, its variable, and what the log says once it is generated. */
private class StoredToken(val key: String, val variable: String, val generated: (Path) -> String)

private val STORED_TOKENS = listOf(
    StoredToken("apiToken", "SOFA_API_TOKEN") {
        "secrets: generated SOFA_API_TOKEN and stored it in $it; reveal it in the admin portal under Settings"
    },
    StoredToken("adminToken", "SOFA_ADMIN_TOKEN") {
        "secrets: generated SOFA_ADMIN_TOKEN and stored it in $it. To read it: " +
            "docker compose exec sofa-king-backend cat /config/secrets.json (outside Docker: cat $it). " +
            "Sign in at /admin with adminToken; Settings there reveals the API token"
    },
    StoredToken("outputToken", "SOFA_OUTPUT_TOKEN") {
        "secrets: generated SOFA_OUTPUT_TOKEN and stored it in $it; the portal's Plex page shows the player URLs " +
            "with it"
    },
)

/**
 * The tokens [base] leaves unset, by [SecretsFile] key, read from that file or generated into it, and the file;
 * nothing when every token is set. Each one taken marks its variable [SecretOrigin.GENERATED] in [origins].
 */
internal fun storedTokens(
    base: ServerConfig,
    origins: MutableMap<String, SecretOrigin>,
    log: (String) -> Unit,
): Pair<Map<String, String>, Path?> {
    val set = mapOf("apiToken" to base.apiToken, "adminToken" to base.adminToken, "outputToken" to base.outputs.token)
    val missing = STORED_TOKENS.filter { set[it.key] == null }
    if (missing.isEmpty()) return emptyMap<String, String>() to null
    val file = SecretsFile(base.dataDirectory.resolve(SecretsFile.NAME))
    val (values, generated) = file.resolve(missing.map { it.key })
    for (token in missing) {
        if (values[token.key] == null) continue
        origins[token.variable] = SecretOrigin.GENERATED
        log(if (token.key in generated) token.generated(file.path) else "secrets: ${token.variable} from ${file.path}")
    }
    return values to file.path
}

/** The TMDB token saved from the portal into [SecretsFile], marked [SecretOrigin.SAVED] in [origins]. */
internal fun savedTmdb(
    resolved: Map<String, String>,
    directory: Path,
    origins: MutableMap<String, SecretOrigin>,
    log: (String) -> Unit,
): TmdbConfig? {
    val file = SecretsFile(directory.resolve(SecretsFile.NAME))
    val token = file.stored(SecretsFile.TMDB_TOKEN) ?: return null
    origins["SOFA_TMDB_TOKEN"] = SecretOrigin.SAVED
    log("secrets: SOFA_TMDB_TOKEN from ${file.path}, saved from the portal")
    return tmdbConfig(EnvironmentReader(resolved + ("SOFA_TMDB_TOKEN" to token)))
}

/** The fanart.tv key saved from the portal into [SecretsFile], marked [SecretOrigin.SAVED] in [origins]. */
internal fun savedFanartKey(
    directory: Path,
    origins: MutableMap<String, SecretOrigin>,
    log: (String) -> Unit,
): String? {
    val file = SecretsFile(directory.resolve(SecretsFile.NAME))
    val key = file.stored(SecretsFile.FANART_KEY) ?: return null
    origins["SOFA_FANART_KEY"] = SecretOrigin.SAVED
    log("secrets: SOFA_FANART_KEY from ${file.path}, saved from the portal")
    return key
}

/** The ListenBrainz user token saved from the portal into [SecretsFile], marked [SecretOrigin.SAVED] in [origins]. */
internal fun savedListenBrainzToken(
    directory: Path,
    origins: MutableMap<String, SecretOrigin>,
    log: (String) -> Unit,
): String? {
    val file = SecretsFile(directory.resolve(SecretsFile.NAME))
    val token = file.stored(SecretsFile.LISTENBRAINZ_TOKEN) ?: return null
    origins["SOFA_LISTENBRAINZ_TOKEN"] = SecretOrigin.SAVED
    log("secrets: SOFA_LISTENBRAINZ_TOKEN from ${file.path}, saved from the portal")
    return token
}
