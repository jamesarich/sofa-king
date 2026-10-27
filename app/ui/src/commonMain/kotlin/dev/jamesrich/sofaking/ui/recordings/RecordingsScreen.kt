package dev.jamesrich.sofaking.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.RecordingState
import dev.jamesrich.sofaking.data.RecordingsState
import dev.jamesrich.sofaking.data.SeriesRule
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.load_failed
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.resources.recordings_empty
import dev.jamesrich.sofaking.resources.recordings_empty_hint
import dev.jamesrich.sofaking.resources.recordings_loading
import dev.jamesrich.sofaking.resources.recordings_now
import dev.jamesrich.sofaking.resources.recordings_recorded
import dev.jamesrich.sofaking.resources.recordings_scheduled
import dev.jamesrich.sofaking.resources.recordings_space
import dev.jamesrich.sofaking.resources.recordings_unavailable
import dev.jamesrich.sofaking.resources.unconfigured
import dev.jamesrich.sofaking.resources.unconfigured_hint
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.TimeFormatter
import dev.jamesrich.sofaking.ui.formatBytes
import dev.jamesrich.sofaking.ui.listScreenPadding
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The server's recordings in three groups: running now, recorded, and scheduled. A recorded or running one plays
 * with [onPlay] (resuming where the household stopped unless `fromStart`); [onDelete] stops, cancels or deletes one
 * after a second press confirms it. The series rules follow the
 * recordings: [onRuleChange] sets whether one takes repeats and how many it keeps, and [onRuleDelete] stops one.
 */
@Composable
fun RecordingsScreen(
    state: RecordingsState,
    onPlay: (recording: DvrRecording, fromStart: Boolean) -> Unit,
    onDelete: suspend (DvrRecording) -> Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onRuleChange: suspend (rule: SeriesRule, newOnly: Boolean, keep: Int) -> Boolean = { _, _, _ -> false },
    onRuleDelete: suspend (SeriesRule) -> Boolean = { false },
    /** The recording just played, which takes focus again on the way back. */
    returnedFrom: String? = null,
    /** Whether the header above has focus; the list scrolls back to the top under it. */
    headerFocused: Boolean = false,
) {
    BackHandler(onBack = onBack)
    when (state) {
        RecordingsState.Loading -> MessageScreen(stringResource(Res.string.recordings_loading), loading = true)

        RecordingsState.Unconfigured -> MessageScreen(
            stringResource(Res.string.unconfigured),
            hint = stringResource(Res.string.unconfigured_hint),
        )

        RecordingsState.Unavailable -> MessageScreen(stringResource(Res.string.recordings_unavailable))

        is RecordingsState.Failed -> MessageScreen(
            stringResource(Res.string.load_failed),
            detail = state.message,
            onRetry = onRetry,
        )

        is RecordingsState.Ready -> RecordingList(
            state,
            onPlay,
            onDelete,
            onRuleChange,
            onRuleDelete,
            returnedFrom,
            headerFocused,
        )
    }
}

@Composable
private fun RecordingList(
    state: RecordingsState.Ready,
    onPlay: (DvrRecording, Boolean) -> Unit,
    onDelete: suspend (DvrRecording) -> Boolean,
    onRuleChange: suspend (SeriesRule, Boolean, Int) -> Boolean,
    onRuleDelete: suspend (SeriesRule) -> Boolean,
    returnedFrom: String?,
    headerFocused: Boolean,
) {
    val time = rememberTimeFormatter()
    val listState = rememberLazyListState()
    // Back or Up to the header shows the list from its top, so Down lands on the first recording.
    LaunchedEffect(headerFocused) { if (headerFocused) listState.animateScrollToItem(0) }
    val groups = remember(state.recordings) { recordingGroups(state.recordings) }
    val firstFocus = remember { FocusRequester() }
    // The recording just played takes focus, else the first; with none, focus stays on the header's tab.
    LaunchedEffect(groups.isEmpty()) { if (groups.isNotEmpty()) requestFocusWhenAttached(firstFocus) }
    // The app's header sits above, with the tabs, so the list pads the sides and bottom only.
    val padding = listScreenPadding(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    LazyColumn(
        modifier = Modifier.fillMaxSize().then(padding.modifier),
        state = listState,
        contentPadding = padding.content,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.space.totalBytes > 0) {
            item(key = "space") {
                Text(
                    stringResource(
                        Res.string.recordings_space,
                        formatBytes(state.space.freeBytes),
                        formatBytes(state.space.totalBytes),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (groups.isEmpty()) {
            item(key = "empty") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(Res.string.recordings_empty), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(Res.string.recordings_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        recordingGroups(groups, returnedFrom, time, onPlay, onDelete, firstFocus)
        seriesRules(state.rules, onRuleChange, onRuleDelete)
    }
}

/** Each group's heading and its recordings; the one just played, else the first, answers to [firstFocus]. */
private fun LazyListScope.recordingGroups(
    groups: List<Pair<RecordingGroup, List<DvrRecording>>>,
    returnedFrom: String?,
    time: TimeFormatter,
    onPlay: (DvrRecording, Boolean) -> Unit,
    onDelete: suspend (DvrRecording) -> Boolean,
    firstFocus: FocusRequester,
) {
    val firstId = returnedFrom?.takeIf { id -> groups.any { (_, recordings) -> recordings.any { it.id == id } } }
        ?: groups.firstOrNull()?.second?.firstOrNull()?.id
    groups.forEach { (group, recordings) ->
        item(key = "group-${group.name}") {
            Text(
                stringResource(group.label),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        items(recordings, key = { it.id }) { recording ->
            RecordingRow(
                recording,
                time,
                onPlay,
                onDelete,
                firstButtonModifier = if (recording.id == firstId) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

/** The list's groups, in the order they show. */
internal enum class RecordingGroup(val label: StringResource) {
    NOW(Res.string.recordings_now),
    RECORDED(Res.string.recordings_recorded),
    SCHEDULED(Res.string.recordings_scheduled),
}

/**
 * [recordings] by group, empty groups left out: running ones first, then finished and failed ones newest first,
 * then scheduled ones soonest first. A state a newer server added counts as recorded.
 */
internal fun recordingGroups(recordings: List<DvrRecording>): List<Pair<RecordingGroup, List<DvrRecording>>> {
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
        sorted.takeIf { it.isNotEmpty() }?.let { group to it }
    }
}
