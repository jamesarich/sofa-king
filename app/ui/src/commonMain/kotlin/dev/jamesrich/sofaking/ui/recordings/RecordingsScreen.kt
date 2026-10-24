package dev.jamesrich.sofaking.ui.recordings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.RecordingState
import dev.jamesrich.sofaking.data.RecordingsState
import dev.jamesrich.sofaking.data.SeriesRule
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.load_failed
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.resources.recordings_cancel
import dev.jamesrich.sofaking.resources.recordings_change_failed
import dev.jamesrich.sofaking.resources.recordings_confirm_cancel
import dev.jamesrich.sofaking.resources.recordings_confirm_delete
import dev.jamesrich.sofaking.resources.recordings_confirm_stop
import dev.jamesrich.sofaking.resources.recordings_delete
import dev.jamesrich.sofaking.resources.recordings_empty
import dev.jamesrich.sofaking.resources.recordings_empty_hint
import dev.jamesrich.sofaking.resources.recordings_failed
import dev.jamesrich.sofaking.resources.recordings_failed_reason
import dev.jamesrich.sofaking.resources.recordings_from_start
import dev.jamesrich.sofaking.resources.recordings_loading
import dev.jamesrich.sofaking.resources.recordings_now
import dev.jamesrich.sofaking.resources.recordings_play
import dev.jamesrich.sofaking.resources.recordings_recorded
import dev.jamesrich.sofaking.resources.recordings_resume
import dev.jamesrich.sofaking.resources.recordings_scheduled
import dev.jamesrich.sofaking.resources.recordings_series
import dev.jamesrich.sofaking.resources.recordings_series_all_airings
import dev.jamesrich.sofaking.resources.recordings_series_any_channel
import dev.jamesrich.sofaking.resources.recordings_series_confirm_stop
import dev.jamesrich.sofaking.resources.recordings_series_keep_all
import dev.jamesrich.sofaking.resources.recordings_series_keep_all_short
import dev.jamesrich.sofaking.resources.recordings_series_keep_button
import dev.jamesrich.sofaking.resources.recordings_series_keeps
import dev.jamesrich.sofaking.resources.recordings_series_new_only
import dev.jamesrich.sofaking.resources.recordings_series_stop
import dev.jamesrich.sofaking.resources.recordings_series_take_all
import dev.jamesrich.sofaking.resources.recordings_series_take_new
import dev.jamesrich.sofaking.resources.recordings_space
import dev.jamesrich.sofaking.resources.recordings_stop
import dev.jamesrich.sofaking.resources.recordings_unavailable
import dev.jamesrich.sofaking.resources.recordings_until
import dev.jamesrich.sofaking.resources.recordings_watched
import dev.jamesrich.sofaking.resources.unconfigured
import dev.jamesrich.sofaking.resources.unconfigured_hint
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.TimeFormatter
import dev.jamesrich.sofaking.ui.formatBytes
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.toLocalDateTime
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
    LazyColumn(
        // The app's header sits above, with the tabs, so the list pads the sides and bottom only.
        modifier = Modifier.fillMaxSize().screenPadding(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        state = listState,
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

/** The Series heading and one row per series rule, when there are any. */
private fun LazyListScope.seriesRules(
    rules: List<SeriesRule>,
    onRuleChange: suspend (SeriesRule, Boolean, Int) -> Boolean,
    onRuleDelete: suspend (SeriesRule) -> Boolean,
) {
    if (rules.isEmpty()) return
    item(key = "series") {
        Text(
            stringResource(Res.string.recordings_series),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    items(rules, key = { "rule-${it.id}" }) { rule -> SeriesRow(rule, onRuleChange, onRuleDelete) }
}

/** One series rule: its title and what it takes and keeps, with buttons that change them and one that stops it. */
@Composable
private fun SeriesRow(
    rule: SeriesRule,
    onChange: suspend (SeriesRule, Boolean, Int) -> Boolean,
    onDelete: suspend (SeriesRule) -> Boolean,
) {
    val actions = rememberRowActions(rule.id)
    Column(Modifier.widthIn(max = 960.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(rule.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            seriesSummary(rule),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RowButtons(actions) {
            SofaButton(onClick = { actions.run { onChange(rule, !rule.newOnly, rule.keep) } }) {
                val take = if (rule.newOnly) {
                    Res.string.recordings_series_take_all
                } else {
                    Res.string.recordings_series_take_new
                }
                Text(stringResource(take))
            }
            SofaButton(onClick = { actions.run { onChange(rule, rule.newOnly, nextKeep(rule.keep)) } }) {
                val count = if (rule.keep == 0) {
                    stringResource(Res.string.recordings_series_keep_all_short)
                } else {
                    rule.keep.toString()
                }
                Text(stringResource(Res.string.recordings_series_keep_button, count))
            }
            SofaButton(onClick = { actions.confirmThen { onDelete(rule) } }) {
                val stop = if (actions.confirming) {
                    Res.string.recordings_series_confirm_stop
                } else {
                    Res.string.recordings_series_stop
                }
                Text(stringResource(stop))
            }
        }
        if (actions.failed) ChangeFailed()
    }
}

/**
 * A row's changes on the server: [failed] once one did not take, and [confirming] between the first and second press
 * of a button that asks before it acts. Buttons stay enabled while the server answers, so the D-pad focus never drops;
 * a press while a change is running does nothing.
 */
@Stable
private class RowActions(private val scope: CoroutineScope) {
    var confirming by mutableStateOf(false)
    var failed by mutableStateOf(false)
        private set
    private var busy = false

    fun run(action: suspend () -> Boolean) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                failed = !action()
            } finally {
                busy = false
            }
        }
    }

    /** The first press asks, and the second runs [action]. */
    fun confirmThen(action: suspend () -> Boolean) {
        if (busy) return
        if (confirming) {
            confirming = false
            run(action)
        } else {
            confirming = true
            failed = false
        }
    }
}

@Composable
private fun rememberRowActions(key: String): RowActions {
    val scope = rememberCoroutineScope()
    return remember(key) { RowActions(scope) }
}

/**
 * A row's buttons in one focus group, so Left and Right stay on them and Up and Down move between rows; focus
 * leaving them drops a pending confirmation.
 */
@Composable
private fun RowButtons(actions: RowActions, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.padding(top = 4.dp).focusGroup().onFocusChanged {
            if (!it.hasFocus) actions.confirming = false
        },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The line under a row whose last change did not take. */
@Composable
private fun ChangeFailed() {
    Text(
        stringResource(Res.string.recordings_change_failed),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** What a rule takes and keeps, such as "New episodes only · Keeps 5 · Any channel". */
@Composable
private fun seriesSummary(rule: SeriesRule): String = listOfNotNull(
    stringResource(
        if (rule.newOnly) Res.string.recordings_series_new_only else Res.string.recordings_series_all_airings,
    ),
    if (rule.keep == 0) {
        stringResource(Res.string.recordings_series_keep_all)
    } else {
        stringResource(Res.string.recordings_series_keeps, rule.keep)
    },
    stringResource(Res.string.recordings_series_any_channel).takeIf { rule.channelId == null },
).joinToString(" · ")

/** The keep counts the Keep button steps through, all first. */
internal val KEEP_STEPS = listOf(0, 1, 3, 5, 10)

/**
 * The keep count after [keep]: the next step up, back to all after the last; a count set elsewhere
 * goes to the next step above it.
 */
internal fun nextKeep(keep: Int): Int = KEEP_STEPS.firstOrNull { it > keep } ?: 0

@Composable
private fun RecordingRow(
    recording: DvrRecording,
    time: TimeFormatter,
    onPlay: (DvrRecording, Boolean) -> Unit,
    onDelete: suspend (DvrRecording) -> Boolean,
    modifier: Modifier = Modifier,
    firstButtonModifier: Modifier = Modifier,
) {
    val actions = rememberRowActions(recording.id)
    Column(modifier.widthIn(max = 960.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            recording.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            recordingSubtitle(recording, time),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        recordingStatus(recording, time)?.let { status ->
            Text(
                status,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RowButtons(actions) {
            val deleteModifier = if (recording.playable) Modifier else firstButtonModifier
            if (recording.playable) {
                SofaButton(onClick = { onPlay(recording, false) }, modifier = firstButtonModifier) {
                    Text(
                        stringResource(
                            if (recording.inProgress) Res.string.recordings_resume else Res.string.recordings_play,
                        ),
                    )
                }
                if (recording.inProgress) {
                    SofaButton(onClick = {
                        onPlay(recording, true)
                    }) { Text(stringResource(Res.string.recordings_from_start)) }
                }
            }
            SofaButton(modifier = deleteModifier, onClick = { actions.confirmThen { onDelete(recording) } }) {
                val state = recording.state
                Text(stringResource(if (actions.confirming) confirmLabel(state) else deleteLabel(state)))
            }
        }
        if (actions.failed) ChangeFailed()
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

/**
 * The episode, when the guide gave one, then the channel and when it aired, such as "S2 E5 ·
 * News 4 · Sun Oct 18, 6:00 PM".
 */
internal fun recordingSubtitle(recording: DvrRecording, time: TimeFormatter): String {
    val number = listOfNotNull(
        recording.season?.let {
            "S$it"
        },
        recording.episode?.let { "E$it" },
    ).joinToString(" ").ifEmpty { null }
    val local = recording.startsAt.toLocalDateTime(time.zone)
    val aired = "${time.day(recording.startsAt)} ${MonthNames.ENGLISH_ABBREVIATED.names[local.month.ordinal]} " +
        "${local.day}, ${time.format(recording.startsAt)}"
    return listOfNotNull(
        number,
        recording.episodeTitle,
        recording.channelName.ifBlank {
            null
        },
        aired,
    ).joinToString(" · ")
}

@Composable
private fun recordingStatus(recording: DvrRecording, time: TimeFormatter): String? = when (recording.state) {
    RecordingState.RECORDING -> stringResource(Res.string.recordings_until, time.format(recording.endsAt))

    RecordingState.FAILED -> recording.reason?.let { stringResource(Res.string.recordings_failed_reason, it) }
        ?: stringResource(Res.string.recordings_failed)

    RecordingState.COMPLETED -> listOfNotNull(
        stringResource(Res.string.recordings_watched).takeIf { recording.watched },
        recording.bytes.takeIf { it > 0 }?.let(::formatBytes),
    ).joinToString(" · ").ifEmpty { null }

    else -> null
}

private fun deleteLabel(state: RecordingState) = when (state) {
    RecordingState.RECORDING -> Res.string.recordings_stop
    RecordingState.SCHEDULED -> Res.string.recordings_cancel
    else -> Res.string.recordings_delete
}

private fun confirmLabel(state: RecordingState) = when (state) {
    RecordingState.RECORDING -> Res.string.recordings_confirm_stop
    RecordingState.SCHEDULED -> Res.string.recordings_confirm_cancel
    else -> Res.string.recordings_confirm_delete
}
