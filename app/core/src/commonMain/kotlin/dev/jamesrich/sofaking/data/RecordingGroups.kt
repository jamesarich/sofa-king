package dev.jamesrich.sofaking.data

/** The recordings list's groups, in the order they show. */
enum class RecordingGroup { NOW, RECORDED, SCHEDULED }

/** One group of the recordings list and its recordings, in the order they show. */
data class RecordingGroupEntry(val group: RecordingGroup, val recordings: List<DvrRecording>)

/**
 * [recordings] by group, empty groups left out: running ones first, then finished and failed ones newest first,
 * then scheduled ones soonest first. A state a newer server added counts as recorded.
 */
fun recordingGroups(recordings: List<DvrRecording>): List<RecordingGroupEntry> {
    val byGroup = recordings.groupBy {
        when (it.state) {
            RecordingState.RECORDING -> RecordingGroup.NOW
            RecordingState.SCHEDULED -> RecordingGroup.SCHEDULED
            else -> RecordingGroup.RECORDED
        }
    }
    return RecordingGroup.entries.mapNotNull { group ->
        val list = byGroup[group].orEmpty()
        val sorted = if (group == RecordingGroup.SCHEDULED) {
            list.sortedBy { it.startsAt }
        } else {
            list.sortedByDescending { it.startsAt }
        }
        sorted.takeIf { it.isNotEmpty() }?.let { RecordingGroupEntry(group, it) }
    }
}

/** The keep counts the Keep button steps through, all first. */
val KEEP_STEPS = listOf(0, 1, 3, 5, 10)

/**
 * The keep count after [keep]: the next step up, back to all after the last; a count set elsewhere
 * goes to the next step above it.
 */
fun nextKeep(keep: Int): Int = KEEP_STEPS.firstOrNull { it > keep } ?: 0
