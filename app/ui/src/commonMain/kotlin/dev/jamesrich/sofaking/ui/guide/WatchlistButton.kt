package dev.jamesrich.sofaking.ui.guide

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.guide_details_add_to_watchlist
import dev.jamesrich.sofaking.resources.guide_details_remove_from_watchlist
import dev.jamesrich.sofaking.ui.theme.SofaButton
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Adds [program]'s title to the watchlist for every channel, or takes it off when it is [listed]. */
@Composable
internal fun WatchlistButton(program: Program, listed: Boolean, onToggle: suspend (Program) -> Boolean) {
    val scope = rememberCoroutineScope()
    var busy by remember(program) { mutableStateOf(false) }
    SofaButton(onClick = {
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    onToggle(program)
                } finally {
                    busy = false
                }
            }
        }
    }) {
        val label = if (listed) {
            Res.string.guide_details_remove_from_watchlist
        } else {
            Res.string.guide_details_add_to_watchlist
        }
        Text(stringResource(label), maxLines = 1)
    }
}
