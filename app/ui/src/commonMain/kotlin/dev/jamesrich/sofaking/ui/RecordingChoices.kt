package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import dev.jamesrich.sofaking.data.RecordingPadding
import dev.jamesrich.sofaking.data.RecordingWindow
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.recording_padding_custom
import dev.jamesrich.sofaking.resources.recording_padding_default
import dev.jamesrich.sofaking.resources.recording_padding_fifteen
import dev.jamesrich.sofaking.resources.recording_padding_five
import dev.jamesrich.sofaking.resources.recording_padding_none
import dev.jamesrich.sofaking.resources.recording_padding_overrun
import dev.jamesrich.sofaking.resources.recording_window_half_hour
import dev.jamesrich.sofaking.resources.recording_window_one_hour
import dev.jamesrich.sofaking.resources.recording_window_two_hours
import org.jetbrains.compose.resources.stringResource

/** The short name of a padding choice, as the Padding button and the choice row show it; a portal's is Custom. */
@Composable
internal fun paddingName(padding: RecordingPadding?): String = stringResource(
    when (padding) {
        RecordingPadding.DEFAULT -> Res.string.recording_padding_default
        RecordingPadding.NONE -> Res.string.recording_padding_none
        RecordingPadding.FIVE_MINUTES -> Res.string.recording_padding_five
        RecordingPadding.FIFTEEN_MINUTES -> Res.string.recording_padding_fifteen
        RecordingPadding.OVERRUN -> Res.string.recording_padding_overrun
        null -> Res.string.recording_padding_custom
    },
)

/** The text of a window button, such as "Record next hour". */
@Composable
internal fun windowName(window: RecordingWindow): String = stringResource(
    when (window) {
        RecordingWindow.HALF_HOUR -> Res.string.recording_window_half_hour
        RecordingWindow.ONE_HOUR -> Res.string.recording_window_one_hour
        RecordingWindow.TWO_HOURS -> Res.string.recording_window_two_hours
    },
)
