package dev.jamesrich.sofaking.backend.diagnostics

import dev.jamesrich.sofaking.contract.CrashReport

private const val MAX_MESSAGE = 300
private const val MAX_FRAME = 200
private const val MAX_FRAMES = 60
private const val MAX_LABEL = 80
private const val MIN_HEX_RUN = 16
private const val MIN_TOKEN_RUN = 24

private val url = Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S*")
private val bearer = Regex("(?i)\\bbearer\\s+\\S+")
private val email = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+")
private val ipv4 = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b")
private val quoted = Regex("\"[^\"]*\"")
private val run = Regex("[A-Za-z0-9_+/=-]{$MIN_HEX_RUN,}")
private val hex = Regex("[0-9a-fA-F]+")
private val whitespace = Regex("\\s+")

/**
 * What a crash report may keep: the server scrubs again what the app already scrubbed, since any client can post.
 * Addresses, tokens, e-mail addresses and quoted text (a host name or a title in a message) become placeholders.
 */
internal fun scrubbedText(raw: String, max: Int): String = raw
    .replace(url, "[url]")
    .replace(bearer, "[token]")
    .replace(email, "[email]")
    .replace(ipv4, "[address]")
    .replace(quoted, "\"…\"")
    .replace(run) { if (tokenShaped(it.value)) "[token]" else it.value }
    .replace("://", ":[url]")
    .replace(whitespace, " ")
    .trim()
    .take(max)

/** A long run of hex digits, or a long run with both letters and digits in it; a long name of letters only is code. */
private fun tokenShaped(run: String): Boolean = when {
    run.length >= MIN_HEX_RUN && hex.matches(run) -> true
    run.length >= MIN_TOKEN_RUN -> run.any(Char::isDigit) && run.any(Char::isLetter)
    else -> false
}

/** [report] with every field scrubbed and bounded, its frames capped; the id and times are kept as sent. */
internal fun scrubbed(report: CrashReport): CrashReport = report.copy(
    id = report.id.trim().take(MAX_LABEL),
    occurredAt = report.occurredAt.trim().take(MAX_LABEL),
    exception = scrubbedText(report.exception, MAX_FRAME),
    message = scrubbedText(report.message, MAX_MESSAGE),
    frames = report.frames.take(MAX_FRAMES).map { scrubbedText(it, MAX_FRAME) }.filter { it.isNotEmpty() },
    appVersion = scrubbedText(report.appVersion, MAX_LABEL),
    platform = scrubbedText(report.platform, MAX_LABEL),
    osVersion = scrubbedText(report.osVersion, MAX_LABEL),
    device = scrubbedText(report.device, MAX_LABEL),
)
