package dev.jamesrich.sofaking.backend.upstream

/**
 * The protocols FFmpeg may open: plain HTTP over TCP to the loopback relay, and `crypto` for AES-128 HLS segments.
 * Anything else a playlist names, such as `https:`, `file:`, `concat:`, `subfile:` or `ftp:`, is refused, so a remote
 * playlist can neither read files in the container nor reach a host past the relay. Pass it as an input option, before `-i`.
 */
internal val FFMPEG_PROTOCOL_WHITELIST = listOf("-protocol_whitelist", "http,tcp,crypto")

/** The request header that admits FFmpeg to the loopback relay. */
internal const val RELAY_CREDENTIAL_HEADER = "X-Sofa-Relay"

/**
 * What FFmpeg opens for one source: a loopback relay [url] and the [credential] the relay admits it by. FFmpeg sends
 * the credential as a header, so it is in no URL FFmpeg prints; [toString] leaves it out too.
 */
class FfmpegSource internal constructor(val url: String, internal val credential: String) {
    override fun toString(): String = "FfmpegSource(url=$url)"
}

/**
 * The input options every FFmpeg command that reads a source starts with: quiet at [loglevel], no stdin, a 15 s network
 * read timeout, the whitelist and the relay credential, which FFmpeg's HLS demuxer sends with every playlist, segment
 * and key request. The provider's own headers stay with the relay. The caller adds its own input options and `-i`.
 */
internal fun ffmpegBase(ffmpeg: String, loglevel: String, source: FfmpegSource): MutableList<String> {
    val command = mutableListOf(ffmpeg, "-hide_banner", "-loglevel", loglevel, "-nostdin", "-rw_timeout", "15000000")
    command += FFMPEG_PROTOCOL_WHITELIST
    command += listOf("-headers", "$RELAY_CREDENTIAL_HEADER: ${source.credential}\r\n")
    return command
}

/** A builder for an FFmpeg that reads a source: it reaches the relay directly, never through a proxy in the environment. */
internal fun ffmpegProcess(command: List<String>): ProcessBuilder =
    ProcessBuilder(command).also { it.environment().remove("http_proxy") }
