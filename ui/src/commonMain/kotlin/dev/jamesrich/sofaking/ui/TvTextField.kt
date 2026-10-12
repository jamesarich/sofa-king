package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * Single-line text entry with a border that thickens in the primary color when focused. [maskUnfocused]
 * rewrites what is shown while the field is not being edited (a secret's last characters); the text itself
 * and the edited view are untouched.
 */
@Composable
internal fun TvTextField(
    state: TextFieldState,
    focusRequester: FocusRequester,
    imeAction: ImeAction,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    maskUnfocused: ((String) -> String)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val mask = if (focused || maskUnfocused == null) null else remember(maskUnfocused) { MaskTransformation(maskUnfocused) }
    val borderColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val borderWidth = if (focused) 4.dp else 1.dp
    Surface(
        modifier = modifier.onFocusChanged { focused = it.hasFocus },
        shape = RoundedCornerShape(8.dp),
        border = Border(border = BorderStroke(borderWidth, borderColor)),
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            BasicTextField(
                state = state,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                outputTransformation = mask,
                onKeyboardAction = KeyboardActionHandler { onAction() },
                decorator = TextFieldDecorator { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (hint != null && state.text.isEmpty()) {
                            Text(
                                hint,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
    }
}

private class MaskTransformation(private val mask: (String) -> String) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        replace(0, length, mask(asCharSequence().toString()))
    }
}
