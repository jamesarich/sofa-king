package dev.jamesrich.sofaking.ui.recordings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.TimeFormatter
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaButton
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
        RecordingsState.Loading -> MessageScreen(stringResource(Res.string.loading), loading = true)
        RecordingsState.Unconfigured -> MessageScreen(stringResource(Res.string.unconfigured), hint = stringResource(Res.string.unconfigured_hint))
        RecordingsState.Unavailable -> MessageScreen(stringResource(Res.string.recordings_unavailable))
        is RecordingsState.Failed -> MessageScreen(stringResource(Res.string.load_failed), detail = state.message, onRetry = onRetry)
        is RecordingsState.Ready -> RecordingList(state, onPlay, onDelete, onRuleChange, onRuleDelete, returnedFrom, headerFocused)
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
                    stringResource(Res.string.recordings_space, formatBytes(state.space.freeBytes), formatBytes(state.space.totalBytes)),
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
                    Text(stringResource(Res.string.recordings_empty_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val firstId = returnedFrom?.takeIf { id -> groups.any { (_, recordings) -> recordings.any { it.id == id } } }
            ?: groups.firstOrNull()?.second?.firstOrNull()?.id
        groups.forEach { (group, recordings) ->
            item(key = "group-${group.name}") {
                Text(stringResource(group.label), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
            items(recordings, key = { it.id }) { recording ->
                RecordingRow(recording, time, onPlay, onDelete, if (recording.id == firstId) Modifier.focusRequester(firstFocus) else Modifier)
            }
        }
        if (state.rules.isNotEmpty()) {
            item(key = "series") {
                Text(stringResource(Res.string.recordings_series), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
            items(state.rules, key = { "rule-${it.id}" }) { rule -> SeriesRow(rule, onRuleChange, onRuleDelete) }
        }
    }
}

/** One series rule: its title and what it takes and keeps, with buttons that change them and one that stops it. */
@Composable
private fun SeriesRow(
    rule: SeriesRule,
    onChange: suspend (SeriesRule, Boolean, Int) -> Boolean,
    onDelete: suspend (SeriesRule) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var confirming by remember(rule.id) { mutableStateOf(false) }
    var busy by remember(rule.id) { mutableStateOf(false) }
    var failed by remember(rule.id) { mutableStateOf(false) }
    // Buttons stay enabled while the server answers, as the recording rows do, so the D-pad focus never drops.
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
    Column(Modifier.widthIn(max = 960.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(rule.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            seriesSummary(rule),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.padding(top = 4.dp).focusGroup().onFocusChanged { if (!it.hasFocus) confirming = false },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SofaButton(onClick = { run { onChange(rule, !rule.newOnly, rule.keep) } }) {
                Text(stringResource(if (rule.newOnly) Res.string.recordings_series_take_all else Res.string.recordings_series_take_new))
            }
            SofaButton(onClick = { run { onChange(rule, rule.newOnly, nextKeep(rule.keep)) } }) {
                val count = if (rule.keep == 0) stringResource(Res.string.recordings_series_keep_all_short) else rule.keep.toString()
                Text(stringResource(Res.string.recordings_series_keep_button, count))
            }
            SofaButton(onClick = {
                if (!confirming) {
                    confirming = true
                    failed = false
                } else {
                    confirming = false
                    run { onDelete(rule) }
                }
            }) {
                Text(stringResource(if (confirming) Res.string.recordings_series_confirm_stop else Res.string.recordings_series_stop))
            }
        }
        if (failed) {
            Text(stringResource(Res.string.recordings_change_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** What a rule takes and keeps, such as "New episodes only · Keeps 5 · Any channel". */
@Composable
private fun seriesSummary(rule: SeriesRule): String = listOfNotNull(
    stringResource(if (rule.newOnly) Res.string.recordings_series_new_only else Res.string.recordings_series_all_airings),
    if (rule.keep == 0) stringResource(Res.string.recordings_series_keep_all) else stringResource(Res.string.recordings_series_keeps, rule.keep),
    stringResource(Res.string.recordings_series_any_channel).takeIf { rule.channelId == null },
).joinToString(" · ")

/** The keep counts the Keep button steps through, all first. */
internal val KEEP_STEPS = listOf(0, 1, 3, 5, 10)

/** The keep count after [keep]: the next step up, back to all after the last; a count set elsewhere goes to the next step above it. */
internal fun nextKeep(keep: Int): Int = KEEP_STEPS.firstOrNull { it > keep } ?: 0

@Composable
private fun RecordingRow(
    recording: DvrRecording,
    time: TimeFormatter,
    onPlay: (DvrRecording, Boolean) -> Unit,
    onDelete: suspend (DvrRecording) -> Boolean,
    firstButton: Modifier,
) {
    val scope = rememberCoroutineScope()
    var confirming by remember(recording.id) { mutableStateOf(false) }
    var busy by remember(recording.id) { mutableStateOf(false) }
    var failed by remember(recording.id) { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 960.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(recording.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            recordingSubtitle(recording, time),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        recordingStatus(recording, time)?.let { status ->
            Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // One focus group per row, so Left and Right stay on its buttons and Up and Down move between rows.
        Row(
            modifier = Modifier.padding(top = 4.dp).focusGroup().onFocusChanged { if (!it.hasFocus) confirming = false },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val deleteModifier = if (recording.playable) Modifier else firstButton
            if (recording.playable) {
                SofaButton(onClick = { onPlay(recording, false) }, modifier = firstButton) {
                    Text(stringResource(if (recording.inProgress) Res.string.recordings_resume else Res.string.recordings_play))
                }
                if (recording.inProgress) {
                    SofaButton(onClick = { onPlay(recording, true) }) { Text(stringResource(Res.string.recordings_from_start)) }
                }
            }
            // Kept enabled while the server answers: a disabled button would drop the D-pad focus.
            SofaButton(
                modifier = deleteModifier,
                onClick = {
                    when {
                        busy -> {}
                        !confirming -> {
                            confirming = true
                            failed = false
                        }
                        else -> {
                            confirming = false
                            busy = true
                            scope.launch {
                                try {
                                    failed = !onDelete(recording)
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    }
                },
            ) {
                Text(stringResource(if (confirming) confirmLabel(recording.state) else deleteLabel(recording.state)))
            }
        }
        if (failed) {
            Text(stringResource(Res.string.recordings_change_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
        val sorted = if (group == RecordingGroup.SCHEDULED) list.sortedBy { it.startsAt } else list.sortedByDescending { it.startsAt }
        sorted.takeIf { it.isNotEmpty() }?.let { group to it }
    }
}

/** The episode, when the guide gave one, then the channel and when it aired, such as "S2 E5 · News 4 · Sun Oct 18, 6:00 PM". */
internal fun recordingSubtitle(recording: DvrRecording, time: TimeFormatter): String {
    val number = listOfNotNull(recording.season?.let { "S$it" }, recording.episode?.let { "E$it" }).joinToString(" ").ifEmpty { null }
    val local = recording.startsAt.toLocalDateTime(time.zone)
    val aired = "${time.day(recording.startsAt)} ${MonthNames.ENGLISH_ABBREVIATED.names[local.month.ordinal]} ${local.day}, ${time.format(recording.startsAt)}"
    return listOfNotNull(number, recording.episodeTitle, recording.channelName.ifBlank { null }, aired).joinToString(" · ")
}

@Composable
private fun recordingStatus(recording: DvrRecording, time: TimeFormatter): String? = when (recording.state) {
    RecordingState.RECORDING -> stringResource(Res.string.recordings_until, time.format(recording.endsAt))
    RecordingState.FAILED -> recording.reason?.let { stringResource(Res.string.recordings_failed_reason, it) } ?: stringResource(Res.string.recordings_failed)
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

/** A size in the largest unit that keeps it at 1 or more, with one decimal under 10: "740 MB", "2.4 GB". */
internal fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    val text = if (value < 10 && unit > 0) {
        val tenths = kotlin.math.round(value * 10).toLong()
        if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    } else {
        kotlin.math.round(value).toLong().toString()
    }
    return "$text ${units[unit]}"
}
