package dev.jamesrich.sofaking.ui.recordings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.a11y_button_for
import dev.jamesrich.sofaking.resources.change_failed
import dev.jamesrich.sofaking.resources.recordings_keep
import dev.jamesrich.sofaking.ui.announcesChanges
import dev.jamesrich.sofaking.ui.theme.SofaButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * A row's changes on the server: [failed] once one did not take, and [confirming] between the first and second press
 * of a button that asks before it acts. Buttons stay enabled while the server answers, so the D-pad focus never drops;
 * a press while a change is running does nothing.
 */
@Stable
internal class RowActions(private val scope: CoroutineScope) {
    var confirming by mutableStateOf(false)
    var failed by mutableStateOf(false)
        private set
    private var busy = false

    fun run(action: suspend () -> Boolean) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                failed = !action()
            } finally {
                busy = false
            }
        }
    }

    /** The first press asks, and the second runs [action]. */
    fun confirmThen(action: suspend () -> Boolean) {
        if (busy) return
        if (confirming) {
            confirming = false
            run(action)
        } else {
            confirming = true
            failed = false
        }
    }
}

@Composable
internal fun rememberRowActions(key: String): RowActions {
    val scope = rememberCoroutineScope()
    return remember(key) { RowActions(scope) }
}

/**
 * A row's buttons in one focus group, so Left and Right stay on them and Up and Down move between rows; focus
 * leaving them drops a pending confirmation.
 */
@Composable
internal fun RowButtons(actions: RowActions, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.padding(top = 4.dp).focusGroup().onFocusChanged {
            if (!it.hasFocus) actions.confirming = false
        },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Backs out of a pending confirmation and puts focus back on the button that asked, which [asker] names. [title] is
 * what the row is about, so a screen reader hears which row's change the button backs out of.
 */
@Composable
internal fun KeepButton(actions: RowActions, asker: FocusRequester, title: String? = null) {
    SofaButton(onClick = {
        actions.confirming = false
        asker.requestFocus()
    }) {
        val keep = stringResource(Res.string.recordings_keep)
        if (title == null) Text(keep) else NamedLabel(keep, title)
    }
}

/** A button's [label] as written, and for a screen reader followed by the [title] it acts on. */
@Composable
internal fun NamedLabel(label: String, title: String) {
    val name = stringResource(Res.string.a11y_button_for, label, title)
    Text(label, modifier = Modifier.semantics { contentDescription = name })
}

/** The line under a row whose last change did not take; a screen reader hears it when it appears. */
@Composable
internal fun ChangeFailed() {
    Text(
        stringResource(Res.string.change_failed),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.announcesChanges(),
    )
}
