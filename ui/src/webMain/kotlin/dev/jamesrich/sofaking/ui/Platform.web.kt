package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent

/** Browsers do not report a touch-exploring screen reader; the preview may open full screen. */
@Composable
internal actual fun rememberScreenReaderActive(): Boolean = false

/** Browsers keep the display awake while a video element plays. */
@Composable
internal actual fun KeepScreenOn(on: Boolean) = Unit

/** The guide opens its text field; the Web Speech API is not wired in. */
@Composable
internal actual fun rememberVoiceSearch(prompt: String, onResult: (String?) -> Unit): () -> Boolean = { false }

internal actual val KeyEvent.repeatCountOrNull: Int? get() = null

internal actual val KeyEvent.isLongPress: Boolean get() = false
