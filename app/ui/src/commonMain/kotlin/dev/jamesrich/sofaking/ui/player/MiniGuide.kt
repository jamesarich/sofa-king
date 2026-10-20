package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.ui.theme.SofaChip
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.ui.ChannelLogo
import dev.jamesrich.sofaking.ui.formatNumber
import dev.jamesrich.sofaking.ui.noListingText
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrimDense
import kotlin.time.Instant
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.jamesrich.sofaking.ui.panelEdgeMargin
import dev.jamesrich.sofaking.ui.screenPadding

/**
 * The current channel's actions at the end of the channel list's top row, so a remote with only a D-pad, OK and
 * Back reaches what the CC, Play/Pause and Info keys do: the star ([favorite] null leaves it out), captions, pause
 * or play (Retry while [failed]), and the stream card, which lists the audio tracks when [hasAudioChoice].
 */
internal class MiniGuideActions(
    val captionsOn: Boolean,
    val paused: Boolean,
    val failed: Boolean,
    val hasAudioChoice: Boolean,
    val favorite: Boolean?,
    val onToggleFavorite: () -> Unit,
    val onCaptions: () -> Unit,
    val onPlayPause: () -> Unit,
    val onRetry: () -> Unit,
    val onStreamInfo: () -> Unit,
)

/**
 * Bottom channel list over the video: a chip row (Recent, Favorites, the guide filter the player was opened from,
 * All) above the rows, with the current channel's [actions] at its end. OK tunes and closes it through [onTune];
 * Back and the idle timer are handled by the owner. Focus starts on the current channel's row; Up from the first
 * row reaches the top row. [onChipsFocused] tells the owner when the top row has focus, so Left and Right move
 * along its chips and buttons instead of being swallowed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BoxScope.MiniGuide(
    lineup: Lineup,
    scope: ZapScope?,
    favorites: Set<String>,
    recent: List<String>,
    currentGuideId: String?,
    favoritesOrder: List<String> = emptyList(),
    previousGuideId: String?,
    now: Instant,
    onTune: (Channel) -> Unit,
    onChipsFocused: (Boolean) -> Unit = {},
    actions: MiniGuideActions? = null,
) {
    val scoped = scope != null
    val filters = remember(recent.isNotEmpty(), scoped) { miniGuideFilters(hasRecent = recent.isNotEmpty(), scoped = scoped) }
    var filter by remember(scoped) { mutableStateOf(miniGuideDefaultFilter(scoped)) }
    val rows = remember(filter, lineup.channels, scope, favorites, favoritesOrder, recent, previousGuideId) {
        miniGuideRows(filter, lineup.channels, scope?.guideIds, favorites, recent, previousGuideId, favoritesOrder)
    }
    val focusRow = miniGuideFocusRow(rows, currentGuideId)
    // The focused row starts at the top: the panel holds little more than one row on a 1080p TV, and a row
    // scrolled out of the list cannot take focus, which would leave the D-pad nothing to move.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusRow.coerceAtLeast(0))
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focusRequester) }
    val timeFormat = rememberTimeFormatter()

    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .fillMaxHeight(0.45f)
            .background(SofaOverlayScrimDense),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().screenPadding(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).padding(top = panelEdgeMargin())) {
            // One focus group, so the owner sees the top row as focused whether a chip or a button holds it.
            Row(
                Modifier.fillMaxWidth().focusGroup().onFocusChanged { onChipsFocused(it.hasFocus) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(Res.string.mini_guide_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                LazyRow(
                    modifier = Modifier.weight(1f).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filters.size, key = { filters[it].name }) { position ->
                        val option = filters[position]
                        SofaChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = when (option) {
                                MiniGuideFilter.RECENT -> stringResource(Res.string.mini_guide_recent)
                                MiniGuideFilter.FAVORITES -> stringResource(Res.string.filter_favorites)
                                MiniGuideFilter.SCOPE -> scope?.label.orEmpty()
                                MiniGuideFilter.ALL -> stringResource(Res.string.filter_all)
                            },
                        )
                    }
                }
                if (actions != null) MiniGuideActionRow(actions)
            }
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) {
                Text(
                    stringResource(Res.string.mini_guide_empty_favorites),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides FocusedRowOnTop) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).focusRestorer(),
            ) {
                items(rows.size, key = { rows[it].key }) { position ->
                    val row = rows[position]
                    val (programme, next) = lineup.nowAndNext(row.channel, now)
                    MiniGuideRowItem(
                        row = row,
                        programme = programme,
                        nextLine = next?.let { stringResource(Res.string.mini_guide_next, it.title, timeFormat.format(it.start)) },
                        guideLoading = lineup.guideLoading,
                        now = now,
                        current = row.channel.guideId == currentGuideId,
                        onClick = { onTune(row.channel) },
                        modifier = if (position == focusRow) Modifier.focusRequester(focusRequester) else Modifier,
                    )
                }
            }
            }
        }
    }
}

/**
 * The focused row sits at the top of the list with the next one peeking below it. A TV's default pivots it a third
 * of the way down, which in a panel little taller than one row leaves a sliver of the row above.
 */
@OptIn(ExperimentalFoundationApi::class)
private val FocusedRowOnTop = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset
}

/** The action buttons, chip-high; each does what its remote key does, and the icons show the captions and favorite state. */
@Composable
private fun MiniGuideActionRow(actions: MiniGuideActions) {
    val captionsState = stringResource(if (actions.captionsOn) Res.string.captions_on else Res.string.captions_off)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        actions.favorite?.let { favorite ->
            ControlIcon(
                if (favorite) Icons.Filled.Star else Icons.Outlined.Star,
                stringResource(if (favorite) Res.string.guide_details_remove_favorite else Res.string.guide_details_add_favorite),
                actions.onToggleFavorite,
                size = ACTION_SIZE,
            )
        }
        ControlIcon(
            if (actions.captionsOn) SofaIcons.ClosedCaption else SofaIcons.ClosedCaptionOff,
            stringResource(Res.string.player_control_captions),
            actions.onCaptions,
            size = ACTION_SIZE,
            modifier = Modifier.semantics { stateDescription = captionsState },
        )
        if (actions.failed) {
            ControlIcon(Icons.Filled.Refresh, stringResource(Res.string.player_control_retry), actions.onRetry, size = ACTION_SIZE)
        } else {
            ControlIcon(
                if (actions.paused) Icons.Filled.PlayArrow else SofaIcons.Pause,
                stringResource(if (actions.paused) Res.string.player_control_play else Res.string.player_control_pause),
                actions.onPlayPause,
                size = ACTION_SIZE,
            )
        }
        ControlIcon(
            if (actions.hasAudioChoice) SofaIcons.Audiotrack else Icons.Filled.Info,
            stringResource(Res.string.player_control_stream),
            actions.onStreamInfo,
            size = ACTION_SIZE,
        )
    }
}

/** The action buttons sit a little taller than the 32 dp chips beside them, with room for a 24 dp icon. */
private val ACTION_SIZE = 36.dp

@Composable
private fun MiniGuideRowItem(
    row: MiniGuideRow,
    programme: Programme?,
    nextLine: String?,
    guideLoading: Boolean,
    now: Instant,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val channel = row.channel
    // The focused row is primary with on-primary text, the same focus the guide grid uses.
    val colors = MaterialTheme.colorScheme
    val primary = if (focused) colors.onPrimary else colors.onSurface
    val secondary = if (focused) colors.onPrimary else colors.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .background(if (focused) colors.primary else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            // The row playing now reads as selected; OK on any row tunes it.
            .selectable(selected = current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            channel.number.formatNumber(),
            style = MaterialTheme.typography.titleMedium,
            color = primary,
            modifier = Modifier.width(96.dp),
        )
        ChannelLogo(channel, 64.dp, 40.dp, Modifier.padding(end = 16.dp))
        Column(Modifier.weight(1f)) {
            if (row.pinnedLast) {
                Text(
                    stringResource(Res.string.mini_guide_last_channel),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (focused) colors.onPrimary else colors.primary,
                )
            }
            Text(
                channel.name,
                style = MaterialTheme.typography.titleMedium,
                color = primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                programme?.title ?: channel.noListingText(guideLoading),
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (programme != null) {
                Spacer(Modifier.height(4.dp))
                ProgressBar(programmeProgress(programme.start, programme.stop, now), color = primary)
            }
            if (nextLine != null) {
                Text(
                    nextLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
