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
import androidx.compose.runtime.Immutable
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
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.data.RecordingMark
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.guide_details_add_favorite
import dev.jamesrich.sofaking.resources.guide_details_cancel_recording
import dev.jamesrich.sofaking.resources.guide_details_close
import dev.jamesrich.sofaking.resources.guide_details_more_airings
import dev.jamesrich.sofaking.resources.guide_details_record
import dev.jamesrich.sofaking.resources.guide_details_record_failed
import dev.jamesrich.sofaking.resources.guide_details_record_series
import dev.jamesrich.sofaking.resources.guide_details_remove_favorite
import dev.jamesrich.sofaking.resources.guide_details_stop_series
import dev.jamesrich.sofaking.resources.guide_details_watch
import dev.jamesrich.sofaking.resources.guide_ended
import dev.jamesrich.sofaking.resources.guide_starts_in
import dev.jamesrich.sofaking.resources.guide_time_left
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.DETAIL_SEPARATOR
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.ProgrammeBadges
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

/** What the details sheet shows: a channel and the programme focused in its row, which may be a gap. */
@Immutable
internal data class ProgrammeDetails(val channel: Channel, val programme: Programme?)

/** The side panel's width on a TV: room for the image beside the title and three buttons on a line. */
private val PANEL_WIDTH = 560.dp

/** The image's width beside the title in the TV panel, at 16:9. */
private val PANEL_IMAGE_WIDTH = 160.dp

/** The bottom sheet's text scrolls past this, so the buttons stay in reach on a phone. */
private val SHEET_TEXT_MAX_HEIGHT = 360.dp

/**
 * The programme's details with Watch and Favorite, and Record and Record series when [recording] says how the
 * programme is set (null on a server that does not record): a side panel over the guide on a TV, where a window of
 * its own would not take D-pad focus, and a Material bottom sheet elsewhere. Back and Close dismiss it. With other
 * [airings] of the programme, More airings lists them in its place; Back returns from the list to the details.
 */
@Composable
internal fun ProgrammeSheet(
    details: ProgrammeDetails,
    now: Instant,
    isFavorite: Boolean,
    guideLoading: Boolean,
    onWatch: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onDismiss: () -> Unit,
    recording: RecordingMark? = null,
    onRecord: suspend (Programme) -> Boolean = { false },
    onRecordSeries: suspend (Programme) -> Boolean = { false },
    airings: List<Airing> = emptyList(),
    onShowAiring: (Airing) -> Unit = {},
) {
    val watchFocus = remember { FocusRequester() }
    val airingsFocus = remember { FocusRequester() }
    val firstAiring = remember { FocusRequester() }
    var showAirings by remember(details) { mutableStateOf(false) }
    var airingsShown by remember(details) { mutableStateOf(false) }
    val back: () -> Unit = { if (showAirings) showAirings = false else onDismiss() }
    val recordable = details.programme?.takeIf {
        recording != null && programmeTiming(it, now) != ProgrammeTiming.Ended
    }
    val buttons: @Composable () -> Unit = {
        SheetButtons(details.channel, isFavorite, watchFocus, onWatch, onToggleFavorite, onDismiss)
        if (recordable != null && recording != null) RecordButtons(recordable, recording, onRecord, onRecordSeries)
        if (airings.isNotEmpty()) {
            SofaButton(onClick = { showAirings = true }, modifier = Modifier.focusRequester(airingsFocus)) {
                Text(stringResource(Res.string.guide_details_more_airings, airings.size), maxLines = 1)
            }
        }
    }
    // OK on an airing on now watches it; on a later one, its details take the sheet's place.
    val airingsList: @Composable (Modifier) -> Unit = { listModifier ->
        AiringsList(
            title = details.programme?.title.orEmpty(),
            airings = airings,
            now = now,
            firstFocus = firstAiring,
            onPick = { airing ->
                if (isAiring(airing.programme, now)) onWatch(airing.channel) else onShowAiring(airing)
            },
            onBack = back,
            modifier = listModifier,
        )
    }
    // Watch takes focus on every layout, so the remote or a keyboard can act on the panel or sheet at once.
    LaunchedEffect(details) { requestFocusWhenAttached(watchFocus) }
    // The list's first airing takes focus as it opens, and More airings again as it closes.
    LaunchedEffect(showAirings) {
        if (showAirings) {
            airingsShown = true
            requestFocusWhenAttached(firstAiring)
        } else if (airingsShown) {
            requestFocusWhenAttached(airingsFocus)
        }
    }
    if (LocalLayoutProfile.current.tv) {
        BackHandler(onBack = back)
        Box(
            Modifier
                .fillMaxSize()
                // Compose turns the remote's Back into a focus exit before BackHandler sees it, and the panel cancels
                // exits, so Back is taken here. It closes on key-up, so no part of the
                // press reaches the guide under it.
                .onPreviewKeyEvent { event ->
                    if (!event.key.closesDetails()) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) back()
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
                if (showAirings) {
                    airingsList(
                        Modifier.fillMaxSize().padding(24.dp),
                    )
                } else {
                    PanelContent(details, now, guideLoading, buttons)
                }
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

/**
 * The TV panel: the image beside the heading, the description in as many whole lines as the height left holds,
 * ellipsized because the D-pad cannot scroll text, and the buttons at the bottom.
 */
@Composable
private fun PanelContent(
    details: ProgrammeDetails,
    now: Instant,
    guideLoading: Boolean,
    buttons: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            details.programme?.icon?.let { ProgrammeImage(it, Modifier.width(PANEL_IMAGE_WIDTH).aspectRatio(16f / 9f)) }
            Heading(details, now, guideLoading, Modifier.weight(1f))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            details.programme?.description?.let {
                WholeLinesText(it, MaterialTheme.typography.bodyMedium, Modifier.fillMaxSize())
            }
        }
        buttons()
    }
}

/** The bottom sheet: the full-width image, heading and description scroll above the buttons. */
@Composable
private fun SheetContent(
    details: ProgrammeDetails,
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
            details.programme?.icon?.let { ProgrammeImage(it, Modifier.fillMaxWidth().height(180.dp)) }
            Heading(details, now, guideLoading)
            details.programme?.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        buttons()
    }
}

@Composable
private fun ProgrammeImage(url: String, modifier: Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(MaterialTheme.shapes.medium),
    )
}

/** Title, episode, time with badges and the channel; a gap shows why it has no listing. */
@Composable
private fun Heading(details: ProgrammeDetails, now: Instant, guideLoading: Boolean, modifier: Modifier = Modifier) {
    val timeFormat = rememberTimeFormatter()
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val programme = details.programme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (programme == null) {
            Text(details.channel.noListingText(guideLoading), style = MaterialTheme.typography.titleLarge)
        } else {
            Text(
                programme.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            programme.episodeLine()?.let {
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
                    timeFormat.range(programme.start, programme.stop),
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondary,
                )
                ProgrammeBadges(
                    programme = programme,
                    live = isAiring(programme, now),
                    fill = MaterialTheme.colorScheme.primary,
                    onFill = MaterialTheme.colorScheme.onPrimary,
                    outline = secondary,
                )
            }
            Text(programmeTimingText(programme, now), style = MaterialTheme.typography.bodyMedium, color = secondary)
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

/** Watch, the favorite toggle and Close; a line too narrow for all three wraps rather than squeezing one. */
@Composable
private fun SheetButtons(
    channel: Channel,
    isFavorite: Boolean,
    watchFocus: FocusRequester,
    onWatch: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onDismiss: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SofaButton(onClick = { onWatch(channel) }, modifier = Modifier.focusRequester(watchFocus)) {
            Text(stringResource(Res.string.guide_details_watch), maxLines = 1)
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

/**
 * Record or cancel the programme, and record or stop its series, with a line when the server did not take the
 * change. A press waits for the server, so a second press cannot undo the first before the marks catch up.
 */
@Composable
private fun RecordButtons(
    programme: Programme,
    mark: RecordingMark,
    onRecord: suspend (Programme) -> Boolean,
    onRecordSeries: suspend (Programme) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(programme) { mutableStateOf(false) }
    var failed by remember(programme) { mutableStateOf(false) }
    fun run(action: suspend (Programme) -> Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                failed = !action(programme)
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
            SofaButton(onClick = { run(onRecord) }) {
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
            SofaButton(onClick = { run(onRecordSeries) }) {
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

/** "42 min left", "Starts in 1 h 20 min" or "Ended", from [programmeTiming]. */
@Composable
private fun programmeTimingText(programme: Programme, now: Instant): String =
    when (val timing = programmeTiming(programme, now)) {
        is ProgrammeTiming.Airing -> stringResource(Res.string.guide_time_left, timing.left.hoursAndMinutes())
        is ProgrammeTiming.Upcoming -> stringResource(Res.string.guide_starts_in, timing.until.hoursAndMinutes())
        ProgrammeTiming.Ended -> stringResource(Res.string.guide_ended)
    }
