package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.Airing
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.OnLaterKind
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.RecordingMark
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.data.airingsOf
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.guide_today
import dev.jamesrich.sofaking.resources.guide_tomorrow
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.resources.on_later
import dev.jamesrich.sofaking.resources.on_later_empty
import dev.jamesrich.sofaking.resources.on_later_failed
import dev.jamesrich.sofaking.resources.on_later_kids
import dev.jamesrich.sofaking.resources.on_later_movies
import dev.jamesrich.sofaking.resources.on_later_premieres
import dev.jamesrich.sofaking.resources.on_later_sports
import dev.jamesrich.sofaking.resources.on_later_when
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.ProgramBadges
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.listRowColors
import dev.jamesrich.sofaking.ui.listRowShapes
import dev.jamesrich.sofaking.ui.rememberMinuteNow
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.sectionHeading
import dev.jamesrich.sofaking.ui.theme.SofaChip
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** What On Later is showing for the chosen kind. */
private sealed interface OnLaterLoad {
    data object Loading : OnLaterLoad

    data object Failed : OnLaterLoad

    data class Loaded(val airings: List<Airing>) : OnLaterLoad
}

/**
 * On Later: the movies, sports, kids' programs or premieres coming up on the channels in [lineup], soonest first,
 * as [load] reads them from the server. OK on a program opens its details, where it can be watched, recorded or
 * put on the watchlist through [actions]; closing them returns focus to its row.
 */
@Composable
internal fun OnLaterScreen(
    lineup: Lineup,
    favorites: Set<String>,
    recordingMarks: RecordingMarks?,
    actions: GuideActions,
    load: suspend (OnLaterKind) -> List<Program>?,
    onWatch: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    var kind by rememberSaveable { mutableStateOf(OnLaterKind.MOVIES) }
    var attempt by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf<OnLaterLoad>(OnLaterLoad.Loading) }
    val currentLoad by rememberUpdatedState(load)
    LaunchedEffect(kind, attempt, lineup.channels) {
        shown = OnLaterLoad.Loading
        // Only the channels the viewer keeps in the guide; the server lists every enabled one.
        val channels = lineup.channels.associateBy { it.guideId }
        shown = currentLoad(kind)?.let { programs ->
            OnLaterLoad.Loaded(
                programs.mapNotNull { program -> channels[program.channelId]?.let { Airing(it, program) } },
            )
        } ?: OnLaterLoad.Failed
    }
    var details by remember { mutableStateOf<ProgramDetails?>(null) }
    var returnTo by remember { mutableStateOf<Program?>(null) }
    val now by rememberMinuteNow()
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().screenPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OnLaterTitle()
            KindChips(kind, onKind = { kind = it })
            when (val s = shown) {
                OnLaterLoad.Loading -> MessageScreen(stringResource(Res.string.loading), loading = true)

                OnLaterLoad.Failed -> MessageScreen(
                    message = stringResource(Res.string.on_later_failed),
                    onRetry = { attempt++ },
                )

                is OnLaterLoad.Loaded -> if (s.airings.isEmpty()) {
                    MessageScreen(stringResource(Res.string.on_later_empty))
                } else {
                    OnLaterList(s.airings, now, returnTo, onOpen = { details = ProgramDetails(it.channel, it.program) })
                }
            }
        }
        val open = details
        if (open != null) {
            OnLaterDetails(
                open,
                now,
                lineup,
                favorites,
                recordingMarks,
                actions,
                onShow = { details = it },
                onWatch = { channel ->
                    details = null
                    onWatch(channel)
                },
                onClose = {
                    returnTo = open.program
                    details = null
                },
            )
        }
    }
}

/** The details of [open]: [onShow] swaps in another airing's, or none as a recording opens from its start. */
@Composable
private fun OnLaterDetails(
    open: ProgramDetails,
    now: Instant,
    lineup: Lineup,
    favorites: Set<String>,
    recordingMarks: RecordingMarks?,
    actions: GuideActions,
    onShow: (ProgramDetails?) -> Unit,
    onWatch: (Channel) -> Unit,
    onClose: () -> Unit,
) {
    ProgramSheet(
        details = open,
        now = now,
        isFavorite = open.channel.guideId in favorites,
        guideLoading = lineup.guideLoading,
        onWatch = onWatch,
        onToggleFavorite = actions.onToggleFavorite,
        onDismiss = onClose,
        recording = recordingMarks?.let { marks -> open.program?.let(marks::of) ?: RecordingMark() },
        onRecord = actions.onRecord,
        onRecordSeries = actions.onRecordSeries,
        onRecordTeam = actions.onRecordTeam,
        onRecordWindow = actions.onRecordWindow,
        onWatchFromStart = { recordingId ->
            onShow(null)
            actions.onWatchFromStart(recordingId)
        },
        airings = remember(lineup, open.program) { open.program?.let { lineup.airingsOf(it, now) }.orEmpty() },
        onShowAiring = { airing -> onShow(ProgramDetails(airing.channel, airing.program)) },
        watchlisted = open.program?.let(actions.watchlisted),
        onToggleWatchlist = actions.onToggleWatchlist,
    )
}

@Composable
private fun KindChips(kind: OnLaterKind, onKind: (OnLaterKind) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(first) }
    // Room at the ends for the focused chip, which grows and draws its ring past its own bounds.
    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(OnLaterKind.entries, key = { it.name }) { option ->
            SofaChip(
                selected = option == kind,
                onClick = { onKind(option) },
                label = stringResource(option.label),
                modifier = if (option == kind) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}

private val OnLaterKind.label: StringResource
    get() = when (this) {
        OnLaterKind.MOVIES -> Res.string.on_later_movies
        OnLaterKind.SPORTS -> Res.string.on_later_sports
        OnLaterKind.KIDS -> Res.string.on_later_kids
        OnLaterKind.PREMIERES -> Res.string.on_later_premieres
    }

/** The programs, one row each; [returnTo] is the row whose details just closed, which takes focus back. */
@Composable
private fun OnLaterList(airings: List<Airing>, now: Instant, returnTo: Program?, onOpen: (Airing) -> Unit) {
    val timeFormat = rememberTimeFormatter()
    val today = stringResource(Res.string.guide_today)
    val tomorrow = stringResource(Res.string.guide_tomorrow)
    val back = remember { FocusRequester() }
    LaunchedEffect(returnTo) { if (returnTo != null) requestFocusWhenAttached(back) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(airings, key = { "${it.channel.guideId}:${it.program.start}" }) { airing ->
            OnLaterRow(
                airing = airing,
                live = isAiring(airing.program, now),
                whenText = stringResource(
                    Res.string.on_later_when,
                    airingWhen(airing, now, timeFormat, today, tomorrow),
                    airing.channel.label(),
                ),
                onClick = { onOpen(airing) },
                modifier = if (airing.program == returnTo) Modifier.focusRequester(back) else Modifier,
            )
        }
    }
}

@Composable
private fun OnLaterRow(
    airing: Airing,
    live: Boolean,
    whenText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shapes = listRowShapes()
    val interactions = remember { MutableInteractionSource() }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().tvFocus(shapes.shape, focusedScale = 1f),
        supportingContent = { Text(whenText, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            ProgramBadges(
                program = airing.program,
                live = live,
                fill = MaterialTheme.colorScheme.primary,
                onFill = MaterialTheme.colorScheme.onPrimary,
                outline = secondary,
            )
        },
        shapes = shapes,
        colors = listRowColors(interactions),
        interactionSource = interactions,
    ) {
        Text(
            airing.program.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OnLaterTitle() {
    Text(
        stringResource(Res.string.on_later),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.sectionHeading(),
    )
}
