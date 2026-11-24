package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.contract.Wire
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reads the server's sources: a code a client acts on comes from the contract, and every other code is listed here. */
class WireCodesTest {
    /**
     * The error codes only the server names. A code a client branches on goes in [Wire.ErrorCode] instead, and one no
     * call uses any more leaves this list.
     */
    private val serverOnly = sortedSetOf(
        // A saved secret's codes carry its service's prefix, as the call writes them.
        "\${secret.prefix}_FROM_ENVIRONMENT",
        "\${secret.prefix}_REJECTED",
        "\${secret.prefix}_UNREACHABLE",
        "CLIENT_MISMATCH",
        "CONFIRMATION_REQUIRED",
        "DATA_DIRECTORY_UNWRITABLE",
        "FILE_DELETE_REQUIRED",
        "FORMAT_UNSUPPORTED",
        "GUIDE_CHANNEL_NOT_FOUND",
        "GUIDE_NOT_FOUND",
        "HISTORY_NOT_AVAILABLE",
        "IDEMPOTENCY_CONFLICT",
        "INTERNAL_ERROR",
        "INVALID_MANIFEST",
        "INVALID_POLICY",
        "INVALID_QUERY",
        "INVALID_REQUEST",
        "INVALID_SOURCES",
        "IPTV_ORG_CHANNEL_NOT_FOUND",
        "ITEM_MISSING",
        "ITEM_NOT_FOUND",
        "JOB_NOT_FOUND",
        "LIBRARIES_NOT_AVAILABLE",
        "LIBRARY_EXISTS",
        "LIBRARY_NOT_FOUND",
        "LINEUP_ENTRY_EXISTS",
        "METHOD_NOT_ALLOWED",
        "MULTIVIEW_UNAVAILABLE",
        "NOTIFICATIONS_NOT_AVAILABLE",
        "NOTIFICATION_TARGET_NOT_FOUND",
        "NOT_FOUND",
        "NOT_READY",
        "NO_UPDATER",
        "OUTPUT_NOT_FOUND",
        "PLEX_NOT_CONFIGURED",
        "POLICY_CHANGED",
        "POLICY_EXISTS",
        "POLICY_MANAGED",
        "POLICY_NOT_CONFIGURED",
        "PRECONDITION_REQUIRED",
        "PROBES_OFF",
        "PROBE_RUNNING",
        "PROGRAM_ENDED",
        "PROGRAM_NOT_FOUND",
        "PROVIDER_NOT_FOUND",
        "RECORDINGS_NOT_AVAILABLE",
        "RECORDING_MISSING",
        "RECORDING_NOT_FOUND",
        "RECORDING_NOT_READY",
        "REQUEST_TOO_LARGE",
        "RESOURCE_NOT_FOUND",
        "RULE_NOT_FOUND",
        "SCHEDULE_FULL",
        "SECRETS_UNWRITABLE",
        "SETUP_UNAVAILABLE",
        "STATION_CONFLICT",
        "SUBTITLE_FAILED",
        "SUBTITLE_NOT_TEXT",
        "TOKEN_NOT_FOUND",
        "TRANSCODER_UNAVAILABLE",
        "UPSTREAM_REJECTED",
        "VIEWER_LIMIT",
        "VIEWER_NOT_FOUND",
        "WATCHLIST_ENTRY_NOT_FOUND",
        "WATCHLIST_FULL",
    )

    private val sources: List<Path> by lazy {
        val found = Files.walk(Path.of("src", "main", "kotlin")).use { paths ->
            paths.filter { it.extension == "kt" }.toList()
        }
        assertTrue(found.size > MIN_SOURCES, "only ${found.size} sources under ${Path.of("").toAbsolutePath()}")
        found
    }

    private fun constants(holder: Any): Set<String> = holder.javaClass.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.get(null) as String }
        .toSet()

    private val contractCodes = constants(Wire.ErrorCode) + constants(Wire.RecordingReason)

    @Test
    fun `a code the contract names is never written as a string literal`() {
        assertTrue(Wire.ErrorCode.CHANNEL_NOT_FOUND in contractCodes && Wire.RecordingReason.MISSED in contractCodes)
        val literals = sources.flatMap { file ->
            file.readLines().withIndex()
                .filterNot { (_, line) -> COMMENT.containsMatchIn(line) }
                .flatMap { (index, line) ->
                    LITERAL.findAll(line).map { it.groupValues[1] }.filter { it in contractCodes }
                        .map { "${file.fileName}:${index + 1} \"$it\"" }
                }
        }
        assertEquals(emptyList(), literals, "use the Wire constant")
    }

    @Test
    fun `every other error code is one the server lists`() {
        val calls = sources.sumOf { CALL.findAll(it.readText()).count() }
        val codes = sources.flatMap { file -> CODE.findAll(file.readText()).map { it.groupValues[1] } }
        assertEquals(calls, codes.size, "an ApiFailure or ApiError call this test cannot read the code of")
        val literals = codes.filter { it.startsWith('"') }.map { it.trim('"') }.toSet() - contractCodes
        assertEquals(emptyList(), (literals - serverOnly).sorted(), "codes missing from the server's list")
        assertEquals(emptyList(), (serverOnly - literals).sorted(), "listed codes no call uses")
    }

    private companion object {
        const val MIN_SOURCES = 300
        val COMMENT = Regex("""^\s*(\*|/\*|//)""")
        val LITERAL = Regex(""""([A-Z0-9_]+)"""")

        /** A call that builds an error, not the declaration of either class. */
        val CALL = Regex("""(?<!class )\bApi(Failure|Error)\(""")

        /** The code argument: the second of `ApiFailure`, the first of `ApiError`. */
        val CODE = Regex("""(?<!class )\bApi(?:Failure\(\s*[\w.]+\s*,|Error\()\s*("[\w.${'$'}{}]+"|[\w.]+)\s*,""")
    }
}
