package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.pairing_switch
import dev.jamesrich.sofaking.resources.pairing_switch_cancel
import dev.jamesrich.sofaking.resources.pairing_switch_message
import dev.jamesrich.sofaking.resources.pairing_switch_title
import dev.jamesrich.sofaking.ui.sectionHeading
import dev.jamesrich.sofaking.ui.theme.SofaButton
import org.jetbrains.compose.resources.stringResource

/**
 * Asks before a pairing link moves this device from [current] to [requested] (both as `host:port` labels). Focus starts
 * on Cancel, so a stray OK press on a TV keeps the saved server; Back and a tap outside also cancel.
 */
@Composable
fun SwitchServerDialog(requested: String, current: String, onSwitch: () -> Unit, onCancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.widthIn(max = 480.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(Res.string.pairing_switch_title, requested),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.sectionHeading(),
                )
                Text(
                    stringResource(Res.string.pairing_switch_message, current),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SofaButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus), main = true) {
                        Text(stringResource(Res.string.pairing_switch_cancel))
                    }
                    SofaButton(onClick = onSwitch) { Text(stringResource(Res.string.pairing_switch)) }
                }
            }
        }
    }
}
