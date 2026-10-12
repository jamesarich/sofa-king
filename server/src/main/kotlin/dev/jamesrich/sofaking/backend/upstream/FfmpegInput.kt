package dev.jamesrich.sofaking.backend.upstream

/**
 * The protocols FFmpeg may open for a source and everything its playlists reference: HTTP(S) over TCP and TLS,
 * `crypto` for AES-128 HLS segments, and `file`. Anything else a playlist names, such as `concat:`, `subfile:` or
 * `ftp:`, is refused. Pass it as an input option, before `-i`.
 */
internal val FFMPEG_PROTOCOL_WHITELIST = listOf("-protocol_whitelist", "file,http,https,tcp,tls,crypto")
