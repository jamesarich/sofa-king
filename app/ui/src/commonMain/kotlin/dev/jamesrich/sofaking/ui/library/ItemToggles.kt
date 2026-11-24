package dev.jamesrich.sofaking.ui.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_add_to_watchlist
import dev.jamesrich.sofaking.resources.library_mark_unwatched
import dev.jamesrich.sofaking.resources.library_mark_watched
import dev.jamesrich.sofaking.resources.library_remove_from_watchlist
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.announcesChanges
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.SofaIconToggle
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Mark watched or unwatched. A TV's remote reads text, so it gets a button; a phone, tablet or desktop window gets an
 * icon toggle, which sits beside Play instead of wrapping onto a line of its own.
 */
@Composable
internal fun WatchedAction(
    item: MediaItem,
    watched: Boolean,
    actions: ItemDetailsActions,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(if (watched) Res.string.library_mark_unwatched else Res.string.library_mark_watched)
    val announcing = modifier.announcesChanges()
    if (LocalLayoutProfile.current.tv) {
        SofaButton(onClick = { actions.onSetWatched(item, !watched) }, modifier = announcing) { Text(label) }
    } else {
        SofaIconToggle(
            checked = watched,
            onCheckedChange = { actions.onSetWatched(item, it) },
            icon = Icons.Filled.Check,
            label = label,
            modifier = announcing,
        )
    }
}

/** Add to the household's watchlist or take it off: a text button on a TV, an icon toggle elsewhere. */
@Composable
internal fun WatchlistAction(item: MediaItem, listed: Boolean, actions: ItemDetailsActions) {
    val label = stringResource(
        if (listed) Res.string.library_remove_from_watchlist else Res.string.library_add_to_watchlist,
    )
    if (LocalLayoutProfile.current.tv) {
        SofaButton(onClick = {
            actions.onToggleWatchlist(item)
        }, modifier = Modifier.announcesChanges()) { Text(label) }
    } else {
        SofaIconToggle(
            checked = listed,
            onCheckedChange = { actions.onToggleWatchlist(item) },
            icon = SofaIcons.Bookmark,
            label = label,
            modifier = Modifier.announcesChanges(),
        )
    }
}
