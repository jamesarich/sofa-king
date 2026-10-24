package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import dev.jamesrich.sofaking.data.PlaybackSessions

/**
 * Opens the platform's own caption settings (Android's captioning preferences, the app's page in iOS Settings),
 * or null where there is no such page, so Settings leaves the row out.
 */
@Composable
internal expect fun rememberSystemCaptionSettings(): (() -> Unit)?

/** The engine that draws captions on this platform, named for the Settings help text. */
internal expect fun captionEngineName(): String

/**
 * True where the engine honors [dev.jamesrich.sofaking.data.PlaybackPreferences] (Android's Media3); Settings hides
 * the Playback section elsewhere.
 */
internal expect val supportsPlaybackPreferences: Boolean

/**
 * True where captions are drawn through `CaptionSync` (Android's Media3), so smoothing and timing apply;
 * Settings hides those rows elsewhere.
 */
internal expect val supportsCaptionSync: Boolean

/**
 * While [enabled], switches the display to a mode that matches the frame rate of the video [player] plays, and
 * restores the default when it leaves composition or is disabled; nothing happens on platforms without display modes.
 */
@Composable
internal expect fun MatchDisplayFrameRate(player: dev.jamesrich.sofaking.ui.player.LivePlayer, enabled: Boolean)

/**
 * Frees the session [sessions] holds as a browser tab closes, which would otherwise keep its lease until it runs
 * out: the stop's own release never leaves a closing page. Nothing elsewhere.
 */
@Composable
internal expect fun ReleaseOnPageExit(sessions: PlaybackSessions)
