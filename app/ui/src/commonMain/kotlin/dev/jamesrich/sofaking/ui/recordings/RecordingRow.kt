package dev.jamesrich.sofaking.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.RecordingFailure
import dev.jamesrich.sofaking.data.RecordingState
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.recordings_cancel
import dev.jamesrich.sofaking.resources.recordings_confirm_cancel
import dev.jamesrich.sofaking.resources.recordings_confirm_delete
import dev.jamesrich.sofaking.resources.recordings_confirm_stop
import dev.jamesrich.sofaking.resources.recordings_conflict
import dev.jamesrich.sofaking.resources.recordings_delete
import dev.jamesrich.sofaking.resources.recordings_ended_early
import dev.jamesrich.sofaking.resources.recordings_ended_early_channel_gone
import dev.jamesrich.sofaking.resources.recordings_ended_early_disk_full
import dev.jamesrich.sofaking.resources.recordings_ended_early_source
import dev.jamesrich.sofaking.resources.recordings_ended_early_stopped
import dev.jamesrich.sofaking.resources.recordings_failed
import dev.jamesrich.sofaking.resources.recordings_failed_channel_gone
import dev.jamesrich.sofaking.resources.recordings_failed_disk_full
import dev.jamesrich.sofaking.resources.recordings_failed_missed
import dev.jamesrich.sofaking.resources.recordings_failed_no_media
import dev.jamesrich.sofaking.resources.recordings_failed_source
import dev.jamesrich.sofaking.resources.recordings_from_start
import dev.jamesrich.sofaking.resources.recordings_play
import dev.jamesrich.sofaking.resources.recordings_protect
import dev.jamesrich.sofaking.resources.recordings_protected
import dev.jamesrich.sofaking.resources.recordings_resume
import dev.jamesrich.sofaking.resources.recordings_stop
import dev.jamesrich.sofaking.resources.recordings_unprotect
import dev.jamesrich.sofaking.resources.recordings_until
import dev.jamesrich.sofaking.resources.recordings_watched
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.TimeFormatter
import dev.jamesrich.sofaking.ui.announcesChanges
import dev.jamesrich.sofaking.ui.formatBytes
import dev.jamesrich.sofaking.ui.theme.SofaButton
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RecordingRow(
    recording: DvrRecording,
    time: TimeFormatter,
    onPlay: (DvrRecording, Boolean) -> Unit,
    onDelete: suspend (DvrRecording) -> Boolean,
    onProtect: suspend (DvrRecording, Boolean) -> Boolean,
    onCancel: (DvrRecording) -> Unit,
    modifier: Modifier = Modifier,
    firstButtonModifier: Modifier = Modifier,
) {
    val actions = rememberRowActions(recording.id)
    val undoes = undoesInsteadOfConfirming(LocalLayoutProfile.current.tv, recording.state)
    Row(modifier.widthIn(max = 960.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RecordingThumb(recording)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RecordingTexts(recording, time)
            RecordedSoFarBar(recording)
            RowButtons(actions) {
                val deleteFocus = remember { FocusRequester() }
                val deleteModifier =
                    (if (recording.playable) Modifier else firstButtonModifier).focusRequester(deleteFocus)
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
                if (recording.canProtect) {
                    ProtectButton(recording.protected, recording.title) {
                        actions.run { onProtect(recording, !recording.protected) }
                    }
                }
                EndButton(
                    recording,
                    actions,
                    onPress = { if (undoes) onCancel(recording) else actions.confirmThen { onDelete(recording) } },
                    modifier = deleteModifier,
                )
                if (actions.confirming) KeepButton(actions, deleteFocus, recording.title)
            }
            if (actions.failed) ChangeFailed()
        }
    }
}

/** The title, the episode line and the state line. */
@Composable
private fun RecordingTexts(recording: DvrRecording, time: TimeFormatter) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                color = if (recording.wontRecord) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Stop, Cancel or Delete; [onPress] asks by name first or acts at once, and [KeepButton] backs out of the ask. */
@Composable
private fun EndButton(
    recording: DvrRecording,
    actions: RowActions,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SofaButton(modifier = modifier.announcesChanges(), onClick = onPress) {
        val state = recording.state
        if (actions.confirming) {
            Text(stringResource(confirmLabel(state), recording.title))
        } else {
            NamedLabel(stringResource(deleteLabel(state)), recording.title)
        }
    }
}

@Composable
private fun ProtectButton(protected: Boolean, title: String, onClick: () -> Unit) {
    SofaButton(onClick = onClick) {
        val label = if (protected) Res.string.recordings_unprotect else Res.string.recordings_protect
        NamedLabel(stringResource(label), title)
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

    RecordingState.FAILED -> stringResource(failureLabel(recording.failure))

    RecordingState.SCHEDULED -> stringResource(Res.string.recordings_conflict).takeIf { recording.wontRecord }

    RecordingState.COMPLETED -> listOfNotNull(
        stringResource(Res.string.recordings_protected).takeIf { recording.protected },
        recording.endedEarly?.let { stringResource(endedEarlyLabel(it)) },
        stringResource(Res.string.recordings_watched).takeIf { recording.watched },
        recording.bytes.takeIf { it > 0 }?.let(::formatBytes),
    ).joinToString(" · ").ifEmpty { null }

    else -> null
}

private fun failureLabel(failure: RecordingFailure?) = when (failure) {
    RecordingFailure.MISSED -> Res.string.recordings_failed_missed
    RecordingFailure.DISK_FULL -> Res.string.recordings_failed_disk_full
    RecordingFailure.NO_MEDIA -> Res.string.recordings_failed_no_media
    RecordingFailure.SOURCE -> Res.string.recordings_failed_source
    RecordingFailure.CHANNEL_GONE -> Res.string.recordings_failed_channel_gone
    RecordingFailure.STOPPED, RecordingFailure.OTHER, null -> Res.string.recordings_failed
}

private fun endedEarlyLabel(reason: RecordingFailure) = when (reason) {
    RecordingFailure.STOPPED -> Res.string.recordings_ended_early_stopped
    RecordingFailure.DISK_FULL -> Res.string.recordings_ended_early_disk_full
    RecordingFailure.SOURCE, RecordingFailure.NO_MEDIA -> Res.string.recordings_ended_early_source
    RecordingFailure.CHANNEL_GONE -> Res.string.recordings_ended_early_channel_gone
    RecordingFailure.MISSED, RecordingFailure.OTHER -> Res.string.recordings_ended_early
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
