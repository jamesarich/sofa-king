package dev.jamesrich.sofaking.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.RecordingGroup
import dev.jamesrich.sofaking.data.RecordingGroupEntry
import dev.jamesrich.sofaking.data.RecordingsState
import dev.jamesrich.sofaking.data.SeriesRule
import dev.jamesrich.sofaking.data.recordingGroups
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.change_failed
import dev.jamesrich.sofaking.resources.load_failed
import dev.jamesrich.sofaking.resources.load_failed_hint
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.resources.recordings_canceled
import dev.jamesrich.sofaking.resources.recordings_empty
import dev.jamesrich.sofaking.resources.recordings_empty_hint
import dev.jamesrich.sofaking.resources.recordings_loading
import dev.jamesrich.sofaking.resources.recordings_now
import dev.jamesrich.sofaking.resources.recordings_recorded
import dev.jamesrich.sofaking.resources.recordings_scheduled
import dev.jamesrich.sofaking.resources.recordings_space
import dev.jamesrich.sofaking.resources.recordings_unavailable
import dev.jamesrich.sofaking.resources.recordings_unavailable_hint
import dev.jamesrich.sofaking.resources.recordings_undo
import dev.jamesrich.sofaking.resources.unconfigured
import dev.jamesrich.sofaking.resources.unconfigured_hint
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.TimeFormatter
import dev.jamesrich.sofaking.ui.formatBytes
import dev.jamesrich.sofaking.ui.listScreenPadding
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.sectionHeading
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The server's recordings in three groups: running now, recorded, and scheduled. A recorded or running one plays
 * with [onPlay] (resuming where the household stopped unless `fromStart`); [onDelete] stops, cancels or deletes one
 * after a second press confirms it, except that a handheld cancels a scheduled one at once and offers Undo, which
 * schedules it again with [onRestore]; [onProtect] has cleanup keep a finished one or not. The series rules follow the
 * recordings: [onRuleChange] sets whether one takes repeats and how many it keeps, and [onRuleDelete] stops one.
 */
@Composable
fun RecordingsScreen(
    state: RecordingsState,
    onPlay: (recording: DvrRecording, fromStart: Boolean) -> Unit,
    onDelete: suspend (DvrRecording) -> Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onRuleChange: suspend (rule: SeriesRule, edit: RuleEdit) -> Boolean = { _, _ -> false },
    onRuleDelete: suspend (SeriesRule) -> Boolean = { false },
    onProtect: suspend (recording: DvrRecording, protect: Boolean) -> Boolean = { _, _ -> false },
    onRestore: suspend (DvrRecording) -> Boolean = { false },
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

        RecordingsState.Unavailable -> MessageScreen(
            stringResource(Res.string.recordings_unavailable),
            hint = stringResource(Res.string.recordings_unavailable_hint),
        )

        is RecordingsState.Failed -> MessageScreen(
            stringResource(Res.string.load_failed),
            hint = stringResource(Res.string.load_failed_hint),
            detail = state.message,
            onRetry = onRetry,
        )

        is RecordingsState.Ready -> RecordingList(
            state,
            onPlay,
            onDelete,
            onRuleChange,
            onRuleDelete,
            onProtect,
            onRestore,
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
    onRuleChange: suspend (SeriesRule, RuleEdit) -> Boolean,
    onRuleDelete: suspend (SeriesRule) -> Boolean,
    onProtect: suspend (DvrRecording, Boolean) -> Boolean,
    onRestore: suspend (DvrRecording) -> Boolean,
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
    val snackbars = remember { SnackbarHostState() }
    val cancel = rememberCancelWithUndo(snackbars, onDelete, onRestore)
    Box(Modifier.fillMaxSize()) {
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
            recordingGroups(groups, returnedFrom, time, onPlay, onDelete, onProtect, cancel, firstFocus)
            seriesRules(state.rules, onRuleChange, onRuleDelete)
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
    }
}

/**
 * Cancels a recording from the list's own scope, since its row leaves the list the moment the recording goes, then
 * shows [snackbars] with Undo for a few seconds; Undo schedules it again with [onRestore]. A refusal says so there.
 */
@Composable
private fun rememberCancelWithUndo(
    snackbars: SnackbarHostState,
    onDelete: suspend (DvrRecording) -> Boolean,
    onRestore: suspend (DvrRecording) -> Boolean,
): (DvrRecording) -> Unit {
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(Res.string.recordings_undo)
    val failedMessage = stringResource(Res.string.change_failed)
    return { recording ->
        scope.launch {
            snackbars.currentSnackbarData?.dismiss()
            if (!onDelete(recording)) {
                snackbars.showSnackbar(failedMessage)
                return@launch
            }
            val shown = snackbars.showSnackbar(
                getString(Res.string.recordings_canceled, recording.title),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            if (shown == SnackbarResult.ActionPerformed && !onRestore(recording)) {
                snackbars.showSnackbar(failedMessage)
            }
        }
    }
}

/** Each group's heading and its recordings; the one just played, else the first, answers to [firstFocus]. */
private fun LazyListScope.recordingGroups(
    groups: List<RecordingGroupEntry>,
    returnedFrom: String?,
    time: TimeFormatter,
    onPlay: (DvrRecording, Boolean) -> Unit,
    onDelete: suspend (DvrRecording) -> Boolean,
    onProtect: suspend (DvrRecording, Boolean) -> Boolean,
    onCancel: (DvrRecording) -> Unit,
    firstFocus: FocusRequester,
) {
    val firstId = returnedFrom?.takeIf { id -> groups.any { (_, recordings) -> recordings.any { it.id == id } } }
        ?: groups.firstOrNull()?.recordings?.firstOrNull()?.id
    groups.forEach { (group, recordings) ->
        item(key = "group-${group.name}") {
            Text(
                stringResource(group.label),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.sectionHeading(),
            )
        }
        items(recordings, key = { it.id }) { recording ->
            RecordingRow(
                recording,
                time,
                onPlay,
                onDelete,
                onProtect,
                onCancel,
                firstButtonModifier = if (recording.id == firstId) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

/** The heading over the group. */
internal val RecordingGroup.label: StringResource
    get() = when (this) {
        RecordingGroup.NOW -> Res.string.recordings_now
        RecordingGroup.RECORDED -> Res.string.recordings_recorded
        RecordingGroup.SCHEDULED -> Res.string.recordings_scheduled
    }
