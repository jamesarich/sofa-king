package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
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
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrimDense
import kotlin.time.Instant
import androidx.compose.foundation.layout.WindowInsetsSides
import dev.jamesrich.sofaking.ui.panelEdgeMargin
import dev.jamesrich.sofaking.ui.screenPadding

/**
 * Bottom 40% channel list over the video. OK tunes and closes it through [onTune]; Back and the idle
 * timer are handled by the owner. Focus starts on the current channel's row.
 */
@Composable
internal fun BoxScope.MiniGuide(
    lineup: Lineup,
    channels: List<Channel>,
    currentIndex: Int,
    previousIndex: Int?,
    now: Instant,
    onTune: (Int) -> Unit,
) {
    val rows = remember(channels.size, previousIndex) { miniGuideRows(channels.size, previousIndex) }
    val focusRow = miniGuideFocusRow(channels.size, previousIndex, currentIndex)
    // Two rows above the focused one, so it sits near the middle of the panel rather than the top edge.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (focusRow - 2).coerceAtLeast(0))
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focusRequester) }

    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .fillMaxHeight(0.4f)
            .background(SofaOverlayScrimDense),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().screenPadding(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).padding(top = panelEdgeMargin())) {
            Text(
                stringResource(Res.string.mini_guide_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).focusRestorer(),
            ) {
                items(rows.size, key = { rows[it].key }) { position ->
                    val row = rows[position]
                    val channel = channels[row.channelIndex]
                    MiniGuideRowItem(
                        row = row,
                        channel = channel,
                        programme = lineup.nowAndNext(channel, now).first,
                        guideLoading = lineup.guideLoading,
                        now = now,
                        current = row.channelIndex == currentIndex,
                        onClick = { onTune(row.channelIndex) },
                        modifier = if (position == focusRow) Modifier.focusRequester(focusRequester) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniGuideRowItem(
    row: MiniGuideRow,
    channel: Channel,
    programme: Programme?,
    guideLoading: Boolean,
    now: Instant,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
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
        }
    }
}
