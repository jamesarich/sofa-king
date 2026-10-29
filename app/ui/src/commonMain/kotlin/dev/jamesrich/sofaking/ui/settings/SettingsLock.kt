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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.PinThrottle
import dev.jamesrich.sofaking.data.ServerAddress
import dev.jamesrich.sofaking.data.SettingsPin
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.lock_forgot
import dev.jamesrich.sofaking.resources.lock_forgot_help
import dev.jamesrich.sofaking.resources.lock_forgot_no_token
import dev.jamesrich.sofaking.resources.lock_forgot_wrong
import dev.jamesrich.sofaking.resources.lock_help
import dev.jamesrich.sofaking.resources.lock_pin
import dev.jamesrich.sofaking.resources.lock_remove
import dev.jamesrich.sofaking.resources.lock_title
import dev.jamesrich.sofaking.resources.lock_unlock
import dev.jamesrich.sofaking.resources.lock_wait
import dev.jamesrich.sofaking.resources.lock_wrong
import dev.jamesrich.sofaking.resources.settings_api_token
import dev.jamesrich.sofaking.resources.settings_kids_help
import dev.jamesrich.sofaking.resources.settings_kids_on
import dev.jamesrich.sofaking.resources.settings_kids_turn_off
import dev.jamesrich.sofaking.resources.settings_kids_turn_on
import dev.jamesrich.sofaking.resources.settings_lock_cancel
import dev.jamesrich.sofaking.resources.settings_lock_confirm
import dev.jamesrich.sofaking.resources.settings_lock_help
import dev.jamesrich.sofaking.resources.settings_lock_on
import dev.jamesrich.sofaking.resources.settings_lock_pin
import dev.jamesrich.sofaking.resources.settings_lock_pin_again
import dev.jamesrich.sofaking.resources.settings_lock_pin_invalid
import dev.jamesrich.sofaking.resources.settings_lock_pin_mismatch
import dev.jamesrich.sofaking.resources.settings_lock_remove
import dev.jamesrich.sofaking.resources.settings_lock_set
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.TvTextField
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaButton
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The lock page's [PinThrottle] with the wait as Compose state, so the page recomposes when it starts or ends. One
 * lives for the process (App holds it), so backing out to the guide does not reset it.
 */
internal class SettingsLock(timeSource: TimeSource = TimeSource.Monotonic) {
    private val throttle = PinThrottle(timeSource)

    /** When the current wait ends, or null while there is none. */
    var waitEnds by mutableStateOf<TimeMark?>(null)
        private set

    val waiting: Boolean get() = throttle.waiting

    /** How much of the wait is left, zero while there is none. */
    val remaining: Duration get() = throttle.remaining

    /** A wrong PIN; the [PinThrottle.MAX_MISSES]th in a row starts the wait. */
    fun missed() {
        throttle.missed()
        waitEnds = throttle.waitEnds
    }

    /** The right PIN, or the lock removed. */
    fun passed() {
        throttle.passed()
        waitEnds = null
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
    onUnlock: () -> Unit,
    /** The token matched: remove the PIN; the caller then opens Settings. */
    onPinRemove: () -> Unit,
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
    val waiting = rememberWaiting(lock)
    val pinText = pin.text.toString()
    val canUnlock = SettingsPin.isValid(pinText) && !waiting
    val unlock = {
        if (canUnlock) {
            if (SettingsPin.matches(pinText, pinHash)) {
                lock.passed()
                onUnlock()
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
                onPinRemove()
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
            val error = when {
                waiting -> stringResource(Res.string.lock_wait, PinThrottle.WAIT_SECONDS)
                wrongPin -> stringResource(Res.string.lock_wrong)
                else -> null
            }
            PinEntry(pin, pinFocus, error, canUnlock, unlock, onRecover = { forgot = !forgot })
        }
        if (forgot) {
            item { ForgotPin(apiToken.isNotEmpty(), token, tokenFocus, wrongToken, canRemove, remove) }
        }
    }
}

/** Whether the lock makes the viewer wait. The wait is wall time, so this reads it again when it ends. */
@Composable
private fun rememberWaiting(lock: SettingsLock): Boolean {
    var waiting by remember { mutableStateOf(lock.waiting) }
    LaunchedEffect(lock.waitEnds) {
        waiting = lock.waiting
        while (lock.waiting) {
            delay(lock.remaining)
            waiting = lock.waiting
        }
    }
    return waiting
}

/** The PIN field, the reason the last try failed ([error]), and Unlock and Forgot, which [onRecover] answers. */
@Composable
private fun PinEntry(
    pin: TextFieldState,
    pinFocus: FocusRequester,
    error: String?,
    canUnlock: Boolean,
    unlock: () -> Unit,
    onRecover: () -> Unit,
) {
    Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.lock_pin), style = MaterialTheme.typography.titleSmall)
        PinField(pin, pinFocus, ImeAction.Done, onAction = unlock)
        if (error != null) ErrorText(error)
        Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SofaButton(main = true, onClick = unlock, enabled = canUnlock) {
                Text(stringResource(Res.string.lock_unlock))
            }
            SofaButton(onClick = onRecover) { Text(stringResource(Res.string.lock_forgot)) }
        }
    }
}

/** Forgot the PIN: the API token field that removes it, or a line saying the server has no token ([hasToken]). */
@Composable
private fun ForgotPin(
    hasToken: Boolean,
    token: TextFieldState,
    tokenFocus: FocusRequester,
    wrongToken: Boolean,
    canRemove: Boolean,
    remove: () -> Unit,
) {
    Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!hasToken) {
            HelpText(stringResource(Res.string.lock_forgot_no_token))
            return@Column
        }
        HelpText(stringResource(Res.string.lock_forgot_help))
        Text(stringResource(Res.string.settings_api_token), style = MaterialTheme.typography.titleSmall)
        TvTextField(
            state = token,
            focusRequester = tokenFocus,
            imeAction = ImeAction.Done,
            onAction = remove,
            maskUnfocused = ::maskToken,
            keyboardType = KeyboardType.Password,
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
        )
        if (wrongToken) ErrorText(stringResource(Res.string.lock_forgot_wrong))
        SofaButton(onClick = remove, enabled = canRemove) {
            Text(stringResource(Res.string.lock_remove))
        }
    }
}

/**
 * A PIN's Material 3 secure field: the number pad, at most [SettingsPin.LENGTH] characters, and every digit hidden,
 * even the one just typed, so the PIN never shows on a shared screen.
 */
@Composable
private fun PinField(
    state: TextFieldState,
    focusRequester: FocusRequester,
    imeAction: ImeAction,
    onAction: () -> Unit,
) {
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
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.width(FIELD_WIDTH),
    )
}

/**
 * The Lock section of Settings: Set a PIN opens two fields that must agree, and with a PIN set, Remove PIN
 * takes it off. [onPinChange] gets the new digits, or null to remove the lock. With a PIN set, kids mode turns on
 * and off through [onKidsModeChange].
 */
@Composable
internal fun LockSection(settings: AppSettings, onPinChange: (String?) -> Unit, onKidsModeChange: (Boolean) -> Unit) {
    val locked = settings.settingsLocked
    var setting by remember { mutableStateOf(false) }
    val first = rememberTextFieldState()
    val again = rememberTextFieldState()
    val firstFocus = remember { FocusRequester() }
    val againFocus = remember { FocusRequester() }
    // Set, Remove, Confirm and Cancel each swap the section's controls, taking the focused button away; the button the
    // section shows next takes focus, again once the stored lock catches up, since that swaps them once more.
    val buttonFocus = remember { FocusRequester() }
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(setting, locked) {
        if (setting) {
            requestFocusWhenAttached(firstFocus)
        } else if (pressed) {
            requestFocusWhenAttached(buttonFocus)
        }
    }
    val firstText = first.text.toString()
    val againText = again.text.toString()
    val canSet = SettingsPin.isValid(firstText) && firstText == againText
    val set = {
        if (canSet) {
            onPinChange(firstText)
            first.clearText()
            again.clearText()
            pressed = true
            setting = false
        }
    }

    Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            locked -> {
                HelpText(stringResource(Res.string.settings_lock_on))
                SofaButton(onClick = {
                    pressed = true
                    onPinChange(null)
                }, modifier = Modifier.focusRequester(buttonFocus)) {
                    Text(stringResource(Res.string.settings_lock_remove))
                }
                KidsModeRow(settings.kidsMode, onKidsModeChange)
            }

            !setting -> {
                HelpText(stringResource(Res.string.settings_lock_help))
                SofaButton(onClick = {
                    setting = true
                }, modifier = Modifier.focusRequester(buttonFocus)) {
                    Text(stringResource(Res.string.settings_lock_set))
                }
            }

            else -> NewPinFields(first, again, firstFocus, againFocus, canSet, set) {
                pressed = true
                setting = false
                first.clearText()
                again.clearText()
            }
        }
    }
}

/** Kids mode's help and the button that turns it on or off. */
@Composable
private fun KidsModeRow(kidsMode: Boolean, onChange: (Boolean) -> Unit) {
    HelpText(stringResource(if (kidsMode) Res.string.settings_kids_on else Res.string.settings_kids_help))
    SofaButton(onClick = { onChange(!kidsMode) }) {
        Text(stringResource(if (kidsMode) Res.string.settings_kids_turn_off else Res.string.settings_kids_turn_on))
    }
}

/** The new PIN twice, each with what is wrong with it, then Confirm ([set]) and Cancel. */
@Composable
private fun NewPinFields(
    first: TextFieldState,
    again: TextFieldState,
    firstFocus: FocusRequester,
    againFocus: FocusRequester,
    canSet: Boolean,
    set: () -> Unit,
    onCancel: () -> Unit,
) {
    val firstText = first.text.toString()
    val againText = again.text.toString()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.settings_lock_pin), style = MaterialTheme.typography.titleSmall)
        PinField(first, firstFocus, ImeAction.Next, onAction = { againFocus.requestFocus() })
        if (firstText.isNotEmpty() && !SettingsPin.isValid(firstText)) {
            ErrorText(stringResource(Res.string.settings_lock_pin_invalid))
        }
        Text(stringResource(Res.string.settings_lock_pin_again), style = MaterialTheme.typography.titleSmall)
        PinField(again, againFocus, ImeAction.Done, onAction = set)
        if (againText.isNotEmpty() && SettingsPin.isValid(firstText) && againText != firstText) {
            ErrorText(stringResource(Res.string.settings_lock_pin_mismatch))
        }
        Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SofaButton(main = true, onClick = set, enabled = canSet) {
                Text(stringResource(Res.string.settings_lock_confirm))
            }
            SofaButton(onClick = onCancel) { Text(stringResource(Res.string.settings_lock_cancel)) }
        }
    }
}
