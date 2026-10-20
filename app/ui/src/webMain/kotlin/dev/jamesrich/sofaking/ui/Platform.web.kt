package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.jamesrich.sofaking.ui.player.LivePlayer
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import dev.jamesrich.sofaking.data.PublishedBuild

/** A browser tab, sized by its window. */
@Composable
internal actual fun rememberIsTelevision(): Boolean = false

/** The browser locale's hour cycle, as Intl resolves it. */
@Composable
internal actual fun rememberSystem24HourClock(): Boolean = remember { !localeUsesTwelveHourClock() }

private fun localeUsesTwelveHourClock(): Boolean =
    js("(function () { try { var o = new Intl.DateTimeFormat(undefined, { hour: 'numeric' }).resolvedOptions(); return o.hour12 === true || o.hourCycle === 'h12' || o.hourCycle === 'h11'; } catch (e) { return true; } })()")

/** Browsers do not report a touch-exploring screen reader; the preview may open full screen. */
@Composable
internal actual fun rememberScreenReaderActive(): Boolean = false

/** Holds a screen wake lock while [on]; see [screenWake]. */
@Composable
internal actual fun KeepScreenOn(on: Boolean) {
    DisposableEffect(on) {
        val hold = if (on) screenWake.hold() else null
        onDispose { hold?.close() }
    }
}

/** The guide opens its text field; the Web Speech API is not wired in. */
@Composable
internal actual fun rememberVoiceSearch(prompt: String, onResult: (String?) -> Unit): () -> Boolean = { false }

internal actual val KeyEvent.repeatCountOrNull: Int? get() = null

internal actual val KeyEvent.isLongPress: Boolean get() = false

@Composable
internal actual fun rememberSystemCaptionSettings(): (() -> Unit)? = null

internal actual fun captionEngineName(): String = "the browser's video element"

internal actual val supportsPlaybackPreferences: Boolean = false

internal actual val supportsCaptionSync: Boolean = false

@Composable
internal actual fun MatchDisplayFrameRate(player: LivePlayer, enabled: Boolean) = Unit

internal actual fun updateDownloadUrl(latest: PublishedBuild): String? = latest.pageUrl
