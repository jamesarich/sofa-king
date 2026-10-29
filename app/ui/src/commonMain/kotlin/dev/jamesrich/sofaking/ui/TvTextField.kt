package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * Single-line Material 3 text entry whose outline thickens in the primary color when focused. [maskUnfocused]
 * rewrites what is shown while the field is not being edited (a secret's last characters); the text itself is
 * untouched. [keyboardType], [capitalization] and [autoCorrect] steer the on-screen keyboard; left unset they follow
 * the platform's default.
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
    keyboardType: KeyboardType = KeyboardType.Unspecified,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Unspecified,
    autoCorrect: Boolean? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val mask = if (focused || maskUnfocused == null) {
        null
    } else {
        remember(maskUnfocused) { MaskTransformation(maskUnfocused) }
    }
    OutlinedTextField(
        state = state,
        modifier = modifier.focusRequester(focusRequester).onFocusChanged { focused = it.hasFocus },
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = hint?.let { { Text(it, style = MaterialTheme.typography.bodyLarge) } },
        lineLimits = TextFieldLineLimits.SingleLine,
        outputTransformation = mask,
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            autoCorrectEnabled = autoCorrect,
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        onKeyboardAction = KeyboardActionHandler { onAction() },
    )
}

private class MaskTransformation(private val mask: (String) -> String) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        replace(0, length, mask(asCharSequence().toString()))
    }
}
