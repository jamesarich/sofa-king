package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.RecordingGroupEntry
import dev.jamesrich.sofaking.data.awaitLiveSegments
import dev.jamesrich.sofaking.data.fetchText
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import dev.jamesrich.sofaking.data.nextKeep as nextKeepOf
import dev.jamesrich.sofaking.data.reconnectDelayMs as reconnectDelayMsOf
import dev.jamesrich.sofaking.data.recordingGroups as recordingGroupsOf

/** The decisions every client shares, which the Apple TV app asks for instead of keeping a copy of its own. */
interface TvDecisions {
    /** How long to wait before reconnect [attempt] (0-based) after a stream fails or ends, in milliseconds. */
    fun reconnectDelayMs(attempt: Int): Long

    /** The keep count after [keep] on a series rule: the next step up, back to all (0) after the last. */
    fun nextKeep(keep: Int): Int

    /** The recordings by group in the order the list shows them, empty groups left out. */
    fun recordingGroups(recordings: List<DvrRecording>): List<RecordingGroupEntry>

    /**
     * Calls [onSettled] on the main thread once the live playlist at [url] lists enough segments for AVPlayer to start
     * it, or at once when it is not a live media playlist; canceling the [Watch] calls it too.
     */
    fun awaitLiveStart(url: String, onSettled: () -> Unit): Watch
}

internal class TvDecisionCalls(private val parts: TvParts) : TvDecisions {
    override fun reconnectDelayMs(attempt: Int): Long = reconnectDelayMsOf(attempt)

    override fun nextKeep(keep: Int): Int = nextKeepOf(keep)

    override fun recordingGroups(recordings: List<DvrRecording>): List<RecordingGroupEntry> =
        recordingGroupsOf(recordings)

    override fun awaitLiveStart(url: String, onSettled: () -> Unit): Watch {
        val job = parts.scope.launch {
            try {
                NSURL.URLWithString(url)?.let { awaitLiveSegments { fetchText(it) } }
            } finally {
                onSettled()
            }
        }
        return Watch(job)
    }
}
