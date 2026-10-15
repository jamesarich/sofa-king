package dev.jamesrich.sofaking.backend.upstream

/**
 * The protocols FFmpeg may open for a source and everything its playlists reference: HTTP(S) over TCP and TLS, and
 * `crypto` for AES-128 HLS segments. Anything else a playlist names, such as `file:`, `concat:`, `subfile:` or `ftp:`,
 * is refused, so a remote playlist cannot read files in the container. Pass it as an input option, before `-i`.
 */
internal val FFMPEG_PROTOCOL_WHITELIST = listOf("-protocol_whitelist", "http,https,tcp,tls,crypto")

/**
 * The input options every FFmpeg command starts with: quiet at [loglevel], no stdin, a 15 s network read timeout, the
 * whitelist and the source's [headers]. The caller adds its own input options and `-i`.
 */
internal fun ffmpegBase(ffmpeg: String, loglevel: String, headers: Map<String, String>): MutableList<String> {
    val command = mutableListOf(ffmpeg, "-hide_banner", "-loglevel", loglevel, "-nostdin", "-rw_timeout", "15000000")
    command += FFMPEG_PROTOCOL_WHITELIST
    if (headers.isNotEmpty()) command += listOf("-headers", headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
    return command
}
