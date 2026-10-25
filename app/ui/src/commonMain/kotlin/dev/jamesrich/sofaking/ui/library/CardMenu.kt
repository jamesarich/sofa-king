package dev.jamesrich.sofaking.ui.library

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_details
import dev.jamesrich.sofaking.resources.library_mark_unwatched
import dev.jamesrich.sofaking.resources.library_mark_watched
import dev.jamesrich.sofaking.resources.library_play_from_start
import dev.jamesrich.sofaking.ui.arrowKeysMoveFocus
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What a card's menu does: open its page (null where OK already does), play from the start (null when that is not
 * offered) and mark watched or unwatched.
 */
internal class CardMenu(
    val onOpen: ((MediaItem) -> Unit)? = null,
    val onPlayFromStart: ((MediaItem) -> Unit)?,
    val onSetWatched: (MediaItem, Boolean) -> Unit,
)

/** [menu]'s entries for [item] while [expanded], the first focused; picking one or dismissing it calls [onClose]. */
@Composable
internal fun CardMenuDropdown(item: MediaItem, menu: CardMenu, expanded: Boolean, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    DropdownMenu(expanded = expanded, onDismissRequest = onClose, modifier = Modifier.arrowKeysMoveFocus()) {
        val entries = cardMenuEntries(item, playFromStart = menu.onPlayFromStart != null, details = menu.onOpen != null)
        entries.forEachIndexed { index, entry ->
            DropdownMenuItem(
                text = { Text(stringResource(entry.label)) },
                onClick = {
                    onClose()
                    when (entry) {
                        CardMenuEntry.DETAILS -> menu.onOpen?.invoke(item)
                        CardMenuEntry.PLAY_FROM_START -> menu.onPlayFromStart?.invoke(item)
                        CardMenuEntry.MARK_WATCHED -> menu.onSetWatched(item, true)
                        CardMenuEntry.MARK_UNWATCHED -> menu.onSetWatched(item, false)
                    }
                },
                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
        LaunchedEffect(Unit) { requestFocusWhenAttached(first) }
    }
}

private val CardMenuEntry.label: StringResource
    get() = when (this) {
        CardMenuEntry.DETAILS -> Res.string.library_details
        CardMenuEntry.PLAY_FROM_START -> Res.string.library_play_from_start
        CardMenuEntry.MARK_WATCHED -> Res.string.library_mark_watched
        CardMenuEntry.MARK_UNWATCHED -> Res.string.library_mark_unwatched
    }
