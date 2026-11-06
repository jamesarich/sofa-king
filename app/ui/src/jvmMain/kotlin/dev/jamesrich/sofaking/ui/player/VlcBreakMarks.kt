package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.AdBreakNow
import dev.jamesrich.sofaking.data.BreakMarks
import dev.jamesrich.sofaking.data.LivePlaylist
import dev.jamesrich.sofaking.data.fetchPlaylistText
import dev.jamesrich.sofaking.data.parseLivePlaylist
import dev.jamesrich.sofaking.data.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The shortest wait between two reads of the playlist. */
private const val MIN_REFRESH_MS = 2_000L

/**
 * The ad breaks of a live HLS stream for libVLC, which exposes no playlist: the media playlist is read through the
 * stream's URL (following one variant) every target duration, off the main thread, and its `EXT-X-DATERANGE` marks are
 * placed on the playlist's clock. libVLC plays [liveDelayMs] behind the newest segment, so the picture's time is the
 * live edge less that delay, moving on in real time between reads.
 */
internal class VlcBreakMarks(
    private val liveDelayMs: Long,
    private val fetch: suspend (String) -> String? = ::fetchPlaylistText,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var marks = BreakMarks()
    private var edge: Pair<Long, TimeMark>? = null
    private var reading: Job? = null

    /** Reads the marks of the live playlist at [url]; null, or anything but a playlist, stops. */
    fun follow(url: String?) {
        reading?.cancel()
        synchronized(lock) {
            marks = BreakMarks()
            edge = null
        }
        reading = url?.takeIf { it.substringBefore('?').endsWith(".m3u8") }?.let { scope.launch { read(it) } }
    }

    /** The break the picture is in, or null outside one or before a playlist with a clock has been read. */
    fun adBreak(): AdBreakNow? = synchronized(lock) {
        val (liveEdgeMs, read) = edge ?: return null
        marks.at(liveEdgeMs + read.elapsedNow().inWholeMilliseconds - liveDelayMs)
    }

    fun release() {
        scope.cancel()
    }

    private suspend fun read(url: String) {
        var playlistUrl = url
        while (true) {
            val text = runCatchingCancellable { fetch(playlistUrl) }.getOrNull()
            val playlist = text?.let { parseLivePlaylist(it, playlistUrl) }
            val variant = playlist?.variantUrl
            if (variant != null) {
                playlistUrl = variant
                continue
            }
            playlist?.let(::place)
            delay(maxOf(playlist?.targetDurationMs ?: 0L, MIN_REFRESH_MS))
        }
    }

    private fun place(playlist: LivePlaylist) {
        val liveEdgeMs = playlist.liveEdgeMs ?: return
        val read = timeSource.markNow()
        synchronized(lock) {
            marks.replaceOpen(playlist.marks)
            edge = liveEdgeMs to read
        }
    }
}
