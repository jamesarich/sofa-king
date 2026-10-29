package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.RecordingState
import dev.jamesrich.sofaking.data.RecordingsState
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.resources.recordings_loading
import dev.jamesrich.sofaking.resources.settings
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.VodMarker
import dev.jamesrich.sofaking.ui.player.VodMarkerKind
import dev.jamesrich.sofaking.ui.player.VodPlayerScreen
import dev.jamesrich.sofaking.ui.player.VodTitle
import dev.jamesrich.sofaking.ui.recordings.RecordingsScreen
import dev.jamesrich.sofaking.ui.recordings.recordingSubtitle
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import org.jetbrains.compose.resources.stringResource

/** The Recordings tab: the list, read again each time it opens. [tabBack] climbs to the header, then opens Live. */
@Composable
internal fun RecordingsDestination(
    arrival: Arrival,
    shell: AppShellState,
    recordingsVm: RecordingsViewModel,
    tabBack: () -> Unit,
    headerFocused: Boolean,
) {
    val recordings by recordingsVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { recordingsVm.refresh() }
    RecordingsScreen(
        state = recordings,
        onPlay = { recording, fromStart -> shell.push(Route.Recording(recording.id, fromStart)) },
        onDelete = recordingsVm::delete,
        onRetry = recordingsVm::refresh,
        onBack = tabBack,
        onRuleChange = { rule, edit ->
            recordingsVm.updateRule(rule, edit.newOnly, edit.keep, edit.padding, edit.anyChannel)
        },
        onRuleDelete = recordingsVm::deleteRule,
        onProtect = recordingsVm::setProtected,
        returnedFrom = arrival.returnedFrom,
        headerFocused = headerFocused,
    )
}

/**
 * A recording full screen, skipping its commercial breaks when Settings says so; one gone from
 * the list sends the viewer back.
 */
@Composable
internal fun RecordingDestination(
    route: Route.Recording,
    shell: AppShellState,
    vm: LineupViewModel,
    recordingsVm: RecordingsViewModel,
    player: LivePlayer,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val prefs = settings ?: AppSettings()
    val recordings by recordingsVm.state.collectAsStateWithLifecycle()
    // Kept once found, so a list reload while it plays never swaps the title or markers under the player.
    val recording =
        remember(route.recordingId, recordings is RecordingsState.Ready) { recordingsVm.recording(route.recordingId) }
    when {
        recording != null -> VodPlayerScreen(
            title = recording.vodTitle(),
            player = player,
            sessions = vm.sessions,
            itemId = recording.id,
            open = { recordingsVm.play(recording, player.playbackProfile, route.fromStart) },
            onPosition = { position, _ -> recordingsVm.progress(recording.id, position) },
            onStop = { position, duration -> recordingsVm.stopped(recording.id, position, duration) },
            onExit = shell::back,
            skips = prefs.skips,
            seekSteps = prefs.seekSteps,
            captionsOn = prefs.captionsEnabled,
            onToggleCaptions = { vm.setCaptionsEnabled(!prefs.captionsEnabled) },
            captionStyle = prefs.captionStyle,
            zoom = shell.videoZoom,
            onZoom = { shell.videoZoom = it },
            sleep = shell.sleep,
            onBackToLive = { shell.toLive(recording.channelId) }.takeIf { recording.state == RecordingState.RECORDING },
        )

        recordings is RecordingsState.Ready || recordings is RecordingsState.Failed -> LaunchedEffect(Unit) {
            shell.back()
        }

        else -> MessageScreen(stringResource(Res.string.recordings_loading), loading = true)
    }
}

/** What the player shows for a recording: its episode and when it aired, with the commercial breaks marked. */
@Composable
private fun DvrRecording.vodTitle(): VodTitle {
    val time = rememberTimeFormatter()
    return VodTitle(
        title = title,
        subtitle = recordingSubtitle(this, time),
        artworkUrl = imageUrl,
        markers = breaks.map { VodMarker(it.startMs, it.endMs, VodMarkerKind.BREAK) },
    )
}
