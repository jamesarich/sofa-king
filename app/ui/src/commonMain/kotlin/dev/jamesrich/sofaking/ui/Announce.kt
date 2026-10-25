package dev.jamesrich.sofaking.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * A toggle whose label says its state, such as Record and Cancel recording: TalkBack and VoiceOver read the new
 * label when a press changes it, so the viewer hears that it took without moving focus.
 */
internal fun Modifier.announcesChanges(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }
