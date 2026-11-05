package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import dev.jamesrich.sofaking.data.PublishedBuild
import dev.jamesrich.sofaking.data.UpdateInstaller

/**
 * What Get the update opens for a published [latest] build: the Android package on Android, the release page on a
 * desktop, or null where no package is published, so Settings says so instead.
 */
internal expect fun updateDownloadUrl(latest: PublishedBuild): String?

/**
 * Installs a published build from inside the app: on Android, unless an app store installed it; null elsewhere,
 * where Get the update opens [updateDownloadUrl] instead.
 */
@Composable
internal expect fun rememberUpdateInstaller(): UpdateInstaller?
