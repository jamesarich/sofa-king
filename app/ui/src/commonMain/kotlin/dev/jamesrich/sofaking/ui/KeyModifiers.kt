package dev.jamesrich.sofaking.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key

/** The letters a player takes as shortcuts: C captions, K play and pause, M mute, F full screen. */
internal fun Key.isLetterShortcut(): Boolean = this == Key.C || this == Key.K || this == Key.M || this == Key.F

/**
 * True when a letter shortcut is pressed with Ctrl, Meta or Alt held: Ctrl+C, Cmd+F and the like belong to the browser
 * or the system, so the player leaves them alone.
 */
internal fun blocksLetterShortcut(key: Key, ctrl: Boolean, meta: Boolean, alt: Boolean): Boolean =
    (ctrl || meta || alt) && key.isLetterShortcut()

internal fun KeyEvent.blocksLetterShortcut(): Boolean =
    blocksLetterShortcut(key, isCtrlPressed, isMetaPressed, isAltPressed)
