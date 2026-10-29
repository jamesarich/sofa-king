package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.RecordingMark
import dev.jamesrich.sofaking.data.RecordingWindow
import dev.jamesrich.sofaking.data.SeriesOptions
import dev.jamesrich.sofaking.data.teamSides
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.change_failed
import dev.jamesrich.sofaking.resources.guide_details_cancel_recording
import dev.jamesrich.sofaking.resources.guide_details_record
import dev.jamesrich.sofaking.resources.guide_details_record_series
import dev.jamesrich.sofaking.resources.guide_details_record_team
import dev.jamesrich.sofaking.resources.guide_details_stop_series
import dev.jamesrich.sofaking.resources.guide_details_stop_team
import dev.jamesrich.sofaking.resources.recordings_conflict
import dev.jamesrich.sofaking.ui.announcesChanges
import dev.jamesrich.sofaking.ui.theme.SofaButton
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** What the record buttons ask of the server; each answers false when it did not take the change. */
internal class RecordActions(
    val onRecord: suspend (Program) -> Boolean,
    val onRecordSeries: suspend (Program, SeriesOptions) -> Boolean,
    val onRecordTeam: suspend (String) -> Boolean,
    val onRecordWindow: suspend (Channel, RecordingWindow) -> Boolean,
)

/** The record buttons for the program, when it can still be recorded, and for the channel's next hours. */
@Composable
internal fun RecordSection(channel: Channel, program: Program?, mark: RecordingMark?, actions: RecordActions) {
    if (mark == null) return
    if (program != null) RecordButtons(program, mark, actions)
    WindowButtons(channel, actions.onRecordWindow)
}

/**
 * Record or cancel the program, record or stop its series, and record or stop each team a sports program names, with
 * a line when the server did not take the change. A press waits for the server, so a second press cannot undo the
 * first before the marks catch up.
 */
@Composable
private fun RecordButtons(program: Program, mark: RecordingMark, actions: RecordActions) {
    val scope = rememberCoroutineScope()
    var busy by remember(program) { mutableStateOf(false) }
    var failed by remember(program) { mutableStateOf(false) }
    var options by remember(program) { mutableStateOf(SeriesOptions()) }
    val teams = remember(program) { teamSides(program) }
    fun run(action: suspend (Program) -> Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                failed = !action(program)
            } finally {
                busy = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SofaButton(onClick = { run(actions.onRecord) }, modifier = Modifier.announcesChanges()) {
                Text(
                    stringResource(
                        if (mark.recording) {
                            Res.string.guide_details_cancel_recording
                        } else {
                            Res.string.guide_details_record
                        },
                    ),
                    maxLines = 1,
                )
            }
            SofaButton(onClick = {
                run { actions.onRecordSeries(it, options) }
            }, modifier = Modifier.announcesChanges()) {
                Text(
                    stringResource(
                        if (mark.series) {
                            Res.string.guide_details_stop_series
                        } else {
                            Res.string.guide_details_record_series
                        },
                    ),
                    maxLines = 1,
                )
            }
            if (!mark.series) {
                PaddingButton(options) { options = it }
                ChannelScopeButton(options) { options = it }
            }
            for (team in teams) {
                TeamButton(team, recorded = team.lowercase() in mark.teams) {
                    run { actions.onRecordTeam(team) }
                }
            }
        }
        RecordNotes(mark.conflict, failed)
    }
}

/** The line that says a recording will not happen for want of a slot, and the one for a refused change. */
@Composable
private fun RecordNotes(conflict: Boolean, failed: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (conflict) {
            Text(
                stringResource(Res.string.recordings_conflict),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (failed) {
            Text(
                stringResource(Res.string.change_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.announcesChanges(),
            )
        }
    }
}

/** Record [team], or Stop recording it once a team rule has it. */
@Composable
private fun TeamButton(team: String, recorded: Boolean, onClick: () -> Unit) {
    SofaButton(onClick = onClick, modifier = Modifier.announcesChanges()) {
        Text(
            stringResource(
                if (recorded) Res.string.guide_details_stop_team else Res.string.guide_details_record_team,
                team,
            ),
            maxLines = 1,
        )
    }
}
