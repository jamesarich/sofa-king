package dev.jamesrich.sofaking.backend.iptvorg

import dev.jamesrich.sofaking.backend.providers.Fetched
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val STATE_FILE = "state.json"

/** What asking for one file came to. */
internal sealed interface Fetch {
    data object Unchanged : Fetch

    class Failed(val note: String) : Fetch

    class Rows(val response: Fetched, val count: Int) : Fetch
}

/**
 * [response]'s rows when it is a JSON array that is not empty and, for a [guarded] file, not under half of the
 * [previous] count; otherwise the failure that keeps the last copy.
 */
internal fun checkedRows(response: Fetched, guarded: Boolean, previous: Int): Fetch {
    val rows = runCatching { response.json() as? JsonArray }.getOrNull()
    return when {
        rows == null -> Fetch.Failed("not a JSON array")

        rows.isEmpty() || (guarded && rows.size * 2 < previous) ->
            Fetch.Failed("refused a download of ${rows.size} entries after $previous; kept the last copy")

        else -> Fetch.Rows(response, rows.size)
    }
}

/** The rows of the JSON array in [name], or none when the file is missing or is not one. */
private fun rows(directory: Path, name: String): List<JsonElement> {
    val path = directory.resolve(name)
    return if (Files.exists(path)) {
        runCatching { Json.parseToJsonElement(Files.readString(path)) as? JsonArray }.getOrNull().orEmpty()
    } else {
        emptyList()
    }
}

/** The parsed files of [directory]; whatever is missing is empty. */
internal fun readData(directory: Path): IptvOrgData = IptvOrgData(
    parseChannels(rows(directory, "channels.json")),
    parseLogos(rows(directory, "logos.json")),
    parseBlocklist(rows(directory, "blocklist.json")),
    parseTimezones(rows(directory, "timezones.json")),
    parseStreams(rows(directory, "streams.json")),
)

internal fun readState(directory: Path): IptvOrgState = runCatching {
    Json.decodeFromString<IptvOrgState>(Files.readString(directory.resolve(STATE_FILE)))
}.getOrDefault(IptvOrgState())

internal fun writeState(directory: Path, state: IptvOrgState) =
    writeAtomically(directory, STATE_FILE, Json.encodeToString(IptvOrgState.serializer(), state).toByteArray())

/** Writes [bytes] as [name] in [directory] through a temporary file, so a reader sees the old file or the new one. */
internal fun writeAtomically(directory: Path, name: String, bytes: ByteArray) {
    val temporary = Files.createTempFile(directory, "$name-", ".tmp")
    try {
        Files.write(temporary, bytes)
        Files.move(
            temporary,
            directory.resolve(name),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } finally {
        Files.deleteIfExists(temporary)
    }
}
