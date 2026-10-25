package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jamesrich.sofaking.data.Airing
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.RecordingMark
import dev.jamesrich.sofaking.data.teamSides
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.guide_details_add_favorite
import dev.jamesrich.sofaking.resources.guide_details_cancel_recording
import dev.jamesrich.sofaking.resources.guide_details_close
import dev.jamesrich.sofaking.resources.guide_details_more_airings
import dev.jamesrich.sofaking.resources.guide_details_record
import dev.jamesrich.sofaking.resources.guide_details_record_failed
import dev.jamesrich.sofaking.resources.guide_details_record_series
import dev.jamesrich.sofaking.resources.guide_details_record_team
import dev.jamesrich.sofaking.resources.guide_details_remove_favorite
import dev.jamesrich.sofaking.resources.guide_details_stop_series
import dev.jamesrich.sofaking.resources.guide_details_stop_team
import dev.jamesrich.sofaking.resources.guide_details_watch
import dev.jamesrich.sofaking.resources.guide_ended
import dev.jamesrich.sofaking.resources.guide_starts_in
import dev.jamesrich.sofaking.resources.guide_time_left
import dev.jamesrich.sofaking.resources.player_control_from_start
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.ProgramBadges
import dev.jamesrich.sofaking.ui.WIDESCREEN
import dev.jamesrich.sofaking.ui.WholeLinesText
import dev.jamesrich.sofaking.ui.arrowKeysMoveFocus
import dev.jamesrich.sofaking.ui.episodeLine
import dev.jamesrich.sofaking.ui.hoursAndMinutes
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.noListingText
import dev.jamesrich.sofaking.ui.panelEdgeMargin
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaButton
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** The side panel's width on a TV: room for the image beside the title and three buttons on a line. */
private val PANEL_WIDTH = 560.dp

/** The image's width beside the title in the TV panel, at 16:9. */
private val PANEL_IMAGE_WIDTH = 160.dp

/** The bottom sheet's text scrolls past this, so the buttons stay in reach on a phone. */
private val SHEET_TEXT_MAX_HEIGHT = 360.dp

/**
 * The program's details with Watch and Favorite, and Record and Record series when [recording] says how the
 * program is set (null on a server that does not record), with Record for each team a sports program names: a side
 * panel over the guide on a TV, where a window of its own would not take D-pad focus, and a Material bottom sheet
 * elsewhere. Back and Close dismiss it. With other
 * [airings] of the program, More airings lists them in its place; Back returns from the list to the details.
 */
@Composable
internal fun ProgramSheet(
    details: ProgramDetails,
    now: Instant,
    isFavorite: Boolean,
    guideLoading: Boolean,
    onWatch: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onDismiss: () -> Unit,
    recording: RecordingMark? = null,
    onRecord: suspend (Program) -> Boolean = { false },
    onRecordSeries: suspend (Program) -> Boolean = { false },
    onRecordTeam: suspend (String) -> Boolean = { false },
    onWatchFromStart: (recordingId: String) -> Unit = {},
    airings: List<Airing> = emptyList(),
    onShowAiring: (Airing) -> Unit = {},
) {
    val watchFocus = remember { FocusRequester() }
    val airingsFocus = remember { FocusRequester() }
    val firstAiring = remember { FocusRequester() }
    var showAirings by remember(details) { mutableStateOf(false) }
    val back: () -> Unit = { if (showAirings) showAirings = false else onDismiss() }
    val recordable = details.program?.takeIf { recording != null && programTiming(it, now) != ProgramTiming.Ended }
    val buttons: @Composable () -> Unit = {
        // A recording being written plays from the program's start, while the program is still on.
        val fromStart = recording?.runningId?.takeIf { recordable != null }?.let { id -> { onWatchFromStart(id) } }
        SheetButtons(details.channel, isFavorite, watchFocus, onWatch, fromStart, onToggleFavorite, onDismiss)
        if (recordable != null && recording != null) {
            RecordButtons(recordable, recording, RecordActions(onRecord, onRecordSeries, onRecordTeam))
        }
        if (airings.isNotEmpty()) {
            SofaButton(onClick = { showAirings = true }, modifier = Modifier.focusRequester(airingsFocus)) {
                Text(stringResource(Res.string.guide_details_more_airings, airings.size), maxLines = 1)
            }
        }
    }
    // OK on an airing on now watches it; on a later one, its details take the sheet's place.
    val airingsList: @Composable (Modifier) -> Unit = { listModifier ->
        AiringsList(
            title = details.program?.title.orEmpty(),
            airings = airings,
            now = now,
            firstFocus = firstAiring,
            onPick = { airing -> if (isAiring(airing.program, now)) onWatch(airing.channel) else onShowAiring(airing) },
            onBack = back,
            modifier = listModifier,
        )
    }
    // Watch takes focus on every layout, so the remote or a keyboard can act on the panel or sheet at once.
    LaunchedEffect(details) { requestFocusWhenAttached(watchFocus) }
    AiringsFocus(details, showAirings, firstAiring, airingsFocus)
    if (LocalLayoutProfile.current.tv) {
        BackHandler(onBack = back)
        DetailsPanel(onBack = back, onDismiss = onDismiss) {
            if (showAirings) {
                airingsList(Modifier.fillMaxSize().padding(24.dp))
            } else {
                PanelContent(details, now, guideLoading, buttons)
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            BackHandler(enabled = showAirings, onBack = back)
            Box(Modifier.arrowKeysMoveFocus()) {
                if (showAirings) {
                    airingsList(
                        Modifier.fillMaxWidth().heightIn(
                            max = SHEET_TEXT_MAX_HEIGHT + 120.dp,
                        ).padding(horizontal = 24.dp).padding(bottom = 24.dp),
                    )
                } else {
                    SheetContent(details, now, guideLoading, buttons)
                }
            }
        }
    }
}

/** The list's first airing takes focus as it opens, and More airings again as it closes. */
@Composable
private fun AiringsFocus(
    details: ProgramDetails,
    showAirings: Boolean,
    firstAiring: FocusRequester,
    airingsFocus: FocusRequester,
) {
    var airingsShown by remember(details) { mutableStateOf(false) }
    LaunchedEffect(showAirings) {
        if (showAirings) {
            airingsShown = true
            requestFocusWhenAttached(firstAiring)
        } else if (airingsShown) {
            requestFocusWhenAttached(airingsFocus)
        }
    }
}

/**
 * The TV's side panel over a scrim: Back closes it through [onBack], a tap on the scrim through [onDismiss], and focus
 * stays inside it.
 */
@Composable
private fun DetailsPanel(onBack: () -> Unit, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            // Compose turns the remote's Back into a focus exit before BackHandler sees it, and the panel cancels
            // exits, so Back is taken here. It closes on key-up, so no part of the
            // press reaches the guide under it.
            .onPreviewKeyEvent { event ->
                if (!event.key.closesDetails()) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) onBack()
                true
            }
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            // The scrim takes a tap or click, so touch on a TV closes it as Back does; no
            // focus cue, it is not a control.
            .clickable(
                interactionSource = remember {
                    MutableInteractionSource()
                },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Surface(
            // Darker than the buttons' raised plum, so an unfocused button still reads as a button.
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.align(Alignment.CenterEnd)
                .screenPadding(WindowInsetsSides.End + WindowInsetsSides.Bottom)
                .padding(top = panelEdgeMargin())
                .fillMaxHeight()
                .width(PANEL_WIDTH)
                // Focus stays in the panel: a D-pad move past its buttons must not land on the grid under it.
                .focusProperties { onExit = { cancelFocusChange() } }
                .clickable(
                    interactionSource = remember {
                        MutableInteractionSource()
                    },
                    indication = null,
                    onClick = {},
                ),
        ) {
            content()
        }
    }
}

/**
 * The TV panel: the image beside the heading, the description in as many whole lines as the height left holds,
 * ellipsized because the D-pad cannot scroll text, and the buttons at the bottom.
 */
@Composable
private fun PanelContent(
    details: ProgramDetails,
    now: Instant,
    guideLoading: Boolean,
    buttons: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            details.program?.icon?.let { ProgramImage(it, Modifier.width(PANEL_IMAGE_WIDTH).aspectRatio(WIDESCREEN)) }
            Heading(details, now, guideLoading, Modifier.weight(1f))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            details.program?.description?.let {
                WholeLinesText(it, MaterialTheme.typography.bodyMedium, Modifier.fillMaxSize())
            }
        }
        buttons()
    }
}

/** The bottom sheet: the full-width image, heading and description scroll above the buttons. */
@Composable
private fun SheetContent(
    details: ProgramDetails,
    now: Instant,
    guideLoading: Boolean,
    buttons: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.heightIn(max = SHEET_TEXT_MAX_HEIGHT).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            details.program?.icon?.let { ProgramImage(it, Modifier.fillMaxWidth().height(180.dp)) }
            Heading(details, now, guideLoading)
            details.program?.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        buttons()
    }
}

@Composable
private fun ProgramImage(url: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(MaterialTheme.shapes.medium),
    )
}

/** Title, episode, time with badges and the channel; a gap shows why it has no listing. */
@Composable
private fun Heading(details: ProgramDetails, now: Instant, guideLoading: Boolean, modifier: Modifier = Modifier) {
    val timeFormat = rememberTimeFormatter()
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val program = details.program
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (program == null) {
            Text(details.channel.noListingText(guideLoading), style = MaterialTheme.typography.titleLarge)
        } else {
            Text(
                program.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            program.episodeLine()?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The badges ride on the time range and the time left has its own line, so neither wraps mid-phrase.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    timeFormat.range(program.start, program.stop),
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondary,
                )
                ProgramBadges(
                    program = program,
                    live = isAiring(program, now),
                    fill = MaterialTheme.colorScheme.primary,
                    onFill = MaterialTheme.colorScheme.onPrimary,
                    outline = secondary,
                )
            }
            Text(programTimingText(program, now), style = MaterialTheme.typography.bodyMedium, color = secondary)
        }
        Text(
            details.channel.label(),
            style = MaterialTheme.typography.bodyMedium,
            color = secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Watch, Watch from start where [fromStart] is given, the favorite toggle and Close; a line too narrow for all of them
 * wraps rather than squeezing one.
 */
@Composable
private fun SheetButtons(
    channel: Channel,
    isFavorite: Boolean,
    watchFocus: FocusRequester,
    onWatch: (Channel) -> Unit,
    fromStart: (() -> Unit)?,
    onToggleFavorite: (Channel) -> Unit,
    onDismiss: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SofaButton(onClick = { onWatch(channel) }, modifier = Modifier.focusRequester(watchFocus)) {
            Text(stringResource(Res.string.guide_details_watch), maxLines = 1)
        }
        if (fromStart != null) {
            SofaButton(onClick = fromStart) {
                Text(stringResource(Res.string.player_control_from_start), maxLines = 1)
            }
        }
        SofaButton(onClick = { onToggleFavorite(channel) }) {
            Text(
                stringResource(
                    if (isFavorite) Res.string.guide_details_remove_favorite else Res.string.guide_details_add_favorite,
                ),
                maxLines = 1,
            )
        }
        SofaButton(onClick = onDismiss) { Text(stringResource(Res.string.guide_details_close), maxLines = 1) }
    }
}

/** What the record buttons ask of the server; each answers false when it did not take the change. */
private class RecordActions(
    val onRecord: suspend (Program) -> Boolean,
    val onRecordSeries: suspend (Program) -> Boolean,
    val onRecordTeam: suspend (String) -> Boolean,
)

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
            SofaButton(onClick = { run(actions.onRecord) }) {
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
            SofaButton(onClick = { run(actions.onRecordSeries) }) {
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
            for (team in teams) {
                TeamButton(team, recorded = team.lowercase() in mark.teams) {
                    run { actions.onRecordTeam(team) }
                }
            }
        }
        if (failed) {
            Text(
                stringResource(Res.string.guide_details_record_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Record [team], or Stop recording it once a team rule has it. */
@Composable
private fun TeamButton(team: String, recorded: Boolean, onClick: () -> Unit) {
    SofaButton(onClick = onClick) {
        Text(
            stringResource(
                if (recorded) Res.string.guide_details_stop_team else Res.string.guide_details_record_team,
                team,
            ),
            maxLines = 1,
        )
    }
}

/** "42 min left", "Starts in 1 h 20 min" or "Ended", from [programTiming]. */
@Composable
private fun programTimingText(program: Program, now: Instant): String =
    when (val timing = programTiming(program, now)) {
        is ProgramTiming.Airing -> stringResource(Res.string.guide_time_left, timing.left.hoursAndMinutes())
        is ProgramTiming.Upcoming -> stringResource(Res.string.guide_starts_in, timing.until.hoursAndMinutes())
        ProgramTiming.Ended -> stringResource(Res.string.guide_ended)
    }
