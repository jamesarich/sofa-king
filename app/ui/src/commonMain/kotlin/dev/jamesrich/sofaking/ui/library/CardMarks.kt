package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_watched
import dev.jamesrich.sofaking.ui.ProgressBar
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import org.jetbrains.compose.resources.stringResource

/** The progress bar along the bottom, the watched tick and a show's unwatched count. */
@Composable
internal fun BoxScope.ItemMarks(item: MediaItem) {
    val progress = item.progress
    when {
        progress?.watched == true -> WatchedTick(Modifier.align(Alignment.TopEnd).padding(8.dp))

        item.unwatched > 0 && (item.kind == ItemKind.SHOW || item.kind == ItemKind.SEASON) -> Text(
            item.unwatched.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(
                Alignment.TopEnd,
            ).padding(
                8.dp,
            ).background(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.shapes.small,
            ).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
    if (progress != null && progress.inProgress) {
        progress.fraction?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter)) }
    }
}

@Composable
internal fun WatchedTick(modifier: Modifier = Modifier) {
    Box(modifier.size(28.dp).background(SofaOverlayScrim, CircleShape), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.Check,
            contentDescription = stringResource(Res.string.library_watched),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}
