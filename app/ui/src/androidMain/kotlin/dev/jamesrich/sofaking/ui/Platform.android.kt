package dev.jamesrich.sofaking.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.provider.Settings
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
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleOwner
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PublishedBuild
import dev.jamesrich.sofaking.data.UpdateInstaller
import dev.jamesrich.sofaking.data.appUpdateInstaller
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.MatchExoDisplayFrameRate

@Composable
internal actual fun rememberIsTelevision(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.isTelevision() }
}

/** A TV by its UI mode or its leanback feature; the layout and the platform the server is told both ask this. */
fun Context.isTelevision(): Boolean =
    getSystemService<UiModeManager>()?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

/** The system's "Use 24-hour format" setting; read once, as the zone is, so the guide does not reformat under the user. */
@Composable
internal actual fun rememberSystem24HourClock(): Boolean {
    val context = LocalContext.current
    return remember(context) { android.text.format.DateFormat.is24HourFormat(context) }
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
        currentOnResult(
            voiceQuery(
                result.resultCode == Activity.RESULT_OK,
                result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS),
            ),
        )
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

/** The system's captioning preferences; a build without that activity (some set-top boxes) gets no row. */
@Composable
internal actual fun rememberSystemCaptionSettings(): (() -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        val intent = Intent(Settings.ACTION_CAPTIONING_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            null
        } else {
            {
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
    }
}

internal actual fun captionEngineName(): String = "Media3"

internal actual val supportsPlaybackPreferences: Boolean = true

internal actual val platformMovesFocusOnArrows: Boolean = true

internal actual val supportsCaptionSync: Boolean = true

internal actual val pageServerUrl: String? = null

@Composable
internal actual fun MatchDisplayFrameRate(player: LivePlayer, enabled: Boolean) =
    MatchExoDisplayFrameRate(player, enabled)

internal actual fun updateDownloadUrl(latest: PublishedBuild): String? = latest.apkUrl ?: latest.pageUrl

@Composable
internal actual fun rememberUpdateInstaller(): UpdateInstaller? {
    val context = LocalContext.current
    return remember(context) { appUpdateInstaller(context) }
}

@Composable
internal actual fun rememberAppLifecycleOwner(host: LifecycleOwner): LifecycleOwner = host

@Composable
internal actual fun ReleaseOnPageExit(sessions: PlaybackSessions) = Unit
