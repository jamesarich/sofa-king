package dev.jamesrich.sofaking.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.RecordingState
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.recordings_cancel
import dev.jamesrich.sofaking.resources.recordings_confirm_cancel
import dev.jamesrich.sofaking.resources.recordings_confirm_delete
import dev.jamesrich.sofaking.resources.recordings_confirm_stop
import dev.jamesrich.sofaking.resources.recordings_delete
import dev.jamesrich.sofaking.resources.recordings_failed
import dev.jamesrich.sofaking.resources.recordings_failed_reason
import dev.jamesrich.sofaking.resources.recordings_from_start
import dev.jamesrich.sofaking.resources.recordings_play
import dev.jamesrich.sofaking.resources.recordings_resume
import dev.jamesrich.sofaking.resources.recordings_stop
import dev.jamesrich.sofaking.resources.recordings_until
import dev.jamesrich.sofaking.resources.recordings_watched
import dev.jamesrich.sofaking.ui.TimeFormatter
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
