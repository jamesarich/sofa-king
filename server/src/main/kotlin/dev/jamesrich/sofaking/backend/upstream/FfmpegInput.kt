package dev.jamesrich.sofaking.backend.upstream

/**
 * The protocols FFmpeg may open for a source and everything its playlists reference: HTTP(S) over TCP and TLS, and
 * `crypto` for AES-128 HLS segments. Anything else a playlist names, such as `file:`, `concat:`, `subfile:` or `ftp:`,
 * is refused, so a remote playlist cannot read files in the container. Pass it as an input option, before `-i`.
 */
internal val FFMPEG_PROTOCOL_WHITELIST = listOf("-protocol_whitelist", "http,https,tcp,tls,crypto")
