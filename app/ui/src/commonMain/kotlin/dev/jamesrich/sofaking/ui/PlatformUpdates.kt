package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import dev.jamesrich.sofaking.data.PublishedBuild
import dev.jamesrich.sofaking.data.UpdateInstaller

/**
 * What Get the update opens for a published [latest] build: the Android package on Android, the release page on a
 * desktop or in a browser, or null where no package is published (iOS), so Settings says so instead.
 */
internal expect fun updateDownloadUrl(latest: PublishedBuild): String?

/**
 * Installs a published build from inside the app: on Android, unless an app store installed it; null elsewhere,
 * where Get the update opens [updateDownloadUrl] instead.
 */
@Composable
internal expect fun rememberUpdateInstaller(): UpdateInstaller?

/**
 * The address Settings offers while none is saved: the web page's own origin, which serves the
 * API beside it; null elsewhere.
 */
internal expect val pageServerUrl: String?

/**
 * Whether this app came with the server and updates with it, so Settings checks GitHub for no newer build: the web app
 * the server serves at `/app/`; false elsewhere.
 */
internal expect val updatesWithServer: Boolean
