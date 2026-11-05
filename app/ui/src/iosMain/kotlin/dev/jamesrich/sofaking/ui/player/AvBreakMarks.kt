package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.contract.AdDateRange
import dev.jamesrich.sofaking.data.AdBreakNow
import dev.jamesrich.sofaking.data.BreakMark
import dev.jamesrich.sofaking.data.BreakMarks
import platform.AVFoundation.AVDateRangeMetadataGroup
import platform.AVFoundation.AVMetadataItem
import platform.AVFoundation.AVMetadataKeySpaceHLSDateRange
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemMetadataCollector
import platform.AVFoundation.AVPlayerItemMetadataCollectorPushDelegateProtocol
import platform.AVFoundation.addMediaDataCollector
import platform.AVFoundation.classifyingLabel
import platform.AVFoundation.currentDate
import platform.AVFoundation.key
import platform.AVFoundation.keySpace
import platform.AVFoundation.numberValue
import platform.AVFoundation.removeMediaDataCollector
import platform.AVFoundation.uniqueID
import platform.Foundation.NSDate
import platform.Foundation.NSIndexSet
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue

private const val MS_PER_SECOND = 1000.0
private const val PLANNED_DURATION = "PLANNED-DURATION"

/**
 * The ad breaks of the live item AVPlayer plays, from the stream's `EXT-X-DATERANGE` marks: a metadata collector on the
 * item hands each date range group to [marks] on the main queue, where the break screen reads it. The picture's own
 * clock is the item's current date, which AVPlayer takes from the playlist's program date time.
 */
internal class AvBreakMarks {
    private var marks = BreakMarks()
    private var item: AVPlayerItem? = null
    private var collector: AVPlayerItemMetadataCollector? = null
    private val delegate = Delegate { groups ->
        groups.forEach { (it as? AVDateRangeMetadataGroup)?.toMark()?.let(marks::add) }
    }

    /** Reads the marks of [next], and none after it replaces it; null stops. */
    fun follow(next: AVPlayerItem?) {
        collector?.let { item?.removeMediaDataCollector(it) }
        collector = null
        item = next
        marks = BreakMarks()
        if (next == null) return
        val created = AVPlayerItemMetadataCollector()
        created.setDelegate(delegate, dispatch_get_main_queue())
        next.addMediaDataCollector(created)
        collector = created
    }

    /** The break the picture is in, or null outside one or before the item has a date. */
    fun adBreak(): AdBreakNow? {
        val date = item?.currentDate() ?: return null
        return marks.at((date.timeIntervalSince1970 * MS_PER_SECOND).toLong())
    }

    private class Delegate(private val onGroups: (List<*>) -> Unit) :
        NSObject(),
        AVPlayerItemMetadataCollectorPushDelegateProtocol {
        override fun metadataCollector(
            metadataCollector: AVPlayerItemMetadataCollector,
            didCollectDateRangeMetadataGroups: List<*>,
            indexesOfNewGroups: NSIndexSet,
            indexesOfModifiedGroups: NSIndexSet,
        ) = onGroups(didCollectDateRangeMetadataGroups)
    }
}

/** The mark an ad range stands for: its end is `DURATION` when the playlist has it, else `PLANNED-DURATION`. */
private fun AVDateRangeMetadataGroup.toMark(): BreakMark? {
    if (classifyingLabel != AdDateRange.CLASS) return null
    val id = uniqueID ?: return null
    val startMs = startDate.ms()
    val end = endDate
    if (end != null) return BreakMark(id, startMs, end.ms(), planned = false)
    val planned = items.filterIsInstance<AVMetadataItem>()
        .firstOrNull { it.keySpace == AVMetadataKeySpaceHLSDateRange && it.key?.toString() == PLANNED_DURATION }
        ?.numberValue?.doubleValue
    return BreakMark(id, startMs, planned?.let { startMs + (it * MS_PER_SECOND).toLong() }, planned = planned != null)
}

private fun NSDate.ms(): Long = (timeIntervalSince1970 * MS_PER_SECOND).toLong()
