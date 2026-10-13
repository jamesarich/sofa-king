package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import dev.jamesrich.sofaking.ui.theme.SofaAmber
import dev.jamesrich.sofaking.ui.theme.SofaDusk
import dev.jamesrich.sofaking.ui.theme.SofaMauve
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrimDense
import dev.jamesrich.sofaking.ui.theme.SofaPlum
import kotlin.time.Instant

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
        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 48.dp, vertical = 27.dp)) {
            Text(
                stringResource(Res.string.mini_guide_title),
                style = MaterialTheme.typography.titleMedium,
                color = SofaAmber,
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
                        now = now,
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
    now: Instant,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    // The focused row is amber with plum text, the same focus the guide grid uses.
    val primary = if (focused) SofaPlum else SofaDusk
    val secondary = if (focused) SofaPlum else SofaMauve
    Row(
        modifier
            .fillMaxWidth()
            .background(if (focused) SofaAmber else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            channel.number.formatNumber(),
            style = MaterialTheme.typography.titleMedium,
            color = primary,
            modifier = Modifier.width(96.dp),
        )
        ChannelLogo(channel, Modifier.padding(end = 16.dp).size(width = 64.dp, height = 40.dp))
        Column(Modifier.weight(1f)) {
            if (row.pinnedLast) {
                Text(
                    stringResource(Res.string.mini_guide_last_channel),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (focused) SofaPlum else SofaAmber,
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
                programme?.title ?: channel.noListingText(),
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
