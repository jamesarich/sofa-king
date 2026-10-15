package dev.jamesrich.sofaking.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import dev.jamesrich.sofaking.ui.guide.voiceQuery
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration

@Composable
internal actual fun rememberIsTelevision(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}

/** TalkBack's touch exploration, followed while the screen is up: it can be switched on over an open guide. */
@Composable
internal actual fun rememberScreenReaderActive(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var active by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { active = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return active
}

@Composable
internal actual fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** The TV's speech recognizer activity; the manifest's `<queries>` entry lets the app see whether one is installed. */
@Composable
internal actual fun rememberVoiceSearch(prompt: String, onResult: (String?) -> Unit): () -> Boolean {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        currentOnResult(voiceQuery(result.resultCode == Activity.RESULT_OK, result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)))
    }
    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        if (intent.resolveActivity(context.packageManager) == null) {
            false
        } else {
            try {
                launcher.launch(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }
}

internal actual val KeyEvent.repeatCountOrNull: Int? get() = nativeKeyEvent.repeatCount

internal actual val KeyEvent.isLongPress: Boolean get() = nativeKeyEvent.isLongPress
