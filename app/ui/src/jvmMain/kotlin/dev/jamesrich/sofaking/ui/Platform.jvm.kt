package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent

/** Desktop screen readers do not explore by touch; the preview may open full screen. */
@Composable
internal actual fun rememberScreenReaderActive(): Boolean = false

/** Desktops keep the display awake during video playback through the OS's own media heuristics. */
@Composable
internal actual fun KeepScreenOn(on: Boolean) = Unit

/** No speech recognizer on the JVM; the guide opens its text field. */
@Composable
internal actual fun rememberVoiceSearch(prompt: String, onResult: (String?) -> Unit): () -> Boolean = { false }

internal actual val KeyEvent.repeatCountOrNull: Int? get() = null

internal actual val KeyEvent.isLongPress: Boolean get() = false

/** A desktop window keeps its title bar; full screen is the window manager's. */
@Composable
internal actual fun HideSystemBars(on: Boolean) = Unit
