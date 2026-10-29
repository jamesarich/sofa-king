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
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.data.RecordingWindow
import dev.jamesrich.sofaking.data.SeriesOptions
import dev.jamesrich.sofaking.data.keywordTerm
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.change_failed
import dev.jamesrich.sofaking.resources.recording_keyword_record
import dev.jamesrich.sofaking.resources.recording_keyword_stop
import dev.jamesrich.sofaking.resources.recording_series_this_channel
import dev.jamesrich.sofaking.resources.recording_window_started
import dev.jamesrich.sofaking.resources.recordings_series_any_channel
import dev.jamesrich.sofaking.resources.recordings_series_padding_button
import dev.jamesrich.sofaking.ui.announcesChanges
import dev.jamesrich.sofaking.ui.paddingName
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.windowName
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Padding on the next Record series, which steps through the choices a press at a time. */
@Composable
internal fun PaddingButton(options: SeriesOptions, onChange: (SeriesOptions) -> Unit) {
    val next = options.copy(padding = options.padding.next())
    SofaButton(onClick = { onChange(next) }, modifier = Modifier.announcesChanges()) {
        Text(stringResource(Res.string.recordings_series_padding_button, paddingName(options.padding)), maxLines = 1)
    }
}

/** Whether the next Record series takes this channel only or any channel. */
@Composable
internal fun ChannelScopeButton(options: SeriesOptions, onChange: (SeriesOptions) -> Unit) {
    val flipped = options.copy(anyChannel = !options.anyChannel)
    SofaButton(onClick = { onChange(flipped) }, modifier = Modifier.announcesChanges()) {
        val name = if (options.anyChannel) {
            Res.string.recordings_series_any_channel
        } else {
            Res.string.recording_series_this_channel
        }
        Text(stringResource(name), maxLines = 1)
    }
}

/**
 * Record this channel for the next half hour, hour or two hours, with a line when the server did not take it. It
 * needs no guide, so it is the way to record a channel that lists no programs.
 */
@Composable
internal fun WindowButtons(channel: Channel, onRecordWindow: suspend (Channel, RecordingWindow) -> Boolean) {
    val scope = rememberCoroutineScope()
    var busy by remember(channel) { mutableStateOf(false) }
    var failed by remember(channel) { mutableStateOf(false) }
    var started by remember(channel) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (window in RecordingWindow.entries) {
                SofaButton(
                    onClick = {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                try {
                                    failed = !onRecordWindow(channel, window)
                                    started = !failed
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.announcesChanges(),
                ) { Text(windowName(window), maxLines = 1) }
            }
        }
        if (started) {
            Text(
                stringResource(Res.string.recording_window_started),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.announcesChanges(),
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

/**
 * Beside the guide's search field: Record everything matching the search text, on any channel, or Stop recording it
 * once a keyword rule has it. Hidden until the text is long enough to be a keyword. The search is where a viewer
 * already types the words, so no other page needs a text field for it.
 */
@Composable
internal fun KeywordRecordButton(query: String, marks: RecordingMarks, onRecordKeyword: suspend (String) -> Boolean) {
    val term = keywordTerm(query) ?: return
    val scope = rememberCoroutineScope()
    var busy by remember(term) { mutableStateOf(false) }
    var failed by remember(term) { mutableStateOf(false) }
    val recorded = marks.recordsKeyword(term)
    SofaButton(
        onClick = {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        failed = !onRecordKeyword(term)
                    } finally {
                        busy = false
                    }
                }
            }
        },
        modifier = Modifier.announcesChanges(),
    ) {
        val label = when {
            failed -> stringResource(Res.string.change_failed)
            recorded -> stringResource(Res.string.recording_keyword_stop, term)
            else -> stringResource(Res.string.recording_keyword_record, term)
        }
        Text(label, maxLines = 1)
    }
}
