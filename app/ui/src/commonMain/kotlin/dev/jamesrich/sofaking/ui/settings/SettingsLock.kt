package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.maxLengthReject
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.ServerAddress
import dev.jamesrich.sofaking.data.SettingsPin
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.TvTextField
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaButton
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * What the lock page remembers between tries: after [MAX_MISSES] wrong PINs in a row, Unlock waits [WAIT]
 * before the next try. One lives for the process (App holds it), so backing out to the guide does not reset
 * it; a restart does, which is fine for a lock meant to keep a child out, not an attacker.
 */
internal class SettingsLock(private val timeSource: TimeSource = TimeSource.Monotonic) {
    var misses by mutableIntStateOf(0)
        private set

    /** When the current wait ends, or null while there is none. */
    var waitEnds by mutableStateOf<TimeMark?>(null)
        private set

    val waiting: Boolean get() = waitEnds?.hasNotPassedNow() == true

    /** How much of the wait is left, zero while there is none. */
    val remaining: Duration get() = waitEnds?.let { -it.elapsedNow() }?.takeIf { it.isPositive() } ?: Duration.ZERO

    /** A wrong PIN; the [MAX_MISSES]th in a row starts the wait and the count starts over. */
    fun missed() {
        misses++
        if (misses >= MAX_MISSES) {
            misses = 0
            waitEnds = timeSource.markNow() + WAIT
        }
    }

    /** The right PIN, or the lock removed. */
    fun passed() {
        misses = 0
        waitEnds = null
    }

    companion object {
        const val MAX_MISSES = 5
        val WAIT = 30.seconds
    }
}

/**
 * The page in front of Settings while it is locked: the PIN field, Unlock and Back, and under Forgot the PIN
 * the server's API token as the way out, since the admin can read it in the portal and the device already holds
 * it. Nothing of Settings composes behind it, so there is no disabled row for a D-pad to land on.
 */
@Composable
internal fun SettingsLockScreen(
    /** The stored [SettingsPin.hash]. */
    pinHash: String,
    /** The token the device holds, empty on a server without one; it unlocks and removes the PIN. */
    apiToken: String,
    onUnlocked: () -> Unit,
    /** The token matched: remove the PIN; the caller then opens Settings. */
    onPinRemoved: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    lock: SettingsLock = remember { SettingsLock() },
) {
    val pin = rememberTextFieldState()
    val token = rememberTextFieldState()
    val pinFocus = remember { FocusRequester() }
    val tokenFocus = remember { FocusRequester() }
    var wrongPin by remember { mutableStateOf(false) }
    var wrongToken by remember { mutableStateOf(false) }
    var forgot by remember { mutableStateOf(false) }
    // The wait is wall time, so the page re-reads it when it ends rather than on the next key press.
    var waiting by remember { mutableStateOf(lock.waiting) }
    LaunchedEffect(lock.waitEnds) {
        waiting = lock.waiting
        while (lock.waiting) {
            delay(lock.remaining)
            waiting = lock.waiting
        }
    }
    val pinText = pin.text.toString()
    val canUnlock = SettingsPin.isValid(pinText) && !waiting
    val unlock = {
        if (canUnlock) {
            if (SettingsPin.matches(pinText, pinHash)) {
                lock.passed()
                onUnlocked()
            } else {
                lock.missed()
                wrongPin = true
                pin.clearText()
            }
        }
    }
    val tokenText = ServerAddress.normalizeToken(token.text.toString())
    val canRemove = apiToken.isNotEmpty() && tokenText != null
    val remove = {
        if (canRemove) {
            if (tokenText == apiToken) {
                lock.passed()
                onPinRemoved()
            } else {
                wrongToken = true
                token.clearText()
            }
        }
    }

    BackHandler { onBack() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(pinFocus) }
    LaunchedEffect(forgot) { if (forgot && apiToken.isNotEmpty()) requestFocusWhenAttached(tokenFocus) }

    LazyColumn(
        modifier = modifier.fillMaxSize().screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenTitle(stringResource(Res.string.lock_title), onBack) }
        item { HelpText(stringResource(Res.string.lock_help)) }
        item {
            Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.lock_pin), style = MaterialTheme.typography.titleSmall)
                PinField(pin, pinFocus, ImeAction.Done, onAction = unlock)
                when {
                    waiting -> ErrorText(stringResource(Res.string.lock_wait, SettingsLock.WAIT.inWholeSeconds))
                    wrongPin -> ErrorText(stringResource(Res.string.lock_wrong))
                }
                Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SofaButton(onClick = unlock, enabled = canUnlock) { Text(stringResource(Res.string.lock_unlock)) }
                    SofaButton(onClick = { forgot = !forgot }) { Text(stringResource(Res.string.lock_forgot)) }
                }
            }
        }
        if (forgot) {
            item {
                Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (apiToken.isEmpty()) {
                        HelpText(stringResource(Res.string.lock_forgot_no_token))
                    } else {
                        HelpText(stringResource(Res.string.lock_forgot_help))
                        Text(stringResource(Res.string.settings_api_token), style = MaterialTheme.typography.titleSmall)
                        TvTextField(
                            state = token,
                            focusRequester = tokenFocus,
                            imeAction = ImeAction.Done,
                            onAction = remove,
                            maskUnfocused = ::maskToken,
                        )
                        if (wrongToken) ErrorText(stringResource(Res.string.lock_forgot_wrong))
                        SofaButton(onClick = remove, enabled = canRemove) { Text(stringResource(Res.string.lock_remove)) }
                    }
                }
            }
        }
    }
}

/**
 * A PIN's Material 3 secure field: the number pad, at most [SettingsPin.LENGTH] characters, and every digit hidden,
 * even the one just typed, so the PIN never shows on a shared screen.
 */
@Composable
private fun PinField(state: TextFieldState, focusRequester: FocusRequester, imeAction: ImeAction, onAction: () -> Unit) {
    OutlinedSecureTextField(
        state = state,
        modifier = Modifier.focusRequester(focusRequester),
        textStyle = MaterialTheme.typography.bodyLarge,
        inputTransformation = InputTransformation.maxLengthReject(SettingsPin.LENGTH),
        textObfuscationMode = TextObfuscationMode.Hidden,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        onKeyboardAction = KeyboardActionHandler { onAction() },
    )
}

@Composable
internal fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.width(FIELD_WIDTH))
}

/**
 * The Lock section of Settings: Set a PIN opens two fields that must agree, and with a PIN set, Remove PIN
 * takes it off. [onPinChanged] gets the new digits, or null to remove the lock.
 */
@Composable
internal fun LockSection(locked: Boolean, onPinChanged: (String?) -> Unit) {
    var setting by remember { mutableStateOf(false) }
    val first = rememberTextFieldState()
    val again = rememberTextFieldState()
    val firstFocus = remember { FocusRequester() }
    val againFocus = remember { FocusRequester() }
    LaunchedEffect(setting) { if (setting) requestFocusWhenAttached(firstFocus) }
    val firstText = first.text.toString()
    val againText = again.text.toString()
    val canSet = SettingsPin.isValid(firstText) && firstText == againText
    val set = {
        if (canSet) {
            onPinChanged(firstText)
            first.clearText()
            again.clearText()
            setting = false
        }
    }

    Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            locked -> {
                HelpText(stringResource(Res.string.settings_lock_on))
                SofaButton(onClick = { onPinChanged(null) }) { Text(stringResource(Res.string.settings_lock_remove)) }
            }
            !setting -> {
                HelpText(stringResource(Res.string.settings_lock_help))
                SofaButton(onClick = { setting = true }) { Text(stringResource(Res.string.settings_lock_set)) }
            }
            else -> {
                Text(stringResource(Res.string.settings_lock_pin), style = MaterialTheme.typography.titleSmall)
                PinField(first, firstFocus, ImeAction.Next, onAction = { againFocus.requestFocus() })
                if (firstText.isNotEmpty() && !SettingsPin.isValid(firstText)) ErrorText(stringResource(Res.string.settings_lock_pin_invalid))
                Text(stringResource(Res.string.settings_lock_pin_again), style = MaterialTheme.typography.titleSmall)
                PinField(again, againFocus, ImeAction.Done, onAction = set)
                if (againText.isNotEmpty() && SettingsPin.isValid(firstText) && againText != firstText) ErrorText(stringResource(Res.string.settings_lock_pin_mismatch))
                Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SofaButton(onClick = set, enabled = canSet) { Text(stringResource(Res.string.settings_lock_confirm)) }
                    SofaButton(onClick = { setting = false; first.clearText(); again.clearText() }) { Text(stringResource(Res.string.settings_lock_cancel)) }
                }
            }
        }
    }
}
