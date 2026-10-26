package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.jamesrich.sofaking.ui.Artwork
import dev.jamesrich.sofaking.ui.screenPadding

/**
 * What a song shows where a picture would be: its cover, as big as a little over half the screen's height, with its
 * title and its artist and album under it, above the bar's place. The surface already names it to a screen reader.
 */
@Composable
internal fun NowPlayingCover(title: VodTitle) {
    BoxWithConstraints(Modifier.fillMaxSize().screenPadding().clearAndSetSemantics {}) {
        val side = min(maxHeight * COVER_HEIGHT, maxWidth * COVER_WIDTH)
        Column(
            Modifier.align(Alignment.Center).padding(bottom = maxHeight * LIFT),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(side).clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Artwork(title.artworkUrl, label = null)
            }
            Text(
                title.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            title.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The cover's side as a share of the screen's height and of its width, whichever is smaller. */
private const val COVER_HEIGHT = 0.5f
private const val COVER_WIDTH = 0.7f

/** How far above the middle the cover sits, as a share of the height, to stay clear of the bar. */
private const val LIFT = 0.12f
