package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.vod_still_watching
import dev.jamesrich.sofaking.resources.vod_still_watching_in
import dev.jamesrich.sofaking.resources.vod_still_watching_keep
import dev.jamesrich.sofaking.resources.vod_still_watching_keys
import dev.jamesrich.sofaking.resources.vod_still_watching_stop
import dev.jamesrich.sofaking.resources.vod_up_next
import dev.jamesrich.sofaking.resources.vod_up_next_cancel
import dev.jamesrich.sofaking.resources.vod_up_next_in
import dev.jamesrich.sofaking.resources.vod_up_next_keys
import dev.jamesrich.sofaking.resources.vod_up_next_play
import dev.jamesrich.sofaking.ui.Artwork
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import org.jetbrains.compose.resources.stringResource

/** What plays after the item on screen, offered when it ends: its title, the line under it and its picture. */
data class VodUpNext(val title: String, val subtitle: String? = null, val artworkUrl: String? = null)

/**
 * The Up next card in the corner once an item ends, counting [secondsLeft] down to playing [next]. OK and play/pause
 * play it now and Back leaves, through the screen's keys; a pointer gets the two buttons. Asking [stillWatching], it
 * counts down to leaving instead, and OK keeps watching.
 */
@Composable
internal fun BoxScope.UpNextCard(
    next: VodUpNext,
    secondsLeft: Int,
    pointerDriven: Boolean,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    stillWatching: Boolean = false,
) {
    Column(
        Modifier
            .align(Alignment.BottomEnd)
            .screenPadding()
            .width(420.dp)
            .background(SofaOverlayScrim, CardShape)
            .padding(16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(if (stillWatching) Res.string.vod_still_watching else Res.string.vod_up_next),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Box(
            Modifier.fillMaxWidth().aspectRatio(
                16f / 9f,
            ).clip(CardShape).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Artwork(next.artworkUrl, next.title)
        }
        next.subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            next.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            progress = { 1f - secondsLeft.toFloat() / if (stillWatching) STILL_WATCHING_SECONDS else UP_NEXT_SECONDS },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(
                if (stillWatching) Res.string.vod_still_watching_in else Res.string.vod_up_next_in,
                secondsLeft,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (pointerDriven) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SofaButton(onClick = onPlay) {
                    Text(
                        stringResource(
                            if (stillWatching) Res.string.vod_still_watching_keep else Res.string.vod_up_next_play,
                        ),
                    )
                }
                SofaButton(onClick = onCancel) {
                    Text(
                        stringResource(
                            if (stillWatching) Res.string.vod_still_watching_stop else Res.string.vod_up_next_cancel,
                        ),
                    )
                }
            }
        } else {
            Text(
                stringResource(if (stillWatching) Res.string.vod_still_watching_keys else Res.string.vod_up_next_keys),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val CardShape = RoundedCornerShape(8.dp)

/** How long the Up next card waits before playing the next item. */
internal const val UP_NEXT_SECONDS = 10
